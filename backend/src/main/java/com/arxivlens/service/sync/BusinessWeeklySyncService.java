package com.arxivlens.service.sync;

import com.arxivlens.config.AppProperties;
import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes the public search-results page on businessweekly.com.tw for a fixed
 * keyword (default: {@code 林裕森}) and upserts each hit as a {@link Paper}.
 *
 * <p>This is a "search feed" source — there is no per-topic taxonomy and no
 * RSS, so we rely on the search page's HTML cards. The parser is deliberately
 * permissive: it captures every internal article link, then per-link extracts
 * the title (anchor text minus tags) and any nearby publish date.
 *
 * <p>Pagination: the search page supports {@code &page=N}. We iterate until
 * either we hit {@link #MAX_PAGES} or a page yields no new articles.
 */
@Service
public class BusinessWeeklySyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusinessWeeklySyncService.class);
    private static final String SOURCE_CODE = "businessweekly";
    private static final String BASE = "https://www.businessweekly.com.tw";
    private static final int MAX_PAGES = 10;

    /**
     * Fallback search keyword when {@code app.business-weekly.search-keyword} is
     * unset or empty. Hardcoded in source (which javac reads as UTF-8) rather
     * than as a properties default because some Spring Boot setups still load
     * {@code .properties} files as Latin-1, mojibaking raw CJK defaults.
     */
    private static final String DEFAULT_KEYWORD = "林裕森";

    private static final Pattern ARTICLE_ANCHOR = Pattern.compile(
            "<a\\b[^>]*href=[\"'](/[a-z\\-]+/(?:blog|article)/\\d+)[\"'][^>]*>([\\s\\S]{1,4000}?)</a>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})");

    private static final Pattern CHINESE_DATE = Pattern.compile(
            "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    private static final Pattern STRIP_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0]+");

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
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source row missing — restart so data.sql / SchemaBootstrap can seed it.");
        }
        Source src = srcOpt.get();
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source disabled.");
        }

        String configured = props.businessWeekly() == null ? null : props.businessWeekly().searchKeyword();
        String keyword = (configured == null || configured.isBlank()) ? DEFAULT_KEYWORD : configured;
        log.info("Business Weekly sync using keyword='{}' (env override={})",
                keyword, configured != null && !configured.isBlank());

        int fetched = 0, inserted = 0, skipped = 0;
        int emptyPages = 0;
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                String url = BASE + "/Search?keyword="
                        + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                        + (page == 1 ? "" : "&page=" + page);
                String html = fetchHtml(url);
                Map<String, ArticleHit> hits = parseHits(html);
                if (hits.isEmpty()) {
                    if (++emptyPages >= 2) break;
                    continue;
                }
                emptyPages = 0;

                int pageNew = 0;
                for (ArticleHit h : hits.values()) {
                    fetched++;
                    String externalId = "bw-" + h.path.replaceFirst("^/", "").replace('/', '-');
                    Optional<Paper> existing = papers.findBySourceIdAndExternalId(src.getId(), externalId);
                    if (existing.isPresent()) {
                        skipped++;
                        continue;
                    }
                    Paper p = new Paper();
                    p.setSource(src);
                    p.setSourceId(src.getId());
                    p.setExternalId(externalId);
                    p.setTitle(h.title);
                    p.setAuthorsJson("[]");
                    p.setAbstractText(h.snippet == null || h.snippet.isBlank() ? h.title : h.snippet);
                    p.setIntroduction(null);
                    p.setUrl(BASE + h.path);
                    p.setPdfUrl(null);
                    p.setTopicCode(null);
                    p.setPublishedAt(h.publishedAt != null ? h.publishedAt : Instant.now());
                    papers.save(p);
                    inserted++;
                    pageNew++;
                }
                log.info("Business Weekly page {}: {} hits, {} new, {} duplicates",
                        page, hits.size(), pageNew, hits.size() - pageNew);
                if (pageNew == 0) break;
            }
            return new SyncResult(SOURCE_CODE, fetched, inserted, skipped, null);
        } catch (Exception e) {
            log.warn("Business Weekly sync failed", e);
            return new SyncResult(SOURCE_CODE, fetched, inserted, skipped,
                    "Business Weekly fetch failed: " + e.getMessage());
        }
    }

    static Map<String, ArticleHit> parseHits(String html) {
        Map<String, ArticleHit> out = new LinkedHashMap<>();
        if (html == null || html.isBlank()) return out;
        Matcher m = ARTICLE_ANCHOR.matcher(html);
        while (m.find()) {
            String path = m.group(1);
            String inner = m.group(2);
            if (out.containsKey(path)) continue;

            String stripped = WHITESPACE.matcher(STRIP_TAGS.matcher(inner).replaceAll(" ")).replaceAll(" ").trim();
            if (stripped.isEmpty()) continue;

            String title = pickTitle(stripped);
            if (title.isEmpty()) continue;

            Instant published = pickDate(stripped);
            String snippet = stripped.length() > 280 ? stripped.substring(0, 280).trim() + "…" : stripped;
            out.put(path, new ArticleHit(path, title, snippet, published));
        }
        return out;
    }

    private static String pickTitle(String stripped) {
        String[] chunks = stripped.split("[|｜·\\u2022]|\\s{3,}");
        String best = "";
        for (String c : chunks) {
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (NUMERIC_DATE.matcher(t).matches() || CHINESE_DATE.matcher(t).matches()) continue;
            if (t.length() < 6) continue;
            if (t.length() > best.length()) best = t;
        }
        if (best.length() > 200) best = best.substring(0, 200).trim() + "…";
        return best;
    }

    private static Instant pickDate(String text) {
        Matcher m = NUMERIC_DATE.matcher(text);
        if (m.find()) return toInstant(m.group(1), m.group(2), m.group(3));
        Matcher c = CHINESE_DATE.matcher(text);
        if (c.find()) return toInstant(c.group(1), c.group(2), c.group(3));
        return null;
    }

    private static Instant toInstant(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
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

    record ArticleHit(String path, String title, String snippet, Instant publishedAt) {}

    @SuppressWarnings("unused")
    private static List<String> unusedRef() { return new ArrayList<>(); }
}
