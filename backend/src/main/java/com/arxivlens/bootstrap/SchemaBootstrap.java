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

    private final JdbcTemplate jdbc;

    public SchemaBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTables() {
        try {
            jdbc.execute(CREATE_MONTHLY_TOPIC_COUNTS);
            log.info("Schema bootstrap: monthly_topic_counts is present");
        } catch (Exception e) {
            // Don't kill startup — log loudly so the admin can fix DDL privileges,
            // but let the rest of the app come up. /trends will fall back to its
            // Paper-aggregate path until this is resolved.
            log.error("Schema bootstrap failed to create monthly_topic_counts: {}", e.getMessage(), e);
        }
    }
}
