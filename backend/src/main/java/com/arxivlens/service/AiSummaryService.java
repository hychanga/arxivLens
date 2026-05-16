package com.arxivlens.service;

import com.arxivlens.dto.FavoriteDtos.AiSummaryView;
import com.arxivlens.entity.AiSummary;
import com.arxivlens.entity.Favorite;
import com.arxivlens.repository.AiSummaryRepository;
import com.arxivlens.service.ai.AiClient;
import com.arxivlens.service.ai.AiClient.AiSummaryResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AiSummaryService {

    /**
     * BCP-47 locale → human-readable language name passed to the AI provider.
     * Mirrors PaperTranslationService.LANGUAGE_NAMES so summary and translation
     * agree on what "zh-TW" maps to. Unknown locales fall back to English.
     */
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "en",    "English",
            "zh-TW", "Traditional Chinese (Taiwan)",
            "zh-CN", "Simplified Chinese",
            "ja",    "Japanese",
            "de",    "German"
    );

    private final FavoriteService favorites;
    private final AiSummaryRepository summaries;
    private final AiClient ai;

    public AiSummaryService(FavoriteService favorites, AiSummaryRepository summaries, AiClient ai) {
        this.favorites = favorites;
        this.summaries = summaries;
        this.ai = ai;
    }

    @Transactional
    public AiSummaryView generate(Long userId, Long favoriteId, String locale) {
        Favorite f = favorites.ownedFavoriteOrThrow(userId, favoriteId);

        String languageName = LANGUAGE_NAMES.getOrDefault(locale, "English");
        AiSummaryResult r = ai.summarize(f.getPaper(), languageName);

        AiSummary existing = summaries.findByFavoriteId(f.getId()).orElseGet(AiSummary::new);
        existing.setFavorite(f);
        existing.setSummary(r.summary());
        existing.setKeyPointsJson(PreferenceService.toJsonArray(r.keyPoints()));
        existing.setTagsJson(PreferenceService.toJsonArray(r.tags()));
        existing.setDifficulty(r.difficulty());
        existing.setReadingTimeMin(r.readingTimeMin());
        AiSummary saved = summaries.save(existing);
        return AiSummaryView.of(saved);
    }
}
