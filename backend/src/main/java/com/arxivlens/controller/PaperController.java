package com.arxivlens.controller;

import com.arxivlens.dto.PaperDtos.ManualPaperRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperResponse;
import com.arxivlens.entity.Paper;
import com.arxivlens.entity.PaperTranslation;
import com.arxivlens.service.PaperService;
import com.arxivlens.service.PaperTranslationService;
import com.arxivlens.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(name = "page",   defaultValue = "0") int page,
            @RequestParam(name = "size",   defaultValue = "10") int size
    ) {
        Page<Paper> result = service.findFeed(source, days, topic, page, size);
        return Map.of(
                "items",      result.getContent(),
                "page",       result.getNumber(),
                "size",       result.getSize(),
                "totalItems", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        );
    }

    /**
     * Returns a previously-cached translation. 404 means "not yet translated for this locale" —
     * the frontend treats that as "show original + offer Translate button" rather than an error.
     */
    @GetMapping("/{id}/translation")
    public PaperTranslation getTranslation(@PathVariable Long id,
                                           @RequestParam(name = "locale") String locale) {
        return translations.findCached(id, locale)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No translation cached"));
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
}
