package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.MonthlyTopicCount;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.repository.MonthlyTopicCountRepository;
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
    private final MonthlyTopicCountRepository monthlyCounts;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ArxivSyncService(SourceRepository sources,
                            TopicRepository topics,
                            PaperRepository papers,
                            SettingRepository settings,
                            MonthlyTopicCountRepository monthlyCounts) {
        this.sources = sources;
        this.topics = topics;
        this.papers = papers;
        this.settings = settings;
        this.monthlyCounts = monthlyCounts;
    }

    @Override
    public String sourceCode() {
        return "arxiv";
    }

    /**
     * Default lookback applied to a topic that has never been synced before
     * ({@code lastSyncedAt == null}). Past this window, the topic looks
     * "fresh" and we'd be paying for arXiv queries against years of data
     * that the user almost certainly doesn't care about on first run.
     */
    private static final Duration INITIAL_LOOKBACK = Duration.ofDays(7);

    /**
     * Safety ceiling on how many pages we'll pull for a single topic before
     * bailing — guards against runaway loops if arXiv ever returns infinite
     * results. {@code 50 × perTopicMax(2000) = 100k} papers per topic per
     * sync, which is more than every cs.* category accumulates in a year.
     */
    private static final int MAX_PAGES_PER_TOPIC = 50;

    /**
     * arXiv asks programmatic clients to pause ≥3s between requests; we make
     * one query per enabled topic now, so 3s × N for N topics. Honour it
     * with this sleep between iterations.
     */
    private static final long POLITE_SLEEP_MS = 3000;

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
        // arXiv API accepts up to 2000 results per single query — that's the
        // hard ceiling. The user-configured max_results_per_sync sits below
        // that and the Math.max guard rejects pathological 0 / negative.
        int perTopicMax = Math.min(2000, Math.max(1, max));
        Instant syncStart = Instant.now();

        int totalFetched = 0, totalInserted = 0, totalSkipped = 0;
        StringBuilder errors = new StringBuilder();

        // Per-topic incremental sync. Categories that haven't accumulated new
        // arXiv submissions since lastSyncedAt yield a zero-result query and
        // we skip straight past — no wasted work re-downloading rows we
        // already have. Topics that ARE new always pull a bounded window
        // (INITIAL_LOOKBACK) so the first sync after enabling a topic
        // doesn't try to scan all of arXiv's history.
        for (int i = 0; i < activeTopics.size(); i++) {
            Topic topic = activeTopics.get(i);
            Instant since = topic.getLastSyncedAt() != null
                    ? topic.getLastSyncedAt()
                    : syncStart.minus(INITIAL_LOOKBACK);

            try {
                String query = buildTopicRangeQuery(topic.getCode(), since, syncStart);
                // Paginate until either the upstream returns less than a
                // full page (we've drained the window) or we hit the
                // per-topic page safety cap. Without this, a deep window
                // that contains more than perTopicMax papers had its tail
                // silently dropped — and once lastSyncedAt advanced past
                // syncStart, those dropped rows were unreachable on every
                // subsequent run, leaving permanent holes in the data set.
                int topicFetched = 0, topicInserted = 0, topicSkipped = 0;
                int pageStart = 0;
                int pagesPulled = 0;
                while (pagesPulled < MAX_PAGES_PER_TOPIC) {
                    List<Paper> page = fetchPage(query, pageStart, perTopicMax, src.getId());
                    pagesPulled++;
                    int got = page.size();
                    int[] counts = upsertAll(page, src);
                    topicFetched += got;
                    topicInserted += counts[0];
                    topicSkipped += counts[1];
                    if (got < perTopicMax) break; // last page — window drained.
                    pageStart += perTopicMax;
                    // Polite pause between successive pages of the same topic.
                    // Smaller than the inter-topic sleep because we're still
                    // inside one logical query for arXiv's rate-limit purposes.
                    if (sleep1sOrInterrupted()) break;
                }
                totalFetched += topicFetched;
                totalInserted += topicInserted;
                totalSkipped += topicSkipped;

                topic.setLastSyncedAt(syncStart);
                topics.save(topic);
                log.info("arXiv sync [{}]: pages={} fetched={} inserted={} skipped={} since={}",
                        topic.getCode(), pagesPulled, topicFetched, topicInserted, topicSkipped, since);
            } catch (Exception ex) {
                log.warn("arXiv sync [{}] failed: {}", topic.getCode(), ex.getMessage());
                if (errors.length() > 0) errors.append("; ");
                errors.append(topic.getCode()).append(":").append(ex.getClass().getSimpleName());
            }

            // Polite pause between topics, but only when there's another one
            // coming — no need to sleep after the final query.
            if (i < activeTopics.size() - 1 && sleep3sOrInterrupted()) {
                if (errors.length() > 0) errors.append("; ");
                errors.append("interrupted");
                break;
            }
        }

        String err = errors.length() == 0 ? null : errors.toString();
        return new SyncResult(sourceCode(), totalFetched, totalInserted, totalSkipped, err);
    }

    /**
     * Builds a single-topic + date-range query keyed off {@code lastUpdatedDate}
     * rather than {@code submittedDate}.
     *
     * <p>Why lastUpdatedDate: arXiv only "announces" new submissions on a
     * lagging schedule (Sun-Thu evenings US Eastern, with a 14:00 ET cutoff).
     * In practice papers freshly submitted today don't appear in the API for
     * 1-3 days, so a {@code submittedDate}-keyed sync that runs right after
     * lunch can come back with nothing newer than last Friday's submissions
     * — even though those papers were technically inserted into the index
     * over the weekend. Filtering on {@code lastUpdatedDate} catches every
     * paper whose record changed in the window (new submissions, revisions,
     * cross-list re-announcements), which is the freshness signal users
     * actually care about.
     */
    private static String buildTopicRangeQuery(String topicCode, Instant since, Instant until) {
        String start = ARXIV_TIMESTAMP.format(since.atOffset(ZoneOffset.UTC));
        String end = ARXIV_TIMESTAMP.format(until.atOffset(ZoneOffset.UTC));
        return "cat:" + topicCode
                + "+AND+lastUpdatedDate:%5B" + start + "+TO+" + end + "%5D";
    }

    private static final DateTimeFormatter ARXIV_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /**
     * Historical backfill — populates {@link MonthlyTopicCount} with the
     * **real** number of arXiv submissions per (topic, month) using arXiv's
     * {@code <opensearch:totalResults>} field, NOT capped by
     * {@code max_results_per_sync}.
     *
     * <p>Why count-only and not "store full Paper rows": cs.AI alone publishes
     * ~6000 papers/month — storing 5 topics × 12 months × 6k = 360k Paper rows
     * just to power a bar chart is gratuitous on a hobby DB. The chart only
     * needs the counts, and {@code <opensearch:totalResults>} gives us the
     * exact number with a single query (using {@code max_results=1}).
     *
     * <p>Issues one query per (topic, month) pair → 5 topics × 12 months
     * = 60 queries × 3s polite-pause ≈ 180s total. arXiv's rate guidance is
     * "≥3s between programmatic requests", which this honors.
     *
     * <p>The "Latest" feed is unaffected — that path is fed by {@link #sync()},
     * still capped at {@code max_results_per_sync} (which is what that setting
     * was always meant for).
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

        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        long totalCount = 0;
        int totalQueries = 0;
        int totalUpserted = 0;
        StringBuilder errors = new StringBuilder();
        int monthsToFetch = Math.max(1, months);

        outer:
        for (Topic topic : activeTopics) {
            for (int back = 0; back < monthsToFetch; back++) {
                YearMonth target = current.minusMonths(back);
                String start = target.atDay(1).format(YYYYMMDD) + "0000";
                String end = target.atEndOfMonth().format(YYYYMMDD) + "2359";
                // %5B / %5D = url-encoded brackets; raw [ ] is rejected by HttpClient's URI parser.
                String query = "cat:" + topic.getCode()
                        + "+AND+submittedDate:%5B" + start + "+TO+" + end + "%5D";

                long count;
                try {
                    count = fetchTotalResults(query);
                    totalQueries++;
                } catch (Exception ex) {
                    log.warn("arXiv backfill {} {} failed: {}", topic.getCode(), target, ex.getMessage());
                    if (errors.length() > 0) errors.append("; ");
                    errors.append(topic.getCode()).append("@").append(target)
                            .append(":").append(ex.getClass().getSimpleName());
                    // Skip the polite-sleep on failure too — fall through.
                    if (sleep3sOrInterrupted()) break outer;
                    continue;
                }

                upsertMonthlyCount(src.getId(), topic.getCode(), target.toString(), count);
                totalUpserted++;
                totalCount += count;
                log.info("arXiv backfill {} {} → totalResults={}", topic.getCode(), target, count);

                if (sleep3sOrInterrupted()) break outer;
            }
        }

        log.info("arXiv backfill complete: {} queries, {} rows upserted, {} papers counted across all buckets",
                totalQueries, totalUpserted, totalCount);
        String err = errors.length() == 0 ? null : errors.toString();
        // SyncResult fields re-purposed for backfill:
        //   fetched  = total papers counted across all buckets (the headline number)
        //   inserted = count rows upserted
        //   skipped  = always 0 (we upsert, never skip)
        return new SyncResult(sourceCode(),
                totalCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCount,
                totalUpserted, 0, err);
    }

    private boolean sleep3sOrInterrupted() {
        return sleepOrInterrupted(3000);
    }

    /** Shorter pause used between pages of the same topic query. */
    private boolean sleep1sOrInterrupted() {
        return sleepOrInterrupted(1000);
    }

    private boolean sleepOrInterrupted(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private void upsertMonthlyCount(Long sourceId, String topicCode, String yearMonth, long count) {
        MonthlyTopicCount row = monthlyCounts
                .findBySourceIdAndTopicCodeAndYearMonth(sourceId, topicCode, yearMonth)
                .orElseGet(() -> {
                    MonthlyTopicCount n = new MonthlyTopicCount();
                    n.setSourceId(sourceId);
                    n.setTopicCode(topicCode);
                    n.setYearMonth(yearMonth);
                    return n;
                });
        row.setCount(count);
        monthlyCounts.save(row);
    }

    private long fetchTotalResults(String searchQuery) throws Exception {
        String url = "https://export.arxiv.org/api/query"
                + "?search_query=" + searchQuery
                + "&start=0&max_results=1";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        checkArxivStatus(res.statusCode());
        return parseTotalResults(res.body());
    }

    private static final String OPENSEARCH_NS = "http://a9.com/-/spec/opensearch/1.1/";

    private static long parseTotalResults(byte[] xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList nl = doc.getElementsByTagNameNS(OPENSEARCH_NS, "totalResults");
        if (nl.getLength() == 0) return 0;
        String text = nl.item(0).getTextContent();
        if (text == null || text.isBlank()) return 0;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    @Override
    public boolean supportsBackfill() {
        return true;
    }

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * arXiv asks that programmatic clients identify themselves so requests can be
     * rate-limited per-app rather than per-IP. Anonymous requests share a global
     * bucket — much easier to trip 429.
     */
    private static final String USER_AGENT = "arxivLens/0.1 (+https://arxivlens.vercel.app)";

    private List<Paper> fetchPage(String search, int start, int maxResults, Long sourceId) throws Exception {
        // Sort by lastUpdatedDate so freshly revised / re-announced papers
        // rise to the top — matches the query keyed off lastUpdatedDate in
        // buildTopicRangeQuery so the windowed result actually lands in
        // most-recently-active order.
        String url = "https://export.arxiv.org/api/query"
                + "?search_query=" + search
                + "&start=" + start
                + "&max_results=" + maxResults
                + "&sortBy=lastUpdatedDate&sortOrder=descending";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        checkArxivStatus(res.statusCode());
        return parseAtom(res.body(), sourceId);
    }

    /**
     * Distinguishes 429 (rate limit — recoverable, retry after a pause) from
     * generic 4xx/5xx so the caller can surface a useful message to the admin
     * instead of "HTTP 429" with no context.
     */
    private static void checkArxivStatus(int code) {
        if (code == 429) {
            throw new IllegalStateException(
                    "arXiv rate-limit hit (HTTP 429). Wait ~1 minute then retry. "
                            + "If this happens often, run Backfill less aggressively.");
        }
        if (code / 100 != 2) {
            throw new IllegalStateException("HTTP " + code);
        }
    }

    /**
     * Insert every paper in {@code parsed} that isn't already on file, and
     * backfill the {@code categories} column on rows we already have (so a deep
     * resync fills in cross-list data for the historical backlog). Existence is
     * decided by a single batched IN-query per page, NOT a find-per-paper
     * round-trip — at 1000-paper pages and 4000-paper windows that's the
     * difference between ~4 queries and ~4000.
     */
    private int[] upsertAll(List<Paper> parsed, Source src) {
        if (parsed.isEmpty()) return new int[]{0, 0};
        java.util.Map<String, Paper> existing = new java.util.HashMap<>();
        // Chunk the IN-list so we never blow past MySQL / TiDB's
        // max_allowed_packet ceiling even on a full 2000-row page.
        final int CHUNK = 500;
        java.util.List<String> ids = new java.util.ArrayList<>(parsed.size());
        for (Paper p : parsed) ids.add(p.getExternalId());
        for (int i = 0; i < ids.size(); i += CHUNK) {
            java.util.List<String> slice = ids.subList(i, Math.min(i + CHUNK, ids.size()));
            for (Paper e : papers.findBySourceIdAndExternalIdIn(src.getId(), slice)) {
                existing.put(e.getExternalId(), e);
            }
        }
        java.util.List<Paper> toInsert = new java.util.ArrayList<>();
        java.util.List<Paper> toUpdate = new java.util.ArrayList<>();
        for (Paper p : parsed) {
            Paper found = existing.get(p.getExternalId());
            if (found == null) {
                p.setSource(src);
                toInsert.add(p);
            } else if (p.getCategories() != null && !p.getCategories().equals(found.getCategories())) {
                // Row already on file but its category list is missing or stale —
                // refresh just that column. This is what makes a deep resync able
                // to backfill cross-list data onto papers synced before the
                // categories column existed.
                found.setCategories(p.getCategories());
                toUpdate.add(found);
            }
        }
        if (!toInsert.isEmpty()) papers.saveAll(toInsert);
        if (!toUpdate.isEmpty()) papers.saveAll(toUpdate);
        return new int[]{toInsert.size(), existing.size()};
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
            // Prefer <updated> over <published> so revisions / re-announces
            // surface as fresh activity in Latest. arXiv's <published> is the
            // original submission instant and never changes; <updated> is the
            // most recent activity. Fall back to <published>, then to "now",
            // if <updated> isn't present.
            String updatedStr = textNS(e, ATOM_NS, "updated");
            String publishedStr = textNS(e, ATOM_NS, "published");
            String pubStr = (updatedStr != null && !updatedStr.isBlank()) ? updatedStr : publishedStr;
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

            // Every category the paper is tagged with (primary + cross-lists),
            // from the Atom <category term="…"> elements. Stored comma-delimited
            // with leading/trailing commas so the feed can match an exact term
            // with a LIKE '%,term,%' without false positives.
            java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
            if (topicCode != null && !topicCode.isBlank()) cats.add(topicCode.trim());
            NodeList catNodes = e.getElementsByTagNameNS(ATOM_NS, "category");
            for (int j = 0; j < catNodes.getLength(); j++) {
                String term = ((Element) catNodes.item(j)).getAttribute("term");
                if (term != null && !term.isBlank()) cats.add(term.trim());
            }
            String categories = cats.isEmpty() ? null : "," + String.join(",", cats) + ",";

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
            p.setCategories(categories);
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
