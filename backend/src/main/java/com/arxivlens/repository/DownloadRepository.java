package com.arxivlens.repository;

import com.arxivlens.entity.Download;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** {@link EntityGraph} keeps the {@code Download.paper} relationship eagerly fetched
 *  so DownloadView serialization survives transaction commit. */
public interface DownloadRepository extends JpaRepository<Download, Long> {

    @EntityGraph(attributePaths = "paper")
    List<Download> findByUserIdOrderByDownloadedAtDesc(Long userId);

    @EntityGraph(attributePaths = "paper")
    Optional<Download> findByUserIdAndPaper_Id(Long userId, Long paperId);

    void deleteByUserIdAndPaper_Id(Long userId, Long paperId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
