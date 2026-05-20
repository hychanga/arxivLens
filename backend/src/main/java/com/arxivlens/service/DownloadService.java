package com.arxivlens.service;

import com.arxivlens.dto.DownloadDtos.CreateDownloadRequest;
import com.arxivlens.dto.DownloadDtos.DownloadView;
import com.arxivlens.entity.Download;
import com.arxivlens.entity.DownloadBlob;
import com.arxivlens.entity.Paper;
import com.arxivlens.repository.DownloadBlobRepository;
import com.arxivlens.repository.DownloadRepository;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    /** Hard cap on a single PDF. Anything bigger is rejected mid-stream. */
    private static final long MAX_BYTES = 100L * 1024 * 1024; // 100 MB

    /** Result tuple for streaming a cached PDF back through the controller. */
    public record CachedPdf(Resource resource, String filename) {}

    private final DownloadRepository downloads;
    private final DownloadBlobRepository blobs;
    private final PaperRepository papers;
    private final UserRepository users;

    /**
     * {@link HttpClient.Redirect#ALWAYS} so we follow HTTP→HTTPS upgrades that arXiv issues
     * for {@code http://arxiv.org/pdf/…} URLs scraped from the Atom feed. {@code NORMAL}
     * allows HTTP→HTTPS but is stricter on cross-host hops, which arXiv's CDN sometimes
     * triggers — ALWAYS removes that footgun.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public DownloadService(DownloadRepository downloads, DownloadBlobRepository blobs,
                           PaperRepository papers, UserRepository users) {
        this.downloads = downloads;
        this.blobs = blobs;
        this.papers = papers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<DownloadView> list(Long userId) {
        return downloads.findByUserIdOrderByDownloadedAtDesc(userId).stream()
                .map(DownloadView::of)
                .toList();
    }

    @Transactional
    public DownloadView create(Long userId, CreateDownloadRequest req) {
        Paper p = papers.findById(req.paperId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paper not found"));
        String pdfUrl = p.getPdfUrl();
        if (pdfUrl == null || pdfUrl.isBlank()) {
            // Some older synced rows in TiDB came back with pdfUrl null even
            // though arXiv exposes a deterministic URL for every paper. Fall
            // back to constructing it from the externalId so favorites of
            // those rows aren't permanently un-downloadable.
            pdfUrl = inferArxivPdfUrl(p);
        }
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This paper has no downloadable PDF. " +
                    "Demo seed papers ship without PDF links — run a sync to fetch real papers, " +
                    "or pick another paper from a source that exposes PDFs.");
        }
        String url = normalizeArxivUrl(pdfUrl);

        Optional<Download> existing = downloads.findByUserIdAndPaper_Id(userId, p.getId());
        if (existing.isPresent()) {
            Download d = existing.get();
            // Self-heal rows that pre-date BLOB storage: their file_path pointed at
            // a filesystem cache that Render's ephemeral disk threw away. Refetching
            // makes "Open cached" work again without forcing the user to delete + redo.
            if (!blobs.existsById(d.getId())) {
                byte[] bytes = fetchOrThrow(url);
                saveBlob(d.getId(), bytes);
                d.setSizeMb(bytes.length / (1024.0 * 1024.0));
                downloads.save(d);
            }
            return DownloadView.of(d);
        }

        byte[] bytes = fetchOrThrow(url);

        Download d = new Download();
        d.setUser(users.getReferenceById(userId));
        d.setPaper(p);
        // file_path is legacy from the on-disk era. The column is still NOT NULL in the
        // existing schema (and we can't relax it via ddl-auto=update), so we keep writing
        // a stable marker that's grep-able when scanning logs / dumps.
        d.setFilePath("blob:" + p.getExternalId());
        d.setSizeMb(bytes.length / (1024.0 * 1024.0));
        downloads.save(d);

        saveBlob(d.getId(), bytes);
        return DownloadView.of(d);
    }

    @Transactional
    public void delete(Long userId, Long paperId) {
        Optional<Download> opt = downloads.findByUserIdAndPaper_Id(userId, paperId);
        if (opt.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Download not found");
        }
        Download d = opt.get();
        if (blobs.existsById(d.getId())) {
            blobs.deleteById(d.getId());
        }
        downloads.deleteByUserIdAndPaper_Id(userId, paperId);
    }

    @Transactional
    public long clear(Long userId) {
        List<Download> userDownloads = downloads.findByUserIdOrderByDownloadedAtDesc(userId);
        long count = userDownloads.size();
        for (Download d : userDownloads) {
            if (blobs.existsById(d.getId())) {
                blobs.deleteById(d.getId());
            }
        }
        downloads.deleteByUserId(userId);
        return count;
    }

    /**
     * Loads a previously-cached PDF for the {@code paperId} owned by {@code userId} so the
     * controller can stream it inline back to the browser. Returns 404/410 with a clear
     * message rather than a generic 500 when the row exists but the blob is gone.
     */
    @Transactional(readOnly = true)
    public CachedPdf serveCachedFile(Long userId, Long paperId) {
        Download d = downloads.findByUserIdAndPaper_Id(userId, paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Not in your library — download the paper first."));
        DownloadBlob b = blobs.findById(d.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.GONE,
                        "PDF is no longer cached. Click \"Download PDF\" on this paper in Favorites to refetch."));
        String filename = safeFilename(d.getPaper().getExternalId()) + ".pdf";
        return new CachedPdf(new ByteArrayResource(b.getPdfData()), filename);
    }

    private void saveBlob(Long downloadId, byte[] bytes) {
        DownloadBlob b = new DownloadBlob();
        b.setDownloadId(downloadId);
        b.setPdfData(bytes);
        blobs.save(b);
    }

    private byte[] fetchOrThrow(String url) {
        try {
            return downloadToBytes(url);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Download failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Download interrupted");
        }
    }

    /**
     * Streams the remote PDF into memory. We buffer because the bytes go to a
     * BLOB column, not a file — and the row is the source of truth. Cap at
     * {@link #MAX_BYTES} so a runaway server can't bloat our heap.
     */
    private byte[] downloadToBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (compatible; arxivLens/0.1; +https://github.com/arxivlens)")
                .header("Accept", "application/pdf,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            // Drain the stream so the connection can be returned to the pool, then bail.
            try (InputStream body = res.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
        }

        try (InputStream in = res.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("PDF exceeds " + (MAX_BYTES / (1024 * 1024)) + " MB cap");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Reconstructs arXiv's deterministic PDF URL from a paper's external id.
     * Modern arXiv ids look like {@code 2501.00001} (optionally suffixed with
     * a version like {@code v2}); older ones use {@code <archive>/<7 digits>}
     * (e.g. {@code cs/9501001}). Manual / Business-Weekly papers are rejected.
     */
    private static String inferArxivPdfUrl(Paper p) {
        String ext = p == null ? null : p.getExternalId();
        if (ext == null || ext.isBlank()) return null;
        if (ext.startsWith("manual-") || ext.startsWith("bw-")) return null;
        boolean modern = ext.matches("\\d{4}\\.\\d{4,5}(v\\d+)?");
        boolean older = ext.matches("[a-z\\-]+/\\d{7}(v\\d+)?");
        if (!modern && !older) return null;
        return "https://arxiv.org/pdf/" + ext + ".pdf";
    }

    /**
     * arXiv's Atom feed hands out {@code http://arxiv.org/...} URLs. Some hops in their CDN
     * chain don't tolerate the protocol switch cleanly, so we upgrade upfront to skip the
     * redirect entirely. No-op for non-arXiv hosts.
     */
    private static String normalizeArxivUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("http://arxiv.org/")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private static String safeFilename(String s) {
        if (s == null || s.isBlank()) return "paper";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
