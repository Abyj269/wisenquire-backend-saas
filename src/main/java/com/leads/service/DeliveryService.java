package com.leads.service;

import com.leads.dto.delivery.*;
import com.leads.dto.lead.LeadProductDto;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.DeliveryStatus;
import com.leads.model.enums.RequestedDeliveryAction;
import com.leads.model.enums.StageType;
import com.leads.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryService {
    private final TenantAccess access;
    private final CompanyClock companyClock;
    private final DocumentNumberService numbers;

    private final DeliveryRepository deliveryRepo;
    private final DeliveryFailureReasonRepository failureReasonRepo;
    private final CompanyDeliverySettingsRepository settingsRepo;
    private final LeadProductRepository leadProductRepo;
    private final LeadTimelineActivityRepository activityRepo;
    private final UserRepository userRepo;
    private final AuthService authService;
    private final CustomerService customerService;

    @Transactional
    public DeliveryDto createDelivery(DeliveryCreateRequest req) {
        Lead lead = access.record(Lead.class, req.getLeadId());

        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(lead.getCompany().getId()).orElse(null);
        if (settings == null || !Boolean.TRUE.equals(settings.getDeliveryModuleEnabled())) {
            throw new IllegalArgumentException("The Delivery module is disabled for this company.");
        }
        if (lead.getStatus().getStageType() == StageType.LOST) {
            throw new IllegalArgumentException("A delivery cannot be scheduled on a lost lead.");
        }
        if (req.getScheduledDate().isBefore(companyClock.today(lead.getCompany()))) { // D15: the company's today
            throw new IllegalArgumentException("The scheduled date cannot be in the past.");
        }

        User currentUser = authService.getCurrentUser();

        User assignedTo = null;
        if (req.getAssignedToUserId() != null) {
            denyOwn("assign deliveries");
            access.permission("DELIVERIES:ASSIGN");
            assignedTo = userRepo.findById(req.getAssignedToUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Assignee not found: " + req.getAssignedToUserId()));
            validateAssignee(lead.getCompany().getId(), lead.getBranch().getId(), assignedTo);
        }

        Delivery delivery = Delivery.builder()
                .organization(lead.getOrganization())
                .company(lead.getCompany())
                .branch(lead.getBranch())
                .lead(lead)
                .deliveryNumber(numbers.next("DL", lead.getCompany().getId()))
                .status(DeliveryStatus.SCHEDULED)
                .scheduledDate(req.getScheduledDate())
                .assignedTo(assignedTo)
                .assignedBy(assignedTo != null ? currentUser : null)
                .recipientName(req.getRecipientName())
                .recipientPhone(req.getRecipientPhone())
                .addressLine1(req.getAddressLine1())
                .addressLine2(req.getAddressLine2())
                .city(req.getCity())
                .stateOrEmirate(req.getStateOrEmirate())
                .country(req.getCountry())
                .postalCode(req.getPostalCode())
                .landmark(req.getLandmark())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .deliveryNotes(req.getDeliveryNotes())
                .createdBy(currentUser)
                .build();

        Delivery saved = deliveryRepo.save(delivery);

        recordActivity(lead, saved, ActivityType.DELIVERY_SCHEDULED, "Delivery Scheduled",
                "Delivery #" + saved.getDeliveryNumber() + " scheduled for " + saved.getScheduledDate()
                        + (assignedTo != null ? ", assigned to " + assignedTo.getFullName() : ""), currentUser);

        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto updateDelivery(Long id, DeliveryUpdateRequest req) {
        Delivery delivery = access.record(Delivery.class, id);
        if (delivery.getStatus() != DeliveryStatus.SCHEDULED) {
            throw new IllegalArgumentException("Only a Scheduled delivery's details can be edited directly — use the appropriate status action otherwise.");
        }

        User currentUser = authService.getCurrentUser();

        delivery.setScheduledDate(req.getScheduledDate());
        delivery.setRecipientName(req.getRecipientName());
        delivery.setRecipientPhone(req.getRecipientPhone());
        delivery.setAddressLine1(req.getAddressLine1());
        delivery.setAddressLine2(req.getAddressLine2());
        delivery.setCity(req.getCity());
        delivery.setStateOrEmirate(req.getStateOrEmirate());
        delivery.setCountry(req.getCountry());
        delivery.setPostalCode(req.getPostalCode());
        delivery.setLandmark(req.getLandmark());
        delivery.setLatitude(req.getLatitude());
        delivery.setLongitude(req.getLongitude());
        delivery.setDeliveryNotes(req.getDeliveryNotes());

        if (req.getAssignedToUserId() == null && delivery.getAssignedTo() != null) {
            denyOwn("unassign deliveries");
            access.permission("DELIVERIES:ASSIGN");
            delivery.setAssignedTo(null);
            delivery.setAssignedBy(currentUser);
            recordActivity(delivery.getLead(), delivery, ActivityType.REASSIGNMENT, "Delivery Unassigned", "Assignment cleared", currentUser);
        } else if (req.getAssignedToUserId() != null
                && (delivery.getAssignedTo() == null || !delivery.getAssignedTo().getId().equals(req.getAssignedToUserId()))) {
            denyOwn("assign deliveries");
            access.permission("DELIVERIES:ASSIGN");
            User oldUser = delivery.getAssignedTo();
            User newUser = userRepo.findById(req.getAssignedToUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + req.getAssignedToUserId()));
            validateAssignee(delivery.getCompany().getId(), delivery.getBranch().getId(), newUser);
            delivery.setAssignedTo(newUser);
            delivery.setAssignedBy(currentUser);
            recordActivity(delivery.getLead(), delivery, ActivityType.REASSIGNMENT, "Delivery Reassigned",
                    "Reassigned from " + (oldUser != null ? oldUser.getFullName() : "Unassigned") + " to " + newUser.getFullName(), currentUser);
        }

        return mapToDto(deliveryRepo.save(delivery));
    }

    @Transactional
    public void assignDeliveries(DeliveryAssignRequest req) {
        denyOwn("assign deliveries");
        access.permission("DELIVERIES:ASSIGN");
        User assignToUser = userRepo.findById(req.getAssignToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Assignee not found: " + req.getAssignToUserId()));
        User currentUser = authService.getCurrentUser();

        for (Long deliveryId : req.getDeliveryIds()) {
            Delivery delivery = access.record(Delivery.class, deliveryId);
            validateAssignee(delivery.getCompany().getId(), delivery.getBranch().getId(), assignToUser);
            delivery.setAssignedTo(assignToUser);
            delivery.setAssignedBy(currentUser);
            deliveryRepo.save(delivery);

            recordActivity(delivery.getLead(), delivery, ActivityType.ASSIGNMENT, "Delivery Assigned",
                    "Assigned to " + assignToUser.getFullName() + (req.getAssignmentNotes() != null ? ". Notes: " + req.getAssignmentNotes() : ""), currentUser);
        }
    }

    @Transactional
    public DeliveryDto markOutForDelivery(Long id) {
        Delivery delivery = requireOwnAssignee(access.record(Delivery.class, id));
        requireStatus(delivery, "start the delivery run", DeliveryStatus.SCHEDULED);
        requireAssigned(delivery);

        delivery.setStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Out for Delivery",
                "Delivery #" + saved.getDeliveryNumber() + " is out for delivery.", authService.getCurrentUser());
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto markDelivered(Long id, DeliveryMarkDeliveredRequest req) {
        Delivery delivery = requireOwnAssignee(access.record(Delivery.class, id));
        requireStatus(delivery, "mark this delivery as delivered", DeliveryStatus.SCHEDULED, DeliveryStatus.OUT_FOR_DELIVERY);
        requireAssigned(delivery);

        User currentUser = authService.getCurrentUser();
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(LocalDateTime.now());
        delivery.setDeliveryProofNotes(req.getDeliveryProofNotes());

        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Delivered",
                "Delivery #" + saved.getDeliveryNumber() + " marked Delivered."
                        + (req.getDeliveryProofNotes() != null ? " Notes: " + req.getDeliveryProofNotes() : ""), currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto markNotDelivered(Long id, DeliveryMarkNotDeliveredRequest req) {
        Delivery delivery = requireOwnAssignee(access.record(Delivery.class, id));
        requireStatus(delivery, "mark this delivery as not delivered", DeliveryStatus.SCHEDULED, DeliveryStatus.OUT_FOR_DELIVERY);
        requireAssigned(delivery);

        DeliveryFailureReason reason = failureReasonRepo.findById(req.getFailureReasonId())
                .orElseThrow(() -> new IllegalArgumentException("Failure reason not found: " + req.getFailureReasonId()));
        access.sameCompany(delivery.getCompany().getId(), reason.getCompany());
        if (!Boolean.TRUE.equals(reason.getIsActive())) throw new IllegalArgumentException("Failure reason is inactive.");

        User currentUser = authService.getCurrentUser();
        delivery.setStatus(DeliveryStatus.NOT_DELIVERED);
        delivery.setFailureReason(reason);
        delivery.setFailureNotes(req.getFailureNotes());
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now());
        delivery.setRequestedAction(RequestedDeliveryAction.NONE);

        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(delivery.getCompany().getId()).orElse(null);
        boolean attemptsExceeded = settings != null && settings.getMaxDeliveryAttempts() != null
                && delivery.getAttemptCount() >= settings.getMaxDeliveryAttempts();

        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Not Delivered",
                "Delivery #" + saved.getDeliveryNumber() + " attempt #" + saved.getAttemptCount() + " failed. Reason: " + reason.getReasonText()
                        + (req.getFailureNotes() != null ? ". Notes: " + req.getFailureNotes() : "")
                        + (attemptsExceeded ? " (advisory: attempt limit of " + settings.getMaxDeliveryAttempts() + " reached)" : ""), currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto requestPostpone(Long id, DeliveryPostponeRequest req) {
        Delivery delivery = requireOwnAssignee(access.record(Delivery.class, id));
        requireStatus(delivery, "request a postponement", DeliveryStatus.NOT_DELIVERED);
        if (req.getRequestedNewDate().isBefore(companyClock.today(delivery.getCompany()))) { // D15: the company's today
            throw new IllegalArgumentException("The new date cannot be in the past.");
        }

        User currentUser = authService.getCurrentUser();
        delivery.setRequestedAction(RequestedDeliveryAction.POSTPONE);
        delivery.setRequestedNewDate(req.getRequestedNewDate());
        delivery.setRequestedByUser(currentUser);
        delivery.setRequestedAt(LocalDateTime.now());
        delivery.setRequestNotes(req.getRequestNotes());

        boolean requiresApproval = requiresApprovalFor(delivery.getCompany().getId(), true);
        if (requiresApproval) {
            delivery.setStatus(DeliveryStatus.PENDING_APPROVAL);
            Delivery saved = deliveryRepo.save(delivery);
            recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_APPROVAL_REQUESTED, "Postponement Requested",
                    "Requested to postpone delivery #" + saved.getDeliveryNumber() + " to " + saved.getRequestedNewDate()
                            + (req.getRequestNotes() != null ? ". Notes: " + req.getRequestNotes() : ""), currentUser);
            return mapToDto(saved);
        }

        delivery.setStatus(DeliveryStatus.SCHEDULED);
        delivery.setScheduledDate(req.getRequestedNewDate());
        delivery.setRequestedAction(RequestedDeliveryAction.NONE);
        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Postponed",
                "Delivery #" + saved.getDeliveryNumber() + " rescheduled to " + saved.getScheduledDate(), currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto requestClose(Long id, DeliveryCloseRequest req) {
        Delivery delivery = requireOwnAssignee(access.record(Delivery.class, id));
        requireStatus(delivery, "request to close this delivery", DeliveryStatus.NOT_DELIVERED);

        User currentUser = authService.getCurrentUser();
        delivery.setRequestedAction(RequestedDeliveryAction.CLOSE);
        delivery.setRequestedByUser(currentUser);
        delivery.setRequestedAt(LocalDateTime.now());
        delivery.setRequestNotes(req.getRequestNotes());

        boolean requiresApproval = requiresApprovalFor(delivery.getCompany().getId(), false);
        if (requiresApproval) {
            delivery.setStatus(DeliveryStatus.PENDING_APPROVAL);
            Delivery saved = deliveryRepo.save(delivery);
            recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_APPROVAL_REQUESTED, "Closure Requested",
                    "Requested to close delivery #" + saved.getDeliveryNumber() + " without delivering. Notes: " + req.getRequestNotes(), currentUser);
            return mapToDto(saved);
        }

        delivery.setStatus(DeliveryStatus.CLOSED);
        delivery.setRequestedAction(RequestedDeliveryAction.NONE);
        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Closed",
                "Delivery #" + saved.getDeliveryNumber() + " closed without delivering. Notes: " + req.getRequestNotes(), currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto approve(Long id) {
        Delivery delivery = access.record(Delivery.class, id);
        denyOwn("approve delivery requests");
        requireStatus(delivery, "approve this request", DeliveryStatus.PENDING_APPROVAL);

        User currentUser = authService.getCurrentUser();
        RequestedDeliveryAction action = delivery.getRequestedAction();

        if (action == RequestedDeliveryAction.POSTPONE) {
            delivery.setStatus(DeliveryStatus.SCHEDULED);
            delivery.setScheduledDate(delivery.getRequestedNewDate());
        } else if (action == RequestedDeliveryAction.CLOSE) {
            delivery.setStatus(DeliveryStatus.CLOSED);
        } else {
            throw new IllegalArgumentException("This delivery has no pending postpone/close request to approve.");
        }
        delivery.setApprovalDecisionBy(currentUser);
        delivery.setApprovalDecisionAt(LocalDateTime.now());
        delivery.setRequestedAction(RequestedDeliveryAction.NONE);

        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_APPROVED,
                action == RequestedDeliveryAction.POSTPONE ? "Postponement Approved" : "Closure Approved",
                "Delivery #" + saved.getDeliveryNumber() + " request approved by " + currentUser.getFullName() + ".", currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto reject(Long id, DeliveryRejectRequest req) {
        Delivery delivery = access.record(Delivery.class, id);
        denyOwn("reject delivery requests");
        requireStatus(delivery, "reject this request", DeliveryStatus.PENDING_APPROVAL);

        User currentUser = authService.getCurrentUser();
        delivery.setStatus(DeliveryStatus.NOT_DELIVERED);
        delivery.setApprovalDecisionBy(currentUser);
        delivery.setApprovalDecisionAt(LocalDateTime.now());
        delivery.setApprovalRejectionNotes(req.getApprovalRejectionNotes());
        delivery.setRequestedAction(RequestedDeliveryAction.NONE);

        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_REJECTED, "Request Rejected",
                "Delivery #" + saved.getDeliveryNumber() + " request rejected by " + currentUser.getFullName() + ". Notes: " + req.getApprovalRejectionNotes(), currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public DeliveryDto cancel(Long id, DeliveryCancelRequest req) {
        Delivery delivery = access.record(Delivery.class, id);
        denyOwn("cancel deliveries");
        if (isTerminal(delivery.getStatus())) {
            throw new IllegalArgumentException("This delivery is already " + delivery.getStatus() + " and can't be cancelled.");
        }

        User currentUser = authService.getCurrentUser();
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledBy(currentUser);
        delivery.setCancelledAt(LocalDateTime.now());
        delivery.setCancelledReason(req.getCancelledReason());

        Delivery saved = deliveryRepo.save(delivery);
        recordActivity(saved.getLead(), saved, ActivityType.DELIVERY_STATUS_CHANGE, "Delivery Cancelled",
                "Delivery #" + saved.getDeliveryNumber() + " cancelled. Reason: " + req.getCancelledReason(), currentUser);
        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public DeliveryDto getDeliveryById(Long id) {
        return mapToDto(access.record(Delivery.class, id));
    }

    @Transactional(readOnly = true)
    public Page<DeliveryDto> getDeliveries(Long companyId, Long branchId, DeliveryStatus status, Long assignedToUserId,
                                            Long leadId, LocalDate scheduledDate, LocalDate scheduledDateFrom, LocalDate scheduledDateTo,
                                            Boolean unassigned, String search, Pageable pageable) {
        access.scope(companyId, branchId);
        com.leads.util.InputRules.dateRange(scheduledDateFrom, scheduledDateTo);
        List<Long> permittedBranches = access.branchIds();
        final Long ownMe = access.ownerFilter();
        final boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();

        Specification<Delivery> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (permittedBranches != null) predicates.add(root.get("branch").get("id").in(permittedBranches));
            if (ownMe != null) predicates.add(ownDeliveryPredicate(root, cb, ownMe, ownUnassigned));
            if (companyId != null) predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (branchId != null) predicates.add(cb.equal(root.get("branch").get("id"), branchId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (assignedToUserId != null) predicates.add(cb.equal(root.get("assignedTo").get("id"), assignedToUserId));
            if (Boolean.TRUE.equals(unassigned)) predicates.add(cb.isNull(root.get("assignedTo")));
            if (leadId != null) predicates.add(cb.equal(root.get("lead").get("id"), leadId));
            if (scheduledDate != null) predicates.add(cb.equal(root.get("scheduledDate"), scheduledDate));
            if (scheduledDateFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledDate"), scheduledDateFrom));
            if (scheduledDateTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("scheduledDate"), scheduledDateTo));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("deliveryNumber")), pattern),
                        cb.like(cb.lower(root.get("recipientName")), pattern),
                        cb.like(cb.lower(root.get("recipientPhone")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return deliveryRepo.findAll(spec, com.leads.util.InputRules.page(pageable, "scheduledDate", "createdAt", "updatedAt", "deliveryNumber", "id"))
                .map(this::mapToDto);
    }

    /** Dashboard warning banner: deliveries scheduled for today that still need a delivery agent assigned. Excludes terminal statuses — a cancelled/closed/delivered delivery no longer needs one. */
    @Transactional(readOnly = true)
    public long countUnassignedToday(Long companyId, Long branchId) {
        access.scope(companyId, branchId);
        List<Long> permittedBranches = access.branchIds();
        // D15 (module 09 P1): the company's today (was the server date).
        LocalDate today = companyId != null ? companyClock.today(access.company(companyId)) : companyClock.serverNow().toLocalDate();
        final Long ownMe = access.ownerFilter();
        final boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();

        Specification<Delivery> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (permittedBranches != null) predicates.add(root.get("branch").get("id").in(permittedBranches));
            if (ownMe != null) predicates.add(ownDeliveryPredicate(root, cb, ownMe, ownUnassigned));
            if (companyId != null) predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (branchId != null) predicates.add(cb.equal(root.get("branch").get("id"), branchId));
            predicates.add(cb.isNull(root.get("assignedTo")));
            predicates.add(cb.equal(root.get("scheduledDate"), today));
            predicates.add(root.get("status").in(DeliveryStatus.SCHEDULED, DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.NOT_DELIVERED, DeliveryStatus.PENDING_APPROVAL));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return deliveryRepo.count(spec);
    }

    @Transactional(readOnly = true)
    public List<com.leads.dto.lead.LeadTimelineDto> getDeliveryTimeline(Long id) {
        access.record(Delivery.class, id);
        return activityRepo.findByDeliveryIdOrderByCreatedAtDesc(id).stream()
                .map(this::mapTimelineToDto)
                .collect(Collectors.toList());
    }

    // --- helpers ---

    private boolean requiresApprovalFor(Long companyId, boolean isPostpone) {
        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(companyId).orElse(null);
        if (settings == null) return true;
        return isPostpone ? Boolean.TRUE.equals(settings.getRequireApprovalForPostpone())
                : Boolean.TRUE.equals(settings.getRequireApprovalForClose());
    }

    private void requireStatus(Delivery delivery, String action, DeliveryStatus... allowed) {
        for (DeliveryStatus s : allowed) if (delivery.getStatus() == s) return;
        throw new IllegalArgumentException("Cannot " + action + " while the delivery is " + delivery.getStatus() + ".");
    }

    /** D14: assigned to me, or its lead is mine (or unassigned with include_unassigned). Same rule as record(Delivery). */
    private static jakarta.persistence.criteria.Predicate ownDeliveryPredicate(jakarta.persistence.criteria.Root<Delivery> root,
            jakarta.persistence.criteria.CriteriaBuilder cb, Long me, boolean includeUnassigned) {
        var agent = root.join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
        var leadOwner = root.join("lead").join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
        var visible = cb.or(cb.equal(agent.get("id"), me), cb.equal(leadOwner.get("id"), me));
        return includeUnassigned ? cb.or(visible, cb.isNull(leadOwner.get("id"))) : visible;
    }

    /**
     * D14 (module 09 P1): with OWN scope, only the assigned delivery agent may record what happened
     * (out for delivery, delivered, not delivered, postpone, close), even when the delivery is
     * visible through the agent's lead.
     */
    private Delivery requireOwnAssignee(Delivery delivery) {
        Long me = access.ownerFilter();
        if (me != null && (delivery.getAssignedTo() == null || !me.equals(delivery.getAssignedTo().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Only the assigned delivery agent can update this delivery.");
        }
        return delivery;
    }

    /** D14 (module 09 P1): OWN users never assign, approve, reject or cancel deliveries, whatever their permissions. */
    private void denyOwn(String action) {
        if (access.ownerFilter() != null) throw new org.springframework.security.access.AccessDeniedException("Your role cannot " + action + ".");
    }

    /** A delivery agent has to actually be assigned before anyone can record what happened on the ground. */
    private void requireAssigned(Delivery delivery) {
        if (delivery.getAssignedTo() == null) {
            throw new IllegalArgumentException("Assign a delivery agent to this delivery before updating its status.");
        }
    }

    private boolean isTerminal(DeliveryStatus status) {
        return status == DeliveryStatus.DELIVERED || status == DeliveryStatus.CLOSED || status == DeliveryStatus.CANCELLED;
    }

    private void validateAssignee(Long companyId, Long branchId, User user) {
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getOrganization() == null
                || !user.getOrganization().getId().equals(access.company(companyId).getOrganization().getId()))
            throw new IllegalArgumentException("Assignee is outside this organization or inactive.");
        if (!access.canCompany(user, access.company(companyId))
                || (user.getRole().getRoleLevel() == com.leads.model.enums.RoleLevel.BRANCH
                    && user.getAllowedBranches().stream().noneMatch(b -> b.getId().equals(branchId))))
            throw new IllegalArgumentException("Assignee cannot access this branch.");
    }

    private void recordActivity(Lead lead, Delivery delivery, ActivityType type, String title, String description, User performedBy) {
        LeadTimelineActivity activity = LeadTimelineActivity.builder()
                .lead(lead)
                .delivery(delivery)
                .activityType(type)
                .title(title)
                .description(description)
                .performedBy(performedBy)
                .build();
        activityRepo.save(activity);
    }

    private com.leads.dto.lead.LeadTimelineDto mapTimelineToDto(LeadTimelineActivity a) {
        return com.leads.dto.lead.LeadTimelineDto.builder()
                .id(a.getId())
                .leadId(a.getLead().getId())
                .deliveryId(a.getDelivery() != null ? a.getDelivery().getId() : null)
                .activityType(a.getActivityType())
                .title(a.getTitle())
                .description(a.getDescription())
                .metaData(a.getMetaData())
                .performedByUserId(a.getPerformedBy() != null ? a.getPerformedBy().getId() : null)
                .performedByName(a.getPerformedBy() != null ? a.getPerformedBy().getFullName() : "System")
                .createdAt(a.getCreatedAt())
                .build();
    }

    public DeliveryDto mapToDto(Delivery d) {
        if (d == null) return null;
        Lead lead = d.getLead();

        List<LeadProductDto> products = leadProductRepo.findByLeadId(lead.getId()).stream()
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

        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(d.getCompany().getId()).orElse(null);
        boolean attemptsExceeded = settings != null && settings.getMaxDeliveryAttempts() != null
                && d.getAttemptCount() != null && d.getAttemptCount() >= settings.getMaxDeliveryAttempts();

        return DeliveryDto.builder()
                .id(d.getId())
                .organizationId(d.getOrganization().getId())
                .companyId(d.getCompany().getId())
                .branchId(d.getBranch().getId())
                .branchName(d.getBranch().getName())
                .leadId(lead.getId())
                .leadNumber(lead.getLeadNumber())
                .leadFullName(lead.getFullName())
                .leadPhone(lead.getPhone())
                .customer(lead.getCustomer() != null ? customerService.mapToSummaryDto(lead.getCustomer()) : null)
                .products(products)
                .deliveryNumber(d.getDeliveryNumber())
                .status(d.getStatus())
                .scheduledDate(d.getScheduledDate())
                .assignedToUserId(d.getAssignedTo() != null ? d.getAssignedTo().getId() : null)
                .assignedToName(d.getAssignedTo() != null ? d.getAssignedTo().getFullName() : "Unassigned")
                .assignedByUserId(d.getAssignedBy() != null ? d.getAssignedBy().getId() : null)
                .assignedByName(d.getAssignedBy() != null ? d.getAssignedBy().getFullName() : null)
                .recipientName(d.getRecipientName())
                .recipientPhone(d.getRecipientPhone())
                .addressLine1(d.getAddressLine1())
                .addressLine2(d.getAddressLine2())
                .city(d.getCity())
                .stateOrEmirate(d.getStateOrEmirate())
                .country(d.getCountry())
                .postalCode(d.getPostalCode())
                .landmark(d.getLandmark())
                .latitude(d.getLatitude())
                .longitude(d.getLongitude())
                .deliveryNotes(d.getDeliveryNotes())
                .attemptCount(d.getAttemptCount())
                .lastAttemptAt(d.getLastAttemptAt())
                .deliveredAt(d.getDeliveredAt())
                .deliveryProofNotes(d.getDeliveryProofNotes())
                .failureReasonId(d.getFailureReason() != null ? d.getFailureReason().getId() : null)
                .failureReasonText(d.getFailureReason() != null ? d.getFailureReason().getReasonText() : null)
                .failureNotes(d.getFailureNotes())
                .requestedAction(d.getRequestedAction())
                .requestedNewDate(d.getRequestedNewDate())
                .requestedByUserId(d.getRequestedByUser() != null ? d.getRequestedByUser().getId() : null)
                .requestedByName(d.getRequestedByUser() != null ? d.getRequestedByUser().getFullName() : null)
                .requestedAt(d.getRequestedAt())
                .requestNotes(d.getRequestNotes())
                .approvalDecisionByUserId(d.getApprovalDecisionBy() != null ? d.getApprovalDecisionBy().getId() : null)
                .approvalDecisionByName(d.getApprovalDecisionBy() != null ? d.getApprovalDecisionBy().getFullName() : null)
                .approvalDecisionAt(d.getApprovalDecisionAt())
                .approvalRejectionNotes(d.getApprovalRejectionNotes())
                .cancelledReason(d.getCancelledReason())
                .cancelledByUserId(d.getCancelledBy() != null ? d.getCancelledBy().getId() : null)
                .cancelledByName(d.getCancelledBy() != null ? d.getCancelledBy().getFullName() : null)
                .cancelledAt(d.getCancelledAt())
                .createdByUserId(d.getCreatedBy() != null ? d.getCreatedBy().getId() : null)
                .createdByName(d.getCreatedBy() != null ? d.getCreatedBy().getFullName() : null)
                .attemptsExceededAdvisory(attemptsExceeded)
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
