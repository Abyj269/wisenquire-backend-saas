package com.leads.dto.whatsapp;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTestConnectionResult {
    private boolean success;
    private String message;
    private String verifiedName;
    private String displayPhoneNumber;
    private String qualityRating;
}
