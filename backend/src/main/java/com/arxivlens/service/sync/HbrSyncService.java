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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls HBR articles from per-topic RSS feeds.
 *
 * RSS URL pattern: https://hbr.org/topic/{slug}/feed (ATOM/RSS hybrid).
 * If a topic feed 404s we just skip it; the rest of the batch still completes.
 */
@Service
public class HbrSyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(HbrSyncService.class);
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final SourceRepository sources;
    private final TopicRepository topics;
    private final PaperRepository papers;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HbrSyncService(SourceRepository sources, TopicRepository topics, PaperRepository papers) {
        this.sources = sources;
        this.topics = topics;
        this.papers = papers;
    }

    @Override
    public String sourceCode() {
        return "hbr";
    }

    @Override
    @Transactional
    public SyncResult sync() {
        Source src = sources.findByCode(sourceCode()).orElse(null);
        if (src == null)                              return new SyncResult(sourceCode(), 0, 0, 0, "source not found");
        if (!Boolean.TRUE.equals(src.getEnabled()))   return new SyncResult(sourceCode(), 0, 0, 0, "source disabled");

        List<Topic> activeTopics = topics.findBySourceIdAndEnabledTrue(src.getId());
        int fetched = 0, inserted = 0, skipped = 0;
        StringBuilder errors = new StringBuilder();

        for (Topic t : activeTopics) {
            try {
                List<Paper> parsed = fetchTopicFeed(t, src.getId());
                fetched += parsed.size();
                for (Paper p : parsed) {
                    if (papers.findBySourceIdAndExternalId(p.getSourceId(), p.getExternalId()).isPresent()) {
                        skipped++;
                    } else {
                        p.setSource(src);
                        papers.save(p);
                        inserted++;
                    }
                }
            } catch (Exception ex) {
                log.warn("HBR topic '{}' failed: {}", t.getCode(), ex.getMessage());
                if (errors.length() > 0) errors.append("; ");
                errors.append(t.getCode()).append(": ").append(ex.getClass().getSimpleName());
            }
        }
        String err = errors.length() == 0 ? null : errors.toString();
        return new SyncResult(sourceCode(), fetched, inserted, skipped, err);
    }

    private List<Paper> fetchTopicFeed(Topic topic, Long sourceId) throws Exception {
        String url = "https://hbr.org/topic/" + topic.getCode() + "/feed";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return parseRss(res.body(), sourceId, topic.getCode());
    }

    private List<Paper> parseRss(byte[] xml, Long sourceId, String topicCode) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        NodeList items = doc.getElementsByTagName("item");
        List<Paper> out = new ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            String guid = text(item, "guid");
            String pubDate = text(item, "pubDate");
            String description = text(item, "description");
            String dcCreator = text(item, "dc:creator");

            Instant published = Instant.now();
            if (pubDate != null && !pubDate.isBlank()) {
                try {
                    published = ZonedDateTime.parse(pubDate, RFC1123).toInstant();
                } catch (Exception ignored) { /* fall back to now */ }
            }

            Paper p = new Paper();
            p.setSourceId(sourceId);
            p.setExternalId(guid == null || guid.isBlank() ? slugify(link) : guid);
            p.setTitle(title == null ? "(untitled)" : title);
            p.setAuthorsJson(ArxivSyncService.toJsonStringArray(
                    dcCreator == null || dcCreator.isBlank() ? List.of() : List.of(dcCreator.trim())));
            p.setAbstractText(description == null ? "" : stripHtml(description));
            p.setUrl(link);
            p.setPdfUrl(null);
            p.setTopicCode(topicCode);
            p.setPublishedAt(published);
            out.add(p);
        }
        return out;
    }

    private static String text(Element parent, String name) {
        NodeList nl = parent.getElementsByTagName(name);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    private static String slugify(String url) {
        if (url == null) return "hbr-" + System.nanoTime();
        return url.replaceFirst("^https?://[^/]+/", "")
                .replaceAll("[^a-zA-Z0-9_-]+", "-");
    }

    private static String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }
}
