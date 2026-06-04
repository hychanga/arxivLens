package com.arxivlens.bootstrap;

import com.arxivlens.entity.Setting;
import com.arxivlens.entity.Source;
import com.arxivlens.entity.Topic;
import com.arxivlens.entity.User;
import com.arxivlens.entity.UserPreference;
import com.arxivlens.repository.SettingRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.repository.TopicRepository;
import com.arxivlens.repository.UserPreferenceRepository;
import com.arxivlens.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds lookup data + the two demo accounts on first startup. All checks are
 * idempotent so the runner is safe to run on every restart.
 *
 * In dev (default profile) the {@code data.sql} script also seeds sources /
 * topics / settings. The Java seed below uses {@code count() == 0} guards so
 * it no-ops in that case. In prod ({@code SPRING_PROFILES_ACTIVE=prod}),
 * data.sql is disabled (see {@code application-prod.properties}) and the Java
 * seed becomes the only source of lookup data — that's why every prod-required
 * lookup has to be reproduced here.
 *
 * Real papers come from {@link StartupSyncRunner}, which fires the arXiv + HBR
 * syncs asynchronously after Spring is up.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SettingRepository settings;
    private final SourceRepository sources;
    private final TopicRepository topics;
    private final UserRepository users;
    private final UserPreferenceRepository prefs;
    private final PasswordEncoder encoder;

    public DataSeeder(SettingRepository settings,
                      SourceRepository sources,
                      TopicRepository topics,
                      UserRepository users,
                      UserPreferenceRepository prefs,
                      PasswordEncoder encoder) {
        this.settings = settings;
        this.sources = sources;
        this.topics = topics;
        this.users = users;
        this.prefs = prefs;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedSettings();
        seedSources();
        reconcileSourceNames();
        seedTopics();
        seedAdmin();
        seedDemoUser();
    }

    /**
     * One-off, self-deactivating renames for seeded sources whose display name
     * changed after they were first seeded. {@link #ensureSource} only inserts
     * missing rows, so a name change in code never reaches a DB that already
     * has the row — this nudges a known old value to the new one. It no-ops once
     * renamed, and only fires when the name still matches the exact old value,
     * so an admin's custom rename is never clobbered.
     */
    private void reconcileSourceNames() {
        renameIfMatches("hbr", "Harvard Business Review", "哈佛商業評論");
    }

    private void renameIfMatches(String code, String oldName, String newName) {
        sources.findByCode(code).ifPresent(s -> {
            if (oldName.equals(s.getName())) {
                s.setName(newName);
                sources.save(s);
                log.info("Renamed source {} → {}", code, newName);
            }
        });
    }

    // ---------- lookup data ----------

    private void seedSettings() {
        if (settings.existsById(1L)) return;
        Setting s = new Setting();
        s.setId(1L);
        s.setDefaultDays(7);
        s.setMaxResultsPerSync(50);
        s.setAutoRefreshIntervalMinutes(360);
        settings.save(s);
        log.info("Seeded settings row");
    }

    /**
     * Seeds each data source individually, inserting only the ones not already
     * present. NOT guarded by a blanket {@code count() > 0} check: that guard
     * meant a NEW source added in a later release never reached an existing prod
     * DB (which already had rows from the first deploy), so the source row — and
     * therefore its tab and its sync handler — silently never appeared. Keying
     * off {@code findByCode} makes the seeder additive and safe to re-run.
     */
    private void seedSources() {
        ensureSource("arxiv", "arXiv",
                "Computer science & physics preprints — synced every 6 hours.", 1);
        ensureSource("hbr", "哈佛商業評論",
                "Management & leadership insights via RSS.", 2);
        // Manual-paste source (no real auto-sync) — still needs its row in prod,
        // where data.sql is disabled and this Java seeder is the only seed path.
        ensureSource("businessweekly", "商業週刊",
                "搜尋商業週刊網站，依關鍵字抓取最新文章列表。", 3);
        ensureSource("mckinsey", "McKinsey Quarterly",
                "McKinsey Insights & Quarterly articles via RSS — synced daily.", 4);
    }

    /** Inserts the source if no row with {@code code} exists yet; otherwise no-ops. */
    private void ensureSource(String code, String name, String description, int displayOrder) {
        if (sources.findByCode(code).isPresent()) return;
        Source s = new Source();
        s.setCode(code);
        s.setName(name);
        s.setDescription(description);
        s.setEnabled(true);
        s.setDisplayOrder(displayOrder);
        sources.save(s);
        log.info("Seeded data source: {}", code);
    }

    private void seedTopics() {
        if (topics.count() > 0) return;
        Source arxiv = sources.findByCode("arxiv").orElseThrow();
        Source hbr = sources.findByCode("hbr").orElseThrow();

        // arXiv categories
        saveTopic(arxiv, "cs.AI", "Artificial Intelligence", true);
        saveTopic(arxiv, "cs.LG", "Machine Learning",        true);
        saveTopic(arxiv, "cs.CL", "Computation and Language", true);
        saveTopic(arxiv, "cs.CV", "Computer Vision",          true);
        saveTopic(arxiv, "cs.SE", "Software Engineering",     false);
        saveTopic(arxiv, "cs.DC", "Distributed Computing",    false);

        // HBR categories
        saveTopic(hbr, "leadership", "Leadership",             true);
        saveTopic(hbr, "strategy",   "Strategy",               true);
        saveTopic(hbr, "innovation", "Innovation",             true);
        saveTopic(hbr, "org",        "Organizational Culture", true);
        saveTopic(hbr, "data",       "Data & Analytics",       true);

        log.info("Seeded topics: 6 arXiv + 5 HBR");
    }

    private void saveTopic(Source src, String code, String name, boolean enabled) {
        Topic t = new Topic();
        t.setSource(src);
        t.setCode(code);
        t.setName(name);
        t.setEnabled(enabled);
        topics.save(t);
    }

    // ---------- demo accounts ----------

    private void seedAdmin() {
        if (users.existsByEmail("admin@arxivlens.local")) return;
        User u = new User();
        u.setEmail("admin@arxivlens.local");
        u.setPasswordHash(encoder.encode("admin123"));
        u.setDisplayName("Admin User");
        u.setRole("ADMIN");
        users.save(u);

        UserPreference pref = new UserPreference();
        pref.setUser(u);
        pref.setQueryDays(30);
        pref.setSortMode("NEWEST");
        pref.setKeywordsJson("{\"arxiv\":[\"llm\",\"transformer\",\"agent\"],\"hbr\":[\"governance\",\"ai\"]}");
        sources.findByCode("arxiv").ifPresent(s -> pref.setCurrentSourceId(s.getId()));
        prefs.save(pref);

        log.info("Seeded admin: admin@arxivlens.local / admin123");
    }

    private void seedDemoUser() {
        if (users.findByEmail("demo@arxivlens.local").isPresent()) return;
        User u = new User();
        u.setEmail("demo@arxivlens.local");
        u.setPasswordHash(encoder.encode("demo123"));
        u.setDisplayName("Demo User");
        u.setRole("USER");
        users.save(u);

        UserPreference pref = new UserPreference();
        pref.setUser(u);
        pref.setQueryDays(14);
        pref.setSortMode("RELEVANCE");
        pref.setKeywordsJson("{\"arxiv\":[\"vision\",\"agent\"],\"hbr\":[\"leadership\",\"strategy\",\"team\"]}");
        sources.findByCode("hbr").ifPresent(s -> pref.setCurrentSourceId(s.getId()));
        prefs.save(pref);

        log.info("Seeded demo user: demo@arxivlens.local / demo123");
    }
}
