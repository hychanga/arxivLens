package com.arxivlens.service;

import com.arxivlens.dto.FavoriteDtos.CreateFavoriteRequest;
import com.arxivlens.dto.FavoriteDtos.FavoriteView;
import com.arxivlens.dto.FavoriteDtos.UpdateNoteRequest;
import com.arxivlens.entity.AiSummary;
import com.arxivlens.entity.Favorite;
import com.arxivlens.entity.Paper;
import com.arxivlens.repository.AiSummaryRepository;
import com.arxivlens.repository.DownloadRepository;
import com.arxivlens.repository.FavoriteRepository;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FavoriteService {

    private final FavoriteRepository favorites;
    private final PaperRepository papers;
    private final AiSummaryRepository summaries;
    private final DownloadRepository downloads;
    private final UserRepository users;

    public FavoriteService(FavoriteRepository favorites,
                           PaperRepository papers,
                           AiSummaryRepository summaries,
                           DownloadRepository downloads,
                           UserRepository users) {
        this.favorites = favorites;
        this.papers = papers;
        this.summaries = summaries;
        this.downloads = downloads;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<FavoriteView> list(Long userId) {
        return favorites.findByUserIdOrderBySavedAtDesc(userId).stream()
                .map(f -> {
                    AiSummary s = summaries.findByFavoriteId(f.getId()).orElse(null);
                    boolean cached = downloads.findByUserIdAndPaper_Id(userId, f.getPaper().getId()).isPresent();
                    return FavoriteView.of(f, s, cached);
                })
                .toList();
    }

    @Transactional
    public FavoriteView create(Long userId, CreateFavoriteRequest req) {
        if (favorites.existsByUserIdAndPaper_Id(userId, req.paperId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Already favorited");
        }
        Paper p = papers.findById(req.paperId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Paper not found"));

        Favorite f = new Favorite();
        f.setUser(users.getReferenceById(userId));
        f.setPaper(p);
        f.setNote(req.note());
        favorites.save(f);

        boolean cached = downloads.findByUserIdAndPaper_Id(userId, p.getId()).isPresent();
        return FavoriteView.of(f, null, cached);
    }

    @Transactional
    public void delete(Long userId, Long favoriteId) {
        Favorite f = ownedFavoriteOrThrow(userId, favoriteId);
        // ai_summaries cascades on FK; downloads are independent and not removed.
        favorites.delete(f);
    }

    @Transactional
    public FavoriteView updateNote(Long userId, Long favoriteId, UpdateNoteRequest req) {
        Favorite f = ownedFavoriteOrThrow(userId, favoriteId);
        f.setNote(req.note());
        favorites.save(f);
        AiSummary s = summaries.findByFavoriteId(f.getId()).orElse(null);
        boolean cached = downloads.findByUserIdAndPaper_Id(userId, f.getPaper().getId()).isPresent();
        return FavoriteView.of(f, s, cached);
    }

    Favorite ownedFavoriteOrThrow(Long userId, Long favoriteId) {
        Favorite f = favorites.findById(favoriteId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Favorite not found"));
        if (!userId.equals(f.getUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not your favorite");
        }
        return f;
    }
}
