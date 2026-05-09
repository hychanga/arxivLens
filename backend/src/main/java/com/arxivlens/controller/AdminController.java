package com.arxivlens.controller;

import com.arxivlens.dto.AdminDtos.SettingsView;
import com.arxivlens.dto.AdminDtos.UpdateSettingsRequest;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.service.SettingService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SettingService settings;
    private final PaperRepository papers;

    public AdminController(SettingService settings, PaperRepository papers) {
        this.settings = settings;
        this.papers = papers;
    }

    @GetMapping("/settings")
    public SettingsView get() {
        return settings.get();
    }

    @PutMapping("/settings")
    public SettingsView update(@Valid @RequestBody UpdateSettingsRequest req) {
        return settings.update(req);
    }

    @PostMapping("/settings/reset")
    public SettingsView reset() {
        return settings.reset();
    }

    /** Clears all paper rows. Next sync will re-fetch. */
    @DeleteMapping("/papers")
    @Transactional
    public Map<String, Long> clearPapers() {
        long count = papers.count();
        papers.deleteAllInBatch();
        return Map.of("removed", count);
    }
}
