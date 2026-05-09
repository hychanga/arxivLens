package com.arxivlens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Cors cors,
        Ai ai,
        Scheduler scheduler
) {
    public record Jwt(String secret, long expirationMs, String issuer) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Ai(String provider, Gemini gemini) {
        public record Gemini(String apiKey, String model) {}
    }
    public record Scheduler(String arxivCron, String hbrCron, boolean enabled) {}
}
