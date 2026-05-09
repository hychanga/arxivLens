"use client";

import { create } from "zustand";
import { apiFetch, HttpError } from "@/lib/api";
import type { PaperTranslation } from "@/types";

type Key = `${number}:${string}`;
const k = (paperId: number, locale: string): Key => `${paperId}:${locale}` as Key;

interface TranslationsState {
  byKey: Record<Key, PaperTranslation>;
  loading: Set<Key>;
  notFound: Set<Key>;
  /** Look up an already-fetched translation for the given paper+locale, or undefined. */
  get: (paperId: number, locale: string) => PaperTranslation | undefined;
  /** Try to fetch a previously-cached translation. 404 means "not yet generated" — handled silently. */
  fetchCached: (paperId: number, locale: string) => Promise<void>;
  /** Trigger AI translation (uses cache on the backend if available). */
  generate: (paperId: number, locale: string) => Promise<PaperTranslation>;
  /** Forget the locally-cached translation so the card falls back to the original text. */
  clearLocal: (paperId: number, locale: string) => void;
  reset: () => void;
}

const INITIAL = (): Pick<TranslationsState, "byKey" | "loading" | "notFound"> => ({
  byKey: {} as Record<Key, PaperTranslation>,
  loading: new Set<Key>(),
  notFound: new Set<Key>(),
});

export const useTranslationsStore = create<TranslationsState>((set, get) => ({
  ...INITIAL(),

  get: (paperId, locale) => get().byKey[k(paperId, locale)],

  fetchCached: async (paperId, locale) => {
    if (locale === "en") return;
    const key = k(paperId, locale);
    const state = get();
    if (state.byKey[key] || state.loading.has(key) || state.notFound.has(key)) return;

    const nextLoading = new Set(state.loading);
    nextLoading.add(key);
    set({ loading: nextLoading });

    try {
      const t = await apiFetch<PaperTranslation>(
        `/papers/${paperId}/translation?locale=${encodeURIComponent(locale)}`
      );
      set((prev) => {
        const loading = new Set(prev.loading);
        loading.delete(key);
        return { byKey: { ...prev.byKey, [key]: t }, loading };
      });
    } catch (e) {
      set((prev) => {
        const loading = new Set(prev.loading);
        loading.delete(key);
        const notFound = new Set(prev.notFound);
        if (e instanceof HttpError && e.status === 404) notFound.add(key);
        return { loading, notFound };
      });
    }
  },

  generate: async (paperId, locale) => {
    const key = k(paperId, locale);
    const nextLoading = new Set(get().loading);
    nextLoading.add(key);
    set({ loading: nextLoading });

    try {
      const t = await apiFetch<PaperTranslation>(
        `/papers/${paperId}/translate?locale=${encodeURIComponent(locale)}`,
        { method: "POST" }
      );
      set((prev) => {
        const loading = new Set(prev.loading);
        loading.delete(key);
        const notFound = new Set(prev.notFound);
        notFound.delete(key);
        return { byKey: { ...prev.byKey, [key]: t }, loading, notFound };
      });
      return t;
    } catch (e) {
      set((prev) => {
        const loading = new Set(prev.loading);
        loading.delete(key);
        return { loading };
      });
      throw e;
    }
  },

  clearLocal: (paperId, locale) => {
    const key = k(paperId, locale);
    set((prev) => {
      const byKey = { ...prev.byKey };
      delete byKey[key];
      return { byKey };
    });
  },

  reset: () => set({ ...INITIAL() }),
}));

export function isLoadingTranslation(
  state: { loading: Set<Key> },
  paperId: number,
  locale: string
): boolean {
  return state.loading.has(k(paperId, locale));
}
