package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.automation.*;
import com.leads.model.entity.*;
import com.leads.model.enums.AutomationStatus;
import com.leads.model.enums.ExecutionStatus;
import com.leads.repository.*;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationServiceImpl implements AutomationService {

    private final TenantAccess access;
    private final AutomationRepository automationRepo;
    private final AutomationExecutionRepository executionRepo;
    private final AutomationExecutionStepRepository executionStepRepo;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<AutomationListDto> getAutomations(Long companyId, Long branchId, String status, Pageable pageable) {
        access.scope(companyId, branchId);
        AutomationStatus statusEnum = parseStatus(status);
        // Branch-level users only see their own branches' automations plus
        // company-wide ones (which also run on their branches).
        List<Long> permittedBranches = access.branchIds();

        Specification<Automation> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("company").get("id"), companyId));
            p.add(cb.isNull(root.get("deletedAt")));
            if (statusEnum != null) p.add(cb.equal(root.get("status"), statusEnum));
            if (branchId != null) p.add(cb.equal(root.get("branch").get("id"), branchId));
            if (permittedBranches != null) {
                var branch = root.join("branch", JoinType.LEFT);
                p.add(permittedBranches.isEmpty() ? cb.isNull(branch.get("id"))
                        : cb.or(cb.isNull(branch.get("id")), branch.get("id").in(permittedBranches)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return automationRepo.findAll(spec, pageable).map(this::mapToListDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationDto getAutomationById(Long id) {
        return mapToDto(readable(id));
    }

    @Override
    @Transactional
    public AutomationDto createAutomation(AutomationCreateRequest request, Long currentUserId) {
        Company company = access.company(request.getCompanyId());
        Branch branch = resolveBranch(company, request.getBranchId());

        User createdBy = currentUserId != null ? userRepo.findById(currentUserId).orElse(null) : null;

        Automation automation = Automation.builder()
                .company(company)
                .branch(branch)
                .name(request.getName().trim())
                .description(request.getDescription())
                // Always starts as a draft - going live is the publish endpoint's job.
                .status(AutomationStatus.DRAFT)
                .triggerType(request.getTriggerType())
                .triggerJson(request.getTriggerJson())
                .conditionsJson(request.getConditionsJson())
                .actionsJson(request.getActionsJson())
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .stopOnMatch(request.getStopOnMatch() != null ? request.getStopOnMatch() : true)
                .createdBy(createdBy)
                .triggerCount(0)
                .successCount(0)
                .failureCount(0)
                .build();

        return mapToDto(automationRepo.save(automation));
    }

    @Override
    @Transactional
    public AutomationDto updateAutomation(Long id, AutomationCreateRequest request) {
        Automation automation = writable(id);
        if (!automation.getCompany().getId().equals(request.getCompanyId())) {
            throw new IllegalArgumentException("An automation can't be moved to another company.");
        }

        automation.setBranch(resolveBranch(automation.getCompany(), request.getBranchId()));
        automation.setName(request.getName().trim());
        automation.setDescription(request.getDescription());
        automation.setTriggerType(request.getTriggerType());
        automation.setTriggerJson(request.getTriggerJson());
        automation.setConditionsJson(request.getConditionsJson());
        automation.setActionsJson(request.getActionsJson());
        if (request.getPriority() != null) automation.setPriority(request.getPriority());
        if (request.getStopOnMatch() != null) automation.setStopOnMatch(request.getStopOnMatch());

        // A live automation must stay runnable: re-validate edits to it the
        // same way publish does, rather than letting an edit slip in an
        // action the engine can't execute.
        if (automation.getStatus() == AutomationStatus.ACTIVE) requirePublishable(automation);

        return mapToDto(automationRepo.save(automation));
    }

    @Override
    @Transactional
    public AutomationDto publishAutomation(Long id) {
        Automation automation = writable(id);
        requirePublishable(automation);
        automation.setStatus(AutomationStatus.ACTIVE);
        return mapToDto(automationRepo.save(automation));
    }

    @Override
    @Transactional
    public AutomationDto pauseAutomation(Long id) {
        Automation automation = writable(id);
        automation.setStatus(AutomationStatus.PAUSED);
        return mapToDto(automationRepo.save(automation));
    }

    @Override
    @Transactional
    public AutomationDto duplicateAutomation(Long id, Long currentUserId) {
        Automation original = writable(id);

        User createdBy = currentUserId != null ? access.actor() : null; // the signed-in user (Phase 1 close)

        Automation copy = Automation.builder()
                .company(original.getCompany())
                .branch(original.getBranch())
                .name(truncate(original.getName() + " (Copy)", 200))
                .description(original.getDescription())
                .status(AutomationStatus.DRAFT)
                .triggerType(original.getTriggerType())
                .triggerJson(original.getTriggerJson())
                .conditionsJson(original.getConditionsJson())
                .actionsJson(original.getActionsJson())
                .priority(original.getPriority())
                .stopOnMatch(original.getStopOnMatch())
                .createdBy(createdBy)
                .triggerCount(0)
                .successCount(0)
                .failureCount(0)
                .build();

        return mapToDto(automationRepo.save(copy));
    }

    @Override
    @Transactional
    public void deleteAutomation(Long id) {
        Automation automation = writable(id);
        automation.setDeletedAt(LocalDateTime.now());
        automation.setStatus(AutomationStatus.PAUSED);
        automationRepo.save(automation);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AutomationExecutionDto> getExecutions(Long automationId, Pageable pageable) {
        readable(automationId);
        return executionRepo.findByAutomationIdOrderByStartedAtDesc(automationId, pageable)
                .map(this::mapToExecutionDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationExecutionDto getExecution(Long automationId, Long executionId) {
        readable(automationId);
        AutomationExecution execution = executionRepo.findByIdAndAutomationId(executionId, automationId)
                // Module 17 P1: an execution of another automation is a scope denial (403; 404 with errors.uniform_not_found).
                .orElseThrow(() -> new com.leads.exception.ScopeDeniedException("Execution not found for this automation."));
        return mapToExecutionDto(execution);
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationAnalyticsDto getAnalytics(Long automationId) {
        readable(automationId);

        long total = nz(executionRepo.countByAutomationId(automationId));
        long successful = nz(executionRepo.countByAutomationIdAndStatus(automationId, ExecutionStatus.COMPLETED));
        long failed = nz(executionRepo.countByAutomationIdAndStatus(automationId, ExecutionStatus.FAILED));
        long running = nz(executionRepo.countByAutomationIdAndStatus(automationId, ExecutionStatus.RUNNING));
        double rate = total > 0 ? ((double) successful / total) * 100.0 : 0.0;

        return AutomationAnalyticsDto.builder()
                .automationId(automationId)
                .totalExecutions(total)
                .successfulExecutions(successful)
                .failedExecutions(failed)
                .runningExecutions(running)
                .successRate(Math.round(rate * 100.0) / 100.0)
                .totalMessagesSent(executionStepRepo.countByExecutionAutomationIdAndNodeTypeAndStatus(automationId, "SEND_MESSAGE", ExecutionStatus.COMPLETED))
                .totalCustomersReached(executionRepo.countDistinctCustomers(automationId))
                .build();
    }

    // -- Scope helpers -------------------------------------------------

    /** Loads a live automation the caller may see: its company, and its branch when it has one. */
    private Automation readable(Long id) {
        Automation automation = automationRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("Automation not found."));
        access.company(automation.getCompany().getId());
        if (automation.getBranch() != null) access.branch(automation.getBranch().getId());
        return automation;
    }

    /**
     * Like {@link #readable} but for changes: a company-wide automation runs
     * on every branch, so only company-wide users may change it - a
     * branch-restricted user can't alter what other branches' customers get.
     */
    private Automation writable(Long id) {
        Automation automation = readable(id);
        if (automation.getBranch() == null) access.companyWide(automation.getCompany().getId());
        return automation;
    }

    private Branch resolveBranch(Company company, Long branchId) {
        if (branchId == null) {
            access.companyWide(company.getId());
            return null;
        }
        Branch branch = access.branch(branchId);
        access.sameCompany(company.getId(), branch.getCompany());
        return branch;
    }

    private void requirePublishable(Automation automation) {
        List<String> problems = AutomationDefinition.publishProblems(automation, objectMapper);
        if (problems.isEmpty()) {
            var actions = AutomationDefinition.parse(objectMapper, automation.getActionsJson(), true);
            for (Long userId : AutomationDefinition.assigneeIds(actions)) {
                User agent = userRepo.findById(userId).orElse(null);
                if (agent == null || !Boolean.TRUE.equals(agent.getIsActive()) || !access.canCompany(agent, automation.getCompany())) {
                    problems.add("The agent chosen in \"Assign to Agent\" isn't an active user of this company.");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("This automation can't go live yet: " + String.join(" ", problems));
        }
    }

    private static AutomationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return AutomationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown automation status: " + status);
        }
    }

    private static long nz(Long value) { return value != null ? value : 0L; }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AutomationDto mapToDto(Automation a) {
        return AutomationDto.builder()
                .id(a.getId())
                .version(a.getVersion())
                .companyId(a.getCompany() != null ? a.getCompany().getId() : null)
                .branchId(a.getBranch() != null ? a.getBranch().getId() : null)
                .branchName(a.getBranch() != null ? a.getBranch().getName() : null)
                .name(a.getName())
                .description(a.getDescription())
                .status(a.getStatus())
                .triggerType(a.getTriggerType())
                .triggerJson(a.getTriggerJson())
                .conditionsJson(a.getConditionsJson())
                .actionsJson(a.getActionsJson())
                .priority(a.getPriority())
                .stopOnMatch(a.getStopOnMatch())
                .triggerCount(a.getTriggerCount())
                .successCount(a.getSuccessCount())
                .failureCount(a.getFailureCount())
                .lastTriggeredAt(a.getLastTriggeredAt())
                .createdByUserId(a.getCreatedBy() != null ? a.getCreatedBy().getId() : null)
                .createdByName(a.getCreatedBy() != null ? a.getCreatedBy().getFirstName() + (a.getCreatedBy().getLastName() != null ? " " + a.getCreatedBy().getLastName() : "") : "System")
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private AutomationListDto mapToListDto(Automation a) {
        return AutomationListDto.builder()
                .id(a.getId())
                .companyId(a.getCompany() != null ? a.getCompany().getId() : null)
                .branchId(a.getBranch() != null ? a.getBranch().getId() : null)
                .branchName(a.getBranch() != null ? a.getBranch().getName() : null)
                .name(a.getName())
                .description(a.getDescription())
                .status(a.getStatus())
                .triggerType(a.getTriggerType())
                .triggerJson(a.getTriggerJson())
                .conditionsJson(a.getConditionsJson())
                .actionsJson(a.getActionsJson())
                .priority(a.getPriority())
                .stopOnMatch(a.getStopOnMatch())
                .triggerCount(a.getTriggerCount())
                .successCount(a.getSuccessCount())
                .failureCount(a.getFailureCount())
                .lastTriggeredAt(a.getLastTriggeredAt())
                .createdByName(a.getCreatedBy() != null ? a.getCreatedBy().getFirstName() + (a.getCreatedBy().getLastName() != null ? " " + a.getCreatedBy().getLastName() : "") : "System")
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private AutomationExecutionDto mapToExecutionDto(AutomationExecution e) {
        List<AutomationExecutionStepDto> stepDtos = null;
        if (e.getSteps() != null) {
            stepDtos = e.getSteps().stream().map(this::mapToStepDto).collect(Collectors.toList());
        }

        return AutomationExecutionDto.builder()
                .id(e.getId())
                .automationId(e.getAutomation() != null ? e.getAutomation().getId() : null)
                .automationName(e.getAutomation() != null ? e.getAutomation().getName() : null)
                .customerId(e.getCustomer() != null ? e.getCustomer().getId() : null)
                .customerPhone(e.getCustomerPhone())
                .customerName(e.getCustomerName())
                .status(e.getStatus())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .failureReason(e.getFailureReason())
                .steps(stepDtos)
                .build();
    }

    private AutomationExecutionStepDto mapToStepDto(AutomationExecutionStep s) {
        return AutomationExecutionStepDto.builder()
                .id(s.getId())
                .executionId(s.getExecution() != null ? s.getExecution().getId() : null)
                .nodeType(s.getNodeType())
                .nodeLabel(s.getNodeLabel())
                .status(s.getStatus())
                .executedAt(s.getExecutedAt())
                .detail(s.getDetail())
                .build();
    }
}
