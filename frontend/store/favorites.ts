"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { AiSummary, Favorite } from "@/types";

interface FavoritesState {
  items: Favorite[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
  add: (paperId: number, note?: string) => Promise<Favorite>;
  remove: (favoriteId: number) => Promise<void>;
  updateNote: (favoriteId: number, note: string) => Promise<Favorite>;
  generateSummary: (favoriteId: number, locale: string) => Promise<AiSummary>;
  isFavoritedByPaperId: (paperId: number) => boolean;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

const INITIAL: Pick<FavoritesState, "items" | "loaded" | "loading" | "error"> = {
  items: [],
  loaded: false,
  loading: false,
  error: null,
};

export const useFavoritesStore = create<FavoritesState>((set, get) => ({
  ...INITIAL,

  load: async () => {
    set({ loading: true, error: null });
    try {
      const items = await apiFetch<Favorite[]>("/favorites");
      set({ items, loaded: true, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "Load failed" });
    }
  },

  add: async (paperId, note) => {
    const f = await apiFetch<Favorite>("/favorites", {
      method: "POST",
      body: { paperId, note },
    });
    set({ items: [f, ...get().items] });
    return f;
  },

  remove: async (favoriteId) => {
    await apiFetch<void>(`/favorites/${favoriteId}`, { method: "DELETE" });
    set({ items: get().items.filter((f) => f.id !== favoriteId) });
  },

  updateNote: async (favoriteId, note) => {
    const updated = await apiFetch<Favorite>(`/favorites/${favoriteId}/note`, {
      method: "PUT",
      body: { note },
    });
    set({ items: get().items.map((f) => (f.id === favoriteId ? updated : f)) });
    return updated;
  },

  generateSummary: async (favoriteId, locale) => {
    const s = await apiFetch<AiSummary>(
      `/favorites/${favoriteId}/summary?locale=${encodeURIComponent(locale)}`,
      { method: "POST" }
    );
    set({
      items: get().items.map((f) => (f.id === favoriteId ? { ...f, summary: s } : f)),
    });
    return s;
  },

  isFavoritedByPaperId: (paperId) => get().items.some((f) => f.paper.id === paperId),

  reset: () => set({ ...INITIAL }),
}));
