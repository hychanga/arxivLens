"use client";

import { useEffect, useMemo, useState } from "react";
import type { Paper } from "@/types";
import { parseAuthors, fmtDate, shortenUrl } from "@/lib/format";
import { scoreBadgeClass } from "@/lib/relevance";
import { useT } from "@/lib/i18n";
import { detectLanguage, sameLanguageFamily } from "@/lib/lang";
import { apiFetch } from "@/lib/api";
import { useLocaleStore } from "@/store/locale";
import { useTranslationsStore } from "@/store/translations";
import { useUiStore } from "@/store/ui";
import { usePapersStore } from "@/store/papers";
import { useFavoritesStore } from "@/store/favorites";
import { useDownloadsStore } from "@/store/downloads";
import BodyContent from "@/components/BodyContent";
import EditArticleModal from "@/components/EditArticleModal";
import { highlight } from "@/lib/highlight";

interface Props {
  paper: Paper;
  saved?: boolean;
  cached?: boolean;
  score?: number;
  selectable?: boolean;
  selected?: boolean;
  onSelect?: (paperId: number) => void;
  showScore?: boolean;
  /**
   * The topic the feed is currently filtered by. When it differs from the
   * paper's primary {@code topicCode}, this card is only here because the paper
   * is cross-listed into that topic — we flag that with a small marker.
   */
  activeTopicCode?: string | null;
  /** Active search query — matching spans in title and abstract are highlighted. */
  query?: string;
}

