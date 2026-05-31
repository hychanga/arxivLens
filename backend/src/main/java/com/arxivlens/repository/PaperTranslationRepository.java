package com.arxivlens.repository;

import com.arxivlens.entity.PaperTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaperTranslationRepository extends JpaRepository<PaperTranslation, Long> {

    Optional<PaperTranslation> findByPaperIdAndLocale(Long paperId, String locale);

    /** Batch variant — one query for the cached translations of many papers in a locale. */
    List<PaperTranslation> findByPaperIdInAndLocale(Collection<Long> paperIds, String locale);

    /** Cascades when the parent Paper is deleted. */
    void deleteByPaperId(Long paperId);
}
