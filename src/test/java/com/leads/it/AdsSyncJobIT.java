package com.leads.it;

import com.leads.it.fixture.Slot;
import com.leads.model.entity.AdAccountConnection;
import com.leads.model.entity.Company;
import com.leads.service.AdSyncService;
import com.leads.service.AdsProviderClient;
import com.leads.service.CompanyClock;
import com.leads.service.TenantAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Module 19 P1: the hourly sync and the async backfill run each connection as its own company (TenantAccess
 * scope) with orgId in the log context, and the sync window ends on the company's today (D15).
 */
class AdsSyncJobIT extends AbstractIntegrationTest {

    record Seen(String org, Long company, LocalDate to) { }

    @Autowired AdSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyClock clock;
    @Autowired jakarta.persistence.EntityManager em;
    private String savedZone;

    @AfterEach
    void restoreZone() {
        if (savedZone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", savedZone, fixture.company(Slot.A2a));
    }

    /** Mocked clients that record, per connection, the log context, the scope and the window end. */
    private Map<Long, Seen> recordingClients() {
        Map<Long, Seen> seen = new ConcurrentHashMap<>();
        AdsProviderClient client = Mockito.mock(AdsProviderClient.class);
        when(client.fetchAds(any())).thenReturn(List.of());
        when(client.fetchInsights(any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            AdAccountConnection c = inv.getArgument(0);
            seen.put(c.getId(), new Seen(MDC.get("orgId"), TenantAccess.systemCompanyId(), inv.getArgument(2)));
            return List.of();
        });
        when(adsClients.resolve(any())).thenReturn(client);
        return seen;
    }

    @Test
    void theHourlySyncRunsEachConnectionAsItsCompanyOnTheCompanysDay() {
        Long company = fixture.company(Slot.A2a);
        savedZone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        Long dubai = fixture.freshAdConnection(Slot.A1a, false);
        Long india = fixture.freshAdConnection(Slot.A2a, false);
        Long otherOrg = fixture.freshAdConnection(Slot.B1a, true);
        Map<Long, Seen> seen = recordingClients();
        jdbc.update("DELETE FROM shedlock WHERE name = 'ads-sync'"); // another test may hold the hourly lock

        sync.runScheduledSync();

        assertEquals(new Seen(String.valueOf(fixture.org('A')), fixture.company(Slot.A1a), clock.today(em.find(Company.class, fixture.company(Slot.A1a)))), seen.get(dubai));
        assertEquals(new Seen(String.valueOf(fixture.org('A')), company, clock.today(em.find(Company.class, company))), seen.get(india),
                "an IST company's window ends on its own today");
        assertEquals(new Seen(String.valueOf(fixture.org('B')), fixture.company(Slot.B1a), clock.today(em.find(Company.class, fixture.company(Slot.B1a)))), seen.get(otherOrg),
                "each organization's connection carries its own orgId");
        assertNull(MDC.get("orgId"), "the job thread's context is restored");
        assertNull(TenantAccess.systemCompanyId());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT last_sync_status FROM ad_account_connections WHERE id = ?", String.class, india));
    }

    @Test
    void theAsyncBackfillRunsAsTheConnectionsCompany() throws Exception {
        Long connection = fixture.freshAdConnection(Slot.A1b, false);
        Map<Long, Seen> seen = recordingClients();

        sync.backfillAsync(connection);
        for (int i = 0; i < 100 && !seen.containsKey(connection); i++) Thread.sleep(50);

        Seen s = seen.get(connection);
        assertNotNull(s, "the backfill ran");
        assertEquals(String.valueOf(fixture.org('A')), s.org());
        assertEquals(fixture.company(Slot.A1b), s.company());
    }
}
