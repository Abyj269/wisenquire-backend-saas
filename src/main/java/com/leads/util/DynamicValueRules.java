package com.leads.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.model.entity.DynamicFieldDefinition;
import com.leads.model.enums.FieldType;
import java.util.*;

public final class DynamicValueRules {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DynamicValueRules() {}

    public static com.google.re2j.Pattern pattern(String regex) {
        if (regex == null || regex.isBlank()) return null;
        if (regex.length() > 255) throw new IllegalArgumentException("Validation pattern is too long.");
        try { return com.google.re2j.Pattern.compile(regex); }
        catch (com.google.re2j.PatternSyntaxException e) { throw new IllegalArgumentException("Invalid validation pattern; backreferences and lookarounds are unsupported."); }
    }

    public static Set<String> options(String json) {
        try {
            if (json == null || json.length() > 20000) throw new IllegalArgumentException();
            var array = JSON.readTree(json);
            if (!array.isArray() || array.isEmpty() || array.size() > 100) throw new IllegalArgumentException();
            Set<String> values = new HashSet<>();
            for (var option : array) {
                if (!option.path("label").isTextual() || !option.path("value").isTextual()
                        || option.path("label").asText().isBlank() || option.path("value").asText().isBlank()
                        || option.path("value").asText().length() > 255 || !values.add(option.path("value").asText())) throw new IllegalArgumentException();
            }
            return values;
        } catch (Exception e) { throw new IllegalArgumentException("Options require 1 to 100 unique text values and labels."); }
    }

    public static boolean choice(FieldType type) {
        return type == FieldType.SELECT || type == FieldType.RADIO || type == FieldType.MULTI_SELECT || type == FieldType.CHECKBOX;
    }

    public static void value(DynamicFieldDefinition def, Object value) {
        boolean empty = value == null || value instanceof String s && s.isBlank() || value instanceof Collection<?> c && c.isEmpty();
        if (empty) {
            if (Boolean.TRUE.equals(def.getIsMandatory())) throw new IllegalArgumentException("Mandatory field '" + def.getFieldLabel() + "' is required.");
            return;
        }
        if (def.getFieldType() == null) throw new IllegalArgumentException("Field type is missing.");
        try {
            if (def.getFieldType() == FieldType.MULTI_SELECT || def.getFieldType() == FieldType.CHECKBOX) {
                if (!(value instanceof List<?> list) || list.size() > 100 || new HashSet<>(list).size() != list.size()
                        || !options(def.getOptionsJson()).containsAll(list)) throw new IllegalArgumentException();
                return;
            }
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException();
            String text = value.toString();
            if (text.length() > 4000) throw new IllegalArgumentException();
            switch (def.getFieldType()) {
                case NUMBER, DECIMAL -> {
                    var number = new java.math.BigDecimal(text);
                    if (number.precision() > 18 || number.scale() < -18 || number.scale() > 6) throw new IllegalArgumentException();
                    if (def.getFieldType() == FieldType.NUMBER) number.toBigIntegerExact();
                }
                case DATE -> java.time.LocalDate.parse(text);
                case TIME -> java.time.LocalTime.parse(text);
                case DATETIME -> java.time.LocalDateTime.parse(text);
                case SWITCH -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException(); }
                case SELECT, RADIO -> { if (!options(def.getOptionsJson()).contains(text)) throw new IllegalArgumentException(); }
                case EMAIL -> { if (!text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException(); }
                case PHONE -> PhoneNumberUtil.canonical(text);
                case URL -> {
                    var uri = java.net.URI.create(text);
                    if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
                }
                case FILE -> throw new IllegalArgumentException("File fields require a managed upload workflow.");
                default -> { if (!(value instanceof String)) throw new IllegalArgumentException(); }
            }
            var pattern = pattern(def.getRegexValidation());
            if (pattern != null && !pattern.matches(text)) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("Field '" + def.getFieldLabel() + "' has an invalid value or format."); }
    }
}
