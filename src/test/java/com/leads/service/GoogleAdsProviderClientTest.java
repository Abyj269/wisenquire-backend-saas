package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.AdAccountConnection;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleAdsProviderClientTest {
    @Test void revokedRefreshTokenFailsWithoutCallingAdsEndpoint() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var client = new GoogleAdsProviderClient(rest, new ObjectMapper());
        ReflectionTestUtils.setField(client, "oauthTokenUrl", "https://oauth2.googleapis.com/token");
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        assertThrows(com.leads.exception.AdsProviderApiException.class,
            () -> client.fetchAds(AdAccountConnection.builder().googleRefreshToken("mock-revoked").build()));
        server.verify();
    }
    @Test void permissionDeniedIsNotReportedAsEmptySuccess() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var client = new GoogleAdsProviderClient(rest, new ObjectMapper());
        ReflectionTestUtils.setField(client, "oauthTokenUrl", "https://oauth2.googleapis.com/token");
        ReflectionTestUtils.setField(client, "apiBaseUrl", "https://googleads.googleapis.com/v25");
        server.expect(anything()).andRespond(withSuccess("{\"access_token\":\"mock\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://googleads.googleapis.com/v25/customers/1234567890/googleAds:search"))
            .andExpect(header("login-customer-id", "1112223333"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":403,\"message\":\"User does not have permission to access customer\",\"status\":\"PERMISSION_DENIED\"}}"));
        var connection = AdAccountConnection.builder().externalAccountId("123-456-7890")
            .googleLoginCustomerId("111-222-3333").googleDeveloperToken("mock").build();
        assertThrows(com.leads.exception.AdsProviderApiException.class, () -> client.fetchAds(connection));
        server.verify();
    }
    @Test void searchUsesPageTokenWithoutUnsupportedPageSize() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var client = new GoogleAdsProviderClient(rest, new ObjectMapper());
        ReflectionTestUtils.setField(client, "apiBaseUrl", "https://googleads.googleapis.com/v25");
        ReflectionTestUtils.setField(client, "oauthTokenUrl", "https://oauth2.googleapis.com/token");
        var connection = AdAccountConnection.builder().externalAccountId("123-456-7890")
                .googleClientId("test-client").googleClientSecret("test-secret")
                .googleRefreshToken("test-refresh").googleDeveloperToken("test-developer").build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"test-access\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://googleads.googleapis.com/v25/customers/1234567890/googleAds:search"))
                .andExpect(jsonPath("$.pageSize").doesNotExist())
                .andExpect(jsonPath("$.pageToken").doesNotExist())
                .andRespond(withSuccess("{\"results\":[],\"nextPageToken\":\"next\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://googleads.googleapis.com/v25/customers/1234567890/googleAds:search"))
                .andExpect(jsonPath("$.pageSize").doesNotExist())
                .andExpect(jsonPath("$.pageToken").value("next"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(client.fetchAds(connection).isEmpty());
        server.verify();
    }
}
