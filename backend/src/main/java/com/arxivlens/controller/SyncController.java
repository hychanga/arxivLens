package com.arxivlens.controller;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.service.sync.SyncDispatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/papers/sync")
public class SyncController {

    private final SyncDispatcher dispatcher;

    public SyncController(SyncDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/{sourceId}")
    public SyncResult sync(@PathVariable Long sourceId) {
        return dispatcher.syncById(sourceId);
    }
}
