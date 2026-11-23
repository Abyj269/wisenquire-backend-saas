package com.leads.dto.broadcast;

import com.leads.model.enums.BroadcastAudienceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastCreateRequest {

    @NotNull
    private Long companyId;

    private Long branchId;

    @NotNull
    private Long whatsappConfigurationId;

    @NotBlank
    private String name;

    @NotBlank
    private String templateName;

    private String templateLanguage;

    private List<BroadcastComponentDto> components;

    @NotNull
    private BroadcastAudienceType audienceType;

    private String audienceFilterJson;

    private List<String> customNumbers;

    private LocalDateTime scheduledAt;
}
