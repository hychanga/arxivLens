"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch, HttpError } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useUiStore } from "@/store/ui";

/**
 * Button + inline modal for adding articles to a manual source.
 *
 * <p>Two tabs:
 * <ul>
 *   <li><b>From URL</b> — server fetches the URL and extracts title +
 *       main body. Good for public articles; paywalled pages will only
 *       expose their teaser.</li>
 *   <li><b>Paste content</b> — user pastes the article text directly.
 *       The only legal path for subscriber-only content (HBR, paid blogs)
 *       since we can't replay the user's session from a backend HTTP call.</li>
 * </ul>
 *
 * <p>Self-contained — owns the open/closed state and the form fields.
 * Notifies the parent via {@link Props.onAdded} so the feed can refetch.
 */
interface Props {
  sourceId: number;
  /**
   * Source code of the current feed (e.g. {@code "hbr"}, {@code "businessweekly"}).
   * Drives the inline URL warning — if the typed URL doesn't contain a
   * substring matching the source's domain, we flag it before submit.
   */
  sourceCode?: string;
  onAdded?: () => void;
}

/**
 * Substring we expect to find inside a URL pasted in URL-import mode for the
 * given source. Used by the inline warning that nudges the user when they
 * paste an obvious mismatch (HBR URL while on the Business Weekly tab, etc.).
 */
function expectedUrlSubstring(sourceCode: string | undefined): string | null {
  switch (sourceCode) {
    case "hbr": return "hbr";
    case "businessweekly": return "businessweekly";
    case "mckinsey": return "mckinsey";
    case "golf": return null;
    default: return null;
  }
}

interface ManualResponse {
  id: number;
  externalId: string;
  title: string;
  authors: string[];
  publishedAt: string;
}

type Mode = "url" | "paste";

