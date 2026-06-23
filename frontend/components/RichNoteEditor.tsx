"use client";

import { useEffect, useRef, useState } from "react";
import DOMPurify from "dompurify";
import { useT } from "@/lib/i18n";

/**
 * Notes are stored as a small subset of HTML produced by the toolbar below
 * (font family / size / colour / highlight + bold/italic/underline). Everything
 * is run through DOMPurify both on the way in (save) and on the way out
 * (render) so a note can never carry script or event handlers — only
 * presentational tags survive. Plain-text notes saved before this feature have
 * no tags and render unchanged (see {@link NoteView}).
 */
const ALLOWED_TAGS = ["b", "strong", "i", "em", "u", "span", "br", "div", "p", "font"];
const ALLOWED_ATTR = ["style", "color", "face", "size"];

export function sanitizeNote(html: string): string {
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR });
}

/** True when the string contains any HTML tag (i.e. a formatted note). */
function looksLikeHtml(s: string): boolean {
  return /<[a-z][\s\S]*>/i.test(s);
}

/**
 * Renders a saved note. New notes are sanitized HTML; legacy notes are plain
 * text and keep their line breaks via `whitespace-pre-line`.
 */
export function NoteView({ note, className = "" }: { note: string; className?: string }) {
  if (looksLikeHtml(note)) {
    return (
      <div
        className={`whitespace-pre-wrap break-words [&_p]:my-0 [&_div]:min-h-[1em] ${className}`}
        dangerouslySetInnerHTML={{ __html: sanitizeNote(note) }}
      />
    );
  }
  return <div className={`whitespace-pre-line break-words ${className}`}>{note}</div>;
}

const FONTS: { label: string; css: string }[] = [
  { label: "Sans", css: "ui-sans-serif, system-ui, sans-serif" },
  { label: "Serif", css: "Georgia, 'Times New Roman', serif" },
  { label: "Mono", css: "ui-monospace, 'Courier New', monospace" },
];

// execCommand fontSize uses the legacy 1–7 scale; these map to sensible steps.
const SIZES: { label: string; value: string }[] = [
  { label: "S", value: "2" },
  { label: "M", value: "3" },
  { label: "L", value: "5" },
  { label: "XL", value: "6" },
];

const TEXT_COLORS = ["#ef4444", "#f97316", "#16a34a", "#2563eb", "#7c3aed", "#0891b2", "#111827"];
const HIGHLIGHTS = ["#fef08a", "#bbf7d0", "#bfdbfe", "#fbcfe8", "#fed7aa", "#e9d5ff"];

