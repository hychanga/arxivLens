"use client";

import { useLocaleStore, type Locale, SUPPORTED_LOCALES } from "@/store/locale";
import { useT } from "@/lib/i18n";

const LABELS: Record<Locale, string> = {
  en: "English",
  "zh-TW": "繁體中文",
  "zh-CN": "简体中文",
  ja: "日本語",
};

export default function LocaleSelector() {
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const t = useT();

  return (
    <label className="inline-flex items-center gap-1.5 text-sm">
      <span className="sr-only">{t("topbar.locale")}</span>
      <select
        value={locale}
        onChange={(e) => setLocale(e.target.value as Locale)}
        aria-label={t("topbar.locale")}
        className="rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        {SUPPORTED_LOCALES.map((l) => (
          <option key={l} value={l}>{LABELS[l]}</option>
        ))}
      </select>
    </label>
  );
}
