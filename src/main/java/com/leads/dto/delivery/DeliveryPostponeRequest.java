package com.leads.dto.delivery;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryPostponeRequest {
    @NotNull(message = "A new date is required")
    private LocalDate requestedNewDate;

    private String requestNotes;
}
