package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;

public interface SourceSyncService {

    /** Stable code identifying the source (e.g. "arxiv", "hbr"). */
    String sourceCode();

    /** Fetches & upserts papers. Never throws — wraps any error in {@link SyncResult#error()}. */
    SyncResult sync();

    /**
     * Historical backfill so {@code /trends} can show data spanning {@code months}
     * months instead of just whatever the last regular {@link #sync()} brought in
     * (which is capped at the source's "max results per sync" setting and is
     * always the newest entries — useless for monthly trend buckets that need
     * older months populated).
     *
     * <p>Default impl is the same as {@link #sync()} — sources without paginated
     * history APIs (HBR's RSS, for example) simply don't have older data to fetch.
     * Override when the source supports pagination + date filtering.
     */
    default SyncResult backfill(int months) {
        return sync();
    }

    /**
     * Whether {@link #backfill(int)} actually reaches further back than {@link #sync()}.
     * Used by {@code StartupSyncRunner} to decide whether re-triggering backfill on
     * a shallow source could ever help — for HBR (RSS-only) it can't, so the auto-trigger
     * skips it and avoids re-running every boot.
     */
    default boolean supportsBackfill() {
        return false;
    }
}
