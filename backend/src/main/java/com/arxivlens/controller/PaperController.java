package com.arxivlens.controller;

import com.arxivlens.dto.PaperDtos.ImportUrlRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperResponse;
import com.arxivlens.dto.PaperDtos.UpdateManualPaperRequest;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.PaperTranslation;
import com.arxivlens.service.PaperService;
import com.arxivlens.service.PaperTranslationService;
import com.arxivlens.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final PaperService service;
    private final PaperTranslationService translations;

    public PaperController(PaperService service, PaperTranslationService translations) {
        this.service = service;
        this.translations = translations;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(name = "source", defaultValue = "arxiv") String source,
            @RequestParam(name = "days",   required = false) Integer days,
            @RequestParam(name = "topic",  required = false) String topic,
            @RequestParam(name = "q",      required = false) String q,
            @RequestParam(name = "page",   defaultValue = "0") int page,
            @RequestParam(name = "size",   defaultValue = "10") int size
    ) {
        Page<Paper> result = service.findFeed(source, days, topic, q, page, size);
        return Map.of(
                "items",      result.getContent(),
                "page",       result.getNumber(),
                "size",       result.getSize(),
                "totalItems", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        );
    }

    /**
     * Returns a previously-cached translation, or {@code 204 No Content} when none
     * exists yet for this locale. The feed probes this endpoint once per visible
     * card, so "not translated yet" is the common, expected case — returning 204
     * rather than 404 keeps it out of the browser's console as a network error.
     * The frontend treats an empty body as "show original + offer Translate button".
     */
    @GetMapping("/{id}/translation")
    public ResponseEntity<PaperTranslation> getTranslation(@PathVariable Long id,
                                                           @RequestParam(name = "locale") String locale) {
        return translations.findCached(id, locale)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Batch cache lookup for many papers in one locale — the feed asks for all
     * visible cards at once instead of one request per card, which would exhaust
     * the small connection pool. Returns only the papers that already have a
     * cached translation; missing ones are simply absent from the list.
     */
    @GetMapping("/translations")
    public List<PaperTranslation> getTranslations(@RequestParam(name = "ids") List<Long> ids,
                                                  @RequestParam(name = "locale") String locale) {
        return translations.findCachedBatch(ids, locale);
    }

    /** Generates (or returns cached) translation for the paper. Idempotent per (paper, locale). */
    @PostMapping("/{id}/translate")
    public PaperTranslation generateTranslation(@PathVariable Long id,
                                                @RequestParam(name = "locale") String locale) {
        return translations.translateOrCached(id, locale);
    }

    /**
     * Adds a user-pasted article (used for sources without a usable feed, like HBR
     * after switching to manual mode). Any authenticated user can call this; rows
     * are global (same model as arXiv-synced papers).
     */
    @PostMapping("/manual")
    public ResponseEntity<ManualPaperResponse> createManual(@Valid @RequestBody ManualPaperRequest req) {
        return ResponseEntity.status(201).body(service.createManual(req));
    }

    /**
     * Fetches the URL server-side, extracts title + main content, and saves it.
     * For public pages only — paywalled sources fall through to the public
     * teaser, which is rarely useful. Returns the same shape as /manual.
     */
    @PostMapping("/import-url")
    public ResponseEntity<ManualPaperResponse> importFromUrl(@Valid @RequestBody ImportUrlRequest req) {
        return ResponseEntity.status(201).body(service.importFromUrl(req));
    }

    /**
     * Updates the editable fields of a manually-added article. Only {@code manual-…}
     * papers can be edited — synced rows are read-only from the user's perspective.
     * Any authenticated user can call this (same rule as manual-add and delete).
     */
    @PatchMapping("/{id}")
    public ManualPaperResponse update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateManualPaperRequest req) {
        return service.updateManual(id, req);
    }

    /**
     * Deletes a manually-added paper plus any favorites / summaries / downloads
     * that reference it. Restricted to {@code manual-…} papers — synced rows
     * are managed via Admin → Clear paper cache. Any authed user can call this
     * (papers are global; the manual-add flow is also globally writable).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
