package com.leads.dto.ai;

import com.leads.model.enums.AiProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderConfigUpsertRequest {
    /** Null when connecting a new provider for the branch; set to update an existing one by its own id. */
    private Long id;

    @NotNull(message = "Branch is required")
    private Long branchId;

    @NotNull(message = "Provider is required")
    private AiProviderType provider;

    @NotBlank(message = "Model name is required")
    private String modelName;

    /** Left blank on update to keep the previously saved key unchanged. */
    private String apiKey;

    private String apiBaseUrlOverride;
    private String providerAccountRef;
    private Boolean isActive;
}
