package com.leads.service;

import com.leads.dto.ai.AiUsageSnapshotDto;
import com.leads.exception.AiUsageLimitExceededException;
import com.leads.exception.AiUsageLimitExceededException.LimitType;
import com.leads.model.entity.AiBranchSettings;
import com.leads.model.entity.AiUsageCounter;
import com.leads.model.entity.Branch;
import com.leads.repository.AiUsageCounterRepository;
import com.leads.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The other half of "avoid unwanted usage/overflow": a durable, atomic,
 * branch-wise usage cap enforced BEFORE every AI provider call. Two-phase
 * because real token usage isn't known until the provider responds:
 * {@link #checkAndReserve} runs in its own short transaction and commits
 * immediately (never wrapping the slow provider HTTP call), then the caller
 * makes that call outside any transaction, then {@link #recordActual}
 * reconciles the estimate to the provider's real reported usage in another
 * short transaction. Deliberately backed by durable DB rows
 * ({@link AiUsageCounter}), not {@link RequestRateLimiter}'s in-memory map,
 * which resets on restart and isn't safe across multiple app instances.
 */
@Service
@RequiredArgsConstructor
public class AiUsageGuardService {
    private final CompanyClock companyClock;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    public enum Feature { REPLY_SUGGESTION, MESSAGE_IMPROVEMENT, TRANSLATION, CONVERSION_SCORING, SUMMARIZATION, TRANSCRIPTION }

    /** ~4 chars per token — a soft admission-control estimate, not a billing reconciliation (real usage reconciles post-call via {@link #recordActual}). Deliberately uniform across providers rather than per-vendor tokenizers. */
    private static final double CHARS_PER_TOKEN = 4.0;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiUsageCounterRepository counterRepo;
    private final AiBranchSettingsService branchSettingsService;
    private final WhatsAppScheduleService scheduleService;
    private final BranchRepository branchRepo;
    private final TenantAccess access;

    @Value
    public static class Reservation {
        Long branchId;
        String dayKey;
        String monthKey;
        long estimatedTokens;
    }

    /**
     * Admits or rejects a call before it's made. On rejection, the
     * transaction (including the counter increment already applied by the
     * upsert) rolls back automatically since {@link AiUsageLimitExceededException}
     * is unchecked — no manual rollback needed.
     */
    @Transactional
    public Reservation checkAndReserve(Long branchId, Feature feature, int estimatedPromptChars, int maxOutputTokens) {
        return doCheckAndReserve(branchId, feature, estimatedPromptChars, maxOutputTokens);
    }

    // ---- module 18 P1 (S3-b, flag ai.short_transactions) ------------------------------------------

    private org.springframework.transaction.support.TransactionTemplate newTransaction() {
        var t = new org.springframework.transaction.support.TransactionTemplate(transactions);
        t.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /**
     * Same admission check as {@link #checkAndReserve}, but always in its OWN transaction: it commits (and so
     * releases the counter row locks) before the caller makes the provider call.
     */
    public Reservation reserveNow(Long branchId, Feature feature, int estimatedPromptChars, int maxOutputTokens) {
        return newTransaction().execute(st -> doCheckAndReserve(branchId, feature, estimatedPromptChars, maxOutputTokens));
    }

    /** {@link #recordActual} in its own short transaction. */
    public void recordActualNow(Reservation reservation, Integer actualPromptTokens, Integer actualCompletionTokens) {
        newTransaction().executeWithoutResult(st -> recordActual(reservation, actualPromptTokens, actualCompletionTokens));
    }

    /** The provider call failed: give the request and its estimated tokens back, so a failure does not count against the cap. */
    public void releaseNow(Reservation reservation) {
        newTransaction().executeWithoutResult(st -> {
            counterRepo.release(reservation.getBranchId(), "DAY", reservation.getDayKey(), reservation.getEstimatedTokens());
            counterRepo.release(reservation.getBranchId(), "MONTH", reservation.getMonthKey(), reservation.getEstimatedTokens());
        });
    }

    /** Guard for the short path: the provider call must not run inside a DB transaction (CLAUDE.md hard rule 2). */
    public static void requireNoTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("An AI provider call must not run inside a DB transaction (hard rule 2).");
    }

    private Reservation doCheckAndReserve(Long branchId, Feature feature, int estimatedPromptChars, int maxOutputTokens) {
        Branch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
        AiBranchSettings settings = branchSettingsService.resolveEffective(branchId);

        if (!isFeatureEnabled(settings, feature)) {
            throw new IllegalStateException("This AI feature is disabled for this branch. Ask an admin to enable it in AI Settings.");
        }

        LocalDateTime now = companyClock.now(branch.getCompany()); // D15: company-local day and month keys
        String dayKey = now.format(DAY_FMT);
        String monthKey = now.format(MONTH_FMT);
        long estimatedTokens = (long) Math.ceil(estimatedPromptChars / CHARS_PER_TOKEN) + Math.max(0, maxOutputTokens);

        Long companyId = branch.getCompany().getId();
        counterRepo.upsertIncrement(branchId, companyId, "DAY", dayKey, estimatedTokens);
        counterRepo.upsertIncrement(branchId, companyId, "MONTH", monthKey, estimatedTokens);

        AiUsageCounter dayCounter = counterRepo.findForUpdate(branchId, "DAY", dayKey)
                .orElseThrow(() -> new IllegalStateException("AI usage counter missing immediately after upsert."));
        AiUsageCounter monthCounter = counterRepo.findForUpdate(branchId, "MONTH", monthKey)
                .orElseThrow(() -> new IllegalStateException("AI usage counter missing immediately after upsert."));

        if (dayCounter.getRequestCount() > settings.getDailyRequestCap()) {
            throw limitExceeded(LimitType.DAILY_REQUESTS, dayCounter.getRequestCount(), settings.getDailyRequestCap(), companyClock.toServer(branch.getCompany(), startOfNextDay(now)));
        }
        if (monthCounter.getRequestCount() > settings.getMonthlyRequestCap()) {
            throw limitExceeded(LimitType.MONTHLY_REQUESTS, monthCounter.getRequestCount(), settings.getMonthlyRequestCap(), companyClock.toServer(branch.getCompany(), startOfNextMonth(now)));
        }
        if (monthCounter.getEstimatedTokens() > settings.getMonthlyTokenCap()) {
            throw limitExceeded(LimitType.MONTHLY_TOKENS, monthCounter.getEstimatedTokens(), settings.getMonthlyTokenCap(), companyClock.toServer(branch.getCompany(), startOfNextMonth(now)));
        }

        return new Reservation(branchId, dayKey, monthKey, estimatedTokens);
    }

    /**
     * Reconciles the pre-call estimate to the provider's real reported usage.
     * Deliberately NOT rolled back or re-checked against the cap if the
     * actual total runs slightly over the estimate — the request was already
     * admitted; this only keeps future admission decisions accurate.
     * A provider that reports no usage at all leaves the pre-call estimate
     * standing (delta 0), which is the safe direction.
     */
    @Transactional
    public void recordActual(Reservation reservation, Integer actualPromptTokens, Integer actualCompletionTokens) {
        if (actualPromptTokens == null && actualCompletionTokens == null) return;
        long actualTotal = (actualPromptTokens != null ? actualPromptTokens : 0) + (actualCompletionTokens != null ? actualCompletionTokens : 0);
        long delta = actualTotal - reservation.getEstimatedTokens();
        if (delta == 0) return;
        counterRepo.applyTokenDelta(reservation.getBranchId(), "DAY", reservation.getDayKey(), delta);
        counterRepo.applyTokenDelta(reservation.getBranchId(), "MONTH", reservation.getMonthKey(), delta);
    }

    @Transactional(readOnly = true)
    public AiUsageSnapshotDto getUsageSnapshot(Long branchId) {
        access.branch(branchId); // F-14: the caller must be allowed to see this branch
        Branch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
        AiBranchSettings settings = branchSettingsService.resolveEffective(branchId);
        LocalDateTime now = companyClock.now(branch.getCompany()); // D15: company-local day and month keys
        String dayKey = now.format(DAY_FMT);
        String monthKey = now.format(MONTH_FMT);

        long dayCount = counterRepo.findByBranchIdAndPeriodTypeAndPeriodKey(branchId, "DAY", dayKey)
                .map(AiUsageCounter::getRequestCount).orElse(0L);
        AiUsageCounter monthCounter = counterRepo.findByBranchIdAndPeriodTypeAndPeriodKey(branchId, "MONTH", monthKey).orElse(null);
        long monthCount = monthCounter != null ? monthCounter.getRequestCount() : 0L;
        long monthTokens = monthCounter != null ? monthCounter.getEstimatedTokens() : 0L;

        boolean approaching = crossesThreshold(dayCount, settings.getDailyRequestCap(), settings.getNotifyThresholdPercent())
                || crossesThreshold(monthCount, settings.getMonthlyRequestCap(), settings.getNotifyThresholdPercent())
                || crossesThreshold(monthTokens, settings.getMonthlyTokenCap(), settings.getNotifyThresholdPercent());

        return AiUsageSnapshotDto.builder()
                .branchId(branchId)
                .dayRequestCount(dayCount)
                .dailyRequestCap(settings.getDailyRequestCap())
                .monthRequestCount(monthCount)
                .monthlyRequestCap(settings.getMonthlyRequestCap())
                .monthEstimatedTokens(monthTokens)
                .monthlyTokenCap(settings.getMonthlyTokenCap())
                .notifyThresholdPercent(settings.getNotifyThresholdPercent())
                .approachingLimit(approaching)
                .build();
    }

    private boolean crossesThreshold(long value, long cap, int thresholdPercent) {
        return cap > 0 && value * 100.0 / cap >= thresholdPercent;
    }

    private boolean isFeatureEnabled(AiBranchSettings settings, Feature feature) {
        return switch (feature) {
            case REPLY_SUGGESTION -> Boolean.TRUE.equals(settings.getReplySuggestionEnabled());
            case MESSAGE_IMPROVEMENT -> Boolean.TRUE.equals(settings.getMessageImprovementEnabled());
            case TRANSLATION -> Boolean.TRUE.equals(settings.getTranslationEnabled());
            case CONVERSION_SCORING -> Boolean.TRUE.equals(settings.getConversionScoringEnabled());
            // Summarization/transcription (the "extra tools") ride on the same
            // enable switches as the closest feature they resemble rather than
            // needing two more toggle columns for a near-identical purpose.
            case SUMMARIZATION -> Boolean.TRUE.equals(settings.getConversionScoringEnabled());
            case TRANSCRIPTION -> Boolean.TRUE.equals(settings.getTranslationEnabled());
        };
    }

    private AiUsageLimitExceededException limitExceeded(LimitType type, long current, long cap, LocalDateTime resetsAt) {
        String label = switch (type) {
            case DAILY_REQUESTS -> "daily AI request limit";
            case MONTHLY_REQUESTS -> "monthly AI request limit";
            case MONTHLY_TOKENS -> "monthly AI token limit";
        };
        return new AiUsageLimitExceededException(
                "This branch has reached its " + label + " (" + current + "/" + cap + "). Ask an admin to raise it in AI Settings, or try again after it resets.",
                type, current, cap, resetsAt);
    }

    // resetsAt (module 18 part 2, D15): computed on the company's calendar, returned as server wall-clock like
    // every timestamp so the FE companyDate pipe shows the company's midnight.
    private LocalDateTime startOfNextDay(LocalDateTime now) {
        return now.toLocalDate().plusDays(1).atStartOfDay();
    }

    private LocalDateTime startOfNextMonth(LocalDateTime now) {
        return now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
    }
}
