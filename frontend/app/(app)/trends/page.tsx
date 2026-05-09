"use client";

import { useEffect, useMemo, useState } from "react";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { usePreferencesStore } from "@/store/preferences";
import { usePapersStore } from "@/store/papers";
import { apiFetch } from "@/lib/api";
import { useT } from "@/lib/i18n";
import type { TrendsResponse } from "@/types";

/**
 * Stable color palette indexed by topic order in {@link TrendsResponse.topics}
 * (which is sorted by total desc). Using inline styles instead of Tailwind classes
 * avoids the JIT-purge gotcha for dynamically-built class names.
 */
const PALETTE = [
  "#0ea5e9", // sky-500
  "#10b981", // emerald-500
  "#a855f7", // purple-500
  "#f59e0b", // amber-500
  "#f43f5e", // rose-500
  "#06b6d4", // cyan-500
  "#6366f1", // indigo-500
  "#14b8a6", // teal-500
  "#f97316", // orange-500
  "#ec4899", // pink-500
];

export default function TrendsPage() {
  const sources = useSourcesStore((s) => s.items);
  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const current = findSourceById(sources, currentSourceId);
  const topicFilter = usePapersStore((s) => s.topic);
  const t = useT();

  const [data, setData] = useState<TrendsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!current) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    apiFetch<TrendsResponse>(`/trends?source=${encodeURIComponent(current.code)}`)
      .then((r) => { if (!cancelled) setData(r); })
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : "Load failed"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [current]);

  // Color stays bound to the same topic regardless of filter so the legend reads
  // consistently if the user toggles the filter on and off.
  const colorByTopic = useMemo(() => {
    const map = new Map<string, string>();
    if (!data) return map;
    data.topics.forEach((tb, i) => map.set(tb.topicCode, PALETTE[i % PALETTE.length]));
    return map;
  }, [data]);

  // Topic codes to render — full list, or just the filtered one.
  const visibleTopics = useMemo<string[]>(() => {
    if (!data) return [];
    const all = data.topics.map((tb) => tb.topicCode);
    if (!topicFilter) return all;
    return all.filter((code) => code === topicFilter);
  }, [data, topicFilter]);

  // Filtered months: only count the chosen topic when filter is on. Required so
  // the chart's bar height + the metric cards both reflect the filter.
  const months = useMemo(() => {
    if (!data) return [];
    if (!topicFilter) return data.months;
    return data.months.map((m) => {
      const v = m.byTopic[topicFilter] ?? 0;
      return {
        yearMonth: m.yearMonth,
        byTopic: v > 0 ? { [topicFilter]: v } : {},
        total: v,
      };
    });
  }, [data, topicFilter]);

  const max = useMemo(() => {
    if (months.length === 0) return 0;
    return Math.max(1, ...months.map((m) => m.total));
  }, [months]);

  const filteredMetrics = useMemo(() => {
    if (!data) return null;
    if (!topicFilter) return data.metrics;
    let total = 0;
    let peakMonth = "—";
    let peakValue = -1;
    for (const m of months) {
      total += m.total;
      if (m.total > peakValue) { peakValue = m.total; peakMonth = m.yearMonth; }
    }
    return {
      totalPapers: total,
      monthlyAverage: Math.round((total / 12) * 10) / 10,
      peakMonth: peakValue > 0 ? peakMonth : "—",
      activeTopicCount: total > 0 ? 1 : 0,
    };
  }, [data, topicFilter, months]);

  const breakdownTopics = useMemo(() => {
    if (!data) return [];
    if (!topicFilter) return data.topics;
    return data.topics.filter((tb) => tb.topicCode === topicFilter);
  }, [data, topicFilter]);

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">
        {t("trends.heading")} — {current?.name ?? "—"}
        {topicFilter && (
          <span className="ml-2 text-sm font-normal text-zinc-500">· {topicFilter}</span>
        )}
      </h1>

      {loading && <p className="text-sm text-zinc-500">{t("common.loading")}</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {data && filteredMetrics && (
        <>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Metric label={t("trends.metric_total")}         value={filteredMetrics.totalPapers} />
            <Metric label={t("trends.metric_avg")}           value={filteredMetrics.monthlyAverage.toFixed(1)} />
            <Metric label={t("trends.metric_peak")}          value={filteredMetrics.peakMonth} />
            <Metric label={t("trends.metric_active_topics")} value={filteredMetrics.activeTopicCount} />
          </div>

          <div>
            <h2 className="text-sm font-semibold mb-2">{t("trends.section_monthly")}</h2>
            <div className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4">
              <div className="flex items-end gap-2 h-48">
                {months.map((m) => {
                  const barRatio = max > 0 ? m.total / max : 0;
                  // Reverse so the largest topic sits at the base (visual weight at bottom).
                  // Stack from bottom upward via flex-col-reverse.
                  const stack = [...visibleTopics].filter((code) => (m.byTopic[code] ?? 0) > 0);
                  return (
                    <div key={m.yearMonth} className="flex-1 h-full flex flex-col items-center justify-end gap-1">
                      <div
                        title={`${m.yearMonth}: ${m.total}`}
                        className="w-full rounded-t overflow-hidden flex flex-col-reverse"
                        style={{ height: `${m.total === 0 ? 2 : Math.max(4, barRatio * 100)}%` }}
                      >
                        {m.total > 0 &&
                          stack.map((code) => {
                            const count = m.byTopic[code] ?? 0;
                            const segPct = (count / m.total) * 100;
                            return (
                              <div
                                key={code}
                                title={`${code}: ${count}`}
                                style={{
                                  backgroundColor: colorByTopic.get(code) ?? "#94a3b8",
                                  height: `${segPct}%`,
                                  flexShrink: 0,
                                }}
                              />
                            );
                          })}
                      </div>
                      <span className="text-[10px] text-zinc-500 shrink-0">{m.yearMonth.slice(2)}</span>
                    </div>
                  );
                })}
              </div>

              {/* Legend */}
              {visibleTopics.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs text-zinc-600 dark:text-zinc-400">
                  {visibleTopics.map((code) => (
                    <span key={code} className="inline-flex items-center gap-1.5">
                      <span
                        aria-hidden
                        className="inline-block h-2.5 w-2.5 rounded-sm"
                        style={{ backgroundColor: colorByTopic.get(code) ?? "#94a3b8" }}
                      />
                      {code}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div>
            <h2 className="text-sm font-semibold mb-2">{t("trends.section_by_topic")}</h2>
            <ul className="space-y-2">
              {breakdownTopics.map((tb) => {
                const recentMax = Math.max(1, ...tb.recent5Months.map((m) => m.count));
                const color = colorByTopic.get(tb.topicCode) ?? "#94a3b8";
                return (
                  <li key={tb.topicCode} className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-3 text-sm">
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-medium inline-flex items-center gap-2">
                        <span aria-hidden className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: color }} />
                        {tb.topicCode}
                      </span>
                      <span className="text-xs text-zinc-500">{t("trends.topic_total", { n: tb.total })}</span>
                    </div>
                    <div className="grid grid-cols-5 gap-2">
                      {tb.recent5Months.map((m) => (
                        <div key={m.yearMonth} className="space-y-1">
                          <div className="h-1.5 bg-zinc-100 dark:bg-zinc-800 rounded">
                            <div
                              className="h-full rounded"
                              style={{ width: `${(m.count / recentMax) * 100}%`, backgroundColor: color }}
                            />
                          </div>
                          <div className="flex justify-between text-[10px] text-zinc-500">
                            <span>{m.yearMonth.slice(2)}</span>
                            <span>{m.count}</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </li>
                );
              })}
              {breakdownTopics.length === 0 && (
                <li className="text-sm text-zinc-500">{t("admin.no_topics")}</li>
              )}
            </ul>
          </div>
        </>
      )}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4">
      <div className="text-xs text-zinc-500">{label}</div>
      <div className="text-2xl font-semibold mt-1">{value}</div>
    </div>
  );
}
