package com.arxivlens.repository;

import com.arxivlens.entity.DownloadBlob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadBlobRepository extends JpaRepository<DownloadBlob, Long> {
}
