"use client";

import { useEffect, useState } from "react";
import { clamp } from "@/lib/format";
import { useT } from "@/lib/i18n";

interface Props {
  days: number;
  onApply: (next: number) => void;
}

const QUICK = [
  { d: 7,   label: "7d"  },
  { d: 30,  label: "30d" },
  { d: 90,  label: "90d" },
  { d: 180, label: "6mo" },
  { d: 365, label: "1yr" },
];

export default function DaysControl({ days, onApply }: Props) {
  const [pending, setPending] = useState(days);
  const [appliedFlash, setAppliedFlash] = useState(false);
  const t = useT();

  useEffect(() => setPending(days), [days]);
  useEffect(() => {
    if (!appliedFlash) return;
    const timer = setTimeout(() => setAppliedFlash(false), 1800);
    return () => clearTimeout(timer);
  }, [appliedFlash]);

  function apply(next: number) {
    const v = clamp(Math.round(next), 1, 365);
    onApply(v);
    setPending(v);
    setAppliedFlash(true);
  }

  const dirty = pending !== days;

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-xs">
        <span className="uppercase tracking-wide text-zinc-500">{t("sidebar.days")}</span>
        <span className="text-zinc-700 dark:text-zinc-200 font-medium">{pending}</span>
        {appliedFlash && <span className="text-emerald-600 dark:text-emerald-300">{t("common.applied")}</span>}
      </div>

      <input
        type="range"
        min={1}
        max={365}
        value={pending}
        onChange={(e) => setPending(Number(e.target.value))}
        className="w-full"
      />

      <div className="flex items-center gap-1 flex-wrap">
        {QUICK.map((q) => (
          <button
            key={q.d}
            onClick={() => apply(q.d)}
            className={`rounded px-2 py-0.5 text-xs ${
              days === q.d
                ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
            }`}
          >
            {q.label}
          </button>
        ))}
        {dirty && (
          <button
            onClick={() => apply(pending)}
            className="ml-auto rounded bg-emerald-600 hover:bg-emerald-700 text-white px-2 py-0.5 text-xs"
          >
            {t("sidebar.apply")}
          </button>
        )}
      </div>
    </div>
  );
}
