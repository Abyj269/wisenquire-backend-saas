package com.leads.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpVerifyRequest {
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "OTP code is required")
    private String otp;

    @NotBlank(message = "Login challenge is required")
    private String challengeId;
}
