package com.arxivlens.controller;

import com.arxivlens.service.ImageProxyService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves inline article images that were referenced in saved article bodies.
 * Endpoint is public (no JWT) because {@code <img>} requests can't carry an
 * Authorization header — the {@link ImageProxyService} restricts upstream
 * fetches to a known publisher allow-list, so opening this URL doesn't grant
 * arbitrary SSRF.
 */
@RestController
@RequestMapping("/api/images")
public class ImageProxyController {

    private final ImageProxyService service;

    public ImageProxyController(ImageProxyService service) {
        this.service = service;
    }

    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxy(@RequestParam("url") String url) {
        ImageProxyService.Fetched f = service.fetchOrCache(url);
        MediaType type = parseMediaType(f.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(f.bytes());
    }

    private static MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) return MediaType.IMAGE_JPEG;
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.IMAGE_JPEG;
        }
    }
}
