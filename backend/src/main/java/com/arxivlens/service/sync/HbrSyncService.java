package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.service.HtmlExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daily auto-sync for HBR Taiwan. Walks the public home page,
 * {@code https://www.hbrtaiwan.com/}, scrapes every {@code /article/<id>/…}
 * link the editors have surfaced, and for each previously unseen article id
 * fetches the article page and runs it through {@link HtmlExtractor} to
 * harvest title / body / publish date. The saved row points
 * {@code Paper.url} at the HBR Taiwan URL so the favorites "Open source"
 * button continues to work.
 *
 * <p>Complementary to the manual paste / URL-import path on the Feed page:
 * the home page only carries the public teaser for paywalled articles, so
 * subscribers who want the full body can still paste it in afterwards.
 */
@Service
public class HbrSyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(HbrSyncService.class);
    private static final String SOURCE_CODE = "hbr";
    private static final String BASE = "https://www.hbrtaiwan.com";

    /**
     * Matches every {@code /article/<id>(/slug)?} href anywhere in the home
     * page markup. Group 1 is the path; we hash group 2 (the id) into the
     * stable externalId.
     */
    private static final Pattern ARTICLE_HREF = Pattern.compile(
            "href=[\"'](/article/(\\d+)(?:/[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** Extracts the numeric article id from an already-collected path. */
    private static final Pattern ARTICLE_HREF_ID = Pattern.compile("^/article/(\\d+)");

    /** Polite pause between per-article fetches so we don't hammer the CDN. */
    private static final long POLITE_SLEEP_MS = 800;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SourceRepository sources;
    private final PaperRepository papers;

    public HbrSyncService(SourceRepository sources, PaperRepository papers) {
        this.sources = sources;
        this.papers = papers;
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    @Transactional
    public SyncResult sync() {
        Optional<Source> srcOpt = sources.findByCode(SOURCE_CODE);
        if (srcOpt.isEmpty()) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source row missing");
        }
        Source src = srcOpt.get();
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source disabled");
        }

        // Phase 1 — pull the home page and harvest every article path.
        Set<String> paths;
        try {
            String homeHtml = fetchHtml(BASE + "/");
            paths = collectPaths(homeHtml);
        } catch (Exception e) {
            log.warn("HBR home fetch failed: {}", e.getMessage());
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Home fetch failed: " + e.getMessage());
        }
        log.info("HBR: home yielded {} unique article path(s)", paths.size());
        if (paths.isEmpty()) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0,
                    "Home returned no article links — markup may have changed.");
        }

        // Phase 2 — per article: fetch the page, extract via HtmlExtractor, save.
        int fetched = 0, inserted = 0, skipped = 0, errored = 0;
        boolean first = true;
        for (String path : paths) {
            fetched++;
            String articleId = extractArticleId(path);
            if (articleId == null) {
                errored++;
                continue;
            }
            String externalId = "hbr-" + articleId;

            if (papers.findBySourceIdAndExternalId(src.getId(), externalId).isPresent()) {
                skipped++;
                continue;
            }

            // Polite pause between successive article-page fetches. Skip on
            // the very first iteration so a sync with zero new articles is
            // fully cache-served and instant.
            if (!first && sleepOrInterrupted()) {
                errored++;
                break;
            }
            first = false;

            try {
                Paper p = buildFromArticlePage(src, externalId, path);
                if (p == null) {
                    errored++;
                    continue;
                }
                papers.save(p);
                inserted++;
            } catch (Exception ex) {
                log.warn("HBR ingest failed for {}: {}", path, ex.getMessage());
                errored++;
            }
        }

        log.info("HBR sync: fetched={} inserted={} skipped={} errored={}",
                fetched, inserted, skipped, errored);
        return new SyncResult(SOURCE_CODE, fetched, inserted, skipped,
                errored > 0 ? errored + " article(s) errored — see warn logs" : null);
    }

    private static String extractArticleId(String path) {
        Matcher m = ARTICLE_HREF_ID.matcher(path);
        return m.find() ? m.group(1) : null;
    }

    static Set<String> collectPaths(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null) return out;
        Matcher m = ARTICLE_HREF.matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private Paper buildFromArticlePage(Source src, String externalId, String path) throws Exception {
        String articleUrl = BASE + path;
        String html = fetchHtml(articleUrl);
        HtmlExtractor.ExtractedArticle ex = HtmlExtractor.extract(html, articleUrl);
        String title = ex.title() == null ? "" : ex.title().trim();
        if (title.isEmpty()) {
            log.warn("HBR: could not extract title for {}", articleUrl);
            return null;
        }
        String content = ex.content() == null ? "" : ex.content().trim();

        Paper p = new Paper();
        p.setSource(src);
        p.setSourceId(src.getId());
        p.setExternalId(externalId);
        p.setTitle(title);
        p.setAuthorsJson("[]");
        p.setAbstractText(snippet(content, title));
        p.setIntroduction(content.isBlank() ? null : content);
        p.setUrl(articleUrl);
        p.setPdfUrl(null);
        p.setTopicCode(null);
        p.setPublishedAt(ex.publishedAt() != null ? ex.publishedAt() : Instant.now());
        return p;
    }

    private static String snippet(String content, String fallback) {
        if (content == null || content.isBlank()) return fallback;
        String trimmed = content.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500).trim() + "…" : trimmed;
    }

    private boolean sleepOrInterrupted() {
        try {
            Thread.sleep(POLITE_SLEEP_MS);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private String fetchHtml(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 "
                                + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + " from " + url);
        }
        return new String(res.body(), StandardCharsets.UTF_8);
    }
}
