package com.arxivlens.dto;

import com.arxivlens.entity.AiSummary;
import com.arxivlens.entity.Favorite;
import com.arxivlens.entity.Paper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class FavoriteDtos {

    private FavoriteDtos() {}

    public record CreateFavoriteRequest(
            @NotNull Long paperId,
            @Size(max = 8000) String note
    ) {}

    public record UpdateNoteRequest(
            @Size(max = 8000) String note
    ) {}

    public record FavoriteView(
            Long id,
            Paper paper,
            String note,
            Instant savedAt,
            AiSummaryView summary,
            boolean cached
    ) {
        public static FavoriteView of(Favorite f, AiSummary s, boolean cached) {
            return new FavoriteView(
                    f.getId(),
                    f.getPaper(),
                    f.getNote(),
                    f.getSavedAt(),
                    s == null ? null : AiSummaryView.of(s),
                    cached
            );
        }
    }

    public record AiSummaryView(
            Long id,
            String summary,
            List<String> keyPoints,
            List<String> tags,
            String difficulty,
            Integer readingTimeMin,
            Instant createdAt
    ) {
        public static AiSummaryView of(AiSummary s) {
            return new AiSummaryView(
                    s.getId(),
                    s.getSummary(),
                    s.getKeyPoints(),
                    s.getTags(),
                    s.getDifficulty(),
                    s.getReadingTimeMin(),
                    s.getCreatedAt()
            );
        }
    }
}
