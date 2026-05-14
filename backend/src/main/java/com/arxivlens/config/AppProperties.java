package com.arxivlens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Cors cors,
        Ai ai,
        Scheduler scheduler,
        Mail mail,
        Frontend frontend,
        PasswordReset passwordReset
) {
    public record Jwt(String secret, long expirationMs, String issuer) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Ai(String provider, Gemini gemini) {
        public record Gemini(String apiKey, String model) {}
    }
    public record Scheduler(String arxivCron, String hbrCron, boolean enabled) {}

    /**
     * Outgoing-mail settings. EmailService prefers HTTP providers (Resend) over
     * SMTP because PaaS hosts like Render block outbound SMTP ports for anti-abuse.
     * Priority: {@code resendApiKey} set → Resend HTTP API; else {@code host} set
     * → SMTP via Spring's JavaMailSender; else email is logged rather than sent.
     */
    public record Mail(String host, String from, String resendApiKey) {}

    /** Public URL the frontend is served from — used to build the reset-password link. */
    public record Frontend(String baseUrl) {}

    public record PasswordReset(long expirationMinutes) {}
}
