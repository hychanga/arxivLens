package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SettingRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.repository.TopicRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pulls recent entries from arXiv's Atom feed for the source's enabled topics.
 *
 * Uses only JDK XML APIs to avoid dragging in another XML lib.
 * The arXiv API rate limit is generous; we still cap {@code max_results} via settings.
 */
@Service
public class ArxivSyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(ArxivSyncService.class);
    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final String ARXIV_NS = "http://arxiv.org/schemas/atom";

    private final SourceRepository sources;
    private final TopicRepository topics;
    private final PaperRepository papers;
    private final SettingRepository settings;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ArxivSyncService(SourceRepository sources,
                            TopicRepository topics,
                            PaperRepository papers,
                            SettingRepository settings) {
        this.sources = sources;
        this.topics = topics;
        this.papers = papers;
        this.settings = settings;
    }

    @Override
    public String sourceCode() {
        return "arxiv";
    }

    @Override
    @Transactional
    public SyncResult sync() {
        Source src = sources.findByCode(sourceCode()).orElse(null);
        if (src == null) {
            return new SyncResult(sourceCode(), 0, 0, 0, "source not found");
        }
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            return new SyncResult(sourceCode(), 0, 0, 0, "source disabled");
        }
        List<Topic> activeTopics = topics.findBySourceIdAndEnabledTrue(src.getId());
        if (activeTopics.isEmpty()) {
            return new SyncResult(sourceCode(), 0, 0, 0, "no enabled topics");
        }

        int max = settings.findById(1L).map(s -> s.getMaxResultsPerSync()).orElse(50);
        String search = buildSearchExpr(activeTopics);

        try {
            List<Paper> parsed = fetchPage(search, 0, Math.min(200, Math.max(1, max)), src.getId());
            int[] counts = upsertAll(parsed, src);
            return new SyncResult(sourceCode(), parsed.size(), counts[0], counts[1], null);
        } catch (Exception ex) {
            log.warn("arXiv sync failed", ex);
            return new SyncResult(sourceCode(), 0, 0, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * Historical backfill — one query per calendar month with a
     * {@code submittedDate:[start TO end]} range filter, so each of the last
     * {@code months} buckets receives data even when the source is a firehose.
     *
     * <p>Why per-month and not "page through firehose": cs.* categories receive
     * 50–100 submissions/day each, so 5 enabled topics × ~400/day means a
     * naive {@code start=0, 200, 400, ...} loop bottoms out after a few
     * thousand entries that all live within the last week. Trends needs older
     * buckets populated, which only the date-range filter guarantees.
     *
     * <p>Each month is capped at {@link #BACKFILL_PER_MONTH} so dense months
     * don't dominate; for a 12-month backfill that's ≤2400 inserts total. The
     * 3-second pause between requests honors arXiv's polite-use guidance.
     * Total wall time ≈ {@code months × ~5s} (~60s for 12 months).
     */
    @Override
    public SyncResult backfill(int months) {
        Source src = sources.findByCode(sourceCode()).orElse(null);
        if (src == null) {
            return new SyncResult(sourceCode(), 0, 0, 0, "source not found");
        }
        if (!Boolean.TRUE.equals(src.getEnabled())) {
            return new SyncResult(sourceCode(), 0, 0, 0, "source disabled");
        }
        List<Topic> activeTopics = topics.findBySourceIdAndEnabledTrue(src.getId());
        if (activeTopics.isEmpty()) {
            return new SyncResult(sourceCode(), 0, 0, 0, "no enabled topics");
        }

        String topicExpr = "(" + buildSearchExpr(activeTopics) + ")";
        YearMonth current = YearMonth.now(ZoneOffset.UTC);

        int totalParsed = 0, totalInserted = 0, totalSkipped = 0;
        StringBuilder errors = new StringBuilder();

        for (int back = 0; back < Math.max(1, months); back++) {
            YearMonth target = current.minusMonths(back);
            String start = target.atDay(1).format(YYYYMMDD) + "0000";
            String end = target.atEndOfMonth().format(YYYYMMDD) + "2359";
            // %5B / %5D = url-encoded brackets; raw [ ] is rejected by HttpClient's URI parser.
            String monthQuery = topicExpr + "+AND+submittedDate:%5B" + start + "+TO+" + end + "%5D";

            List<Paper> parsed;
            try {
                parsed = fetchPage(monthQuery, 0, BACKFILL_PER_MONTH, src.getId());
            } catch (Exception ex) {
                log.warn("arXiv backfill month {} failed: {}", target, ex.getMessage());
                if (errors.length() > 0) errors.append("; ");
                errors.append(target).append(": ").append(ex.getClass().getSimpleName());
                continue;
            }

            totalParsed += parsed.size();
            int[] counts = upsertEachInOwnTx(parsed, src);
            totalInserted += counts[0];
            totalSkipped += counts[1];
            log.info("arXiv backfill {} → parsed={} inserted={} skipped={}",
                    target, parsed.size(), counts[0], counts[1]);

            // arXiv asks for >= 3s between programmatic requests.
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        String err = errors.length() == 0 ? null : errors.toString();
        log.info("arXiv backfill complete: parsed={} inserted={} skipped={}", totalParsed, totalInserted, totalSkipped);
        return new SyncResult(sourceCode(), totalParsed, totalInserted, totalSkipped, err);
    }

    @Override
    public boolean supportsBackfill() {
        return true;
    }

    /** Per-month cap. Dense categories (cs.AI ≈ 2000/mo) get sampled rather than fully ingested. */
    private static final int BACKFILL_PER_MONTH = 200;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static String buildSearchExpr(List<Topic> activeTopics) {
        return activeTopics.stream()
                .map(t -> "cat:" + t.getCode())
                .collect(Collectors.joining("+OR+"));
    }

    private List<Paper> fetchPage(String search, int start, int maxResults, Long sourceId) throws Exception {
        String url = "https://export.arxiv.org/api/query"
                + "?search_query=" + search
                + "&start=" + start
                + "&max_results=" + maxResults
                + "&sortBy=submittedDate&sortOrder=descending";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return parseAtom(res.body(), sourceId);
    }

    /** Upserts within the current transaction. Returns [inserted, skipped]. */
    private int[] upsertAll(List<Paper> parsed, Source src) {
        int inserted = 0, skipped = 0;
        for (Paper p : parsed) {
            if (papers.findBySourceIdAndExternalId(p.getSourceId(), p.getExternalId()).isPresent()) {
                skipped++;
            } else {
                p.setSource(src);
                papers.save(p);
                inserted++;
            }
        }
        return new int[]{inserted, skipped};
    }

    /**
     * For the backfill loop: each row in its own short transaction so partial
     * progress survives if the loop is interrupted mid-page. The page itself
     * is already in memory, so the cost is just the per-insert tx overhead
     * (acceptable: backfill is sleep-bound, not DB-bound).
     */
    private int[] upsertEachInOwnTx(List<Paper> parsed, Source src) {
        int inserted = 0, skipped = 0;
        for (Paper p : parsed) {
            try {
                if (papers.findBySourceIdAndExternalId(p.getSourceId(), p.getExternalId()).isPresent()) {
                    skipped++;
                } else {
                    p.setSource(src);
                    papers.save(p);
                    inserted++;
                }
            } catch (Exception ex) {
                log.warn("Backfill upsert {} failed: {}", p.getExternalId(), ex.getMessage());
                skipped++;
            }
        }
        return new int[]{inserted, skipped};
    }

    private List<Paper> parseAtom(byte[] xml, Long sourceId) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Defensive: disable external entities to avoid XXE.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList entries = doc.getElementsByTagNameNS(ATOM_NS, "entry");
        List<Paper> out = new ArrayList<>(entries.getLength());

        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = textNS(e, ATOM_NS, "id");           // e.g. http://arxiv.org/abs/2501.00001v2
            String externalId = trimArxivId(id);
            String title = collapse(textNS(e, ATOM_NS, "title"));
            String summary = collapse(textNS(e, ATOM_NS, "summary"));
            String pubStr = textNS(e, ATOM_NS, "published");
            Instant published = (pubStr == null || pubStr.isBlank())
                    ? Instant.now()
                    : OffsetDateTime.parse(pubStr).toInstant();

            // Authors
            List<String> authors = new ArrayList<>();
            NodeList authorNodes = e.getElementsByTagNameNS(ATOM_NS, "author");
            for (int j = 0; j < authorNodes.getLength(); j++) {
                String n = textNS((Element) authorNodes.item(j), ATOM_NS, "name");
                if (n != null && !n.isBlank()) authors.add(collapse(n));
            }

            // Primary category (arXiv extension)
            String topicCode = null;
            NodeList primary = e.getElementsByTagNameNS(ARXIV_NS, "primary_category");
            if (primary.getLength() > 0) {
                topicCode = ((Element) primary.item(0)).getAttribute("term");
            }

            // PDF link
            String pdfUrl = null;
            String htmlUrl = null;
            NodeList links = e.getElementsByTagNameNS(ATOM_NS, "link");
            for (int j = 0; j < links.getLength(); j++) {
                Element link = (Element) links.item(j);
                String type = link.getAttribute("type");
                String rel = link.getAttribute("rel");
                String href = link.getAttribute("href");
                if ("application/pdf".equals(type)) pdfUrl = href;
                else if ("alternate".equals(rel)) htmlUrl = href;
            }

            Paper p = new Paper();
            p.setSourceId(sourceId);
            p.setExternalId(externalId);
            p.setTitle(title == null ? "(untitled)" : title);
            p.setAuthorsJson(toJsonStringArray(authors));
            p.setAbstractText(summary == null ? "" : summary);
            p.setUrl(htmlUrl);
            p.setPdfUrl(pdfUrl);
            p.setTopicCode(topicCode);
            p.setPublishedAt(published);
            out.add(p);
        }
        return out;
    }

    private static String textNS(Element parent, String ns, String name) {
        NodeList nl = parent.getElementsByTagNameNS(ns, name);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return n.getTextContent();
    }

    private static String collapse(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String trimArxivId(String idUrl) {
        if (idUrl == null) return "";
        String s = idUrl.replaceFirst("^.*?/abs/", "");
        return s.replaceAll("v\\d+$", "");
    }

    static String toJsonStringArray(List<String> values) {
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

    /** package-private for charset reference (kept to avoid import warning). */
    @SuppressWarnings("unused")
    private static final java.nio.charset.Charset CS = StandardCharsets.UTF_8;
}
