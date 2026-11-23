package com.leads.dto.auth;

import com.leads.model.enums.RoleLevel;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long expiresIn;
    private UserProfileDto user;

    // --- Trusted-user OTP challenge ---
    // When true, token/user above are null — the credentials were correct,
    // but a trusted user must also submit the emailed OTP (POST
    // /auth/verify-otp) before a real token is issued.
    @Builder.Default
    private boolean otpRequired = false;
    private String otpPendingEmail;
    private String challengeId;
    // DEV ONLY — the actual OTP code, populated only when
    // app.security.otp.expose-in-response is true, so the 2FA flow is
    // testable without a live mailbox. Always null in production.
    private String devOtpHint;
}
