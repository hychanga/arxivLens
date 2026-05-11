package com.arxivlens.bootstrap;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.service.sync.SyncDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kicks off an arXiv + HBR sync right after the application is up so users see real
 * data on first login without ever clicking "Sync now". Replaces the old demo seed
 * papers, which carried fictitious titles.
 *
 * The sync runs on a background thread via {@link CompletableFuture#runAsync}: blocking
 * the main startup path on an external HTTP call (5–15 s for arXiv) would make the
 * server look hung. If the call fails (offline, rate limit, source disabled) the
 * exception is logged but the server still serves — the scheduler / "Sync now" can
 * recover later.
 *
 * {@code @Order(100)} keeps this strictly after {@link DataSeeder} (which has the
 * default {@code Ordered.LOWEST_PRECEDENCE}). The two are otherwise independent —
 * sources / topics come from {@code data.sql}.
 */
@Component
@Order(100)
public class StartupSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncRunner.class);

    /**
     * If a source has fewer than this many rows, regular sync alone can't fill out
     * 12 monthly Trends buckets — kick off a backfill on first boot. Stays above the
     * default {@code maxResultsPerSync} (50) so a freshly-synced source still
     * triggers backfill, but well below what one full backfill produces (typically
     * 1000–2000), so it doesn't fire on every cold start.
     */
    private static final long BACKFILL_THRESHOLD = 300L;

    private final SyncDispatcher dispatcher;
    private final SourceRepository sources;
    private final PaperRepository papers;

    public StartupSyncRunner(SyncDispatcher dispatcher, SourceRepository sources, PaperRepository papers) {
        this.dispatcher = dispatcher;
        this.sources = sources;
        this.papers = papers;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(this::sync);
    }

    private void sync() {
        try {
            log.info("Initial sync: starting for all enabled sources");
            List<SyncResult> results = dispatcher.syncAllEnabled();
            for (SyncResult r : results) {
                if (r.error() != null) {
                    log.warn("Initial sync [{}] error: {}", r.sourceCode(), r.error());
                } else {
                    log.info("Initial sync [{}] fetched={} inserted={} skipped={}",
                            r.sourceCode(), r.fetched(), r.inserted(), r.skipped());
                }
            }
            log.info("Initial sync: done");

            maybeBackfill();
        } catch (Exception e) {
            log.warn("Initial sync failed (server is up; user can hit Sync now manually)", e);
        }
    }

    /**
     * Self-heals an empty / shallow DB: if any enabled source has fewer than
     * {@link #BACKFILL_THRESHOLD} papers, runs a 12-month backfill so Trends has
     * meaningful per-month data without the admin needing to push a button.
     * After one successful backfill the row count comfortably exceeds the
     * threshold and subsequent restarts skip this path.
     */
    private void maybeBackfill() {
        try {
            List<Source> enabled = sources.findByEnabledTrueOrderByDisplayOrderAsc();
            boolean any = false;
            for (Source s : enabled) {
                long count = papers.countBySourceId(s.getId());
                if (count < BACKFILL_THRESHOLD) {
                    any = true;
                    log.info("Source [{}] has {} papers (< {}); triggering 12-month backfill",
                            s.getCode(), count, BACKFILL_THRESHOLD);
                    break;
                }
            }
            if (!any) return;
            List<SyncResult> results = dispatcher.backfillAllEnabled(12);
            for (SyncResult r : results) {
                if (r.error() != null) {
                    log.warn("Backfill [{}] error: {}", r.sourceCode(), r.error());
                } else {
                    log.info("Backfill [{}] fetched={} inserted={} skipped={}",
                            r.sourceCode(), r.fetched(), r.inserted(), r.skipped());
                }
            }
        } catch (Exception e) {
            log.warn("Auto-backfill failed (admin can still POST /api/admin/backfill)", e);
        }
    }
}
