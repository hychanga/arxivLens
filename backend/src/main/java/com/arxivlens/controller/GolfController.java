package com.arxivlens.controller;

import com.arxivlens.dto.GolfDtos.GolfResourceRequest;
import com.arxivlens.entity.GolfResource;
import com.arxivlens.repository.GolfResourceRepository;
import com.arxivlens.web.ApiException;
import com.arxivlens.web.AuthUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/golf")
public class GolfController {

    private final GolfResourceRepository repository;

    public GolfController(GolfResourceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<GolfResource> list(@RequestParam(required = false) String q) {
        return (q == null || q.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.search(q.trim());
    }

    @GetMapping("/{id}")
    public GolfResource get(@PathVariable Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GolfResource create(@Valid @RequestBody GolfResourceRequest req) {
        requireAdmin();
        GolfResource r = new GolfResource();
        apply(r, req);
        r.setCreatedBy(AuthUtil.currentUserEmail());
        return repository.save(r);
    }

    @PutMapping("/{id}")
    public GolfResource update(@PathVariable Long id, @Valid @RequestBody GolfResourceRequest req) {
        requireAdmin();
        GolfResource r = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found"));
        apply(r, req);
        return repository.save(r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        requireAdmin();
        if (!repository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
        repository.deleteById(id);
    }

    private void requireAdmin() {
        if (!AuthUtil.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin only");
        }
    }

    private void apply(GolfResource r, GolfResourceRequest req) {
        r.setTitle(req.title().trim());
        r.setSummary(trimToNull(req.summary()));
        r.setContent(trimToNull(req.content()));
        r.setCategory(trimToNull(req.category()));
        r.setTags(trimToNull(req.tags()));
        r.setVideoUrl(trimToNull(req.videoUrl()));
        r.setPdfUrl(trimToNull(req.pdfUrl()));
        r.setSource(trimToNull(req.source()));
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
