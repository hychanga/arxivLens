package com.arxivlens.service;

import com.arxivlens.dto.PreferenceDtos.PreferenceResponse;
import com.arxivlens.dto.PreferenceDtos.PreferenceUpdateRequest;
import com.arxivlens.entity.User;
import com.arxivlens.entity.UserPreference;
import com.arxivlens.repository.UserPreferenceRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);

    /** Per-source keyword map shape: {@code {"arxiv":["llm","agent"], "hbr":["leadership"]}}. */
    private static final TypeReference<Map<String, List<String>>> KEYWORDS_TYPE = new TypeReference<>() {};

    private final UserPreferenceRepository prefs;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public PreferenceService(UserPreferenceRepository prefs, UserRepository users, ObjectMapper mapper) {
        this.prefs = prefs;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional
    public PreferenceResponse getOrCreate(Long userId) {
        return toResponse(loadOrCreate(userId));
    }

    @Transactional
    public PreferenceResponse update(Long userId, PreferenceUpdateRequest req) {
        UserPreference p = loadOrCreate(userId);
        if (req.queryDays() != null)        p.setQueryDays(req.queryDays());
        if (req.sortMode() != null)         p.setSortMode(req.sortMode());
        if (req.currentSourceId() != null)  p.setCurrentSourceId(req.currentSourceId());
        if (req.perPage() != null)          p.setPerPage(req.perPage());
        if (req.keywords() != null)         p.setKeywordsJson(serializeKeywords(req.keywords()));
        return toResponse(prefs.save(p));
    }

    private UserPreference loadOrCreate(Long userId) {
        return prefs.findById(userId).orElseGet(() -> {
            User u = users.findById(userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Stale session"));
            UserPreference p = new UserPreference();
            p.setUser(u);
            return prefs.save(p);
        });
    }

    private PreferenceResponse toResponse(UserPreference p) {
        return new PreferenceResponse(
                p.getQueryDays(),
                p.getSortMode(),
                parseKeywords(p.getKeywordsJson()),
                p.getCurrentSourceId(),
                p.getPerPage()
        );
    }

    /**
     * Tolerant parse:
     *   - {@code null} / blank → empty map
     *   - JSON object like {@code {"arxiv":[…]}} → map (current shape)
     *   - JSON array like {@code ["a","b"]} → migrate as the legacy single-source shape;
     *     dropped because we no longer know which source it belonged to. Returns empty map.
     */
    Map<String, List<String>> parseKeywords(String json) {
        if (json == null || json.isBlank()) return Map.of();
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            log.debug("Discarding legacy array-shaped keywords payload");
            return Map.of();
        }
        try {
            Map<String, List<String>> raw = mapper.readValue(trimmed, KEYWORDS_TYPE);
            // Defensively drop nulls.
            Map<String, List<String>> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k == null || k.isBlank()) return;
                List<String> list = new ArrayList<>();
                if (v != null) {
                    for (String s : v) {
                        if (s != null && !s.isBlank()) list.add(s);
                    }
                }
                out.put(k, Collections.unmodifiableList(list));
            });
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse keywords JSON, returning empty map: {}", e.getMessage());
            return Map.of();
        }
    }

    String serializeKeywords(Map<String, List<String>> keywords) {
        if (keywords == null || keywords.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(keywords);
        } catch (Exception e) {
            log.warn("Failed to serialize keywords map: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Legacy helper still used by {@code AiSummaryService} for {@code key_points} / {@code tags}
     * — those genuinely are flat arrays, not the per-source map shape.
     */
    static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"', '\\' -> sb.append('\\').append(c);
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }
}
