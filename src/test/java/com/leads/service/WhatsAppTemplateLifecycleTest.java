package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.whatsapp.*;
import com.leads.model.entity.WhatsAppConfiguration;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WhatsAppTemplateLifecycleTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void creationUsesMetaReturnedCategoryAndNamedExamples() throws Exception {
        var graph = mock(WhatsAppGraphApiClient.class);
        var configs = mock(WhatsAppConfigurationRepository.class);
        var config = WhatsAppConfiguration.builder().id(1L).build();
        when(configs.findById(1L)).thenReturn(Optional.of(config));
        when(graph.createTemplate(eq(config), anyMap())).thenAnswer(i -> {
            var payload = mapper.valueToTree(i.getArgument(1));
            assertEquals("NAMED", payload.path("parameter_format").asText());
            assertEquals("customer_name", payload.path("components").get(0).path("example").path("body_text_named_params").get(0).path("param_name").asText());
            return mapper.readTree("{\"id\":\"123\",\"status\":\"PENDING\",\"category\":\"MARKETING\"}");
        });
        var service = new WhatsAppTemplateService(mock(TenantAccess.class), configs, mock(WhatsAppMessageRepository.class), graph, mock(WhatsAppTemplateEventService.class));
        var request = WhatsAppTemplateCreateRequest.builder().configId(1L).name("order_update").category("UTILITY").language("en_US")
            .parameterFormat("NAMED").bodyText("Hello {{customer_name}}, your order is ready.").bodyExampleValues(List.of("Alex")).build();
        assertEquals("MARKETING", service.createTemplate(request).getCategory());
    }
    @Test void categoryChangeRequiresReviewAndPausedTemplateCannotSend() throws Exception {
        var request = SendMessageRequest.builder().expectedTemplateCategory("UTILITY").build();
        var template = mapper.readTree("{\"category\":\"MARKETING\",\"status\":\"APPROVED\",\"components\":[]}");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> WhatsAppTemplateSendPolicy.validate(template, request)).getStatusCode().value());
        request.setExpectedTemplateCategory("MARKETING");
        assertDoesNotThrow(() -> WhatsAppTemplateSendPolicy.validate(template, request));
        var paused = mapper.readTree("{\"category\":\"MARKETING\",\"status\":\"PAUSED\"}");
        assertThrows(ResponseStatusException.class, () -> WhatsAppTemplateSendPolicy.validate(paused, request));
    }
    @Test void futureCategoryDoesNotReplaceCurrentCategory() throws Exception {
        var template = mapper.readTree("{\"category\":\"UTILITY\",\"correct_category\":\"MARKETING\",\"status\":\"APPROVED\",\"components\":[]}");
        assertDoesNotThrow(() -> WhatsAppTemplateSendPolicy.validate(template, SendMessageRequest.builder().expectedTemplateCategory("UTILITY").build()));
    }
    @Test void invalidVariablesAndUtilityOfferRejected() {
        var r = WhatsAppTemplateCreateRequest.builder().name("order_update").category("UTILITY").language("en_US").bodyText("Your order {{2}} is ready").bodyExampleValues(List.of("123")).build();
        assertThrows(IllegalArgumentException.class, () -> WhatsAppTemplateValidation.validate(r));
        r.setBodyText("Your order {{1}} is ready");
        assertDoesNotThrow(() -> WhatsAppTemplateValidation.validate(r));
        r.setLimitedTimeOfferEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> WhatsAppTemplateValidation.validate(r));
    }
    @Test void flowNavigationRequiresStartingScreen() {
        var button = WhatsAppTemplateCreateRequest.TemplateButton.builder().type("FLOW").text("View order").flowId("123").build();
        var r = WhatsAppTemplateCreateRequest.builder().name("order_flow").category("UTILITY").language("en_US").bodyText("View your order details.").buttons(List.of(button)).build();
        assertThrows(IllegalArgumentException.class, () -> WhatsAppTemplateValidation.validate(r));
        button.setNavigateScreen("ORDER");
        assertDoesNotThrow(() -> WhatsAppTemplateValidation.validate(r));
    }
    @Test void templatePaginationPreservesEncodedCursorAndLiveCategoryFields() {
        var rest = new RestTemplate(); var http = MockRestServiceServer.bindTo(rest).build();
        var graph = new WhatsAppGraphApiClient(rest, mapper);
        ReflectionTestUtils.setField(graph, "defaultBaseUrl", "https://graph.facebook.com/v20.0");
        http.expect(request -> assertTrue(request.getURI().getQuery().contains("correct_category")))
            .andRespond(withSuccess("{\"data\":[{\"id\":\"1\"}],\"paging\":{\"next\":\"https://graph.facebook.com/next\",\"cursors\":{\"after\":\"a+b&c\"}}}", MediaType.APPLICATION_JSON));
        http.expect(request -> assertTrue(request.getURI().getRawQuery().endsWith("after=a%2Bb%26c")))
            .andExpect(header("Authorization", "Bearer mock"))
            .andRespond(withSuccess("{\"data\":[{\"id\":\"2\",\"category\":\"UTILITY\",\"correct_category\":\"MARKETING\"}]}", MediaType.APPLICATION_JSON));
        var result = graph.listTemplates(WhatsAppConfiguration.builder().wabaId("123").accessToken("mock").build());
        assertEquals(2, result.size()); assertEquals("MARKETING", result.get(1).path("correct_category").asText()); http.verify();
    }
    @Test void editCannotTargetAnotherWaba() {
        var graph = mock(WhatsAppGraphApiClient.class); var configs = mock(WhatsAppConfigurationRepository.class);
        var config = WhatsAppConfiguration.builder().id(1L).build(); when(configs.findById(1L)).thenReturn(Optional.of(config));
        when(graph.requireTemplate(config, "foreign")).thenThrow(new com.leads.exception.WhatsAppApiException("Template does not belong to account"));
        var service = new WhatsAppTemplateService(mock(TenantAccess.class), configs, mock(WhatsAppMessageRepository.class), graph, mock(WhatsAppTemplateEventService.class));
        assertThrows(com.leads.exception.WhatsAppApiException.class, () -> service.updateTemplate("foreign", WhatsAppTemplateCreateRequest.builder().configId(1L).build()));
        verify(graph, never()).updateTemplate(any(), anyString(), anyMap());
    }
}
