package com.arxivlens.bootstrap;

import com.arxivlens.dto.SyncDtos.SyncResult;
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

    private final SyncDispatcher dispatcher;

    public StartupSyncRunner(SyncDispatcher dispatcher) {
        this.dispatcher = dispatcher;
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
        } catch (Exception e) {
            log.warn("Initial sync failed (server is up; user can hit Sync now manually)", e);
        }
    }
}
