package com.arxivlens.service;

import com.arxivlens.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link JavaMailSender} that gracefully no-ops when SMTP
 * isn't configured. Returning success rather than throwing keeps the caller
 * idempotent: in dev/staging without SMTP credentials we still want
 * "request password reset" to return 200 so the UX is testable; the reset URL
 * is logged at WARN so a developer can click through manually.
 *
 * <p>SMTP is considered "configured" when {@code app.mail.host} is non-blank.
 * Spring Boot only wires a {@link JavaMailSender} when {@code spring.mail.host}
 * is set — we mirror the same trigger so the two settings move in lockstep.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final AppProperties props;
    private final JavaMailSender mailSender;  // null when SMTP isn't configured

    public EmailService(AppProperties props, @Autowired(required = false) JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    /**
     * Sends a plain-text email. If SMTP isn't wired, prints the body to logs
     * (so a developer or CI environment without a mail server can still see
     * the reset link).
     */
    public void sendPlainText(String to, String subject, String body) {
        if (mailSender == null) {
            log.warn("SMTP not configured — would have sent email to {} | subject: {} | body:\n{}",
                    to, subject, body);
            return;
        }
        String from = props.mail() == null || props.mail().from() == null || props.mail().from().isBlank()
                ? "noreply@arxivlens.local"
                : props.mail().from();
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mailSender.send(msg);
            log.info("Email sent to {} | subject: {}", to, subject);
        } catch (Exception e) {
            // Don't blow up the calling request — log and let the user retry.
            log.error("Email send failed to {} | subject: {} | {}", to, subject, e.getMessage(), e);
        }
    }
}
