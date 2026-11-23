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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around Meta's Marketing (Graph) API for ads/campaigns and
 * spend — same "thin wrapper, JsonNode navigation, one connection object
 * passed explicitly per call" convention as {@link WhatsAppGraphApiClient},
 * just for the ads side of the same Graph API rather than the messaging
 * side. Uses {@link AdAccountConnection#getAccessToken()} as-is (Meta's
 * System User tokens are long-lived, not refreshed like Google's).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaAdsProviderClient implements AdsProviderClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ads.meta.base-url:https://graph.facebook.com/v20.0}")
    private String baseUrl;

    @Override
    public AdProvider getProvider() {
        return AdProvider.META;
    }

    @Override
    public List<ProviderAdDto> fetchAds(AdAccountConnection connection) {
        List<ProviderAdDto> result = new ArrayList<>();
        String url = baseUrl + "/" + connection.getExternalAccountId()
                + "/ads?fields=id,name,campaign{id,name,objective},adset{id,name},effective_status&limit=200";

        while (url != null) {
            JsonNode response = get(connection, url);
            JsonNode data = response.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode ad : data) {
                    JsonNode campaign = ad.get("campaign");
                    JsonNode adset = ad.get("adset");
                    result.add(ProviderAdDto.builder()
                            .externalAdId(text(ad, "id"))
                            .externalCampaignId(campaign != null ? text(campaign, "id") : null)
                            .campaignName(campaign != null ? text(campaign, "name") : null)
                            .objective(campaign != null ? text(campaign, "objective") : null)
                            .externalAdsetId(adset != null ? text(adset, "id") : null)
                            .adsetName(adset != null ? text(adset, "name") : null)
                            .adName(text(ad, "name"))
                            .status(text(ad, "effective_status"))
                            .build());
                }
            }
            url = nextPageUrl(response);
        }
        return result;
    }

    @Override
    public List<ProviderInsightDto> fetchInsights(AdAccountConnection connection, LocalDate fromDate, LocalDate toDate, boolean activeOrPausedOnly) {
        List<ProviderInsightDto> result = new ArrayList<>();
        String timeRange = URLEncoder.encode(
                "{\"since\":\"" + fromDate + "\",\"until\":\"" + toDate + "\"}", StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(baseUrl + "/" + connection.getExternalAccountId()
                + "/insights?level=ad&time_increment=1&limit=500"
                + "&fields=ad_id,campaign_id,spend,impressions,clicks,account_currency"
                + "&time_range=" + timeRange);
        if (activeOrPausedOnly) {
            String filtering = URLEncoder.encode(
                    "[{\"field\":\"ad.effective_status\",\"operator\":\"IN\",\"value\":[\"ACTIVE\",\"PAUSED\"]}]",
                    StandardCharsets.UTF_8);
            urlBuilder.append("&filtering=").append(filtering);
        }
        String url = urlBuilder.toString();

        while (url != null) {
            JsonNode response = get(connection, url);
            JsonNode data = response.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode row : data) {
                    result.add(ProviderInsightDto.builder()
                            .externalAdId(text(row, "ad_id"))
                            .statDate(LocalDate.parse(text(row, "date_start")))
                            .spend(parseDecimal(text(row, "spend")))
                            .currency(text(row, "account_currency"))
                            .impressions(parseLong(text(row, "impressions")))
                            .clicks(parseLong(text(row, "clicks")))
                            .build());
                }
            }
            url = nextPageUrl(response);
        }
        return result;
    }

    @Override
    public AdConnectionTestResult testConnection(AdAccountConnection connection) {
        try {
            JsonNode response = get(connection, baseUrl + "/" + connection.getExternalAccountId()
                    + "?fields=id,name,account_status,currency");
            return AdConnectionTestResult.builder()
                    .success(true)
                    .accountDisplayName(text(response, "name"))
                    .accountCurrency(text(response, "currency"))
                    .message("Connected successfully.")
                    .build();
        } catch (AdsProviderApiException ex) {
            return AdConnectionTestResult.builder().success(false).message(ex.getMessage()).build();
        }
    }

    private JsonNode get(AdAccountConnection connection, String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(connection.getAccessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            // Use the URI overload: Graph fields contain braces, while paging and
            // JSON query values are already encoded. String overloads expand URI
            // templates and encode percent signs a second time.
            URI uri = URI.create(url.replace("{", "%7B").replace("}", "%7D"));
            URI origin = URI.create(baseUrl);
            if (!origin.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !origin.getHost().equalsIgnoreCase(uri.getHost())
                    || origin.getPort() != uri.getPort() || uri.getUserInfo() != null) {
                throw new AdsProviderApiException("Meta pagination returned an unexpected API origin.");
            }
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new AdsProviderApiException("Could not reach Meta's Marketing API — check network access and the base URL.", ex);
        } catch (Exception ex) {
            throw new AdsProviderApiException("Unexpected error calling Meta's Marketing API: " + ex.getMessage(), ex);
        }
    }

    /** Meta hands back a ready-to-call absolute URL for the next page — no cursor bookkeeping needed on our side. */
    private String nextPageUrl(JsonNode response) {
        JsonNode paging = response.get("paging");
        if (paging == null) return null;
        JsonNode next = paging.get("next");
        return next != null && !next.isNull() && !next.asText().isBlank() ? next.asText() : null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private BigDecimal parseDecimal(String value) {
        return value != null ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    private Long parseLong(String value) {
        return value != null ? Long.valueOf(value) : null;
    }

    private AdsProviderApiException toApiException(HttpStatusCodeException ex) {
        String reason = ex.getStatusCode().toString();
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            JsonNode error = body.get("error");
            if (error != null && error.get("message") != null) {
                reason = error.get("message").asText();
            }
        } catch (Exception ignored) {
            // Meta didn't return the expected error shape — fall back to the raw status.
        }
        log.warn("Meta Marketing API call failed: {}", reason);
        return new AdsProviderApiException(reason);
    }
}
