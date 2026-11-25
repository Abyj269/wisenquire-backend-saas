package com.leads.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leads.config.RequestTraceFilter;
import com.leads.config.TenantContext;
import com.leads.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 01 Part B item 9: writes {@code audit_log} for POST/PUT/PATCH/DELETE on {@link Audited} controllers.
 * <ul>
 *   <li>Actor and organization come from the verified JWT ({@link TenantContext}), never from the body.</li>
 *   <li>{@code after_json} is the request body with secret-looking fields masked and long values cut;
 *       {@code before_json} stays empty until a module records the previous state.</li>
 *   <li>Refusals are recorded too (outcome DENIED); other errors are FAILED.</li>
 *   <li>Written in its own transaction after the call. If writing fails it is logged and the user's
 *       action is NOT affected.</li>
 *   <li>Kill switch: {@code app.audit.enabled=false}.</li>
 * </ul>
 * The IP comes from {@link com.leads.config.ClientIpResolver} (not spoofable via X-Forwarded-For).
 */
@Slf4j
@Aspect
@Component
// Outermost: wraps method security, so @PreAuthorize refusals are recorded as DENIED.
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditAspect {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String[] SECRET_HINTS = {"password", "token", "secret", "apikey", "api_key", "credential", "otp", "privatekey", "private_key"};
    private static final int MAX_VALUE = 2_000;
    private static final int MAX_JSON = 20_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final ObjectMapper json;
    private final boolean enabled;
    private final com.leads.config.ClientIpResolver clientIp;

    public AuditAspect(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectMapper json,
                       @Value("${app.audit.enabled:true}") boolean enabled, com.leads.config.ClientIpResolver clientIp) {
        this.clientIp = clientIp;
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.enabled = enabled;
    }

    @Around("@within(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (!enabled || request == null || !AUDITED_METHODS.contains(request.getMethod())) return pjp.proceed();
        String outcome = "SUCCESS";
        int status = 200;
        try {
            Object result = pjp.proceed();
            if (result instanceof ResponseEntity<?> r) status = r.getStatusCode().value();
            return result;
        } catch (AccessDeniedException e) {
            outcome = "DENIED";
            status = 403;
            throw e;
        } catch (ApiException e) {
            status = e.getCode().status.value();
            outcome = Set.of(401, 402, 403, 404, 423).contains(status) ? "DENIED" : "FAILED";
            throw e;
        } catch (Throwable e) {
            outcome = "FAILED";
            status = e instanceof IllegalArgumentException ? 400 : e instanceof IllegalStateException ? 409 : 500;
            throw e;
        } finally {
            write(pjp, audited, request, outcome, status);
        }
    }

    private void write(ProceedingJoinPoint pjp, Audited audited, HttpServletRequest request, String outcome, int status) {
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String action = request.getMethod() + " " + (pattern != null ? pattern : request.getRequestURI());
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            String entityId = null;
            String body = null;
            Annotation[][] annotations = method.getParameterAnnotations();
            Object[] args = pjp.getArgs();
            for (int i = 0; i < args.length; i++) {
                for (Annotation a : annotations[i]) {
                    if (a instanceof PathVariable pv && entityId == null && args[i] != null) {
                        String name = pv.value().isEmpty() ? pv.name() : pv.value();
                        if (name.isEmpty()) name = method.getParameters()[i].getName();
                        if (name.equals("id") || name.endsWith("Id")) entityId = String.valueOf(args[i]);
                    }
                    if (a instanceof RequestBody && args[i] != null) body = masked(args[i]);
                }
            }
            var ctx = TenantContext.get();
            Long org = ctx.map(TenantContext.Data::organizationId).orElse(null);
            Long actor = ctx.map(TenantContext.Data::userId).orElse(null);
            String trace = MDC.get(RequestTraceFilter.MDC_TRACE_ID);
            String fEntityId = entityId, fBody = body;
            newTx.executeWithoutResult(s -> jdbc.update(
                    "INSERT INTO audit_log (organization_id, actor_user_id, action, entity_type, entity_id, after_json, outcome, http_status, ip, trace_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    org, actor, truncate(action, 200), audited.entity(), fEntityId, fBody, outcome, status,
                    truncate(clientIp == null ? request.getRemoteAddr() : clientIp.resolve(request), 64), trace));
        } catch (RuntimeException e) {
            log.error("Could not write the audit record for {} {}", request.getMethod(), request.getRequestURI(), e);
        }
    }

    /** The body as JSON with secret-looking fields replaced by "***" and long strings cut. */
    String masked(Object body) {
        try {
            JsonNode tree = json.valueToTree(body);
            mask(tree);
            return truncate(json.writeValueAsString(tree), MAX_JSON);
        } catch (Exception e) {
            return null;
        }
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (isSecret(f.getKey()) && !f.getValue().isNull()) f.setValue(TextNode.valueOf("***"));
                else if (f.getValue().isTextual() && f.getValue().asText().length() > MAX_VALUE) f.setValue(TextNode.valueOf("<" + f.getValue().asText().length() + " chars>"));
                else mask(f.getValue());
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(AuditAspect::mask);
        }
    }

    static boolean isSecret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String hint : SECRET_HINTS) if (k.contains(hint)) return true;
        return false;
    }

    private static String truncate(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }
}
