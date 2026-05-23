package com.arxivlens.service;

import com.arxivlens.entity.CachedImage;
import com.arxivlens.repository.CachedImageRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
     * Domain suffixes we'll proxy from. Both the bare domain and any subdomain
     * are accepted, so {@code images.hbrtaiwan.com} / {@code cdn.hbrtaiwan.com}
     * / etc. all pass without having to enumerate every CDN host the
     * publisher uses. Suffixes (not arbitrary host substrings) over allow-all
     * so a malicious markdown body still can't make our backend hit intranet
     * services. Add new entries when wiring up a new publisher source.
     */
    private static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(
            "bwnet.com.tw",
            "businessweekly.com.tw",
            "hbrtaiwan.com",
            "hbr.org",
            // HBR Taiwan / 哈佛商業評論繁體中文版 is published by Commonwealth
            // Group (天下雜誌); their inline article images live under
            // imgs.cwgv.com.tw and similar cwgv.com.tw subdomains.
            "cwgv.com.tw",
            "cw.com.tw"
    );

    private static boolean hostAllowed(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

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
        // Downscale before persisting — publisher images often ship at
        // 1200×675 etc. which is wasteful for our cards. We never display
        // larger than the modal width, so shrinking at ingest time saves
        // both TiDB storage and bandwidth on every subsequent serve.
        byte[] thumb = resizeForThumbnail(fresh.bytes(), fresh.contentType());
        Fetched served = thumb == fresh.bytes() ? fresh : new Fetched(thumb, fresh.contentType());
        try {
            CachedImage row = new CachedImage();
            row.setUrlHash(hash);
            row.setSourceUrl(normalized.length() > 2048 ? normalized.substring(0, 2048) : normalized);
            row.setContentType(served.contentType());
            row.setData(served.bytes());
            cache.save(row);
        } catch (Exception e) {
            // Cache failure is non-fatal — we still got the bytes; just serve
            // without caching this time and try again next request.
            log.warn("Image proxy: could not persist cache for {} — {}", normalized, e.getMessage());
        }
        return served;
    }

    /** Max width / height for the cached thumbnail. Aspect ratio preserved. */
    public static final int THUMB_MAX_WIDTH = 400;
    public static final int THUMB_MAX_HEIGHT = 225;

    /**
     * Scales {@code data} so it fits inside {@link #THUMB_MAX_WIDTH} ×
     * {@link #THUMB_MAX_HEIGHT}, preserving aspect ratio. Returns the input
     * bytes verbatim when the image is already small enough, when its format
     * isn't decodable by ImageIO (WebP without the optional plugin, for
     * example), or when the encoded result is somehow larger than the input.
     */
    public static byte[] resizeForThumbnail(byte[] data, String contentType) {
        if (data == null || data.length == 0) return data;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return data; // unrecognised format → pass through.
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(
                    (double) THUMB_MAX_WIDTH / w,
                    (double) THUMB_MAX_HEIGHT / h);
            if (scale >= 1.0) return data; // already within budget.
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));
            // Preserve alpha for PNG so transparent backgrounds stay
            // transparent; JPEG never has alpha so RGB is enough.
            boolean keepAlpha = src.getColorModel().hasAlpha()
                    && !isJpegContentType(contentType);
            BufferedImage dst = new BufferedImage(newW, newH,
                    keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // White matte under any transparent area when re-encoding to JPEG
            // so we don't get a black background.
            if (!keepAlpha && src.getColorModel().hasAlpha()) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, newW, newH);
            }
            g.drawImage(src, 0, 0, newW, newH, null);
            g.dispose();
            String formatName = imageIoFormat(contentType);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            boolean wrote = ImageIO.write(dst, formatName, out);
            if (!wrote) return data;
            byte[] resized = out.toByteArray();
            // If the re-encoded version is somehow larger than the original
            // (rare — happens when the source is already heavily compressed
            // and our re-encode is more conservative), keep the original to
            // honor the "smaller cache" goal.
            return resized.length < data.length ? resized : data;
        } catch (Exception e) {
            return data;
        }
    }

    private static String imageIoFormat(String contentType) {
        if (contentType == null) return "jpeg";
        String low = contentType.toLowerCase();
        if (low.contains("png")) return "png";
        if (low.contains("gif")) return "gif";
        return "jpeg";
    }

    private static boolean isJpegContentType(String ct) {
        if (ct == null) return false;
        String low = ct.toLowerCase();
        return low.contains("jpeg") || low.contains("jpg");
    }

    private Fetched fetchUpstream(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bad image URL: " + e.getMessage());
        }
        if (!hostAllowed(uri.getHost())) {
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
