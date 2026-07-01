"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useAuthStore } from "@/store/auth";
import { useUiStore } from "@/store/ui";
import { useT } from "@/lib/i18n";
import {
  listGolf, createGolf, updateGolf, removeGolf,
  uploadGolfPdf, suggestGolfTags,
  splitTags, youtubeId, youtubeEmbed, GOLF_CATEGORIES,
  type GolfResource, type GolfResourceInput,
} from "@/lib/golfApi";
import GolfRichEditor, { looksLikeHtml, decodeDual, adaptHtmlColors } from "@/components/GolfRichEditor";

// Encode content as {raw, light, dark} so the card can pick the right version.
// "light" and "dark" are auto-derived at save time; the user only edits "raw".
function encodeDualAuto(raw: string): string {
  if (!raw) return "";
  return JSON.stringify({
    raw,
    light: adaptHtmlColors(raw, false), // very-light colours → dark complement
    dark:  adaptHtmlColors(raw, true),  // very-dark colours → light complement
  });
}

const EMPTY_FORM: GolfResourceInput = {
  title: "", summary: "", content: "",
  category: "", tags: "", videoUrl: "", pdfUrl: "", source: "",
};

export default function GolfPage() {
  const isLoggedIn = !!useAuthStore((s) => s.user);
  const flash = useUiStore((s) => s.flash);
  const ask = useUiStore((s) => s.ask);
  const t = useT();

  const [items, setItems] = useState<GolfResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<GolfResource | null>(null);
  const [form, setForm] = useState<GolfResourceInput>(EMPTY_FORM);
  const [tagInput, setTagInput] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [uploadingPdf, setUploadingPdf] = useState(false);
  const [suggestingTags, setSuggestingTags] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(async (q?: string) => {
    setLoading(true);
    try {
      setItems(await listGolf(q));
    } catch (e) {
      flash(e instanceof Error ? e.message : "載入失敗", "error");
    } finally {
      setLoading(false);
    }
  }, [flash]);

  useEffect(() => {
    if (!search.trim()) {
      void load();
      return;
    }
    const tid = setTimeout(() => void load(search.trim()), 300);
    return () => clearTimeout(tid);
  }, [search, load]);

  const categories = useMemo(() => {
    const present = new Set(items.map(r => r.category).filter((c): c is string => !!c));
    const ordered = GOLF_CATEGORIES.filter(c => present.has(c));
    const extra = [...present].filter(c => !(GOLF_CATEGORIES as readonly string[]).includes(c));
    return [...ordered, ...extra];
  }, [items]);

  const filtered = useMemo(() =>
    activeCategory ? items.filter(r => r.category === activeCategory) : items,
  [items, activeCategory]);

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setTagInput("");
    setModalOpen(true);
  }

  function openEdit(r: GolfResource) {
    setEditing(r);
    setForm({
      title: r.title,
      summary: decodeDual(r.summary).raw,
      content: decodeDual(r.content).raw,
      category: r.category ?? "",
      tags: r.tags ?? "",
      videoUrl: r.videoUrl ?? "",
      pdfUrl: r.pdfUrl ?? "",
      source: r.source ?? "",
    });
    setTagInput("");
    setModalOpen(true);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.title?.trim()) return;
    setSubmitting(true);
    try {
      const payload: GolfResourceInput = {
        title:    form.title.trim(),
        summary:  encodeDualAuto(form.summary ?? ""),
        content:  encodeDualAuto(form.content ?? ""),
        category: form.category,
        tags:     form.tags,
        videoUrl: form.videoUrl,
        pdfUrl:   form.pdfUrl,
        source:   form.source,
      };
      if (editing) {
        const updated = await updateGolf(editing.id, payload);
        setItems(prev => prev.map(r => r.id === updated.id ? updated : r));
        flash(t("golf.updated"), "success");
      } else {
        const created = await createGolf(payload);
        setItems(prev => [created, ...prev]);
        flash(t("golf.created"), "success");
      }
      setModalOpen(false);
    } catch (e) {
      flash(e instanceof Error ? e.message : t("golf.save_error"), "error");
    } finally {
      setSubmitting(false);
    }
  }

  function confirmDelete(r: GolfResource) {
    ask({
      title: t("golf.delete_title"),
      message: t("golf.delete_msg"),
      confirmLabel: t("library.delete"),
      danger: true,
      onConfirm: async () => {
        await removeGolf(r.id);
        setItems(prev => prev.filter(x => x.id !== r.id));
        flash(t("golf.deleted"), "success");
      },
    });
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold">{t("golf.heading")}</h1>
        <div className="flex items-center ml-auto gap-2">
          <div className="relative">
            <input
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder={t("golf.search_placeholder")}
              className="rounded border border-zinc-300 dark:border-zinc-600 bg-white dark:bg-zinc-900 pl-2 pr-6 py-0.5 text-sm focus:outline-none focus:ring-1 focus:ring-zinc-400 dark:focus:ring-zinc-500 w-44"
            />
            {search && (
              <button
                type="button"
                onClick={() => setSearch("")}
                className="absolute right-1.5 top-1/2 -translate-y-1/2 text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 leading-none"
              >
                ✕
              </button>
            )}
          </div>
          {isLoggedIn && (
            <button
              type="button"
              onClick={openCreate}
              className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm hover:opacity-90"
            >
              {t("golf.add_button")}
            </button>
          )}
        </div>
      </div>

      {categories.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          <button
            type="button"
            onClick={() => setActiveCategory(null)}
            className={`rounded-full px-3 py-0.5 text-xs ${
              !activeCategory
                ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
            }`}
          >
            {t("sidebar.all")}
          </button>
          {categories.map(cat => (
            <button
              key={cat}
              type="button"
              onClick={() => setActiveCategory(activeCategory === cat ? null : cat)}
              className={`rounded-full px-3 py-0.5 text-xs ${
                activeCategory === cat
                  ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                  : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
              }`}
            >
              {cat}
            </button>
          ))}
        </div>
      )}

      {loading && <p className="text-sm text-zinc-500">{t("common.loading")}</p>}

      <ul className="space-y-3">
        {filtered.map(r => (
          <ResourceCard
            key={r.id}
            item={r}
            canManage={isLoggedIn}
            expanded={expanded === r.id}
            onToggle={() => setExpanded(expanded === r.id ? null : r.id)}
            onEdit={() => openEdit(r)}
            onDelete={() => confirmDelete(r)}
            t={t}
          />
        ))}
        {!loading && filtered.length === 0 && (
          <li className="text-sm text-zinc-500">{t("golf.empty")}</li>
        )}
      </ul>

      {modalOpen && (
        <div
          onClick={() => setModalOpen(false)}
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/50 px-4 py-8"
          role="dialog"
          aria-modal="true"
        >
          <div
            onClick={e => e.stopPropagation()}
            className="flex w-full max-w-2xl flex-col max-h-[calc(100vh-4rem)] rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 shadow-xl"
          >
            <h2 className="shrink-0 px-6 pt-6 pb-4 text-lg font-semibold">
              {editing ? t("golf.edit_heading") : t("golf.add_heading")}
            </h2>
            <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
              <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-6">
              <FormField label={t("golf.field_title")} required>
                <input
                  type="text"
                  required
                  value={form.title ?? ""}
                  onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
                  className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm"
                />
              </FormField>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <FormField label={t("golf.field_category")}>
                  <input
                    type="text"
                    list="golf-categories"
                    value={form.category ?? ""}
                    onChange={e => setForm(f => ({ ...f, category: e.target.value }))}
                    className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm"
                  />
                  <datalist id="golf-categories">
                    {GOLF_CATEGORIES.map(c => <option key={c} value={c} />)}
                  </datalist>
                </FormField>

                <FormField label={t("golf.field_source")}>
                  <input
                    type="text"
                    value={form.source ?? ""}
                    onChange={e => setForm(f => ({ ...f, source: e.target.value }))}
                    placeholder="https://..."
                    className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm"
                  />
                </FormField>
              </div>

              <FormField label={t("golf.field_tags")}>
                <div className="space-y-1.5">
                  <div className="flex justify-end">
                    <button
                      type="button"
                      disabled={suggestingTags || !form.title?.trim()}
                      onClick={async () => {
                        setSuggestingTags(true);
                        try {
                          const suggested = await suggestGolfTags(
                            form.title ?? "",
                            form.summary ?? "",
                            form.content ?? ""
                          );
                          const existing = splitTags(form.tags ?? null);
                          const added = suggested.filter(tag => !existing.includes(tag));
                          if (added.length > 0) {
                            setForm(f => ({ ...f, tags: [...existing, ...added].join(",") }));
                          }
                          flash(added.length > 0 ? `已新增 ${added.length} 個 AI 標籤` : "無新標籤可加", "success");
                        } catch (e) {
                          flash(e instanceof Error ? e.message : "AI 標籤失敗", "error");
                        } finally {
                          setSuggestingTags(false);
                        }
                      }}
                      className="text-xs text-indigo-600 dark:text-indigo-400 hover:underline disabled:opacity-40 disabled:no-underline"
                    >
                      {suggestingTags ? t("golf.ai_suggesting") : t("golf.ai_suggest_tags")}
                    </button>
                  </div>
                  <TagEditor
                    tags={splitTags(form.tags ?? null)}
                    tagInput={tagInput}
                    setTagInput={setTagInput}
                    setForm={setForm}
                  />
                </div>
              </FormField>

              <FormField label={t("golf.field_summary")}>
                <GolfRichEditor
                  key={`summary-${editing?.id ?? "new"}`}
                  value={form.summary ?? ""}
                  onChange={html => setForm(f => ({ ...f, summary: html }))}
                  minHeight="5rem"
                />
              </FormField>

              <FormField label={t("golf.field_content")}>
                <GolfRichEditor
                  key={`content-${editing?.id ?? "new"}`}
                  value={form.content ?? ""}
                  onChange={html => setForm(f => ({ ...f, content: html }))}
                  minHeight="12rem"
                  placeholder={t("golf.content_placeholder")}
                />
              </FormField>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <FormField label={t("golf.field_video")}>
                  <input
                    type="text"
                    value={form.videoUrl ?? ""}
                    onChange={e => setForm(f => ({ ...f, videoUrl: e.target.value }))}
                    placeholder="https://youtu.be/..."
                    className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm"
                  />
                </FormField>
                <FormField label={t("golf.field_pdf")}>
                  <div className="space-y-2">
                    <input
                      type="text"
                      value={form.pdfUrl ?? ""}
                      onChange={e => setForm(f => ({ ...f, pdfUrl: e.target.value }))}
                      placeholder="https://..."
                      className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm"
                    />
                    <label className={`inline-flex items-center gap-2 cursor-pointer rounded-md border border-zinc-300 dark:border-zinc-700 px-3 py-1.5 text-xs hover:bg-zinc-50 dark:hover:bg-zinc-800 ${uploadingPdf ? "opacity-50 pointer-events-none" : ""}`}>
                      {uploadingPdf ? t("golf.uploading_pdf") : t("golf.upload_pdf")}
                      <input
                        type="file"
                        accept=".pdf,application/pdf"
                        className="hidden"
                        disabled={uploadingPdf}
                        onChange={async (e) => {
                          const f = e.target.files?.[0];
                          if (!f) return;
                          if (f.size > 20 * 1024 * 1024) {
                            flash("PDF 檔案不能超過 20 MB", "error");
                            e.target.value = "";
                            return;
                          }
                          setUploadingPdf(true);
                          try {
                            const url = await uploadGolfPdf(f);
                            setForm(prev => ({ ...prev, pdfUrl: url }));
                            flash(t("golf.upload_pdf_done"), "success");
                          } catch (err) {
                            flash(err instanceof Error ? err.message : "Upload failed", "error");
                          } finally {
                            setUploadingPdf(false);
                            e.target.value = "";
                          }
                        }}
                      />
                    </label>
                  </div>
                </FormField>
              </div>
              </div>

              <div className="flex shrink-0 justify-end gap-2 border-t border-zinc-100 dark:border-zinc-800 px-6 py-4">
                <button
                  type="button"
                  onClick={() => setModalOpen(false)}
                  className="rounded-md bg-zinc-100 dark:bg-zinc-800 px-3 py-1.5 text-sm hover:bg-zinc-200 dark:hover:bg-zinc-700"
                >
                  {t("common.cancel")}
                </button>
                <button
                  type="submit"
                  disabled={submitting || !form.title?.trim()}
                  className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm disabled:opacity-50"
                >
                  {submitting ? t("golf.saving") : t("common.save")}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

function ResourceCard({
  item, canManage, expanded, onToggle, onEdit, onDelete, t,
}: {
  item: GolfResource;
  canManage: boolean;
  expanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
  onDelete: () => void;
  t: ReturnType<typeof useT>;
}) {
  const tags = splitTags(item.tags);
  const vid = youtubeId(item.videoUrl);
  const embedUrl = vid ? `https://www.youtube.com/embed/${vid}` : null;
  const [playing, setPlaying] = useState(false);

  // Track dark mode to pick the right dual-mode content version.
  const [isDark, setIsDark] = useState(() =>
    typeof window !== "undefined" && document.documentElement.classList.contains("dark")
  );
  useEffect(() => {
    const el = document.documentElement;
    setIsDark(el.classList.contains("dark"));
    const obs = new MutationObserver(() => setIsDark(el.classList.contains("dark")));
    obs.observe(el, { attributes: true, attributeFilter: ["class"] });
    return () => obs.disconnect();
  }, []);

  // Close the reading pop-up with Escape for keyboard users.
  useEffect(() => {
    if (!expanded) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onToggle(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [expanded, onToggle]);

  const sumDual = decodeDual(item.summary);
  const conDual = decodeDual(item.content);
  const displaySummary = isDark ? (sumDual.dark || sumDual.light) : sumDual.light;
  const displayContent = isDark ? (conDual.dark || conDual.light) : conDual.light;

  return (
    <li className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4">
      <div className="flex items-start gap-3">
        {/* text area */}
        <button type="button" onClick={onToggle} className="flex-1 text-left min-w-0">
          <div className="flex flex-wrap items-center gap-2 mb-1">
            {item.category && (
              <span className="rounded-full bg-emerald-100 dark:bg-emerald-900/40 text-emerald-800 dark:text-emerald-200 text-xs px-2 py-0.5">
                {item.category}
              </span>
            )}
            <h3 className="font-medium text-sm">{item.title}</h3>
          </div>
          {displaySummary && (
            <div
              className="text-xs text-zinc-500 line-clamp-2"
              dangerouslySetInnerHTML={{ __html: displaySummary }}
            />
          )}
          {tags.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-1.5">
              {tags.map((tag, i) => (
                <span key={i} className="rounded-full bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-xs px-1.5 py-0.5">
                  {tag}
                </span>
              ))}
            </div>
          )}
          {(displaySummary || displayContent) && (
            <span className="mt-1.5 inline-block text-xs text-indigo-600 dark:text-indigo-400">
              {t("golf.read_more")}
            </span>
          )}
        </button>

        {/* YouTube thumbnail (right of summary) */}
        {vid && (
          <button
            type="button"
            onClick={() => setPlaying(true)}
            className="relative shrink-0 w-36 aspect-video rounded-md overflow-hidden group bg-black"
            aria-label={`播放 ${item.title}`}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={`https://img.youtube.com/vi/${vid}/mqdefault.jpg`}
              alt={item.title}
              className="w-full h-full object-cover"
            />
            <div className="absolute inset-0 flex items-center justify-center bg-black/20 group-hover:bg-black/40 transition-colors">
              <div className="w-8 h-8 rounded-full bg-red-600 flex items-center justify-center shadow-md group-hover:scale-110 transition-transform">
                <svg viewBox="0 0 24 24" className="w-4 h-4 fill-white ml-0.5" aria-hidden="true">
                  <path d="M8 5v14l11-7z" />
                </svg>
              </div>
            </div>
          </button>
        )}

        {/* action buttons */}
        <div className="flex items-center gap-1 shrink-0">
          {item.source && (
            <a href={item.source} target="_blank" rel="noopener noreferrer"
              className="rounded px-2 py-1 text-xs text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800">
              ↗
            </a>
          )}
          {item.pdfUrl && (
            <a href={item.pdfUrl} target="_blank" rel="noopener noreferrer"
              className="rounded px-2 py-1 text-xs text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800">
              PDF
            </a>
          )}
          {canManage && (
            <>
              <button type="button" onClick={onEdit}
                className="rounded px-2 py-1 text-xs hover:bg-zinc-100 dark:hover:bg-zinc-800">
                {t("golf.edit")}
              </button>
              <button type="button" onClick={onDelete}
                className="rounded px-2 py-1 text-xs text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20">
                {t("library.delete")}
              </button>
            </>
          )}
        </div>
      </div>

      {/* reading pop-up — full summary + content */}
      {expanded && (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/50 px-4 py-8"
          onClick={onToggle}
          role="dialog"
          aria-modal="true"
        >
          <div
            onClick={e => e.stopPropagation()}
            className="flex w-full max-w-2xl flex-col max-h-[calc(100vh-4rem)] rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 shadow-xl"
          >
            <div className="flex shrink-0 items-start gap-3 border-b border-zinc-100 dark:border-zinc-800 px-6 pt-6 pb-4">
              <div className="min-w-0 flex-1">
                {item.category && (
                  <span className="mb-1 inline-block rounded-full bg-emerald-100 dark:bg-emerald-900/40 text-emerald-800 dark:text-emerald-200 text-xs px-2 py-0.5">
                    {item.category}
                  </span>
                )}
                <h2 className="text-lg font-semibold break-words">{item.title}</h2>
              </div>
              <button
                type="button"
                onClick={onToggle}
                aria-label="關閉"
                className="shrink-0 rounded px-2 py-1 text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200"
              >
                ✕
              </button>
            </div>

            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-4">
              {displaySummary && (
                <div
                  className="text-sm text-zinc-600 dark:text-zinc-300 [&_p]:my-1"
                  dangerouslySetInnerHTML={{ __html: displaySummary }}
                />
              )}
              {displayContent && (
                looksLikeHtml(displayContent) ? (
                  <div
                    className="leading-relaxed [&_p]:my-1 [&_div]:min-h-[1em]"
                    dangerouslySetInnerHTML={{ __html: displayContent }}
                  />
                ) : (
                  <div className="text-sm text-zinc-700 dark:text-zinc-300 whitespace-pre-wrap leading-relaxed">
                    {displayContent}
                  </div>
                )
              )}
              {!displaySummary && !displayContent && (
                <p className="text-sm text-zinc-500">{t("golf.empty")}</p>
              )}
              {tags.length > 0 && (
                <div className="flex flex-wrap gap-1 pt-1">
                  {tags.map((tag, i) => (
                    <span key={i} className="rounded-full bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-xs px-1.5 py-0.5">
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </div>

            {(item.source || item.pdfUrl) && (
              <div className="flex shrink-0 gap-3 border-t border-zinc-100 dark:border-zinc-800 px-6 py-3 text-sm">
                {item.source && (
                  <a href={item.source} target="_blank" rel="noopener noreferrer"
                    className="text-indigo-600 dark:text-indigo-400 hover:underline">
                    {t("golf.field_source")} ↗
                  </a>
                )}
                {item.pdfUrl && (
                  <a href={item.pdfUrl} target="_blank" rel="noopener noreferrer"
                    className="text-indigo-600 dark:text-indigo-400 hover:underline">
                    PDF ↗
                  </a>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* video lightbox */}
      {playing && embedUrl && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
          onClick={() => setPlaying(false)}
        >
          <div
            className="w-full max-w-3xl aspect-video relative"
            onClick={e => e.stopPropagation()}
          >
            <iframe
              src={`${embedUrl}?autoplay=1&rel=0`}
              title={item.title}
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
              allowFullScreen
              className="w-full h-full rounded-lg"
            />
            <button
              onClick={() => setPlaying(false)}
              className="absolute -top-9 right-0 text-white text-lg hover:opacity-75"
              aria-label="關閉"
            >
              ✕ 關閉
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

function FormField({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm mb-1">
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      {children}
    </div>
  );
}

function TagEditor({
  tags, tagInput, setTagInput, setForm,
}: {
  tags: string[];
  tagInput: string;
  setTagInput: (v: string) => void;
  setForm: React.Dispatch<React.SetStateAction<GolfResourceInput>>;
}) {
  const removeTag = (i: number) =>
    setForm(f => ({ ...f, tags: tags.filter((_, j) => j !== i).join(",") }));
  const commitTag = (raw: string) => {
    const tag = raw.trim();
    if (!tag || tags.includes(tag)) return;
    setForm(f => ({ ...f, tags: [...tags, tag].join(",") }));
  };
  return (
    <div className="flex flex-wrap items-center gap-1.5 rounded-md border border-zinc-300 dark:border-zinc-700 px-2 py-1.5 bg-white dark:bg-zinc-950">
      {tags.map((tag, i) => (
        <span key={i} className="flex items-center gap-0.5 rounded-full bg-blue-100 dark:bg-blue-900/30 px-2 py-0.5 text-xs">
          {tag}
          <button type="button" onClick={() => removeTag(i)} className="hover:text-red-600">×</button>
        </span>
      ))}
      <input
        value={tagInput}
        onChange={e => setTagInput(e.target.value)}
        onKeyDown={e => {
          if (e.key === "Enter" || e.key === ",") {
            e.preventDefault();
            commitTag(tagInput);
            setTagInput("");
          } else if (e.key === "Backspace" && !tagInput && tags.length) {
            removeTag(tags.length - 1);
          }
        }}
        onBlur={() => { if (tagInput.trim()) { commitTag(tagInput); setTagInput(""); } }}
        placeholder={tags.length ? "" : "輸入標籤後按 Enter 新增…"}
        className="min-w-[8rem] flex-1 bg-transparent text-sm outline-none"
      />
    </div>
  );
}
