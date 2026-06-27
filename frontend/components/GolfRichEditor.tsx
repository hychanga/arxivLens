"use client";

import { useEffect, useRef, useState } from "react";
import DOMPurify from "dompurify";

const ALLOWED_TAGS = ["b", "strong", "i", "em", "u", "span", "br", "div", "p", "font"];
const ALLOWED_ATTR = ["style", "color", "face", "size"];

export function sanitizeGolf(html: string): string {
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR });
}

export function looksLikeHtml(s: string | null | undefined): boolean {
  return s ? /<[a-z][\s\S]*>/i.test(s) : false;
}

const FONTS: { label: string; css: string }[] = [
  { label: "Sans", css: "ui-sans-serif, system-ui, sans-serif" },
  { label: "Serif", css: "Georgia, 'Times New Roman', serif" },
  { label: "Mono", css: "ui-monospace, 'Courier New', monospace" },
  { label: "微軟正黑體", css: "'Microsoft JhengHei', sans-serif" },
  { label: "標楷體", css: "DFKai-SB, serif" },
];

// Pixel-based font sizes. execCommand("fontSize") always emits <font size="N">
// (a presentational attribute) even with styleWithCSS=true, and that loses to
// Tailwind's class selectors. We use size="7" as a placeholder then swap those
// elements for <span style="font-size: Xpx"> which has inline-style specificity.
const SIZES: { label: string; px: string }[] = [
  { label: "小 12px", px: "12px" },
  { label: "中 16px", px: "16px" },
  { label: "大 20px", px: "20px" },
  { label: "特大 28px", px: "28px" },
];

const TEXT_COLORS = ["#111827", "#ef4444", "#f97316", "#16a34a", "#2563eb", "#7c3aed", "#0891b2"];
const HIGHLIGHTS = ["#fef08a", "#bbf7d0", "#bfdbfe", "#fbcfe8", "#fed7aa", "#e9d5ff"];

