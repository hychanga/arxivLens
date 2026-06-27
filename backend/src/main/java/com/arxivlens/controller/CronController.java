package com.arxivlens.controller;

import com.arxivlens.config.AppProperties;
import com.arxivlens.service.sync.SyncScheduler;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token-protected triggers for an external scheduler. Render's free tier sleeps
 * the service after 15 min idle, so the in-process {@code @Scheduled} jobs don't
 * fire on time — a free external cron (cron-job.org, GitHub Actions, …) POSTs
 * here every 6h instead. The HTTP request both wakes the service and runs the
 * work. Auth is a shared secret ({@code CRON_TOKEN}), not a JWT, so the caller
 * doesn't need a login session.
 */
@RestController
@RequestMapping("/api/cron")
public class CronController {

    private final AppProperties props;
    private final SyncScheduler scheduler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cron-trigger");
        t.setDaemon(true);
        return t;
    });

    public CronController(AppProperties props, SyncScheduler scheduler) {
        this.props = props;
        this.scheduler = scheduler;
    }

    /**
     * Runs the arXiv sync + notification email. Accepts the token via a
     * {@code ?token=} query param or an {@code X-Cron-Token} header. Runs the
     * sync off the request thread (it makes paced arXiv calls and can outlive a
     * caller timeout) and returns immediately — the summary email is the result.
     */
    @PostMapping("/arxiv-sync")
    public Map<String, Object> arxivSync(@RequestParam(name = "token", required = false) String token,
                                         @RequestHeader(name = "X-Cron-Token", required = false) String headerToken) {
        authorize(token != null ? token : headerToken);
        executor.submit(scheduler::runArxivSyncAndNotify);
        return Map.of("status", "started",
                "message", "arXiv sync started; a summary email follows when it finishes.");
    }

    /**
     * McKinsey counterpart of {@link #arxivSync}. Same token auth and off-thread
     * execution — McKinsey's RSS pull is quick, but running it off the request
     * thread keeps the contract identical to the arXiv trigger.
     */
    @PostMapping("/mckinsey-sync")
    public Map<String, Object> mckinseySync(@RequestParam(name = "token", required = false) String token,
                                            @RequestHeader(name = "X-Cron-Token", required = false) String headerToken) {
        authorize(token != null ? token : headerToken);
        executor.submit(scheduler::runMckinseySyncAndNotify);
        return Map.of("status", "started",
                "message", "McKinsey sync started; a summary email follows when it finishes.");
    }

    @PostMapping("/medium-sync")
    public Map<String, Object> mediumSync(@RequestParam(name = "token", required = false) String token,
                                          @RequestHeader(name = "X-Cron-Token", required = false) String headerToken) {
        authorize(token != null ? token : headerToken);
        executor.submit(scheduler::runMediumSyncAndNotify);
        return Map.of("status", "started",
                "message", "Medium sync started; a summary email follows when it finishes.");
    }

    private void authorize(String provided) {
        String expected = props.cron() == null ? null : props.cron().token();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cron trigger is not configured (set CRON_TOKEN).");
        }
        // Constant-time compare so the token can't be guessed byte-by-byte via timing.
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or missing cron token.");
        }
    }
}
