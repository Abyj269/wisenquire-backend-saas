package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.*;
import com.leads.model.enums.*;
import com.leads.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;

/** Real HTTP parsers + sync/upsert logic + report service, with isolated repository doubles. */
class AdsMockPipelineTest {
    static String fixture(String name) throws Exception {
        try (var stream = AdsMockPipelineTest.class.getResourceAsStream("/ads/" + name + ".json")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest @EnumSource(AdProvider.class)
    void providerPayloadThroughRepeatedSyncToReport(AdProvider provider) throws Exception {
        var rest = new RestTemplate();
        var http = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        AdsProviderClient client;
        if (provider == AdProvider.META) {
            var meta = new MetaAdsProviderClient(rest, new ObjectMapper());
            ReflectionTestUtils.setField(meta, "baseUrl", "https://graph.facebook.com/v20.0"); client = meta;
            http.expect(ExpectedCount.times(2), request -> assertEquals("/v20.0/act_123/ads", request.getURI().getPath()))
                .andExpect(header("Authorization", "Bearer mock-token"))
                .andRespond(withSuccess(fixture("meta-ads"), MediaType.APPLICATION_JSON));
            http.expect(ExpectedCount.times(2), request -> assertEquals("/v20.0/act_123/insights", request.getURI().getPath()))
                .andRespond(withSuccess(fixture("meta-insights"), MediaType.APPLICATION_JSON));
        } else {
            var google = new GoogleAdsProviderClient(rest, new ObjectMapper());
            ReflectionTestUtils.setField(google, "apiBaseUrl", "https://googleads.googleapis.com/v25");
            ReflectionTestUtils.setField(google, "oauthTokenUrl", "https://oauth2.googleapis.com/token"); client = google;
            http.expect(ExpectedCount.times(4), requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"mock-access\",\"expires_in\":3600,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
            http.expect(ExpectedCount.times(2), jsonPath("$.query").value(org.hamcrest.Matchers.containsString("ad_group_ad.ad.name")))
                .andExpect(header("developer-token", "mock-developer")).andExpect(jsonPath("$.pageSize").doesNotExist())
                .andRespond(withSuccess(fixture("google-ads"), MediaType.APPLICATION_JSON));
            http.expect(ExpectedCount.times(2), jsonPath("$.query").value(org.hamcrest.Matchers.containsString("metrics.cost_micros")))
                .andRespond(withSuccess(fixture("google-insights"), MediaType.APPLICATION_JSON));
        }
        var company = Company.builder().id(1L).build();
        var connection = AdAccountConnection.builder().id(10L).company(company).provider(provider).externalAccountId(provider == AdProvider.META ? "act_123" : "1234567890")
            .accessToken("mock-token").googleClientId("mock-client").googleClientSecret("mock-secret").googleRefreshToken("mock-refresh").googleDeveloperToken("mock-developer").build();
        var connections = mock(AdAccountConnectionRepository.class);
        var ads = mock(AdRepository.class);
        var insights = mock(AdInsightRepository.class);
        var leads = mock(LeadRepository.class);
        var conversations = mock(WhatsAppConversationRepository.class);
        Map<String, Ad> storedAds = new LinkedHashMap<>();
        Map<String, AdInsight> storedInsights = new LinkedHashMap<>();
        when(ads.findByConnectionIdAndExternalAdId(eq(10L), anyString())).thenAnswer(i -> Optional.ofNullable(storedAds.get(i.getArgument(1))));
        when(ads.save(any())).thenAnswer(i -> { Ad a = i.getArgument(0); if (a.getId() == null) a.setId((long) storedAds.size()+1); storedAds.put(a.getExternalAdId(), a); return a; });
        when(insights.findByAdIdAndStatDate(anyLong(), any())).thenAnswer(i -> Optional.ofNullable(storedInsights.get(i.getArgument(0)+":"+i.getArgument(1))));
        when(insights.save(any())).thenAnswer(i -> { AdInsight row = i.getArgument(0); storedInsights.put(row.getAd().getId()+":"+row.getStatDate(), row); return row; });
        var sync = new AdSyncConnectionService(connections, ads, insights, leads, conversations, new AdsProviderClientFactory(List.of(client)));
        sync.syncConnection(connection);
        sync.syncConnection(connection);
        assertEquals(2, storedAds.size(), "Repeat sync must not duplicate ads");
        assertEquals(2, storedInsights.size(), "Repeat sync must not double-count daily spend");
        assertEquals(AdSyncStatus.SUCCESS, connection.getLastSyncStatus());
        assertEquals(Set.of(AdStatus.ACTIVE, AdStatus.PAUSED), new HashSet<>(storedAds.values().stream().map(Ad::getStatus).toList()));
        assertEquals(15000L, storedInsights.values().stream().mapToLong(AdInsight::getImpressions).sum());
        assertEquals(350L, storedInsights.values().stream().mapToLong(AdInsight::getClicks).sum());
        when(ads.findForReport(1L, null)).thenReturn(new ArrayList<>(storedAds.values()));
        when(insights.sumSpendByAd(1L, null, null, null)).thenReturn(storedInsights.values().stream().map(r -> new Object[]{r.getAd().getId(), r.getSpend(), r.getCurrency()}).toList());
        var report = new AdReportService(mock(TenantAccess.class), mock(LeadService.class), ads, insights).getAdPerformanceReport(1L, null, null, null);
        assertEquals(new BigDecimal("200.00"), report.getTotalSpend());
        assertEquals(1, report.getByCampaign().size());
        assertEquals(2, report.getByAd().size());
        assertEquals("AED", report.getByCampaign().get(0).getCurrency());
        assertNull(report.getByCampaign().get(0).getCostPerLead(), "Zero leads must not produce an infinite CPL");
        http.verify();
    }
}
