package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

/** Saves the same 7-day schedule to every branch listed — this is the "which branches this applies to" behavior for working hours. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkingHoursUpsertRequest {
    @NotEmpty(message = "Choose at least one branch")
    private List<Long> branchIds;

    @NotEmpty(message = "Provide a schedule for each day")
    private List<WorkingHoursDayDto> days;
}
