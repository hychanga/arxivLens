"use client";

import { create } from "zustand";

export type Locale = "en" | "zh-TW" | "zh-CN" | "ja";

export const SUPPORTED_LOCALES: Locale[] = ["en", "zh-TW", "zh-CN", "ja"];

const STORAGE_KEY = "arxivlens.locale";

interface LocaleState {
  locale: Locale;
  setLocale: (l: Locale) => void;
  hydrate: () => void;
}

function readStoredLocale(): Locale {
  if (typeof window === "undefined") return "en";
  const raw = window.localStorage.getItem(STORAGE_KEY);
  return SUPPORTED_LOCALES.includes(raw as Locale) ? (raw as Locale) : "en";
}

export const useLocaleStore = create<LocaleState>((set) => ({
  locale: "en",

  setLocale: (l) => {
    if (typeof window !== "undefined") window.localStorage.setItem(STORAGE_KEY, l);
    set({ locale: l });
  },

  hydrate: () => {
    set({ locale: readStoredLocale() });
  },
}));
