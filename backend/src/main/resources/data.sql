-- arxivLens — seed data (MySQL 8)
-- Idempotent: uses INSERT IGNORE.

-- ----- settings (single row) -----
INSERT IGNORE INTO settings (id, default_days, max_results_per_sync, auto_refresh_interval_minutes)
VALUES (1, 7, 50, 360);

-- ----- data_sources -----
-- created_at must be set explicitly: the column is NOT NULL with no SQL DEFAULT,
-- so omitting it lets non-strict MySQL / TiDB write 0000-00-00 which the JDBC
-- driver later refuses to read ("Zero date value prohibited" → 500).
INSERT IGNORE INTO data_sources (id, code, name, description, is_enabled, display_order, created_at) VALUES
  (1, 'arxiv',          'arXiv',                    'Computer science & physics preprints — synced every 6 hours.', 1, 1, NOW(6)),
  (2, 'hbr',            '哈佛商業評論',              'Management & leadership insights via RSS.',                    1, 2, NOW(6)),
  (3, 'businessweekly', '商業週刊',                  '搜尋商業週刊網站，依關鍵字抓取最新文章列表。',                     1, 3, NOW(6)),
  (4, 'mckinsey',       'McKinsey Quarterly',       'McKinsey Insights & Quarterly articles via RSS — synced daily.', 1, 4, NOW(6)),
  (5, 'managertoday',   '經理人雜誌',                '經理人 Manager Today 每日學管理電子報精選文章（透過 Gmail 日報擷取）。', 1, 5, NOW(6));

-- ----- topics -----
-- arXiv categories
INSERT IGNORE INTO topics (source_id, code, name, is_enabled) VALUES
  (1, 'cs.AI', 'Artificial Intelligence',     1),
  (1, 'cs.LG', 'Machine Learning',            1),
  (1, 'cs.CL', 'Computation and Language',    1),
  (1, 'cs.CV', 'Computer Vision',             1),
  (1, 'cs.SE', 'Software Engineering',        0),
  (1, 'cs.DC', 'Distributed Computing',       0);
-- HBR categories
INSERT IGNORE INTO topics (source_id, code, name, is_enabled) VALUES
  (2, 'leadership', 'Leadership',              1),
  (2, 'strategy',   'Strategy',                1),
  (2, 'innovation', 'Innovation',              1),
  (2, 'org',        'Organizational Culture',  1),
  (2, 'data',       'Data & Analytics',        1);

-- NOTE: papers / favorites / ai_summaries / downloads are NOT seeded here.
-- StartupSyncRunner triggers an async sync against arXiv + HBR right after the
-- application starts so users see real, recent papers on first login. Demo seed
-- papers were removed because their captions did not match the real documents
-- the URLs would have served.
-- users + user_preferences are seeded programmatically by DataSeeder so password
-- hashes are produced with a real BCryptPasswordEncoder.
