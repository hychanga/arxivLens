package com.arxivlens.service;

import com.arxivlens.entity.CachedImage;
import com.arxivlens.repository.CachedImageRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches and caches inline article images so the frontend can render them
 * without depending on the publisher's CDN being hot-link friendly. One DB
 * row per source URL; subsequent requests come straight out of the cache.
 */
@Service
public class ImageProxyService {

    private static final Logger log = LoggerFactory.getLogger(ImageProxyService.class);

    /** Cap on image size we'll store. Larger responses are refused. */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8 MB

    /**
     * Hosts we're willing to proxy from. Whitelist over allow-all so a
     * malicious markdown body can't make our backend hit arbitrary intranet
     * services. Add new hosts here when a new publisher source is wired up.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "ibw.bwnet.com.tw",
            "img.bwnet.com.tw",
            "image.bwnet.com.tw",
            "www.businessweekly.com.tw",
            "businessweekly.com.tw",
            "www.hbrtaiwan.com",
            "hbrtaiwan.com",
            "hbr.org",
            "www.hbr.org"
    );

    /** Content-Types we consider real image responses. */
    private static final Set<String> ALLOWED_CT_PREFIXES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/avif"
    );

    private final CachedImageRepository cache;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ImageProxyService(CachedImageRepository cache) {
        this.cache = cache;
    }

    /** Returns the cached bytes for {@code url}, fetching and caching them on first request. */
    @Transactional
    public Fetched fetchOrCache(String url) {
        String normalized = normalize(url);
        if (normalized == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image URL");
        }
        String hash = sha256Hex(normalized);

        Optional<CachedImage> hit = cache.findByUrlHash(hash);
        if (hit.isPresent()) {
            CachedImage row = hit.get();
            return new Fetched(row.getData(), row.getContentType());
        }

        Fetched fresh = fetchUpstream(normalized);
        try {
            CachedImage row = new CachedImage();
            row.setUrlHash(hash);
            row.setSourceUrl(normalized.length() > 2048 ? normalized.substring(0, 2048) : normalized);
            row.setContentType(fresh.contentType());
            row.setData(fresh.bytes());
            cache.save(row);
        } catch (Exception e) {
            // Cache failure is non-fatal — we still got the bytes; just serve
            // without caching this time and try again next request.
            log.warn("Image proxy: could not persist cache for {} — {}", normalized, e.getMessage());
        }
        return fresh;
    }

    private Fetched fetchUpstream(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bad image URL: " + e.getMessage());
        }
        if (uri.getHost() == null || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Image host not on allow-list: " + uri.getHost());
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only http(s) image URLs are allowed");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                // Some CDNs (BW's included) inspect Referer for hot-link
                // protection. Sending a Referer from the publisher itself
                // looks like a normal in-page image load and works.
                .header("Referer", uri.getScheme() + "://" + uri.getHost() + "/")
                .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 "
                                + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Upstream image HTTP " + res.statusCode());
            }
            byte[] body = res.body();
            if (body.length == 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned empty image body");
            }
            if (body.length > MAX_IMAGE_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Image exceeds " + (MAX_IMAGE_BYTES / (1024 * 1024)) + " MB cap");
            }
            String ct = res.headers().firstValue("content-type").orElse("image/jpeg").toLowerCase();
            // Strip any "; charset=..." suffix the server volunteers.
            int semi = ct.indexOf(';');
            if (semi > 0) ct = ct.substring(0, semi).trim();
            if (ALLOWED_CT_PREFIXES.stream().noneMatch(ct::startsWith)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Upstream returned non-image content-type: " + ct);
            }
            return new Fetched(body, ct);
        } catch (ApiException ae) {
            throw ae;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Image fetch interrupted");
        } catch (Exception e) {
            log.warn("Image proxy: upstream fetch failed for {} — {}", url, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't fetch image: " + e.getMessage());
        }
    }

    /**
     * Normalises a candidate image URL — fixes Windows-style backslashes that
     * BW's CDN sometimes emits in {@code <img src>} ({@code AC_Gallery\2024\10\...}),
     * trims whitespace, and rejects obviously bad forms.
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Backslashes are never legal in URL paths, but some publishers ship them
        // anyway. Convert to forward slashes uniformly.
        s = s.replace('\\', '/');
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        return s;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandated by the JRE; this should never happen.
            throw new IllegalStateException(e);
        }
    }

    public record Fetched(byte[] bytes, String contentType) {}
}
