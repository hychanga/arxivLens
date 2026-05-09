package com.arxivlens.service;

import com.arxivlens.entity.Paper;
import com.arxivlens.entity.PaperTranslation;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.PaperTranslationRepository;
import com.arxivlens.service.ai.AiClient;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PaperTranslationService {

    /**
     * Locales we support translating into. {@code en} is the original — the frontend never asks
     * us to translate to English so we don't list it here.
     */
    private static final Set<String> SUPPORTED = Set.of("zh-TW", "zh-CN", "ja");

    /** Gemini gets a human-readable language name in the prompt rather than a BCP-47 tag. */
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "zh-TW", "Traditional Chinese (Taiwan)",
            "zh-CN", "Simplified Chinese",
            "ja",    "Japanese"
    );

    private final PaperRepository papers;
    private final PaperTranslationRepository translations;
    private final AiClient ai;

    public PaperTranslationService(PaperRepository papers,
                                   PaperTranslationRepository translations,
                                   AiClient ai) {
        this.papers = papers;
        this.translations = translations;
        this.ai = ai;
    }

    @Transactional(readOnly = true)
    public Optional<PaperTranslation> findCached(Long paperId, String locale) {
        validateLocale(locale);
        return translations.findByPaperIdAndLocale(paperId, locale);
    }

    /** Returns cached translation if present, otherwise calls the AI provider, persists, returns. */
    @Transactional
    public PaperTranslation translateOrCached(Long paperId, String locale) {
        validateLocale(locale);

        Optional<PaperTranslation> cached = translations.findByPaperIdAndLocale(paperId, locale);
        if (cached.isPresent()) return cached.get();

        Paper p = papers.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paper not found"));

        String languageName = LANGUAGE_NAMES.get(locale);
        AiClient.TranslationResult r = ai.translate(p.getTitle(), p.getAbstractText(), languageName);

        PaperTranslation t = new PaperTranslation();
        t.setPaper(p);
        t.setLocale(locale);
        t.setTitle(r.title() == null || r.title().isBlank() ? p.getTitle() : r.title());
        t.setAbstractText(r.abstractText() == null || r.abstractText().isBlank() ? p.getAbstractText() : r.abstractText());
        return translations.save(t);
    }

    private void validateLocale(String locale) {
        if (locale == null || !SUPPORTED.contains(locale)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unsupported locale: " + locale + " (supported: " + SUPPORTED + ")");
        }
    }
}
