package com.leads.dto.whatsapp;

import lombok.*;

/** Read shape — media is reported as presence + mime type only, never the (potentially multi-MB) blob itself. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppAutoReplySettingsDto {
    private Boolean workingHoursEnabled;
    private String workingHoursMessage;
    private Boolean workingHoursHasMedia;
    private String workingHoursMediaMimeType;

    private Boolean nonWorkingHoursEnabled;
    private String nonWorkingHoursMessage;
    private Boolean nonWorkingHoursHasMedia;
    private String nonWorkingHoursMediaMimeType;

    private Boolean holidayEnabled;
    private String holidayMessage;
    private Boolean holidayHasMedia;
    private String holidayMediaMimeType;
}
