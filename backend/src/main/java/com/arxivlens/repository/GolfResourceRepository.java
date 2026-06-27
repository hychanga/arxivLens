package com.arxivlens.repository;

import com.arxivlens.entity.GolfResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface GolfResourceRepository extends JpaRepository<GolfResource, Long> {
    List<GolfResource> findAllByOrderByCreatedAtDesc();

    @Query("SELECT r FROM GolfResource r WHERE "
         + "LOWER(r.title)    LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(r.summary)  LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(r.content)  LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(r.tags)     LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(r.category) LIKE LOWER(CONCAT('%', :q, '%')) "
         + "ORDER BY r.createdAt DESC")
    List<GolfResource> search(@Param("q") String q);
}
