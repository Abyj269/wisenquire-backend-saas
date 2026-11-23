package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.ads.AdConnectionTestResult;
import com.leads.dto.ads.ProviderAdDto;
import com.leads.dto.ads.ProviderInsightDto;
import com.leads.exception.AdsProviderApiException;
import com.leads.model.entity.AdAccountConnection;
import com.leads.model.enums.AdProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Google Ads REST API. Unlike Meta, Google Ads has
 * no long-lived static token — every call here refreshes a short-lived
 * access token from the stored refresh token first (no in-memory caching
 * across calls: at this client's hourly-or-slower call cadence, one refresh
 * per sync easily stays inside quota).
 * <p>
 * Deliberately uses the paginated {@code googleAds:search} endpoint, not
 * {@code googleAds:searchStream} — the latter's newline-delimited chunked
 * response doesn't fit RestTemplate's buffer-the-whole-body model without
 * custom streaming deserialization, whereas {@code search} returns one
 * ordinary JSON object plus a {@code nextPageToken} to loop on, matching the
 * plain request/response style every other client in this codebase uses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleAdsProviderClient implements AdsProviderClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ads.google.api-base-url:https://googleads.googleapis.com/v25}")
    private String apiBaseUrl;

    @Value("${ads.google.oauth-token-url:https://oauth2.googleapis.com/token}")
    private String oauthTokenUrl;

    @Override
    public AdProvider getProvider() {
        return AdProvider.GOOGLE;
    }

    @Override
    public List<ProviderAdDto> fetchAds(AdAccountConnection connection) {
        String accessToken = refreshAccessToken(connection);
        String gaql = "SELECT ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group_ad.status, "
                + "campaign.id, campaign.name, campaign.advertising_channel_type, "
                + "ad_group.id, ad_group.name "
                + "FROM ad_group_ad";

        List<ProviderAdDto> result = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = search(connection, accessToken, gaql, pageToken);
            JsonNode rows = page.get("results");
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    result.add(ProviderAdDto.builder()
                            .externalAdId(at(row, "adGroupAd", "ad", "id"))
                            .adName(at(row, "adGroupAd", "ad", "name"))
                            .status(at(row, "adGroupAd", "status"))
                            .externalCampaignId(at(row, "campaign", "id"))
                            .campaignName(at(row, "campaign", "name"))
                            .objective(at(row, "campaign", "advertisingChannelType"))
                            .externalAdsetId(at(row, "adGroup", "id"))
                            .adsetName(at(row, "adGroup", "name"))
                            .build());
                }
            }
            pageToken = text(page, "nextPageToken");
        } while (pageToken != null);
        return result;
    }

    @Override
    public List<ProviderInsightDto> fetchInsights(AdAccountConnection connection, LocalDate fromDate, LocalDate toDate, boolean activeOrPausedOnly) {
        String accessToken = refreshAccessToken(connection);
        String gaql = "SELECT ad_group_ad.ad.id, segments.date, metrics.cost_micros, metrics.impressions, metrics.clicks, customer.currency_code "
                + "FROM ad_group_ad "
                + "WHERE segments.date BETWEEN '" + fromDate + "' AND '" + toDate + "'"
                + (activeOrPausedOnly ? " AND ad_group_ad.status IN ('ENABLED','PAUSED')" : "");

        List<ProviderInsightDto> result = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = search(connection, accessToken, gaql, pageToken);
            JsonNode rows = page.get("results");
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    String costMicros = at(row, "metrics", "costMicros");
                    result.add(ProviderInsightDto.builder()
                            .externalAdId(at(row, "adGroupAd", "ad", "id"))
                            .statDate(LocalDate.parse(at(row, "segments", "date")))
                            .spend(microsToSpend(costMicros))
                            .currency(at(row, "customer", "currencyCode"))
                            .impressions(parseLong(at(row, "metrics", "impressions")))
                            .clicks(parseLong(at(row, "metrics", "clicks")))
                            .build());
                }
            }
            pageToken = text(page, "nextPageToken");
        } while (pageToken != null);
        return result;
    }

    @Override
    public AdConnectionTestResult testConnection(AdAccountConnection connection) {
        try {
            String accessToken = refreshAccessToken(connection);
            JsonNode page = search(connection, accessToken,
                    "SELECT customer.id, customer.descriptive_name, customer.currency_code FROM customer LIMIT 1", null);
            JsonNode rows = page.get("results");
            JsonNode first = rows != null && rows.isArray() && !rows.isEmpty() ? rows.get(0) : null;
            return AdConnectionTestResult.builder()
                    .success(true)
                    .accountDisplayName(first != null ? at(first, "customer", "descriptiveName") : null)
                    .accountCurrency(first != null ? at(first, "customer", "currencyCode") : null)
                    .message("Connected successfully.")
                    .build();
        } catch (AdsProviderApiException ex) {
            return AdConnectionTestResult.builder().success(false).message(ex.getMessage()).build();
        }
    }

    private String refreshAccessToken(AdAccountConnection connection) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", connection.getGoogleClientId());
        form.add("client_secret", connection.getGoogleClientSecret());
        form.add("refresh_token", connection.getGoogleRefreshToken());
        form.add("grant_type", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(oauthTokenUrl, entity, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            String accessToken = text(body, "access_token");
            if (accessToken == null) throw new AdsProviderApiException("Google did not return an access token.");
            return accessToken;
        } catch (HttpStatusCodeException ex) {
            // invalid_grant here means the refresh token was revoked or expired
            // (Google refresh tokens can lapse after ~6 months unused) — this
            // is the "credential dead, admin must reconnect" signal AdSyncConnectionService surfaces as lastSyncStatus=FAILED.
            throw new AdsProviderApiException("Google OAuth token refresh failed: " + ex.getResponseBodyAsString(), ex);
        } catch (ResourceAccessException ex) {
            throw new AdsProviderApiException("Could not reach Google's OAuth token endpoint — check network access.", ex);
        } catch (AdsProviderApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AdsProviderApiException("Unexpected error refreshing Google Ads access token: " + ex.getMessage(), ex);
        }
    }

    private JsonNode search(AdAccountConnection connection, String accessToken, String gaql, String pageToken) {
        String customerId = digitsOnly(connection.getExternalAccountId());
        String url = apiBaseUrl + "/customers/" + customerId + "/googleAds:search";

        Map<String, Object> body = new HashMap<>();
        body.put("query", gaql);
        if (pageToken != null) body.put("pageToken", pageToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        headers.set("developer-token", connection.getGoogleDeveloperToken());
        String loginCustomerId = digitsOnly(connection.getGoogleLoginCustomerId());
        if (loginCustomerId != null && !loginCustomerId.isBlank()) {
            headers.set("login-customer-id", loginCustomerId);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new AdsProviderApiException("Could not reach the Google Ads API — check network access and the base URL.", ex);
        } catch (Exception ex) {
            throw new AdsProviderApiException("Unexpected error calling the Google Ads API: " + ex.getMessage(), ex);
        }
    }

    /** Google's UI displays account/login-customer ids dashed (123-456-7890); the API rejects dashes. Defense-in-depth even though AdAccountConnectionService also normalizes at save time. */
    private String digitsOnly(String value) {
        return value != null ? value.replaceAll("\\D", "") : null;
    }

    private BigDecimal microsToSpend(String costMicros) {
        if (costMicros == null) return BigDecimal.ZERO;
        return new BigDecimal(costMicros).divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);
    }

    private Long parseLong(String value) {
        return value != null ? Long.valueOf(value) : null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    /** Walks a chain of nested object fields (e.g. "adGroupAd","ad","id"), returning null the moment any level is missing rather than throwing. */
    private String at(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            if (current == null) return null;
            current = current.get(field);
        }
        return current != null && !current.isNull() ? current.asText() : null;
    }

    private AdsProviderApiException toApiException(HttpStatusCodeException ex) {
        String reason = ex.getStatusCode().toString();
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            JsonNode errors = body.isArray() && !body.isEmpty() ? body.get(0).get("error") : body.get("error");
            if (errors != null && errors.get("message") != null) {
                reason = errors.get("message").asText();
            }
        } catch (Exception ignored) {
            // Google didn't return the expected error shape — fall back to the raw status.
        }
        log.warn("Google Ads API call failed: {}", reason);
        return new AdsProviderApiException(reason);
    }
}
