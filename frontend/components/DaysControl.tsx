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
 * Sentinel value persisted in {@code queryDays} to mean "no upper bound".
 * Backend treats it the same as any large value (it clamps to 3650 = ten
 * years, which exceeds anything in our data set).
 */
const ALL_DAYS = 3650;

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
    // Anything past the slider top jumps to the All sentinel; otherwise
    // clamp to the slider range so e.g. accidental negative values from
    // older persisted prefs don't poison the UI.
    const v = rounded > SLIDER_MAX ? ALL_DAYS : clamp(rounded, 1, SLIDER_MAX);
    onApply(v);
    setPending(v);
    setAppliedFlash(true);
  }

  const dirty = pending !== days;
  const isAll = pending >= ALL_DAYS;
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
