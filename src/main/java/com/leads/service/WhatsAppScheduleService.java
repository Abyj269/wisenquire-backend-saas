package com.leads.service;

import com.leads.dto.whatsapp.*;
import com.leads.model.entity.Branch;
import com.leads.model.entity.BranchWorkingHours;
import com.leads.model.entity.Company;
import com.leads.model.entity.WhatsAppAutoReplySettings;
import com.leads.model.entity.WhatsAppHoliday;
import com.leads.repository.BranchRepository;
import com.leads.repository.BranchWorkingHoursRepository;
import com.leads.repository.CompanyRepository;
import com.leads.repository.WhatsAppAutoReplySettingsRepository;
import com.leads.repository.WhatsAppHolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Branch-wise working hours, the company's holiday calendar, and the three
 * common auto-reply messages (working hours / non-working hours / holiday) —
 * plus the decision logic ({@link #resolveAutoReply}) that turns all three
 * into "what, if anything, should we send back right now" for a given
 * branch. Called from {@link WhatsAppCloudService} on every inbound message.
 *
 * The server's own clock (Asia/Dubai, fixed +04:00, no DST) is the one fixed
 * reference point every conversion starts from — see the note on
 * {@link Company#getTimezone()}.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppScheduleService {
    private final TenantAccess access;

    /** D15: calendar decisions go through CompanyClock (01 Part B item 11); SERVER_ZONE now lives there. */
    private final CompanyClock companyClock;

    private final BranchWorkingHoursRepository workingHoursRepo;
    private final WhatsAppHolidayRepository holidayRepo;
    private final WhatsAppAutoReplySettingsRepository autoReplySettingsRepo;
    private final BranchRepository branchRepo;
    private final CompanyRepository companyRepo;

    public record ResolvedAutoReply(String text, String mediaData, String mediaMimeType) {}

    // ---------- Working hours ----------

    @Transactional(readOnly = true)
    public List<WorkingHoursDayDto> getWorkingHours(Long branchId) {
        access.branch(branchId);
        List<BranchWorkingHours> rows = workingHoursRepo.findByBranchIdOrderByDayOfWeek(branchId);
        return rows.stream().map(this::toDayDto).collect(Collectors.toList());
    }

    @Transactional
    public void saveWorkingHours(WorkingHoursUpsertRequest req) {
        validateSchedule(req);
        req.getBranchIds().forEach(access::branch);
        for (Long branchId : req.getBranchIds()) {
            Branch branch = branchRepo.findById(branchId)
                    .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
            for (WorkingHoursDayDto day : req.getDays()) {
                BranchWorkingHours row = workingHoursRepo.findByBranchIdAndDayOfWeek(branchId, day.getDayOfWeek())
                        .orElseGet(() -> BranchWorkingHours.builder().branch(branch).dayOfWeek(day.getDayOfWeek()).build());
                row.setIsOpen(day.getIsOpen() != null ? day.getIsOpen() : true);
                row.setOpenTime(day.getOpenTime());
                row.setCloseTime(day.getCloseTime());
                workingHoursRepo.save(row);
            }
        }
    }

    // ---------- Holidays ----------

    @Transactional(readOnly = true)
    public List<WhatsAppHolidayDto> listHolidays(Long companyId) {
        access.companyWide(companyId);
        return holidayRepo.findByCompanyIdOrderByStartDate(companyId).stream()
                .map(this::toHolidayDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public WhatsAppHolidayDto createHoliday(WhatsAppHolidayUpsertRequest req) {
        access.scope(req.getCompanyId(), null);
        req.getBranchIds().forEach(id -> access.scope(req.getCompanyId(), id));
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + req.getCompanyId()));
        WhatsAppHoliday holiday = WhatsAppHoliday.builder().company(company).build();
        applyHolidayFields(holiday, req);
        return toHolidayDto(holidayRepo.save(holiday));
    }

    @Transactional
    public WhatsAppHolidayDto updateHoliday(Long id, WhatsAppHolidayUpsertRequest req) {
        access.record(com.leads.model.entity.WhatsAppHoliday.class, id);
        req.getBranchIds().forEach(b -> access.scope(req.getCompanyId(), b));
        WhatsAppHoliday holiday = holidayRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found: " + id));
        applyHolidayFields(holiday, req);
        return toHolidayDto(holidayRepo.save(holiday));
    }

    @Transactional
    public void deleteHoliday(Long id) {
        access.record(com.leads.model.entity.WhatsAppHoliday.class, id);
        if (!holidayRepo.existsById(id)) {
            throw new IllegalArgumentException("Holiday not found: " + id);
        }
        holidayRepo.deleteById(id);
    }

    private void applyHolidayFields(WhatsAppHoliday holiday, WhatsAppHolidayUpsertRequest req) {
        if (req.getStartDate() == null || req.getEndDate() == null || req.getStartDate().isAfter(req.getEndDate())) {
            throw new IllegalArgumentException("Holiday end date must be on or after its start date.");
        }
        if (!holiday.getCompany().getId().equals(req.getCompanyId())) {
            throw new IllegalArgumentException("A holiday cannot move to another company.");
        }
        holiday.setName(req.getName());
        holiday.setStartDate(req.getStartDate());
        holiday.setEndDate(req.getEndDate());
        holiday.setMessageText(req.getMessageText());

        if (Boolean.TRUE.equals(req.getRemoveMedia())) {
            holiday.setMediaData(null);
            holiday.setMediaMimeType(null);
        } else if (req.getMediaData() != null && !req.getMediaData().isBlank()) {
            holiday.setMediaData(req.getMediaData());
            holiday.setMediaMimeType(req.getMediaMimeType());
        }

        Set<Branch> branches = new LinkedHashSet<>(branchRepo.findAllById(req.getBranchIds()));
        holiday.setBranches(branches);
    }

    // ---------- Auto-reply settings ----------

    @Transactional(readOnly = true)
    public WhatsAppAutoReplySettingsDto getAutoReplySettings(Long companyId) {
        access.company(companyId);
        return autoReplySettingsRepo.findByCompanyId(companyId)
                .map(this::toSettingsDto)
                .orElseGet(() -> WhatsAppAutoReplySettingsDto.builder()
                        .workingHoursEnabled(false).nonWorkingHoursEnabled(false).holidayEnabled(false)
                        .workingHoursHasMedia(false).nonWorkingHoursHasMedia(false).holidayHasMedia(false)
                        .build());
    }

    @Transactional
    public WhatsAppAutoReplySettingsDto saveAutoReplySettings(Long companyId, WhatsAppAutoReplySettingsUpsertRequest req) {
        access.companyWide(companyId);
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
        WhatsAppAutoReplySettings settings = autoReplySettingsRepo.findByCompanyId(companyId)
                .orElseGet(() -> WhatsAppAutoReplySettings.builder().company(company).build());

        settings.setWorkingHoursEnabled(Boolean.TRUE.equals(req.getWorkingHoursEnabled()));
        settings.setWorkingHoursMessage(req.getWorkingHoursMessage());
        applyMedia(req.getWorkingHoursRemoveMedia(), req.getWorkingHoursMediaData(), req.getWorkingHoursMediaMimeType(),
                settings::setWorkingHoursMediaData, settings::setWorkingHoursMediaMimeType);

        settings.setNonWorkingHoursEnabled(Boolean.TRUE.equals(req.getNonWorkingHoursEnabled()));
        settings.setNonWorkingHoursMessage(req.getNonWorkingHoursMessage());
        applyMedia(req.getNonWorkingHoursRemoveMedia(), req.getNonWorkingHoursMediaData(), req.getNonWorkingHoursMediaMimeType(),
                settings::setNonWorkingHoursMediaData, settings::setNonWorkingHoursMediaMimeType);

        settings.setHolidayEnabled(Boolean.TRUE.equals(req.getHolidayEnabled()));
        settings.setHolidayMessage(req.getHolidayMessage());
        applyMedia(req.getHolidayRemoveMedia(), req.getHolidayMediaData(), req.getHolidayMediaMimeType(),
                settings::setHolidayMediaData, settings::setHolidayMediaMimeType);

        return toSettingsDto(autoReplySettingsRepo.save(settings));
    }

    private void applyMedia(Boolean remove, String data, String mimeType, java.util.function.Consumer<String> setData, java.util.function.Consumer<String> setMime) {
        if (Boolean.TRUE.equals(remove)) {
            setData.accept(null);
            setMime.accept(null);
        } else if (data != null && !data.isBlank()) {
            setData.accept(data);
            setMime.accept(mimeType);
        }
    }

    // ---------- Decision logic ----------

    /** What, if anything, should be auto-replied right now for this branch. Empty when nothing is configured/enabled. */
    @Transactional(readOnly = true)
    public Optional<ResolvedAutoReply> resolveAutoReply(Branch branch) {
        Company company = branch.getCompany();
        LocalDateTime companyNow = companyClock.now(company);
        LocalDate today = companyNow.toLocalDate();

        List<WhatsAppHoliday> activeHolidays = holidayRepo.findActiveForBranch(branch.getId(), today);
        if (!activeHolidays.isEmpty()) {
            WhatsAppHoliday holiday = activeHolidays.get(0);
            if (holiday.getMessageText() != null && !holiday.getMessageText().isBlank()) {
                return Optional.of(new ResolvedAutoReply(holiday.getMessageText(), holiday.getMediaData(), holiday.getMediaMimeType()));
            }
            return autoReplySettingsRepo.findByCompanyId(company.getId())
                    .filter(s -> Boolean.TRUE.equals(s.getHolidayEnabled()))
                    .map(s -> new ResolvedAutoReply(s.getHolidayMessage(), s.getHolidayMediaData(), s.getHolidayMediaMimeType()));
        }

        Optional<BranchWorkingHours> hoursOpt = workingHoursRepo.findByBranchIdAndDayOfWeek(branch.getId(), today.getDayOfWeek().getValue());
        if (hoursOpt.isEmpty()) {
            return Optional.empty(); // opt-in per branch — nothing configured
        }
        BranchWorkingHours hours = hoursOpt.get();
        var previous = workingHoursRepo.findByBranchIdAndDayOfWeek(branch.getId(), today.minusDays(1).getDayOfWeek().getValue()).orElse(null);
        boolean isWorkingNow = withinShift(hours, companyNow.toLocalTime(), false)
                || withinShift(previous, companyNow.toLocalTime(), true);

        Optional<WhatsAppAutoReplySettings> settingsOpt = autoReplySettingsRepo.findByCompanyId(company.getId());
        if (settingsOpt.isEmpty()) return Optional.empty();
        WhatsAppAutoReplySettings settings = settingsOpt.get();

        if (isWorkingNow) {
            if (!Boolean.TRUE.equals(settings.getWorkingHoursEnabled())) return Optional.empty();
            return Optional.of(new ResolvedAutoReply(settings.getWorkingHoursMessage(), settings.getWorkingHoursMediaData(), settings.getWorkingHoursMediaMimeType()));
        } else {
            if (!Boolean.TRUE.equals(settings.getNonWorkingHoursEnabled())) return Optional.empty();
            return Optional.of(new ResolvedAutoReply(settings.getNonWorkingHoursMessage(), settings.getNonWorkingHoursMediaData(), settings.getNonWorkingHoursMediaMimeType()));
        }
    }

    /**
     * Whether the branch is open right now: closed on an active holiday,
     * otherwise per its working hours (overnight shifts included). A branch
     * with no working hours configured for today has no restriction, so it
     * counts as open.
     */
    @Transactional(readOnly = true)
    public boolean isWithinWorkingHours(Branch branch) {
        LocalDateTime companyNow = companyClock.now(branch.getCompany());
        LocalDate today = companyNow.toLocalDate();
        if (!holidayRepo.findActiveForBranch(branch.getId(), today).isEmpty()) return false;
        Optional<BranchWorkingHours> hours = workingHoursRepo.findByBranchIdAndDayOfWeek(branch.getId(), today.getDayOfWeek().getValue());
        if (hours.isEmpty()) return true;
        var previous = workingHoursRepo.findByBranchIdAndDayOfWeek(branch.getId(), today.minusDays(1).getDayOfWeek().getValue()).orElse(null);
        return withinShift(hours.get(), companyNow.toLocalTime(), false) || withinShift(previous, companyNow.toLocalTime(), true);
    }

    /**
     * Module 14 P1 (v3.1): {@code lastAutoReplyAt} is stored in server wall-clock time like every other
     * column; "already auto-replied today" means the same company-local day. A value written before this
     * change was company-local: for a non-Dubai company it can at most skip one auto-reply on the deploy day.
     */
    public boolean repliedToday(Company company, LocalDateTime lastAutoReplyAtServer) {
        return lastAutoReplyAtServer != null
                && companyClock.fromServer(company, lastAutoReplyAtServer).toLocalDate().isEqual(companyClock.today(company));
    }

    /** The value to store in {@code lastAutoReplyAt} for a reply sent now (server wall-clock). */
    public LocalDateTime autoReplyStampNow() {
        return companyClock.serverNow();
    }

    /**
     * True current moment in the company's own timezone. Delegates to {@link CompanyClock#now}.
     * @deprecated call {@code CompanyClock.now(company)} directly; the remaining caller
     * (AiUsageGuardService) moves in module 18.
     */
    @Deprecated
    public LocalDateTime nowInCompanyZone(Company company) {
        return companyClock.now(company);
    }

    static void validateSchedule(WorkingHoursUpsertRequest req) {
        if (req.getBranchIds() == null || req.getBranchIds().isEmpty() || req.getBranchIds().size() > 100
                || req.getDays() == null || req.getDays().size() != 7) {
            throw new IllegalArgumentException("Choose 1 to 100 branches and provide all seven days.");
        }
        var days = new java.util.HashSet<Integer>();
        for (var day : req.getDays()) {
            if (day == null || day.getDayOfWeek() == null || day.getDayOfWeek() < 1 || day.getDayOfWeek() > 7
                    || !days.add(day.getDayOfWeek()) || day.getIsOpen() == null) {
                throw new IllegalArgumentException("Each weekday must appear exactly once with an open/closed setting.");
            }
            if (Boolean.TRUE.equals(day.getIsOpen()) && (day.getOpenTime() == null || day.getCloseTime() == null
                    || day.getOpenTime().equals(day.getCloseTime()))) {
                throw new IllegalArgumentException("Open days require distinct opening and closing times; an earlier closing time means overnight.");
            }
        }
    }

    static boolean withinShift(BranchWorkingHours hours, java.time.LocalTime time, boolean previousDay) {
        if (hours == null || !Boolean.TRUE.equals(hours.getIsOpen()) || hours.getOpenTime() == null || hours.getCloseTime() == null) return false;
        boolean overnight = hours.getCloseTime().isBefore(hours.getOpenTime());
        if (previousDay) return overnight && time.isBefore(hours.getCloseTime());
        return !time.isBefore(hours.getOpenTime()) && (overnight || time.isBefore(hours.getCloseTime()));
    }

    // ---------- Mapping ----------

    private WorkingHoursDayDto toDayDto(BranchWorkingHours h) {
        return WorkingHoursDayDto.builder()
                .dayOfWeek(h.getDayOfWeek())
                .isOpen(h.getIsOpen())
                .openTime(h.getOpenTime())
                .closeTime(h.getCloseTime())
                .build();
    }

    private WhatsAppHolidayDto toHolidayDto(WhatsAppHoliday h) {
        return WhatsAppHolidayDto.builder()
                .id(h.getId())
                .name(h.getName())
                .startDate(h.getStartDate())
                .endDate(h.getEndDate())
                .messageText(h.getMessageText())
                .hasMedia(h.getMediaData() != null && !h.getMediaData().isBlank())
                .mediaMimeType(h.getMediaMimeType())
                .branchIds(h.getBranches().stream().map(Branch::getId).collect(Collectors.toList()))
                .build();
    }

    private WhatsAppAutoReplySettingsDto toSettingsDto(WhatsAppAutoReplySettings s) {
        return WhatsAppAutoReplySettingsDto.builder()
                .workingHoursEnabled(s.getWorkingHoursEnabled())
                .workingHoursMessage(s.getWorkingHoursMessage())
                .workingHoursHasMedia(s.getWorkingHoursMediaData() != null && !s.getWorkingHoursMediaData().isBlank())
                .workingHoursMediaMimeType(s.getWorkingHoursMediaMimeType())
                .nonWorkingHoursEnabled(s.getNonWorkingHoursEnabled())
                .nonWorkingHoursMessage(s.getNonWorkingHoursMessage())
                .nonWorkingHoursHasMedia(s.getNonWorkingHoursMediaData() != null && !s.getNonWorkingHoursMediaData().isBlank())
                .nonWorkingHoursMediaMimeType(s.getNonWorkingHoursMediaMimeType())
                .holidayEnabled(s.getHolidayEnabled())
                .holidayMessage(s.getHolidayMessage())
                .holidayHasMedia(s.getHolidayMediaData() != null && !s.getHolidayMediaData().isBlank())
                .holidayMediaMimeType(s.getHolidayMediaMimeType())
                .build();
    }
}
