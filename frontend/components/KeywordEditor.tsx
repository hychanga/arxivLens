"use client";

import { useState } from "react";
import { priorityClass } from "@/lib/relevance";
import { useT } from "@/lib/i18n";

interface Props {
  keywords: string[];
  onChange: (next: string[]) => void;
}

export default function KeywordEditor({ keywords, onChange }: Props) {
  const [draft, setDraft] = useState("");
  const t = useT();

  function add() {
    const v = draft.trim();
    if (!v) return;
    if (keywords.includes(v)) {
      setDraft("");
      return;
    }
    onChange([...keywords, v]);
    setDraft("");
  }

  function remove(idx: number) {
    onChange(keywords.filter((_, i) => i !== idx));
  }

  function move(idx: number, dir: -1 | 1) {
    const next = [...keywords];
    const target = idx + dir;
    if (target < 0 || target >= next.length) return;
    [next[idx], next[target]] = [next[target], next[idx]];
    onChange(next);
  }

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              add();
            }
          }}
          placeholder={t("sidebar.add_keyword_placeholder")}
          className="flex-1 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-1.5 text-sm"
        />
        <button
          onClick={add}
          disabled={!draft.trim()}
          className="rounded-md bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1.5 text-sm disabled:opacity-50"
        >
          {t("common.add")}
        </button>
      </div>

      {keywords.length === 0 ? (
        <p className="text-xs text-zinc-500">{t("sidebar.no_keywords_hint")}</p>
      ) : (
        <ul className="space-y-1">
          {keywords.map((kw, idx) => (
            <li
              key={kw + idx}
              className={`flex items-center gap-2 rounded-md px-2 py-1 text-xs ${priorityClass(idx, keywords.length)}`}
            >
              <span className="font-mono opacity-70 w-6">#{idx + 1}</span>
              <span className="flex-1 truncate">{kw}</span>
              <button
                aria-label="Move up"
                disabled={idx === 0}
                onClick={() => move(idx, -1)}
                className="rounded px-1 disabled:opacity-30 hover:bg-black/5 dark:hover:bg-white/10"
              >
                ▲
              </button>
              <button
                aria-label="Move down"
                disabled={idx === keywords.length - 1}
                onClick={() => move(idx, 1)}
                className="rounded px-1 disabled:opacity-30 hover:bg-black/5 dark:hover:bg-white/10"
              >
                ▼
              </button>
              <button
                aria-label="Remove keyword"
                onClick={() => remove(idx)}
                className="rounded px-1 hover:bg-black/5 dark:hover:bg-white/10"
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
