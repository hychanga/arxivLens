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
