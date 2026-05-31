"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { PaperTranslation } from "@/types";

type Key = `${number}:${string}`;
const k = (paperId: number, locale: string): Key => `${paperId}:${locale}` as Key;

interface TranslationsState {
  byKey: Record<Key, PaperTranslation>;
  loading: Set<Key>;
  notFound: Set<Key>;
  /** Look up an already-fetched translation for the given paper+locale, or undefined. */
  get: (paperId: number, locale: string) => PaperTranslation | undefined;
  /** Probe for a previously-cached translation. Calls are coalesced into one batch request. */
  fetchCached: (paperId: number, locale: string) => void;
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

// --- Request coalescing -----------------------------------------------------
// Every PaperCard probes its own (paper, locale) on mount, so one feed render
// fires dozens of fetchCached calls in the same tick. Sending one network
// request each exhausts the backend's tiny connection pool (Hikari max 3 on the
// free tier) and the overflow requests time out with a 500. Instead we buffer
// the requested paper IDs per locale and flush them as a single batch request
// on the next macrotask — one transaction, one pooled connection, no fan-out.
const pending = new Map<string, Set<number>>(); // locale -> paperIds awaiting a batch
let flushHandle: ReturnType<typeof setTimeout> | null = null;

function scheduleFlush() {
  if (flushHandle != null) return;
  flushHandle = setTimeout(flushPending, 0);
}

async function flushPending() {
  flushHandle = null;
  const batches = [...pending.entries()].map(([locale, ids]) => [locale, [...ids]] as const);
  pending.clear();

  await Promise.all(
    batches.map(async ([locale, ids]) => {
      try {
        const list = await apiFetch<PaperTranslation[]>(
          `/papers/translations?ids=${ids.join(",")}&locale=${encodeURIComponent(locale)}`
        );
        const found = new Set(list.map((t) => t.paperId));
        useTranslationsStore.setState((prev) => {
          const byKey = { ...prev.byKey };
          const loading = new Set(prev.loading);
          const notFound = new Set(prev.notFound);
          for (const tr of list) {
            const key = k(tr.paperId, locale);
            byKey[key] = tr;
            loading.delete(key);
          }
          for (const id of ids) {
            const key = k(id, locale);
            loading.delete(key);
            // Asked for but absent from the result → not translated for this
            // locale yet. Remember so we don't keep probing; the card shows the
            // original text and a Translate button.
            if (!found.has(id)) notFound.add(key);
          }
          return { byKey, loading, notFound };
        });
      } catch {
        // Network / 5xx — clear the loading markers so a later render can retry.
        // Don't mark notFound; that's reserved for "definitely no translation".
        useTranslationsStore.setState((prev) => {
          const loading = new Set(prev.loading);
          for (const id of ids) loading.delete(k(id, locale));
          return { loading };
        });
      }
    })
  );
}

export const useTranslationsStore = create<TranslationsState>((set, get) => ({
  ...INITIAL(),

  get: (paperId, locale) => get().byKey[k(paperId, locale)],

  fetchCached: (paperId, locale) => {
    if (locale === "en") return;
    const key = k(paperId, locale);
    const state = get();
    if (state.byKey[key] || state.loading.has(key) || state.notFound.has(key)) return;

    const nextLoading = new Set(state.loading);
    nextLoading.add(key);
    set({ loading: nextLoading });

    let bucket = pending.get(locale);
    if (!bucket) {
      bucket = new Set<number>();
      pending.set(locale, bucket);
    }
    bucket.add(paperId);
    scheduleFlush();
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
