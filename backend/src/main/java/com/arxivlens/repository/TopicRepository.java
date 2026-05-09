package com.arxivlens.repository;

import com.arxivlens.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    List<Topic> findBySourceId(Long sourceId);

    List<Topic> findBySourceIdAndEnabledTrue(Long sourceId);
}
