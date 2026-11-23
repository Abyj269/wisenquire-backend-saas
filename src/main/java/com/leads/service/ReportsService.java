package com.leads.service;

import com.leads.dto.reports.*;
import com.leads.dto.settings.CompanyLeadSettingsDto;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.StageType;
import com.leads.repository.CustomerRepository;
import com.leads.repository.LeadTimelineActivityRepository;
import com.leads.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owner/decision-maker facing reports. Everything here reuses the same
 * company/branch/date filtering as the pipeline grid and dashboard metrics
 * ({@link LeadService#findLeadsForReport}) and aggregates in memory with
 * Java streams, matching {@link LeadService#getCompanyMetrics} rather than
 * introducing native SQL — dataset sizes here are SMB-CRM scale.
 */
@Service
@RequiredArgsConstructor
public class ReportsService {
    private final TenantAccess access;

    /** Minutes considered "on time" for a first response to a new lead — fixed v1 benchmark. */
    private static final int SLA_TARGET_MINUTES = 60;

    /** Trend chart bucket granularity thresholds, by selected range span in days. */
    private static final int DAILY_TREND_MAX_DAYS = 45;
    private static final int WEEKLY_TREND_MAX_DAYS = 180;

    private static final Set<ActivityType> SYSTEM_ACTIVITY_TYPES =
            EnumSet.of(ActivityType.CREATED, ActivityType.STATUS_CHANGE, ActivityType.ASSIGNMENT);

    private final LeadService leadService;
    private final CustomerRepository customerRepo;
    private final LeadTimelineActivityRepository activityRepo;
    private final WhatsAppMessageRepository whatsAppMessageRepo;
    private final LeadSettingsService leadSettingsService;

    // ==================== Customers: Acquired / Regained / Lost ====================

    @Transactional(readOnly = true)
    public CustomerReportDto getCustomerReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        if (branchId == null) access.companyWide(companyId);
        CompanyLeadSettingsDto settings = leadSettingsService.getCompanyLeadSettings(companyId);
        int lostAfterDays = settings.getCustomerLostAfterDays() != null ? settings.getCustomerLostAfterDays() : 60;

        List<Customer> customers = branchId != null
                ? customerRepo.findByCompanyIdAndHomeBranchId(companyId, branchId)
                : customerRepo.findByCompanyId(companyId);

        LocalDateTime rangeStart = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime rangeEndExclusive = toDate != null ? toDate.plusDays(1).atStartOfDay() : null;
        LocalDateTime now = LocalDateTime.now();

        List<CustomerSummaryDto> acquired = customers.stream()
                .filter(c -> c.getFirstLeadAt() != null && inRange(c.getFirstLeadAt(), rangeStart, rangeEndExclusive))
                .sorted(Comparator.comparing(Customer::getFirstLeadAt).reversed())
                .map(c -> toSummary(c, c.getFirstLeadAt(), null))
                .collect(Collectors.toList());

        // A customer counts as "lost" for this period when their last known
        // activity falls inside the selected range AND that activity is
        // already more than the threshold behind *today* — i.e. "who went
        // quiet during this window and still hasn't come back".
        List<CustomerSummaryDto> lost = customers.stream()
                .filter(c -> c.getLastLeadAt() != null
                        && inRange(c.getLastLeadAt(), rangeStart, rangeEndExclusive)
                        && ChronoUnit.DAYS.between(c.getLastLeadAt(), now) >= lostAfterDays)
                .sorted(Comparator.comparing(Customer::getLastLeadAt))
                .map(c -> toSummary(c, c.getLastLeadAt(), (int) ChronoUnit.DAYS.between(c.getLastLeadAt(), now)))
                .collect(Collectors.toList());

        List<CustomerSummaryDto> regained = findRegainedCustomers(companyId, branchId, lostAfterDays, rangeStart, rangeEndExclusive);

        return CustomerReportDto.builder()
                .customerLostAfterDays(lostAfterDays)
                .newlyAcquiredCount(acquired.size())
                .newlyAcquired(acquired)
                .regainedCount(regained.size())
                .regained(regained)
                .lostCount(lost.size())
                .lost(lost)
                .trend(buildCustomerTrend(acquired, regained, lost, fromDate, toDate))
                .build();
    }

    // A customer is "regained" when a later lead breaks a dormancy gap of at
    // least the threshold, and that comeback lead falls inside the selected
    // range. Needs the customer's *full* lead history (no date bound) to
    // find the gap, which is why this runs a separate, unbounded fetch.
    private List<CustomerSummaryDto> findRegainedCustomers(Long companyId, Long branchId, int lostAfterDays,
                                                             LocalDateTime rangeStart, LocalDateTime rangeEndExclusive) {
        List<Lead> allLeads = leadService.findLeadsForReport(companyId, branchId, null, null);
        Map<Long, List<Lead>> leadsByCustomer = allLeads.stream()
                .filter(l -> l.getCustomer() != null)
                .collect(Collectors.groupingBy(l -> l.getCustomer().getId()));

        List<CustomerSummaryDto> regained = new ArrayList<>();
        for (List<Lead> customerLeads : leadsByCustomer.values()) {
            customerLeads.sort(Comparator.comparing(Lead::getCreatedAt));
            for (int i = 1; i < customerLeads.size(); i++) {
                LocalDateTime prev = customerLeads.get(i - 1).getCreatedAt();
                LocalDateTime next = customerLeads.get(i).getCreatedAt();
                long gapDays = ChronoUnit.DAYS.between(prev, next);
                if (gapDays >= lostAfterDays && inRange(next, rangeStart, rangeEndExclusive)) {
                    regained.add(toSummary(customerLeads.get(i).getCustomer(), next, (int) gapDays));
                }
            }
        }
        regained.sort(Comparator.comparing(CustomerSummaryDto::getReferenceDate).reversed());
        return regained;
    }

    // Bucket granularity adapts to the selected range's span: short ranges
    // (Day/Week/WTD/MTD and similar custom ranges) get daily points so the
    // line chart actually has a trend to draw, longer ranges fall back to
    // weekly then monthly so the chart doesn't end up with hundreds of points.
    private List<TrendPointDto> buildCustomerTrend(List<CustomerSummaryDto> acquired, List<CustomerSummaryDto> regained,
                                                     List<CustomerSummaryDto> lost, LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) return List.of();
        // A single-day range (the "Day" filter, or a custom range collapsed to
        // one day) has nothing to bucket by day — go hourly instead so the
        // chart still has a trend to draw.
        if (fromDate.isEqual(toDate)) {
            return buildHourlyTrendBuckets(acquired, regained, lost, fromDate);
        }
        long spanDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (spanDays <= DAILY_TREND_MAX_DAYS) {
            return buildTrendBuckets(acquired, regained, lost, fromDate, toDate, 1, ChronoUnit.DAYS, DateTimeFormatter.ofPattern("d MMM"));
        }
        if (spanDays <= WEEKLY_TREND_MAX_DAYS) {
            return buildTrendBuckets(acquired, regained, lost, fromDate, toDate, 7, ChronoUnit.DAYS, DateTimeFormatter.ofPattern("d MMM"));
        }
        return buildTrendBuckets(acquired, regained, lost, fromDate.withDayOfMonth(1), toDate.withDayOfMonth(1), 1, ChronoUnit.MONTHS, DateTimeFormatter.ofPattern("MMM yyyy"));
    }

    private List<TrendPointDto> buildTrendBuckets(List<CustomerSummaryDto> acquired, List<CustomerSummaryDto> regained,
            List<CustomerSummaryDto> lost, LocalDate start, LocalDate end, int stepAmount, ChronoUnit stepUnit, DateTimeFormatter labelFmt) {
        List<TrendPointDto> buckets = new ArrayList<>();
        LocalDate cursor = start;
        int guard = 0;
        while (!cursor.isAfter(end) && guard < 400) {
            LocalDateTime bucketStart = cursor.atStartOfDay();
            LocalDateTime bucketEndExclusive = cursor.plus(stepAmount, stepUnit).atStartOfDay();
            buckets.add(TrendPointDto.builder()
                    .label(cursor.format(labelFmt))
                    .acquired(acquired.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .regained(regained.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .lost(lost.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .build());
            cursor = cursor.plus(stepAmount, stepUnit);
            guard++;
        }
        return buckets;
    }

    private List<TrendPointDto> buildHourlyTrendBuckets(List<CustomerSummaryDto> acquired, List<CustomerSummaryDto> regained,
            List<CustomerSummaryDto> lost, LocalDate day) {
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("h a");
        List<TrendPointDto> buckets = new ArrayList<>();
        LocalDateTime cursor = day.atStartOfDay();
        LocalDateTime dayEndExclusive = day.plusDays(1).atStartOfDay();
        while (cursor.isBefore(dayEndExclusive)) {
            final LocalDateTime bucketStart = cursor;
            final LocalDateTime bucketEndExclusive = cursor.plusHours(1);
            buckets.add(TrendPointDto.builder()
                    .label(bucketStart.format(labelFmt))
                    .acquired(acquired.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .regained(regained.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .lost(lost.stream().filter(x -> inRange(x.getReferenceDate(), bucketStart, bucketEndExclusive)).count())
                    .build());
            cursor = bucketEndExclusive;
        }
        return buckets;
    }

    private CustomerSummaryDto toSummary(Customer c, LocalDateTime referenceDate, Integer gapDays) {
        return CustomerSummaryDto.builder()
                .id(c.getId())
                .name(c.getName())
                .mobileNumber(c.getMobileNumber())
                .homeBranchName(c.getHomeBranch() != null ? c.getHomeBranch().getName() : null)
                .referenceDate(referenceDate)
                .gapDays(gapDays)
                .build();
    }

    // ==================== Staff Performance ====================

    @Transactional(readOnly = true)
    public StaffPerformanceReportDto getStaffPerformanceReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        if (branchId == null) access.companyWide(companyId);
        List<Lead> assignedLeads = leadService.findLeadsForReport(companyId, branchId, fromDate, toDate).stream()
                .filter(l -> l.getAssignedTo() != null)
                .collect(Collectors.toList());
        Map<Long, List<Lead>> byUser = assignedLeads.stream()
                .collect(Collectors.groupingBy(l -> l.getAssignedTo().getId()));

        LocalDateTime fromDt = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime toDt = toDate != null ? toDate.plusDays(1).atStartOfDay() : null;
        Map<Long, Long> messageCounts = whatsAppMessageRepo.countOutboundByUser(companyId, branchId, fromDt, toDt).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        Map<Long, Double> avgResponseByUser = computeAvgResponseMinutesByUser(byUser);
        long maxVolume = byUser.values().stream().mapToLong(List::size).max().orElse(0);

        List<StaffPerformanceDto> staffList = new ArrayList<>();
        for (Map.Entry<Long, List<Lead>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<Lead> userLeads = entry.getValue();
            User user = userLeads.get(0).getAssignedTo();
            long assigned = userLeads.size();
            long converted = userLeads.stream().filter(this::isConverted).count();
            double conversionRate = assigned > 0 ? round1((double) converted / assigned * 100.0) : 0.0;
            Double avgResponse = avgResponseByUser.get(userId);
            long messagesSent = messageCounts.getOrDefault(userId, 0L);

            // Composite score: 50% conversion rate, 30% response speed (vs. the
            // SLA benchmark, neutral 50 when unmeasured), 20% volume relative
            // to the team's busiest agent this period. A v1 heuristic, easy to
            // retune once real usage data shows what actually matters most.
            double responseScore = avgResponse != null
                    ? Math.max(0, Math.min(100, 100 - (avgResponse / SLA_TARGET_MINUTES * 100)))
                    : 50.0;
            double volumeScore = maxVolume > 0 ? (double) assigned / maxVolume * 100.0 : 0.0;
            double score = round1(0.5 * conversionRate + 0.3 * responseScore + 0.2 * volumeScore);

            staffList.add(StaffPerformanceDto.builder()
                    .userId(userId)
                    .name(user.getFullName())
                    .leadsAssigned(assigned)
                    .leadsConverted(converted)
                    .conversionRate(conversionRate)
                    .avgResponseMinutes(avgResponse != null ? round1(avgResponse) : null)
                    .whatsappMessagesSent(messagesSent)
                    .score(score)
                    .build());
        }

        staffList.sort(Comparator.comparingDouble(StaffPerformanceDto::getScore).reversed());
        for (int i = 0; i < staffList.size(); i++) {
            staffList.get(i).setRank(i + 1);
        }

        double teamAvg = staffList.stream().mapToDouble(StaffPerformanceDto::getScore).average().orElse(0);
        String topPerformer = staffList.isEmpty() ? null : staffList.get(0).getName();

        return StaffPerformanceReportDto.builder()
                .staff(staffList)
                .teamAverageScore(round1(teamAvg))
                .topPerformerName(topPerformer)
                .build();
    }

    private Map<Long, Double> computeAvgResponseMinutesByUser(Map<Long, List<Lead>> byUser) {
        List<Long> leadIds = byUser.values().stream().flatMap(List::stream).map(Lead::getId).collect(Collectors.toList());
        Map<Long, LeadTimelineActivity> firstResponseByLead = firstResponsePerLead(leadIds);

        Map<Long, Double> result = new HashMap<>();
        for (Map.Entry<Long, List<Lead>> entry : byUser.entrySet()) {
            List<Long> minutes = responseMinutesFor(entry.getValue(), firstResponseByLead);
            if (!minutes.isEmpty()) {
                result.put(entry.getKey(), minutes.stream().mapToLong(Long::longValue).average().orElse(0));
            }
        }
        return result;
    }

    /** First non-system (agent-driven) activity per lead — a first-response-time proxy since there's no dedicated timestamp tracked today. */
    private Map<Long, LeadTimelineActivity> firstResponsePerLead(List<Long> leadIds) {
        if (leadIds.isEmpty()) return Map.of();
        Map<Long, LeadTimelineActivity> firstByLead = new HashMap<>();
        for (LeadTimelineActivity a : activityRepo.findByLeadIdInOrderByCreatedAtAsc(leadIds)) {
            if (SYSTEM_ACTIVITY_TYPES.contains(a.getActivityType())) continue;
            firstByLead.putIfAbsent(a.getLead().getId(), a);
        }
        return firstByLead;
    }

    private List<Long> responseMinutesFor(List<Lead> leads, Map<Long, LeadTimelineActivity> firstResponseByLead) {
        List<Long> minutes = new ArrayList<>();
        for (Lead lead : leads) {
            LeadTimelineActivity first = firstResponseByLead.get(lead.getId());
            if (first != null && lead.getCreatedAt() != null) {
                long m = Duration.between(lead.getCreatedAt(), first.getCreatedAt()).toMinutes();
                if (m >= 0) minutes.add(m);
            }
        }
        return minutes;
    }

    // ==================== Lead Source ROI ====================

    @Transactional(readOnly = true)
    public List<LeadSourceRoiDto> getLeadSourceRoiReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        if (branchId == null) access.companyWide(companyId);
        List<Lead> leads = leadService.findLeadsForReport(companyId, branchId, fromDate, toDate);
        Map<String, List<Lead>> bySource = leads.stream().collect(Collectors.groupingBy(l -> l.getSource().getSourceCode()));

        List<LeadSourceRoiDto> result = new ArrayList<>();
        for (List<Lead> sourceLeads : bySource.values()) {
            LeadSource source = sourceLeads.get(0).getSource();
            long count = sourceLeads.size();
            long converted = sourceLeads.stream().filter(this::isConverted).count();
            BigDecimal revenue = sumDealValue(sourceLeads.stream().filter(this::isConverted));
            result.add(LeadSourceRoiDto.builder()
                    .sourceCode(source.getSourceCode())
                    .sourceName(source.getSourceName())
                    .colorCode(source.getColorCode())
                    .leadsCount(count)
                    .convertedCount(converted)
                    .conversionRate(count > 0 ? round1((double) converted / count * 100.0) : 0.0)
                    .revenue(revenue)
                    .build());
        }
        result.sort(Comparator.comparing(LeadSourceRoiDto::getRevenue).reversed());
        return result;
    }

    // ==================== Branch Comparison ====================

    @Transactional(readOnly = true)
    public List<BranchComparisonDto> getBranchComparisonReport(Long companyId, LocalDate fromDate, LocalDate toDate) {
        access.companyWide(companyId);
        List<Lead> leads = leadService.findLeadsForReport(companyId, null, fromDate, toDate);
        Map<Long, List<Lead>> byBranch = leads.stream().collect(Collectors.groupingBy(l -> l.getBranch().getId()));

        LocalDateTime rangeStart = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime rangeEndExclusive = toDate != null ? toDate.plusDays(1).atStartOfDay() : null;
        Map<Long, Long> newCustomersByBranch = customerRepo.findByCompanyId(companyId).stream()
                .filter(c -> c.getHomeBranch() != null && c.getFirstLeadAt() != null && inRange(c.getFirstLeadAt(), rangeStart, rangeEndExclusive))
                .collect(Collectors.groupingBy(c -> c.getHomeBranch().getId(), Collectors.counting()));

        List<BranchComparisonDto> result = new ArrayList<>();
        for (List<Lead> branchLeads : byBranch.values()) {
            Branch branch = branchLeads.get(0).getBranch();
            long count = branchLeads.size();
            long converted = branchLeads.stream().filter(this::isConverted).count();
            result.add(BranchComparisonDto.builder()
                    .branchId(branch.getId())
                    .branchName(branch.getName())
                    .leadsCount(count)
                    .convertedCount(converted)
                    .conversionRate(count > 0 ? round1((double) converted / count * 100.0) : 0.0)
                    .revenue(sumDealValue(branchLeads.stream().filter(this::isConverted)))
                    .newCustomersAcquired(newCustomersByBranch.getOrDefault(branch.getId(), 0L))
                    .build());
        }
        result.sort(Comparator.comparing(BranchComparisonDto::getRevenue).reversed());
        return result;
    }

    // ==================== Response Time & SLA ====================

    @Transactional(readOnly = true)
    public ResponseTimeReportDto getResponseTimeReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        if (branchId == null) access.companyWide(companyId);
        List<Lead> leads = leadService.findLeadsForReport(companyId, branchId, fromDate, toDate);
        Map<Long, LeadTimelineActivity> firstResponseByLead =
                firstResponsePerLead(leads.stream().map(Lead::getId).collect(Collectors.toList()));

        Map<Long, List<Lead>> byUser = leads.stream()
                .filter(l -> l.getAssignedTo() != null)
                .collect(Collectors.groupingBy(l -> l.getAssignedTo().getId()));

        List<Long> allMinutes = new ArrayList<>();
        List<StaffResponseTimeDto> byStaff = new ArrayList<>();
        for (List<Lead> userLeads : byUser.values()) {
            List<Long> minutes = responseMinutesFor(userLeads, firstResponseByLead);
            if (minutes.isEmpty()) continue;
            allMinutes.addAll(minutes);
            User user = userLeads.get(0).getAssignedTo();
            long onTime = minutes.stream().filter(m -> m <= SLA_TARGET_MINUTES).count();
            byStaff.add(StaffResponseTimeDto.builder()
                    .userId(user.getId())
                    .name(user.getFullName())
                    .avgResponseMinutes(round1(minutes.stream().mapToLong(Long::longValue).average().orElse(0)))
                    .medianResponseMinutes(round1(median(minutes)))
                    .slaCompliancePercent(round1((double) onTime / minutes.size() * 100.0))
                    .leadsResponded(minutes.size())
                    .build());
        }
        byStaff.sort(Comparator.comparingDouble(StaffResponseTimeDto::getAvgResponseMinutes));

        long overallOnTime = allMinutes.stream().filter(m -> m <= SLA_TARGET_MINUTES).count();
        return ResponseTimeReportDto.builder()
                .slaTargetMinutes(SLA_TARGET_MINUTES)
                .overallAvgResponseMinutes(round1(allMinutes.stream().mapToLong(Long::longValue).average().orElse(0)))
                .overallMedianResponseMinutes(round1(median(allMinutes)))
                .slaCompliancePercent(allMinutes.isEmpty() ? 0 : round1((double) overallOnTime / allMinutes.size() * 100.0))
                .byStaff(byStaff)
                .build();
    }

    private double median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    // ==================== Revenue & Pipeline Forecast ====================

    @Transactional(readOnly = true)
    public RevenueForecastDto getRevenueForecastReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        if (branchId == null) access.companyWide(companyId);
        List<Lead> leads = leadService.findLeadsForReport(companyId, branchId, fromDate, toDate);
        Map<StageType, List<Lead>> byStage = leads.stream()
                .collect(Collectors.groupingBy(l -> l.getStatus().getStageType()));

        List<StageValueDto> stageValues = byStage.entrySet().stream()
                .map(e -> StageValueDto.builder()
                        .stageType(e.getKey())
                        .leadCount(e.getValue().size())
                        .totalValue(sumDealValue(e.getValue().stream()))
                        .build())
                .sorted(Comparator.comparing(StageValueDto::getStageType))
                .collect(Collectors.toList());

        BigDecimal totalValue = sumDealValue(leads.stream());
        BigDecimal wonRevenue = sumDealValue(leads.stream().filter(this::isConverted));

        Set<StageType> openStages = EnumSet.complementOf(EnumSet.of(StageType.CONVERTED, StageType.LOST, StageType.CLOSED));
        BigDecimal openValue = sumDealValue(leads.stream().filter(l -> openStages.contains(l.getStatus().getStageType())));

        long total = leads.size();
        long converted = leads.stream().filter(this::isConverted).count();
        double conversionRate = total > 0 ? round1((double) converted / total * 100.0) : 0.0;
        BigDecimal forecast = openValue.multiply(BigDecimal.valueOf(conversionRate / 100.0)).setScale(2, RoundingMode.HALF_UP);

        return RevenueForecastDto.builder()
                .byStage(stageValues)
                .totalPipelineValue(totalValue)
                .wonRevenue(wonRevenue)
                .openPipelineValue(openValue)
                .companyConversionRate(conversionRate)
                .forecastedRevenue(forecast)
                .build();
    }

    // ==================== Shared helpers ====================

    private boolean isConverted(Lead l) {
        return Boolean.TRUE.equals(l.getIsConverted()) || l.getStatus().getStageType() == StageType.CONVERTED;
    }

    private BigDecimal sumDealValue(java.util.stream.Stream<Lead> leads) {
        return leads.map(Lead::getEstimatedDealValue).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean inRange(LocalDateTime dt, LocalDateTime start, LocalDateTime endExclusive) {
        if (dt == null) return false;
        if (start != null && dt.isBefore(start)) return false;
        return endExclusive == null || dt.isBefore(endExclusive);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
