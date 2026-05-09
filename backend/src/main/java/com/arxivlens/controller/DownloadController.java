package com.arxivlens.controller;

import com.arxivlens.dto.DownloadDtos.CreateDownloadRequest;
import com.arxivlens.dto.DownloadDtos.DownloadView;
import com.arxivlens.service.DownloadService;
import com.arxivlens.service.DownloadService.CachedPdf;
import com.arxivlens.web.AuthUtil;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final DownloadService service;

    public DownloadController(DownloadService service) {
        this.service = service;
    }

    @GetMapping
    public List<DownloadView> list() {
        return service.list(AuthUtil.currentUserId());
    }

    @PostMapping
    public ResponseEntity<DownloadView> create(@Valid @RequestBody CreateDownloadRequest req) {
        return ResponseEntity.status(201).body(service.create(AuthUtil.currentUserId(), req));
    }

    @DeleteMapping("/{paperId}")
    public ResponseEntity<Void> delete(@PathVariable Long paperId) {
        service.delete(AuthUtil.currentUserId(), paperId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public Map<String, Long> clear() {
        return Map.of("removed", service.clear(AuthUtil.currentUserId()));
    }

    /**
     * Streams the previously-cached PDF inline so the browser's PDF viewer can render it.
     * Frontend fetches this URL with the JWT in the {@code Authorization} header and
     * converts the response into a blob URL (a plain {@code <a href>} can't carry the
     * header, so we don't expose this as a directly clickable link).
     */
    @GetMapping("/{paperId}/file")
    public ResponseEntity<Resource> file(@PathVariable Long paperId) {
        CachedPdf pdf = service.serveCachedFile(AuthUtil.currentUserId(), paperId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + pdf.filename() + "\"")
                .body(pdf.resource());
    }
}
