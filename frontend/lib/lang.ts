import type { Locale } from "@/store/locale";

/**
 * Coarse-grained language detector — wide enough to drive UI decisions
 * ("should the Translate button appear for this paper given the current
 * UI locale?"), not precise enough to feed back into the translation
 * prompt. We don't try to distinguish Traditional vs Simplified Chinese
 * (the {@link sameLanguageFamily} comparison treats them as one).
 *
 * <p>Heuristic: count characters by Unicode block in the first 500 chars,
 * then pick the dominant block. Whitespace and punctuation are ignored.
 * German and English both map to {@code "en"} — they share the Latin
 * script and the heuristic can't tell them apart without a dictionary,
 * which is fine for our purpose (German UI users still see the Translate
 * button on a German article, but pressing it just produces the same
 * German text, so no harm done).
 */
export type DetectedLang = "en" | "zh" | "ja" | "ko" | "other";

const SAMPLE_CHARS = 500;

export function detectLanguage(text: string | null | undefined): DetectedLang {
  if (!text) return "other";
  const sample = text.slice(0, SAMPLE_CHARS);
  let cjk = 0;
  let kana = 0;
  let hangul = 0;
  let latin = 0;
  let counted = 0;

  for (const ch of sample) {
    const code = ch.codePointAt(0);
    if (code === undefined) continue;
    // CJK Unified Ideographs (includes Chinese hanzi + Japanese kanji + Korean hanja).
    if (code >= 0x4e00 && code <= 0x9fff) {
      cjk++;
      counted++;
      continue;
    }
    // Hiragana + Katakana (Japanese only — Chinese / Korean don't use these).
    if ((code >= 0x3040 && code <= 0x309f) || (code >= 0x30a0 && code <= 0x30ff)) {
      kana++;
      counted++;
      continue;
    }
    // Hangul syllables (Korean).
    if (code >= 0xac00 && code <= 0xd7af) {
      hangul++;
      counted++;
      continue;
    }
    // ASCII letters (English / German / most European Latin-script langs).
    if ((code >= 0x41 && code <= 0x5a) || (code >= 0x61 && code <= 0x7a)) {
      latin++;
      counted++;
    }
  }

  if (counted === 0) return "other";

  // Any meaningful amount of kana → Japanese. Even mixed-with-kanji articles
  // have kana in particles, so 5% is a reliable threshold.
  if (kana / counted > 0.05) return "ja";
  // Dominant CJK without kana → Chinese.
  if (cjk / counted > 0.3) return "zh";
  if (hangul / counted > 0.3) return "ko";
  if (latin / counted > 0.3) return "en";
  return "other";
}

/**
 * Returns true when {@code detected} represents the same language family as
 * the user's UI locale — i.e. the article is already in the user's reading
 * language, so the Translate button shouldn't appear.
 */
export function sameLanguageFamily(detected: DetectedLang, uiLocale: Locale): boolean {
  switch (detected) {
    case "zh":
      return uiLocale === "zh-TW" || uiLocale === "zh-CN";
    case "ja":
      return uiLocale === "ja";
    case "en":
      // Treat Latin-script UI locales (English, German) as matching English-detected
      // text. German-on-German is the false-positive edge: the user wouldn't get a
      // translate button on a German article, but pressing it would do nothing
      // useful anyway, so we err on the side of less UI noise.
      return uiLocale === "en" || uiLocale === "de";
    case "ko":
    case "other":
    default:
      return false;
  }
}
