package com.leads.exception;

import com.leads.config.RequestTraceFilter;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the D12 error body {@code {code, message, details, traceId}}. {@code message} is always present. */
public final class ErrorBodies {

    private ErrorBodies() { }

    public static Map<String, Object> of(ErrorCode code, String message) { return of(code, message, Map.of()); }

    public static Map<String, Object> of(ErrorCode code, String message, Map<String, ?> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message != null && !message.isBlank() ? message : "Request could not be processed.");
        body.put("details", details == null ? Map.of() : details);
        body.put("traceId", MDC.get(RequestTraceFilter.MDC_TRACE_ID));
        return body;
    }

    /** For filters that write the response themselves (no Jackson there): a small, safe JSON string. */
    public static String json(ErrorCode code, String message) {
        String trace = MDC.get(RequestTraceFilter.MDC_TRACE_ID);
        return "{\"code\":\"" + code.name() + "\",\"message\":\"" + escape(message) + "\",\"details\":{},\"traceId\":"
                + (trace == null ? "null" : "\"" + escape(trace) + "\"") + "}";
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : String.valueOf(s).toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }
}
