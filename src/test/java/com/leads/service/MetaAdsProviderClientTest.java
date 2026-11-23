package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.exception.AdsProviderApiException;
import com.leads.model.entity.AdAccountConnection;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MetaAdsProviderClientTest {
    final RestTemplate rest = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    final MetaAdsProviderClient client = new MetaAdsProviderClient(rest, new ObjectMapper());
    final AdAccountConnection connection = AdAccountConnection.builder().externalAccountId("act_123").accessToken("mock-token").build();
    MetaAdsProviderClientTest() { ReflectionTestUtils.setField(client, "baseUrl", "https://graph.facebook.com/v20.0"); }

    @Test void nestedFieldsAndEncodedPagingArePreserved() {
        server.expect(request -> assertTrue(request.getURI().getQuery().contains("campaign{id,name,objective}")))
            .andRespond(withSuccess("{\"data\":[{\"id\":\"1\"}],\"paging\":{\"next\":\"https://graph.facebook.com/v20.0/act_123/ads?after=a%2Bb\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://graph.facebook.com/v20.0/act_123/ads?after=a%2Bb"))
            .andRespond(withSuccess("{\"data\":[{\"id\":\"2\"}],\"paging\":{\"next\":null}}", MediaType.APPLICATION_JSON));
        assertEquals(2, client.fetchAds(connection).size()); server.verify();
    }
    @Test void insightJsonIsEncodedExactlyOnce() {
        server.expect(request -> {
            assertTrue(request.getURI().getQuery().contains("time_range={\"since\":\"2026-09-08\",\"until\":\"2026-09-09\"}"));
            assertTrue(request.getURI().getQuery().contains("\"value\":[\"ACTIVE\",\"PAUSED\"]"));
            assertFalse(request.getURI().getRawQuery().contains("%25"));
        }).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(client.fetchInsights(connection, LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-09"), true).isEmpty()); server.verify();
    }
    @Test void expiredTokenIsAFailure() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"Error validating access token: Session has expired\",\"type\":\"OAuthException\",\"code\":190,\"error_subcode\":463}}"));
        assertTrue(assertThrows(AdsProviderApiException.class, () -> client.fetchAds(connection)).getMessage().contains("expired")); server.verify();
    }
    @Test void rateLimitIsNotAnEmptySuccessfulImport() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"User request limit reached\",\"code\":17}}"));
        assertThrows(AdsProviderApiException.class, () -> client.fetchAds(connection)); server.verify();
    }
    @Test void pagingCannotSendCredentialsToAnotherHost() {
        server.expect(anything()).andRespond(withSuccess("{\"data\":[],\"paging\":{\"next\":\"https://untrusted.example/collect\"}}", MediaType.APPLICATION_JSON));
        assertTrue(assertThrows(AdsProviderApiException.class, () -> client.fetchAds(connection)).getMessage().contains("origin")); server.verify();
    }
}
