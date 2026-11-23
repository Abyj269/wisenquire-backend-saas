package com.leads.dto.broadcast;

import com.leads.model.enums.BroadcastRecipientStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastRecipientDto {
    private Long id;
    private Long broadcastId;
    private Long customerId;
    private Long leadId;
    private String recipientPhone;
    private String recipientName;
    private String wamid;
    private BroadcastRecipientStatus status;
    private String errorMessage;
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private LocalDateTime failedAt;
    private LocalDateTime createdAt;
}
