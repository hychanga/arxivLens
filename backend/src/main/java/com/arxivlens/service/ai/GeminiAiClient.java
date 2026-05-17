package com.arxivlens.service.ai;

import com.arxivlens.config.AppProperties;
import com.arxivlens.entity.Paper;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls Google AI Studio (Gemini) for AI summary generation.
 *
 * Registered only when {@code app.ai.gemini.api-key} is non-empty.
 * {@code @ConditionalOnProperty} alone wasn't enough because
 * {@code application.properties} sets the property to {@code ""} when the env
 * var is missing, and an empty string still satisfies that conditional. The
 * SpEL expression below requires real content so an unset key correctly falls
 * back to {@link StubAiClient} instead of firing 403s at Gemini.
 *
 * Uses the {@code generateContent} REST endpoint with
 * {@code responseMimeType=application/json}. The model is instructed via the
 * prompt to follow a fixed schema; we then parse the inner JSON string from
 * {@code candidates[0].content.parts[0].text}.
 */
@Component
@Primary
@ConditionalOnExpression("'${app.ai.gemini.api-key:}' != ''")
public class GeminiAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final AppProperties props;
    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GeminiAiClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean isConfigured() {
        String key = props.ai().gemini().apiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Belt-and-braces: even if Spring registered this bean (e.g. someone bypasses the
     * conditional via XML or programmatic registration), refuse to fire empty keys at
     * Gemini — the resulting 403 is hard to interpret. Returns the trimmed key on success.
     */
    private String requireApiKey() {
        String key = props.ai().gemini().apiKey();
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                    "Gemini API key not configured. Set GEMINI_API_KEY in the environment "
                    + "BEFORE starting the backend, then restart.");
        }
        return key.trim();
    }

    @Override
    public AiSummaryResult summarize(Paper paper, String targetLanguage) {
        String apiKey = requireApiKey();
        String url = BASE + props.ai().gemini().model() + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", buildPrompt(paper, targetLanguage));

        ObjectNode gc = body.putObject("generationConfig");
        gc.put("responseMimeType", "application/json");
        gc.put("temperature", 0.3);
        gc.put("maxOutputTokens", 2048);
        // Gemini 2.5 has "thinking" enabled by default. Those internal reasoning tokens
        // consume our maxOutputTokens budget without producing visible content, leading
        // to MAX_TOKENS truncations. Summarization is a deterministic transform — disable.
        gc.putObject("thinkingConfig").put("thinkingBudget", 0);

        try {
            String requestBody = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                String upstream = extractGeminiError(res.body());
                log.warn("Gemini HTTP {}: {}", res.statusCode(), trim(res.body(), 400));
                HttpStatus mapped = res.statusCode() == 429
                        ? HttpStatus.TOO_MANY_REQUESTS
                        : (res.statusCode() == 401 || res.statusCode() == 403)
                            ? HttpStatus.UNAUTHORIZED
                            : HttpStatus.BAD_GATEWAY;
                throw new ApiException(mapped,
                        "Gemini " + res.statusCode() + ": "
                                + (upstream.isEmpty() ? "(no error details)" : upstream));
            }

            JsonNode root = mapper.readTree(res.body());
            String text = root.path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text").asText("");
            if (text.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini returned no content");
            }

            JsonNode json = mapper.readTree(text);
            return new AiSummaryResult(
                    json.path("summary").asText(""),
                    asStringList(json.path("key_points")),
                    asStringList(json.path("tags")),
                    json.path("difficulty").asText(null),
                    json.has("reading_time_min") && json.get("reading_time_min").isInt()
                            ? json.get("reading_time_min").asInt() : null
            );
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini call interrupted");
        } catch (Exception e) {
            log.warn("Gemini call failed", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini call failed: " + e.getMessage());
        }
    }

    @Override
    public TranslationResult translate(String title, String abstractText, String introduction, String targetLanguage) {
        String apiKey = requireApiKey();
        String url = BASE + props.ai().gemini().model() + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        boolean hasIntro = introduction != null && !introduction.isBlank();

        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", buildTranslationPrompt(title, abstractText, introduction, targetLanguage));

        ObjectNode gc = body.putObject("generationConfig");
        gc.put("responseMimeType", "application/json");
        gc.put("temperature", 0.2);
        // 8192 was fine for abstract-only. Manual / URL-imported articles store
        // the whole body in introduction, so we need much more headroom — bump
        // to 32k when an intro is being translated. Gemini 2.5 supports it.
        gc.put("maxOutputTokens", hasIntro ? 32768 : 8192);
        // Gemini 2.5's default thinking mode burns the bulk of maxOutputTokens on
        // internal reasoning before producing visible output. Translation needs none
        // of that — disable it. Without this, even 8192 hits MAX_TOKENS on long abstracts.
        gc.putObject("thinkingConfig").put("thinkingBudget", 0);

        try {
            String requestBody = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                String upstream = extractGeminiError(res.body());
                log.warn("Gemini translate HTTP {}: {}", res.statusCode(), trim(res.body(), 400));
                HttpStatus mapped = res.statusCode() == 429
                        ? HttpStatus.TOO_MANY_REQUESTS
                        : (res.statusCode() == 401 || res.statusCode() == 403)
                            ? HttpStatus.UNAUTHORIZED
                            : HttpStatus.BAD_GATEWAY;
                throw new ApiException(mapped,
                        "Gemini " + res.statusCode() + ": "
                                + (upstream.isEmpty() ? "(no error details)" : upstream));
            }

            JsonNode root = mapper.readTree(res.body());
            JsonNode candidate = root.path("candidates").path(0);
            String finishReason = candidate.path("finishReason").asText("");
            String text = candidate.path("content").path("parts").path(0)
                    .path("text").asText("");
            if (text.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini returned no translation content");
            }
            // If Gemini hit the output cap mid-stream the JSON is truncated; surface a
            // clear message instead of letting the JSON parser die with "expecting
            // closing quote".
            if ("MAX_TOKENS".equals(finishReason)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Translation was cut off by Gemini's output token limit. "
                        + "Try translating a shorter paper or ask the admin to raise maxOutputTokens.");
            }
            JsonNode json;
            try {
                json = mapper.readTree(text);
            } catch (Exception parseError) {
                log.warn("Gemini translate JSON parse failed (finishReason={}): {}",
                        finishReason, trim(text, 400));
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Gemini returned malformed JSON for translation"
                        + (finishReason.isBlank() ? "" : " (finishReason=" + finishReason + ")"));
            }
            String translatedIntro = hasIntro
                    ? json.path("introduction").asText(introduction)
                    : null;
            return new TranslationResult(
                    json.path("title").asText(title),
                    json.path("abstract").asText(abstractText),
                    translatedIntro
            );
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini translate interrupted");
        } catch (Exception e) {
            log.warn("Gemini translate failed", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini translate failed: " + e.getMessage());
        }
    }

    private static String buildTranslationPrompt(String title, String abstractText, String introduction, String targetLanguage) {
        boolean hasIntro = introduction != null && !introduction.isBlank();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are a professional translator. Translate the fields below into ")
          .append(targetLanguage).append(".\n");
        sb.append("Rules:\n");
        sb.append("- Preserve technical terminology and proper nouns when there is no idiomatic translation.\n");
        sb.append("- Keep the meaning faithful; do not summarize or shorten.\n");
        sb.append("- Preserve paragraph breaks exactly as in the source.\n");
        sb.append("- Preserve any markdown image markers `![alt](url)` verbatim. The URL inside the parentheses must NOT be translated, paraphrased, or modified. The alt text inside the brackets MAY be translated.\n");
        sb.append("- Output ONLY a JSON object matching the schema. No markdown fences, no commentary.\n\n");
        sb.append("=== INPUT ===\n");
        sb.append("Title: ").append(title == null ? "" : title).append("\n\n");
        sb.append("Abstract:\n").append(abstractText == null ? "" : abstractText).append("\n");
        if (hasIntro) {
            sb.append("\nIntroduction:\n").append(introduction).append("\n");
        }
        sb.append("\n=== SCHEMA ===\n");
        sb.append("{\n");
        sb.append("  \"title\":    string,   // translated title\n");
        sb.append("  \"abstract\": string");
        if (hasIntro) {
            sb.append(",   // translated abstract\n");
            sb.append("  \"introduction\": string  // translated introduction / body, paragraph breaks preserved\n");
        } else {
            sb.append("    // translated abstract\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static List<String> asStringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        n.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Pulls {@code error.message} out of Gemini's error envelope so the user sees a useful reason
     * instead of just "HTTP 429". Falls back to empty string if the body isn't parseable.
     */
    private String extractGeminiError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = mapper.readTree(body);
            String msg = root.path("error").path("message").asText("");
            int cut = msg.indexOf('\n');
            if (cut > 0) msg = msg.substring(0, cut);
            return trim(msg, 240);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String buildPrompt(Paper p, String targetLanguage) {
        String lang = (targetLanguage == null || targetLanguage.isBlank()) ? "English" : targetLanguage;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are a research-paper assistant. Read the article's title and abstract below and produce a structured summary.\n");
        sb.append("Write the summary, key_points, tags, and difficulty IN ").append(lang).append(".\n");
        sb.append("difficulty must be the ").append(lang).append(" equivalent of Beginner / Intermediate / Advanced.\n");
        sb.append("Respond with ONLY a JSON object that exactly matches the schema. No prose, no markdown fences.\n\n");
        sb.append("=== INPUT ===\n");
        sb.append("Title: ").append(p.getTitle() == null ? "" : p.getTitle()).append("\n\n");
        sb.append("Abstract:\n").append(p.getAbstractText() == null ? "" : p.getAbstractText()).append("\n");
        sb.append("\n=== SCHEMA ===\n");
        sb.append("{\n");
        sb.append("  \"summary\":          string,    // 1-2 sentences, <= 200 chars, in ").append(lang).append("\n");
        sb.append("  \"key_points\":       string[],  // 3-5 items, each <= 80 chars, in ").append(lang).append("\n");
        sb.append("  \"tags\":             string[],  // 3-5 short topical tags in ").append(lang).append(" (lowercase, no #)\n");
        sb.append("  \"difficulty\":       string,    // Beginner / Intermediate / Advanced equivalent in ").append(lang).append("\n");
        sb.append("  \"reading_time_min\": integer    // estimated minutes to read in full\n");
        sb.append("}\n");
        return sb.toString();
    }
}
