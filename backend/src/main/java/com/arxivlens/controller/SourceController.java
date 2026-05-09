package com.arxivlens.controller;

import com.arxivlens.dto.SourceDtos.CreateSourceRequest;
import com.arxivlens.dto.SourceDtos.UpdateSourceRequest;
import com.arxivlens.entity.Source;
import com.arxivlens.service.SourceService;
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
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService service;

    public SourceController(SourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<Source> list(@RequestParam(name = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        return service.list(enabledOnly);
    }

    @PostMapping
    public ResponseEntity<Source> create(@Valid @RequestBody CreateSourceRequest req) {
        return ResponseEntity.status(201).body(service.create(req));
    }

    @PutMapping("/{id}")
    public Source update(@PathVariable Long id, @Valid @RequestBody UpdateSourceRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