export default function GolfRichEditor({
  value,
  onChange,
  minHeight = "8rem",
  placeholder,
}: {
  value: string;
  onChange: (html: string) => void;
  minHeight?: string;
  placeholder?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const savedRangeRef = useRef<Range | null>(null);
  const textColorInputRef = useRef<HTMLInputElement>(null);
  const highlightInputRef = useRef<HTMLInputElement>(null);
  const [openMenu, setOpenMenu] = useState<"font" | "size" | null>(null);
  const [empty, setEmpty] = useState(true);

  // Seed content on mount only. Parent uses `key` prop to re-mount when
  // editing a different item — that's cleaner than trying to sync innerHTML.
  useEffect(() => {
    if (ref.current) {
      ref.current.innerHTML = sanitizeGolf(value);
      setEmpty(!ref.current.textContent?.trim());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function exec(command: string, val?: string) {
    ref.current?.focus();
    document.execCommand("styleWithCSS", false, "true");
    document.execCommand(command, false, val);
    emitChange();
  }

  // execCommand("fontSize") always emits <font size="N"> (presentational, low specificity).
  // We use size="7" as a unique marker, then replace those elements with
  // <span style="font-size: Xpx"> so inline-style specificity wins over Tailwind classes.
  function applyFontSize(px: string) {
    ref.current?.focus();
    document.execCommand("fontSize", false, "7");
    ref.current?.querySelectorAll("font[size='7']").forEach(el => {
      const span = document.createElement("span");
      span.style.fontSize = px;
      span.innerHTML = (el as HTMLElement).innerHTML;
      el.parentNode?.replaceChild(span, el);
    });
    emitChange();
  }

  function applyHighlight(color: string) {
    ref.current?.focus();
    document.execCommand("styleWithCSS", false, "true");
    if (!document.execCommand("hiliteColor", false, color)) {
      document.execCommand("backColor", false, color);
    }
    emitChange();
  }

  function emitChange() {
    if (!ref.current) return;
    const hasText = Boolean(ref.current.textContent?.trim());
    const html = hasText ? sanitizeGolf(ref.current.innerHTML) : "";
    setEmpty(!hasText);
    onChange(html);
  }

  function saveSelection() {
    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0) {
      savedRangeRef.current = sel.getRangeAt(0).cloneRange();
    }
  }

  function restoreSelection() {
    ref.current?.focus();
    const sel = window.getSelection();
    if (sel && savedRangeRef.current) {
      sel.removeAllRanges();
      sel.addRange(savedRangeRef.current);
    }
  }

  const keepFocus = (e: React.MouseEvent) => e.preventDefault();

  const btn =
    "rounded border border-zinc-300 dark:border-zinc-700 px-2 py-1 text-sm leading-none " +
    "hover:bg-zinc-100 dark:hover:bg-zinc-800 text-zinc-700 dark:text-zinc-300";
  const swatchBtn = "h-4 w-4 rounded-full border border-zinc-300 dark:border-zinc-600 shrink-0";
  const swatchRect = "h-4 w-4 rounded-sm border border-zinc-300 dark:border-zinc-600 shrink-0";
  const pickerBase =
    "h-4 w-4 border border-dashed border-zinc-400 dark:border-zinc-500 text-zinc-400 " +
    "text-[9px] leading-none flex items-center justify-center " +
    "hover:border-zinc-600 dark:hover:border-zinc-300 hover:text-zinc-600 dark:hover:text-zinc-300";

  return (
    <div className="rounded-md overflow-hidden border border-zinc-300 dark:border-zinc-700">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-1 bg-zinc-50 dark:bg-zinc-900 px-2 py-1.5 border-b border-zinc-200 dark:border-zinc-700">

        {/* Font family */}
        <div className="relative">
          <button type="button" className={btn} title="字型"
            onMouseDown={keepFocus}
            onClick={() => setOpenMenu(openMenu === "font" ? null : "font")}>
            A▾
          </button>
          {openMenu === "font" && (
            <div className="absolute z-20 mt-1 flex flex-col rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow-lg min-w-max">
              {FONTS.map(f => (
                <button key={f.label} type="button"
                  className="px-3 py-1.5 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
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
          <button type="button" className={btn} title="大小"
            onMouseDown={keepFocus}
            onClick={() => setOpenMenu(openMenu === "size" ? null : "size")}>
            ⇕▾
          </button>
          {openMenu === "size" && (
            <div className="absolute z-20 mt-1 flex flex-col rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-900 shadow-lg min-w-max">
              {SIZES.map(s => (
                <button key={s.label} type="button"
                  className="px-3 py-1.5 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
                  style={{ fontSize: s.px }}
                  onMouseDown={keepFocus}
                  onClick={() => { applyFontSize(s.px); setOpenMenu(null); }}>
                  {s.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Bold / Italic / Underline */}
        <button type="button" className={`${btn} font-bold`} title="粗體 (B)"
          onMouseDown={keepFocus} onClick={() => exec("bold")}>B</button>
        <button type="button" className={`${btn} italic`} title="斜體 (I)"
          onMouseDown={keepFocus} onClick={() => exec("italic")}>I</button>
        <button type="button" className={`${btn} underline`} title="底線 (U)"
          onMouseDown={keepFocus} onClick={() => exec("underline")}>U</button>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Text color — preset swatches + native picker */}
        <span className="flex items-center gap-0.5" title="文字顏色">
          <span className="text-xs text-zinc-500 mr-0.5">A</span>
          {TEXT_COLORS.map(c => (
            <button key={c} type="button" aria-label={`文字顏色 ${c}`}
              className={swatchBtn} style={{ backgroundColor: c }}
              onMouseDown={keepFocus} onClick={() => exec("foreColor", c)} />
          ))}
          <input ref={textColorInputRef} type="color" className="sr-only" defaultValue="#000000"
            onChange={e => {
              restoreSelection();
              document.execCommand("styleWithCSS", false, "true");
              document.execCommand("foreColor", false, e.target.value);
              emitChange();
            }} />
          <button type="button" title="自訂顏色" className={`${pickerBase} rounded-full`}
            onMouseDown={saveSelection} onClick={() => textColorInputRef.current?.click()}>+</button>
        </span>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Highlight — preset swatches + native picker */}
        <span className="flex items-center gap-0.5" title="網底顏色">
          <span className="text-xs text-zinc-500 mr-0.5">▒</span>
          {HIGHLIGHTS.map(c => (
            <button key={c} type="button" aria-label={`網底 ${c}`}
              className={swatchRect} style={{ backgroundColor: c }}
              onMouseDown={keepFocus} onClick={() => applyHighlight(c)} />
          ))}
          <input ref={highlightInputRef} type="color" className="sr-only" defaultValue="#ffff00"
            onChange={e => {
              restoreSelection();
              document.execCommand("styleWithCSS", false, "true");
              if (!document.execCommand("hiliteColor", false, e.target.value)) {
                document.execCommand("backColor", false, e.target.value);
              }
              emitChange();
            }} />
          <button type="button" title="自訂網底" className={`${pickerBase} rounded-sm`}
            onMouseDown={saveSelection} onClick={() => highlightInputRef.current?.click()}>+</button>
        </span>

        <span className="mx-0.5 h-5 w-px bg-zinc-300 dark:bg-zinc-700" />

        {/* Clear formatting */}
        <button type="button" className={btn} title="清除格式"
          onMouseDown={keepFocus} onClick={() => exec("removeFormat")}>⌫</button>
      </div>

      {/* Editable area */}
      <div className="relative">
        <div
          ref={ref}
          contentEditable
          suppressContentEditableWarning
          role="textbox"
          aria-multiline="true"
          onInput={emitChange}
          style={{ minHeight }}
          className="w-full bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:outline-none [&_*]:my-0"
        />
        {empty && placeholder && (
          <span className="pointer-events-none absolute left-3 top-2 text-sm text-zinc-400">
            {placeholder}
          </span>
        )}
      </div>
    </div>
  );
}
