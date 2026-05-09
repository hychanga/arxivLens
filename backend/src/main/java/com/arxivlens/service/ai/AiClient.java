package com.arxivlens.service.ai;

import com.arxivlens.entity.Paper;

public interface AiClient {

    /** True if the underlying provider has been configured (e.g. API key present). */
    boolean isConfigured();

    /** Generates a structured summary for the given paper. Implementations decide the exact prompt. */
    AiSummaryResult summarize(Paper paper);

    /**
     * Translates a paper's {@code title} and {@code abstractText} into the language named by
     * {@code targetLanguage} (e.g. {@code "Traditional Chinese (Taiwan)"}, {@code "Japanese"}).
     * Implementations should preserve technical terminology when there's no idiomatic equivalent.
     */
    TranslationResult translate(String title, String abstractText, String targetLanguage);

    record AiSummaryResult(
            String summary,
            java.util.List<String> keyPoints,
            java.util.List<String> tags,
            String difficulty,
            Integer readingTimeMin
    ) {}

    record TranslationResult(String title, String abstractText) {}
}
