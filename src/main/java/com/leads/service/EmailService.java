package com.leads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final com.leads.mail.MailRenderer renderer;
    private final com.leads.mail.MailProvider provider;

    /**
     * The login OTP: sent SYNCHRONOUSLY (the user waits for the code), never through the outbox. A failed send is
     * reported to the user, who can retry. Callers send it after their transaction committed (hard rule 2).
     */
    public void sendOtpEmail(String toEmail, String otpCode, int expiryMinutes) {
        try {
            provider.send(renderer.render(toEmail, com.leads.mail.MailTemplate.OTP, java.util.Map.of("code", otpCode, "minutes", expiryMinutes)));
        } catch (Exception e) {
            log.warn("OTP email delivery failed.", e);
            throw new IllegalStateException("Unable to deliver the login code. Please retry or contact your administrator.");
        }
    }
}
