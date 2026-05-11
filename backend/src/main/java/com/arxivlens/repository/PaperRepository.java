package com.arxivlens.repository;

import com.arxivlens.entity.Paper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaperRepository extends JpaRepository<Paper, Long> {

    Optional<Paper> findBySourceIdAndExternalId(Long sourceId, String externalId);

    long countBySourceId(Long sourceId);

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
              AND (:topicCode IS NULL OR p.topicCode = :topicCode)
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
