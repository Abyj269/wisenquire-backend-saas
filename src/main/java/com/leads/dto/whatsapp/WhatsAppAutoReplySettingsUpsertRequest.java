package com.leads.dto.whatsapp;

import lombok.*;

/** Write shape — each section's mediaData is left null to keep existing media unchanged, or set to replace it; removeMedia clears it without a replacement. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppAutoReplySettingsUpsertRequest {
    private Boolean workingHoursEnabled;
    private String workingHoursMessage;
    private String workingHoursMediaData;
    private String workingHoursMediaMimeType;
    private Boolean workingHoursRemoveMedia;

    private Boolean nonWorkingHoursEnabled;
    private String nonWorkingHoursMessage;
    private String nonWorkingHoursMediaData;
    private String nonWorkingHoursMediaMimeType;
    private Boolean nonWorkingHoursRemoveMedia;

    private Boolean holidayEnabled;
    private String holidayMessage;
    private String holidayMediaData;
    private String holidayMediaMimeType;
    private Boolean holidayRemoveMedia;
}
