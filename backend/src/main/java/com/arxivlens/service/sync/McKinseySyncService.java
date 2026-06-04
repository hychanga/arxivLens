package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Daily auto-sync for McKinsey Quarterly, fed by McKinsey's public RSS feed
 * ({@code https://www.mckinsey.com/insights/rss} — "McKinsey Insights &
 * Publications", the feed that surfaces Quarterly articles alongside the rest
 * of McKinsey's published insights).
 *
 * <p>Unlike {@link HbrSyncService}, this source does <em>not</em> fetch each
 * article page for its body: McKinsey's article HTML is behind an Akamai bot
 * wall that resets datacenter connections (the RSS endpoint is the only thing
 * that answers a server-side {@link HttpClient} request reliably). So each
 * {@link Paper} is built entirely from the RSS item — title, summary
 * ({@code <description>}), canonical URL, and publish date. The saved row points
 * {@code Paper.url} at the mckinsey.com article so the favorites "Open source"
 * button works; subscribers who want the full text read it there.
 *
 * <p>The feed carries no historical depth (just the latest ~50 items), so this
 * source reports {@link #supportsBackfill()} {@code false} — same as HBR.
 */
@Service
public class McKinseySyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(McKinseySyncService.class);
    private static final String SOURCE_CODE = "mckinsey";

    /**
     * McKinsey's only server-reachable feed. There is no Quarterly-only feed
     * (every {@code /quarterly/…/rss} path 404s), so we pull the umbrella
     * Insights feed; Quarterly articles arrive in it like any other insight.
     */
    private static final String FEED_URL = "https://www.mckinsey.com/insights/rss";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SourceRepository sources;
    private final PaperRepository papers;

    public McKinseySyncService(SourceRepository sources, PaperRepository papers) {
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

        byte[] xml;
        try {
            xml = fetch(FEED_URL);
        } catch (Exception e) {
            log.warn("McKinsey RSS fetch failed: {}", e.getMessage());
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Feed fetch failed: " + e.getMessage());
        }

        List<Item> items;
        try {
            items = parseItems(xml);
        } catch (Exception e) {
            log.warn("McKinsey RSS parse failed: {}", e.getMessage());
            return new SyncResult(SOURCE_CODE, 0, 0, 0, "Feed parse failed: " + e.getMessage());
        }
        log.info("McKinsey: feed yielded {} item(s)", items.size());
        if (items.isEmpty()) {
            return new SyncResult(SOURCE_CODE, 0, 0, 0,
                    "Feed returned no items — RSS format may have changed.");
        }

        // Idempotent upsert: one batched existence check, then insert only the
        // unseen items (mirrors the arXiv sync's IN-query approach rather than a
        // find-per-item round-trip).
        List<String> externalIds = new ArrayList<>(items.size());
        for (Item it : items) externalIds.add(it.externalId);
        java.util.Set<String> existing = new java.util.HashSet<>(
                papers.findExistingExternalIds(src.getId(), externalIds));

        int fetched = items.size(), inserted = 0, skipped = 0, errored = 0;
        List<Paper> toInsert = new ArrayList<>();
        for (Item it : items) {
            if (existing.contains(it.externalId)) {
                skipped++;
                continue;
            }
            try {
                toInsert.add(buildPaper(src, it));
                inserted++;
            } catch (Exception ex) {
                log.warn("McKinsey ingest failed for {}: {}", it.link, ex.getMessage());
                errored++;
            }
        }
        if (!toInsert.isEmpty()) papers.saveAll(toInsert);

        log.info("McKinsey sync: fetched={} inserted={} skipped={} errored={}",
                fetched, inserted, skipped, errored);
        return new SyncResult(SOURCE_CODE, fetched, inserted, skipped,
                errored > 0 ? errored + " item(s) errored — see warn logs" : null);
    }

    private Paper buildPaper(Source src, Item it) {
        Paper p = new Paper();
        p.setSource(src);
        p.setSourceId(src.getId());
        p.setExternalId(it.externalId);
        p.setTitle(truncate(it.title, 512));
        p.setAuthorsJson("[]");
        p.setAbstractText(it.description != null && !it.description.isBlank() ? it.description : it.title);
        // No body: McKinsey article pages block server-side fetches (see class
        // javadoc), so there is nothing richer than the RSS summary to store.
        p.setIntroduction(null);
        p.setUrl(truncate(it.link, 512));
        p.setPdfUrl(null);
        p.setTopicCode(null);
        p.setPublishedAt(it.publishedAt != null ? it.publishedAt : Instant.now());
        return p;
    }

    // ---------- RSS parsing ----------

    /** One RSS {@code <item>}, reduced to the fields a Paper needs. */
    private record Item(String externalId, String title, String description, String link, Instant publishedAt) {}

    List<Item> parseItems(byte[] xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Defensive: disable external entities to avoid XXE.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        NodeList itemNodes = doc.getElementsByTagName("item");
        // Dedupe within a single feed by externalId — RSS occasionally repeats a
        // guid, and a LinkedHashMap keeps first-seen order while collapsing dupes.
        Map<String, Item> out = new LinkedHashMap<>();
        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element e = (Element) itemNodes.item(i);
            String title = collapse(text(e, "title"));
            String link = collapse(text(e, "link"));
            String guid = collapse(text(e, "guid"));
            String description = collapse(text(e, "description"));
            Instant published = parseDate(text(e, "pubDate"));

            // Stable id: prefer the feed's guid (a UUID), fall back to the link.
            String key = (guid != null && !guid.isBlank()) ? guid : link;
            if (key == null || key.isBlank() || title == null || title.isBlank() || link == null) {
                continue; // unusable item — no stable id, title, or URL
            }
            String externalId = "mckinsey-" + key;
            out.putIfAbsent(externalId, new Item(externalId, title, description, link, published));
        }
        return new ArrayList<>(out.values());
    }

    private static String text(Element parent, String name) {
        NodeList nl = parent.getElementsByTagName(name);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return n.getTextContent();
    }

    private static String collapse(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Full RFC-1123 form, e.g. {@code Tue, 01 Sep 2015 08:44:43 GMT}. */
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    /** Date-only form McKinsey usually emits, e.g. {@code Wed, 03 Jun 2026}. */
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH);

    /**
     * McKinsey's {@code <pubDate>} is usually a date with no time
     * ({@code "Wed, 03 Jun 2026"}) but a few legacy items carry the full
     * RFC-1123 timestamp with a zone. Try the full form first, then the
     * date-only form (anchored to UTC midnight), then give up so the caller
     * falls back to {@code now}.
     */
    static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return ZonedDateTime.parse(s, RFC_1123).toInstant();
        } catch (Exception ignored) {
            // fall through to date-only
        }
        try {
            return LocalDate.parse(s, DATE_ONLY).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                // McKinsey's edge 403s an empty/obviously-bot UA; a normal browser
                // UA gets the feed served. (The article HTML still blocks us — only
                // the RSS endpoint is reachable this way.)
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + " from " + url);
        }
        return res.body();
    }
}
