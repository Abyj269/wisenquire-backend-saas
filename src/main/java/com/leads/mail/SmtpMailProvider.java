package com.leads.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Module 02 P2 item 4: SMTP adapter (Spring's JavaMailSender): a multipart message with an HTML part and a plain-text alternative. */
@Component
@RequiredArgsConstructor
public class SmtpMailProvider implements MailProvider {

    private final JavaMailSender mailSender;

    // Providers like Office 365 reject sends unless From matches the authenticated mailbox; API-key SMTP providers
    // (ZeptoMail, SendGrid) use a non-email username but still need a verified From address (app.mail.from-address).
    @Value("${app.mail.from-address}")
    private String fromAddress;

    @Override
    public void send(OutgoingMail mail) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("Could not build the email: " + e.getMessage(), e);
        }
    }
}
