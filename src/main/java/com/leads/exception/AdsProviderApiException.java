package com.leads.exception;

/**
 * Thrown when a call to a connected ad provider's API (Meta Marketing/Graph
 * API, Google Ads API) fails — invalid/revoked token, bad account id, rate
 * limiting, network/timeout, a Google refresh-token exchange failure, etc.
 * Shared across both providers so AdSyncConnectionService's per-connection
 * error handling doesn't need provider-specific catch blocks.
 */
public class AdsProviderApiException extends RuntimeException {
    public AdsProviderApiException(String message) {
        super(message);
    }

    public AdsProviderApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
