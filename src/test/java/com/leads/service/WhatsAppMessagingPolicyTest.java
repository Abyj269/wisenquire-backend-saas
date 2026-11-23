package com.leads.service;

import com.leads.model.entity.WhatsAppConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WhatsAppMessagingPolicyTest {
    private final Instant now = Instant.parse("2026-09-10T12:00:00Z");
    @Test void customerReplyOpensWindowAndExactTwentyFourHoursClosesIt() {
        assertTrue(WhatsAppMessagingPolicy.isOpen(now.minusSeconds(86399).getEpochSecond(), now));
        assertFalse(WhatsAppMessagingPolicy.isOpen(now.minusSeconds(86400).getEpochSecond(), now));
        assertFalse(WhatsAppMessagingPolicy.isOpen(null, now));
        assertFalse(WhatsAppMessagingPolicy.isOpen(now.plusSeconds(1).getEpochSecond(), now));
    }
    @Test void delayedWebhookUsesCustomerTimestampNotReceiptTime() {
        Long epoch = WhatsAppMessagingPolicy.inboundEpoch(Long.toString(now.minusSeconds(90000).getEpochSecond()), now);
        assertFalse(WhatsAppMessagingPolicy.isOpen(epoch, now));
        assertNull(WhatsAppMessagingPolicy.inboundEpoch("invalid", now));
        assertNull(WhatsAppMessagingPolicy.inboundEpoch(Long.toString(now.plusSeconds(60).getEpochSecond()), now));
    }
    @Test void outgoingMessageDoesNotOpenWindow() {
        var conversation = WhatsAppConversation.builder().lastMessageAt(java.time.LocalDateTime.now()).build();
        var exception = assertThrows(ResponseStatusException.class, () -> WhatsAppMessagingPolicy.requireOpen(conversation));
        assertEquals(409, exception.getStatusCode().value());
    }
}
