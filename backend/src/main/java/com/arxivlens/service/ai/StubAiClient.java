package com.arxivlens.service.ai;

import com.arxivlens.config.AppProperties;
import com.arxivlens.entity.Paper;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fallback when no real provider is configured. Always throws 501 so the user knows
 * to set {@code GEMINI_API_KEY} (which causes {@link GeminiAiClient} to register
 * as the {@link org.springframework.context.annotation.Primary} bean and shadow this).
 */
@Component
public class StubAiClient implements AiClient {

    private final AppProperties props;

    public StubAiClient(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean isConfigured() {
        String key = props.ai().gemini().apiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public AiSummaryResult summarize(Paper paper) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                "AI summary not configured. Set GEMINI_API_KEY in the environment and restart the server.");
    }

    @Override
    public TranslationResult translate(String title, String abstractText, String targetLanguage) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                "AI translation not configured. Set GEMINI_API_KEY in the environment and restart the server.");
    }
}
