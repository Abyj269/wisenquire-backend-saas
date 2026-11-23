package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.Automation;
import com.leads.model.enums.TriggerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the automation builder's JSON (trigger / conditions / actions) — the
 * single place that knows its field names, shared by publish-time validation
 * (AutomationServiceImpl) and the runtime engine (AutomationEngineService) so
 * the two can never disagree about what an automation means or whether the
 * engine can actually run it.
 */
final class AutomationDefinition {
    private AutomationDefinition() {}

    /** Action types the engine can execute today. Anything else is rejected at publish. */
    static final Set<String> SUPPORTED_ACTIONS = Set.of("SEND_MESSAGE", "WAIT", "BRANCH", "ASSIGN_CUSTOMER", "END_AUTOMATION");

    /** Condition fields the engine can evaluate from an inbound message today. */
    static final Set<String> SUPPORTED_CONDITION_FIELDS = Set.of(
            "message.body", "conversation.isNew", "interactive.button_title", "interactive.list_row_title",
            "interactive.button_id", "interactive.list_row_id", "business.hours");

    static final Set<String> OPERATORS = Set.of("CONTAINS", "EXACT_MATCH", "EXACT", "STARTS_WITH", "ENDS_WITH", "EQUALS", "NOT_EQUALS");

    /** Variables the engine can fill from the conversation/customer/company. */
    static final Set<String> SUPPORTED_VARIABLES = Set.of(
            "customer.name", "customer.firstName", "customer.phone", "customer.email", "business.name", "message.body");

    /**
     * Waits run inline on the automation executor thread — there's no
     * persisted resume/scheduler yet — so only short delays are allowed.
     */
    static final long MAX_INLINE_WAIT_SECONDS = 30;

    /** WhatsApp Cloud API's text body limit. */
    static final int MAX_TEXT_LENGTH = 4096;

    private static final ObjectMapper PLAIN = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    static JsonNode parse(ObjectMapper om, String json, boolean array) {
        if (json == null || json.isBlank()) return array ? om.createArrayNode() : om.createObjectNode();
        try {
            JsonNode node = om.readTree(json);
            if (node == null || node.isNull()) return array ? om.createArrayNode() : om.createObjectNode();
            if (array != node.isArray()) throw new IllegalArgumentException("Automation definition is malformed.");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Automation definition is not valid JSON.");
        }
    }

    /**
     * The builder saves a single "keyword" string (the input invites a
     * comma-separated list, e.g. "appointment, help, book"); older drafts may
     * carry a "keywords" array. Both are honoured.
     */
    static List<String> keywords(JsonNode trigger) {
        List<String> out = new ArrayList<>();
        String single = text(trigger, "keyword");
        if (single != null) for (String part : single.split(",")) if (!part.isBlank()) out.add(part.trim());
        JsonNode many = trigger.get("keywords");
        if (many != null && many.isArray()) for (JsonNode k : many) if (!k.asText("").isBlank()) out.add(k.asText().trim());
        return out;
    }

    static boolean textMatches(String actual, String operator, String expected, boolean caseSensitive) {
        String a = actual == null ? "" : actual.trim();
        String e = expected == null ? "" : expected.trim();
        if (!caseSensitive) {
            a = a.toLowerCase(Locale.ROOT);
            e = e.toLowerCase(Locale.ROOT);
        }
        return switch (operator == null ? "CONTAINS" : operator.toUpperCase(Locale.ROOT)) {
            case "EXACT_MATCH", "EXACT", "EQUALS" -> a.equals(e);
            case "NOT_EQUALS" -> !a.equals(e);
            case "STARTS_WITH" -> a.startsWith(e);
            case "ENDS_WITH" -> a.endsWith(e);
            case "CONTAINS" -> a.contains(e);
            default -> false;
        };
    }

    static long waitSeconds(JsonNode action) {
        long amount = action.path("waitDuration").asLong(action.path("duration").asLong(0));
        return switch (action.path("waitUnit").asText("SECONDS").toUpperCase(Locale.ROOT)) {
            case "MINUTES" -> amount * 60;
            case "HOURS" -> amount * 3600;
            case "DAYS" -> amount * 86400;
            default -> amount;
        };
    }

