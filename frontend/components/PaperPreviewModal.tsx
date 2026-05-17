"use client";

import { useEffect, useMemo, useState } from "react";
import { useUiStore } from "@/store/ui";
import { useDownloadsStore } from "@/store/downloads";
import { useFavoritesStore } from "@/store/favorites";
import { useLocaleStore } from "@/store/locale";
import { usePapersStore } from "@/store/papers";
import { useTranslationsStore } from "@/store/translations";
import { parseAuthors, fmtDate } from "@/lib/format";
import { openCachedPdf } from "@/lib/pdf";
import { apiFetch } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { detectLanguage, sameLanguageFamily } from "@/lib/lang";

export default function PaperPreviewModal() {
  const preview = useUiStore((s) => s.preview);
  const close = useUiStore((s) => s.closePreview);
  const flash = useUiStore((s) => s.flash);
  const ask = useUiStore((s) => s.ask);
  const addDownload = useDownloadsStore((s) => s.add);
  const refetchFavorites = useFavoritesStore((s) => s.load);
  const refetchDownloads = useDownloadsStore((s) => s.load);
  const refetchPapers = usePapersStore((s) => s.fetch);
  const locale = useLocaleStore((s) => s.locale);
  const t = useT();

  const [deleting, setDeleting] = useState(false);

  const paperId = preview.paper?.id ?? null;
  const translation = useTranslationsStore((s) =>
    paperId != null ? s.byKey[`${paperId}:${locale}`] : undefined
  );
  const fetchCached = useTranslationsStore((s) => s.fetchCached);
  const generate = useTranslationsStore((s) => s.generate);
  const clearLocal = useTranslationsStore((s) => s.clearLocal);
  const isLoading = useTranslationsStore((s) =>
    paperId != null && s.loading.has(`${paperId}:${locale}`)
  );

  // What language is the article actually in? Drives the Translate button's
  // visibility — show it whenever the article isn't already in the user's UI
  // language.
  //
  // We sample the BODY (introduction) first rather than title+abstract: HBR
  // Taiwan ships an English og:title even for Chinese articles, so reading
  // the title would invert the detection ("English title + chrome leftovers
  // skews detection to en even though 95% of the body is Chinese"). The full
  // body is hundreds of chars long, which makes the script-ratio heuristic
  // unambiguous. Abstract and title are only used as fallbacks for arxiv-style
  // papers that don't store a body.
  const articleLang = useMemo(() => {
    if (!preview.paper) return "other" as const;
    const sample =
      preview.paper.introduction?.trim() ||
      preview.paper.abstract?.trim() ||
      preview.paper.title?.trim() ||
      "";
    return detectLanguage(sample);
  }, [preview.paper]);
  const needsTranslation = preview.paper != null && !sameLanguageFamily(articleLang, locale);

  useEffect(() => {
    if (!preview.open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [preview.open, close]);

  // Probe cache once whenever the modal opens onto a paper that needs translation.
  useEffect(() => {
    if (preview.open && paperId != null && needsTranslation) {
      void fetchCached(paperId, locale);
    }
  }, [preview.open, paperId, locale, needsTranslation, fetchCached]);

  if (!preview.open || !preview.paper) return null;
  const p = preview.paper;
  const authors = parseAuthors(p);
  const displayTitle = translation?.title ?? p.title;
  const displayAbstract = translation?.abstract ?? p.abstract;
  const displayIntroduction = translation?.introduction ?? p.introduction;
  const isTranslated = needsTranslation && translation != null;

  // Manual / URL-imported articles store the full body in `introduction` and a
  // truncated copy in `abstract` (the feed-card teaser). Rendering both in the
  // modal just shows the same paragraph twice. Detect the prefix relationship
  // from the raw abstract — when a translation exists we still want to show it
  // since the abstract is the only field that gets translated.
  const abstractIsTeaserOfIntro = (() => {
    const a = p.abstract?.trim() ?? "";
    const intro = p.introduction?.trim() ?? "";
    if (!a || !intro) return false;
    const stripped = a.replace(/…$/u, "").trim();
    return stripped.length > 0 && intro.startsWith(stripped);
  })();

  // Manual-added papers (external_id starts with "manual-") can be deleted by
  // the user — they came in via the paste / URL-import flow and have no
  // upstream source to recover them from. Sync-fetched papers don't expose
  // this button; nuking those is the admin-page "Clear paper cache" affordance.
  const isManual = preview.paper?.externalId?.startsWith("manual-") ?? false;

  async function onDelete() {
    if (paperId == null) return;
    ask({
      title: t("modal.delete_confirm_title"),
      message: t("modal.delete_confirm_message"),
      confirmLabel: t("modal.delete"),
      danger: true,
      onConfirm: async () => {
        setDeleting(true);
        try {
          await apiFetch<void>(`/papers/${paperId}`, { method: "DELETE" });
          // Refresh anything that may have shown this paper.
          await Promise.all([refetchPapers(), refetchFavorites(), refetchDownloads()]);
          flash(t("modal.delete_done"), "success");
          close();
        } catch (e) {
          flash(e instanceof Error ? e.message : "Delete failed", "error");
        } finally {
          setDeleting(false);
        }
      },
    });
  }

  async function onTranslate() {
    if (paperId == null) return;
    try {
      await generate(paperId, locale);
    } catch (e) {
      flash(e instanceof Error ? e.message : "Translation failed", "error");
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={close}>
      <div
        role="dialog"
        aria-modal="true"
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-3xl max-h-[85vh] overflow-y-auto rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 shadow-xl"
      >
        <header className="border-b border-zinc-200 dark:border-zinc-800 p-5">
          <div className="flex items-center gap-2 text-xs text-zinc-500 mb-2 flex-wrap">
            {p.topicCode && <span className="rounded bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5">{p.topicCode}</span>}
            <span>{fmtDate(p.publishedAt)}</span>
            {preview.cached && (
              <span className="rounded bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200 px-2 py-0.5">{t("card.cached")}</span>
            )}
            {isTranslated && (
              <span className="rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200 px-2 py-0.5">
                {t("common.translated_label")} · {locale}
              </span>
            )}
          </div>
          <h2 className="text-xl font-semibold leading-tight">{displayTitle}</h2>
          {authors.length > 0 && <p className="text-sm text-zinc-500 mt-1">{authors.join(", ")}</p>}

          <div className="mt-3 flex items-center gap-2 flex-wrap">
            {needsTranslation && (
              !isTranslated ? (
                <button
                  type="button"
                  onClick={onTranslate}
                  disabled={isLoading}
                  className="rounded-md bg-purple-600 hover:bg-purple-700 text-white px-3 py-1 text-xs disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-400"
                >
                  {isLoading ? t("common.translating") : t("common.translate")}
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => paperId != null && clearLocal(paperId, locale)}
                  className="rounded-md border border-zinc-300 dark:border-zinc-700 px-3 py-1 text-xs hover:bg-zinc-100 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                >
                  {t("common.show_original")}
                </button>
              )
            )}
            {p.url && (
              <a
                href={p.url}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border border-zinc-300 dark:border-zinc-700 px-3 py-1 text-xs hover:bg-zinc-100 dark:hover:bg-zinc-800"
              >
                {t("modal.open_on_source")}
              </a>
            )}
            {isManual && (
              <button
                type="button"
                onClick={onDelete}
                disabled={deleting}
                className="rounded-md text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-3 py-1 text-xs disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
              >
                {deleting ? t("modal.deleting") : t("modal.delete")}
              </button>
            )}
            <button
              type="button"
              onClick={close}
              className="ml-auto rounded-md bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 px-3 py-1 text-xs"
            >
              {t("common.close")}
            </button>
          </div>
        </header>

        <section className="p-5 space-y-5 text-sm">
          {!abstractIsTeaserOfIntro && (
            <Block title={t("modal.abstract")} body={displayAbstract} />
          )}
          {displayIntroduction && <Block title={t("modal.intro")} body={displayIntroduction} />}
          {p.conclusion && <Block title={t("modal.conclusion")} body={p.conclusion} />}
        </section>

        <footer className="border-t border-zinc-200 dark:border-zinc-800 p-4 text-xs text-zinc-500 flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-3">
            <span>{p.externalId}</span>
            {p.pages != null && <span>· {p.pages} pages</span>}
          </div>
          <div className="flex items-center gap-2">
            {preview.cached ? (
              <button
                onClick={async () => {
                  try {
                    await openCachedPdf(p.id);
                  } catch (e) {
                    flash(e instanceof Error ? e.message : "Failed to open PDF", "error");
                  }
                }}
                className="rounded-md bg-emerald-600 hover:bg-emerald-700 text-white px-3 py-1.5"
              >
                {t("modal.open_cached_pdf")}
              </button>
            ) : p.pdfUrl ? (
              <button
                onClick={async () => {
                  try {
                    await addDownload(p.id);
                    flash("Saved to library", "success");
                  } catch (e) {
                    flash(e instanceof Error ? e.message : "Download failed", "error");
                  }
                }}
                className="rounded-md bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1.5"
              >
                {t("modal.download_save")}
              </button>
            ) : null}
          </div>
        </footer>
      </div>
    </div>
  );

  function Block({ title, body }: { title: string; body: string }) {
    return (
      <div>
        <div className="text-xs uppercase tracking-wide text-zinc-500 mb-1">{title}</div>
        <BodyContent body={body} />
      </div>
    );
  }
}

/**
 * Renders an article body that may contain inline markdown image markers
 * ({@code ![alt](url)}) produced by the backend's {@code HtmlExtractor}.
 * Splits on the marker pattern, renders each chunk either as a paragraph of
 * text (newlines preserved) or as an {@code <img>}.
 */
const IMG_MARKER = /!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g;

function BodyContent({ body }: { body: string }) {
  if (!body) return null;
  const parts: Array<{ kind: "text" | "img"; value: string; alt?: string }> = [];
  let last = 0;
  for (const m of body.matchAll(IMG_MARKER)) {
    if (m.index! > last) {
      parts.push({ kind: "text", value: body.slice(last, m.index!) });
    }
    parts.push({ kind: "img", value: m[2], alt: m[1] });
    last = m.index! + m[0].length;
  }
  if (last < body.length) {
    parts.push({ kind: "text", value: body.slice(last) });
  }
  return (
    <div className="leading-relaxed">
      {parts.map((p, i) =>
        p.kind === "img" ? (
          <img
            key={i}
            src={p.value}
            alt={p.alt ?? ""}
            loading="lazy"
            className="my-3 max-w-full rounded border border-zinc-200 dark:border-zinc-800"
          />
        ) : p.value.trim() ? (
          <p key={i} className="whitespace-pre-line">{p.value}</p>
        ) : null
      )}
    </div>
  );
}
