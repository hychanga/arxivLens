package com.arxivlens.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HTML → "article" extractor. No jsoup dependency — we lean on
 * regex because the document structure we care about is shallow: a
 * {@code <title>} or {@code og:title}, an {@code <article>}/{@code <main>}
 * container, and an optional {@code article:published_time} meta tag.
 *
 * <p>Best for blog posts and news articles whose markup follows the
 * "one main content block" convention. Doesn't try to be a general-purpose
 * HTML cleaner — sites that ship their content inside a deeply nested
 * SPA shell (or behind a paywall) will give noisier output that the user
 * can clean up manually before saving.
 *
 * <p>Side note on why we don't use jsoup: each transitive jar adds JVM
 * startup time on Render Free's 0.1 CPU shared tier, where every 100ms
 * counts during the 30-second cold-start budget. For this single feature
 * the regex approach is good enough.
 */
public final class HtmlExtractor {

    private HtmlExtractor() {}

    public record ExtractedArticle(String title, String content, Instant publishedAt) {}

    private static final Pattern TITLE_TAG = Pattern.compile(
            "<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);

    // og:title can have property/content in either order, optionally with extra attrs between.
    private static final Pattern OG_TITLE_PC = Pattern.compile(
            "<meta\\s+[^>]*property\\s*=\\s*[\"']og:title[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE_CP = Pattern.compile(
            "<meta\\s+[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*property\\s*=\\s*[\"']og:title[\"']",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ordered list of "where to look for a publish date" patterns. We try every
     * one and return the first that parses — HBR Taiwan doesn't ship
     * {@code article:published_time}, so the JSON-LD {@code datePublished} block
     * and the {@code <time datetime>} element matter just as much.
     */
    private static final Pattern[] PUBLISHED_PATTERNS = {
            // <meta property="article:published_time" content="..."> (both attr orders)
            Pattern.compile(
                    "<meta\\s+[^>]*property\\s*=\\s*[\"']article:published_time[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "<meta\\s+[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*property\\s*=\\s*[\"']article:published_time[\"']",
                    Pattern.CASE_INSENSITIVE),
            // <meta itemprop="datePublished" content="...">
            Pattern.compile(
                    "<meta\\s+[^>]*itemprop\\s*=\\s*[\"']datePublished[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
            // <meta name="pubdate" / "publishdate" / "date" content="...">
            Pattern.compile(
                    "<meta\\s+[^>]*name\\s*=\\s*[\"'](?:pubdate|publishdate|date)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
            // JSON-LD: "datePublished":"2026-05-10T..." — appears in schema.org Article blocks.
            Pattern.compile(
                    "\"datePublished\"\\s*:\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE),
            // <time datetime="...">  — fallback for sites that only mark up the date in markup.
            Pattern.compile(
                    "<time\\b[^>]*\\sdatetime\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
    };

    /** Matches yyyy/MM/dd, yyyy-MM-dd, yyyy.MM.dd in the middle of a longer string. */
    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})");

    /** Matches yyyy年MM月dd日 — common on HBR Taiwan article meta. */
    private static final Pattern CHINESE_DATE = Pattern.compile(
            "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    private static final Pattern ARTICLE_TAG = Pattern.compile(
            "<article\\b[^>]*>([\\s\\S]*?)</article>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIN_TAG = Pattern.compile(
            "<main\\b[^>]*>([\\s\\S]*?)</main>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_TAG = Pattern.compile(
            "<body\\b[^>]*>([\\s\\S]*?)</body>", Pattern.CASE_INSENSITIVE);

    // Tags whose content is noise rather than article body. Drop the whole element.
    private static final Pattern NOISE_TAGS = Pattern.compile(
            "<(script|style|noscript|svg|header|nav|footer|aside|form|button|figure|figcaption|iframe)\\b[^>]*>" +
            "[\\s\\S]*?</\\1>",
            Pattern.CASE_INSENSITIVE);

    // Self-closing variants of the same (e.g. `<svg .../>`).
    private static final Pattern NOISE_VOID = Pattern.compile(
            "<(script|style|noscript|svg|header|nav|footer|aside|form|button|figure|figcaption|iframe)\\b[^/>]*/>",
            Pattern.CASE_INSENSITIVE);

    // Block-level tags whose boundaries are paragraph breaks once we strip markup.
    private static final Pattern BLOCK_BOUNDARIES = Pattern.compile(
            "<(?:/?(p|h[1-6]|div|li|tr|blockquote|section|article|br|hr))(?:\\s[^>]*)?>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    public static ExtractedArticle extract(String html) {
        if (html == null) {
            return new ExtractedArticle("", "", null);
        }
        String title = pickTitle(html);
        Instant publishedAt = pickPublishedAt(html);
        String content = postProcess(pickContent(html), title);
        return new ExtractedArticle(title, content, publishedAt);
    }

    private static String pickTitle(String html) {
        String og = firstGroup(OG_TITLE_PC, html);
        if (og != null) return decodeEntities(og).trim();
        og = firstGroup(OG_TITLE_CP, html);
        if (og != null) return decodeEntities(og).trim();
        String t = firstGroup(TITLE_TAG, html);
        return t == null ? "" : decodeEntities(t).replaceAll("\\s+", " ").trim();
    }

    private static Instant pickPublishedAt(String html) {
        for (Pattern p : PUBLISHED_PATTERNS) {
            String raw = firstGroup(p, html);
            Instant parsed = parseDate(raw);
            if (parsed != null) return parsed;
        }
        // Last-resort: search the whole doc for a Chinese-format date. HBR Taiwan
        // articles often print "2026年5月10日" inline near the byline without any
        // structured metadata around it.
        Matcher cn = CHINESE_DATE.matcher(html);
        if (cn.find()) {
            Instant fromCn = parseYmd(cn.group(1), cn.group(2), cn.group(3));
            if (fromCn != null) return fromCn;
        }
        return null;
    }

    /** Tries the most common publish-date encodings; returns null if none parse. */
    private static Instant parseDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // 1) Full ISO 8601 instant ("2026-05-10T08:00:00Z").
        try { return Instant.parse(s); } catch (Exception ignored) {}
        // 2) Offset date-time ("2026-05-10T08:00:00+08:00").
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
        // 3) Local date-time without offset — assume UTC.
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) {}
        // 4) Plain ISO date ("2026-05-10").
        try { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (Exception ignored) {}
        // 5) yyyy/MM/dd, yyyy.MM.dd, yyyy-MM-dd inside a longer string.
        Matcher m = NUMERIC_DATE.matcher(s);
        if (m.find()) {
            Instant fromN = parseYmd(m.group(1), m.group(2), m.group(3));
            if (fromN != null) return fromN;
        }
        // 6) yyyy年MM月dd日.
        Matcher c = CHINESE_DATE.matcher(s);
        if (c.find()) {
            Instant fromC = parseYmd(c.group(1), c.group(2), c.group(3));
            if (fromC != null) return fromC;
        }
        return null;
    }

    private static Instant parseYmd(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickContent(String html) {
        String raw = firstGroup(ARTICLE_TAG, html);
        if (raw == null) raw = firstGroup(MAIN_TAG, html);
        if (raw == null) raw = firstGroup(BODY_TAG, html);
        if (raw == null) raw = html;
        return cleanText(raw);
    }

    private static String cleanText(String htmlFragment) {
        String s = htmlFragment;
        s = NOISE_TAGS.matcher(s).replaceAll(" ");
        s = NOISE_VOID.matcher(s).replaceAll(" ");
        s = BLOCK_BOUNDARIES.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        // Collapse whitespace: keep newlines (paragraph breaks) but squeeze runs of spaces/tabs.
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String firstGroup(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Section labels that mark "everything below this is related-articles /
     * footer chrome, not body text". HBR Taiwan renders a "延伸閱讀" heading
     * before a list of links; readers don't want those repeated in the saved
     * article text, and they trip up translation token budgets too.
     *
     * <p>When postProcess encounters a line equal (case-insensitive,
     * trim-equal) to one of these, it stops emitting further lines.
     */
    private static final Set<String> BODY_END_MARKERS = Set.of(
            // Traditional / Simplified Chinese (codepoints differ between scripts
            // for these phrases, so listing both is safe with Set.of).
            "延伸閱讀", "延伸阅读",
            "推薦閱讀", "推荐阅读",
            "相關文章", "相关文章",
            "更多文章",
            // English
            "related articles", "related reading", "further reading", "read more",
            // Japanese
            "関連記事", "おすすめ記事",
            // German
            "weiterführende artikel", "verwandte artikel"
    );

    /**
     * UI chrome lines that show up as standalone block-level text on most news
     * sites: breadcrumbs, share buttons, font-size widgets, "next/previous" nav.
     * Matched case-insensitively against {@code line.toLowerCase(ROOT)}.
     */
    private static final Set<String> CHROME_PHRASES = Set.of(
            // English
            "post", "share", "tweet", "email", "print", "comment", "save",
            "next", "previous", "more", "menu", "search", "subscribe", "sign in", "sign up",
            // Traditional Chinese — and shared Chinese (下一篇 / 上一篇 / 更多 are identical
            // in Simplified, so they live here and aren't repeated below).
            "首頁", "目錄", "分享", "放大縮小", "字級", "列印",
            "分類", "主題分類", "下一篇", "上一篇", "更多", "推薦", "訂閱",
            // Simplified Chinese — only chars that actually differ from Traditional.
            // Anything matching Trad in glyph (downloaded as identical codepoints) goes
            // above; listing the same string twice trips Set.of's duplicate guard and
            // crashes class init with ExceptionInInitializerError.
            "首页", "目录", "栏目", "字号", "推荐", "订阅",
            // Japanese
            "ホーム", "カテゴリ", "共有", "印刷", "次へ", "前へ", "もっと見る",
            // German
            "startseite", "kategorie", "teilen", "drucken", "weiter", "zurück", "abonnieren"
    );

    /**
     * Cleans the post-strip-tags output by:
     * <ol>
     *   <li>Dropping lines that match common UI chrome (Share, 分享, 放大縮小, …).</li>
     *   <li>Dropping lines that equal the article title — the preview modal
     *       renders title separately, so seeing it 3× in the body looks broken.</li>
     *   <li>De-duping consecutive identical lines (common breadcrumb-then-h1
     *       pattern) and globally de-duping short lines (≤30 chars), which is
     *       where chrome repetition concentrates. Long paragraphs are left
     *       alone so legitimate body repetition isn't lost.</li>
     * </ol>
     *
     * <p>Intentionally conservative — we don't try to reflow the article or
     * strip headings; that's a job for a Readability-style scoring pass and
     * out of scope for the current regex extractor.
     */
    static String postProcess(String text, String title) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        String titleTrim = title == null ? "" : title.trim();
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        Set<String> seenShort = new HashSet<>();
        String prev = null;

        for (String raw : lines) {
            String line = raw.trim();
            // Hard stop: everything from "延伸閱讀" / "Related articles" onwards
            // is footer chrome — drop it.
            if (BODY_END_MARKERS.contains(line.toLowerCase(Locale.ROOT))) {
                break;
            }
            if (line.isEmpty()) {
                // Collapse runs of blank lines but preserve a single paragraph break.
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
                    out.add("");
                    prev = "";
                }
                continue;
            }
            if (line.equals(prev)) continue;
            if (!titleTrim.isEmpty() && line.equals(titleTrim)) {
                prev = line;
                continue;
            }
            if (CHROME_PHRASES.contains(line.toLowerCase(Locale.ROOT))) {
                prev = line;
                continue;
            }
            // Short lines (likely nav / labels / repeated headers) are de-duped
            // globally. Long paragraphs aren't, since articles can legitimately
            // repeat a phrase across paragraphs.
            if (line.length() <= 30) {
                if (seenShort.contains(line)) {
                    prev = line;
                    continue;
                }
                seenShort.add(line);
            }
            out.add(line);
            prev = line;
        }

        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return String.join("\n", out);
    }

    /**
     * Decodes the handful of HTML entities likely to appear in extracted body text.
     * Skips full SGML/HTML5 entity coverage on purpose — those that don't decode
     * still survive as literal text, which is acceptable for human-readable output.
     */
    static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 10) {
                out.append(c);
                i++;
                continue;
            }
            String entity = s.substring(i + 1, semi);
            String replacement = switch (entity) {
                case "amp"    -> "&";
                case "lt"     -> "<";
                case "gt"     -> ">";
                case "quot"   -> "\"";
                case "apos"   -> "'";
                case "nbsp"   -> " ";
                case "ndash"  -> "–";
                case "mdash"  -> "—";
                case "hellip" -> "…";
                case "lsquo"  -> "‘";
                case "rsquo"  -> "’";
                case "ldquo"  -> "“";
                case "rdquo"  -> "”";
                case "copy"   -> "©";
                case "reg"    -> "®";
                case "trade"  -> "™";
                default -> decodeNumeric(entity);
            };
            if (replacement != null) {
                out.append(replacement);
                i = semi + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String decodeNumeric(String entity) {
        if (entity.isEmpty() || entity.charAt(0) != '#') return null;
        try {
            int codePoint;
            if (entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                codePoint = Integer.parseInt(entity.substring(2), 16);
            } else {
                codePoint = Integer.parseInt(entity.substring(1));
            }
            return new String(Character.toChars(codePoint));
        } catch (Exception e) {
            return null;
        }
    }
}
