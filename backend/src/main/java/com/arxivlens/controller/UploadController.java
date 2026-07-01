package com.arxivlens.controller;

import com.arxivlens.service.GcsUploadService;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final GcsUploadService gcs;

    public UploadController(Optional<GcsUploadService> gcs) {
        this.gcs = gcs.orElse(null);
    }

    @PostMapping("/pdf")
    public Map<String, String> uploadPdf(@RequestParam("file") MultipartFile file) {
        if (gcs == null)
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                    "PDF upload not configured — set GCS_BUCKET environment variable");
        if (file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "No file provided");
        String ct = file.getContentType();
        if (!"application/pdf".equals(ct))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only PDF files accepted");
        try {
            String url = gcs.uploadPdf(file.getOriginalFilename(), file.getBytes());
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed: " + e.getMessage());
        }
    }
}
