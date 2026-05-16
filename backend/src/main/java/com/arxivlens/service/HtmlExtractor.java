package com.arxivlens.service;

import java.time.Instant;
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

    private static final Pattern PUBLISHED_PC = Pattern.compile(
            "<meta\\s+[^>]*property\\s*=\\s*[\"']article:published_time[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHED_CP = Pattern.compile(
            "<meta\\s+[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*property\\s*=\\s*[\"']article:published_time[\"']",
            Pattern.CASE_INSENSITIVE);

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
        String content = pickContent(html);
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
        String iso = firstGroup(PUBLISHED_PC, html);
        if (iso == null) iso = firstGroup(PUBLISHED_CP, html);
        if (iso == null || iso.isBlank()) return null;
        try {
            // article:published_time is conventionally ISO 8601 with timezone.
            return Instant.parse(iso.trim());
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
