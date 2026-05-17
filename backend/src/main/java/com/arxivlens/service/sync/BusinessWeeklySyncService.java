package com.arxivlens.service.sync;

import com.arxivlens.config.AppProperties;
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
import java.net.URLEncoder;
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
 * Pulls every article surfaced by businessweekly.com.tw's public search-results
 * page for a fixed keyword (default {@code 林裕森}).
 *
 * <p>Two-phase scrape:
 * <ol>
 *   <li><b>Search page</b> — paginate up to {@link #MAX_PAGES} pages and collect
 *       every internal article path (e.g. {@code /style/blog/3021396}). Only
 *       the href is read here; titles are unreliable on the card markup
 *       (image-only anchors, JS-rendered headings, etc.).</li>
 *   <li><b>Article page</b> — for each new path, GET the article URL and run
 *       it through {@link HtmlExtractor}, which already handles og:title,
 *       JSON-LD {@code datePublished}, {@code <time datetime>}, and Chinese
 *       date formats. Titles come from {@code og:title}, dates from any of
 *       the supported meta tags, and content from the article body.</li>
 * </ol>
 *
 * <p>Re-syncs are cheap because we skip article-page fetches for paths whose
 * external id already exists in the DB. Rows previously saved with a URL-like
 * title (legacy of the earlier card-only parser) are self-healed by re-fetching
 * the article page.
 */
@Service
public class BusinessWeeklySyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusinessWeeklySyncService.class);
    private static final String SOURCE_CODE = "businessweekly";
    private static final String BASE = "https://www.businessweekly.com.tw";
    private static final int MAX_PAGES = 20;

    /**
     * Fallback search keyword when {@code app.business-weekly.search-keyword} is
     * unset or empty. Hardcoded here (javac reads source as UTF-8) rather than
     * in {@code application.properties}, where some Spring Boot setups still
     * decode .properties files as Latin-1 and mojibake raw CJK defaults.
     */
    private static final String DEFAULT_KEYWORD = "林裕森";

    /** Matches every internal article path (e.g. /style/blog/3021396). */
    private static final Pattern ARTICLE_HREF = Pattern.compile(
            "href=[\"'](/[a-z\\-]+/(?:blog|article)/\\d+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AppProperties props;
    private final SourceRepository sources;
    private final PaperRepository papers;

    public BusinessWeeklySyncService(AppProperties props,
                                     SourceRepository sources,
                                     PaperRepository papers) {
        this.props = props;
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
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source row missing — restart so SchemaBootstrap can seed it.");
        }
        Source src = srcOpt.get();
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source disabled.");
        }

        String configured = props.businessWeekly() == null ? null : props.businessWeekly().searchKeyword();
        String keyword = (configured == null || configured.isBlank()) ? DEFAULT_KEYWORD : configured;
        log.info("Business Weekly sync using keyword='{}' (env override={})",
                keyword, configured != null && !configured.isBlank());

        // Phase 1: collect every article path the search returns.
        Set<String> paths = collectPaths(keyword);
        log.info("Business Weekly: search yielded {} unique article path(s)", paths.size());
        if (paths.isEmpty()) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Search returned no article links — markup may have changed.");
        }

        // Phase 2: fetch each article page, extract via HtmlExtractor, upsert.
        int fetched = 0, inserted = 0, skipped = 0, errored = 0;
        for (String path : paths) {
            fetched++;
            String externalId = "bw-" + path.replaceFirst("^/", "").replace('/', '-');
            Optional<Paper> existing = papers.findBySourceIdAndExternalId(src.getId(), externalId);
            if (existing.isPresent()) {
                Paper p = existing.get();
                if (looksLikeUrl(p.getTitle())) {
                    if (healFromArticlePage(p, path)) inserted++;
                    else errored++;
                } else {
                    skipped++;
                }
                continue;
            }
            try {
                Paper p = buildFromArticlePage(src, externalId, path);
                if (p == null) {
                    errored++;
                    continue;
                }
                papers.save(p);
                inserted++;
            } catch (Exception e) {
                log.warn("BW: failed to ingest {} — {}", path, e.getMessage());
                errored++;
            }
        }

        log.info("Business Weekly sync: fetched={} inserted={} skipped={} errored={}",
                fetched, inserted, skipped, errored);
        return new SyncResult(SOURCE_CODE, fetched, inserted, skipped,
                errored > 0 ? errored + " article(s) errored — see warn logs" : null);
    }

    /** Walks search-results pages until we run out of new paths or hit MAX_PAGES. */
    private Set<String> collectPaths(String keyword) {
        Set<String> out = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url;
            try {
                url = BASE + "/Search?keyword="
                        + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                        + (page == 1 ? "" : "&page=" + page);
            } catch (Exception e) {
                break;
            }
            String html;
            try {
                html = fetchHtml(url);
            } catch (Exception e) {
                log.warn("BW: page {} fetch failed — {}", page, e.getMessage());
                break;
            }
            int before = out.size();
            Matcher m = ARTICLE_HREF.matcher(html);
            while (m.find()) out.add(m.group(1));
            int found = out.size() - before;
            log.info("BW: page {} contributed {} new path(s) (total {})", page, found, out.size());
            // Stop when a page yields no new paths — businessweekly's search just
            // re-renders the last real page once you walk past the end.
            if (found == 0) break;
        }
        return out;
    }

    /** Fetches the article page and builds a fully-populated Paper from it. */
    private Paper buildFromArticlePage(Source src, String externalId, String path) {
        String articleUrl = BASE + path;
        String html;
        try {
            html = fetchHtml(articleUrl);
        } catch (Exception e) {
            log.warn("BW: cannot fetch article page {} — {}", articleUrl, e.getMessage());
            return null;
        }
        HtmlExtractor.ExtractedArticle ex = HtmlExtractor.extract(html);
        String title = ex.title() == null ? "" : ex.title().trim();
        if (title.isEmpty() || looksLikeUrl(title)) {
            log.warn("BW: could not extract a usable title for {}", articleUrl);
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

    /** Refreshes an existing row whose title got stored as a URL in an earlier run. */
    private boolean healFromArticlePage(Paper p, String path) {
        try {
            String html = fetchHtml(BASE + path);
            HtmlExtractor.ExtractedArticle ex = HtmlExtractor.extract(html);
            String title = ex.title() == null ? "" : ex.title().trim();
            if (title.isEmpty() || looksLikeUrl(title)) return false;
            String content = ex.content() == null ? "" : ex.content().trim();
            p.setTitle(title);
            p.setAbstractText(snippet(content, title));
            if (!content.isBlank() && (p.getIntroduction() == null || p.getIntroduction().isBlank())) {
                p.setIntroduction(content);
            }
            if (ex.publishedAt() != null) p.setPublishedAt(ex.publishedAt());
            papers.save(p);
            log.info("BW: healed title for {}", p.getExternalId());
            return true;
        } catch (Exception e) {
            log.warn("BW: heal failed for {} — {}", p.getExternalId(), e.getMessage());
            return false;
        }
    }

    private static String snippet(String content, String fallback) {
        if (content == null || content.isBlank()) return fallback;
        String trimmed = content.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500).trim() + "…" : trimmed;
    }

    /** True for strings that look like URLs — we never want those as titles. */
    private static boolean looksLikeUrl(String s) {
        if (s == null) return true;
        String low = s.toLowerCase();
        return low.startsWith("http") || low.startsWith("www.") || low.contains("businessweekly.com")
                || low.contains("://") || s.contains("/blog/") || s.contains("/article/");
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
