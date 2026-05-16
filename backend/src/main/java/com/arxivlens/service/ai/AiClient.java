package com.arxivlens.service.ai;

import com.arxivlens.entity.Paper;

public interface AiClient {

    /** True if the underlying provider has been configured (e.g. API key present). */
    boolean isConfigured();

    /**
     * Generates a structured summary for the given paper, written in {@code targetLanguage}
     * (e.g. {@code "English"}, {@code "Traditional Chinese (Taiwan)"}). Implementations decide
     * the exact prompt but must respect the requested language.
     */
    AiSummaryResult summarize(Paper paper, String targetLanguage);

    /**
     * Translates a paper's {@code title}, {@code abstractText}, and (optional)
     * {@code introduction} into the language named by {@code targetLanguage}
     * (e.g. {@code "Traditional Chinese (Taiwan)"}, {@code "Japanese"}).
     * Implementations should preserve technical terminology when there's no
     * idiomatic equivalent. The introduction is what manual / URL-imported
     * articles store as their full body; passing null skips it (returned
     * introduction will be null too).
     */
    TranslationResult translate(String title, String abstractText, String introduction, String targetLanguage);

    record AiSummaryResult(
            String summary,
            java.util.List<String> keyPoints,
            java.util.List<String> tags,
            String difficulty,
            Integer readingTimeMin
    ) {}

    record TranslationResult(String title, String abstractText, String introduction) {}
}
