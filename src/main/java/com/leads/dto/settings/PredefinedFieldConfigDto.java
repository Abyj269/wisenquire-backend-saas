package com.leads.dto.settings;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredefinedFieldConfigDto {
    private String fieldKey;   // PredefinedLeadField enum name, e.g. "NATIONALITY"
    private String fieldLabel;
    private Boolean isVisible;
    private Boolean isMandatory;
    private Boolean isLocked;  // true for FIRST_NAME/PHONE/PURPOSE/SOURCE — frontend hides toggle controls when true
}
