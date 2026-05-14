package com.arxivlens.service;

import com.arxivlens.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Outgoing email with provider fallthrough.
 *
 * <p>Priority order:
 * <ol>
 *   <li>If {@code app.mail.resend-api-key} is set → POST to Resend's HTTP API.
 *       This is the required path on Render Free, which blocks outbound SMTP
 *       (ports 25/465/587) for anti-abuse. Resend is HTTPS so it sails through.</li>
 *   <li>Else if Spring's {@link JavaMailSender} bean exists (i.e. {@code spring.mail.host}
 *       is set) → send over SMTP. Useful for local dev where SMTP isn't blocked.</li>
 *   <li>Else → no-op, log the would-have-been message at WARN so a developer can
 *       still copy the reset link from logs.</li>
 * </ol>
 *
 * <p>Errors at the provider level are logged but never propagated to the caller —
 * the password-reset endpoint already returns 204 regardless (anti-enumeration),
 * and we don't want a transient SMTP/Resend hiccup to surface as a 5xx.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";

    private final AppProperties props;
    private final JavaMailSender mailSender;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public EmailService(AppProperties props,
                        @Autowired(required = false) JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    public void sendPlainText(String to, String subject, String body) {
        String resendKey = resendApiKey();
        if (resendKey != null && !resendKey.isBlank()) {
            sendViaResend(to, subject, body, resendKey);
            return;
        }
        if (mailSender != null) {
            sendViaSmtp(to, subject, body);
            return;
        }
        log.warn("Email not configured (no RESEND_API_KEY, no SMTP) — would have sent to {} | subject: {} | body:\n{}",
                to, subject, body);
    }

    private void sendViaResend(String to, String subject, String body, String apiKey) {
        try {
            // Hand-built JSON to avoid depending on jackson-databind being on the
            // compile classpath (it isn't, with our current starter set). The
            // Resend payload is rigid: from/to/subject/text — escapeJson handles
            // the four risky fields uniformly.
            String reqBody = "{"
                    + "\"from\":\"" + escapeJson(from()) + "\","
                    + "\"to\":[\"" + escapeJson(to) + "\"],"
                    + "\"subject\":\"" + escapeJson(subject) + "\","
                    + "\"text\":\"" + escapeJson(body) + "\""
                    + "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                // Resend returns JSON error bodies like {"statusCode":403,"name":"...","message":"..."}.
                log.error("Resend send failed to {} | HTTP {} | body: {}", to, res.statusCode(), res.body());
                return;
            }
            log.info("Email sent via Resend to {} | subject: {}", to, subject);
        } catch (Exception e) {
            log.error("Resend send error to {} | {}", to, e.getMessage(), e);
        }
    }

    /** Minimal JSON string escaping — sufficient for the four scalar fields we send to Resend. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void sendViaSmtp(String to, String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mailSender.send(msg);
            log.info("Email sent via SMTP to {} | subject: {}", to, subject);
        } catch (Exception e) {
            log.error("SMTP send failed to {} | {}", to, e.getMessage(), e);
        }
    }

    /**
     * "From" address. When using Resend's shared dev domain, the recommended
     * value is {@code arxivLens <onboarding@resend.dev>}; otherwise an email at
     * a domain you've verified with Resend.
     */
    private String from() {
        if (props.mail() == null || props.mail().from() == null || props.mail().from().isBlank()) {
            return "arxivLens <onboarding@resend.dev>";
        }
        return props.mail().from();
    }

    private String resendApiKey() {
        return props.mail() == null ? null : props.mail().resendApiKey();
    }
}