export default function AddArticleButton({ sourceId, sourceCode, onAdded }: Props) {
  const t = useT();
  const flash = useUiStore((s) => s.flash);

  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<Mode>("url");
  const [submitting, setSubmitting] = useState(false);

  // URL mode state.
  const [importUrl, setImportUrl] = useState("");

  // Paste mode state.
  const [title, setTitle] = useState("");
  const [author, setAuthor] = useState("");
  const [pasteUrl, setPasteUrl] = useState("");
  const [publishedAt, setPublishedAt] = useState(""); // yyyy-mm-dd, empty = "now" on server
  const [content, setContent] = useState("");

  const urlInputRef = useRef<HTMLInputElement>(null);
  const titleInputRef = useRef<HTMLInputElement>(null);

  // Focus first field on tab switch / open; close on Escape.
  useEffect(() => {
    if (!open) return;
    if (mode === "url") urlInputRef.current?.focus();
    else titleInputRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, mode]);

  // Prefer a locale-aware message for known domain error codes (duplicate
  // article); fall back to the backend's English message for anything else.
  function errorMessage(err: unknown, fallback: string): string {
    if (err instanceof HttpError) {
      const code = err.payload?.code;
      if (code === "DUPLICATE_URL") return t("feed.add_article_dup_url");
      if (code === "DUPLICATE_TITLE") return t("feed.add_article_dup_title");
    }
    return err instanceof Error ? err.message : fallback;
  }

  function resetForm() {
    setImportUrl("");
    setTitle("");
    setAuthor("");
    setPasteUrl("");
    setPublishedAt("");
    setContent("");
  }

  async function submitUrl(e: React.FormEvent) {
    e.preventDefault();
    if (!importUrl.trim()) return;
    setSubmitting(true);
    try {
      await apiFetch<ManualResponse>("/papers/import-url", {
        method: "POST",
        body: { sourceId, url: importUrl.trim() },
      });
      flash(t("feed.add_article_added"), "success");
      resetForm();
      setOpen(false);
      onAdded?.();
    } catch (err) {
      flash(errorMessage(err, "Import failed"), "error");
    } finally {
      setSubmitting(false);
    }
  }

  async function submitPaste(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim() || !content.trim()) return;
    setSubmitting(true);
    try {
      await apiFetch<ManualResponse>("/papers/manual", {
        method: "POST",
        body: {
          sourceId,
          title: title.trim(),
          content: content.trim(),
          url: pasteUrl.trim() || undefined,
          author: author.trim() || undefined,
          publishedAt: publishedAt ? new Date(publishedAt + "T00:00:00Z").toISOString() : undefined,
        },
      });
      flash(t("feed.add_article_added"), "success");
      resetForm();
      setOpen(false);
      onAdded?.();
    } catch (err) {
      flash(errorMessage(err, "Add failed"), "error");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        {t("feed.add_article")}
      </button>

      {open && (
        <div
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/50 px-4 py-8 overflow-y-auto"
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-article-title"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-2xl rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 shadow-xl"
          >
            <h2 id="add-article-title" className="text-lg font-semibold">
              {t("feed.add_article_heading")}
            </h2>

            {/* Tabs */}
            <div role="tablist" aria-label="Add article mode" className="mt-4 flex gap-2 text-sm">
              <TabButton id="url" current={mode} setMode={setMode} label={t("feed.add_tab_url")} />
              <TabButton id="paste" current={mode} setMode={setMode} label={t("feed.add_tab_paste")} />
            </div>

            {mode === "url" ? (
              <form onSubmit={submitUrl} className="mt-4 space-y-3" aria-busy={submitting}>
                <p className="text-xs text-zinc-500">{t("feed.add_url_hint")}</p>
                <div>
                  <label htmlFor="aa_url" className="block text-sm mb-1">
                    {t("feed.add_url_field")} <span className="text-red-500">*</span>
                  </label>
                  <input
                    id="aa_url"
                    ref={urlInputRef}
                    type="url"
                    required
                    value={importUrl}
                    onChange={(e) => setImportUrl(e.target.value)}
                    placeholder="https://www.hbrtaiwan.com/article/..."
                    className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                  />
                  {(() => {
                    const expected = expectedUrlSubstring(sourceCode);
                    if (!expected) return null;
                    if (!importUrl.trim()) return null;
                    if (importUrl.toLowerCase().includes(expected)) return null;
                    return (
                      <p
                        role="alert"
                        className="mt-1 text-xs text-amber-700 dark:text-amber-300 bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 rounded px-2 py-1"
                      >
                        ⚠ {t("feed.add_url_warn_wrong_source")}
                      </p>
                    );
                  })()}
                </div>
                <DialogFooter
                  onCancel={() => setOpen(false)}
                  cancelLabel={t("common.cancel")}
                  submitLabel={
                    submitting ? t("feed.add_url_fetching") : t("feed.add_url_save")
                  }
                  submitting={submitting}
                  submitDisabled={!importUrl.trim()}
                />
              </form>
            ) : (
              <form onSubmit={submitPaste} className="mt-4 space-y-3" aria-busy={submitting}>
                <p className="text-xs text-zinc-500">{t("feed.add_article_hint")}</p>
                <Field
                  label={t("feed.add_article_title")}
                  required
                  value={title}
                  onChange={setTitle}
                  inputRef={titleInputRef}
                />
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  <Field
                    label={t("feed.add_article_author")}
                    value={author}
                    onChange={setAuthor}
                    placeholder={t("feed.add_article_author_placeholder")}
                  />
                  <Field
                    label={t("feed.add_article_date")}
                    type="date"
                    value={publishedAt}
                    onChange={setPublishedAt}
                  />
                </div>
                <Field
                  label={t("feed.add_article_url")}
                  type="url"
                  value={pasteUrl}
                  onChange={setPasteUrl}
                  placeholder="https://hbr.org/..."
                />
                <div>
                  <label htmlFor="aa_content" className="block text-sm mb-1">
                    {t("feed.add_article_content")} <span className="text-red-500">*</span>
                  </label>
                  <textarea
                    id="aa_content"
                    required
                    rows={10}
                    value={content}
                    onChange={(e) => setContent(e.target.value)}
                    placeholder={t("feed.add_article_content_placeholder")}
                    className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                  />
                </div>
                <DialogFooter
                  onCancel={() => setOpen(false)}
                  cancelLabel={t("common.cancel")}
                  submitLabel={
                    submitting ? t("feed.add_article_saving") : t("feed.add_article_save")
                  }
                  submitting={submitting}
                  submitDisabled={!title.trim() || !content.trim()}
                />
              </form>
            )}
          </div>
        </div>
      )}
    </>
  );
}

function TabButton({
  id,
  current,
  setMode,
  label,
}: {
  id: Mode;
  current: Mode;
  setMode: (m: Mode) => void;
  label: string;
}) {
  const active = id === current;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={() => setMode(id)}
      className={`flex-1 rounded-md px-3 py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
        active
          ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
          : "bg-zinc-100 dark:bg-zinc-800 text-zinc-600 dark:text-zinc-300 hover:bg-zinc-200 dark:hover:bg-zinc-700"
      }`}
    >
      {label}
    </button>
  );
}

function DialogFooter({
  onCancel,
  cancelLabel,
  submitLabel,
  submitting,
  submitDisabled,
}: {
  onCancel: () => void;
  cancelLabel: string;
  submitLabel: string;
  submitting: boolean;
  submitDisabled: boolean;
}) {
  return (
    <div className="flex justify-end gap-2 pt-2">
      <button
        type="button"
        onClick={onCancel}
        className="rounded-md bg-zinc-100 dark:bg-zinc-800 px-3 py-1.5 text-sm hover:bg-zinc-200 dark:hover:bg-zinc-700"
      >
        {cancelLabel}
      </button>
      <button
        type="submit"
        disabled={submitting || submitDisabled}
        className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm disabled:opacity-50"
      >
        {submitLabel}
      </button>
    </div>
  );
}

function Field({
  label,
  type = "text",
  required,
  value,
  onChange,
  placeholder,
  inputRef,
}: {
  label: string;
  type?: string;
  required?: boolean;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  inputRef?: React.RefObject<HTMLInputElement | null>;
}) {
  return (
    <div>
      <label className="block text-sm mb-1">
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      <input
        ref={inputRef}
        type={type}
        required={required}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      />
    </div>
  );
}
