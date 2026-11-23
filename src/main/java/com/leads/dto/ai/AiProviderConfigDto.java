package com.leads.dto.ai;

import com.leads.model.enums.AiProviderType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderConfigDto {
    private Long id;
    private Long companyId;
    private Long branchId;
    private String branchName;
    private AiProviderType provider;
    private String modelName;
    /** Masked (e.g. "sk-p...9f2a") — the real key is never sent back to the browser. */
    private String apiKeyMasked;
    private String apiBaseUrlOverride;
    private String providerAccountRef;
    private Boolean isActive;
    private Boolean isSelected;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
