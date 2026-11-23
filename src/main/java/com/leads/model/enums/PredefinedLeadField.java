package com.leads.model.enums;

/**
 * Closed set of native Lead fields that can be governed via
 * PredefinedFieldConfig (hide/show, mandatory/optional) — not admin-
 * extensible, unlike DynamicFieldDefinition. FIRST_NAME/PHONE/PURPOSE/SOURCE
 * are locked: always visible, always mandatory, no per-company toggle at all.
 * Enum names are the durable field_key strings persisted in
 * predefined_field_configs and referenced by the frontend — treat as an API
 * contract once shipped.
 */
public enum PredefinedLeadField {
    FIRST_NAME("Name", true),
    PHONE("Mobile", true),
    PURPOSE("Lead Purpose", true),
    SOURCE("Lead Source", true),
    LAST_NAME("Last Name", false),
    EMAIL("Email", false),
    COMPANY_NAME("Company Name", false),
    ASSIGNED_TO("Assigned To", false),
    CLOSING_DATE("Target Closing Date", false),
    NOTES("Notes", false),
    NATIONALITY("Nationality", false),
    PREFERRED_LANGUAGE("Preferred Language", false);

    private final String label;
    private final boolean locked;

    PredefinedLeadField(String label, boolean locked) {
        this.label = label;
        this.locked = locked;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLocked() {
        return locked;
    }
}
