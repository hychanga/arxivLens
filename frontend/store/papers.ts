"use client";

import { create } from "zustand";
import { apiFetch } from "@/lib/api";
import type { Paper, PaperPage } from "@/types";

interface PapersState {
  source: string;
  days: number;
  topic: string | null;
  q: string;
  page: number;
  size: number;
  loading: boolean;
  error: string | null;
  data: PaperPage | null;
  selected: Set<number>;
  setSource: (s: string) => void;
  setDays: (d: number) => void;
  setTopic: (t: string | null) => void;
  setQuery: (q: string) => void;
  setPage: (p: number) => void;
  setSize: (n: number) => void;
  toggleSelect: (paperId: number) => void;
  clearSelection: () => void;
  selectAll: (papers: Paper[]) => void;
  fetch: () => Promise<void>;
  /** Wipe state so the next user starts clean. Called from auth.logout. */
  reset: () => void;
}

const initial = (): Pick<
  PapersState,
  "source" | "days" | "topic" | "q" | "page" | "size" | "loading" | "error" | "data" | "selected"
> => ({
  source: "arxiv",
  days: 30,
  topic: null,
  q: "",
  page: 0,
  size: 10,
  loading: false,
  error: null,
  data: null,
  selected: new Set<number>(),
});

export const usePapersStore = create<PapersState>((set, get) => ({
  ...initial(),

  // Reset topic on source change — topics are per-source (arXiv's "cs.AI" has
  // no meaning on HBR / Business Weekly), so leaving the old topic selected
  // would silently filter the new feed to zero rows.
  setSource: (s) => set({ source: s, page: 0, topic: null, selected: new Set() }),
  setDays: (d) => set({ days: d, page: 0 }),
  setTopic: (t) => set({ topic: t, page: 0 }),
  setQuery: (q) => set({ q, page: 0 }),
  setPage: (p) => set({ page: p }),
  setSize: (n) => set({ size: n, page: 0 }),

  toggleSelect: (paperId) => {
    const next = new Set(get().selected);
    if (next.has(paperId)) next.delete(paperId);
    else next.add(paperId);
    set({ selected: next });
  },
  clearSelection: () => set({ selected: new Set() }),
  selectAll: (papers) => set({ selected: new Set(papers.map((p) => p.id)) }),

  fetch: async () => {
    const { source, days, topic, q, page, size } = get();
    set({ loading: true, error: null });
    try {
      const params = new URLSearchParams({
        source,
        days: String(days),
        page: String(page),
        size: String(size),
      });
      if (topic) params.set("topic", topic);
      if (q.trim()) params.set("q", q.trim());
      const res = await apiFetch<PaperPage>(`/papers?${params.toString()}`);
      set({ data: res, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "Fetch failed" });
    }
  },

  reset: () => set(initial()),
}));
