"use client";

import { useEffect, useRef, useState } from "react";

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

const SIZES: { label: string; px: string; idx: string }[] = [
  { label: "小 12px",   px: "12px", idx: "2" },
  { label: "中 16px",   px: "16px", idx: "3" },
  { label: "大 20px",   px: "20px", idx: "5" },
  { label: "特大 28px", px: "28px", idx: "6" },
];

// Map execCommand fontSize index → CSS px. execCommand("fontSize") always
// emits <font size="N"> (presentational attribute), which Tailwind v4's
// author-level * { font-size: inherit } overrides. We convert every <font>
// to <span style="font-size: Npx"> (inline style, highest specificity) so
// the formatting survives in the card view.
const EXEC_SIZE_TO_PX: Record<string, string> = {
  "1": "10px", "2": "12px", "3": "16px", "4": "18px",
  "5": "20px", "6": "28px", "7": "32px",
};

const TEXT_COLORS = [
  "#000000", "#ffffff", "#6b7280",
  "#ef4444", "#f97316", "#eab308",
  "#16a34a", "#2563eb", "#7c3aed", "#ec4899",
];
const HIGHLIGHTS = ["#fef08a", "#bbf7d0", "#bfdbfe", "#fbcfe8", "#fed7aa", "#e9d5ff"];

// Converts any <font size|color|face> elements to <span style="..."> so the
// saved HTML always uses inline styles rather than presentational attributes.
// This runs synchronously before each innerHTML read in emitChange, so no
// <font> tags ever make it into form state or the database.
function convertFontElements(root: HTMLElement) {
  const fonts = Array.from(root.querySelectorAll("font"));
  for (const el of fonts) {
    const span = document.createElement("span");
    // Preserve any existing inline style already on the element
    const existing = (el as HTMLElement).style.cssText;
    if (existing) span.style.cssText = existing;
    const size  = el.getAttribute("size");
    const color = el.getAttribute("color");
    const face  = el.getAttribute("face");
    if (size)  span.style.fontSize   = EXEC_SIZE_TO_PX[size] ?? "16px";
    if (color) span.style.color      = color;
    if (face)  span.style.fontFamily = face;
    span.innerHTML = (el as HTMLElement).innerHTML;
    el.parentNode?.replaceChild(span, el);
  }
}

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
  const ref              = useRef<HTMLDivElement>(null);
  const savedRangeRef    = useRef<Range | null>(null);
  const textColorInputRef = useRef<HTMLInputElement>(null);
  const highlightInputRef = useRef<HTMLInputElement>(null);
  const processingRef    = useRef(false);
  const [openMenu, setOpenMenu] = useState<"font" | "size" | null>(null);
  const [empty, setEmpty] = useState(true);

  // Seed content on mount only — parent uses `key` prop to force a fresh
  // mount when editing a different item. We set innerHTML directly (no
  // DOMPurify) because this runs client-side only inside a contenteditable.
  useEffect(() => {
    if (ref.current) {
      ref.current.innerHTML = value;
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

  // Uses the correct size index so that text typed after setting the size
  // (without a selection) also receives the right <font size="idx"> which
  // convertFontElements then maps to the exact px value.
  function applyFontSize(px: string, idx: string) {
    ref.current?.focus();
    document.execCommand("fontSize", false, idx);
    emitChange();
  }

  // WCAG relative luminance (0 = black, 1 = white).
  function luminance(hex: string): number {
    const ch = (s: string) => {
      const v = parseInt(s, 16) / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * ch(hex.slice(1, 3)) + 0.7152 * ch(hex.slice(3, 5)) + 0.0722 * ch(hex.slice(5, 7));
  }

  // When the chosen text colour is very light (e.g. white, yellow) it becomes
  // invisible on a white background. Auto-pair it with a dark highlight so the
  // text is always readable. Users can remove the background with ⌫.
  function applyForeColor(color: string) {
    ref.current?.focus();
    document.execCommand("styleWithCSS", false, "true");
    document.execCommand("foreColor", false, color);
    if (luminance(color) > 0.4) {
      if (!document.execCommand("hiliteColor", false, "#1f2937")) {
        document.execCommand("backColor", false, "#1f2937");
      }
    }
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
    if (!ref.current || processingRef.current) return;
    // Convert <font> presentational attributes → <span style> inline styles
    // before reading innerHTML so the saved content always wins the CSS cascade.
    processingRef.current = true;
    convertFontElements(ref.current);
    processingRef.current = false;
    const hasText = Boolean(ref.current.textContent?.trim());
    setEmpty(!hasText);
    onChange(hasText ? ref.current.innerHTML : "");
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
  const swatchBtn  = "h-4 w-4 rounded-full border border-zinc-300 dark:border-zinc-600 shrink-0";
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
                  onClick={() => { applyFontSize(s.px, s.idx); setOpenMenu(null); }}>
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
              onMouseDown={keepFocus} onClick={() => applyForeColor(c)} />
          ))}
          <input ref={textColorInputRef} type="color" className="sr-only" defaultValue="#000000"
            onChange={e => {
              restoreSelection();
              applyForeColor(e.target.value);
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
          className="w-full bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus:outline-none"
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
