package com.leads.dto.settings;

import com.leads.model.enums.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicFieldDto {
    private Long id;
    private Long companyId;
    private String module;

    @NotBlank(message = "Field name is required")
    private String fieldName;

    @NotBlank(message = "Field label is required")
    private String fieldLabel;

    @NotNull(message = "Field type is required")
    private FieldType fieldType;

    private Boolean isMandatory;
    private String defaultValue;
    private String placeholder;
    private String regexValidation;
    private String optionsJson; // JSON array of options: [{"label":"High","value":"HIGH"}]
    private Integer gridWidth;
    private Integer sortOrder;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
