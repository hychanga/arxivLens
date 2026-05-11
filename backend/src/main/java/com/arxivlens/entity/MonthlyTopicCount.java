package com.arxivlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Real arXiv submission count per (source, topic, month).
 *
 * <p>Populated by {@code ArxivSyncService.backfill(int)} which reads the
 * {@code <opensearch:totalResults>} value from arXiv's Atom response — the
 * authoritative count of papers matching the query (not capped by
 * {@code max_results_per_sync}, which only governs how many full
 * {@link Paper} rows we ingest for the "Latest" feed).
 *
 * <p>{@code TrendService} aggregates this table to build the monthly chart,
 * falling back to {@link Paper} aggregation only when this table is empty
 * (e.g. immediately after deploy, before the first backfill completes).
 */
@Entity
@Table(
    name = "monthly_topic_counts",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_mtc_source_topic_month",
        columnNames = {"source_id", "topic_code", "year_month"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class MonthlyTopicCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "topic_code", nullable = false, length = 64)
    private String topicCode;

    /** ISO month, "YYYY-MM". */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(nullable = false)
    private Long count;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
