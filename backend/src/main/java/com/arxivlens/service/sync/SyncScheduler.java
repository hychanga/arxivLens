package com.arxivlens.service.sync;

import com.arxivlens.config.AppProperties;
import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final AppProperties props;
    private final SyncDispatcher dispatcher;
    private final EmailService email;

    @Value("${app.scheduler.enabled:false}")
    private boolean enabled;

    public SyncScheduler(AppProperties props, SyncDispatcher dispatcher, EmailService email) {
        this.props = props;
        this.dispatcher = dispatcher;
        this.email = email;
    }

    @Scheduled(cron = "${app.scheduler.arxiv-cron:0 0 */6 * * *}")
    public void arxiv() {
        if (!enabled) return;
        runArxivSyncAndNotify();
    }

    /**
     * Runs the arXiv sync and emails the summary. Shared by the in-process
     * {@code @Scheduled} job and the external-cron trigger endpoint — on Render
     * Free the scheduled job can't fire while the service is asleep, so an
     * external scheduler calls this via {@code /api/cron/arxiv-sync}.
     */
    public SyncResult runArxivSyncAndNotify() {
        log.info("arXiv sync starting");
        SyncResult r = dispatcher.syncByCode("arxiv");
        log.info("arXiv sync done: fetched={} inserted={} skipped={} error={}",
                r.fetched(), r.inserted(), r.skipped(), r.error());
        notifyArxivComplete(r);
        return r;
    }

    /**
     * Emails a one-line summary of a finished scheduled arXiv sync to the address
     * in {@code app.scheduler.notify-email} (if set). Best-effort: delivery is
     * delegated to {@link EmailService}, which logs and swallows any provider
     * error, so a mail hiccup never affects the sync itself.
     */
    private void notifyArxivComplete(SyncResult r) {
        String to = props.scheduler() == null ? null : props.scheduler().notifyEmail();
        if (to == null || to.isBlank()) return;
        String subject = "arxivLens — arXiv sync complete (" + r.inserted() + " new)";
        String body = "The scheduled arXiv sync finished at " + Instant.now() + " (UTC).\n\n"
                + "New papers inserted: " + r.inserted() + "\n"
                + "Already on file (skipped): " + r.skipped() + "\n"
                + "Total entries fetched this run: " + r.fetched() + "\n"
                + "Errors: " + (r.error() == null ? "none" : r.error()) + "\n\n"
                + "— arxivLens";
        email.sendPlainText(to, subject, body);
    }

    /**
     * Sends a one-off test of the post-sync notification email to the configured
     * address, so an admin can verify mail delivery without waiting for the 6h
     * cron. Returns the target address, or {@code null} if no notify-email is set.
     */
    public String sendTestNotification() {
        String to = props.scheduler() == null ? null : props.scheduler().notifyEmail();
        if (to == null || to.isBlank()) return null;
        String body = "This is a test of the scheduled-sync email notification, sent at "
                + Instant.now() + " (UTC).\n\n"
                + "If you received this, the post-sync summary email is configured correctly.\n\n"
                + "— arxivLens";
        email.sendPlainText(to, "arxivLens — test notification", body);
        return to;
    }

    @Scheduled(cron = "${app.scheduler.hbr-cron:0 30 */6 * * *}")
    public void hbr() {
        if (!enabled) return;
        log.info("Scheduled HBR sync starting");
        SyncResult r = dispatcher.syncByCode("hbr");
        log.info("HBR sync done: fetched={} inserted={} skipped={} error={}",
                r.fetched(), r.inserted(), r.skipped(), r.error());
    }

    // Business Weekly intentionally has no scheduled sync — it's a manual paste
    // source, same as HBR. Articles are added via the Feed page's "Add article"
    // button. Removed the auto-sync cron after the scraping attempts proved
    // unreliable; BW's search page markup varied too much to extract titles
    // consistently.
}