    static Long assigneeUserId(JsonNode action) {
        JsonNode id = action.hasNonNull("assignToUserId") ? action.get("assignToUserId") : action.get("assigneeUserId");
        return id != null && id.canConvertToLong() && id.asLong() > 0 ? id.asLong() : null;
    }

    static boolean isInteractive(JsonNode action) {
        String type = text(action, "interactiveType");
        return type != null && !"NONE".equalsIgnoreCase(type);
    }

    static String render(String template, Map<String, String> values) {
        Matcher m = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        m.appendTail(sb);
        return sb.toString();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Every reason this automation can't go live, in user-facing wording. Empty means publishable. */
    static List<String> publishProblems(Automation automation, ObjectMapper om) {
        List<String> problems = new ArrayList<>();
        JsonNode trigger, conditions, actions;
        try {
            trigger = parse(om, automation.getTriggerJson(), false);
            conditions = parse(om, automation.getConditionsJson(), true);
            actions = parse(om, automation.getActionsJson(), true);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return problems;
        }

        TriggerType type = automation.getTriggerType();
        if (type == TriggerType.SCHEDULED) problems.add("Scheduled triggers aren't supported yet.");
        if (type == TriggerType.KEYWORD) {
            if (keywords(trigger).isEmpty()) problems.add("A keyword trigger needs at least one keyword.");
            String op = text(trigger, "matchType");
            if (op != null && !OPERATORS.contains(op.toUpperCase(Locale.ROOT))) problems.add("Unknown keyword match type: " + op);
        }

        for (JsonNode c : conditions) checkCondition(c, "Condition", problems);

        if (actions.isEmpty()) problems.add("Add at least one action.");
        checkActions(actions, problems);
        return problems;
    }

    private static void checkCondition(JsonNode c, String where, List<String> problems) {
        String field = text(c, "field");
        String op = text(c, "operator");
        if (field == null || field.isBlank()) problems.add(where + " has no field selected.");
        else if (!SUPPORTED_CONDITION_FIELDS.contains(field)) problems.add(where + " on \"" + field + "\" isn't supported yet.");
        if (op == null || !OPERATORS.contains(op.toUpperCase(Locale.ROOT))) problems.add(where + " has an unknown operator.");
    }

    private static void checkActions(JsonNode actions, List<String> problems) {
        for (JsonNode a : actions) {
            String type = text(a, "type");
            String label = text(a, "label");
            if (label == null || label.isBlank()) label = type == null ? "An action" : type;
            if (type == null || !SUPPORTED_ACTIONS.contains(type)) {
                problems.add((type == null ? "An action" : type) + " actions aren't supported yet.");
                continue;
            }
            switch (type) {
                case "SEND_MESSAGE" -> {
                    String body = text(a, "messageText");
                    if (body == null || body.isBlank()) problems.add(label + ": message text is empty.");
                    else {
                        if (body.length() > MAX_TEXT_LENGTH) problems.add(label + ": message is longer than " + MAX_TEXT_LENGTH + " characters.");
                        Matcher m = VARIABLE.matcher(body);
                        while (m.find()) if (!SUPPORTED_VARIABLES.contains(m.group(1)))
                            problems.add(label + ": variable {{" + m.group(1) + "}} isn't available in automations yet.");
                    }
                    if (isInteractive(a)) {
                        String it = text(a, "interactiveType");
                        if ("QUICK_REPLY".equalsIgnoreCase(it)) {
                            JsonNode btns = a.get("quickReplyButtons");
                            if (btns == null || !btns.isArray() || btns.isEmpty()) {
                                problems.add(label + ": Quick Reply requires at least 1 button.");
                            } else if (btns.size() > 3) {
                                problems.add(label + ": Quick Reply allows a maximum of 3 buttons.");
                            } else {
                                for (int i = 0; i < btns.size(); i++) {
                                    String title = text(btns.get(i), "title");
                                    if (title == null || title.isBlank()) problems.add(label + ": Button #" + (i + 1) + " title cannot be empty.");
                                    else if (title.length() > 20) problems.add(label + ": Button #" + (i + 1) + " title cannot exceed 20 characters.");
                                }
                            }
                        } else if ("LIST".equalsIgnoreCase(it) || "LIST_MESSAGE".equalsIgnoreCase(it)) {
                            JsonNode rows = a.has("listRows") ? a.get("listRows") : null;
                            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                                problems.add(label + ": Interactive list requires at least 1 row.");
                            } else if (rows.size() > 10) {
                                problems.add(label + ": Interactive list allows a maximum of 10 rows.");
                            } else {
                                for (int i = 0; i < rows.size(); i++) {
                                    String title = text(rows.get(i), "title");
                                    if (title == null || title.isBlank()) problems.add(label + ": Row #" + (i + 1) + " title cannot be empty.");
                                    else if (title.length() > 24) problems.add(label + ": Row #" + (i + 1) + " title cannot exceed 24 characters.");
                                }
                            }
                        }
                    }
                }
                case "WAIT" -> {
                    long s = waitSeconds(a);
                    if (s <= 0) problems.add(label + ": set a wait duration.");
                    else if (s > MAX_INLINE_WAIT_SECONDS) problems.add(label + ": waits longer than " + MAX_INLINE_WAIT_SECONDS + " seconds aren't supported yet.");
                }
                case "ASSIGN_CUSTOMER" -> {
                    if (assigneeUserId(a) == null) problems.add(label + ": choose an agent to assign.");
                }
                case "BRANCH" -> {
                    String field = text(a, "branchConditionField");
                    if (field != null && !field.isBlank()) {
                        JsonNode cond = PLAIN.createObjectNode()
                                .put("field", field)
                                .put("operator", text(a, "branchConditionOperator"));
                        checkCondition(cond, label, problems);
                    }
                    JsonNode branches = a.get("branches");
                    if (branches != null && branches.isArray() && !branches.isEmpty()) {
                        for (int bIdx = 0; bIdx < branches.size(); bIdx++) {
                            JsonNode b = branches.get(bIdx);
                            String bName = text(b, "name");
                            String bVal = text(b, "value");
                            if (bVal == null || bVal.isBlank()) {
                                problems.add(label + ": Branch #" + (bIdx + 1) + " (" + (bName != null ? bName : "Unnamed") + ") has no match value set.");
                            }
                            JsonNode bActions = b.get("actions");
                            if (bActions != null && bActions.isArray()) checkActions(bActions, problems);
                        }
                    }
                    JsonNode def = a.get("defaultActions");
                    if (def != null && def.isArray()) checkActions(def, problems);

                    JsonNode yes = a.get("branchYesActions"), no = a.get("branchNoActions");
                    if (yes != null && yes.isArray()) checkActions(yes, problems);
                    if (no != null && no.isArray()) checkActions(no, problems);
                }
                default -> { }
            }
        }
    }

    /** Agent ids referenced anywhere in the action tree (including multi-branches and default actions). */
    static List<Long> assigneeIds(JsonNode actions) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode a : actions) {
            if ("ASSIGN_CUSTOMER".equals(text(a, "type")) && assigneeUserId(a) != null) ids.add(assigneeUserId(a));
            if ("BRANCH".equals(text(a, "type"))) {
                JsonNode branches = a.get("branches");
                if (branches != null && branches.isArray()) {
                    for (JsonNode b : branches) {
                        JsonNode bActions = b.get("actions");
                        if (bActions != null && bActions.isArray()) ids.addAll(assigneeIds(bActions));
                    }
                }
                JsonNode def = a.get("defaultActions");
                if (def != null && def.isArray()) ids.addAll(assigneeIds(def));
            }
            for (String path : List.of("branchYesActions", "branchNoActions")) {
                JsonNode nested = a.get(path);
                if (nested != null && nested.isArray()) ids.addAll(assigneeIds(nested));
            }
        }
        return ids;
    }
}
