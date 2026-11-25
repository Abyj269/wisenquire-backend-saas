package com.leads.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.lead.*;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.PredefinedLeadField;
import com.leads.model.enums.StageType;
import com.leads.repository.*;
import com.leads.util.AdAttributionResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {
    private final TenantAccess access;
    private final DocumentNumberService numbers;
    private final CompanyClock companyClock;

    private final LeadRepository leadRepo;
    private final LeadProductRepository leadProductRepo;
    private final LeadTimelineActivityRepository activityRepo;
    private final CompanyRepository companyRepo;
    private final BranchRepository branchRepo;
    private final LeadStatusRepository statusRepo;
    private final LeadPurposeRepository purposeRepo;
    private final LeadSourceRepository sourceRepo;
    private final LeadLostReasonRepository lostReasonRepo;
    private final UserRepository userRepo;
    private final ProductMasterRepository productRepo;
    private final CompanyLeadSettingsRepository settingsRepo;
    private final DynamicFieldService dynamicFieldService;
    private final PredefinedFieldService predefinedFieldService;
    private final LeadSettingsService leadSettingsService;
    private final AuthService authService;
    private final CustomerService customerService;
    private final ObjectMapper objectMapper;
    private final AdRepository adRepo;

    @Transactional
    public LeadDto createLead(LeadCreateRequest req) {
        return createLeadInternal(req, false);
    }

    /** Internal webhook entry point: an inbound message cannot establish customer intent. */
    @Transactional
    public LeadDto createLeadFromInboundChat(LeadCreateRequest req) {
        req.setPurposeId(null);
        return createLeadInternal(req, true);
    }

    LeadDto createLeadInternal(LeadCreateRequest req, boolean purposePending) {
        access.scope(req.getCompanyId(), req.getBranchId());
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + req.getCompanyId()));

        Branch branch = branchRepo.findById(req.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));

        access.sameCompany(company.getId(), branch.getCompany());
        // D14 (module 06 P1): an OWN agent's new lead is theirs. No assignee = assigned to them;
        // another assignee is refused (OWN agents never assign others).
        Long ownMe = access.ownerFilter();
        if (ownMe != null) {
            if (req.getAssignedToUserId() == null) req.setAssignedToUserId(ownMe);
            else if (!ownMe.equals(req.getAssignedToUserId())) throw new org.springframework.security.access.AccessDeniedException("You can only create leads assigned to yourself.");
        }
        validateReferences(company.getId(), branch.getId(), req.getStatusId(), req.getSourceId(), req.getPurposeId(), req.getAssignedToUserId());
        com.leads.util.InputRules.amount(req.getEstimatedDealValue(),"Deal value");
        req.setPhone(com.leads.util.PhoneNumberUtil.canonical(req.getPhone()));
        // Validate dynamic values against defined fields
        dynamicFieldService.validateDynamicValues(company.getId(), req.getDynamicValues());

        // Enforce this company's predefined-field config (hide/mandatory) —
        // Lead Purpose is locked (always mandatory, not config-driven) but
        // gated by a safety net so a company with zero active purposes is
        // never blocked from creating leads at all.
        predefinedFieldService.validateNativeFieldValues(req.getNationality(), req.getPreferredLanguage());
        predefinedFieldService.enforceMandatory(company.getId(), buildNativeFieldValues(req));
        if (!purposePending) predefinedFieldService.enforcePurposeMandatory(req.getPurposeId(),
                !purposeRepo.findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(company.getId()).isEmpty());

        // Resolve Assigned User
        User assignedTo = null;
        if (req.getAssignedToUserId() != null) {
            if (ownMe == null) access.permission("LEADS:ASSIGN"); // OWN: assigned to self above, no ASSIGN right needed
            assignedTo = userRepo.findById(req.getAssignedToUserId()).orElse(null);
        }

        // Resolve Status: an explicit choice always wins. Otherwise every new
        // lead opens on the pipeline's OPEN stage — unless it's being handed
        // to someone right at creation, in which case it starts on the
        // ASSIGNED stage instead of silently sitting as "unassigned but open".
        LeadStatus status = null;
        if (req.getStatusId() != null) {
            status = statusRepo.findById(req.getStatusId()).orElse(null);
        }
        if (status == null && assignedTo != null) {
            status = findStageStatus(company.getId(), StageType.ASSIGNED);
        }
        if (status == null) {
            status = findStageStatus(company.getId(), StageType.OPEN);
        }
        if (status == null) {
            status = statusRepo.findByCompanyIdAndIsDefaultTrue(company.getId())
                    .orElseGet(() -> statusRepo.findByCompanyIdOrderBySortOrderAsc(company.getId()).stream().findFirst().orElse(null));
        }

        // Resolve Purpose
        LeadPurpose purpose = null;
        if (req.getPurposeId() != null) {
            purpose = purposeRepo.findById(req.getPurposeId()).orElse(null);
        }

        // Resolve Source: an explicit choice wins; otherwise every lead
        // created without one is tagged with the company's built-in "Direct
        // Lead" source.
        LeadSource source = null;
        if (req.getSourceId() != null) {
            source = sourceRepo.findById(req.getSourceId()).orElse(null);
        }
        if (source == null) {
            source = leadSettingsService.getSystemSource(company.getId(), "DIRECT");
        }

        User currentUser = authService.getCurrentUser();

        // Resolve or create the Customer this lead belongs to, keyed on the
        // last 9 digits of the phone number (company-wide, not branch-scoped
        // — a repeat visitor at a different branch links to the same record).
        String contactName = (req.getFirstName() + (req.getLastName() != null ? " " + req.getLastName() : "")).trim();
        Customer customer = customerService.resolveOrCreateForLead(
                company, branch, contactName, req.getPhone(),
                req.getWhatsappNumber() != null ? req.getWhatsappNumber() : req.getPhone(),
                req.getEmail(), req.getCustomerCompanyName(), source, currentUser);

        // Generate Lead Number
        String leadNumber = generateLeadNumber(company.getId());

        // Serialize dynamic values to JSON
        String dynamicJson = null;
        if (req.getDynamicValues() != null && !req.getDynamicValues().isEmpty()) {
            try {
                dynamicJson = objectMapper.writeValueAsString(req.getDynamicValues());
            } catch (Exception e) {
                log.error("Failed to serialize dynamic values: {}", e.getMessage());
            }
        }

        Lead lead = Lead.builder()
                .organization(company.getOrganization())
                .company(company)
                .branch(branch)
                .customer(customer)
                .leadNumber(leadNumber)
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .whatsappNumber(req.getWhatsappNumber() != null ? req.getWhatsappNumber() : req.getPhone())
                .customerCompanyName(req.getCustomerCompanyName())
                .jobTitle(req.getJobTitle())
                .nationality(req.getNationality())
                .preferredLanguage(req.getPreferredLanguage())
                .source(source)
                .purpose(purpose)
                .status(status)
                .assignedTo(assignedTo)
                .assignedBy(currentUser)
                .estimatedDealValue(req.getEstimatedDealValue() != null ? req.getEstimatedDealValue() : BigDecimal.ZERO)
                .closingDate(req.getClosingDate())
                .notes(req.getNotes())
                .dynamicValues(dynamicJson)
                .isConverted(false)
                .build();

        Lead savedLead = leadRepo.save(lead);
        customerService.refreshLifecycle(customer);

        // Handle attached products (if link product enabled)
        CompanyLeadSettings settings = settingsRepo.findByCompanyId(company.getId()).orElse(null);
        boolean allowProductLinking = settings == null || Boolean.TRUE.equals(settings.getLinkProductEnabled());

        if (allowProductLinking && req.getProducts() != null && !req.getProducts().isEmpty()) {
            saveLeadProducts(savedLead, req.getProducts());
        }

        // Record Activity
        recordActivity(savedLead, ActivityType.CREATED, "Lead Created",
                "Lead #" + leadNumber + " created with source " + savedLead.getSource().getSourceName(), currentUser);

        return mapToDto(savedLead);
    }

    @Transactional
    public LeadDto updateLead(Long id, LeadUpdateRequest req) {
        access.record(com.leads.model.entity.Lead.class, id);
        Lead lead = leadRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));

        User currentUser = authService.getCurrentUser();

        Long targetBranch=req.getBranchId()!=null ? req.getBranchId() : lead.getBranch().getId();
        access.scope(lead.getCompany().getId(), targetBranch);
        validateReferences(lead.getCompany().getId(),targetBranch,java.util.Objects.equals(req.getStatusId(), lead.getStatus().getId()) ? null : req.getStatusId(),req.getSourceId(),req.getPurposeId(),req.getAssignedToUserId());
        com.leads.util.InputRules.amount(req.getEstimatedDealValue(),"Deal value");
        req.setPhone(com.leads.util.PhoneNumberUtil.canonical(req.getPhone()));
        if(lead.getCustomer()!=null && !com.leads.util.PhoneNumberUtil.canonical(lead.getCustomer().getMobileNumber()).equals(req.getPhone()))
            throw new IllegalArgumentException("Change the customer master phone first; a lead cannot silently change customer identity.");
        if(Boolean.TRUE.equals(lead.getIsConverted()) && !java.util.Objects.equals(req.getEstimatedDealValue(),lead.getEstimatedDealValue())) throw new IllegalArgumentException("Won deal values are immutable. Create a new opportunity for additional business.");
        // Snapshot editable fields so a single diff-based activity can be
        // logged after the fact for the Lead Detail page's audit timeline —
        // status/assignment changes already get their own dedicated entries
        // below, this covers everything else (name, contact info, notes...).
        List<String> fieldChanges = new ArrayList<>();
        diffField(fieldChanges, "First Name", lead.getFirstName(), req.getFirstName());
        diffField(fieldChanges, "Last Name", lead.getLastName(), req.getLastName());
        diffField(fieldChanges, "Email", lead.getEmail(), req.getEmail());
        diffField(fieldChanges, "Phone", lead.getPhone(), req.getPhone());
        diffField(fieldChanges, "WhatsApp", lead.getWhatsappNumber(), req.getWhatsappNumber());
        diffField(fieldChanges, "Company", lead.getCustomerCompanyName(), req.getCustomerCompanyName());
        diffField(fieldChanges, "Job Title", lead.getJobTitle(), req.getJobTitle());
        diffField(fieldChanges, "Notes", lead.getNotes(), req.getNotes());
        diffField(fieldChanges, "Deal Value",
                lead.getEstimatedDealValue() != null ? lead.getEstimatedDealValue().toString() : null,
                req.getEstimatedDealValue() != null ? req.getEstimatedDealValue().toString() : null);
        diffField(fieldChanges, "Closing Date",
                lead.getClosingDate() != null ? lead.getClosingDate().toString() : null,
                req.getClosingDate() != null ? req.getClosingDate().toString() : null);

        // Validate dynamic values
        dynamicFieldService.validateDynamicValues(lead.getCompany().getId(), req.getDynamicValues());

        // Enforce this company's predefined-field config — see createLead for details.
        predefinedFieldService.validateNativeFieldValues(req.getNationality(), req.getPreferredLanguage());
        predefinedFieldService.enforceMandatory(lead.getCompany().getId(), buildNativeFieldValues(req));
        predefinedFieldService.enforcePurposeMandatory(req.getPurposeId(),
                !purposeRepo.findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(lead.getCompany().getId()).isEmpty());

        if (req.getBranchId() != null && !req.getBranchId().equals(lead.getBranch().getId())) {
            Branch branch = branchRepo.findById(req.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));
            lead.setBranch(branch);
        }

        lead.setFirstName(req.getFirstName());
        lead.setLastName(req.getLastName());
        lead.setEmail(req.getEmail());
        lead.setPhone(req.getPhone());
        lead.setWhatsappNumber(req.getWhatsappNumber());
        lead.setCustomerCompanyName(req.getCustomerCompanyName());
        lead.setJobTitle(req.getJobTitle());
        lead.setNationality(req.getNationality());
        lead.setPreferredLanguage(req.getPreferredLanguage());
        if (req.getSourceId() != null) {
            sourceRepo.findById(req.getSourceId()).ifPresent(lead::setSource);
        }

        if (req.getPurposeId() != null) {
            LeadPurpose purpose = purposeRepo.findById(req.getPurposeId()).orElse(null);
            lead.setPurpose(purpose);
        }

        if (req.getPurposeId() == null) lead.setPurpose(null);
        // Handle Status Transition
        if (req.getStatusId() != null && !req.getStatusId().equals(lead.getStatus().getId())) {
            // Converted/Lost are terminal — reaching them goes through their own
            // dedicated flows (convertLead/markLeadLost), never this generic
            // status PATCH, and once there a lead can't be moved to yet another
            // status through this path either. Enforced here (not just hidden in
            // the UI) so it can't be bypassed by any other caller of this endpoint.
            if (Boolean.TRUE.equals(lead.getIsConverted()) || lead.getStatus().getStageType() == StageType.LOST) {
                throw new IllegalArgumentException(
                        "This lead is already " + (Boolean.TRUE.equals(lead.getIsConverted()) ? "Converted" : "marked Lost") + " — its status can't be changed further.");
            }

            LeadStatus oldStatus = lead.getStatus();
            LeadStatus newStatus = statusRepo.findById(req.getStatusId())
                    .orElseThrow(() -> new IllegalArgumentException("Status not found: " + req.getStatusId()));
            lead.setStatus(newStatus);

            recordActivity(lead, ActivityType.STATUS_CHANGE, "Status Changed",
                    "Status updated from " + oldStatus.getStatusName() + " to " + newStatus.getStatusName(), currentUser);
        }

        if (req.getAssignedToUserId() == null && lead.getAssignedTo() != null) {
            if (!ownAssigneeChange(lead, null)) access.permission("LEADS:ASSIGN");
            lead.setAssignedTo(null); lead.setAssignedBy(currentUser);
            recordActivity(lead, ActivityType.REASSIGNMENT, "Lead Unassigned", "Assignment cleared", currentUser);
        }
        // Handle Assignment
        if (req.getAssignedToUserId() != null) {
            if (lead.getAssignedTo() == null || !lead.getAssignedTo().getId().equals(req.getAssignedToUserId())) {
                if (!ownAssigneeChange(lead, req.getAssignedToUserId())) access.permission("LEADS:ASSIGN");
                User oldUser = lead.getAssignedTo();
                User newUser = userRepo.findById(req.getAssignedToUserId())
                        .orElseThrow(() -> new IllegalArgumentException("User not found: " + req.getAssignedToUserId()));
                lead.setAssignedTo(newUser);
                lead.setAssignedBy(currentUser);

                recordActivity(lead, ActivityType.REASSIGNMENT, "Lead Reassigned",
                        "Reassigned from " + (oldUser != null ? oldUser.getFullName() : "Unassigned") + " to " + newUser.getFullName(), currentUser);

                // First-ever assignment auto-advances a still-Open lead to the
                // Assigned stage — but never overrides a status the caller
                // explicitly chose in this same request, and never regresses a
                // lead that has already moved further down the pipeline.
                if (oldUser == null && req.getStatusId() == null && lead.getStatus().getStageType() == StageType.OPEN) {
                    LeadStatus assignedStage = findStageStatus(lead.getCompany().getId(), StageType.ASSIGNED);
                    if (assignedStage != null) {
                        LeadStatus previousStatus = lead.getStatus();
                        lead.setStatus(assignedStage);
                        recordActivity(lead, ActivityType.STATUS_CHANGE, "Status Changed",
                                "Status automatically updated from " + previousStatus.getStatusName() + " to " + assignedStage.getStatusName() + " on assignment", currentUser);
                    }
                }
            }
        }

        lead.setEstimatedDealValue(req.getEstimatedDealValue());
        lead.setClosingDate(req.getClosingDate());
        lead.setNotes(req.getNotes());

        if (req.getDynamicValues() != null) {
            try {
                lead.setDynamicValues(objectMapper.writeValueAsString(req.getDynamicValues()));
            } catch (Exception e) {
                log.error("Failed to serialize dynamic values: {}", e.getMessage());
            }
        }

        if (req.getProducts() != null) {
            if(!settingsRepo.findByCompanyId(lead.getCompany().getId()).map(v -> Boolean.TRUE.equals(v.getLinkProductEnabled())).orElse(true) && !req.getProducts().isEmpty()) throw new IllegalArgumentException("Product linking is disabled.");
            leadProductRepo.deleteByLeadId(lead.getId());
            saveLeadProducts(lead, req.getProducts());
        }

        Lead updated = leadRepo.save(lead);
        customerService.refreshLifecycle(updated.getCustomer());

        if (!fieldChanges.isEmpty()) {
            recordActivity(updated, ActivityType.FIELD_UPDATE, "Lead Details Updated",
                    String.join("; ", fieldChanges), currentUser);
        }

        return mapToDto(updated);
    }

    private Map<PredefinedLeadField, Object> buildNativeFieldValues(LeadCreateRequest req) {
        Map<PredefinedLeadField, Object> values = new EnumMap<>(PredefinedLeadField.class);
        values.put(PredefinedLeadField.LAST_NAME, req.getLastName());
        values.put(PredefinedLeadField.EMAIL, req.getEmail());
        values.put(PredefinedLeadField.COMPANY_NAME, req.getCustomerCompanyName());
        values.put(PredefinedLeadField.ASSIGNED_TO, req.getAssignedToUserId());
        values.put(PredefinedLeadField.CLOSING_DATE, req.getClosingDate());
        values.put(PredefinedLeadField.NOTES, req.getNotes());
        values.put(PredefinedLeadField.NATIONALITY, req.getNationality());
        values.put(PredefinedLeadField.PREFERRED_LANGUAGE, req.getPreferredLanguage());
        return values;
    }

    private Map<PredefinedLeadField, Object> buildNativeFieldValues(LeadUpdateRequest req) {
        Map<PredefinedLeadField, Object> values = new EnumMap<>(PredefinedLeadField.class);
        values.put(PredefinedLeadField.LAST_NAME, req.getLastName());
        values.put(PredefinedLeadField.EMAIL, req.getEmail());
        values.put(PredefinedLeadField.COMPANY_NAME, req.getCustomerCompanyName());
        values.put(PredefinedLeadField.ASSIGNED_TO, req.getAssignedToUserId());
        values.put(PredefinedLeadField.CLOSING_DATE, req.getClosingDate());
        values.put(PredefinedLeadField.NOTES, req.getNotes());
        values.put(PredefinedLeadField.NATIONALITY, req.getNationality());
        values.put(PredefinedLeadField.PREFERRED_LANGUAGE, req.getPreferredLanguage());
        return values;
    }

    private void diffField(List<String> changes, String label, String oldVal, String newVal) {
        String o = oldVal == null ? "" : oldVal;
        String n = newVal == null ? "" : newVal;
        if (!o.equals(n)) {
            changes.add(label + ": \"" + o + "\" -> \"" + n + "\"");
        }
    }

    @Transactional
    public void assignLeads(LeadAssignRequest req) {
        Long ownMe = access.ownerFilter();
        if (ownMe != null) {
            // D14 (module 06 P1): an OWN agent may only claim unassigned leads for themself, and only
            // when own_scope.include_unassigned is on. No LEADS:ASSIGN right is needed for that.
            if (!ownMe.equals(req.getAssignToUserId()) || !access.ownIncludesUnassigned()) throw new org.springframework.security.access.AccessDeniedException("You can only claim unassigned leads for yourself.");
            req.getLeadIds().forEach(id -> {
                if (access.record(Lead.class, id).getAssignedTo() != null) throw new org.springframework.security.access.AccessDeniedException("You can only claim unassigned leads for yourself.");
            });
        } else {
            access.permission("LEADS:ASSIGN");
            req.getLeadIds().forEach(id -> access.record(Lead.class, id));
        }
        User assignToUser = userRepo.findById(req.getAssignToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Assignee not found: " + req.getAssignToUserId()));
        User currentUser = authService.getCurrentUser();

        for (Long leadId : req.getLeadIds()) {
            leadRepo.findById(leadId).ifPresent(lead -> {
                validateAssignee(lead.getCompany().getId(),lead.getBranch().getId(),assignToUser);
                boolean wasUnassigned = lead.getAssignedTo() == null;
                lead.setAssignedTo(assignToUser);
                lead.setAssignedBy(currentUser);

                if (wasUnassigned && lead.getStatus().getStageType() == StageType.OPEN) {
                    LeadStatus assignedStage = findStageStatus(lead.getCompany().getId(), StageType.ASSIGNED);
                    if (assignedStage != null) {
                        lead.setStatus(assignedStage);
                    }
                }

                leadRepo.save(lead);

                recordActivity(lead, ActivityType.ASSIGNMENT, "Lead Assigned",
                        "Assigned to " + assignToUser.getFullName() + (req.getAssignmentNotes() != null ? ". Notes: " + req.getAssignmentNotes() : ""), currentUser);
            });
        }
    }

    @Transactional
    public void assignLeadFromAutomation(Long leadId, User agent) {
        if (leadId == null || agent == null) return;
        Lead lead = leadRepo.findById(leadId).orElse(null);
        if (lead == null || Boolean.TRUE.equals(lead.getIsConverted())
                || (lead.getStatus() != null && java.util.Set.of(StageType.CONVERTED, StageType.LOST, StageType.CLOSED).contains(lead.getStatus().getStageType()))) {
            return;
        }
        User oldUser = lead.getAssignedTo();
        lead.setAssignedTo(agent);
        lead.setAssignedBy(agent);

        if (oldUser == null && lead.getStatus() != null && lead.getStatus().getStageType() == StageType.OPEN) {
            LeadStatus assignedStage = findStageStatus(lead.getCompany().getId(), StageType.ASSIGNED);
            if (assignedStage != null) {
                LeadStatus previousStatus = lead.getStatus();
                lead.setStatus(assignedStage);
                recordActivity(lead, ActivityType.STATUS_CHANGE, "Status Changed",
                        "Status automatically updated from " + previousStatus.getStatusName() + " to " + assignedStage.getStatusName() + " on assignment", agent);
            }
        }

        leadRepo.save(lead);
        recordActivity(lead, ActivityType.REASSIGNMENT, "Lead Assigned via WhatsApp Automation",
                "Assigned to " + (agent.getFullName() != null ? agent.getFullName() : agent.getFirstName())
                        + (oldUser != null ? " (previously " + (oldUser.getFullName() != null ? oldUser.getFullName() : oldUser.getFirstName()) + ")" : ""),
                agent);
    }

    private LeadStatus findStageStatus(Long companyId, StageType stageType) {
        return statusRepo.findByCompanyIdOrderBySortOrderAsc(companyId).stream()
                .filter(s -> s.getStageType() == stageType && Boolean.TRUE.equals(s.getIsActive()))
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public LeadDto convertLead(LeadConvertRequest req) {
        access.record(com.leads.model.entity.Lead.class, req.getLeadId());
        Lead lead = leadRepo.findById(req.getLeadId())
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + req.getLeadId()));

        if(Boolean.TRUE.equals(lead.getIsConverted())) return mapToDto(lead);
        if(lead.getStatus().getStageType()==StageType.LOST || lead.getStatus().getStageType()==StageType.CLOSED) throw new IllegalArgumentException("A closed lead cannot be converted.");
        com.leads.util.InputRules.amount(req.getDealValue(),"Won value");
        User currentUser = authService.getCurrentUser();

        lead.setIsConverted(true);
        lead.setConvertedAt(LocalDateTime.now());
        if (req.getDealValue() != null) lead.setEstimatedDealValue(req.getDealValue());

        // Find or map to Converted stage status
        statusRepo.findByCompanyIdOrderBySortOrderAsc(lead.getCompany().getId()).stream()
                .filter(s -> s.getStageType() == StageType.CONVERTED)
                .findFirst()
                .ifPresent(lead::setStatus);

        Lead saved = leadRepo.save(lead);
        customerService.markConverted(saved.getCustomer());

        recordActivity(saved, ActivityType.CONVERTED, "Lead Converted",
                "Lead successfully converted to Customer/Deal. " + (req.getConversionNotes() != null ? req.getConversionNotes() : ""), currentUser);

        return mapToDto(saved);
    }

    /** Mirrors convertLead's shape — a dedicated flow (not a plain status PATCH) so the reason/note are always captured whenever a lead is marked Lost, however that's triggered. */
    @Transactional
    public LeadDto markLeadLost(LeadLostRequest req) {
        access.record(com.leads.model.entity.Lead.class, req.getLeadId());
        Lead lead = leadRepo.findById(req.getLeadId())
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + req.getLeadId()));

        if(Boolean.TRUE.equals(lead.getIsConverted())) throw new IllegalArgumentException("A converted lead cannot be marked lost.");
        if(lead.getStatus().getStageType()==StageType.LOST) return mapToDto(lead);
        User currentUser = authService.getCurrentUser();

        LeadLostReason reason = lostReasonRepo.findById(req.getLostReasonId())
                .orElseThrow(() -> new IllegalArgumentException("Lost reason not found: " + req.getLostReasonId()));

        access.sameCompany(lead.getCompany().getId(),reason.getCompany());
        if(!Boolean.TRUE.equals(reason.getIsActive())) throw new IllegalArgumentException("Lost reason is inactive.");
        lead.setLostReason(reason);
        lead.setLostNotes(req.getNotes());

        statusRepo.findByCompanyIdOrderBySortOrderAsc(lead.getCompany().getId()).stream()
                .filter(s -> s.getStageType() == StageType.LOST)
                .findFirst()
                .ifPresent(lead::setStatus);

        Lead saved = leadRepo.save(lead);
        customerService.refreshLifecycle(saved.getCustomer());

        recordActivity(saved, ActivityType.CLOSED, "Lead Marked Lost",
                "Reason: " + reason.getReasonText() + ". Notes: " + req.getNotes(), currentUser);

        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public LeadDto getLeadById(Long id) {
        access.record(com.leads.model.entity.Lead.class, id);
        Lead lead = leadRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));
        return mapToDto(lead);
    }

    @Transactional(readOnly = true)
    public Page<LeadDto> getLeads(Long companyId, Long branchId, Long statusId, Long sourceId, String search,
                                   java.time.LocalDate fromDate, java.time.LocalDate toDate, Boolean excludeClosed, com.leads.model.enums.PurposeClassification purposeClassification, Long assignedToUserId, Boolean missingPurpose, Pageable pageable) {
        Specification<Lead> spec = buildLeadSpecification(companyId, branchId, statusId, sourceId, search, fromDate, toDate, excludeClosed);
        if (purposeClassification != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("purpose").get("classification"), purposeClassification));
        if (Boolean.TRUE.equals(missingPurpose)) spec = spec.and((root, query, cb) -> cb.isNull(root.get("purpose")));
        if (assignedToUserId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("assignedTo").get("id"), assignedToUserId));
        return leadRepo.findAll(spec, com.leads.util.InputRules.page(pageable,"createdAt","updatedAt","leadNumber","firstName","closingDate","estimatedDealValue","id")).map(this::mapToDto);
    }

    // Reports & Analytics reuses the exact same company/branch/date filtering
    // as the pipeline grid and dashboard metrics instead of re-deriving it —
    // pass fromDate/toDate as null for the unbounded, full-history list a
    // "customer regained" gap analysis needs.
    @Transactional(readOnly = true)
    public List<Lead> findLeadsForReport(Long companyId, Long branchId, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        return leadRepo.findAll(buildLeadSpecification(companyId, branchId, null, null, null, fromDate, toDate, null));
    }

    /** Shared by getLeads (paginated grid) and getCompanyMetrics (dashboard aggregates) so the two never drift apart. */
    private Specification<Lead> buildLeadSpecification(Long companyId, Long branchId, Long statusId, Long sourceId,
                                                         String search, java.time.LocalDate fromDate, java.time.LocalDate toDate,
                                                         Boolean excludeClosed) {
        access.scope(companyId, branchId);
        com.leads.util.InputRules.dateRange(fromDate,toDate);
        List<Long> permittedBranches = access.branchIds();
        // D14 (module 06 P1): OWN agents see their own leads (+ unassigned with include_unassigned).
        // Shared by the grid, the Kanban, the dashboard metrics and the reports, so they all agree.
        Long ownMe = access.ownerFilter();
        boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();
        // D15 (module 06 P1): "from"/"to" are company-local days, as server wall-clock bounds.
        Company zoneCompany = companyId != null ? access.company(companyId) : null;
        LocalDateTime fromBound = fromDate == null ? null : companyClock.range(zoneCompany, fromDate, fromDate).start();
        LocalDateTime toBound = toDate == null ? null : companyClock.range(zoneCompany, toDate, toDate).endExclusive();
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (permittedBranches != null) predicates.add(root.get("branch").get("id").in(permittedBranches));
            if (ownMe != null) predicates.add(ownPredicate(root, cb, ownMe, ownUnassigned));

            if (companyId != null) {
                predicates.add(cb.equal(root.get("company").get("id"), companyId));
            }
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branch").get("id"), branchId));
            }
            if (statusId != null) {
                predicates.add(cb.equal(root.get("status").get("id"), statusId));
            }
            if (sourceId != null) {
                predicates.add(cb.equal(root.get("source").get("id"), sourceId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromBound));
            }
            if (toDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toBound));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("leadNumber")), pattern),
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern),
                        cb.like(cb.lower(root.get("phone")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("customerCompanyName")), pattern)
                ));
            }
            // "Closed" mirrors the definition used everywhere else this session
            // (WhatsApp re-engagement, status-lock) — Converted or Lost. Used by
            // pickers (e.g. the Quotation builder's lead search) that only make
            // sense against a still-open lead; reports/metrics never pass this.
            if (Boolean.TRUE.equals(excludeClosed)) {
                predicates.add(cb.isFalse(root.get("isConverted")));
                predicates.add(cb.notEqual(root.get("status").get("stageType"), StageType.LOST));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public Page<LeadDto> getLeadsByCustomer(Long customerId, Pageable pageable) {
        access.record(com.leads.model.entity.Customer.class, customerId);
        // Module 06 P1: the customer's leads the caller may see: permitted branches and, for OWN agents,
        // their own (was: every lead of the customer in every branch).
        List<Long> permittedBranches = access.branchIds();
        Long ownMe = access.ownerFilter();
        boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();
        Specification<Lead> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            if (permittedBranches != null) predicates.add(root.get("branch").get("id").in(permittedBranches));
            if (ownMe != null) predicates.add(ownPredicate(root, cb, ownMe, ownUnassigned));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        org.springframework.data.domain.Pageable byNewest = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return leadRepo.findAll(spec, byNewest).map(this::mapToDto);
    }

    /** D14: assigned to {@code me}, or also unassigned when the org shows unassigned leads to OWN agents. */
    private static jakarta.persistence.criteria.Predicate ownPredicate(jakarta.persistence.criteria.Root<Lead> root,
            jakarta.persistence.criteria.CriteriaBuilder cb, Long me, boolean includeUnassigned) {
        var assignee = root.join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
        return includeUnassigned ? cb.or(cb.equal(assignee.get("id"), me), cb.isNull(assignee.get("id"))) : cb.equal(assignee.get("id"), me);
    }

    /**
     * D14 (module 06 P1): OWN agents may not assign, reassign or unassign. The one exception is
     * self-claim: an unassigned lead, to themself, with own_scope.include_unassigned on (no
     * LEADS:ASSIGN right needed). Returns true when the change is allowed for an OWN agent;
     * false for everyone else (the caller then checks LEADS:ASSIGN as before).
     */
    private boolean ownAssigneeChange(Lead lead, Long newAssignee) {
        Long me = access.ownerFilter();
        if (me == null) return false;
        Long current = lead.getAssignedTo() == null ? null : lead.getAssignedTo().getId();
        if (Objects.equals(current, newAssignee)) return true;
        if (current == null && me.equals(newAssignee) && access.ownIncludesUnassigned()) return true;
        throw new org.springframework.security.access.AccessDeniedException("You can only claim unassigned leads for yourself.");
    }

    @Transactional(readOnly = true)
    public List<LeadTimelineDto> getLeadTimeline(Long leadId) {
        access.record(com.leads.model.entity.Lead.class, leadId);
        return activityRepo.findByLeadIdOrderByCreatedAtDesc(leadId)
                .stream()
                .map(this::mapTimelineToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LeadMetricsDto getCompanyMetrics(Long companyId, Long branchId, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        Specification<Lead> spec = buildLeadSpecification(companyId, branchId, null, null, null, fromDate, toDate, null);
        List<Lead> leads = leadRepo.findAll(spec);

        long total = leads.size();
        long open = leads.stream().filter(l -> l.getStatus().getStageType() == StageType.OPEN).count();
        long inProgress = leads.stream().filter(l -> l.getStatus().getStageType() == StageType.IN_PROGRESS).count();
        long qualified = leads.stream().filter(l -> l.getStatus().getStageType() == StageType.QUALIFIED).count();
        long converted = leads.stream().filter(l -> l.getStatus().getStageType() == StageType.CONVERTED || Boolean.TRUE.equals(l.getIsConverted())).count();
        long lost = leads.stream().filter(l -> l.getStatus().getStageType() == StageType.LOST).count();

        double conversionRate = total > 0 ? ((double) converted / total) * 100.0 : 0.0;

        BigDecimal pipelineValue = leads.stream()
                .map(Lead::getEstimatedDealValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> byStatus = leads.stream()
                .collect(Collectors.groupingBy(l -> l.getStatus().getStatusName(), Collectors.counting()));

        // Keyed by the source's stable code (not its display name, which a
        // custom source can be renamed to) so the frontend can reliably look
        // up "how many leads came in via WHATSAPP" across renames.
        Map<String, Long> bySource = leads.stream()
                .collect(Collectors.groupingBy(l -> l.getSource().getSourceCode(), Collectors.counting()));

        return LeadMetricsDto.builder()
                .pendingPurposeLeads(leadRepo.count(buildLeadSpecification(companyId, branchId, null, null, null, null, null, null)
                        .and((root, query, cb) -> cb.isNull(root.get("purpose")))))
                .totalLeads(total)
                .openLeads(open)
                .inProgressLeads(inProgress)
                .qualifiedLeads(qualified)
                .convertedLeads(converted)
                .lostLeads(lost)
                .conversionRate(Math.round(conversionRate * 10.0) / 10.0)
                .totalPipelineValue(pipelineValue)
                .leadsByStatus(byStatus)
                .leadsBySource(bySource)
                .build();
    }

    public void recordActivity(Lead lead, ActivityType type, String title, String description, User performedBy) {
        recordActivity(lead, type, title, description, performedBy, null, null);
    }

    public void recordActivity(Lead lead, ActivityType type, String title, String description, User performedBy,
                                String attachmentName, String attachmentData) {
        LeadTimelineActivity activity = LeadTimelineActivity.builder()
                .lead(lead)
                .activityType(type)
                .title(title)
                .description(description)
                .performedBy(performedBy)
                .attachmentName(attachmentName)
                .attachmentData(attachmentData)
                .build();
        activityRepo.save(activity);
    }

    /**
     * Attributes a newly-created WhatsApp lead to the ad it came from, when
     * resolvable. {@code ctwaClid} is set unconditionally — it's known
     * immediately from the originating conversation and needed regardless
     * of ad-sync timing (future Conversions API use). {@code ad} is set
     * only when a matching {@link Ad} row already exists for
     * {@code externalAdId}; if the periodic ad sync hasn't reached that ad
     * yet, this is a no-op for the ad link and AdSyncConnectionService's
     * reconciliation sweep resolves it on the next sync instead.
     */
    @Transactional
    public void linkAdAndClid(Long leadId, Long companyId, String externalAdId, String ctwaClid) {
        access.record(com.leads.model.entity.Lead.class, leadId);
        if (externalAdId == null && ctwaClid == null) return;
        Lead lead = leadRepo.findById(leadId).orElse(null);
        if (lead == null) return;

        lead.setCtwaClid(ctwaClid);
        if (externalAdId != null) {
            Long leadBranchId = lead.getBranch() != null ? lead.getBranch().getId() : null;
            List<Ad> candidates = adRepo.findByCompanyIdAndExternalAdId(companyId, externalAdId);
            Ad resolved = AdAttributionResolver.pickBestMatch(candidates, leadBranchId);
            if (resolved != null) lead.setAd(resolved);
        }
        leadRepo.save(lead);
    }

    /** A user-authored note (optionally with a file attachment) on the Lead's activity log — the Lead Detail page's ClickUp-style panel. */
    @Transactional
    public LeadTimelineDto addNote(Long leadId, String note, String attachmentName, String attachmentData) {
        access.record(com.leads.model.entity.Lead.class, leadId);
        Lead lead = leadRepo.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        User currentUser = authService.getCurrentUser();

        com.leads.util.InputRules.attachment(attachmentName,attachmentData);
        String title = attachmentName != null && !attachmentName.isBlank() ? "Note with Attachment" : "Note Added";
        LeadTimelineActivity activity = LeadTimelineActivity.builder()
                .lead(lead)
                .activityType(ActivityType.NOTE)
                .title(title)
                .description(note)
                .performedBy(currentUser)
                .attachmentName(attachmentName)
                .attachmentData(attachmentData)
                .build();
        return mapTimelineToDto(activityRepo.save(activity));
    }

    private void saveLeadProducts(Lead lead, List<LeadProductDto> productDtos) {
        BigDecimal totalDealValue = BigDecimal.ZERO;

        for (LeadProductDto pDto : productDtos) {
            ProductMaster product = productRepo.findById(pDto.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + pDto.getProductId()));

            access.sameCompany(lead.getCompany().getId(),product.getCompany());
            if(!Boolean.TRUE.equals(product.getIsActive())) throw new IllegalArgumentException("Product is inactive.");
            if(!java.util.Objects.equals(product.getCurrency(),resolveDefaultCurrency(lead))) throw new IllegalArgumentException("Product currency differs from the lead currency.");
            if(pDto.getQuantity()!=null && pDto.getQuantity()<=0) throw new IllegalArgumentException("Quantity must be positive.");
            com.leads.util.InputRules.amount(pDto.getUnitPrice(),"Unit price");
            com.leads.util.InputRules.amount(pDto.getDiscountAmount(),"Discount");
            com.leads.util.InputRules.amount(pDto.getTaxAmount(),"Tax");
            int qty = pDto.getQuantity() != null && pDto.getQuantity() > 0 ? pDto.getQuantity() : 1;
            BigDecimal price = pDto.getUnitPrice() != null ? pDto.getUnitPrice() : product.getUnitPrice();
            BigDecimal discount = pDto.getDiscountAmount() != null ? pDto.getDiscountAmount() : BigDecimal.ZERO;
            BigDecimal tax = pDto.getTaxAmount() != null ? pDto.getTaxAmount() : BigDecimal.ZERO;

            if(discount.compareTo(price.multiply(BigDecimal.valueOf(qty)))>0) throw new IllegalArgumentException("Discount exceeds the line value.");
            BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(qty)).subtract(discount).add(tax);
            totalDealValue = totalDealValue.add(lineTotal);

            LeadProduct lp = LeadProduct.builder()
                    .lead(lead)
                    .product(product)
                    .quantity(qty)
                    .unitPrice(price)
                    .discountAmount(discount)
                    .taxAmount(tax)
                    .totalPrice(lineTotal)
                    .notes(pDto.getNotes())
                    .build();

            leadProductRepo.save(lp);
        }

        lead.setEstimatedDealValue(totalDealValue);
        leadRepo.save(lead);
    }

    private void validateReferences(Long companyId,Long branchId,Long statusId,Long sourceId,Long purposeId,Long assigneeId) {
        if(statusId!=null) {
            LeadStatus status=access.record(LeadStatus.class,statusId);access.sameCompany(companyId,status.getCompany());
            if(!Boolean.TRUE.equals(status.getIsActive()) || java.util.Set.of(StageType.CONVERTED,StageType.LOST,StageType.CLOSED).contains(status.getStageType())) throw new IllegalArgumentException("Use the dedicated conversion/lost flow for a terminal stage.");
        }
        if(sourceId!=null) {var source=access.record(LeadSource.class,sourceId);access.sameCompany(companyId,source.getCompany());if(!Boolean.TRUE.equals(source.getIsActive()))throw new IllegalArgumentException("Source is inactive.");}
        if(purposeId!=null) {var purpose=access.record(LeadPurpose.class,purposeId);access.sameCompany(companyId,purpose.getCompany());if(!Boolean.TRUE.equals(purpose.getIsActive()))throw new IllegalArgumentException("Purpose is inactive.");}
        if(assigneeId!=null) validateAssignee(companyId,branchId,userRepo.findById(assigneeId).orElseThrow(()->new IllegalArgumentException("Unknown assignee.")));
    }
    private void validateAssignee(Long companyId,Long branchId,User user) {
        if(!Boolean.TRUE.equals(user.getIsActive()) || user.getOrganization()==null || !user.getOrganization().getId().equals(access.company(companyId).getOrganization().getId())) throw new IllegalArgumentException("Assignee is outside this organization or inactive.");
        if(!access.canCompany(user,access.company(companyId)) || (user.getRole().getRoleLevel()==com.leads.model.enums.RoleLevel.BRANCH && user.getAllowedBranches().stream().noneMatch(b->b.getId().equals(branchId)))) throw new IllegalArgumentException("Assignee cannot access this branch.");
    }

    private String generateLeadNumber(Long companyId) {
        return numbers.next("LD",companyId);
    }

    public LeadDto mapToDto(Lead l) {
        if (l == null) return null;

        Map<String, Object> dynamicMap = new HashMap<>();
        if (l.getDynamicValues() != null && !l.getDynamicValues().isBlank()) {
            try {
                dynamicMap = objectMapper.readValue(l.getDynamicValues(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.error("Failed to deserialize dynamic values: {}", e.getMessage());
            }
        }

        List<LeadProductDto> productDtos = leadProductRepo.findByLeadId(l.getId()).stream()
                .map(lp -> LeadProductDto.builder()
                        .id(lp.getId())
                        .productId(lp.getProduct().getId())
                        .productCode(lp.getProduct().getProductCode())
                        .productName(lp.getProduct().getProductName())
                        .sku(lp.getProduct().getSku())
                        .quantity(lp.getQuantity())
                        .unitPrice(lp.getUnitPrice())
                        .discountAmount(lp.getDiscountAmount())
                        .taxAmount(lp.getTaxAmount())
                        .totalPrice(lp.getTotalPrice())
                        .notes(lp.getNotes())
                        .build())
                .collect(Collectors.toList());

        return LeadDto.builder()
                .id(l.getId())
                .organizationId(l.getOrganization().getId())
                .organizationName(l.getOrganization().getName())
                .companyId(l.getCompany().getId())
                .companyName(l.getCompany().getName())
                .branchId(l.getBranch().getId())
                .branchName(l.getBranch().getName())
                .customer(l.getCustomer() != null ? customerService.mapToSummaryDto(l.getCustomer()) : null)
                .leadNumber(l.getLeadNumber())
                .firstName(l.getFirstName())
                .lastName(l.getLastName())
                .fullName(l.getFullName())
                .email(l.getEmail())
                .phone(l.getPhone())
                .whatsappNumber(l.getWhatsappNumber())
                .customerCompanyName(l.getCustomerCompanyName())
                .jobTitle(l.getJobTitle())
                .nationality(l.getNationality())
                .preferredLanguage(l.getPreferredLanguage())
                .source(leadSettingsService.mapSourceToDto(l.getSource()))
                .purpose(leadSettingsService.mapPurposeToDto(l.getPurpose()))
                .status(leadSettingsService.mapStatusToDto(l.getStatus()))
                .assignedToUserId(l.getAssignedTo() != null ? l.getAssignedTo().getId() : null)
                .assignedToName(l.getAssignedTo() != null ? l.getAssignedTo().getFullName() : "Unassigned")
                .estimatedDealValue(l.getEstimatedDealValue())
                .closingDate(l.getClosingDate())
                .notes(l.getNotes())
                .dynamicValues(dynamicMap)
                .isConverted(l.getIsConverted())
                .convertedAt(l.getConvertedAt())
                .lostReasonId(l.getLostReason() != null ? l.getLostReason().getId() : null)
                .lostReasonText(l.getLostReason() != null ? l.getLostReason().getReasonText() : null)
                .lostNotes(l.getLostNotes())
                .products(productDtos)
                .defaultCurrency(resolveDefaultCurrency(l))
                .defaultVatPercent(l.getBranch().getDefaultVatPercent() != null ? l.getBranch().getDefaultVatPercent() : BigDecimal.ZERO)
                .defaultQuotationTerms(l.getBranch().getQuotationTerms())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }

    /** Branch's own quoting currency wins; falls back to the company's when the branch hasn't set one. Shared with QuotationService so a saved quotation's currency always matches what the builder was shown. */
    public static String resolveDefaultCurrency(Lead lead) {
        String branchCurrency = lead.getBranch().getDefaultCurrency();
        return branchCurrency != null && !branchCurrency.isBlank() ? branchCurrency : lead.getCompany().getCurrency();
    }

    private LeadTimelineDto mapTimelineToDto(LeadTimelineActivity a) {
        return LeadTimelineDto.builder()
                .id(a.getId())
                .leadId(a.getLead().getId())
                .deliveryId(a.getDelivery() != null ? a.getDelivery().getId() : null)
                .activityType(a.getActivityType())
                .title(a.getTitle())
                .description(a.getDescription())
                .metaData(a.getMetaData())
                .attachmentName(a.getAttachmentName())
                .attachmentData(a.getAttachmentData())
                .performedByUserId(a.getPerformedBy() != null ? a.getPerformedBy().getId() : null)
                .performedByName(a.getPerformedBy() != null ? a.getPerformedBy().getFullName() : "System")
                .createdAt(a.getCreatedAt())
                .build();
    }
}
