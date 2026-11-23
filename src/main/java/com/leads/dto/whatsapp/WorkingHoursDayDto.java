package com.leads.dto.whatsapp;

import lombok.*;

import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkingHoursDayDto {
    /** 1=Monday .. 7=Sunday. */
    private Integer dayOfWeek;
    private Boolean isOpen;
    private LocalTime openTime;
    private LocalTime closeTime;
}
