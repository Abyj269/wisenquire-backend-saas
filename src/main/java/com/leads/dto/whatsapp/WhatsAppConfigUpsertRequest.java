package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConfigUpsertRequest {
    /** Null when connecting a new number; set to update an existing connection by its own id (a branch can hold several numbers, so branchId alone can't disambiguate which one is being edited). */
    private Long id;

    @NotNull(message = "Branch is required")
    private Long branchId;

    @NotBlank(message = "Phone Number ID is required")
    private String phoneNumberId;

    @NotBlank(message = "WhatsApp Business Account ID is required")
    private String wabaId;

    /** Optional — only required for template Image/Video/Document headers and Carousel cards (Resumable Upload API). */
    private String appId;

    /** Left blank on update to keep the previously saved token unchanged. */
    private String accessToken;

    @NotBlank(message = "Webhook verify token is required")
    private String verifyToken;

    private String appSecret;
    private String displayPhoneNumber;
    private Boolean isActive;
}
