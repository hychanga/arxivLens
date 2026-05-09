"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { Download } from "@/types";

interface DownloadsState {
  items: Download[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
  add: (paperId: number) => Promise<Download>;
  remove: (paperId: number) => Promise<void>;
  clear: () => Promise<number>;
  isCachedByPaperId: (paperId: number) => boolean;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

const INITIAL: Pick<DownloadsState, "items" | "loaded" | "loading" | "error"> = {
  items: [],
  loaded: false,
  loading: false,
  error: null,
};

export const useDownloadsStore = create<DownloadsState>((set, get) => ({
  ...INITIAL,

  load: async () => {
    set({ loading: true, error: null });
    try {
      const items = await apiFetch<Download[]>("/downloads");
      set({ items, loaded: true, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "Load failed" });
    }
  },

  add: async (paperId) => {
    const d = await apiFetch<Download>("/downloads", {
      method: "POST",
      body: { paperId },
    });
    if (!get().items.find((x) => x.paper.id === d.paper.id)) {
      set({ items: [d, ...get().items] });
    }
    return d;
  },

  remove: async (paperId) => {
    await apiFetch<void>(`/downloads/${paperId}`, { method: "DELETE" });
    set({ items: get().items.filter((d) => d.paper.id !== paperId) });
  },

  clear: async () => {
    const result = await apiFetch<{ removed: number }>("/downloads", { method: "DELETE" });
    set({ items: [] });
    return result.removed;
  },

  isCachedByPaperId: (paperId) => get().items.some((d) => d.paper.id === paperId),

  reset: () => set({ ...INITIAL }),
}));
