package com.leads.dto.whatsapp;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppHolidayDto {
    private Long id;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String messageText;
    private Boolean hasMedia;
    private String mediaMimeType;
    private List<Long> branchIds;
}
