package com.arxivlens.repository;

import com.arxivlens.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceRepository extends JpaRepository<Source, Long> {

    Optional<Source> findByCode(String code);

    List<Source> findAllByOrderByDisplayOrderAsc();

    List<Source> findByEnabledTrueOrderByDisplayOrderAsc();
}
