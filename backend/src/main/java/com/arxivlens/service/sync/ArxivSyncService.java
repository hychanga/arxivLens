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
        String search = activeTopics.stream()
                .map(t -> "cat:" + t.getCode())
                .collect(Collectors.joining("+OR+"));
        String url = "https://export.arxiv.org/api/query"
                + "?search_query=" + search
                + "&max_results=" + Math.min(200, Math.max(1, max))
                + "&sortBy=submittedDate&sortOrder=descending";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                return new SyncResult(sourceCode(), 0, 0, 0, "HTTP " + res.statusCode());
            }

            List<Paper> parsed = parseAtom(res.body(), src.getId());
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
            return new SyncResult(sourceCode(), parsed.size(), inserted, skipped, null);
        } catch (Exception ex) {
            log.warn("arXiv sync failed", ex);
            return new SyncResult(sourceCode(), 0, 0, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
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
