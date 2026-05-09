package com.arxivlens.service;

import com.arxivlens.dto.DownloadDtos.CreateDownloadRequest;
import com.arxivlens.dto.DownloadDtos.DownloadView;
import com.arxivlens.entity.Download;
import com.arxivlens.entity.Paper;
import com.arxivlens.repository.DownloadRepository;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    /** Where downloaded PDFs land. Relative to {@code user.dir} so the path is reproducible per process. */
    private static final Path CACHE_ROOT = Paths.get(System.getProperty("user.dir"), "var", "arxivlens", "cache");

    /** Hard cap on a single PDF. Anything bigger is rejected mid-stream. */
    private static final long MAX_BYTES = 100L * 1024 * 1024; // 100 MB

    /** Result tuple for streaming a cached PDF back through the controller. */
    public record CachedPdf(Resource resource, String filename) {}

    private final DownloadRepository downloads;
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

    public DownloadService(DownloadRepository downloads, PaperRepository papers, UserRepository users) {
        this.downloads = downloads;
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

        Optional<Download> existing = downloads.findByUserIdAndPaper_Id(userId, p.getId());
        if (existing.isPresent()) return DownloadView.of(existing.get());

        if (p.getPdfUrl() == null || p.getPdfUrl().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This paper has no downloadable PDF. " +
                    "Demo seed papers ship without PDF links — run a sync to fetch real papers, " +
                    "or pick another paper from a source that exposes PDFs.");
        }

        String url = normalizeArxivUrl(p.getPdfUrl());
        Path target = CACHE_ROOT.resolve(safeFilename(p.getExternalId()) + ".pdf");
        long bytes;
        try {
            Files.createDirectories(target.getParent());
            bytes = (Files.exists(target) && Files.size(target) > 0)
                    ? Files.size(target)
                    : downloadToFile(url, target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Download failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Download interrupted");
        }

        Download d = new Download();
        d.setUser(users.getReferenceById(userId));
        d.setPaper(p);
        d.setFilePath(target.toString());
        d.setSizeMb(bytes / (1024.0 * 1024.0));
        downloads.save(d);
        return DownloadView.of(d);
    }

    @Transactional
    public void delete(Long userId, Long paperId) {
        Optional<Download> opt = downloads.findByUserIdAndPaper_Id(userId, paperId);
        if (opt.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Download not found");
        }
        Download d = opt.get();
        // Best-effort file cleanup; row is the source of truth so don't fail the request on IO error.
        try {
            Path p = Paths.get(d.getFilePath());
            if (Files.exists(p)) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete cached PDF {}: {}", d.getFilePath(), e.getMessage());
        }
        downloads.deleteByUserIdAndPaper_Id(userId, paperId);
    }

    @Transactional
    public long clear(Long userId) {
        long count = downloads.countByUserId(userId);
        for (Download d : downloads.findByUserIdOrderByDownloadedAtDesc(userId)) {
            try {
                Path p = Paths.get(d.getFilePath());
                if (Files.exists(p)) Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("Failed to delete cached PDF {}: {}", d.getFilePath(), e.getMessage());
            }
        }
        downloads.deleteByUserId(userId);
        return count;
    }

    /**
     * Loads a previously-cached PDF for the {@code paperId} owned by {@code userId} so the
     * controller can stream it inline back to the browser. Returns 404/410 with a clear
     * message rather than a generic 500 when the row exists but the file is gone.
     */
    @Transactional(readOnly = true)
    public CachedPdf serveCachedFile(Long userId, Long paperId) {
        Download d = downloads.findByUserIdAndPaper_Id(userId, paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Not in your library — download the paper first."));
        Path path = Paths.get(d.getFilePath());
        if (!Files.exists(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ApiException(HttpStatus.GONE,
                    "Cached PDF is missing on disk. Delete this entry and re-download from Favorites.");
        }
        String filename = safeFilename(d.getPaper().getExternalId()) + ".pdf";
        return new CachedPdf(new FileSystemResource(path), filename);
    }

    private long downloadToFile(String url, Path target) throws IOException, InterruptedException {
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

        try (InputStream in = res.body();
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    out.close();
                    Files.deleteIfExists(target);
                    throw new IOException("PDF exceeds " + (MAX_BYTES / (1024 * 1024)) + " MB cap");
                }
                out.write(buf, 0, n);
            }
            return total;
        }
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
