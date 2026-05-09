package com.arxivlens.service;

import com.arxivlens.dto.TopicDtos.CreateTopicRequest;
import com.arxivlens.dto.TopicDtos.UpdateTopicRequest;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.repository.TopicRepository;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TopicService {

    private final TopicRepository topics;
    private final SourceRepository sources;

    public TopicService(TopicRepository topics, SourceRepository sources) {
        this.topics = topics;
        this.sources = sources;
    }

    @Transactional(readOnly = true)
    public List<Topic> listBySource(Long sourceId, boolean enabledOnly) {
        return enabledOnly
                ? topics.findBySourceIdAndEnabledTrue(sourceId)
                : topics.findBySourceId(sourceId);
    }

    @Transactional
    public Topic create(CreateTopicRequest req) {
        Source s = sources.findById(req.sourceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Source not found"));
        Topic t = new Topic();
        t.setSource(s);
        t.setCode(req.code());
        t.setName(req.name());
        t.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        return topics.save(t);
    }

    @Transactional
    public Topic update(Long id, UpdateTopicRequest req) {
        Topic t = topics.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found"));
        if (req.name() != null)    t.setName(req.name());
        if (req.enabled() != null) t.setEnabled(req.enabled());
        return topics.save(t);
    }

    @Transactional
    public void delete(Long id) {
        if (!topics.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Topic not found");
        }
        topics.deleteById(id);
    }
}
