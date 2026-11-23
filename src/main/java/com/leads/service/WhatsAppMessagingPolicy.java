package com.leads.service;

import com.leads.model.entity.WhatsAppConversation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** The service window is scoped to a customer and receiving business number. */
public final class WhatsAppMessagingPolicy {
    private WhatsAppMessagingPolicy() {}
    public static boolean isOpen(Long lastCustomerEpoch, Instant now) {
        return lastCustomerEpoch != null && lastCustomerEpoch > 0 && lastCustomerEpoch <= now.getEpochSecond()
                && now.getEpochSecond() - lastCustomerEpoch < 86400;
    }
    public static void requireOpen(WhatsAppConversation conversation) {
        if (!isOpen(conversation.getLastCustomerMessageEpoch(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The 24-hour reply window is closed or unverified. Send an approved WhatsApp template and wait for the customer to reply before sending text or attachments.");
        }
    }
    public static Long inboundEpoch(String timestamp, Instant now) {
        try {
            long epoch = Long.parseLong(timestamp);
            // Never use webhook arrival time: delayed/replayed events must not reopen a window.
            return epoch > 0 && epoch <= now.getEpochSecond() ? epoch : null;
        } catch (RuntimeException e) { return null; }
    }
}
