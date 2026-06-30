"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch, HttpError } from "@/lib/api";
import { parseAuthors } from "@/lib/format";
import { useT } from "@/lib/i18n";
import { useUiStore } from "@/store/ui";
import type { Paper } from "@/types";

interface Props {
  paper: Paper;
  onClose: () => void;
  onSaved: () => void;
}

export default function EditArticleModal({ paper, onClose, onSaved }: Props) {
  const t = useT();
  const flash = useUiStore((s) => s.flash);

  const [title, setTitle] = useState(paper.title);
  const [author, setAuthor] = useState(parseAuthors(paper).join(", "));
  const [url, setUrl] = useState(paper.url ?? "");
  const [publishedAt, setPublishedAt] = useState(
    paper.publishedAt ? paper.publishedAt.slice(0, 10) : ""
  );
  const [content, setContent] = useState(paper.introduction ?? paper.abstract ?? "");
  const [submitting, setSubmitting] = useState(false);

  const titleRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    titleRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  function errorMessage(err: unknown): string {
    if (err instanceof HttpError) {
      const code = err.payload?.code;
      if (code === "DUPLICATE_URL") return t("feed.add_article_dup_url");
      if (code === "DUPLICATE_TITLE") return t("feed.add_article_dup_title");
    }
    return err instanceof Error ? err.message : "Save failed";
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim() || !content.trim()) return;
    setSubmitting(true);
    try {
      await apiFetch<unknown>(`/papers/${paper.id}`, {
        method: "PATCH",
        body: {
          title: title.trim(),
          content: content.trim(),
          url: url.trim() || undefined,
          author: author.trim() || undefined,
          publishedAt: publishedAt
            ? new Date(publishedAt + "T00:00:00Z").toISOString()
            : undefined,
        },
      });
      flash(t("modal.edit_saved"), "success");
      onSaved();
      onClose();
    } catch (err) {
      flash(errorMessage(err), "error");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/50 px-4 py-8 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      aria-labelledby="edit-article-title"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 shadow-xl"
      >
        <h2 id="edit-article-title" className="text-lg font-semibold">
          {t("modal.edit_heading")}
        </h2>

        <form onSubmit={handleSubmit} className="mt-4 space-y-3" aria-busy={submitting}>
          <Field
            label={t("feed.add_article_title")}
            required
            value={title}
            onChange={setTitle}
            inputRef={titleRef}
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
              required
              value={publishedAt}
              onChange={setPublishedAt}
            />
          </div>
          <Field
            label={t("feed.add_article_url")}
            type="url"
            value={url}
            onChange={setUrl}
            placeholder="https://..."
          />
          <div>
            <label htmlFor="ea_content" className="block text-sm mb-1">
              {t("feed.add_article_content")} <span className="text-red-500">*</span>
            </label>
            <textarea
              id="ea_content"
              required
              rows={10}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md bg-zinc-100 dark:bg-zinc-800 px-3 py-1.5 text-sm hover:bg-zinc-200 dark:hover:bg-zinc-700"
            >
              {t("common.cancel")}
            </button>
            <button
              type="submit"
              disabled={submitting || !title.trim() || !content.trim() || !publishedAt}
              className="rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-3 py-1.5 text-sm disabled:opacity-50"
            >
              {submitting ? t("modal.edit_saving") : t("modal.edit_save")}
            </button>
          </div>
        </form>
      </div>
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
