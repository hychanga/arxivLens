"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useUiStore } from "@/store/ui";

/**
 * Button + inline modal for adding a manually-pasted article to a source.
 *
 * <p>Designed for sources that can't be auto-synced (HBR after we dropped the RSS
 * scraper). The user reads the article in their own subscription, pastes the body
 * here, and the resulting Paper row flows through the same favorite / translate /
 * AI-summary pipeline as machine-fetched papers.
 *
 * <p>Self-contained — owns the open/closed state and the form fields. Tells the
 * parent only "an article was added" via {@link Props.onAdded} so the feed can
 * refetch.
 */
interface Props {
  sourceId: number;
  onAdded?: () => void;
}

interface ManualResponse {
  id: number;
  externalId: string;
  title: string;
  authors: string[];
  publishedAt: string;
}

export default function AddArticleButton({ sourceId, onAdded }: Props) {
  const t = useT();
  const flash = useUiStore((s) => s.flash);

  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [title, setTitle] = useState("");
  const [author, setAuthor] = useState("");
  const [url, setUrl] = useState("");
  const [publishedAt, setPublishedAt] = useState(""); // yyyy-mm-dd, empty = "now" on server
  const [content, setContent] = useState("");
  const firstFieldRef = useRef<HTMLInputElement>(null);

  // Focus the first field when the modal opens; close on Escape.
  useEffect(() => {
    if (!open) return;
    firstFieldRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  function resetForm() {
    setTitle("");
    setAuthor("");
    setUrl("");
    setPublishedAt("");
    setContent("");
  }

  async function onSubmit(e: React.FormEvent) {
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
          url: url.trim() || undefined,
          author: author.trim() || undefined,
          // Local date input → midnight UTC ISO string. Server falls back to "now" when omitted.
          publishedAt: publishedAt ? new Date(publishedAt + "T00:00:00Z").toISOString() : undefined,
        },
      });
      flash(t("feed.add_article_added"), "success");
      resetForm();
      setOpen(false);
      onAdded?.();
    } catch (err) {
      flash(err instanceof Error ? err.message : "Add failed", "error");
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
          // Click on the backdrop dismisses; clicks inside the dialog stop propagation.
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
            <p className="mt-1 text-xs text-zinc-500">{t("feed.add_article_hint")}</p>

            <form onSubmit={onSubmit} className="mt-4 space-y-3" aria-busy={submitting}>
              <Field
                label={t("feed.add_article_title")}
                required
                value={title}
                onChange={setTitle}
                inputRef={firstFieldRef}
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
                value={url}
                onChange={setUrl}
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

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  className="rounded-md bg-zinc-100 dark:bg-zinc-800 px-3 py-1.5 text-sm hover:bg-zinc-200 dark:hover:bg-zinc-700"
                >
                  {t("common.cancel")}
                </button>
                <button
                  type="submit"
                  disabled={submitting || !title.trim() || !content.trim()}
                  className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm disabled:opacity-50"
                >
                  {submitting ? t("feed.add_article_saving") : t("feed.add_article_save")}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
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
