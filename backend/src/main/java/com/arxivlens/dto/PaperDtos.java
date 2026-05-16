package com.arxivlens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class PaperDtos {

    private PaperDtos() {}

    /**
     * User-pasted article. Used for sources that don't expose a usable feed
     * (HBR after we switched off RSS sync — only paying subscribers can read
     * the full article, and pasting is the only legal way to ingest it).
     *
     * <p>{@code content} is the entire article body. The service derives a
     * short abstract from the first chunk and stores the full text in the
     * Paper entity's {@code introduction} column so it renders in the preview
     * modal and is available to the AI summary / translation flows.
     */
    public record ManualPaperRequest(
            @NotNull Long sourceId,
            @NotBlank @Size(max = 512) String title,
            @NotBlank String content,
            @Size(max = 512) String url,
            @Size(max = 256) String author,
            @Size(max = 64) String topicCode,
            Instant publishedAt
    ) {}

    public record ManualPaperResponse(
            Long id,
            String externalId,
            String title,
            List<String> authors,
            Instant publishedAt
    ) {}

    /**
     * Body for {@code POST /api/papers/import-url}. Server fetches the URL,
     * extracts title + content, and saves a Paper in one shot.
     *
     * <p>Note: paywalled sites only return the public teaser to an anonymous
     * fetch. For full subscriber content the user falls back to
     * {@link ManualPaperRequest}.
     */
    public record ImportUrlRequest(
            @NotNull Long sourceId,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 64) String topicCode
    ) {}
}
