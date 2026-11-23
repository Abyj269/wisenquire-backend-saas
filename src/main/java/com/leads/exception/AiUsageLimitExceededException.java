package com.leads.exception;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Thrown by AiUsageGuardService when a branch has hit its own configured
 * daily/monthly request or token cap (AiBranchSettings) — never thrown for a
 * provider-side rate limit, which surfaces as {@link AiProviderApiException}
 * instead. Carries structured fields (not just a message) because the
 * frontend renders a specific usage banner rather than a generic error toast.
 */
@Getter
public class AiUsageLimitExceededException extends RuntimeException {

    public enum LimitType { DAILY_REQUESTS, MONTHLY_REQUESTS, MONTHLY_TOKENS }

    private final LimitType limitType;
    private final long currentValue;
    private final long capValue;
    private final LocalDateTime resetsAt;

    public AiUsageLimitExceededException(String message, LimitType limitType, long currentValue, long capValue, LocalDateTime resetsAt) {
        super(message);
        this.limitType = limitType;
        this.currentValue = currentValue;
        this.capValue = capValue;
        this.resetsAt = resetsAt;
    }
}
