"use client";

import { useMemo } from "react";
import { useDownloadsStore } from "@/store/downloads";
import { useFavoritesStore } from "@/store/favorites";
import { usePreferencesStore } from "@/store/preferences";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useUiStore } from "@/store/ui";
import { parseAuthors, fmtDate, fmtSize } from "@/lib/format";
import { openCachedPdf } from "@/lib/pdf";
import { useT } from "@/lib/i18n";
import { NoteView } from "@/components/RichNoteEditor";

export default function LibraryPage() {
  const allItems = useDownloadsStore((s) => s.items);
  const remove = useDownloadsStore((s) => s.remove);
  const clear = useDownloadsStore((s) => s.clear);

  const favorites = useFavoritesStore((s) => s.items);
  const noteByPaperId = new Map(favorites.filter((f) => f.note).map((f) => [f.paper.id, f.note!]));

  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const sources = useSourcesStore((s) => s.items);
  const currentSource = findSourceById(sources, currentSourceId);

  const openPreview = useUiStore((s) => s.openPreview);
  const ask = useUiStore((s) => s.ask);
  const flash = useUiStore((s) => s.flash);
  const t = useT();

  // Per-source view: only show downloads belonging to the active source.
  const items = useMemo(
    () => (currentSourceId == null ? allItems : allItems.filter((d) => d.paper.sourceId === currentSourceId)),
    [allItems, currentSourceId]
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-lg font-semibold">
          {t("library.heading")}{currentSource ? <span className="ml-2 text-sm font-normal text-zinc-500">· {currentSource.name}</span> : null}
        </h1>
        <span className="text-sm text-zinc-500">
          {t("library.count", { n: items.length })}
        </span>
        {items.length > 0 && (
          <button
            onClick={() =>
              ask({
                title: t("library.confirm_clear_title"),
                message: t("library.confirm_delete_message"),
                confirmLabel: t("library.clear_all"),
                danger: true,
                onConfirm: async () => {
                  const removed = await clear();
                  flash(`Cleared ${removed}`, "success");
                },
              })
            }
            className="ml-auto rounded text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-3 py-1.5 text-sm"
          >
            {t("library.clear_all")}
          </button>
        )}
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-zinc-500">
          {allItems.length === 0
            ? t("library.empty")
            : t("library.empty_in_source", { source: currentSource?.name ?? "—" })}
        </p>
      ) : (
        <ul className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {items.map((d) => {
            const note = noteByPaperId.get(d.paper.id);
            const authors = parseAuthors(d.paper);
            return (
              <li
                key={d.id}
                className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4"
              >
                <div className="flex items-start gap-3">
                  <div aria-hidden className="text-2xl select-none">📄</div>
                  <div className="flex-1 min-w-0">
                    <h3 className="font-medium leading-tight">{d.paper.title}</h3>
                    {authors.length > 0 && <p className="text-xs text-zinc-500 mt-0.5 truncate">{authors.join(", ")}</p>}
                    <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-zinc-500">
                      {d.paper.topicCode && <span>{d.paper.topicCode}</span>}
                      <span>{fmtSize(d.sizeMB)}</span>
                      <span>{fmtDate(d.downloadedAt)}</span>
                      <span>{d.paper.externalId}</span>
                    </div>
                    {note && (
                      <NoteView note={note} className="mt-2 text-xs rounded bg-yellow-50 dark:bg-yellow-900/20 text-yellow-900 dark:text-yellow-100 p-2" />
                    )}
                    <div className="mt-3 flex gap-2 text-xs">
                      <button
                        onClick={async () => {
                          try {
                            await openCachedPdf(d.paper.id);
                          } catch (e) {
                            flash(e instanceof Error ? e.message : "Failed to open PDF", "error");
                          }
                        }}
                        className="rounded bg-emerald-600 hover:bg-emerald-700 text-white px-2 py-1"
                      >
                        {t("library.read_paper")}
                      </button>
                      <button
                        onClick={() => openPreview(d.paper, true)}
                        className="rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                      >
                        {t("library.preview_metadata")}
                      </button>
                      <button
                        onClick={() =>
                          ask({
                            title: t("library.confirm_delete_title"),
                            message: t("library.confirm_delete_message"),
                            confirmLabel: t("library.delete"),
                            danger: true,
                            onConfirm: async () => {
                              await remove(d.paper.id);
                              flash("Deleted", "success");
                            },
                          })
                        }
                        className="rounded text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-2 py-1"
                      >
                        {t("library.delete")}
                      </button>
                    </div>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
