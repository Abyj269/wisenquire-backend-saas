package com.leads.mail;

import java.util.List;

/**
 * Module 02 P2 item 4: every transactional email the app can send (code-owned, never free text). Each has
 * {@code templates/email/<file>.html} and {@code .txt}; {@link #variables} are the names the templates read.
 * OTP and PASSWORD_RESET are sent today; the others are ready for invitations (module 03), signup (20) and billing (22).
 */
public enum MailTemplate {
    OTP("otp", "Your login verification code", List.of("code", "minutes")),
    VERIFY_EMAIL("verify-email", "Confirm your email address", List.of("link", "minutes")),
    INVITE("invite", "You have been invited to join your team", List.of("inviterName", "organizationName", "link")),
    PASSWORD_RESET("password-reset", "Reset your password", List.of("link", "minutes")),
    TRIAL_EXPIRING("trial-expiring", "Your trial ends soon", List.of("organizationName", "daysLeft", "link")),
    PAYMENT_FAILED("payment-failed", "Your payment did not go through", List.of("organizationName", "amount", "link")),
    INVOICE_ISSUED("invoice-issued", "Your invoice is ready", List.of("organizationName", "invoiceNumber", "amount", "link"));

    public final String file;
    public final String subject;
    public final List<String> variables;

    MailTemplate(String file, String subject, List<String> variables) {
        this.file = file;
        this.subject = subject;
        this.variables = variables;
    }
}