export default function PaperCard({
  paper,
  saved,
  cached,
  score,
  selectable = false,
  selected = false,
  onSelect,
  showScore = false,
  activeTopicCode = null,
  query = "",
}: Props) {
  const [expanded, setExpanded] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [editing, setEditing] = useState(false);
  const t = useT();
  const locale = useLocaleStore((s) => s.locale);
  const translation = useTranslationsStore((s) => s.byKey[`${paper.id}:${locale}`]);
  const fetchCached = useTranslationsStore((s) => s.fetchCached);
  const generate = useTranslationsStore((s) => s.generate);
  const clearLocal = useTranslationsStore((s) => s.clearLocal);
  const isLoading = useTranslationsStore((s) => s.loading.has(`${paper.id}:${locale}`));
  const flash = useUiStore((s) => s.flash);
  const ask = useUiStore((s) => s.ask);
  const refetchPapers = usePapersStore((s) => s.fetch);
  const refetchFavorites = useFavoritesStore((s) => s.load);
  const refetchDownloads = useDownloadsStore((s) => s.load);

  // Only manually-added articles (paste / URL import) can be removed here —
  // arxiv papers come back on the next sync, so the delete would be a no-op
  // from the user's POV. Backend enforces the same rule via externalId prefix.
  const isManual = paper.externalId?.startsWith("manual-") ?? false;

  function onDelete() {
    ask({
      title: t("modal.delete_confirm_title"),
      message: t("modal.delete_confirm_message"),
      confirmLabel: t("modal.delete"),
      danger: true,
      onConfirm: async () => {
        setDeleting(true);
        try {
          await apiFetch<void>(`/papers/${paper.id}`, { method: "DELETE" });
          await Promise.all([refetchPapers(), refetchFavorites(), refetchDownloads()]);
          flash(t("modal.delete_done"), "success");
        } catch (e) {
          flash(e instanceof Error ? e.message : "Delete failed", "error");
        } finally {
          setDeleting(false);
        }
      },
    });
  }

  // Whether the article's actual language differs from the user's UI locale —
  // sample the body first because chrome leftovers in title/abstract can
  // mis-skew detection (see PaperPreviewModal for the HBR-Taiwan rationale).
  const articleLang = useMemo(() => {
    const sample =
      paper.introduction?.trim() ||
      paper.abstract?.trim() ||
      paper.title?.trim() ||
      "";
    return detectLanguage(sample);
  }, [paper]);
  const needsTranslation = !sameLanguageFamily(articleLang, locale);

  // Probe cache once per (paper, locale). The store de-dupes loading + 404 markers internally.
  useEffect(() => {
    if (needsTranslation) void fetchCached(paper.id, locale);
  }, [paper.id, locale, needsTranslation, fetchCached]);

  const authors = parseAuthors(paper);
  const displayTitle = translation?.title ?? paper.title;
  const displayAbstract = translation?.abstract ?? paper.abstract;
  const isTranslated = needsTranslation && translation != null;

  async function onTranslateClick() {
    try {
      await generate(paper.id, locale);
    } catch (e) {
      flash(e instanceof Error ? e.message : "Translation failed", "error");
    }
  }

  return (
    <>
    <li
      className={`rounded-lg border p-4 ${
        saved
          ? "border-emerald-300 dark:border-emerald-700 bg-emerald-50/50 dark:bg-emerald-900/20"
          : "border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900"
      }`}
    >
      <div className="flex items-start gap-3">
        {selectable && (
          <div className="pt-0.5">
            {saved ? (
              <span aria-label="Already saved" title="Already saved" className="block h-3 w-3 rounded-full bg-emerald-500 mt-1.5" />
            ) : (
              <input
                type="checkbox"
                aria-label="Select paper"
                checked={selected}
                onChange={() => onSelect?.(paper.id)}
                className="mt-1.5"
              />
            )}
          </div>
        )}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 text-xs text-zinc-500 mb-1 flex-wrap">
            {paper.topicCode && (
              <span className="rounded bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5">{paper.topicCode}</span>
            )}
            {activeTopicCode && activeTopicCode !== paper.topicCode && (
              <span
                title={t("card.crosslisted", { topic: activeTopicCode })}
                className="rounded bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 px-1.5 py-0.5"
              >
                +{activeTopicCode}
              </span>
            )}
            <span>{fmtDate(paper.publishedAt)}</span>
            {saved && <span className="text-emerald-600 dark:text-emerald-400">{t("card.saved")}</span>}
            {cached && <span className="text-sky-600 dark:text-sky-400">{t("card.cached")}</span>}
            {showScore && score != null && score > 0 && (
              <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${scoreBadgeClass(score)}`}>
                {score}/10
              </span>
            )}
            {isTranslated && (
              <span className="rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200 px-1.5 py-0.5 text-[10px]">
                {t("common.translated_label")} · {locale}
              </span>
            )}
          </div>
          <button
            onClick={() => setExpanded((e) => !e)}
            className="text-left w-full"
          >
            <h3 className="font-medium leading-tight hover:underline">{highlight(displayTitle, query)}</h3>
          </button>
          {paper.url && (
            <a
              href={paper.url}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
              title={paper.url}
              className="block mt-0.5 text-xs text-blue-600 dark:text-blue-400 hover:underline truncate"
            >
              {shortenUrl(paper.url)} ↗
            </a>
          )}
          {authors.length > 0 && (
            <p className="text-xs text-zinc-500 mt-0.5 truncate">{authors.join(", ")}</p>
          )}
          {expanded && (
            <div className="mt-3 space-y-2 text-sm">
              <BodyContent body={displayAbstract} query={query} />

              {needsTranslation && (
                <div className="flex items-center gap-2 flex-wrap">
                  {!isTranslated ? (
                    <button
                      type="button"
                      onClick={onTranslateClick}
                      disabled={isLoading}
                      className="rounded-md bg-purple-600 hover:bg-purple-700 text-white px-2 py-1 text-xs disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-400"
                    >
                      {isLoading ? t("common.translating") : t("common.translate")}
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => clearLocal(paper.id, locale)}
                      className="rounded-md border border-zinc-300 dark:border-zinc-700 px-2 py-1 text-xs hover:bg-zinc-100 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                    >
                      {t("common.show_original")}
                    </button>
                  )}
                </div>
              )}

              <div className="flex items-center gap-3 text-xs">
                {paper.url && (
                  <a
                    href={paper.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-blue-600 dark:text-blue-400"
                  >
                    {t("modal.open_on_source")}
                  </a>
                )}
                {isManual && (
                  <div className="ml-auto flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setEditing(true)}
                      className="rounded border border-zinc-300 dark:border-zinc-600 px-2 py-1 hover:bg-zinc-50 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                    >
                      {t("modal.edit")}
                    </button>
                    <button
                      type="button"
                      onClick={onDelete}
                      disabled={deleting}
                      className="rounded text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-2 py-1 disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
                    >
                      {deleting ? t("modal.deleting") : t("modal.delete")}
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </li>
    {editing && (
      <EditArticleModal
        paper={paper}
        onClose={() => setEditing(false)}
        onSaved={() => void refetchPapers()}
      />
    )}
  </>
  );
}
