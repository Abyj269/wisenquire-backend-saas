package com.leads.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Without this, every IllegalArgumentException thrown by a service (there are
 * dozens — "Company not found", pipeline validation, etc.) fell through to
 * Spring Boot's default handler as an opaque 500 with no message in the
 * response body, so the frontend's error banners never had anything to show.
 * <p>
 * Every handler here is deliberately narrow — each returns a message a
 * developer wrote specifically for end users (a validation rule, a business
 * reason, Meta's own API error text). The two handlers at the bottom
 * ({@link AccessDeniedException} and the {@link Exception} catch-all) exist
 * for the opposite reason: to guarantee that anything NOT on this list —
 * a stray NullPointerException, a raw SQL constraint message, a stack
 * trace — never reaches the frontend verbatim. The real exception is always
 * logged server-side; only a generic, safe message crosses the wire.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(errorBody("The attachment exceeds the server upload limit. Choose a smaller file."));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(errorBody(ex.getReason()));
    }
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Map<String,String>> handleAuthentication(org.springframework.security.core.AuthenticationException ex) {
        return ResponseEntity.status(401).body(errorBody("Invalid credentials or disabled account."));
    }
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String,String>> handleConflict(Exception ex) {
        return ResponseEntity.status(409).body(errorBody("This record changed. Reload it before retrying."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(LoginNotAllowedException.class)
    public ResponseEntity<Map<String, String>> handleLoginNotAllowed(LoginNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(WhatsAppApiException.class)
    public ResponseEntity<Map<String, String>> handleWhatsAppApi(WhatsAppApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(AdsProviderApiException.class)
    public ResponseEntity<Map<String, String>> handleAdsProviderApi(AdsProviderApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(AiProviderApiException.class)
    public ResponseEntity<Map<String, String>> handleAiProviderApi(AiProviderApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody(ex.getMessage()));
    }

    /** A branch's own configured AI usage cap was hit — distinct from a provider-side rate limit (see {@link AiProviderApiException}). Structured body so the frontend can render a specific usage banner instead of a generic error toast. */
    @ExceptionHandler(AiUsageLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleAiUsageLimit(AiUsageLimitExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("limitType", ex.getLimitType().name());
        body.put("currentValue", ex.getCurrentValue());
        body.put("capValue", ex.getCapValue());
        body.put("resetsAt", ex.getResetsAt());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(message));
    }

    /** A @PreAuthorize denial (e.g. missing COMPANY_BRANCH_SETTINGS:VIEW) — previously fell through with no body, which is why the frontend showed Angular's own raw "403 OK" text instead of a real reason. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("You don't have permission to do this."));
    }

    /**
     * No controller and no static resource matched the URL (Spring 6.1+ throws
     * {@code NoResourceFoundException} from the resource handler; {@code NoHandlerFoundException}
     * when no handler exists at all). Without these two, the {@link Exception} catch-all below
     * turned every unknown path — e.g. /swagger-ui.html with springdoc off in prod — into a 500
     * and an ERROR log line. Spring picks the most specific matching handler, so these win over
     * the catch-all regardless of order. The body never echoes the requested path.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<Map<String, String>> handleNotFound(Exception ex) {
        log.debug("No handler for request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("Not found."));
    }

    /** Catch-all for anything not covered above — logs the real exception and returns a generic, safe message instead of leaking internal details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody("Something went wrong. Please try again."));
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", message != null ? message : "Request could not be processed.");
        return body;
    }
}
