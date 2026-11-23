package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.*;
import com.leads.model.enums.AutomationStatus;
import com.leads.model.enums.ExecutionStatus;
import com.leads.model.enums.MessageDirection;
import com.leads.repository.*;
import com.leads.util.PhoneNumberUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs ACTIVE automations against a newly stored inbound WhatsApp message.
 * <p>
 * Called by id after the inbound transaction has committed, on its own
 * executor, so it never sees uncommitted data or shares entities with the
 * webhook thread. Matching happens in one short read transaction; each
 * action then commits on its own (a sent WhatsApp message can't be rolled
 * back, so its record mustn't be either). The builder JSON is read through
 * {@link AutomationDefinition}, the same reader publish-time validation uses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationEngineService {

    private final AutomationRepository automationRepo;
    private final AutomationExecutionRepository executionRepo;
    private final AutomationExecutionStepRepository executionStepRepo;
    private final WhatsAppMessageRepository messageRepo;
    private final CustomerRepository customerRepo;
    private final WhatsAppScheduleService scheduleService;
    private final CompanyLeadSettingsRepository companyLeadSettingsRepo;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactions;

    // Lazy: WhatsAppCloudService also depends on this service.
    @Autowired
    @Lazy
    private WhatsAppCloudService whatsAppCloudService;

    /** Message types that are customer activity rather than something to respond to. */
    private static final Set<String> IGNORED_TYPES = Set.of("SYSTEM", "REACTION", "UNSUPPORTED");

    /** What the engine needs from the inbound message, captured once inside a transaction. */
    record Inbound(Long conversationId, Long companyId, String messageType, String body, String customerWaId,
                   String customerName, String customerEmail, Long customerId, String businessName,
                   boolean newConversation, boolean withinBusinessHours) {}

    record Matched(Long id, String name, String triggerType, String actionsJson, boolean stopOnMatch) {}

    @Async("automationExecutor")
    public void onIncomingWhatsAppMessage(Long messageId) {
        List<Matched> matched = new ArrayList<>();
        Inbound inbound;
        try {
            TransactionTemplate read = new TransactionTemplate(transactions);
            read.setReadOnly(true);
            inbound = read.execute(status -> collectMatches(messageId, matched));
        } catch (RuntimeException e) {
            log.error("Automation matching failed for message {}: {}", messageId, e.getMessage(), e);
            return;
        }
        if (inbound == null || matched.isEmpty()) return;

        // Retrieve Company conflict resolution strategy: FIRST_MATCH (default) or RUN_ALL
        String strategy = "FIRST_MATCH";
        try {
            var settings = companyLeadSettingsRepo.findByCompanyId(inbound.companyId()).orElse(null);
            if (settings != null && settings.getAutomationConflictStrategy() != null) {
                strategy = settings.getAutomationConflictStrategy();
            }
        } catch (Exception ex) {
            log.warn("Could not determine automation conflict strategy for company {}: {}", inbound.companyId(), ex.getMessage());
        }

        boolean isFirstMatchOnly = !"RUN_ALL".equalsIgnoreCase(strategy);

        for (int i = 0; i < matched.size(); i++) {
            Matched automation = matched.get(i);
            try {
                execute(automation, inbound);
            } catch (RuntimeException e) {
                log.error("Automation {} failed for conversation {}: {}", automation.id(), inbound.conversationId(), e.getMessage(), e);
            }

            // Conflict resolution: Stop subsequent matching automations if policy is FIRST_MATCH or if this automation has stopOnMatch enabled
            if (isFirstMatchOnly || automation.stopOnMatch()) {
                if (i < matched.size() - 1) {
                    log.info("Automation '{}' (ID {}) executed. Skipping {} subsequent matching automation(s) due to conflict resolution policy (strategy={}, stopOnMatch={})",
                            automation.name(), automation.id(), matched.size() - 1 - i, strategy, automation.stopOnMatch());
                }
                break;
            }
        }
    }

    // -- Matching -------------------------------------------------------

    private Inbound collectMatches(Long messageId, List<Matched> out) {
        WhatsAppMessage message = messageRepo.findById(messageId).orElse(null);
        if (message == null || message.getDirection() != MessageDirection.INBOUND
                || IGNORED_TYPES.contains(String.valueOf(message.getMessageType()).toUpperCase(Locale.ROOT))) return null;

        WhatsAppConversation conversation = message.getConversation();
        Company company = conversation.getCompany();
        Long branchId = conversation.getBranch() != null ? conversation.getBranch().getId() : null;

        List<Automation> candidates = automationRepo
                .findByCompanyIdAndStatusAndDeletedAtIsNull(company.getId(), AutomationStatus.ACTIVE).stream()
                // A branch automation only runs on that branch's conversations.
                .filter(a -> a.getBranch() == null || a.getBranch().getId().equals(branchId))
                .sorted(automationPriorityComparator())
                .toList();
        if (candidates.isEmpty()) return null;

        Customer customer = customerRepo.findByCompanyIdAndMobileNormalized(company.getId(),
                PhoneNumberUtil.lastSignificantDigits(conversation.getCustomerWaId())).orElse(null);
        if (customer != null && Boolean.TRUE.equals(customer.getIsBlocked())) return null;

        Inbound inbound = new Inbound(
                conversation.getId(),
                company.getId(),
                String.valueOf(message.getMessageType()).toUpperCase(Locale.ROOT),
                message.getBodyText() != null ? message.getBodyText().trim() : "",
                conversation.getCustomerWaId(),
                customer != null && customer.getName() != null ? customer.getName() : conversation.getCustomerName(),
                customer != null ? customer.getEmail() : null,
                customer != null ? customer.getId() : null,
                company.getName(),
                messageRepo.countByConversationIdAndDirection(conversation.getId(), MessageDirection.INBOUND) == 1,
                conversation.getBranch() == null || scheduleService.isWithinWorkingHours(conversation.getBranch()));

        for (Automation a : candidates) {
            try {
                boolean stop = !Boolean.FALSE.equals(a.getStopOnMatch());
                if (matchesTrigger(a, inbound) && passesConditions(a, inbound)) {
                    out.add(new Matched(a.getId(), a.getName(), String.valueOf(a.getTriggerType()), a.getActionsJson(), stop));
                } else if (Set.of("INTERACTIVE", "BUTTON").contains(inbound.messageType()) && hasInteractiveBranchFor(a, inbound)) {
                    out.add(new Matched(a.getId(), a.getName(), String.valueOf(a.getTriggerType()), a.getActionsJson(), stop));
                }
            } catch (RuntimeException e) {
                log.warn("Skipping automation {}: {}", a.getId(), e.getMessage());
            }
        }
        return inbound;
    }

    private java.util.Comparator<Automation> automationPriorityComparator() {
        return (a1, a2) -> {
            // 1. Explicit Priority: lower number = higher execution priority (1, 2, ... default 100)
            int p1 = a1.getPriority() != null ? a1.getPriority() : 100;
            int p2 = a2.getPriority() != null ? a2.getPriority() : 100;
            if (p1 != p2) return Integer.compare(p1, p2);

            // 2. Branch Specificity: Branch-scoped automation beats company-wide fallback
            boolean a1Branch = a1.getBranch() != null;
            boolean a2Branch = a2.getBranch() != null;
            if (a1Branch != a2Branch) return a1Branch ? -1 : 1;

            // 3. Trigger Specificity: KEYWORD (specific) beats generic INCOMING_MESSAGE
            int t1 = triggerSpecificityScore(a1);
            int t2 = triggerSpecificityScore(a2);
            if (t1 != t2) return Integer.compare(t2, t1);

            // 4. Creation Order (earlier/more established first)
            return Long.compare(a1.getId() != null ? a1.getId() : 0L, a2.getId() != null ? a2.getId() : 0L);
        };
    }

    private int triggerSpecificityScore(Automation a) {
        if (a.getTriggerType() == null) return 0;
        return switch (a.getTriggerType()) {
            case KEYWORD -> 10;
            case NEW_CONVERSATION -> 5;
            case INCOMING_MESSAGE -> 1;
            default -> 0;
        };
    }

    private boolean hasInteractiveBranchFor(Automation a, Inbound in) {
        JsonNode actions = AutomationDefinition.parse(objectMapper, a.getActionsJson(), true);
        return checkInteractiveBranchMatches(actions, in);
    }

    private boolean checkInteractiveBranchMatches(JsonNode actions, Inbound in) {
        if (actions == null || !actions.isArray()) return false;
        for (JsonNode action : actions) {
            String type = action.path("type").asText("");
            if ("BRANCH".equals(type)) {
                String parentField = AutomationDefinition.text(action, "branchConditionField");
                String parentOp = AutomationDefinition.text(action, "branchConditionOperator");

                JsonNode branches = action.get("branches");
                if (branches != null && branches.isArray() && !branches.isEmpty()) {
                    for (JsonNode b : branches) {
                        String f = AutomationDefinition.text(b, "field");
                        if (f == null || f.isBlank()) f = parentField;
                        String op = AutomationDefinition.text(b, "operator");
                        if (op == null || op.isBlank()) op = parentOp;
                        String val = AutomationDefinition.text(b, "value");

                        if (f != null && (f.startsWith("interactive.") || "message.body".equals(f))) {
                            if (evaluate(f, op, val, in)) return true;
                        }
                        if (checkInteractiveBranchMatches(b.get("actions"), in)) return true;
                    }
                    if (checkInteractiveBranchMatches(action.get("defaultActions"), in)) return true;
                } else {
                    if (parentField != null && (parentField.startsWith("interactive.") || "message.body".equals(parentField))) {
                        if (evaluate(parentField, parentOp, AutomationDefinition.text(action, "branchConditionValue"), in)) {
                            return true;
                        }
                    }
                    if (checkInteractiveBranchMatches(action.get("branchYesActions"), in)) return true;
                    if (checkInteractiveBranchMatches(action.get("branchNoActions"), in)) return true;
                }
            }
        }
        return false;
    }

    private boolean hasInteractiveBranch(JsonNode actions) {
        if (actions == null || !actions.isArray()) return false;
        for (JsonNode action : actions) {
            String type = action.path("type").asText("");
            if ("BRANCH".equals(type)) {
                String field = AutomationDefinition.text(action, "branchConditionField");
                if (field != null && (field.startsWith("interactive.") || "message.body".equals(field))) return true;
                JsonNode branches = action.get("branches");
                if (branches != null && branches.isArray() && !branches.isEmpty()) {
                    for (JsonNode b : branches) {
                        String bField = AutomationDefinition.text(b, "field");
                        if (bField != null && (bField.startsWith("interactive.") || "message.body".equals(bField))) return true;
                        if (hasInteractiveBranch(b.get("actions"))) return true;
                    }
                    if (hasInteractiveBranch(action.get("defaultActions"))) return true;
                }
                if (hasInteractiveBranch(action.get("branchYesActions"))) return true;
                if (hasInteractiveBranch(action.get("branchNoActions"))) return true;
            }
        }
        return false;
    }

    private boolean matchesTrigger(Automation automation, Inbound in) {
        return switch (automation.getTriggerType()) {
            case INCOMING_MESSAGE -> {
                // If the inbound message is an interactive button or list reply, avoid triggering
                // a generic INCOMING_MESSAGE automation that has no conditions targeting it.
                // Otherwise, clicking any button re-triggers the parent menu in an infinite loop.
                if (Set.of("INTERACTIVE", "BUTTON").contains(in.messageType())) {
                    yield hasInteractiveOrBodyCondition(automation);
                }
                yield true;
            }
            case NEW_CONVERSATION -> in.newConversation();
            case KEYWORD -> {
                JsonNode trigger = AutomationDefinition.parse(objectMapper, automation.getTriggerJson(), false);
                String matchType = AutomationDefinition.text(trigger, "matchType");
                boolean caseSensitive = trigger.path("caseSensitive").asBoolean(false);
                yield AutomationDefinition.keywords(trigger).stream()
                        .anyMatch(k -> AutomationDefinition.textMatches(in.body(), matchType, k, caseSensitive));
            }
            // No scheduler exists yet; publishing a SCHEDULED automation is refused.
            case SCHEDULED -> false;
        };
    }

    private boolean hasInteractiveOrBodyCondition(Automation automation) {
        JsonNode conditions = AutomationDefinition.parse(objectMapper, automation.getConditionsJson(), true);
        if (conditions == null || !conditions.isArray() || conditions.isEmpty()) {
            return false;
        }
        for (JsonNode c : conditions) {
            String field = AutomationDefinition.text(c, "field");
            if ("interactive.button_title".equals(field)
                    || "interactive.button_id".equals(field)
                    || "interactive.list_row_title".equals(field)
                    || "interactive.list_row_id".equals(field)
                    || "message.body".equals(field)) {
                return true;
            }
        }
        return false;
    }

    /** Conditions combine left to right, each joined to the next by its own logicGate (AND by default). */
    private boolean passesConditions(Automation automation, Inbound in) {
        JsonNode conditions = AutomationDefinition.parse(objectMapper, automation.getConditionsJson(), true);
        Boolean result = null;
        String gate = "AND";
        for (JsonNode c : conditions) {
            boolean value = evaluate(AutomationDefinition.text(c, "field"), AutomationDefinition.text(c, "operator"),
                    AutomationDefinition.text(c, "value"), in);
            result = result == null ? value : ("OR".equalsIgnoreCase(gate) ? result || value : result && value);
            gate = c.path("logicGate").asText("AND");
        }
        return result == null || result;
    }

    /** Unknown fields evaluate to false — fail closed rather than message the wrong customers. */
    private boolean evaluate(String field, String operator, String expected, Inbound in) {
        if (field == null) return false;
        return switch (field) {
            case "message.body" -> AutomationDefinition.textMatches(in.body(), operator, expected, false);
            case "interactive.button_title", "interactive.list_row_title",
                 "interactive.button_id", "interactive.list_row_id" ->
                    Set.of("INTERACTIVE", "BUTTON").contains(in.messageType())
                            && AutomationDefinition.textMatches(in.body(), operator, expected, false);
            case "conversation.isNew" -> booleanMatches(in.newConversation(), operator, expected);
            case "business.hours" -> booleanMatches(in.withinBusinessHours(), operator, expected);
            default -> false;
        };
    }

    private static final Set<String> TRUTHY = Set.of("true", "yes", "1", "open", "new", "within");

    private static boolean booleanMatches(boolean actual, String operator, String expected) {
        boolean wanted = expected == null || TRUTHY.contains(expected.trim().toLowerCase(Locale.ROOT));
        return "NOT_EQUALS".equalsIgnoreCase(operator) ? actual != wanted : actual == wanted;
    }

    // -- Execution ------------------------------------------------------

    /** Mutable state for one run through an automation's action tree. */
    private static final class Run {
        final Long executionId;
        boolean ended;
        String failure;
        Run(Long executionId) { this.executionId = executionId; }
    }

    private void execute(Matched automation, Inbound in) {
        log.info("Running automation {} ('{}') for conversation {}", automation.id(), automation.name(), in.conversationId());
        TransactionTemplate tx = new TransactionTemplate(transactions);

        Long executionId = tx.execute(status -> executionRepo.save(AutomationExecution.builder()
                .automation(automationRepo.getReferenceById(automation.id()))
                .customer(in.customerId() != null ? customerRepo.getReferenceById(in.customerId()) : null)
                .customerPhone(truncate(in.customerWaId(), 30))
                .customerName(truncate(in.customerName(), 200))
                .status(ExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build()).getId());
        Run run = new Run(executionId);

        try {
            step(run, "TRIGGER", "Trigger: " + automation.triggerType(), ExecutionStatus.COMPLETED,
                    "Matched inbound message: " + truncate(in.body(), 500));
            runActions(AutomationDefinition.parse(objectMapper, automation.actionsJson(), true), in, run);
        } catch (RuntimeException e) {
            run.failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            boolean success = run.failure == null;
            tx.executeWithoutResult(status -> executionRepo.findById(executionId).ifPresent(e -> {
                e.setStatus(success ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED);
                e.setCompletedAt(LocalDateTime.now());
                e.setFailureReason(run.failure);
                executionRepo.save(e);
            }));
            automationRepo.recordRun(automation.id(), success ? 1 : 0, success ? 0 : 1, LocalDateTime.now());
        }
    }

    private void runActions(JsonNode actions, Inbound in, Run run) {
        boolean isInboundInteractive = Set.of("INTERACTIVE", "BUTTON").contains(in.messageType());
        for (JsonNode action : actions) {
            if (run.ended || run.failure != null) return;
            String type = action.path("type").asText("");
            String label = action.hasNonNull("label") && !action.get("label").asText().isBlank()
                    ? action.get("label").asText() : type;

            if ("SEND_MESSAGE".equals(type) && AutomationDefinition.isInteractive(action)) {
                if (isInboundInteractive) {
                    // Customer already received and clicked one of these buttons. Skip re-sending the prompt.
                    continue;
                }
            }

            if ("BRANCH".equals(type)) {
                JsonNode branches = action.get("branches");
                if (branches != null && branches.isArray() && !branches.isEmpty()) {
                    String parentField = AutomationDefinition.text(action, "branchConditionField");
                    String parentOp = AutomationDefinition.text(action, "branchConditionOperator");
                    boolean matched = false;
                    for (int bIdx = 0; bIdx < branches.size(); bIdx++) {
                        JsonNode b = branches.get(bIdx);
                        String bName = AutomationDefinition.text(b, "name");
                        if (bName == null || bName.isBlank()) bName = "Branch #" + (bIdx + 1);
                        String f = AutomationDefinition.text(b, "field");
                        if (f == null || f.isBlank()) f = parentField;
                        String op = AutomationDefinition.text(b, "operator");
                        if (op == null || op.isBlank()) op = parentOp;
                        String val = AutomationDefinition.text(b, "value");

                        if (evaluate(f, op, val, in)) {
                            matched = true;
                            step(run, type, label, ExecutionStatus.COMPLETED, "Matched branch \"" + bName + "\" (" + f + " " + op + " \"" + val + "\")");
                            JsonNode path = b.get("actions");
                            if (path != null && path.isArray()) runActions(path, in, run);
                            break;
                        }
                    }
                    if (!matched) {
                        JsonNode def = action.get("defaultActions");
                        if (def != null && def.isArray() && !def.isEmpty()) {
                            step(run, type, label, ExecutionStatus.COMPLETED, "No branch matched - took Default path");
                            runActions(def, in, run);
                        } else {
                            step(run, type, label, ExecutionStatus.COMPLETED, "No branch matched - no default path configured");
                        }
                    }
                } else {
                    boolean yes = evaluate(AutomationDefinition.text(action, "branchConditionField"),
                            AutomationDefinition.text(action, "branchConditionOperator"),
                            AutomationDefinition.text(action, "branchConditionValue"), in);
                    step(run, type, label, ExecutionStatus.COMPLETED, "Condition was " + (yes ? "met - took YES path" : "not met - took NO path"));
                    JsonNode path = action.get(yes ? "branchYesActions" : "branchNoActions");
                    if (path != null && path.isArray()) runActions(path, in, run);
                }
                continue;
            }

            try {
                String detail = runAction(type, action, in, run);
                step(run, type, label, ExecutionStatus.COMPLETED, detail);
                // When sending interactive buttons for the first time, pause execution if follow-up branches exist
                if ("SEND_MESSAGE".equals(type) && AutomationDefinition.isInteractive(action) && !isInboundInteractive && hasInteractiveBranch(actions)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                run.failure = "Interrupted during wait";
                step(run, type, label, ExecutionStatus.FAILED, run.failure);
            } catch (Exception e) {
                run.failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Automation action {} failed (execution {}): {}", type, run.executionId, run.failure);
                step(run, type, label, ExecutionStatus.FAILED, "Failed: " + run.failure);
            }
        }
    }

    /** Performs one action; returns the step detail, or throws with the user-facing reason it failed. */
    private String runAction(String type, JsonNode action, Inbound in, Run run) throws InterruptedException {
        switch (type) {
            case "SEND_MESSAGE" -> {
                String text = AutomationDefinition.render(action.path("messageText").asText(""), variables(in)).trim();
                if (text.isEmpty()) throw new IllegalStateException("Message text is empty.");
                if (AutomationDefinition.isInteractive(action)) {
                    String interactiveType = AutomationDefinition.text(action, "interactiveType");
                    whatsAppCloudService.sendAutomationInteractive(in.conversationId(), text, interactiveType, action);
                    return "Sent interactive " + interactiveType + ": " + truncate(text, 500);
                } else {
                    whatsAppCloudService.sendAutomationText(in.conversationId(), text);
                    return "Sent: " + truncate(text, 500);
                }
            }
            case "WAIT" -> {
                long seconds = AutomationDefinition.waitSeconds(action);
                if (seconds > AutomationDefinition.MAX_INLINE_WAIT_SECONDS) {
                    throw new IllegalStateException("Waits longer than " + AutomationDefinition.MAX_INLINE_WAIT_SECONDS + " seconds aren't supported yet.");
                }
                if (seconds > 0) Thread.sleep(seconds * 1000L);
                return "Waited " + seconds + " second(s)";
            }
            case "ASSIGN_CUSTOMER" -> {
                Long userId = AutomationDefinition.assigneeUserId(action);
                if (userId == null) throw new IllegalStateException("No agent selected.");
                whatsAppCloudService.assignFromAutomation(in.conversationId(), userId);
                String name = AutomationDefinition.text(action, "assignToUserName");
                return "Assigned to " + (name != null ? name : "user #" + userId);
            }
            case "END_AUTOMATION" -> {
                run.ended = true;
                return "Workflow ended";
            }
            default -> throw new IllegalStateException((type.isBlank() ? "This" : type) + " action isn't supported yet.");
        }
    }

    private Map<String, String> variables(Inbound in) {
        Map<String, String> v = new HashMap<>();
        String name = in.customerName() != null ? in.customerName().trim() : "";
        v.put("customer.name", name);
        v.put("customer.firstName", name.isEmpty() ? "" : name.split("\\s+")[0]);
        v.put("customer.phone", in.customerWaId() != null ? "+" + in.customerWaId() : "");
        v.put("customer.email", in.customerEmail() != null ? in.customerEmail() : "");
        v.put("business.name", in.businessName() != null ? in.businessName() : "");
        v.put("message.body", in.body());
        return v;
    }

    private void step(Run run, String nodeType, String label, ExecutionStatus status, String detail) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> executionStepRepo.save(AutomationExecutionStep.builder()
                .execution(executionRepo.getReferenceById(run.executionId))
                .nodeType(truncate(nodeType, 50))
                .nodeLabel(truncate(label, 200))
                .status(status)
                .executedAt(LocalDateTime.now())
                .detail(detail)
                .build()));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
