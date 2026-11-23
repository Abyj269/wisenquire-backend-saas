package com.leads.service;

import com.leads.dto.whatsapp.WhatsAppWebhookPayload;
import com.leads.model.enums.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhatsAppReceiptServiceTest {
    @Test void receiptIsBroadcastOnlyAfterDatabaseCommit() {
        var jdbc = mock(JdbcTemplate.class);
        var messages = mock(com.leads.repository.WhatsAppMessageRepository.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<WhatsAppCloudService> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        var cloud = mock(WhatsAppCloudService.class);
        var broker = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<BroadcastService> broadcastProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        var service = new WhatsAppReceiptService(jdbc, messages, provider, broadcastProvider, broker);
        var message = com.leads.model.entity.WhatsAppMessage.builder().status(MessageStatus.ACCEPTED).build();
        var dto = com.leads.dto.whatsapp.WhatsAppMessageDto.builder().conversationId(5L).status(MessageStatus.DELIVERED).build();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(java.util.Map.of("id", 1L, "wamid", "wamid.test", "status", "DELIVERED")));
        when(messages.findByWamid("wamid.test")).thenReturn(java.util.Optional.of(message));
        when(provider.getObject()).thenReturn(cloud);
        when(cloud.mapMessageToDto(message)).thenReturn(dto);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.reconcile();
            assertEquals(MessageStatus.DELIVERED, message.getStatus());
            verifyNoInteractions(broker);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(broker).convertAndSend("/topic/whatsapp/conversation/5", dto);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void lateReceiptsCannotDowngradeDeliveredReadOrFailedToSent() {
        assertTrue(WhatsAppReceiptService.shouldApply(MessageStatus.ACCEPTED, MessageStatus.FAILED));
        assertTrue(WhatsAppReceiptService.shouldApply(MessageStatus.UNKNOWN, MessageStatus.SENT));
        assertFalse(WhatsAppReceiptService.shouldApply(MessageStatus.DELIVERED, MessageStatus.SENT));
        assertFalse(WhatsAppReceiptService.shouldApply(MessageStatus.READ, MessageStatus.FAILED));
        assertFalse(WhatsAppReceiptService.shouldApply(MessageStatus.FAILED, MessageStatus.SENT));
        assertTrue(WhatsAppReceiptService.shouldApply(MessageStatus.FAILED, MessageStatus.DELIVERED));
    }
    @Test void earlyFailureReceiptIsStoredWithActionableWindowError() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new WhatsAppReceiptService(jdbc, null, null, null, null);
        var receipt = new WhatsAppWebhookPayload.Status();
        receipt.setId("wamid.test"); receipt.setStatus("failed"); receipt.setTimestamp("1234567890");
        var error = new WhatsAppWebhookPayload.StatusError(); error.setCode(131047);
        receipt.setErrors(List.of(error));
        service.record("business-number", receipt);
        verify(jdbc).update(anyString(), eq("business-number"), eq("wamid.test"), eq("FAILED"), eq(1234567890L), eq(131047), contains("Reply window expired"));
    }
}
