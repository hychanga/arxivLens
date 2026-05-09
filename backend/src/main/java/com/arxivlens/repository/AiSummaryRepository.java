package com.arxivlens.repository;

import com.arxivlens.entity.AiSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiSummaryRepository extends JpaRepository<AiSummary, Long> {

    Optional<AiSummary> findByFavoriteId(Long favoriteId);
}
