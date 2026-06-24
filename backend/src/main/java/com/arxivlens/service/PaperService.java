package com.arxivlens.service;

import com.arxivlens.dto.PaperDtos.ImportUrlRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperResponse;
import com.arxivlens.entity.Download;
import com.arxivlens.entity.Favorite;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.AiSummaryRepository;
import com.arxivlens.repository.DownloadBlobRepository;
import com.arxivlens.repository.DownloadRepository;
import com.arxivlens.repository.FavoriteRepository;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.PaperTranslationRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaperService {

    private static final Logger log = LoggerFactory.getLogger(PaperService.class);

    /** Abstract truncation point. Keeps the preview card readable without dumping the whole body. */
    private static final int ABSTRACT_PREVIEW_CHARS = 500;

    /** Cap on the HTML response we'll buffer while extracting article text. */
    private static final long URL_FETCH_MAX_BYTES = 5L * 1024 * 1024; // 5 MB

    private final PaperRepository papers;
    private final SourceRepository sources;
    private final PaperTranslationRepository translations;
    private final FavoriteRepository favorites;
    private final AiSummaryRepository summaries;
    private final DownloadRepository downloads;
    private final DownloadBlobRepository blobs;

    /** {@code ALWAYS} so we follow HTTP→HTTPS upgrades silently like every browser does. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public PaperService(PaperRepository papers,
                        SourceRepository sources,
                        PaperTranslationRepository translations,
                        FavoriteRepository favorites,
                        AiSummaryRepository summaries,
                        DownloadRepository downloads,
                        DownloadBlobRepository blobs) {
        this.papers = papers;
        this.sources = sources;
        this.translations = translations;
        this.favorites = favorites;
        this.summaries = summaries;
        this.downloads = downloads;
        this.blobs = blobs;
    }

    /**
     * Sources whose feed bypasses the {@code publishedAt >= since} filter.
     * Empty by default — HBR used to bypass while it was paste-only, but it
     * now auto-syncs the home page and writes real publishedAt values, so
     * the date filter behaves correctly. Users who want to see older
     * imports can pick the 2yr / "All" quick filter in the sidebar.
     */
    private static final java.util.Set<String> MANUAL_SOURCES = java.util.Set.of();

    /**
     * Hard ceiling on a numeric "last N days" filter. {@code days = 0} (and
     * anything {@code <= 0}) is a separate "no upper bound" sentinel — older
     * articles can legitimately predate any reasonable cap (we have rows
     * going back to 2009), so we don't want a clamp to silently swallow them.
     */
    private static final int MAX_FEED_DAYS = 3650;

    public Page<Paper> findFeed(String sourceCode, Integer days, String topicCode, String q, int page, int size) {
        long sourceId = sources.findByCode(sourceCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown source: " + sourceCode))
                .getId();
        boolean noDateFilter;
        int safeDays;
        if (days == null) {
            // Default for new sessions / unset prefs.
            safeDays = 30;
            noDateFilter = false;
        } else if (days <= 0) {
            // 0 = "All" sentinel from the sidebar's quick filter.
            safeDays = 0;
            noDateFilter = true;
        } else {
            safeDays = Math.min(MAX_FEED_DAYS, days);
            noDateFilter = false;
        }
        Instant since = (noDateFilter || MANUAL_SOURCES.contains(sourceCode))
                ? Instant.EPOCH
                : Instant.now().minus(safeDays, ChronoUnit.DAYS);
        int safeSize = Math.min(100, Math.max(1, size));
        String safeQ = (q == null || q.isBlank()) ? null : q.trim();
        String safeTopicCode = (topicCode == null || topicCode.isBlank()) ? null : topicCode;
        PageRequest pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "publishedAt"));
        if (safeQ != null) {
            return papers.findFeedSearch(sourceId, since, safeTopicCode, safeQ, pageable);
        }
        return papers.findFeed(sourceId, since, safeTopicCode, pageable);
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

        String title = req.title().trim();
        String url = blankToNull(req.url());
        rejectIfUrlExists(src.getId(), url);
        rejectIfTitleExists(src.getId(), title);

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
        p.setTitle(title);
        p.setAuthorsJson(toJsonStringArray(authors));
        p.setAbstractText(abstractText);
        p.setIntroduction(body);
        p.setUrl(url);
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

    /**
     * Fetches the URL server-side, extracts a title + main content via
     * {@link HtmlExtractor}, and saves the result as a Paper. One round-trip
     * for the user; the result flows through the same preview / translate /
     * AI-summary path as anything else.
     *
     * <p>Paywalled pages (e.g. hbr.org) only return the public teaser to an
     * anonymous fetch, so the saved content will be whatever the server sees
     * without subscriber cookies. The user can fall back to
     * {@link #createManual(ManualPaperRequest)} for full subscriber text.
     */
    @Transactional
    public ManualPaperResponse importFromUrl(ImportUrlRequest req) {
        Source src = sources.findById(req.sourceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Source not found"));
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Source \"" + src.getCode() + "\" is disabled. Enable it in Admin before importing articles.");
        }

        // Cheap URL check before the network round-trip — no point fetching a
        // page we already have. The title check waits until after extraction,
        // since we only learn the title from the fetched page.
        rejectIfUrlExists(src.getId(), req.url());

        String html = fetchHtml(req.url());
        // Pass the article URL as baseUrl so relative <img src> attributes
        // are resolved into absolute URLs — the frontend renders the markdown
        // image markers as <img> tags directly, with no second resolution pass.
        HtmlExtractor.ExtractedArticle extracted = HtmlExtractor.extract(html, req.url());

        if (extracted.title() == null || extracted.title().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Couldn't extract a title from that URL. Paste the article manually instead.");
        }
        if (extracted.content() == null || extracted.content().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Couldn't extract article content from that URL — the page may be paywalled or "
                            + "rendered entirely in JavaScript. Paste the article text manually instead.");
        }

        rejectIfTitleExists(src.getId(), extracted.title().trim());

        String externalId = "manual-" + UUID.randomUUID();
        String body = extracted.content().trim();
        String abstractText = body.length() > ABSTRACT_PREVIEW_CHARS
                ? body.substring(0, ABSTRACT_PREVIEW_CHARS).trim() + "…"
                : body;

        Paper p = new Paper();
        p.setSource(src);
        p.setSourceId(src.getId());
        p.setExternalId(externalId);
        p.setTitle(extracted.title().trim());
        p.setAuthorsJson("[]");
        p.setAbstractText(abstractText);
        p.setIntroduction(body);
        p.setUrl(req.url());
        p.setPdfUrl(null);
        p.setTopicCode(blankToNull(req.topicCode()));
        p.setPublishedAt(extracted.publishedAt() != null ? extracted.publishedAt() : Instant.now());
        papers.save(p);

        return new ManualPaperResponse(
                p.getId(),
                p.getExternalId(),
                p.getTitle(),
                List.of(),
                p.getPublishedAt()
        );
    }

    /**
     * Rejects a manual add when another article in the same source already has
     * this URL. No-op when {@code url} is null (paste-without-link). Pairs with
     * {@link #rejectIfTitleExists}.
     */
    private void rejectIfUrlExists(Long sourceId, String url) {
        if (url != null && papers.findFirstBySourceIdAndUrl(sourceId, url).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_URL",
                    "An article with this URL already exists in this feed.");
        }
    }

    /** Rejects a manual add when another article in the same source has the same title (case-insensitive). */
    private void rejectIfTitleExists(Long sourceId, String title) {
        if (papers.findFirstBySourceIdAndTitleIgnoreCase(sourceId, title).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_TITLE",
                    "An article with this title already exists in this feed.");
        }
    }

    /**
     * Deletes a manually-added paper and everything that references it:
     * translations, favorites (and their AI summaries), downloads (and their
     * BLOBs). Restricted to {@code manual-…} papers so we can't accidentally
     * nuke a paper that {@code ArxivSyncService} would just re-insert with a
     * new ID — the only place an admin should drop synced papers is the
     * existing "Clear paper cache" action in Admin.
     */
    @Transactional
    public void delete(Long paperId) {
        Paper p = papers.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paper not found"));
        if (p.getExternalId() == null || !p.getExternalId().startsWith("manual-")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only manually-added articles can be deleted from here. "
                            + "Sync-fetched papers are removed via Admin → Clear paper cache.");
        }

        // Cascade by hand — none of these tables have FK ON DELETE CASCADE set up,
        // and we want predictable ordering rather than relying on Hibernate's
        // delete-orphan heuristics across un-mapped sibling tables.
        translations.deleteByPaperId(paperId);

        for (Favorite f : favorites.findByPaper_Id(paperId)) {
            summaries.findByFavoriteId(f.getId()).ifPresent(summaries::delete);
            favorites.delete(f);
        }

        for (Download d : downloads.findByPaper_Id(paperId)) {
            if (blobs.existsById(d.getId())) blobs.deleteById(d.getId());
            downloads.delete(d);
        }

        papers.delete(p);
        log.info("Deleted manual paper {} ({})", paperId, p.getExternalId());
    }

    /**
     * Wipes every {@code manual-…} paper (and its translations / favorites /
     * summaries / downloads / blobs). Called from the admin "Clear manual
     * articles" action when the user wants to start over without poking each
     * paper individually.
     *
     * <p>Reuses {@link #delete(Long)} per row so the cascade logic stays in
     * one place; for hobby-tier volume (tens to a few hundred manual papers)
     * the per-row overhead is irrelevant.
     */
    @Transactional
    public int deleteAllManual() {
        List<Paper> targets = papers.findByExternalIdStartingWith("manual-");
        for (Paper p : targets) {
            delete(p.getId());
        }
        log.info("Deleted {} manual papers in bulk", targets.size());
        return targets.size();
    }

    private String fetchHtml(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid URL: " + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "URL must use http or https");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                // Several CDNs (HBR's included) serve a stripped-down page to default
                // Java User-Agents. Mimic a real browser so we get the same HTML a
                // logged-out visitor would see.
                .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 "
                                + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,zh-TW;q=0.8,zh;q=0.7")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Source URL responded with HTTP " + res.statusCode());
            }
            byte[] body = res.body();
            if (body.length > URL_FETCH_MAX_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Page exceeds " + (URL_FETCH_MAX_BYTES / (1024 * 1024)) + " MB — refusing to parse.");
            }
            // We don't reliably know the page's charset; UTF-8 is the modern default and
            // the regex extractor is tolerant of mis-decoded bytes for ASCII metadata.
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (ApiException ae) {
            throw ae;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Timed out fetching the URL.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Fetch interrupted");
        } catch (Exception e) {
            log.warn("Failed to fetch URL {}: {}", url, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't reach that URL: " + e.getMessage());
        }
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
