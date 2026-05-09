"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n";

interface Props {
  page: number;            // 0-based
  totalPages: number;
  totalItems: number;
  size: number;
  onPageChange: (next: number) => void;
  onSizeChange: (next: number) => void;
}

export default function Pagination({ page, totalPages, totalItems, size, onPageChange, onSizeChange }: Props) {
  const [goto, setGoto] = useState("");
  const t = useT();

  function pages(): (number | "…")[] {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i);
    const out: (number | "…")[] = [0];
    const start = Math.max(1, page - 1);
    const end = Math.min(totalPages - 2, page + 1);
    if (start > 1) out.push("…");
    for (let i = start; i <= end; i++) out.push(i);
    if (end < totalPages - 2) out.push("…");
    out.push(totalPages - 1);
    return out;
  }

  function jumpTo() {
    const n = Number(goto);
    if (!Number.isFinite(n)) return;
    const idx = Math.max(0, Math.min(totalPages - 1, n - 1));
    onPageChange(idx);
    setGoto("");
  }

  if (totalPages === 0) return null;
  const start = page * size + 1;
  const end = Math.min(totalItems, (page + 1) * size);

  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <span className="text-zinc-500">{t("pagination.range", { start, end, total: totalItems })}</span>

      <div className="flex items-center gap-1">
        <button
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
          className="rounded px-2 py-0.5 bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 disabled:opacity-30"
        >
          ‹
        </button>
        {pages().map((p, i) =>
          p === "…" ? (
            <span key={`e${i}`} className="px-1 text-zinc-400">…</span>
          ) : (
            <button
              key={p}
              onClick={() => onPageChange(p)}
              className={`rounded px-2 py-0.5 ${
                p === page
                  ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                  : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
              }`}
            >
              {p + 1}
            </button>
          )
        )}
        <button
          disabled={page + 1 >= totalPages}
          onClick={() => onPageChange(page + 1)}
          className="rounded px-2 py-0.5 bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 disabled:opacity-30"
        >
          ›
        </button>
      </div>

      <div className="flex items-center gap-1">
        <span className="text-zinc-500 text-xs">{t("pagination.go_to")}</span>
        <input
          type="number"
          min={1}
          max={totalPages}
          value={goto}
          onChange={(e) => setGoto(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") jumpTo();
          }}
          className="w-16 rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-0.5 text-sm"
        />
      </div>

      <div className="ml-auto flex items-center gap-1">
        <span className="text-zinc-500 text-xs">{t("pagination.per_page")}</span>
        <select
          value={size}
          onChange={(e) => onSizeChange(Number(e.target.value))}
          className="rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-0.5 text-sm"
        >
          {[10, 50, 100].map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
      </div>
    </div>
  );
}
