package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.WhatsAppConfiguration;
import com.leads.repository.WhatsAppConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhatsAppTemplateWebhookTest {
    final ObjectMapper mapper = new ObjectMapper();
    final String event = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"123\",\"time\":1789000000,\"changes\":[{\"field\":\"template_category_update\",\"value\":{\"message_template_id\":987,\"message_template_name\":\"order_update\",\"message_template_language\":\"en_US\",\"previous_category\":\"UTILITY\",\"new_category\":\"MARKETING\"}}]}]}";
    String signature(byte[] bytes, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    }
    @Test void acceptsSignedWabaEventWithoutPhoneMetadataButRejectsUnknownWabaOrBadSecret() throws Exception {
        var configs = mock(WhatsAppConfigurationRepository.class);
        when(configs.findByWabaIdAndIsActiveTrue("123")).thenReturn(List.of(WhatsAppConfiguration.builder().isActive(true).appSecret("test-secret").build()));
        var verifier = new WebhookSignatureVerifier(configs, mapper); byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> verifier.verify(bytes, signature(bytes, "test-secret")));
        assertThrows(AccessDeniedException.class, () -> verifier.verify(bytes, signature(bytes, "wrong-secret")));
        byte[] unknown = event.replace("\"id\":\"123\"", "\"id\":\"999\"").getBytes(StandardCharsets.UTF_8);
        assertThrows(AccessDeniedException.class, () -> verifier.verify(unknown, signature(unknown, "test-secret")));
        verify(configs, never()).findByPhoneNumberId(anyString());
    }
    static WhatsAppConfigurationRepository registered(String waba) {
        var configs = mock(WhatsAppConfigurationRepository.class);
        when(configs.findByWabaIdAndIsActiveTrue(waba)).thenReturn(List.of(WhatsAppConfiguration.builder().isActive(true).build()));
        return configs;
    }
    /** Module 13 P1: a template event for an account with no active number is not stored. */
    @Test void anEventForAnUnregisteredAccountIsIgnored() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var service = new WhatsAppTemplateEventService(jdbc, mapper, registered("123"));
        String both = event.replace("]}]}", "]},{\"id\":\"999\",\"time\":1789000000,\"changes\":[{\"field\":\"template_category_update\",\"value\":{\"message_template_id\":555,\"new_category\":\"MARKETING\"}}]}]}");
        service.recordVerified(mapper.readTree(both));
        var calls = mockingDetails(jdbc).getInvocations().stream().toList();
        assertEquals(1, calls.size(), "only the registered account's event is stored");
        assertEquals("123", calls.get(0).getArgument(1, String.class));
    }
    @Test void repeatedEventsUseSameDeduplicationKeyAndRetainCategoryEvidence() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var service = new WhatsAppTemplateEventService(jdbc, mapper, registered("123"));
        var payload = mapper.readTree(event); service.recordVerified(payload); service.recordVerified(payload);
        var calls = mockingDetails(jdbc).getInvocations().stream().toList();
        assertEquals(2, calls.size());
        assertArrayEquals(calls.get(0).getArguments(), calls.get(1).getArguments());
        assertTrue(calls.get(0).getArgument(0, String.class).contains("ON DUPLICATE KEY"));
        assertTrue(Arrays.toString(calls.get(0).getArguments()).contains("previous_category"));
    }
    @Test void scheduledCategoryEvidenceIsRetainedWithoutApplyingItAsCurrent() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var service = new WhatsAppTemplateEventService(jdbc, mapper, registered("123"));
        String scheduled = event.replace("\"previous_category\":\"UTILITY\",\"new_category\":\"MARKETING\"", "\"new_category\":\"UTILITY\",\"correct_category\":\"MARKETING\",\"category_update_timestamp\":1789086400");
        service.recordVerified(mapper.readTree(scheduled));
        var call = mockingDetails(jdbc).getInvocations().iterator().next();
        assertTrue(Arrays.toString(call.getArguments()).contains("category_update_timestamp"));
    }
}
