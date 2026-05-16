package com.arxivlens.controller;

import com.arxivlens.dto.FavoriteDtos.AiSummaryView;
import com.arxivlens.dto.FavoriteDtos.CreateFavoriteRequest;
import com.arxivlens.dto.FavoriteDtos.FavoriteView;
import com.arxivlens.dto.FavoriteDtos.UpdateNoteRequest;
import com.arxivlens.service.AiSummaryService;
import com.arxivlens.service.FavoriteService;
import com.arxivlens.web.AuthUtil;
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
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteService favorites;
    private final AiSummaryService summaries;

    public FavoriteController(FavoriteService favorites, AiSummaryService summaries) {
        this.favorites = favorites;
        this.summaries = summaries;
    }

    @GetMapping
    public List<FavoriteView> list() {
        return favorites.list(AuthUtil.currentUserId());
    }

    @PostMapping
    public ResponseEntity<FavoriteView> create(@Valid @RequestBody CreateFavoriteRequest req) {
        return ResponseEntity.status(201).body(favorites.create(AuthUtil.currentUserId(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        favorites.delete(AuthUtil.currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/note")
    public FavoriteView updateNote(@PathVariable Long id, @Valid @RequestBody UpdateNoteRequest req) {
        return favorites.updateNote(AuthUtil.currentUserId(), id, req);
    }

    @PostMapping("/{id}/summary")
    public AiSummaryView generateSummary(
            @PathVariable Long id,
            @RequestParam(name = "locale", required = false) String locale) {
        return summaries.generate(AuthUtil.currentUserId(), id, locale);
    }
}
