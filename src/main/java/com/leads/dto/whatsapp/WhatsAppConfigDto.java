package com.leads.dto.whatsapp;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConfigDto {
    private Long id;
    private Long companyId;
    private Long branchId;
    private String branchName;
    private String branchCode;
    private String phoneNumberId;
    private String wabaId;
    /** Only needed for template Image/Video/Document headers and Carousel cards (Resumable Upload API). */
    private String appId;
    /** Masked (e.g. "EAAB...9f2a") — the real token is never sent back to the browser. */
    private String accessTokenMasked;
    private String verifyToken;
    private Boolean hasAppSecret;
    private String displayPhoneNumber;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