export function RichNoteEditor({
  initialHtml,
  onSave,
  onCancel,
}: {
  initialHtml: string;
  onSave: (html: string) => void | Promise<void>;
  onCancel: () => void;
}) {
  const t = useT();
  const ref = useRef<HTMLDivElement>(null);
  const [empty, setEmpty] = useState(true);
  const [saving, setSaving] = useState(false);
  const [openMenu, setOpenMenu] = useState<"font" | "size" | null>(null);

  // Seed the editor once on mount. Uncontrolled by design — letting React
  // re-render the contentEditable's children would fight the browser's caret.
  useEffect(() => {
    if (ref.current) {
      ref.current.innerHTML = sanitizeNote(initialHtml);
      setEmpty(!ref.current.textContent?.trim());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function exec(command: string, value?: string) {
    ref.current?.focus();
    // Emit inline styles (e.g. <span style="color">) instead of legacy <font> tags.
    document.execCommand("styleWithCSS", false, "true");
    document.execCommand(command, false, value);
    setEmpty(!ref.current?.textContent?.trim());
  }

  function applyHighlight(color: string) {
    ref.current?.focus();
    document.execCommand("styleWithCSS", false, "true");
    // hiliteColor is the standard; some engines only honour backColor.
    if (!document.execCommand("hiliteColor", false, color)) {
      document.execCommand("backColor", false, color);
    }
  }

  async function save() {
    const el = ref.current;
    if (!el) return;
    setSaving(true);
    const hasText = Boolean(el.textContent?.trim());
    try {
      await onSave(hasText ? sanitizeNote(el.innerHTML) : "");
    } finally {
      setSaving(false);
    }
  }

  // Keep focus (and thus the selection) in the editor when a toolbar control is
  // pressed — without this the click would blur the editor and the command
  // would have nothing to act on.
  const keepFocus = (e: { preventDefault: () => void }) => e.preventDefault();

  const btn =
    "rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 text-sm leading-none hover:bg-zinc-100 dark:hover:bg-zinc-800";

  return (
    <div className="mt-2 space-y-2">
      <div className="flex flex-wrap items-center gap-1 rounded border border-zinc-200 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-900 p-1">
        {/* Font family */}
        <div className="relative">
          <button type="button" title={t("note.font")} className={btn}
            onMouseDown={keepFocus}
            onClick={() => setOpenMenu(openMenu === "font" ? null : "font")}>
            A▾
          </button>
          {openMenu === "font" && (
            <div className="absolute z-10 mt-1 flex flex-col rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow">
              {FONTS.map((f) => (
                <button key={f.label} type="button"
                  className="px-3 py-1 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
                  style={{ fontFamily: f.css }}
                  onMouseDown={keepFocus}
                  onClick={() => { exec("fontName", f.css); setOpenMenu(null); }}>
                  {f.label}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Font size */}
        <div className="relative">
          <button type="button" title={t("note.size")} className={btn}
            onMouseDown={keepFocus}
            onClick={() => setOpenMenu(openMenu === "size" ? null : "size")}>
            ⇕▾
          </button>
          {openMenu === "size" && (
            <div className="absolute z-10 mt-1 flex flex-col rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow">
              {SIZES.map((s) => (
                <button key={s.label} type="button"
                  className="px-3 py-1 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
                  onMouseDown={keepFocus}
                  onClick={() => { exec("fontSize", s.value); setOpenMenu(null); }}>
                  {s.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        <button type="button" title={t("note.bold")} className={`${btn} font-bold`}
          onMouseDown={keepFocus} onClick={() => exec("bold")}>B</button>
        <button type="button" title={t("note.italic")} className={`${btn} italic`}
          onMouseDown={keepFocus} onClick={() => exec("italic")}>I</button>
        <button type="button" title={t("note.underline")} className={`${btn} underline`}
          onMouseDown={keepFocus} onClick={() => exec("underline")}>U</button>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Text colour */}
        <span className="flex items-center gap-0.5" title={t("note.text_color")}>
          <span className="text-xs text-zinc-500">A</span>
          {TEXT_COLORS.map((c) => (
            <button key={c} type="button" aria-label={`${t("note.text_color")} ${c}`}
              className="h-4 w-4 rounded-full border border-zinc-300 dark:border-zinc-600"
              style={{ backgroundColor: c }}
              onMouseDown={keepFocus} onClick={() => exec("foreColor", c)} />
          ))}
        </span>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Highlight */}
        <span className="flex items-center gap-0.5" title={t("note.highlight")}>
          <span className="text-xs text-zinc-500">▒</span>
          {HIGHLIGHTS.map((c) => (
            <button key={c} type="button" aria-label={`${t("note.highlight")} ${c}`}
              className="h-4 w-4 rounded-sm border border-zinc-300 dark:border-zinc-600"
              style={{ backgroundColor: c }}
              onMouseDown={keepFocus} onClick={() => applyHighlight(c)} />
          ))}
        </span>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        <button type="button" title={t("note.clear_format")} className={btn}
          onMouseDown={keepFocus} onClick={() => exec("removeFormat")}>⌫</button>
      </div>

      <div className="relative">
        <div
          ref={ref}
          contentEditable
          suppressContentEditableWarning
          role="textbox"
          aria-multiline="true"
          onInput={(e) => setEmpty(!e.currentTarget.textContent?.trim())}
          className="min-h-[6rem] w-full rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 p-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/40 [&_*]:my-0"
        />
        {empty && (
          <span className="pointer-events-none absolute left-2 top-2 text-sm text-zinc-400">
            {t("note.placeholder")}
          </span>
        )}
      </div>

      <div className="flex gap-2 text-sm">
        <button onClick={save} disabled={saving}
          className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1 disabled:opacity-50">
          {saving ? t("common.loading") : t("common.save")}
        </button>
        <button onClick={onCancel} className="rounded bg-zinc-100 dark:bg-zinc-800 px-3 py-1">
          {t("common.cancel")}
        </button>
      </div>
    </div>
  );
}
