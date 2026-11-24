package com.leads.exception;

import com.leads.config.TenantContext;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D12 error contract: every error body is {@code {code, message, details, traceId}}
 * (see {@link ErrorCode}). {@code message} keeps its old meaning, so the existing frontend parser
 * ({@code api.service.ts}) keeps working.
 * <p>
 * Every handler returns a message written for end users. The {@link Exception} catch-all exists so
 * that a stray NullPointerException, SQL text or stack trace never reaches the client: the real
 * exception is logged server-side, and only a generic message crosses the wire.
 * <p>
 * Missing vs out-of-scope records (F-9): with flag {@code errors.uniform_not_found} OFF (default) the
 * old statuses are kept: missing 400, out of scope 403. With the flag ON for the caller's org both are
 * 404 NOT_FOUND with the same message, so the response no longer tells whether the record exists.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String NOT_FOUND_MESSAGE = "Record not found.";
    static final String FORBIDDEN_MESSAGE = "You don't have permission to do this.";

    private final ObjectProvider<FeatureFlags> flags;

    /** Plain construction (standalone MockMvc, unit tests): every flag OFF, i.e. the old statuses. */
    public GlobalExceptionHandler() { this.flags = null; }

    @Autowired
    public GlobalExceptionHandler(ObjectProvider<FeatureFlags> flags) { this.flags = flags; }

    // ---- D12-native ------------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return respond(ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRecordNotFound(RecordNotFoundException ex) {
        if (uniformNotFound()) return respond(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE, Map.of());
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ScopeDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleScopeDenied(ScopeDeniedException ex) {
        if (uniformNotFound()) return respond(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE, Map.of());
        return respond(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE, Map.of());
    }

    // ---- existing exceptions mapped to codes ----------------------------------------------

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, "The attachment exceeds the server upload limit. Choose a smaller file.", Map.of());
    }

    /** Keeps the exception's own status; the code is the closest D12 code for that status. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ErrorCode code = codeFor(status);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (code == ErrorCode.RATE_LIMITED) builder.header(HttpHeaders.RETRY_AFTER, "60");
        return builder.body(ErrorBodies.of(code, ex.getReason(), Map.of()));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(org.springframework.security.core.AuthenticationException ex) {
        return respond(ErrorCode.UNAUTHENTICATED, "Invalid credentials or disabled account.", Map.of());
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(Exception ex) {
        return respond(ErrorCode.CONFLICT, "This record changed. Reload it before retrying.", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return respond(ErrorCode.CONFLICT, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(LoginNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleLoginNotAllowed(LoginNotAllowedException ex) {
        return respond(ErrorCode.FORBIDDEN, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(WhatsAppApiException.class)
    public ResponseEntity<Map<String, Object>> handleWhatsAppApi(WhatsAppApiException ex) {
        return respond(ErrorCode.UPSTREAM_ERROR, ex.getMessage(), Map.of("provider", "WHATSAPP"));
    }

    @ExceptionHandler(AdsProviderApiException.class)
    public ResponseEntity<Map<String, Object>> handleAdsProviderApi(AdsProviderApiException ex) {
        return respond(ErrorCode.UPSTREAM_ERROR, ex.getMessage(), Map.of("provider", "ADS"));
    }

    @ExceptionHandler(AiProviderApiException.class)
    public ResponseEntity<Map<String, Object>> handleAiProviderApi(AiProviderApiException ex) {
        return respond(ErrorCode.UPSTREAM_ERROR, ex.getMessage(), Map.of("provider", "AI"));
    }

    /**
     * A branch's own configured AI usage cap was hit. D12 moves the structured fields under
     * {@code details}; they are also kept at the top level until the frontend (01 Part B, Step 5.3)
     * reads them from {@code details}.
     */
    @ExceptionHandler(AiUsageLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleAiUsageLimit(AiUsageLimitExceededException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limitType", ex.getLimitType().name());
        details.put("currentValue", ex.getCurrentValue());
        details.put("capValue", ex.getCapValue());
        details.put("resetsAt", ex.getResetsAt());
        Map<String, Object> body = ErrorBodies.of(ErrorCode.AI_USAGE_LIMIT, ex.getMessage(), details);
        body.putAll(details); // legacy top-level keys (transition)
        return ResponseEntity.status(ErrorCode.AI_USAGE_LIMIT.status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        String message = String.join(", ", fields.stream().map(f -> f.get("field") + ": " + f.get("message")).toList());
        return respond(ErrorCode.VALIDATION_FAILED, message, Map.of("fields", fields));
    }

    /** A permission denial (@PreAuthorize, access.permission): always 403, whatever the flag. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return respond(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE, Map.of());
    }

    /**
     * No controller and no static resource matched the URL (e.g. /swagger-ui.html with springdoc off).
     * The body never echoes the requested path.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        log.debug("No handler for request: {}", ex.getMessage());
        return respond(ErrorCode.NOT_FOUND, "Not found.", Map.of());
    }

    /** Catch-all: log the real exception, return a generic, safe message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.", Map.of());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> respond(ErrorCode code, String message, Map<String, ?> details) {
        return ResponseEntity.status(code.status).body(ErrorBodies.of(code, message, details));
    }

    private boolean uniformNotFound() {
        FeatureFlags f = flags == null ? null : flags.getIfAvailable();
        if (f == null) return false;
        Long org = TenantContext.get().map(TenantContext.Data::organizationId).orElse(null);
        return f.isOn(FeatureFlag.ERRORS_UNIFORM_NOT_FOUND, org);
    }

    static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 502, 503, 504 -> ErrorCode.UPSTREAM_ERROR;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
    }
}
