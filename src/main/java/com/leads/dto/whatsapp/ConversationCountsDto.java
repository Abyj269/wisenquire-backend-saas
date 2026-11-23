package com.leads.dto.whatsapp;

import lombok.*;

/** Counts for the WhatsApp Inbox's filter pills — scoped to company/number, but never to the free-text search or which pill is currently active, so all four stay visible together. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationCountsDto {
    private long all;
    private long unread;
    private long activeLeads;
    private long assignedToMe;
}
