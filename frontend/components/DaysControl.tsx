"use client";

import { useEffect, useState } from "react";
import { clamp } from "@/lib/format";
import { useT } from "@/lib/i18n";

interface Props {
  days: number;
  onApply: (next: number) => void;
}

/**
 * Slider tops out at 2 years so each pixel actually maps to a useful step
 * (1px ≈ 3 days on a typical sidebar width). Anything beyond that is the
 * "All" sentinel below — the slider can't span both ranges without becoming
 * unusable for short windows.
 */
const SLIDER_MAX = 730;

/**
 * Sentinel persisted in {@code queryDays} for "no upper bound". 0 is the
 * canonical value the backend understands as "skip the publishedAt filter
 * entirely" (the {@code Math.min(3650, …)} clamp would still hide rows older
 * than ten years — we have content going back to 2009). We also treat any
 * value past the slider top as legacy "all" so prefs saved before the cut-over
 * (e.g. 3650) still display as All instead of snapping back to 730.
 */
const ALL_DAYS = 0;

const QUICK = [
  { d: 7,    label: "7d"  },
  { d: 30,   label: "30d" },
  { d: 90,   label: "90d" },
  { d: 180,  label: "6mo" },
  { d: 365,  label: "1yr" },
  { d: 730,  label: "2yr" },
  { d: ALL_DAYS, label: "All" },
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
    const rounded = Math.round(next);
    // ≤0 or anything past the slider top is the "All" sentinel; otherwise
    // clamp to the slider range. The legacy >SLIDER_MAX branch coerces any
    // 3650-style value from earlier deploys into the new 0 canonical form
    // the next time the user touches the control.
    const v = rounded <= 0 || rounded > SLIDER_MAX
        ? ALL_DAYS
        : clamp(rounded, 1, SLIDER_MAX);
    onApply(v);
    setPending(v);
    setAppliedFlash(true);
  }

  const dirty = pending !== days;
  // 0 is the canonical "All"; >SLIDER_MAX catches prefs persisted by the
  // older 3650-sentinel implementation.
  const isAll = pending === ALL_DAYS || pending > SLIDER_MAX;
  const displayValue = isAll ? t("sidebar.days_all") : pending;
  // Slider visual position must stay within [1, SLIDER_MAX]; "All" parks it
  // at the right edge.
  const sliderValue = isAll ? SLIDER_MAX : Math.min(Math.max(pending, 1), SLIDER_MAX);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-xs">
        <span className="uppercase tracking-wide text-zinc-500">{t("sidebar.days")}</span>
        <span className="text-zinc-700 dark:text-zinc-200 font-medium">{displayValue}</span>
        {appliedFlash && <span className="text-emerald-600 dark:text-emerald-300">{t("common.applied")}</span>}
      </div>

      <input
        type="range"
        min={1}
        max={SLIDER_MAX}
        value={sliderValue}
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
