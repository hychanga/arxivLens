package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.repository.TopicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daily sync for Medium articles via the public RSS endpoint.
 *
 * <p>Each enabled {@link Topic} for the {@code medium} source maps to one
 * Medium RSS feed URL:
 * <ul>
 *   <li>Topic code {@code @username}   → {@code https://medium.com/feed/@username}</li>
 *   <li>Topic code {@code technology}  → {@code https://medium.com/feed/tag/technology}</li>
 *   <li>Topic code {@code tag/ai}      → {@code https://medium.com/feed/tag/ai}</li>
 * </ul>
 *
 * <p>The sync is polite (800 ms between feeds) to avoid hitting Medium's
 * rate-limiter. RSS items give us title, link, description (intro excerpt),
 * pubDate, and author — enough to populate the feed and let users follow the
 * link to the full article.
 */
@Service
public class MediumSyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(MediumSyncService.class);
    private static final String SOURCE_CODE = "medium";
    private static final String MEDIUM_FEED_BASE = "https://medium.com/feed/";

    /** Extracts the hex article id from a Medium article or guid URL. */
    private static final Pattern ARTICLE_ID = Pattern.compile("[/-]([0-9a-f]{8,12})(?:[?#].*)?$");

    private static final long POLITE_SLEEP_MS = 800;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SourceRepository sources;
    private final TopicRepository topics;
    private final PaperRepository papers;

    public MediumSyncService(SourceRepository sources, TopicRepository topics, PaperRepository papers) {
        this.sources = sources;
        this.topics  = topics;
        this.papers  = papers;
    }

    @Override
    public String sourceCode() { return SOURCE_CODE; }

    @Override
    @Transactional
    public SyncResult sync() {
        Optional<Source> srcOpt = sources.findByCode(SOURCE_CODE);
        if (srcOpt.isEmpty()) return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source row missing");
        Source src = srcOpt.get();
        if (!Boolean.TRUE.equals(src.getEnabled())) return new SyncResult(SOURCE_CODE, 0, 0, 0, "Source disabled");

        List<Topic> enabledTopics = topics.findBySourceIdAndEnabledTrue(src.getId());
        if (enabledTopics.isEmpty()) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0,
                    "No enabled topics — add topics in Admin (e.g. 'technology', '@username')");
        }

        int fetched = 0, inserted = 0, skipped = 0, errored = 0;
        boolean first = true;

        for (Topic topic : enabledTopics) {
            if (!first && sleepOrInterrupted()) { errored++; break; }
            first = false;

            String feedUrl = toFeedUrl(topic.getCode());
            log.info("Medium: fetching RSS for topic '{}' from {}", topic.getCode(), feedUrl);

            List<RssItem> items;
            try {
                String xml = fetchXml(feedUrl);
                items = parseRss(xml);
            } catch (Exception e) {
                log.warn("Medium: RSS fetch/parse failed for topic '{}': {}", topic.getCode(), e.getMessage());
                errored++;
                continue;
            }

            for (RssItem item : items) {
                fetched++;
                String externalId = "medium-" + item.articleId();
                if (externalId.equals("medium-")) { errored++; continue; }

                if (papers.findBySourceIdAndExternalId(src.getId(), externalId).isPresent()) {
                    skipped++;
                    continue;
                }

                try {
                    papers.save(buildPaper(src, topic, externalId, item));
                    inserted++;
                } catch (Exception e) {
                    log.warn("Medium: save failed for '{}': {}", item.link(), e.getMessage());
                    errored++;
                }
            }
        }

        log.info("Medium sync: fetched={} inserted={} skipped={} errored={}", fetched, inserted, skipped, errored);
        return new SyncResult(SOURCE_CODE, fetched, inserted, skipped,
                errored > 0 ? errored + " item(s) errored — see warn logs" : null);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static String toFeedUrl(String topicCode) {
        if (topicCode == null) return MEDIUM_FEED_BASE;
        String code = topicCode.trim();
        // Already a full path segment: @user, tag/x, or publication slug
        if (code.startsWith("@") || code.startsWith("tag/") || code.contains("/")) {
            return MEDIUM_FEED_BASE + code;
        }
        // Plain word → treat as Medium tag
        return MEDIUM_FEED_BASE + "tag/" + code;
    }

    private Paper buildPaper(Source src, Topic topic, String externalId, RssItem item) {
        Paper p = new Paper();
        p.setSource(src);
        p.setSourceId(src.getId());
        p.setExternalId(externalId);
        p.setTitle(item.title().isBlank() ? "(untitled)" : item.title());
        p.setAuthorsJson(item.author().isBlank() ? "[]" : "[\"" + item.author().replace("\"", "'") + "\"]");
        String body = stripHtml(item.description());
        p.setAbstractText(snippet(body, item.title()));
        p.setIntroduction(body.isBlank() ? null : body);
        p.setUrl(item.link());
        p.setPdfUrl(null);
        p.setTopicCode(topic.getCode());
        p.setPublishedAt(item.pubDate() != null ? item.pubDate() : Instant.now());
        return p;
    }

    // ── RSS parsing ────────────────────────────────────────────────────────

    private String fetchXml(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ArxivLens-Bot/1.0 (+https://arxivlens.vercel.app)")
                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9")
                .GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2)
            throw new RuntimeException("HTTP " + res.statusCode() + " from " + url);
        return new String(res.body(), StandardCharsets.UTF_8);
    }

    private List<RssItem> parseRss(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        NodeList itemNodes = doc.getElementsByTagName("item");
        return new java.util.ArrayList<>() {{
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element el = (Element) itemNodes.item(i);
                String title  = text(el, "title");
                String link   = text(el, "link");
                String guid   = text(el, "guid");
                String desc   = firstNonBlank(text(el, "content:encoded"), text(el, "description"));
                String author = firstNonBlank(text(el, "dc:creator"), text(el, "author"));
                Instant pub   = parseDate(text(el, "pubDate"));
                String id     = extractId(firstNonBlank(guid, link));
                add(new RssItem(title.trim(), link.trim(), desc, author.trim(), pub, id));
            }
        }};
    }

    private record RssItem(String title, String link, String description, String author, Instant pubDate, String articleId) {}

    // ── small utilities ────────────────────────────────────────────────────

    private static String text(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null ? b : "");
    }

    private static String extractId(String url) {
        if (url == null || url.isBlank()) return "";
        Matcher m = ARTICLE_ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static final DateTimeFormatter RSS_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

    private static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return ZonedDateTime.parse(raw.trim(), RSS_DATE).toInstant(); } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Strip HTML tags and collapse whitespace. */
    static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&amp;",  "&")
                .replaceAll("&lt;",   "<")
                .replaceAll("&gt;",   ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;",  "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String snippet(String content, String fallback) {
        if (content == null || content.isBlank()) return fallback;
        return content.length() > 500 ? content.substring(0, 500).trim() + "…" : content;
    }

    private boolean sleepOrInterrupted() {
        try { Thread.sleep(POLITE_SLEEP_MS); return false; }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return true; }
    }
}
