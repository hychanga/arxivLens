package com.arxivlens.controller;

import com.arxivlens.dto.AdminDtos.SettingsView;
import com.arxivlens.dto.AdminDtos.UpdateSettingsRequest;
import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.repository.TopicRepository;
import com.arxivlens.service.PaperService;
import com.arxivlens.service.SettingService;
import com.arxivlens.service.sync.SyncDispatcher;
import com.arxivlens.service.sync.SyncScheduler;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final SourceRepository sources;
    private final TopicRepository topics;
    private final SyncScheduler scheduler;

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** Single background worker so a deep resync runs off the request thread (and never two at once). */
    private final ExecutorService resyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arxiv-resync");
        t.setDaemon(true);
        return t;
    });

    public AdminController(SettingService settings,
                           PaperRepository papers,
                           PaperService paperService,
                           SyncDispatcher dispatcher,
                           SourceRepository sources,
                           TopicRepository topics,
                           SyncScheduler scheduler) {
        this.settings = settings;
        this.papers = papers;
        this.paperService = paperService;
        this.dispatcher = dispatcher;
        this.sources = sources;
        this.topics = topics;
        this.scheduler = scheduler;
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

    /**
     * Resets {@code Topic.lastSyncedAt} for every enabled arXiv topic to
     * {@code now - days}, then runs a full sync. Use this when the regular
     * incremental sync has overshot — e.g. the per-topic page cap dropped
     * the tail of a 4000-paper window and the watermark advanced past those
     * never-fetched rows. The sync that follows paginates through the
     * widened window and back-fills the gap.
     *
     * <p>{@code days} is clamped to [1, 365] so an admin can't accidentally
     * ask arXiv for "everything since 1991".
     *
     * <p>Runs the pull on a background thread and returns immediately: a deep
     * resync can take several minutes (arXiv asks for >=3s between pages), which
     * outlives any proxy/browser request timeout. Running it off the request
     * thread also keeps it clear of the open-in-view session, so each page
     * commits in its own clean transaction. Watch the server logs (or the feed)
     * for completion. NOT {@code @Transactional} — the watermark resets and each
     * page commit are independent units, so one failure doesn't roll back the rest.
     */
    @PostMapping("/arxiv/resync")
    public Map<String, Object> resyncArxiv(@RequestParam(name = "days", defaultValue = "30") int days) {
        int safeDays = Math.max(1, Math.min(365, days));
        Source arxiv = sources.findByCode("arxiv")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "arXiv source not found"));
        Instant resetTo = Instant.now().minus(Duration.ofDays(safeDays));
        List<Topic> active = topics.findBySourceIdAndEnabledTrue(arxiv.getId());
        for (Topic t : active) {
            t.setLastSyncedAt(resetTo);
            topics.save(t);
        }
        resyncExecutor.submit(() -> {
            SyncResult r = dispatcher.syncByCode("arxiv");
            log.info("admin resync ({}d) done: fetched={} inserted={} skipped={} error={}",
                    safeDays, r.fetched(), r.inserted(), r.skipped(), r.error());
        });
        return Map.of(
                "status", "started",
                "days", safeDays,
                "topics", active.size(),
                "message", "Resync started in the background; check the feed in a few minutes.");
    }

    /**
     * Sends a one-off test of the post-sync notification email so the admin can
     * verify mail delivery without waiting for the 6h cron. The send itself is
     * best-effort (EmailService logs and swallows provider errors), so a "sent"
     * here means "handed to the provider" — confirm via the inbox / server logs.
     */
    @PostMapping("/notify-test")
    public Map<String, Object> testNotifyEmail() {
        String to = scheduler.sendTestNotification();
        if (to == null) {
            return Map.of("sent", false,
                    "message", "No notify address configured. Set SYNC_NOTIFY_EMAIL.");
        }
        return Map.of("sent", true, "to", to,
                "message", "Test email handed to the mail provider — check " + to
                        + " (and the server logs if it doesn't arrive).");
    }
}
