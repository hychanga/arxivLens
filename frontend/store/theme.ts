"use client";

import { create } from "zustand";

export type ThemePref = "light" | "dark" | "system";

export const THEME_PREFS: ThemePref[] = ["light", "dark", "system"];

const STORAGE_KEY = "arxivlens.theme";

interface ThemeState {
  pref: ThemePref;
  setPref: (p: ThemePref) => void;
  hydrate: () => void;
}

function effectiveTheme(p: ThemePref): "light" | "dark" {
  if (p === "system") {
    if (typeof window === "undefined") return "light";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return p;
}

function applyClass(p: ThemePref) {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", effectiveTheme(p) === "dark");
}

let mqlAttached = false;

export const useThemeStore = create<ThemeState>((set, get) => ({
  pref: "system",

  setPref: (p) => {
    if (typeof window !== "undefined") window.localStorage.setItem(STORAGE_KEY, p);
    applyClass(p);
    set({ pref: p });
  },

  hydrate: () => {
    if (typeof window === "undefined") return;
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const pref: ThemePref =
      raw === "light" || raw === "dark" || raw === "system" ? raw : "system";
    applyClass(pref);
    set({ pref });

    // Re-apply on OS change while in system mode. Attach the listener once.
    if (!mqlAttached && typeof window.matchMedia === "function") {
      mqlAttached = true;
      window
        .matchMedia("(prefers-color-scheme: dark)")
        .addEventListener("change", () => {
          if (get().pref === "system") applyClass("system");
        });
    }
  },
}));
