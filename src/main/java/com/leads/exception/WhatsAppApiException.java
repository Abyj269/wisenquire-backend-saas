package com.leads.exception;

/**
 * Thrown when a call to Meta's WhatsApp Cloud (Graph) API fails — invalid
 * token, bad phone number id, template rejected, network/timeout, etc. The
 * message is the human-readable reason surfaced back to the UI.
 */
public class WhatsAppApiException extends RuntimeException {
    public WhatsAppApiException(String message) {
        super(message);
    }

    public WhatsAppApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
