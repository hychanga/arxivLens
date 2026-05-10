"use client";

import { useEffect, useRef, useState } from "react";
import { useLocaleStore, type Locale, SUPPORTED_LOCALES } from "@/store/locale";
import { useT } from "@/lib/i18n";

const LABELS: Record<Locale, { native: string; badge: string }> = {
  en: { native: "English", badge: "EN" },
  "zh-TW": { native: "繁體中文", badge: "繁" },
  "zh-CN": { native: "简体中文", badge: "简" },
  ja: { native: "日本語", badge: "日" },
  de: { native: "Deutsch", badge: "DE" },
};

export default function LocaleSelector() {
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const t = useT();

  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  // Close popover on click outside or Escape.
  useEffect(() => {
    if (!open) return;
    function onPointer(e: PointerEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function pick(l: Locale) {
    setLocale(l);
    setOpen(false);
  }

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={t("topbar.locale")}
        aria-haspopup="listbox"
        aria-expanded={open}
        className="inline-flex items-center gap-1.5 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm hover:bg-zinc-50 dark:hover:bg-zinc-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        <GlobeIcon />
        <span className="font-medium">{LABELS[locale].badge}</span>
      </button>

      {open && (
        <ul
          role="listbox"
          aria-label={t("topbar.locale")}
          className="absolute right-0 z-30 mt-1 min-w-[10rem] overflow-hidden rounded-md border border-zinc-200 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow-lg"
        >
          {SUPPORTED_LOCALES.map((l) => {
            const active = l === locale;
            return (
              <li key={l}>
                <button
                  type="button"
                  role="option"
                  aria-selected={active}
                  onClick={() => pick(l)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm focus-visible:outline-none focus-visible:bg-zinc-100 dark:focus-visible:bg-zinc-800 ${
                    active
                      ? "bg-zinc-100 dark:bg-zinc-800 font-medium"
                      : "hover:bg-zinc-50 dark:hover:bg-zinc-800"
                  }`}
                >
                  <span
                    aria-hidden
                    className="inline-flex h-5 min-w-[1.75rem] items-center justify-center rounded-sm border border-zinc-300 dark:border-zinc-600 bg-zinc-50 dark:bg-zinc-950 px-1 text-[11px] font-mono"
                  >
                    {LABELS[l].badge}
                  </span>
                  <span>{LABELS[l].native}</span>
                  {active && <span aria-hidden className="ml-auto text-blue-500">✓</span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function GlobeIcon() {
  return (
    <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3a14.5 14.5 0 0 1 0 18" />
      <path d="M12 3a14.5 14.5 0 0 0 0 18" />
    </svg>
  );
}
