"use client";

import { useUiStore } from "@/store/ui";

export default function Toast() {
  const toast = useUiStore((s) => s.toast);
  if (!toast.open) return null;

  const tone =
    toast.tone === "success"
      ? "bg-emerald-600 text-white"
      : toast.tone === "error"
      ? "bg-red-600 text-white"
      : "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900";

  return (
    <div
      role="status"
      className={`fixed bottom-4 left-1/2 -translate-x-1/2 z-50 rounded-md px-4 py-2 text-sm shadow-lg ${tone}`}
    >
      {toast.message}
    </div>
  );
}
