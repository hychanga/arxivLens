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
        // Belt-and-braces for the new translation.introduction column. Hibernate's
        // ddl-auto=update should add it, but in practice managed TiDB has been
        // flaky about ALTERing existing tables — same reason the per-table
        // CREATE IF NOT EXISTS calls exist above.
        addColumnIfMissing("paper_translations", "introduction", "TEXT NULL");
        // Add the businessweekly source row in prod (data.sql only runs locally).
        ensureSource("businessweekly", "商業週刊",
                "手動加入商業週刊的文章。", 3);
        // One-shot cleanup: the earlier BW auto-scrape wrote rows with external_id
        // "bw-<section>-<id>" and broken titles. After switching BW to a manual
        // paste source, those rows are orphans — clear them. Idempotent: a no-op
        // once the legacy rows are gone, so safe to run on every startup.
        purgeLegacyBusinessWeeklyPapers();
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
