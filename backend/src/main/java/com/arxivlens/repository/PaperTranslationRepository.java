package com.arxivlens.repository;

import com.arxivlens.entity.PaperTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperTranslationRepository extends JpaRepository<PaperTranslation, Long> {

    Optional<PaperTranslation> findByPaperIdAndLocale(Long paperId, String locale);
}
