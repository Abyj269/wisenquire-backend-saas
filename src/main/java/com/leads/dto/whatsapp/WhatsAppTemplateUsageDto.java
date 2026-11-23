package com.leads.dto.whatsapp;

import lombok.*;

/** Per-template send counts for a date range — local counts (from our own outbound messages), not from Meta. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplateUsageDto {
    private String templateName;
    private long sent;
    private long delivered;
    private long read;
    private long failed;
    private long accepted;
    private long unknown;
    private long total;
}
