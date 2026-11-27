package com.leads.controller;

import com.leads.config.ClientIpResolver;
import com.leads.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Module 02 P2: public self-service password reset (no tenant data; never reveals whether an account exists). */
@RestController
@RequestMapping("/public/password-reset")
@RequiredArgsConstructor
@Tag(name = "Password reset", description = "Request a reset link by email, then set a new password with it")
public class PasswordResetController {

    private final PasswordResetService resets;
    private final ClientIpResolver clientIp;

    public record ResetRequest(String email) { }
    public record ResetConfirm(String token, String newPassword) { }

    @PostMapping
    @Operation(summary = "Request a password reset link", description = "Always 202, whether or not the email belongs to an account. "
            + "If it does, a single-use link valid for 30 minutes is emailed.")
    public ResponseEntity<Void> request(@RequestBody(required = false) ResetRequest body, HttpServletRequest http) {
        resets.request(body == null ? null : body.email(), clientIp.resolve(http));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    @Operation(summary = "Set a new password with a reset link", description = "204 when the password was changed; every session of the user ends. "
            + "400 VALIDATION_FAILED when the link is invalid, used or expired, or the password is not 8-128 characters.")
    public ResponseEntity<Void> confirm(@RequestBody(required = false) ResetConfirm body) {
        resets.confirm(body == null ? null : body.token(), body == null ? null : body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
