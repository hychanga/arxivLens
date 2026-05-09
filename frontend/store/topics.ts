"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { Topic } from "@/types";

interface TopicsState {
  bySource: Record<number, Topic[]>;
  loadingSource: number | null;
  error: string | null;
  loadFor: (sourceId: number) => Promise<void>;
  create: (req: { sourceId: number; code: string; name: string; enabled?: boolean }) => Promise<Topic>;
  update: (id: number, changes: Partial<Topic>) => Promise<Topic>;
  remove: (id: number) => Promise<void>;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

const INITIAL: Pick<TopicsState, "bySource" | "loadingSource" | "error"> = {
  bySource: {},
  loadingSource: null,
  error: null,
};

export const useTopicsStore = create<TopicsState>((set, get) => ({
  ...INITIAL,

  loadFor: async (sourceId) => {
    set({ loadingSource: sourceId, error: null });
    try {
      const items = await apiFetch<Topic[]>(`/topics?sourceId=${sourceId}`);
      set({ bySource: { ...get().bySource, [sourceId]: items }, loadingSource: null });
    } catch (e) {
      set({ loadingSource: null, error: e instanceof Error ? e.message : "Load failed" });
    }
  },

  create: async (req) => {
    const t = await apiFetch<Topic>("/topics", { method: "POST", body: req });
    const cur = get().bySource[req.sourceId] ?? [];
    set({ bySource: { ...get().bySource, [req.sourceId]: [...cur, t] } });
    return t;
  },

  update: async (id, changes) => {
    const t = await apiFetch<Topic>(`/topics/${id}`, { method: "PUT", body: changes });
    const map = { ...get().bySource };
    for (const k of Object.keys(map)) {
      const sid = Number(k);
      map[sid] = map[sid].map((x) => (x.id === id ? t : x));
    }
    set({ bySource: map });
    return t;
  },

  remove: async (id) => {
    await apiFetch<void>(`/topics/${id}`, { method: "DELETE" });
    const map = { ...get().bySource };
    for (const k of Object.keys(map)) {
      const sid = Number(k);
      map[sid] = map[sid].filter((x) => x.id !== id);
    }
    set({ bySource: map });
  },

  reset: () => set({ ...INITIAL }),
}));
