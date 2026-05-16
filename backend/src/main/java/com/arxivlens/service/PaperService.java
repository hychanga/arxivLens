package com.arxivlens.service;

import com.arxivlens.dto.PaperDtos.ManualPaperRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperResponse;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaperService {

    /** Abstract truncation point. Keeps the preview card readable without dumping the whole body. */
    private static final int ABSTRACT_PREVIEW_CHARS = 500;

    private final PaperRepository papers;
    private final SourceRepository sources;

    public PaperService(PaperRepository papers, SourceRepository sources) {
        this.papers = papers;
        this.sources = sources;
    }

    public Page<Paper> findFeed(String sourceCode, Integer days, String topicCode, int page, int size) {
        long sourceId = sources.findByCode(sourceCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown source: " + sourceCode))
                .getId();
        int safeDays = (days == null) ? 30 : Math.min(365, Math.max(1, days));
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);
        int safeSize = Math.min(100, Math.max(1, size));
        return papers.findFeed(
                sourceId,
                since,
                (topicCode == null || topicCode.isBlank()) ? null : topicCode,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "publishedAt"))
        );
    }

    /**
     * Inserts a user-pasted article as a {@link Paper}.
     *
     * <p>The pasted body lands in {@code introduction} (so the preview modal
     * renders it under "Introduction"). A short prefix becomes
     * {@code abstract_text} so the feed card has a teaser without dragging
     * the whole article into list queries.
     *
     * <p>{@code external_id} is synthesized — manual rows have no upstream
     * stable id — and namespaced with {@code "manual-"} so they're greppable
     * in dumps and won't clash with anything HBR's CMS might produce.
     */
    @Transactional
    public ManualPaperResponse createManual(ManualPaperRequest req) {
        Source src = sources.findById(req.sourceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Source not found"));
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Source \"" + src.getCode() + "\" is disabled. Enable it in Admin before adding articles.");
        }

        String externalId = "manual-" + UUID.randomUUID();
        String body = req.content().trim();
        String abstractText = body.length() > ABSTRACT_PREVIEW_CHARS
                ? body.substring(0, ABSTRACT_PREVIEW_CHARS).trim() + "…"
                : body;

        List<String> authors = parseAuthors(req.author());

        Paper p = new Paper();
        p.setSource(src);
        p.setSourceId(src.getId());
        p.setExternalId(externalId);
        p.setTitle(req.title().trim());
        p.setAuthorsJson(toJsonStringArray(authors));
        p.setAbstractText(abstractText);
        p.setIntroduction(body);
        p.setUrl(blankToNull(req.url()));
        p.setPdfUrl(null); // manual articles have no PDF URL — the body is the content
        p.setTopicCode(blankToNull(req.topicCode()));
        p.setPublishedAt(req.publishedAt() != null ? req.publishedAt() : Instant.now());
        papers.save(p);

        return new ManualPaperResponse(
                p.getId(),
                p.getExternalId(),
                p.getTitle(),
                authors,
                p.getPublishedAt()
        );
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Author field is a single free-text input — split on commas or semicolons. */
    private static List<String> parseAuthors(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split("[,;]")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String toJsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"', '\\' -> sb.append('\\').append(c);
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }
}
