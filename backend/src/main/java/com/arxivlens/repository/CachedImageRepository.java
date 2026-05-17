package com.arxivlens.repository;

import com.arxivlens.entity.CachedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CachedImageRepository extends JpaRepository<CachedImage, Long> {

    Optional<CachedImage> findByUrlHash(String urlHash);
}
