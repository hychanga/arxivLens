package com.arxivlens.repository;

import com.arxivlens.entity.Favorite;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * All read methods that feed a response containing the {@code Favorite.paper}
 * relationship use {@link EntityGraph} to eagerly fetch the paper. Without this,
 * the lazy proxy is still attached when Jackson serializes after the
 * {@code @Transactional} method returns, triggering
 * {@code LazyInitializationException}.
 */
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    @EntityGraph(attributePaths = "paper")
    List<Favorite> findByUserIdOrderBySavedAtDesc(Long userId);

    @EntityGraph(attributePaths = "paper")
    Optional<Favorite> findByUserIdAndPaper_Id(Long userId, Long paperId);

    boolean existsByUserIdAndPaper_Id(Long userId, Long paperId);

    @EntityGraph(attributePaths = "paper")
    @Override
    Optional<Favorite> findById(Long id);
}
