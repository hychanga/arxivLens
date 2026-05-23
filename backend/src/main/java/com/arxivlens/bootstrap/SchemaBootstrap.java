package com.arxivlens.bootstrap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Belt-and-suspenders DDL: explicitly creates tables that Hibernate's
 * {@code ddl-auto=update} ought to create but, in practice, sometimes does not
 * on a managed MySQL/TiDB target — especially when the entity has any
 * column whose unquoted identifier collides with a reserved keyword (e.g.
 * {@code YEAR_MONTH}, {@code COUNT} on TiDB). When Hibernate's CREATE TABLE
 * statement fails, the failure is logged but not propagated; subsequent
 * SELECTs against the missing table then crash at request time with
 * {@code "Table doesn't exist"}.
 *
 * <p>Using {@code CREATE TABLE IF NOT EXISTS} keeps this idempotent — if
 * Hibernate did manage to create the table, this is a no-op; otherwise we
 * create it ourselves with the exact column names the JPA model expects.
 *
 * <p>Runs via {@link PostConstruct}, before any {@code ApplicationRunner}
 * (so {@code StartupSyncRunner}'s backfill is guaranteed to find the table).
 */
@Component
public class SchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SchemaBootstrap.class);

    private static final String CREATE_MONTHLY_TOPIC_COUNTS = """
            CREATE TABLE IF NOT EXISTS monthly_topic_counts (
                id          BIGINT       NOT NULL AUTO_INCREMENT,
                source_id   BIGINT       NOT NULL,
                topic_code  VARCHAR(64)  NOT NULL,
                month_key   VARCHAR(7)   NOT NULL,
                paper_count BIGINT       NOT NULL,
                updated_at  DATETIME(6)  NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_mtc_source_topic_month (source_id, topic_code, month_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_PASSWORD_RESET_TOKENS = """
            CREATE TABLE IF NOT EXISTS password_reset_tokens (
                id          BIGINT       NOT NULL AUTO_INCREMENT,
                user_id     BIGINT       NOT NULL,
                token_hash  VARCHAR(64)  NOT NULL,
                expires_at  DATETIME(6)  NOT NULL,
                used_at     DATETIME(6)  NULL,
                created_at  DATETIME(6)  NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY ix_prt_token_hash (token_hash),
                KEY ix_prt_user_id (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_DOWNLOAD_BLOBS = """
            CREATE TABLE IF NOT EXISTS download_blobs (
                download_id BIGINT      NOT NULL,
                pdf_data    LONGBLOB    NOT NULL,
                created_at  DATETIME(6) NOT NULL,
                PRIMARY KEY (download_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_CACHED_IMAGES = """
            CREATE TABLE IF NOT EXISTS cached_images (
                id           BIGINT       NOT NULL AUTO_INCREMENT,
                url_hash     VARCHAR(64)  NOT NULL,
                source_url   VARCHAR(2048) NOT NULL,
                content_type VARCHAR(64),
                data         LONGBLOB     NOT NULL,
                created_at   DATETIME(6)  NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY ix_cached_images_url_hash (url_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    /**
     * Legacy column names that previous schemas used (and that
     * {@code ddl-auto=update} never drops, by design). When a fresh
     * {@code @Column(name="…")} renames a field, Hibernate ALTERs the table to
     * ADD the new column but leaves the old column in place — and if the old
     * column was {@code NOT NULL} with no default, every INSERT after the rename
     * fails with {@code "Field 'X' doesn't have a default value"}.
     */
    private static final String[] LEGACY_DROPPABLE_COLUMNS = {"count", "year_month"};

    private final JdbcTemplate jdbc;

    public SchemaBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTables() {
        boolean monthlyOk = createTable("monthly_topic_counts", CREATE_MONTHLY_TOPIC_COUNTS);
        if (monthlyOk) {
            for (String col : LEGACY_DROPPABLE_COLUMNS) {
                dropColumnIfExists("monthly_topic_counts", col);
            }
        }
        createTable("password_reset_tokens", CREATE_PASSWORD_RESET_TOKENS);
        createTable("download_blobs", CREATE_DOWNLOAD_BLOBS);
        createTable("cached_images", CREATE_CACHED_IMAGES);
        // Belt-and-braces for the new translation.introduction column. Hibernate's
        // ddl-auto=update should add it, but in practice managed TiDB has been
        // flaky about ALTERing existing tables — same reason the per-table
        // CREATE IF NOT EXISTS calls exist above.
        addColumnIfMissing("paper_translations", "introduction", "TEXT NULL");
        // TOTP secret for 2FA. Same belt-and-braces reasoning as the other
        // ALTER calls — managed TiDB has been flaky about ddl-auto picking
        // up new columns.
        addColumnIfMissing("users", "totp_secret", "VARCHAR(64) NULL");
        // Per-topic incremental sync watermark — see Topic.lastSyncedAt.
        addColumnIfMissing("topics", "last_synced_at", "DATETIME(6) NULL");
        // Ensure the canonical source rows exist in prod (data.sql only runs
        // locally). hbr is in here too because at least one prod DB lost the
        // row at some point — re-create it idempotently on every boot so the
        // TopBar entry points never disappear silently.
        ensureSource("hbr", "Harvard Business Review",
                "每日自動同步 hbrtaiwan.com 首頁文章，亦可手動補充。", 2);
        ensureSource("businessweekly", "商業週刊",
                "手動加入商業週刊的文章。", 3);
        // Re-enable canonical sources if they were toggled off — the user
        // reported the TopBar entries vanished. Admins can still disable via
        // the admin page; this only nudges rows back to enabled once on the
        // next boot after the regression.
        reEnableSourcesIfDisabled("hbr", "businessweekly");
        // One-shot cleanup: the earlier BW auto-scrape wrote rows with external_id
        // "bw-<section>-<id>" and broken titles. After switching BW to a manual
        // paste source, those rows are orphans — clear them. Idempotent: a no-op
        // once the legacy rows are gone, so safe to run on every startup.
        purgeLegacyBusinessWeeklyPapers();
        // Retroactively trim "｜哈佛商業評論…" suffix from HBR titles saved
        // before HtmlExtractor learned to strip publisher chrome. Idempotent:
        // once cleaned, the LIKE filter matches nothing and the loop runs
        // zero iterations.
        stripHbrPublisherChromeFromExistingTitles();
        // Retroactively downscale cached_images that were stored before
        // ingest-time resize was wired in. Idempotent: rows already within
        // the thumbnail budget are passed through untouched.
        resizeOversizedCachedImages();
    }

    private static final java.util.regex.Pattern HBR_TITLE_CHROME = java.util.regex.Pattern.compile(
            "\\s*[|｜\\-—–]\\s*哈佛商業評論[^\\r\\n]*$");

    /**
     * For every saved HBR paper whose title still carries the
     * {@code ｜哈佛商業評論…} suffix, rewrite the title to the trimmed form.
     * Mirrors {@link com.arxivlens.service.HtmlExtractor#stripPublisherChrome}
     * but standalone so we don't need to load that class on the bootstrap
     * thread.
     */
    private void stripHbrPublisherChromeFromExistingTitles() {
        try {
            java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, title FROM papers "
                            + "WHERE external_id LIKE 'hbr-%' AND title LIKE '%哈佛商業評論%'");
            int updated = 0;
            for (java.util.Map<String, Object> row : rows) {
                Object idObj = row.get("id");
                Object titleObj = row.get("title");
                if (!(idObj instanceof Number) || !(titleObj instanceof String title)) continue;
                String cleaned = HBR_TITLE_CHROME.matcher(title).replaceFirst("").trim();
                if (cleaned.isEmpty() || cleaned.equals(title)) continue;
                jdbc.update("UPDATE papers SET title = ? WHERE id = ?",
                        cleaned, ((Number) idObj).longValue());
                updated++;
            }
            if (updated > 0) {
                log.info("Schema bootstrap: stripped HBR title chrome from {} row(s)", updated);
            }
        } catch (Exception e) {
            log.warn("Schema bootstrap: HBR title cleanup failed — {}", e.getMessage());
        }
    }

    /**
     * Walks {@code cached_images} once and re-encodes any row whose bytes
     * shrink under the thumbnail size cap. Idempotent — rows already at or
     * below the budget are returned unchanged by
     * {@link com.arxivlens.service.ImageProxyService#resizeForThumbnail}, so
     * the UPDATE only fires for the legacy oversize rows.
     */
    private void resizeOversizedCachedImages() {
        try {
            java.util.List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM cached_images", Long.class);
            int resized = 0;
            int failed = 0;
            for (Long id : ids) {
                try {
                    java.util.Map<String, Object> row = jdbc.queryForMap(
                            "SELECT data, content_type FROM cached_images WHERE id = ?", id);
                    byte[] data = (byte[]) row.get("data");
                    String ct = (String) row.get("content_type");
                    if (data == null) continue;
                    byte[] thumb = com.arxivlens.service.ImageProxyService
                            .resizeForThumbnail(data, ct);
                    if (thumb != data && thumb.length < data.length) {
                        jdbc.update("UPDATE cached_images SET data = ? WHERE id = ?",
                                thumb, id);
                        resized++;
                    }
                } catch (Exception inner) {
                    failed++;
                }
            }
            if (resized > 0 || failed > 0) {
                log.info("Schema bootstrap: cached_images resize sweep — resized={} failed={} total={}",
                        resized, failed, ids.size());
            }
        } catch (Exception e) {
            log.warn("Schema bootstrap: cached_images resize sweep failed — {}", e.getMessage());
        }
    }

    /**
     * Flips {@code is_enabled} back to 1 for the named sources if they're
     * currently disabled. One-shot recovery for the case where a row exists
     * but was toggled off (whether by accident or by an earlier code path).
     * Admins can still disable from the admin page; this just guards against
     * silent disappearance from the TopBar.
     */
    private void reEnableSourcesIfDisabled(String... codes) {
        if (codes.length == 0) return;
        try {
            String placeholders = String.join(",", java.util.Collections.nCopies(codes.length, "?"));
            int n = jdbc.update(
                    "UPDATE data_sources SET is_enabled = 1 "
                            + "WHERE is_enabled = 0 AND code IN (" + placeholders + ")",
                    (Object[]) codes);
            if (n > 0) {
                log.info("Schema bootstrap: re-enabled {} canonical source row(s): {}",
                        n, String.join(", ", codes));
            }
        } catch (Exception e) {
            log.warn("Schema bootstrap: re-enable canonical sources failed — {}", e.getMessage());
        }
    }

    /**
     * Removes every paper (and dependent rows) whose externalId starts with
     * {@code "bw-"} — the prefix the old auto-scrape used. Manual BW articles
     * added via the Feed page use the {@code "manual-"} prefix and are
     * untouched.
     *
     * <p>Uses a SELECT-then-DELETE-by-id-list pattern rather than nested
     * correlated subqueries because TiDB has historically been picky about
     * {@code DELETE … WHERE col IN (SELECT …)} forms.
     */
    private void purgeLegacyBusinessWeeklyPapers() {
        try {
            java.util.List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM papers WHERE external_id LIKE 'bw-%'", Long.class);
            if (ids.isEmpty()) {
                log.info("Schema bootstrap: no legacy 'bw-*' papers to purge");
                return;
            }
            String inList = ids.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            log.info("Schema bootstrap: purging {} legacy BW paper(s): ids=[{}]",
                    ids.size(), inList.length() > 200 ? inList.substring(0, 200) + "…" : inList);

            int t = jdbc.update("DELETE FROM paper_translations WHERE paper_id IN (" + inList + ")");
            int s = jdbc.update(
                    "DELETE FROM ai_summaries WHERE favorite_id IN "
                            + "(SELECT id FROM favorites WHERE paper_id IN (" + inList + "))");
            int f = jdbc.update("DELETE FROM favorites WHERE paper_id IN (" + inList + ")");
            int b = jdbc.update(
                    "DELETE FROM download_blobs WHERE download_id IN "
                            + "(SELECT id FROM downloads WHERE paper_id IN (" + inList + "))");
            int d = jdbc.update("DELETE FROM downloads WHERE paper_id IN (" + inList + ")");
            int p = jdbc.update("DELETE FROM papers WHERE id IN (" + inList + ")");
            log.info("Schema bootstrap: BW purge results — translations={} ai_summaries={} favorites={} blobs={} downloads={} papers={}",
                    t, s, f, b, d, p);
        } catch (Exception e) {
            log.warn("Schema bootstrap: BW legacy purge failed — {}", e.getMessage(), e);
        }
    }

    /**
     * Idempotent INSERT IGNORE for a row in {@code data_sources}. Explicitly sets
     * {@code created_at = NOW(6)} because the column is {@code DATETIME(6) NOT NULL}
     * without a SQL-level DEFAULT — leaving it out causes TiDB (and any non-strict
     * MySQL) to write {@code 0000-00-00 00:00:00}, which the JDBC driver then
     * refuses to read with "Zero date value prohibited" → every subsequent
     * {@code SELECT} from {@code data_sources} 500s.
     *
     * <p>Also repairs any pre-existing zero-date rows so the first read after
     * upgrade doesn't 500 just because of historical data written before this
     * fix was in place.
     */
    private void ensureSource(String code, String name, String description, int displayOrder) {
        try {
            // Repair zero-date rows from earlier deploys before we INSERT or SELECT.
            try {
                int repaired = jdbc.update(
                        "UPDATE data_sources SET created_at = NOW(6) "
                                + "WHERE created_at IS NULL OR created_at = '0000-00-00 00:00:00.000000' "
                                + "OR created_at = '0000-00-00 00:00:00'");
                if (repaired > 0) {
                    log.info("Schema bootstrap: repaired {} zero-date data_sources rows", repaired);
                }
            } catch (Exception e) {
                log.warn("Schema bootstrap: zero-date repair failed — {}", e.getMessage());
            }
            jdbc.update(
                    "INSERT IGNORE INTO data_sources (code, name, description, is_enabled, display_order, created_at) "
                            + "VALUES (?, ?, ?, 1, ?, NOW(6))",
                    code, name, description, displayOrder);
            log.info("Schema bootstrap: data_sources row '{}' is present", code);
        } catch (Exception e) {
            log.warn("Schema bootstrap: could not seed data_sources row {} — {}", code, e.getMessage());
        }
    }

    /**
     * Idempotent {@code CREATE TABLE IF NOT EXISTS}. Failure is logged but
     * doesn't kill startup — the dependent feature degrades, the rest of the
     * app stays up.
     */
    private boolean createTable(String name, String ddl) {
        try {
            jdbc.execute(ddl);
            log.info("Schema bootstrap: {} is present", name);
            return true;
        } catch (Exception e) {
            log.error("Schema bootstrap failed to create {}: {}", name, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Idempotent drop of a no-longer-mapped column. Uses INFORMATION_SCHEMA to
     * detect presence so we don't rely on {@code DROP COLUMN IF EXISTS} (which
     * MySQL gained only in 8.0.29 and which TiDB's compat support is uneven on).
     * Backticks the column name because both candidates here are reserved
     * keywords in MySQL/TiDB.
     */
    /**
     * Idempotent {@code ALTER TABLE … ADD COLUMN}. Adds the column when missing
     * and is a no-op otherwise. The type fragment is interpolated raw — only
     * pass trusted, hard-coded values (never user input).
     */
    private void addColumnIfMissing(String table, String column, String typeFragment) {
        try {
            Integer present = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (present != null && present > 0) return;
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN `" + column + "` " + typeFragment);
            log.info("Schema bootstrap: added column {}.{} ({})", table, column, typeFragment);
        } catch (Exception e) {
            log.warn("Schema bootstrap: could not add {}.{} — {}", table, column, e.getMessage());
        }
    }

    private void dropColumnIfExists(String table, String column) {
        try {
            Integer present = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (present == null || present == 0) return;
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN `" + column + "`");
            log.info("Schema bootstrap: dropped legacy column {}.{}", table, column);
        } catch (Exception e) {
            log.warn("Schema bootstrap: could not drop {}.{} — {}", table, column, e.getMessage());
        }
    }
}
