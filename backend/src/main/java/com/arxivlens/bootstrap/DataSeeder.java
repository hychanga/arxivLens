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
        seedTopics();
        seedAdmin();
        seedDemoUser();
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

    private void seedSources() {
        if (sources.count() > 0) return;

        Source arxiv = new Source();
        arxiv.setCode("arxiv");
        arxiv.setName("arXiv");
        arxiv.setDescription("Computer science & physics preprints — synced every 6 hours.");
        arxiv.setEnabled(true);
        arxiv.setDisplayOrder(1);
        sources.save(arxiv);

        Source hbr = new Source();
        hbr.setCode("hbr");
        hbr.setName("Harvard Business Review");
        hbr.setDescription("Management & leadership insights via RSS.");
        hbr.setEnabled(true);
        hbr.setDisplayOrder(2);
        sources.save(hbr);

        // Business Weekly is a manual-paste source (no auto-sync handler beyond a
        // no-op) — but it still needs its row seeded in prod, where data.sql is
        // disabled and this Java seeder is the only source of lookup data.
        Source businessWeekly = new Source();
        businessWeekly.setCode("businessweekly");
        businessWeekly.setName("商業週刊");
        businessWeekly.setDescription("搜尋商業週刊網站，依關鍵字抓取最新文章列表。");
        businessWeekly.setEnabled(true);
        businessWeekly.setDisplayOrder(3);
        sources.save(businessWeekly);

        Source mckinsey = new Source();
        mckinsey.setCode("mckinsey");
        mckinsey.setName("McKinsey Quarterly");
        mckinsey.setDescription("McKinsey Insights & Quarterly articles via RSS — synced daily.");
        mckinsey.setEnabled(true);
        mckinsey.setDisplayOrder(4);
        sources.save(mckinsey);

        log.info("Seeded data sources: arxiv, hbr, businessweekly, mckinsey");
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
