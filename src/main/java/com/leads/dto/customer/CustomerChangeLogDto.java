package com.leads.dto.customer;

import com.leads.model.enums.ActivityType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerChangeLogDto {
    private Long id;
    private Long customerId;
    private ActivityType activityType;
    private String title;
    private String description;
    private String attachmentName;
    private String attachmentData;
    private String performedByName;
    private LocalDateTime createdAt;
}
