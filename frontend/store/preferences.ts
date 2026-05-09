"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { Preferences, SortMode } from "@/types";

interface PreferencesState extends Preferences {
  loaded: boolean;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
  patch: (changes: Partial<Preferences>) => Promise<void>;
  /** Local-only mutation; debounced flush to backend. */
  patchLocal: (changes: Partial<Preferences>) => void;
  flushPending: () => Promise<void>;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

let flushTimer: ReturnType<typeof setTimeout> | null = null;
let pending: Partial<Preferences> = {};

const DEFAULTS: Preferences = {
  queryDays: 30,
  sortMode: "NEWEST",
  keywords: {},
  currentSourceId: null,
  perPage: 10,
};

export const usePreferencesStore = create<PreferencesState>((set, get) => ({
  ...DEFAULTS,
  loaded: false,
  loading: false,
  error: null,

  load: async () => {
    set({ loading: true, error: null });
    try {
      const p = await apiFetch<Preferences>("/preferences");
      set({
        queryDays: p.queryDays ?? DEFAULTS.queryDays,
        sortMode: (p.sortMode as SortMode) ?? DEFAULTS.sortMode,
        keywords: p.keywords ?? {},
        currentSourceId: p.currentSourceId ?? null,
        perPage: p.perPage ?? DEFAULTS.perPage,
        loaded: true,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "Load failed" });
    }
  },

  patch: async (changes) => {
    const next = { ...get(), ...changes };
    set(next);
    try {
      const updated = await apiFetch<Preferences>("/preferences", {
        method: "PATCH",
        body: changes,
      });
      set({
        queryDays: updated.queryDays ?? next.queryDays,
        sortMode: (updated.sortMode as SortMode) ?? next.sortMode,
        keywords: updated.keywords ?? next.keywords,
        currentSourceId: updated.currentSourceId ?? next.currentSourceId,
        perPage: updated.perPage ?? next.perPage,
      });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : "Save failed" });
    }
  },

  patchLocal: (changes) => {
    set({ ...get(), ...changes });
    pending = { ...pending, ...changes };
    if (flushTimer) clearTimeout(flushTimer);
    flushTimer = setTimeout(() => {
      void get().flushPending();
    }, 600);
  },

  flushPending: async () => {
    if (Object.keys(pending).length === 0) return;
    const toSend = pending;
    pending = {};
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
    try {
      await apiFetch<Preferences>("/preferences", { method: "PATCH", body: toSend });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : "Save failed" });
    }
  },

  reset: () => {
    // Drop any queued debounced flush from the previous user.
    pending = {};
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
    set({ ...DEFAULTS, loaded: false, loading: false, error: null });
  },
}));
