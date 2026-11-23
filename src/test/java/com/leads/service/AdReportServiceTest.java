package com.leads.service;

import com.leads.model.entity.*;
import com.leads.model.enums.AdProvider;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdReportServiceTest {
    @Test void identicalCampaignIdsFromDifferentAccountsStaySeparateAndMixedSpendIsNotSummed() {
        var access = mock(TenantAccess.class);
        var leads = mock(LeadService.class);
        var ads = mock(AdRepository.class);
        var insights = mock(AdInsightRepository.class);
        var service = new AdReportService(access, leads, ads, insights);
        var first = Ad.builder().id(1L).externalCampaignId("same-id").provider(AdProvider.META)
                .connection(AdAccountConnection.builder().id(10L).build()).build();
        var second = Ad.builder().id(2L).externalCampaignId("same-id").provider(AdProvider.GOOGLE)
                .connection(AdAccountConnection.builder().id(20L).build()).build();
        when(ads.findForReport(5L, null)).thenReturn(List.of(first, second));
        when(leads.findLeadsForReport(5L, null, null, null)).thenReturn(List.of());
        when(insights.sumSpendByAd(5L, null, null, null)).thenReturn(Arrays.asList(
                new Object[]{1L, new BigDecimal("10.00"), "USD"}, new Object[]{2L, new BigDecimal("20.00"), "AED"}));
        var report = service.getAdPerformanceReport(5L, null, null, null);
        assertEquals(2, report.getByCampaign().size());
        assertNull(report.getTotalSpend());
        assertNull(report.getTotalRevenue());
        assertEquals(Set.of("USD", "AED"), new HashSet<>(report.getByCampaign().stream().map(r -> r.getCurrency()).toList()));
    }
}
