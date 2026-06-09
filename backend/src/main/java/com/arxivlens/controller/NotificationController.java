package com.arxivlens.controller;

import com.arxivlens.entity.User;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.service.GoogleTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Notification feed for the Workspace Gateway. The Gateway calls this
 * server-side with the signed-in user's Google id_token in {@code X-Workspace-Id-Token}
 * (no Authorization header, so the JWT filter is bypassed and this stays a
 * stateless read — no session is minted). Returns a plain JSON array; the
 * Gateway tags each item with its source and merges across apps.
 * NB: avoid an {@code X-Google-*} header name — GCP strips that reserved prefix
 * before the request reaches Cloud Run.
 *
 * <p>Degrades to an empty list on any verification problem so one app can never
 * break the aggregated feed.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final GoogleTokenVerifier google;
    private final UserRepository users;
    private final PaperRepository papers;

    public NotificationController(GoogleTokenVerifier google, UserRepository users, PaperRepository papers) {
        this.google = google;
        this.users = users;
        this.papers = papers;
    }

    public record NotificationDto(String id, String title, String body, String at, boolean unread) {}

    @GetMapping
    public List<NotificationDto> list(
            @RequestHeader(value = "X-Workspace-Id-Token", required = false) String idToken) {
        if (idToken == null || idToken.isBlank()) return List.of();

        String email;
        try {
            email = google.verify(idToken).email().toLowerCase().trim();
        } catch (Exception e) {
            log.debug("notification token verify failed: {}", e.getMessage());
            return List.of();
        }

        Optional<User> user = users.findByEmail(email);
        if (user.isEmpty()) return List.of();

        List<NotificationDto> out = new ArrayList<>();
        Instant now = Instant.now();
        long fresh = papers.countByFetchedAtAfter(now.minus(24, ChronoUnit.HOURS));
        if (fresh > 0) {
            out.add(new NotificationDto(
                    "arxivlens-new-papers",
                    "今日新論文",
                    fresh + " 篇新論文已同步，AI 摘要已就緒。",
                    now.toString(),
                    true));
        }
        return out;
    }
}
