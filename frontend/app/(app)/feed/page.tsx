"use client";

import { useMemo, useState } from "react";
import { usePapersStore } from "@/store/papers";
import { usePreferencesStore } from "@/store/preferences";
import { useFavoritesStore } from "@/store/favorites";
import { useDownloadsStore } from "@/store/downloads";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useUiStore } from "@/store/ui";
import { attachScores, type ScoredPaper } from "@/lib/relevance";
import { useT } from "@/lib/i18n";
import type { SortMode } from "@/types";
import PaperCard from "@/components/PaperCard";
import Pagination from "@/components/Pagination";
import AddArticleButton from "@/components/AddArticleButton";

const SORT_OPTIONS: { id: SortMode; tKey: string; needsKeywords?: boolean }[] = [
  { id: "NEWEST",    tKey: "feed.sort_newest"    },
  { id: "OLDEST",    tKey: "feed.sort_oldest"    },
  { id: "TOPIC",     tKey: "feed.sort_topic"     },
  { id: "RELEVANCE", tKey: "feed.sort_relevance", needsKeywords: true },
];

export default function FeedPage() {
  const data = usePapersStore((s) => s.data);
  const loading = usePapersStore((s) => s.loading);
  const error = usePapersStore((s) => s.error);
  const page = usePapersStore((s) => s.page);
  const size = usePapersStore((s) => s.size);
  const setPage = usePapersStore((s) => s.setPage);
  const setSize = usePapersStore((s) => s.setSize);
  const selected = usePapersStore((s) => s.selected);
  const toggleSelect = usePapersStore((s) => s.toggleSelect);
  const selectAll = usePapersStore((s) => s.selectAll);
  const clearSelection = usePapersStore((s) => s.clearSelection);

  const queryDays = usePreferencesStore((s) => s.queryDays);
  const sortMode = usePreferencesStore((s) => s.sortMode) as SortMode;
  const keywordsBySource = usePreferencesStore((s) => s.keywords);
  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const perPage = usePreferencesStore((s) => s.perPage);
  const patchPrefs = usePreferencesStore((s) => s.patch);

  const sources = useSourcesStore((s) => s.items);
  const currentSource = findSourceById(sources, currentSourceId);
  const currentSourceCode = currentSource?.code ?? null;
  const fetchPapers = usePapersStore((s) => s.fetch);
  // Per-source keywords: arXiv keywords don't apply when viewing HBR feed and vice versa.
  const keywords = useMemo(
    () => (currentSourceCode ? keywordsBySource[currentSourceCode] ?? [] : []),
    [keywordsBySource, currentSourceCode]
  );

  const favorites = useFavoritesStore((s) => s.items);
  const addFavorite = useFavoritesStore((s) => s.add);
  const downloads = useDownloadsStore((s) => s.items);

  const flash = useUiStore((s) => s.flash);
  const t = useT();

  const [savingBatch, setSavingBatch] = useState(false);

  const favoritePaperIds = useMemo(
    () => new Set(favorites.map((f) => f.paper.id)),
    [favorites]
  );
  const downloadedPaperIds = useMemo(
    () => new Set(downloads.map((d) => d.paper.id)),
    [downloads]
  );

  const scored: ScoredPaper[] = useMemo(() => {
    if (!data) return [];
    return attachScores(data.items, keywords);
  }, [data, keywords]);

  const sorted = useMemo(() => {
    const arr = [...scored];
    switch (sortMode) {
      case "OLDEST":
        arr.sort((a, b) => new Date(a.publishedAt).getTime() - new Date(b.publishedAt).getTime());
        break;
      case "TOPIC":
        arr.sort((a, b) => (a.topicCode ?? "").localeCompare(b.topicCode ?? ""));
        break;
      case "RELEVANCE":
        arr.sort((a, b) => b.score - a.score);
        break;
      default:
        arr.sort((a, b) => new Date(b.publishedAt).getTime() - new Date(a.publishedAt).getTime());
    }
    return arr;
  }, [scored, sortMode]);

  const stats = useMemo(() => {
    const topicSet = new Set<string>();
    let ge5 = 0, ge10 = 0;
    sorted.forEach((p) => {
      if (p.topicCode) topicSet.add(p.topicCode);
      if (p.score >= 10) ge10++;
      else if (p.score >= 5) ge5++;
    });
    return { topics: topicSet.size, ge5, ge10 };
  }, [sorted]);

  const selectableIds = sorted.filter((p) => !favoritePaperIds.has(p.id)).map((p) => p.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));
  const someSelected = selectableIds.some((id) => selected.has(id));
  const indeterminate = someSelected && !allSelected;
  const selectedCount = selectableIds.filter((id) => selected.has(id)).length;

  async function saveSelected() {
    if (selectedCount === 0) return;
    setSavingBatch(true);
    let ok = 0;
    for (const id of selectableIds) {
      if (!selected.has(id)) continue;
      try {
        await addFavorite(id);
        ok++;
      } catch {
        // continue
      }
    }
    setSavingBatch(false);
    clearSelection();
    flash(`Saved ${ok} paper${ok === 1 ? "" : "s"}`, "success");
  }

  return (
    <div className="space-y-4">
      {/* toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold mr-2">{t("feed.heading")}</h1>

        <label className="flex items-center gap-2 text-sm">
          <CheckboxWithIndeterminate
            checked={allSelected}
            indeterminate={indeterminate}
            onChange={(checked) => {
              if (checked) selectAll(sorted.filter((p) => !favoritePaperIds.has(p.id)));
              else clearSelection();
            }}
          />
          <span className="text-zinc-500">{t("feed.select_all")}</span>
        </label>

        <div className="flex items-center gap-1 text-sm">
          <span className="text-zinc-500 mr-1">{t("feed.sort")}</span>
          {SORT_OPTIONS.map((o) => {
            if (o.needsKeywords && keywords.length === 0) return null;
            const active = sortMode === o.id;
            const purple = o.id === "RELEVANCE";
            return (
              <button
                key={o.id}
                onClick={() => patchPrefs({ sortMode: o.id })}
                className={
                  `rounded px-2 py-0.5 text-xs ` +
                  (active
                    ? purple
                      ? "bg-purple-600 text-white"
                      : "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                    : purple
                    ? "bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-200 hover:bg-purple-200"
                    : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700")
                }
              >
                {t(o.tKey)}
              </button>
            );
          })}
        </div>

        {/* Manual paste lives only on HBR. arXiv has a working RSS sync so the
            button would just confuse — every arXiv paper there is already there. */}
        {currentSource && currentSourceCode === "hbr" && (
          <div className="ml-auto">
            <AddArticleButton sourceId={currentSource.id} onAdded={() => void fetchPapers()} />
          </div>
        )}

        {selectedCount > 0 && (
          <button
            onClick={saveSelected}
            disabled={savingBatch}
            className={`${currentSourceCode === "hbr" ? "" : "ml-auto "}rounded-md bg-emerald-600 hover:bg-emerald-700 text-white px-3 py-1.5 text-sm disabled:opacity-50`}
          >
            {t("feed.save_n", { n: selectedCount })}
          </button>
        )}
      </div>

      {/* banner */}
      {data && (
        <div className="rounded-md bg-zinc-100 dark:bg-zinc-800/60 px-3 py-2 text-xs text-zinc-600 dark:text-zinc-300">
          {t("feed.showing", { n: sorted.length, total: data.totalItems, days: queryDays })}
        </div>
      )}

      {loading && <p className="text-zinc-500 text-sm">{t("common.loading")}</p>}
      {error && <p className="text-red-600 text-sm">{error}</p>}

      <ul className="space-y-3">
        {sorted.map((p) => (
          <PaperCard
            key={p.id}
            paper={p}
            saved={favoritePaperIds.has(p.id)}
            cached={downloadedPaperIds.has(p.id)}
            score={p.score}
            showScore={keywords.length > 0}
            selectable
            selected={selected.has(p.id)}
            onSelect={toggleSelect}
          />
        ))}
      </ul>

      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          totalItems={data.totalItems}
          size={size}
          onPageChange={setPage}
          onSizeChange={(n) => {
            setSize(n);
            void patchPrefs({ perPage: n });
          }}
        />
      )}

      {/* stats bar */}
      {data && (
        <div className="border-t border-zinc-200 dark:border-zinc-800 pt-3 mt-2 text-xs text-zinc-500 flex flex-wrap gap-x-5 gap-y-1">
          <span>{t("feed.stats_papers")}: {data.totalItems}</span>
          <span>{t("feed.stats_topics")}: {stats.topics}</span>
          <span>{t("feed.stats_days")}: {queryDays}</span>
          <span>{t("feed.stats_selected")}: {selectedCount}</span>
          <span>{t("feed.stats_page")}: {page + 1} / {Math.max(1, data.totalPages)}</span>
          <span>{t("feed.stats_score", { ge5: stats.ge5, ge10: stats.ge10 })}</span>
          <span className="ml-auto">{t("feed.stats_per_page")}: {perPage}</span>
        </div>
      )}
    </div>
  );
}

function CheckboxWithIndeterminate({
  checked,
  indeterminate,
  onChange,
}: {
  checked: boolean;
  indeterminate: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <input
      type="checkbox"
      checked={checked}
      ref={(el) => {
        if (el) el.indeterminate = indeterminate;
      }}
      onChange={(e) => onChange(e.target.checked)}
    />
  );
}
