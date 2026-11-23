package com.leads.dto.lead;

import com.leads.model.enums.ActivityType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadTimelineDto {
    private Long id;
    private Long leadId;
    private Long deliveryId;
    private ActivityType activityType;
    private String title;
    private String description;
    private String metaData;
    private String attachmentName;
    private String attachmentData;
    private Long performedByUserId;
    private String performedByName;
    private LocalDateTime createdAt;
}
