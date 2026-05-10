"use client";

import { useEffect, useRef, useState } from "react";
import { useThemeStore, THEME_PREFS, type ThemePref } from "@/store/theme";
import { useT } from "@/lib/i18n";

export default function ThemeToggle() {
  const pref = useThemeStore((s) => s.pref);
  const setPref = useThemeStore((s) => s.setPref);
  const t = useT();

  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

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

  function pick(p: ThemePref) {
    setPref(p);
    setOpen(false);
  }

  const label = t(`theme.${pref}`);

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`${t("topbar.theme")}: ${label}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        title={`${t("topbar.theme")}: ${label}`}
        className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 hover:bg-zinc-50 dark:hover:bg-zinc-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        <ThemeIcon pref={pref} />
      </button>

      {open && (
        <ul
          role="listbox"
          aria-label={t("topbar.theme")}
          className="absolute right-0 z-30 mt-1 min-w-[10rem] overflow-hidden rounded-md border border-zinc-200 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow-lg"
        >
          {THEME_PREFS.map((p) => {
            const active = p === pref;
            return (
              <li key={p}>
                <button
                  type="button"
                  role="option"
                  aria-selected={active}
                  onClick={() => pick(p)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm focus-visible:outline-none focus-visible:bg-zinc-100 dark:focus-visible:bg-zinc-800 ${
                    active
                      ? "bg-zinc-100 dark:bg-zinc-800 font-medium"
                      : "hover:bg-zinc-50 dark:hover:bg-zinc-800"
                  }`}
                >
                  <span aria-hidden className="inline-flex h-5 w-5 items-center justify-center text-zinc-600 dark:text-zinc-300">
                    <ThemeIcon pref={p} />
                  </span>
                  <span>{t(`theme.${p}`)}</span>
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

function ThemeIcon({ pref }: { pref: ThemePref }) {
  if (pref === "light") return <SunIcon />;
  if (pref === "dark") return <MoonIcon />;
  return <SystemIcon />;
}

function SunIcon() {
  return (
    <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2" />
      <path d="M12 20v2" />
      <path d="m4.93 4.93 1.41 1.41" />
      <path d="m17.66 17.66 1.41 1.41" />
      <path d="M2 12h2" />
      <path d="M20 12h2" />
      <path d="m4.93 19.07 1.41-1.41" />
      <path d="m17.66 6.34 1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

function SystemIcon() {
  return (
    <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="18" height="13" rx="2" />
      <path d="M8 21h8" />
      <path d="M12 17v4" />
    </svg>
  );
}
