package com.leads.util;

import com.leads.model.entity.DynamicFieldDefinition;
import com.leads.model.enums.FieldType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamicValueRulesTest {
    private DynamicFieldDefinition field(FieldType type) {
        return DynamicFieldDefinition.builder().fieldLabel("Test").fieldType(type).isMandatory(true).build();
    }
    @Test void mandatoryCannotBeOmittedOrEmptyCollection() {
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(field(FieldType.TEXT), null));
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(field(FieldType.MULTI_SELECT), List.of()));
    }
    @Test void rejectsWrongScalarTypesAndUnboundedExponents() {
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(field(FieldType.NUMBER), "1.2"));
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(field(FieldType.NUMBER), "1e999999999"));
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(field(FieldType.SWITCH), "true"));
        assertDoesNotThrow(() -> DynamicValueRules.value(field(FieldType.SWITCH), false));
    }
    @Test void rejectsUnsupportedPatternsAndInvalidChoices() {
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.pattern("(a)\\1"));
        var def = field(FieldType.SELECT);
        def.setOptionsJson("[{\"label\":\"High\",\"value\":\"HIGH\"}]");
        assertThrows(IllegalArgumentException.class, () -> DynamicValueRules.value(def, "LOW"));
        assertDoesNotThrow(() -> DynamicValueRules.value(def, "HIGH"));
    }
}
