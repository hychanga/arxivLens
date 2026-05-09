package com.arxivlens.service;

import com.arxivlens.dto.SourceDtos.CreateSourceRequest;
import com.arxivlens.dto.SourceDtos.UpdateSourceRequest;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SourceService {

    private final SourceRepository sources;

    public SourceService(SourceRepository sources) {
        this.sources = sources;
    }

    @Transactional(readOnly = true)
    public List<Source> list(boolean enabledOnly) {
        return enabledOnly
                ? sources.findByEnabledTrueOrderByDisplayOrderAsc()
                : sources.findAllByOrderByDisplayOrderAsc();
    }

    @Transactional
    public Source create(CreateSourceRequest req) {
        if (sources.findByCode(req.code()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Source code already exists");
        }
        Source s = new Source();
        s.setCode(req.code());
        s.setName(req.name());
        s.setDescription(req.description());
        s.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        s.setDisplayOrder(req.displayOrder() == null ? 0 : req.displayOrder());
        return sources.save(s);
    }

    @Transactional
    public Source update(Long id, UpdateSourceRequest req) {
        Source s = sources.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Source not found"));
        if (req.name() != null)         s.setName(req.name());
        if (req.description() != null)  s.setDescription(req.description());
        if (req.enabled() != null)      s.setEnabled(req.enabled());
        if (req.displayOrder() != null) s.setDisplayOrder(req.displayOrder());
        return sources.save(s);
    }

    @Transactional
    public void delete(Long id) {
        if (!sources.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Source not found");
        }
        sources.deleteById(id);
    }
}
