package com.leads.service;

import com.leads.dto.customer.*;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.CustomerStatus;
import com.leads.model.enums.StageType;
import com.leads.repository.*;
import com.leads.util.PhoneNumberUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {
    private final TenantAccess access;
    private final CompanyLeadSettingsRepository settingsRepo;

    /** No activity (visit/new lead) for this many days after a conversion flips a customer from CONVERTED to LOST. */
    private static final long LOST_AFTER_DAYS = 360;

    private final CustomerRepository customerRepo;
    private final CustomerChangeLogRepository changeLogRepo;
    private final LeadRepository leadRepo;
    private final AuthService authService;

    /**
     * Called from LeadService.createLead — resolves the existing customer by
     * (companyId, last-9-digits of phone) or creates a new one. Dedup is
     * deliberately company-wide, not branch-scoped: see the multi-branch
     * design in the implementation plan. Must run before the new Lead itself
     * is persisted, since it reads "the customer's most recent lead" to
     * detect (and log) a cross-branch visit.
     */
    @Transactional
    public Customer resolveOrCreateForLead(Company company, Branch branch, String name, String phone,
                                            String whatsappNumber, String email, String customerCompanyName,
                                            LeadSource source, User actingUser) {
        String normalized = PhoneNumberUtil.canonical(phone);

        return customerRepo.findByCompanyIdAndMobileNormalized(company.getId(), normalized)
                .or(() -> customerRepo.findExactLegacy(company.getId(), normalized).map(c -> { c.setMobileNormalized(normalized); return customerRepo.save(c); }))
                .map(existing -> linkReturningCustomer(existing, branch, name, whatsappNumber, email, customerCompanyName, actingUser))
                .orElseGet(() -> createNewCustomerFromLead(company, branch, name, phone, normalized, whatsappNumber, email, customerCompanyName, source, actingUser));
    }

    private Customer linkReturningCustomer(Customer customer, Branch branch, String name, String whatsappNumber,
                                            String email, String customerCompanyName, User actingUser) {
        List<Lead> mostRecent = leadRepo.findTop1ByCustomerIdOrderByCreatedAtDesc(customer.getId());
        Branch previousBranch = mostRecent.isEmpty() ? null : mostRecent.get(0).getBranch();

        // Backfill blanks only — never clobber a value someone has since edited on the customer record.
        if (isBlank(customer.getWhatsappNumber()) && !isBlank(whatsappNumber)) customer.setWhatsappNumber(whatsappNumber);
        if (isBlank(customer.getEmail()) && !isBlank(email)) customer.setEmail(email);
        if (isBlank(customer.getCustomerCompanyName()) && !isBlank(customerCompanyName)) customer.setCustomerCompanyName(customerCompanyName);

        customer.setTotalLeadsCount(orZero(customer.getTotalLeadsCount()) + 1);
        customer.setLastLeadAt(LocalDateTime.now());
        recomputeStatus(customer);
        Customer saved = customerRepo.save(customer);

        if (previousBranch != null && !previousBranch.getId().equals(branch.getId())) {
            logChange(saved, ActivityType.LEAD_LINKED, "New Lead at Another Branch",
                    "New lead created at " + branch.getName() + " — previously engaged at " + previousBranch.getName() + ".", actingUser);
        } else {
            logChange(saved, ActivityType.LEAD_LINKED, "New Lead Created",
                    "A new lead was created for this existing customer at " + branch.getName() + ".", actingUser);
        }

        return saved;
    }

    private Customer createNewCustomerFromLead(Company company, Branch branch, String name, String phone,
                                                String normalized, String whatsappNumber, String email,
                                                String customerCompanyName, LeadSource source, User actingUser) {
        LocalDateTime now = LocalDateTime.now();
        Customer customer = Customer.builder()
                .organization(company.getOrganization())
                .company(company)
                .homeBranch(branch)
                .initialSource(source)
                .name(name)
                .mobileNumber(phone)
                .mobileNormalized(normalized)
                .whatsappNumber(whatsappNumber)
                .email(email)
                .customerCompanyName(customerCompanyName)
                .status(CustomerStatus.NEW)
                .totalLeadsCount(1)
                .convertedLeadsCount(0)
                .firstLeadAt(now)
                .lastLeadAt(now)
                .build();
        Customer saved = customerRepo.save(customer);
        logChange(saved, ActivityType.CREATED, "Customer Created",
                "First lead created at " + branch.getName() + " — new customer record opened.", actingUser);
        return saved;
    }

    @Transactional
    public void markConverted(Customer customer) {
        if (customer == null) return;
        customer.setConvertedLeadsCount(orZero(customer.getConvertedLeadsCount()) + 1);
        customer.setLastConvertedAt(LocalDateTime.now());
        recomputeStatus(customer);
        Customer saved = customerRepo.save(customer);
        logChange(saved, ActivityType.CONVERTED, "Converted", "A lead for this customer was converted.", null);
    }

    /** Full recompute (may issue one query for the EXISTING/NON_CONVERTED split) — used on every write. */
    private void recomputeStatus(Customer customer) {
        customer.setStatus(computeStatus(customer, true));
    }

    /**
     * Cheap, in-memory-only status resolution for reads — no extra query.
     * The only rule that decays purely with time (CONVERTED -> LOST) is
     * derivable from fields already loaded on the entity, so list/detail
     * views can self-heal a stale "CONVERTED" display without touching the
     * database. The other transitions only ever change on a write, which
     * already goes through {@link #recomputeStatus}, so the persisted value
     * for those is never stale.
     */
    private CustomerStatus computeStatus(Customer customer, boolean allowQuery) {
        int converted = orZero(customer.getConvertedLeadsCount());
        if (converted > 0) {
            long daysSince = customer.getLastLeadAt() != null
                    ? ChronoUnit.DAYS.between(customer.getLastLeadAt(), LocalDateTime.now())
                    : Long.MAX_VALUE;
            return daysSince >= lostAfterDays(customer.getCompany().getId()) ? CustomerStatus.LOST : CustomerStatus.CONVERTED;
        }
        int total = orZero(customer.getTotalLeadsCount());
        if (total <= 1) {
            return CustomerStatus.NEW;
        }
        if (!allowQuery) {
            return customer.getStatus() != null ? customer.getStatus() : CustomerStatus.EXISTING;
        }
        List<Lead> mostRecent = leadRepo.findTop1ByCustomerIdOrderByCreatedAtDesc(customer.getId());
        if (!mostRecent.isEmpty() && mostRecent.get(0).getStatus() != null) {
            StageType stage = mostRecent.get(0).getStatus().getStageType();
            if (stage == StageType.LOST || stage == StageType.CLOSED) {
                return CustomerStatus.NON_CONVERTED;
            }
        }
        return CustomerStatus.EXISTING;
    }

    /** Display-time status used by mapToDto/mapToSummaryDto — zero extra queries. */
    private int lostAfterDays(Long companyId) {
        return settingsRepo.findByCompanyId(companyId).map(s -> s.getCustomerLostAfterDays()!=null ? s.getCustomerLostAfterDays() : 60).orElse(60);
    }
    @Transactional
    public void refreshLifecycle(Customer customer) {
        if(customer==null)return;
        leadRepo.flush();
        customer.setTotalLeadsCount((int)leadRepo.countByCustomerId(customer.getId()));
        customer.setConvertedLeadsCount((int)leadRepo.countByCustomerIdAndIsConvertedTrue(customer.getId()));
        recomputeStatus(customer);
        customerRepo.save(customer);
    }

    private CustomerStatus displayStatus(Customer customer) {
        return computeStatus(customer, false);
    }

    @Transactional
    public CustomerDto updateCustomer(Long id, CustomerUpdateRequest req, User actingUser) {
        access.record(com.leads.model.entity.Customer.class, id);
        Customer customer = customerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));

        String newNormalized = PhoneNumberUtil.canonical(req.getMobileNumber());
        if (!newNormalized.equals(customer.getMobileNormalized())) {
            customerRepo.findByCompanyIdAndMobileNormalized(customer.getCompany().getId(), newNormalized).ifPresent(other -> {
                if (!other.getId().equals(customer.getId())) {
                    throw new IllegalStateException("Another customer already uses this mobile number: " + other.getName());
                }
            });
        }

        List<String> changes = new ArrayList<>();
        diffField(changes, "Name", customer.getName(), req.getName());
        diffField(changes, "Mobile", customer.getMobileNumber(), req.getMobileNumber());
        diffField(changes, "WhatsApp", customer.getWhatsappNumber(), req.getWhatsappNumber());
        diffField(changes, "Email", customer.getEmail(), req.getEmail());
        diffField(changes, "Company", customer.getCustomerCompanyName(), req.getCustomerCompanyName());
        diffField(changes, "City", customer.getCity(), req.getCity());
        diffField(changes, "Notes", customer.getNotes(), req.getNotes());

        customer.setName(req.getName());
        customer.setMobileNumber(req.getMobileNumber());
        customer.setMobileNormalized(newNormalized);
        customer.setWhatsappNumber(req.getWhatsappNumber());
        customer.setEmail(req.getEmail());
        customer.setCustomerCompanyName(req.getCustomerCompanyName());
        customer.setCity(req.getCity());
        customer.setNotes(req.getNotes());

        Customer saved = customerRepo.save(customer);
        if (!changes.isEmpty()) {
            logChange(saved, ActivityType.FIELD_UPDATE, "Customer Updated", String.join("; ", changes), actingUser);
        }
        return mapToDto(saved, false);
    }

    @Transactional(readOnly = true)
    public CustomerDto getCustomer(Long id) {
        access.record(com.leads.model.entity.Customer.class, id);
        Customer customer = customerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        return mapToDto(customer, true);
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> listCustomers(Long companyId, Long branchId, String name, String mobile,
                                            CustomerStatus status, java.time.LocalDate fromDate, java.time.LocalDate toDate,
                                            Boolean blocked, Pageable pageable) {
        access.scope(companyId, branchId);
        com.leads.util.InputRules.dateRange(fromDate,toDate);
        final LocalDateTime lostCutoff=LocalDateTime.now().minusDays(lostAfterDays(companyId));
        pageable=com.leads.util.InputRules.page(pageable,"lastLeadAt","createdAt","name","mobileNumber","status","id");
        // A Customer record is intentionally company-wide, not branch-owned
        // (see resolveOrCreateForLead) - a repeat visitor at a different
        // branch reuses the same row rather than duplicating it. But a
        // BRANCH-level caller must still only ever see customers with at
        // least one lead at one of THEIR permitted branches, same as every
        // other branch-scoped list (Leads, Quotations, WhatsApp Inbox) -
        // this was previously unenforced, so any branch-level user could see
        // every other branch's customers just by omitting the branchId filter.
        List<Long> permittedBranches = access.branchIds();
        Specification<Customer> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (permittedBranches != null) {
                jakarta.persistence.criteria.Subquery<Long> permittedSub = query.subquery(Long.class);
                jakarta.persistence.criteria.Root<Lead> permittedLeadRoot = permittedSub.from(Lead.class);
                permittedSub.select(permittedLeadRoot.get("id"));
                permittedSub.where(cb.and(
                        cb.equal(permittedLeadRoot.get("customer"), root),
                        permittedLeadRoot.get("branch").get("id").in(permittedBranches)
                ));
                predicates.add(cb.exists(permittedSub));
            }

            if (companyId != null) {
                predicates.add(cb.equal(root.get("company").get("id"), companyId));
            }
            // Blocked customers stay out of the normal list by default - the
            // dedicated "View Blocked Customers" screen passes blocked=true
            // to see (and unblock) just them.
            predicates.add(cb.equal(root.get("isBlocked"), Boolean.TRUE.equals(blocked)));
            if (name != null && !name.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + name.trim().toLowerCase() + "%"));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toDate.plusDays(1).atStartOfDay()));
            }
            if (mobile != null && !mobile.isBlank()) {
                predicates.add(cb.like(root.get("mobileNormalized"), "%" + PhoneNumberUtil.lastSignificantDigits(mobile) + "%"));
            }
            if (status != null) {
                if(status==CustomerStatus.LOST) predicates.add(cb.and(cb.greaterThan(root.get("convertedLeadsCount"),0),cb.lessThanOrEqualTo(root.get("lastLeadAt"),lostCutoff)));
                else if(status==CustomerStatus.CONVERTED) predicates.add(cb.and(cb.greaterThan(root.get("convertedLeadsCount"),0),cb.greaterThan(root.get("lastLeadAt"),lostCutoff)));
                else predicates.add(cb.and(cb.equal(root.get("convertedLeadsCount"),0),cb.equal(root.get("status"),status)));
            }
            if (branchId != null && query != null) {
                // "Active at branch X" = has at least one lead at that branch — NOT homeBranch equality.
                // See multi-branch design: a customer is a company-wide record, not locked to one branch.
                jakarta.persistence.criteria.Subquery<Long> sub = query.subquery(Long.class);
                jakarta.persistence.criteria.Root<Lead> leadRoot = sub.from(Lead.class);
                sub.select(leadRoot.get("id"));
                sub.where(cb.and(
                        cb.equal(leadRoot.get("customer"), root),
                        cb.equal(leadRoot.get("branch").get("id"), branchId)
                ));
                predicates.add(cb.exists(sub));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return customerRepo.findAll(spec, pageable).map(c -> mapToDto(c, false));
    }

    /**
     * Blocks the customer for a phone number - creates a minimal Customer
     * shell first if none exists yet, since a raw WhatsApp chat that never
     * became a lead has no Customer row at all. Deliberately doesn't reuse
     * resolveOrCreateForLead: that method's lead-count/first-touch
     * bookkeeping and "First lead created" activity log don't apply here.
     */
    @Transactional
    public Customer blockCustomerByPhone(Company company, Branch branch, String name, String phone) {
        String normalized = PhoneNumberUtil.canonical(phone);
        String safeName = (name == null || name.isBlank()) ? phone : name;
        Customer customer = customerRepo.findByCompanyIdAndMobileNormalized(company.getId(), normalized)
                .orElseGet(() -> customerRepo.save(Customer.builder()
                        .organization(company.getOrganization())
                        .company(company)
                        .homeBranch(branch)
                        .name(safeName)
                        .mobileNumber(phone)
                        .mobileNormalized(normalized)
                        .status(CustomerStatus.NEW)
                        .totalLeadsCount(0)
                        .convertedLeadsCount(0)
                        .build()));
        setBlocked(customer, true);
        return customer;
    }

    @Transactional
    public CustomerDto unblockCustomer(Long id) {
        access.record(com.leads.model.entity.Customer.class, id);
        Customer customer = customerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        setBlocked(customer, false);
        return mapToDto(customer, false);
    }

    private void setBlocked(Customer customer, boolean blocked) {
        User actingUser = authService.getCurrentUser();
        customer.setIsBlocked(blocked);
        customer.setBlockedAt(blocked ? LocalDateTime.now() : null);
        customer.setBlockedBy(blocked ? actingUser : null);
        Customer saved = customerRepo.save(customer);
        logChange(saved, ActivityType.FIELD_UPDATE, blocked ? "Customer Blocked" : "Customer Unblocked",
                blocked ? "This customer's WhatsApp chat was hidden from the inbox." : "This customer's WhatsApp chat is visible in the inbox again.",
                actingUser);
    }

    @Transactional(readOnly = true)
    public List<CustomerSummaryDto> suggest(Long companyId, String partialPhone) {
        access.company(companyId);
        String normalized = PhoneNumberUtil.lastSignificantDigits(partialPhone);
        if (normalized.isBlank()) return List.of();
        return customerRepo.suggestByPartialMobile(companyId, normalized, PageRequest.of(0, 8, Sort.by("lastLeadAt").descending()))
                .stream()
                .map(c -> {
                    CustomerSummaryDto dto = mapToSummaryDto(c);
                    dto.setHasActiveLead(leadRepo.existsActiveLeadForCustomer(c.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CustomerChangeLogDto> getChangeLogs(Long customerId) {
        access.record(com.leads.model.entity.Customer.class, customerId);
        return changeLogRepo.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(this::mapLogToDto)
                .collect(Collectors.toList());
    }

    /** A user-authored note (optionally with a file attachment) on the customer's change log — the Customer Detail page's ClickUp-style panel. */
    @Transactional
    public CustomerChangeLogDto addNote(Long customerId, String note, String attachmentName, String attachmentData) {
        access.record(com.leads.model.entity.Customer.class, customerId);
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
        User currentUser = authService.getCurrentUser();

        com.leads.util.InputRules.attachment(attachmentName,attachmentData);
        String title = attachmentName != null && !attachmentName.isBlank() ? "Note with Attachment" : "Note Added";
        CustomerChangeLog log = CustomerChangeLog.builder()
                .customer(customer)
                .activityType(ActivityType.NOTE)
                .title(title)
                .description(note)
                .performedBy(currentUser)
                .attachmentName(attachmentName)
                .attachmentData(attachmentData)
                .build();
        return mapLogToDto(changeLogRepo.save(log));
    }

    private void logChange(Customer customer, ActivityType type, String title, String description, User performedBy) {
        changeLogRepo.save(CustomerChangeLog.builder()
                .customer(customer)
                .activityType(type)
                .title(title)
                .description(description)
                .performedBy(performedBy)
                .build());
    }

    private void diffField(List<String> changes, String label, String oldVal, String newVal) {
        String o = oldVal == null ? "" : oldVal;
        String n = newVal == null ? "" : newVal;
        if (!o.equals(n)) {
            changes.add(label + ": \"" + o + "\" -> \"" + n + "\"");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    public CustomerDto mapToDto(Customer c, boolean includeActiveBranches) {
        List<CustomerBranchDto> activeBranches = null;
        String initialSourceName = null;
        java.math.BigDecimal totalRevenue = null;
        Integer lostLeadsCount = null;
        if (includeActiveBranches) {
            activeBranches = leadRepo.findDistinctBranchesByCustomerId(c.getId()).stream()
                    .map(b -> CustomerBranchDto.builder().id(b.getId()).name(b.getName()).code(b.getCode()).build())
                    .collect(Collectors.toList());
            initialSourceName = c.getInitialSource() != null ? c.getInitialSource().getSourceName() : null;
            totalRevenue = leadRepo.sumConvertedDealValueByCustomerId(c.getId());
            lostLeadsCount = (int) leadRepo.countLostByCustomerId(c.getId());
        }

        return CustomerDto.builder()
                .id(c.getId())
                .organizationId(c.getOrganization().getId())
                .companyId(c.getCompany().getId())
                .companyName(c.getCompany().getName())
                .homeBranchId(c.getHomeBranch() != null ? c.getHomeBranch().getId() : null)
                .homeBranchName(c.getHomeBranch() != null ? c.getHomeBranch().getName() : null)
                .activeBranches(activeBranches)
                .initialSourceName(initialSourceName)
                .totalRevenue(totalRevenue)
                .lostLeadsCount(lostLeadsCount)
                .name(c.getName())
                .mobileNumber(c.getMobileNumber())
                .whatsappNumber(c.getWhatsappNumber())
                .email(c.getEmail())
                .customerCompanyName(c.getCustomerCompanyName())
                .city(c.getCity())
                .status(displayStatus(c))
                .totalLeadsCount(c.getTotalLeadsCount())
                .convertedLeadsCount(c.getConvertedLeadsCount())
                .firstLeadAt(c.getFirstLeadAt())
                .lastLeadAt(c.getLastLeadAt())
                .lastConvertedAt(c.getLastConvertedAt())
                .notes(c.getNotes())
                .isBlocked(c.getIsBlocked())
                .blockedAt(c.getBlockedAt())
                .blockedByName(c.getBlockedBy() != null ? c.getBlockedBy().getFullName() : null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    public CustomerSummaryDto mapToSummaryDto(Customer c) {
        return CustomerSummaryDto.builder()
                .id(c.getId())
                .name(c.getName())
                .mobileNumber(c.getMobileNumber())
                .whatsappNumber(c.getWhatsappNumber())
                .status(displayStatus(c))
                .totalLeadsCount(c.getTotalLeadsCount())
                .convertedLeadsCount(c.getConvertedLeadsCount())
                .build();
    }

    private CustomerChangeLogDto mapLogToDto(CustomerChangeLog log) {
        return CustomerChangeLogDto.builder()
                .id(log.getId())
                .customerId(log.getCustomer().getId())
                .activityType(log.getActivityType())
                .title(log.getTitle())
                .description(log.getDescription())
                .attachmentName(log.getAttachmentName())
                .attachmentData(log.getAttachmentData())
                .performedByName(log.getPerformedBy() != null ? log.getPerformedBy().getFullName() : "System")
                .createdAt(log.getCreatedAt())
                .build();
    }
}
