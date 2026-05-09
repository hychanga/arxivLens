package com.arxivlens.service;

import com.arxivlens.dto.AdminDtos.SettingsView;
import com.arxivlens.dto.AdminDtos.UpdateSettingsRequest;
import com.arxivlens.entity.Setting;
import com.arxivlens.repository.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {

    private static final long ROW_ID = 1L;

    private final SettingRepository repo;

    public SettingService(SettingRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public SettingsView get() {
        return toView(loadOrDefault());
    }

    @Transactional
    public SettingsView update(UpdateSettingsRequest req) {
        Setting s = loadOrDefault();
        if (req.defaultDays() != null)                  s.setDefaultDays(req.defaultDays());
        if (req.maxResultsPerSync() != null)            s.setMaxResultsPerSync(req.maxResultsPerSync());
        if (req.autoRefreshIntervalMinutes() != null)   s.setAutoRefreshIntervalMinutes(req.autoRefreshIntervalMinutes());
        return toView(repo.save(s));
    }

    @Transactional
    public SettingsView reset() {
        Setting s = loadOrDefault();
        s.setDefaultDays(7);
        s.setMaxResultsPerSync(50);
        s.setAutoRefreshIntervalMinutes(360);
        return toView(repo.save(s));
    }

    private Setting loadOrDefault() {
        return repo.findById(ROW_ID).orElseGet(() -> {
            Setting s = new Setting();
            s.setId(ROW_ID);
            s.setDefaultDays(7);
            s.setMaxResultsPerSync(50);
            s.setAutoRefreshIntervalMinutes(360);
            return repo.save(s);
        });
    }

    private static SettingsView toView(Setting s) {
        return new SettingsView(s.getDefaultDays(), s.getMaxResultsPerSync(), s.getAutoRefreshIntervalMinutes());
    }
}
