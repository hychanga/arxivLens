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
        PasswordReset passwordReset,
        Oauth oauth,
        BusinessWeekly businessWeekly
) {

    /**
     * Business Weekly is a "search-results" source: the Latest feed is built by
     * scraping the public search-results page for {@code searchKeyword}. Default
     * keyword is the Taiwanese wine writer 林裕森; override via the
     * {@code BUSINESS_WEEKLY_SEARCH_KEYWORD} env var when needed.
     */
    public record BusinessWeekly(String searchKeyword) {}
    public record Jwt(String secret, long expirationMs, String issuer) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Ai(String provider, Gemini gemini) {
        public record Gemini(String apiKey, String model) {}
    }
    /**
     * @param notifyEmail address to email a summary to after each scheduled
     *                    arXiv sync completes. Blank → no notification sent.
     */
    public record Scheduler(String arxivCron, String hbrCron, boolean enabled, String notifyEmail) {}

    /**
     * OAuth identity-provider settings. A blank {@code clientId} for a provider
     * means real sign-in for that provider is disabled and
     * {@code AuthService.oauthLogin} falls back to the mock demo-user behavior
     * (useful for offline dev / CI).
     */
    public record Oauth(Google google, Apple apple) {
        public record Google(String clientId) {}

        /**
         * Sign in with Apple settings. {@code clientId} is the Services ID
         * registered in the Apple Developer portal — it is both the web flow's
         * {@code client_id} and the ID token's {@code aud}. Comma-separate to
         * allow more than one audience (e.g. a web Services ID plus a native
         * app bundle ID).
         */
        public record Apple(String clientId) {}
    }

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
