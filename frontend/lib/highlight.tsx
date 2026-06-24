import type { ReactNode } from "react";

/**
 * Splits `text` on case-insensitive occurrences of `query` and wraps each
 * match in a <mark> element. Returns the original string when query is blank.
 *
 * Uses a capturing-group split so the matched fragments land at odd indices
 * and non-matched fragments land at even indices — no post-split regex needed.
 */
export function highlight(text: string, query: string): ReactNode {
  const q = query.trim();
  if (!q || !text) return text;

  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const regex = new RegExp(`(${escaped})`, "gi");
  const parts = text.split(regex);

  if (parts.length === 1) return text;

  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <mark
        key={i}
        className="bg-yellow-200 dark:bg-yellow-700 text-inherit rounded-sm"
      >
        {part}
      </mark>
    ) : (
      part
    )
  );
}
