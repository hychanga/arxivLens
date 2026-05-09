package com.arxivlens.controller;

import com.arxivlens.dto.TopicDtos.CreateTopicRequest;
import com.arxivlens.dto.TopicDtos.UpdateTopicRequest;
import com.arxivlens.entity.Topic;
import com.arxivlens.service.TopicService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/topics")
public class TopicController {

    private final TopicService service;

    public TopicController(TopicService service) {
        this.service = service;
    }

    @GetMapping
    public List<Topic> list(
            @RequestParam(name = "sourceId") Long sourceId,
            @RequestParam(name = "enabledOnly", defaultValue = "false") boolean enabledOnly
    ) {
        return service.listBySource(sourceId, enabledOnly);
    }

    @PostMapping
    public ResponseEntity<Topic> create(@Valid @RequestBody CreateTopicRequest req) {
        return ResponseEntity.status(201).body(service.create(req));
    }

    @PutMapping("/{id}")
    public Topic update(@PathVariable Long id, @Valid @RequestBody UpdateTopicRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
