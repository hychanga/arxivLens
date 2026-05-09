"use client";

import { create } from "zustand";
import type { Paper } from "@/types";

export interface ConfirmOptions {
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void | Promise<void>;
}

interface UiState {
  preview: { open: boolean; paper: Paper | null; cached: boolean };
  openPreview: (paper: Paper, cached: boolean) => void;
  closePreview: () => void;

  confirm: (ConfirmOptions & { open: true }) | { open: false };
  ask: (opts: ConfirmOptions) => void;
  closeConfirm: () => void;

  toast: { open: boolean; message: string; tone: "info" | "success" | "error" };
  flash: (message: string, tone?: "info" | "success" | "error") => void;

  /** Mobile-only sidebar drawer — desktop renders the sidebar inline. */
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
  toggleSidebar: () => void;
}

let toastTimer: ReturnType<typeof setTimeout> | null = null;

export const useUiStore = create<UiState>((set) => ({
  preview: { open: false, paper: null, cached: false },
  openPreview: (paper, cached) => set({ preview: { open: true, paper, cached } }),
  closePreview: () => set({ preview: { open: false, paper: null, cached: false } }),

  confirm: { open: false },
  ask: (opts) => set({ confirm: { ...opts, open: true } }),
  closeConfirm: () => set({ confirm: { open: false } }),

  toast: { open: false, message: "", tone: "info" },
  flash: (message, tone = "info") => {
    set({ toast: { open: true, message, tone } });
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => set({ toast: { open: false, message: "", tone: "info" } }), 2200);
  },

  sidebarOpen: false,
  setSidebarOpen: (open) => set({ sidebarOpen: open }),
  toggleSidebar: () => set((prev) => ({ sidebarOpen: !prev.sidebarOpen })),
}));
