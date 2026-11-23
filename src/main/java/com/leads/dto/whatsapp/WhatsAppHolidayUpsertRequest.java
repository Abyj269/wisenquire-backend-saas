package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppHolidayUpsertRequest {
    /** Null when creating; set to update an existing holiday. */
    private Long id;

    @NotNull(message = "Company is required")
    private Long companyId;

    @NotBlank(message = "Holiday name is required")
    private String name;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    @NotEmpty(message = "Choose at least one branch")
    private List<Long> branchIds;

    /** Optional — overrides the company's common holiday message for these dates when set. */
    private String messageText;

    /** Base64 data URI — left null on update to keep the existing media unchanged. */
    private String mediaData;
    private String mediaMimeType;

    /** Set true to clear previously saved media without uploading a replacement. */
    private Boolean removeMedia;
}
