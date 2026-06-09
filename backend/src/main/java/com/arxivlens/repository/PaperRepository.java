package com.arxivlens.repository;

import com.arxivlens.entity.Paper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaperRepository extends JpaRepository<Paper, Long> {

    /** Papers synced since {@code t} — drives the Workspace "new papers" notification. */
    long countByFetchedAtAfter(Instant t);

    Optional<Paper> findBySourceIdAndExternalId(Long sourceId, String externalId);

    /**
     * Duplicate guard for the manual / URL-import flow — same URL within a
     * source. Manual rows each get a fresh {@code manual-<uuid>} external id,
     * so the {@code (source_id, external_id)} unique key never catches a
     * re-add of the same article; we compare on the user-meaningful URL/title
     * instead.
     */
    Optional<Paper> findFirstBySourceIdAndUrl(Long sourceId, String url);

    /** Duplicate guard for the manual / URL-import flow — same title within a source (case-insensitive). */
    Optional<Paper> findFirstBySourceIdAndTitleIgnoreCase(Long sourceId, String title);

    /**
     * Existence-and-fetch variant of {@link #findExistingExternalIds} that returns
     * the managed entities, so the sync loop can both decide what to insert and
     * backfill the {@code categories} column on rows it already has.
     */
    List<Paper> findBySourceIdAndExternalIdIn(Long sourceId, Collection<String> externalIds);

    /**
     * Bulk existence check used by the arXiv sync pagination loop. Returns
     * the subset of {@code externalIds} that are already present for the
     * given source so the caller can filter the parse result before
     * persisting, instead of issuing one {@code findBySourceIdAndExternalId}
     * lookup per paper.
     */
    @Query("SELECT p.externalId FROM Paper p "
            + "WHERE p.sourceId = :sourceId AND p.externalId IN :externalIds")
    List<String> findExistingExternalIds(
            @Param("sourceId") Long sourceId,
            @Param("externalIds") List<String> externalIds);

    long countBySourceId(Long sourceId);

    /**
     * Used by the admin "Clear manual articles" action — narrows the cascade
     * delete to papers whose {@code externalId} was synthesized by the manual /
     * URL-import flow ({@code "manual-<uuid>"}).
     */
    List<Paper> findByExternalIdStartingWith(String prefix);

    /**
     * Oldest {@code publishedAt} for the source — used by {@code StartupSyncRunner}
     * to detect "shallow" datasets (only the last few days exist) that need a backfill
     * even though row count alone looks healthy.
     */
    @Query("SELECT MIN(p.publishedAt) FROM Paper p WHERE p.sourceId = :sourceId")
    Optional<Instant> findOldestPublishedAtBySourceId(@Param("sourceId") Long sourceId);

    @Query("""
            SELECT p FROM Paper p
            WHERE p.sourceId = :sourceId
              AND p.publishedAt >= :since
              AND (:topicCode IS NULL
                   OR p.topicCode = :topicCode
                   OR p.categories LIKE CONCAT('%,', :topicCode, ',%'))
            """)
    Page<Paper> findFeed(
            @Param("sourceId") Long sourceId,
            @Param("since") Instant since,
            @Param("topicCode") String topicCode,
            Pageable pageable
    );

    @Query("""
            SELECT p.topicCode AS topicCode, FUNCTION('DATE_FORMAT', p.publishedAt, '%Y-%m') AS yearMonth, COUNT(p) AS total
            FROM Paper p
            WHERE p.sourceId = :sourceId AND p.publishedAt >= :since
            GROUP BY p.topicCode, FUNCTION('DATE_FORMAT', p.publishedAt, '%Y-%m')
            ORDER BY yearMonth ASC
            """)
    List<Object[]> aggregateMonthlyByTopic(@Param("sourceId") Long sourceId, @Param("since") Instant since);
}
