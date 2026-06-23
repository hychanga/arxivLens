"use client";

import { useMemo, useState } from "react";
import { useFavoritesStore } from "@/store/favorites";
import { useDownloadsStore } from "@/store/downloads";
import { useLocaleStore } from "@/store/locale";
import { usePreferencesStore } from "@/store/preferences";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useUiStore } from "@/store/ui";
import { fmtDate } from "@/lib/format";
import { openCachedPdf } from "@/lib/pdf";
import { useT } from "@/lib/i18n";
import { RichNoteEditor, NoteView } from "@/components/RichNoteEditor";

export default function FavoritesPage() {
  const allItems = useFavoritesStore((s) => s.items);
  const remove = useFavoritesStore((s) => s.remove);
  const updateNote = useFavoritesStore((s) => s.updateNote);
  const generateSummary = useFavoritesStore((s) => s.generateSummary);

  const downloads = useDownloadsStore((s) => s.items);
  const addDownload = useDownloadsStore((s) => s.add);

  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const sources = useSourcesStore((s) => s.items);
  const currentSource = findSourceById(sources, currentSourceId);

  // arXiv papers always have a deterministic PDF URL the backend can fall
  // back to even when paper.pdfUrl came back blank from an older sync, so
  // treat any arXiv-sourced paper as downloadable from the frontend's POV.
  const arxivSourceId = useMemo(
    () => sources.find((s) => s.code === "arxiv")?.id ?? null,
    [sources]
  );
  const isDownloadable = (sourceId: number, pdfUrl: string | null) =>
    Boolean(pdfUrl) || (arxivSourceId != null && sourceId === arxivSourceId);

  const openPreview = useUiStore((s) => s.openPreview);
  const ask = useUiStore((s) => s.ask);
  const flash = useUiStore((s) => s.flash);
  const locale = useLocaleStore((s) => s.locale);
  const t = useT();

  const [editingNote, setEditingNote] = useState<number | null>(null);
  const [downloadingAll, setDownloadingAll] = useState(false);
  const [generatingFor, setGeneratingFor] = useState<number | null>(null);

  // Per-source view: Favorites of arXiv != Favorites of HBR. Switch the top source tab to flip.
  const items = useMemo(
    () => (currentSourceId == null ? allItems : allItems.filter((f) => f.paper.sourceId === currentSourceId)),
    [allItems, currentSourceId]
  );

  const cachedPaperIds = new Set(downloads.map((d) => d.paper.id));

  // Only PDF-backed papers can be downloaded. HBR / manual / BW articles have
  // no PDF source; arXiv papers always do (the backend reconstructs the URL
  // when pdfUrl is missing from a stale DB row).
  const downloadable = useMemo(
    () => items.filter(
      (f) => isDownloadable(f.paper.sourceId, f.paper.pdfUrl) && !cachedPaperIds.has(f.paper.id)
    ),
    [items, cachedPaperIds, arxivSourceId]
  );

  async function handleDownloadAll() {
    setDownloadingAll(true);
    let ok = 0;
    for (const f of downloadable) {
      try {
        await addDownload(f.paper.id);
        ok++;
      } catch { /* skip */ }
    }
    setDownloadingAll(false);
    flash(`Downloaded ${ok} paper${ok === 1 ? "" : "s"}`, ok > 0 ? "success" : "info");
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-lg font-semibold">
          {t("favorites.heading")}{currentSource ? <span className="ml-2 text-sm font-normal text-zinc-500">· {currentSource.name}</span> : null}
        </h1>
        <span className="text-sm text-zinc-500">{t("favorites.count", { n: items.length })}</span>
        {downloadable.length > 0 && (
          <button
            onClick={handleDownloadAll}
            disabled={downloadingAll}
            className="ml-auto rounded-md bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1.5 text-sm disabled:opacity-50"
          >
            {downloadingAll ? t("favorites.downloading") : t("favorites.download_all")}
          </button>
        )}
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-zinc-500">
          {allItems.length === 0
            ? t("favorites.empty")
            : t("favorites.empty_in_source", { source: currentSource?.name ?? "—" })}
        </p>
      ) : (
        <ul className="space-y-3">
          {items.map((f) => {
            const cached = cachedPaperIds.has(f.paper.id);
            return (
              <li
                key={f.id}
                className="rounded-lg border border-emerald-300 dark:border-emerald-700 bg-emerald-50/50 dark:bg-emerald-900/20 p-4"
              >
                <div className="flex items-center gap-2 text-xs text-zinc-500 mb-1 flex-wrap">
                  {f.paper.topicCode && (
                    <span className="rounded bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5">{f.paper.topicCode}</span>
                  )}
                  <span>{fmtDate(f.savedAt)}</span>
                  {cached && <span className="rounded bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200 px-2 py-0.5">cached</span>}
                  {f.note && <span className="rounded bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-200 px-2 py-0.5">note</span>}
                  {f.summary && <span className="rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200 px-2 py-0.5">AI summary</span>}
                </div>
                <h3 className="font-medium leading-tight">{f.paper.title}</h3>

                {f.note && editingNote !== f.id && (
                  <NoteView note={f.note} className="mt-2 text-sm bg-white/60 dark:bg-black/20 rounded p-2" />
                )}

                {editingNote === f.id && (
                  <RichNoteEditor
                    initialHtml={f.note ?? ""}
                    onCancel={() => setEditingNote(null)}
                    onSave={async (html) => {
                      await updateNote(f.id, html);
                      setEditingNote(null);
                      flash("Note saved", "success");
                    }}
                  />
                )}

                {f.summary && (
                  <div className="mt-2 rounded bg-white/60 dark:bg-black/20 p-3 text-sm space-y-2">
                    <p>{f.summary.summary}</p>
                    {f.summary.key_points && f.summary.key_points.length > 0 && (
                      <ul className="list-disc list-inside text-xs space-y-0.5">
                        {f.summary.key_points.map((kp, i) => <li key={i}>{kp}</li>)}
                      </ul>
                    )}
                    {f.summary.tags && f.summary.tags.length > 0 && (
                      <div className="flex gap-1 flex-wrap">
                        {f.summary.tags.map((t) => (
                          <span key={t} className="rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200 px-1.5 py-0.5 text-xs">
                            {t}
                          </span>
                        ))}
                      </div>
                    )}
                    <div className="text-xs text-zinc-500">
                      {f.summary.difficulty && <>Difficulty: {f.summary.difficulty} · </>}
                      {f.summary.readingTimeMin != null && <>~{f.summary.readingTimeMin} min read</>}
                    </div>
                  </div>
                )}

                <div className="mt-3 flex flex-wrap gap-2 text-xs">
                  <button
                    onClick={() => openPreview(f.paper, cached)}
                    className="rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                  >
                    {t("favorites.preview")}
                  </button>
                  {isDownloadable(f.paper.sourceId, f.paper.pdfUrl) && cached && (
                    <button
                      onClick={async () => {
                        try {
                          await openCachedPdf(f.paper.id);
                        } catch (e) {
                          flash(e instanceof Error ? e.message : "Failed to open PDF", "error");
                        }
                      }}
                      className="rounded bg-emerald-600 hover:bg-emerald-700 text-white px-2 py-1"
                    >
                      {t("favorites.open_cached")}
                    </button>
                  )}
                  {isDownloadable(f.paper.sourceId, f.paper.pdfUrl) && !cached && (
                    <button
                      onClick={async () => {
                        try {
                          await addDownload(f.paper.id);
                          flash("Downloaded", "success");
                        } catch (e) {
                          flash(e instanceof Error ? e.message : "Download failed", "error");
                        }
                      }}
                      className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-2 py-1"
                    >
                      {t("favorites.download_pdf")}
                    </button>
                  )}
                  {!isDownloadable(f.paper.sourceId, f.paper.pdfUrl) && f.paper.url && (
                    <a
                      href={f.paper.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                    >
                      {t("modal.open_on_source")}
                    </a>
                  )}
                  <button
                    onClick={async () => {
                      setGeneratingFor(f.id);
                      try {
                        await generateSummary(f.id, locale);
                        flash("Summary generated", "success");
                      } catch (e) {
                        flash(e instanceof Error ? e.message : "Summary unavailable", "error");
                      } finally {
                        setGeneratingFor(null);
                      }
                    }}
                    disabled={generatingFor === f.id}
                    className="rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800 disabled:opacity-50"
                  >
                    {generatingFor === f.id
                      ? t("favorites.generating")
                      : f.summary
                        ? t("favorites.regenerate_summary")
                        : t("favorites.generate_summary")}
                  </button>
                  <button
                    onClick={() => setEditingNote(f.id)}
                    className="rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                  >
                    {f.note ? t("favorites.edit_note") : t("favorites.add_note")}
                  </button>
                  <button
                    onClick={() =>
                      ask({
                        title: t("favorites.confirm_remove_title"),
                        message: t("favorites.confirm_remove_message"),
                        confirmLabel: t("favorites.remove"),
                        danger: true,
                        onConfirm: async () => {
                          await remove(f.id);
                          flash("Removed", "success");
                        },
                      })
                    }
                    className="rounded text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-2 py-1"
                  >
                    {t("favorites.remove")}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
