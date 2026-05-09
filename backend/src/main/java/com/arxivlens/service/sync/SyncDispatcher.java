package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SyncDispatcher {

    private final Map<String, SourceSyncService> byCode;
    private final SourceRepository sources;

    public SyncDispatcher(List<SourceSyncService> services, SourceRepository sources) {
        this.byCode = services.stream().collect(Collectors.toMap(SourceSyncService::sourceCode, Function.identity()));
        this.sources = sources;
    }

    public SyncResult syncByCode(String code) {
        SourceSyncService svc = byCode.get(code);
        if (svc == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No sync handler registered for source: " + code);
        }
        return svc.sync();
    }

    public SyncResult syncById(Long sourceId) {
        Source s = sources.findById(sourceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Source not found"));
        return syncByCode(s.getCode());
    }

    public List<SyncResult> syncAllEnabled() {
        return sources.findByEnabledTrueOrderByDisplayOrderAsc().stream()
                .map(s -> {
                    SourceSyncService svc = byCode.get(s.getCode());
                    if (svc == null) return new SyncResult(s.getCode(), 0, 0, 0, "no handler");
                    return svc.sync();
                })
                .toList();
    }
}
