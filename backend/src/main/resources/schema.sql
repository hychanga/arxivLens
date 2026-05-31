-- arxivLens — schema (MySQL 8)
-- Drop in dependency-safe order; recreate.

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS ai_summaries;
DROP TABLE IF EXISTS downloads;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS paper_translations;
DROP TABLE IF EXISTS papers;
DROP TABLE IF EXISTS user_preferences;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS topics;
DROP TABLE IF EXISTS data_sources;
DROP TABLE IF EXISTS settings;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE settings (
    id                            BIGINT       NOT NULL PRIMARY KEY,
    default_days                  INT          NOT NULL DEFAULT 7,
    max_results_per_sync          INT          NOT NULL DEFAULT 50,
    auto_refresh_interval_minutes INT          NOT NULL DEFAULT 360
);

CREATE TABLE data_sources (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    is_enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    display_order INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE topics (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id  BIGINT       NOT NULL,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    is_enabled TINYINT(1)   NOT NULL DEFAULT 1,
    UNIQUE KEY uk_topic_source_code (source_id, code),
    CONSTRAINT fk_topics_source FOREIGN KEY (source_id) REFERENCES data_sources(id) ON DELETE CASCADE
);

CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(190) NOT NULL UNIQUE,
    password_hash   VARCHAR(255),
    display_name    VARCHAR(128),
    oauth_provider  VARCHAR(32),
    oauth_subject   VARCHAR(190),
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_oauth (oauth_provider, oauth_subject)
);

CREATE TABLE user_preferences (
    user_id            BIGINT     NOT NULL PRIMARY KEY,
    query_days         INT        NOT NULL DEFAULT 7,
    sort_mode          VARCHAR(32) NOT NULL DEFAULT 'NEWEST',
    keywords_json      JSON,
    current_source_id  BIGINT,
    per_page           INT        NOT NULL DEFAULT 10,
    updated_at         DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_pref_user   FOREIGN KEY (user_id)           REFERENCES users(id)        ON DELETE CASCADE,
    CONSTRAINT fk_pref_source FOREIGN KEY (current_source_id) REFERENCES data_sources(id) ON DELETE SET NULL
);

CREATE TABLE papers (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id     BIGINT        NOT NULL,
    external_id   VARCHAR(190)  NOT NULL,
    title         VARCHAR(512)  NOT NULL,
    authors_json  JSON          NOT NULL,
    abstract_text TEXT          NOT NULL,
    introduction  TEXT,
    conclusion    TEXT,
    url           VARCHAR(512),
    pdf_url       VARCHAR(512),
    page_count    INT,
    topic_code    VARCHAR(64),
    categories    VARCHAR(512),
    published_at  DATETIME      NOT NULL,
    fetched_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_papers_source_external (source_id, external_id),
    KEY idx_papers_published (published_at),
    KEY idx_papers_topic (topic_code),
    CONSTRAINT fk_papers_source FOREIGN KEY (source_id) REFERENCES data_sources(id) ON DELETE CASCADE
);

CREATE TABLE favorites (
    id        BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT    NOT NULL,
    paper_id  BIGINT    NOT NULL,
    note      TEXT,
    saved_at  DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fav_user_paper (user_id, paper_id),
    CONSTRAINT fk_fav_user  FOREIGN KEY (user_id)  REFERENCES users(id)  ON DELETE CASCADE,
    CONSTRAINT fk_fav_paper FOREIGN KEY (paper_id) REFERENCES papers(id) ON DELETE CASCADE
);

CREATE TABLE ai_summaries (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    favorite_id     BIGINT       NOT NULL UNIQUE,
    summary         TEXT         NOT NULL,
    key_points      JSON         NOT NULL,
    tags            JSON         NOT NULL,
    difficulty      VARCHAR(32),
    reading_time_min INT,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_favorite FOREIGN KEY (favorite_id) REFERENCES favorites(id) ON DELETE CASCADE
);

CREATE TABLE downloads (
    id            BIGINT     NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT     NOT NULL,
    paper_id      BIGINT     NOT NULL,
    file_path     VARCHAR(512) NOT NULL,
    size_mb       DOUBLE     NOT NULL,
    downloaded_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dl_user_paper (user_id, paper_id),
    CONSTRAINT fk_dl_user  FOREIGN KEY (user_id)  REFERENCES users(id)  ON DELETE CASCADE,
    CONSTRAINT fk_dl_paper FOREIGN KEY (paper_id) REFERENCES papers(id) ON DELETE CASCADE
);

CREATE TABLE paper_translations (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    paper_id      BIGINT       NOT NULL,
    locale        VARCHAR(10)  NOT NULL,
    title         VARCHAR(512),
    abstract_text TEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_translation_paper_locale (paper_id, locale),
    CONSTRAINT fk_translation_paper FOREIGN KEY (paper_id) REFERENCES papers(id) ON DELETE CASCADE
);
