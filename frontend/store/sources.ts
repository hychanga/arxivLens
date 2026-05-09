"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { Source } from "@/types";

interface SourcesState {
  items: Source[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
  load: (enabledOnly?: boolean) => Promise<void>;
  create: (req: { code: string; name: string; description?: string }) => Promise<Source>;
  update: (id: number, changes: Partial<Source>) => Promise<Source>;
  remove: (id: number) => Promise<void>;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

const INITIAL: Pick<SourcesState, "items" | "loaded" | "loading" | "error"> = {
  items: [],
  loaded: false,
  loading: false,
  error: null,
};

export const useSourcesStore = create<SourcesState>((set, get) => ({
  ...INITIAL,

  load: async (enabledOnly = false) => {
    set({ loading: true, error: null });
    try {
      const items = await apiFetch<Source[]>(`/sources?enabledOnly=${enabledOnly}`);
      set({ items, loaded: true, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "Load failed" });
    }
  },

  create: async (req) => {
    const created = await apiFetch<Source>("/sources", { method: "POST", body: req });
    set({ items: [...get().items, created] });
    return created;
  },

  update: async (id, changes) => {
    const updated = await apiFetch<Source>(`/sources/${id}`, { method: "PUT", body: changes });
    set({ items: get().items.map((s) => (s.id === id ? updated : s)) });
    return updated;
  },

  remove: async (id) => {
    await apiFetch<void>(`/sources/${id}`, { method: "DELETE" });
    set({ items: get().items.filter((s) => s.id !== id) });
  },

  reset: () => set({ ...INITIAL }),
}));

export function findSourceById(items: Source[], id: number | null): Source | undefined {
  if (id == null) return undefined;
  return items.find((s) => s.id === id);
}

export function findSourceByCode(items: Source[], code: string): Source | undefined {
  return items.find((s) => s.code === code);
}
