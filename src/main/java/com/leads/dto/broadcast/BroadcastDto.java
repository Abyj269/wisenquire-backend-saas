package com.leads.dto.broadcast;

import com.leads.model.enums.BroadcastAudienceType;
import com.leads.model.enums.BroadcastStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastDto {
    private Long id;
    private Long companyId;
    private Long branchId;
    private String branchName;
    private Long whatsappConfigurationId;
    private String whatsappPhoneNumber;
    private String name;
    private String templateName;
    private String templateLanguage;
    private String templateComponentsJson;
    private BroadcastAudienceType audienceType;
    private String audienceFilterJson;
    private BroadcastStatus status;
    @io.swagger.v3.oas.annotations.media.Schema(description = "Server wall-clock (Asia/Dubai) like every other timestamp; render it in the company timezone.")
    private LocalDateTime scheduledAt;
    private LocalDateTime sentAt;
    private LocalDateTime completedAt;
    private Integer totalRecipients;
    private Integer sentCount;
    private Integer deliveredCount;
    private Integer readCount;
    private Integer failedCount;
    private String createdByUserName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
