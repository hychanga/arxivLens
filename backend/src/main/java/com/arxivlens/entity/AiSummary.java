package com.arxivlens.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * AI-generated summary tied to a Favorite.
 *
 * Wire shape — see arxivLens-requirements §11:
 *   - {@code key_points} and {@code tags} are stored as JSON strings in the DB,
 *     but exposed as {@code List<String>} via getters annotated with {@code @JsonProperty}.
 *   - The raw string fields are {@code @JsonIgnore} so they don't leak into responses.
 */
@Entity
@Table(name = "ai_summaries")
@Getter
@Setter
@NoArgsConstructor
public class AiSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "favorite_id", nullable = false, unique = true)
    private Favorite favorite;

    @Column(name = "favorite_id", insertable = false, updatable = false)
    private Long favoriteId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @JsonIgnore
    @Column(name = "key_points", nullable = false, columnDefinition = "JSON")
    private String keyPointsJson;

    @JsonIgnore
    @Column(name = "tags", nullable = false, columnDefinition = "JSON")
    private String tagsJson;

    @Column(length = 32)
    private String difficulty;

    @Column(name = "reading_time_min")
    private Integer readingTimeMin;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JsonProperty("key_points")
    public List<String> getKeyPoints() {
        return parse(keyPointsJson);
    }

    @JsonProperty("tags")
    public List<String> getTags() {
        return parse(tagsJson);
    }

    /**
     * Parses a JSON array of strings (e.g. {@code ["a","b","c"]}) into a {@code List<String>}.
     * Hand-rolled to avoid pulling in a databind dependency just for entity getters.
     * Tolerant of whitespace and standard JSON string escapes.
     */
    static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        String s = json.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);

        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                switch (c) {
                    case '"', '\\', '/' -> cur.append(c);
                    case 'n' -> cur.append('\n');
                    case 't' -> cur.append('\t');
                    case 'r' -> cur.append('\r');
                    case 'b' -> cur.append('\b');
                    case 'f' -> cur.append('\f');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            cur.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> cur.append(c);
                }
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                if (inString) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                inString = !inString;
                continue;
            }
            if (inString) cur.append(c);
        }
        return out;
    }
}
