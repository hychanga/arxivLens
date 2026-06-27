package com.arxivlens.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnExpression("'${app.gcs.bucket:}' != ''")
public class GcsUploadService {

    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final String bucket;

    public GcsUploadService(@Value("${app.gcs.bucket}") String bucket) {
        this.bucket = bucket;
    }

    public String uploadPdf(String originalFilename, byte[] bytes) {
        String blobName = "golf-pdfs/" + UUID.randomUUID() + "-" + sanitize(originalFilename);
        BlobId blobId = BlobId.of(bucket, blobName);
        BlobInfo info = BlobInfo.newBuilder(blobId)
                .setContentType("application/pdf")
                .build();
        storage.create(info, bytes);
        return "https://storage.googleapis.com/" + bucket + "/" + blobName;
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "document.pdf";
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.toLowerCase().endsWith(".pdf") ? safe : safe + ".pdf";
    }
}
