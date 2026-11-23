package com.leads.service;

import com.leads.dto.whatsapp.SendMessageRequest;
import com.leads.model.entity.*;
import com.leads.model.enums.*;
import com.leads.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppSendPolicyTest {
    @Mock TenantAccess access;
    @Mock jakarta.persistence.EntityManager entityManager;
    @Mock WhatsAppConversationRepository conversationRepo;
    @Mock WhatsAppMessageRepository messageRepo;
    @Mock WhatsAppConfigurationRepository configRepo;
    @Mock CustomerRepository customerRepo;
    @Mock AuthService authService;
    @Mock WhatsAppGraphApiClient graphApiClient;
    @Mock WhatsAppMediaService mediaService;
    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks WhatsAppCloudService service;
    WhatsAppConversation conversation;

    @BeforeEach void setup() {
        conversation = WhatsAppConversation.builder().id(1L).company(Company.builder().id(2L).build())
                .receivingPhoneNumberId("business-number").customerWaId("971501234567").build();
        when(conversationRepo.findForSending(1L)).thenReturn(Optional.of(conversation));
    }
    @Test void closedWindowBlocksTextBeforeProviderCall() {
        assertThrows(ResponseStatusException.class, () -> service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Test").build()));
        verifyNoInteractions(graphApiClient);
        verify(messageRepo, never()).save(any());
    }
    @Test void closedWindowBlocksAttachmentsBeforeUpload() {
        assertThrows(ResponseStatusException.class, () -> service.sendMediaMessage(1L, new byte[]{1}, "x.pdf", "application/pdf", null, null));
        verifyNoInteractions(graphApiClient);
    }
    @Test void pastedScreenshotSendsCaptionAndKeepsReceiptDrivenStatus() {
        assertAttachmentSent("image.png", "image/png", "image/png", "image");
    }
    @Test void pastedPdfWithoutMimeIsSentAsDocumentWithCaption() {
        assertAttachmentSent("quote.pdf", "application/octet-stream", "application/pdf", "document");
    }
    private void assertAttachmentSent(String filename, String browserType, String mime, String mediaType) {
        conversation.setLastCustomerMessageEpoch(Instant.now().minusSeconds(60).getEpochSecond());
        var config = WhatsAppConfiguration.builder().isActive(true).build();
        var bytes = new byte[]{1, 2, 3};
        when(configRepo.findByPhoneNumberId("business-number")).thenReturn(Optional.of(config));
        when(graphApiClient.uploadMedia(config, bytes, mime, filename)).thenReturn("media-1");
        when(graphApiClient.sendMediaMessage(config, "971501234567", mediaType, "media-1", "Please review this", filename)).thenReturn("wamid.media");
        when(messageRepo.save(any())).thenAnswer(i -> { WhatsAppMessage msg = i.getArgument(0); msg.setId(3L); return msg; });
        var result = service.sendMediaMessage(1L, bytes, filename, browserType, "Please review this", "attachment-1");
        assertEquals(MessageStatus.ACCEPTED, result.getStatus());
        assertEquals(mediaType.toUpperCase(java.util.Locale.ROOT), result.getMessageType());
        assertEquals("Please review this", result.getBodyText());
        assertEquals("attachment-1", result.getClientMessageId());
        verify(mediaService).cacheOutboundMedia(3L, mime, bytes);
        verify(graphApiClient, never()).sendTextMessage(any(), anyString(), anyString());
    }
    @Test void metaAcceptanceIsNotSentAndDoesNotExtendWindow() {
        long inbound = Instant.now().minusSeconds(60).getEpochSecond();
        conversation.setLastCustomerMessageEpoch(inbound);
        var config = WhatsAppConfiguration.builder().isActive(true).build();
        when(configRepo.findByPhoneNumberId("business-number")).thenReturn(Optional.of(config));
        when(graphApiClient.sendTextMessage(config, "971501234567", "Test")).thenReturn("wamid.accepted");
        when(messageRepo.save(any())).thenAnswer(invocation -> { WhatsAppMessage msg = invocation.getArgument(0); msg.setId(3L); return msg; });
        var result = service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Test").clientMessageId("request-1").build());
        assertEquals(MessageStatus.ACCEPTED, result.getStatus());
        assertEquals("request-1", result.getClientMessageId());
        assertEquals(inbound, conversation.getLastCustomerMessageEpoch());
    }
    @Test void templateCanBeSubmittedOutsideWindowWithoutOpeningIt() throws Exception {
        var config = WhatsAppConfiguration.builder().isActive(true).build();
        when(configRepo.findByPhoneNumberId("business-number")).thenReturn(Optional.of(config));
        when(graphApiClient.sendTemplateMessage(config, "971501234567", "approved_greeting", "en_US", null)).thenReturn("wamid.template");
        when(graphApiClient.listTemplates(config)).thenReturn(java.util.List.of(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"name\":\"approved_greeting\",\"language\":\"en_US\",\"status\":\"APPROVED\",\"category\":\"UTILITY\",\"components\":[]}")));
        when(messageRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Hello")
                .messageType("TEMPLATE").templateName("approved_greeting").build());
        assertEquals(MessageStatus.ACCEPTED, result.getStatus());
        assertNull(conversation.getLastCustomerMessageEpoch());
        verify(messageRepo).save(argThat(msg -> "UTILITY".equals(msg.getTemplateCategory()) && "en_US".equals(msg.getTemplateLanguage())));
        verify(graphApiClient, never()).sendTextMessage(any(), anyString(), anyString());
    }
    @Test void namedBodyValuesUseMetaParameterNames() throws Exception {
        var config = WhatsAppConfiguration.builder().isActive(true).build();
        when(configRepo.findByPhoneNumberId("business-number")).thenReturn(Optional.of(config));
        when(graphApiClient.listTemplates(config)).thenReturn(java.util.List.of(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"name\":\"order_update\",\"language\":\"en_US\",\"status\":\"APPROVED\",\"category\":\"UTILITY\",\"parameter_format\":\"NAMED\",\"components\":[{\"type\":\"BODY\",\"text\":\"Hello {{customer_name}}, your order is ready\"}]}")));
        when(graphApiClient.sendTemplateMessage(eq(config), anyString(), eq("order_update"), eq("en_US"), anyList())).thenAnswer(i -> {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(i.getArgument(4));
            assertEquals("customer_name", json.get(0).path("parameters").get(0).path("parameter_name").asText());
            assertEquals("Alex", json.get(0).path("parameters").get(0).path("text").asText());
            return "wamid.named";
        });
        when(messageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(MessageStatus.ACCEPTED, service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Hello Alex, your order is ready")
            .messageType("TEMPLATE").templateName("order_update").expectedTemplateCategory("UTILITY").templateBodyParams(java.util.List.of("Alex")).build()).getStatus());
    }
    @Test void providerTimeoutRemainsUnknownRatherThanSentOrRetryableFailure() {
        conversation.setLastCustomerMessageEpoch(Instant.now().minusSeconds(60).getEpochSecond());
        var config = WhatsAppConfiguration.builder().isActive(true).build();
        when(configRepo.findByPhoneNumberId("business-number")).thenReturn(Optional.of(config));
        when(graphApiClient.sendTextMessage(config, "971501234567", "Test"))
                .thenThrow(new com.leads.exception.WhatsAppApiException("Provider timeout", new org.springframework.web.client.ResourceAccessException("timeout")));
        when(messageRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(MessageStatus.UNKNOWN, service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Test").build()).getStatus());
    }
    @Test void sameClientKeyReturnsStoredMessageWithoutAnotherProviderSend() {
        var existing = WhatsAppMessage.builder().id(3L).conversation(conversation).clientMessageId("request-1")
                .status(MessageStatus.DELIVERED).direction(MessageDirection.OUTBOUND).build();
        when(messageRepo.findByConversationIdAndClientMessageId(1L, "request-1")).thenReturn(Optional.of(existing));
        assertEquals(3L, service.sendMessage(SendMessageRequest.builder().conversationId(1L).message("Test").clientMessageId("request-1").build()).getId());
        verifyNoInteractions(graphApiClient);
    }
}
