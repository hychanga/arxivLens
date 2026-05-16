package com.arxivlens.controller;

import com.arxivlens.dto.AdminDtos.SettingsView;
import com.arxivlens.dto.AdminDtos.UpdateSettingsRequest;
import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.service.PaperService;
import com.arxivlens.service.SettingService;
import com.arxivlens.service.sync.SyncDispatcher;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SettingService settings;
    private final PaperRepository papers;
    private final PaperService paperService;
    private final SyncDispatcher dispatcher;

    public AdminController(SettingService settings,
                           PaperRepository papers,
                           PaperService paperService,
                           SyncDispatcher dispatcher) {
        this.settings = settings;
        this.papers = papers;
        this.paperService = paperService;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/settings")
    public SettingsView get() {
        return settings.get();
    }

    @PutMapping("/settings")
    public SettingsView update(@Valid @RequestBody UpdateSettingsRequest req) {
        return settings.update(req);
    }

    @PostMapping("/settings/reset")
    public SettingsView reset() {
        return settings.reset();
    }

    /** Clears all paper rows. Next sync will re-fetch. */
    @DeleteMapping("/papers")
    @Transactional
    public Map<String, Long> clearPapers() {
        long count = papers.count();
        papers.deleteAllInBatch();
        return Map.of("removed", count);
    }

    /**
     * Paginates every enabled source's history back {@code months} months so the
     * Trends chart has real per-month buckets to show — the regular sync only
     * pulls the freshest {@code maxResultsPerSync} entries which all land in the
     * latest one or two month buckets.
     *
     * <p>Synchronous and slow (30–60 s) on purpose: the admin needs to see the
     * SyncResult to know it worked. Page-by-page upserts persist incrementally,
     * so an HTTP timeout still leaves valid partial progress.
     */
    @PostMapping("/backfill")
    public List<SyncResult> backfill(@RequestParam(name = "months", defaultValue = "12") int months) {
        return dispatcher.backfillAllEnabled(Math.max(1, Math.min(24, months)));
    }

    /**
     * Bulk-deletes every manually-added paper (externalId starting with
     * "manual-") plus its translations / favorites / summaries / downloads /
     * blobs. Used for a clean slate after testing the paste / URL-import flow.
     * Sync-fetched arXiv papers are untouched — those go via /papers above.
     */
    @DeleteMapping("/manual-articles")
    public Map<String, Integer> clearManualArticles() {
        return Map.of("removed", paperService.deleteAllManual());
    }
}
