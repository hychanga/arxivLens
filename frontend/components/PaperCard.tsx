"use client";

import { useEffect, useMemo, useState } from "react";
import type { Paper } from "@/types";
import { parseAuthors, fmtDate, shortenUrl } from "@/lib/format";
import { scoreBadgeClass } from "@/lib/relevance";
import { useT } from "@/lib/i18n";
import { detectLanguage, sameLanguageFamily } from "@/lib/lang";
import { useLocaleStore } from "@/store/locale";
import { useTranslationsStore } from "@/store/translations";
import { useUiStore } from "@/store/ui";

interface Props {
  paper: Paper;
  saved?: boolean;
  cached?: boolean;
  score?: number;
  selectable?: boolean;
  selected?: boolean;
  onSelect?: (paperId: number) => void;
  showScore?: boolean;
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
}: Props) {
  const [expanded, setExpanded] = useState(false);
  const t = useT();
  const locale = useLocaleStore((s) => s.locale);
  const translation = useTranslationsStore((s) => s.byKey[`${paper.id}:${locale}`]);
  const fetchCached = useTranslationsStore((s) => s.fetchCached);
  const generate = useTranslationsStore((s) => s.generate);
  const clearLocal = useTranslationsStore((s) => s.clearLocal);
  const isLoading = useTranslationsStore((s) => s.loading.has(`${paper.id}:${locale}`));
  const flash = useUiStore((s) => s.flash);

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
            <h3 className="font-medium leading-tight hover:underline">{displayTitle}</h3>
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
              <p className="leading-relaxed whitespace-pre-line">{displayAbstract}</p>

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

              {paper.url && (
                <a
                  href={paper.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-block text-xs text-blue-600 dark:text-blue-400"
                >
                  {t("modal.open_on_source")}
                </a>
              )}
            </div>
          )}
        </div>
      </div>
    </li>
  );
}
