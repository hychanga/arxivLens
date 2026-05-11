package com.arxivlens.repository;

import com.arxivlens.entity.MonthlyTopicCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthlyTopicCountRepository extends JpaRepository<MonthlyTopicCount, Long> {

    List<MonthlyTopicCount> findBySourceId(Long sourceId);

    Optional<MonthlyTopicCount> findBySourceIdAndTopicCodeAndYearMonth(
            Long sourceId, String topicCode, String yearMonth);
}
