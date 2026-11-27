package com.leads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    // Providers like Office 365 reject sends with SendAsDenied unless From
    // exactly matches the authenticated mailbox - without setting it
    // explicitly, JavaMail falls back to "<local-user>@<local-hostname>".
    // Separate from spring.mail.username because API-key-auth providers
    // (ZeptoMail, SendGrid) use a non-email username for SMTP auth but still
    // need a real verified address in the From header - see
    // app.mail.from-address in application.yml.
    @Value("${app.mail.from-address}")
    private String fromAddress;

    /**
     * Best-effort send — SMTP is a demo/placeholder in this environment, so a
     * delivery failure is logged rather than thrown. The OTP is also
     * echoed in the login response (app.security.otp.expose-in-response) so
     * the 2FA flow stays testable without live credentials.
     */
    public void sendOtpEmail(String toEmail, String otpCode, int expiryMinutes) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Your login verification code");
            message.setText("Your one-time login code is: " + otpCode
                    + "\n\nThis code expires in " + expiryMinutes + " minutes. "
                    + "If you did not attempt to log in, you can ignore this email.");
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("OTP email delivery failed.", e);
            throw new IllegalStateException("Unable to deliver the login code. Please retry or contact your administrator.");
        }
    }

    /**
     * Module 02 P2: the password-reset link. Best effort (logged, never thrown): the caller has already answered 202,
     * and it runs on the mail executor, outside any transaction.
     */
    public void sendPasswordResetEmail(String toEmail, String link, int expiryMinutes) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Reset your password");
            message.setText("We received a request to reset the password of your account.\n\n"
                    + "Open this link to choose a new password (it works once, for " + expiryMinutes + " minutes):\n" + link
                    + "\n\nIf you did not ask for this, you can ignore this email; your password stays the same.");
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Password reset email delivery failed.", e);
        }
    }
}
