package com.leads.service;

import com.leads.dto.ads.AdConnectionTestResult;
import com.leads.dto.ads.ProviderAdDto;
import com.leads.dto.ads.ProviderInsightDto;
import com.leads.model.entity.AdAccountConnection;
import com.leads.model.enums.AdProvider;

import java.time.LocalDate;
import java.util.List;

/**
 * One implementation per {@link AdProvider} (Meta, Google) — {@link
 * AdsProviderClientFactory} resolves the right one for a given connection so
 * AdSyncConnectionService never branches on provider itself.
 */
public interface AdsProviderClient {

    AdProvider getProvider();

    /** Every ad/campaign currently in the account, regardless of status — this keeps our own `status` column accurate even for ads that just went PAUSED/ARCHIVED. */
    List<ProviderAdDto> fetchAds(AdAccountConnection connection);

    /**
     * Daily spend/impressions/clicks for every ad in {@code [fromDate, toDate]}.
     * {@code activeOrPausedOnly}, when true, asks the provider itself to
     * narrow the result to currently-active-or-paused ads — used for the
     * recurring trailing-3-day sync so the call's size and rate-limit cost
     * don't grow unbounded as an account accumulates years of archived ads
     * that will never post new spend again. A one-off historical backfill
     * passes false, since it legitimately needs every ad regardless of
     * current status.
     */
    List<ProviderInsightDto> fetchInsights(AdAccountConnection connection, LocalDate fromDate, LocalDate toDate, boolean activeOrPausedOnly);

    /** Backs the "Test Connection" action on the Ads Setup screen. */
    AdConnectionTestResult testConnection(AdAccountConnection connection);
}
