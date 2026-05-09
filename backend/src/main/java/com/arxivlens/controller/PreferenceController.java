package com.arxivlens.controller;

import com.arxivlens.dto.PreferenceDtos.PreferenceResponse;
import com.arxivlens.dto.PreferenceDtos.PreferenceUpdateRequest;
import com.arxivlens.service.PreferenceService;
import com.arxivlens.web.AuthUtil;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public PreferenceResponse get() {
        return service.getOrCreate(AuthUtil.currentUserId());
    }

    @PatchMapping
    public PreferenceResponse update(@Valid @RequestBody PreferenceUpdateRequest req) {
        return service.update(AuthUtil.currentUserId(), req);
    }
}
