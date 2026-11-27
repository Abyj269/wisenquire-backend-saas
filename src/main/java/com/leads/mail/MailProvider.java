package com.leads.mail;

/**
 * Module 02 P2 item 4: the one place that hands an email to a mail service. {@link SmtpMailProvider} is the
 * adapter today; another provider (SES, Postmark, SendGrid) is another implementation of this port.
 * Never call it inside a DB transaction (CLAUDE.md hard rule 2).
 */
public interface MailProvider {

    record OutgoingMail(String to, String subject, String html, String text) { }

    /** Sends one email; throws on failure (the caller decides: retry from the outbox, or report to the user). */
    void send(OutgoingMail mail);
}
