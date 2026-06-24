"use client";

import { BASE_URL } from "@/lib/api";
import { highlight } from "@/lib/highlight";

/**
 * Markdown image marker the backend's HtmlExtractor emits — `![alt](url)`.
 * Anchors on http(s) so accidental matches inside body text (e.g. someone
 * literally wrote "![", which is rare) don't get treated as images unless the
 * parenthesised value actually looks like a URL.
 */
const IMG_MARKER = /!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g;

/**
 * Rewrites a publisher image URL to go through our backend's image proxy.
 * Two reasons we proxy:
 *
 * <ul>
 *   <li>Some CDNs (商業週刊's ibw.bwnet.com.tw, for example) enforce
 *       Referer-based hot-link protection, so a direct {@code <img src>} from
 *       our origin would 403.</li>
 *   <li>The proxy also caches the bytes in TiDB, so the saved article still
 *       has its images even if the publisher rotates URLs later.</li>
 * </ul>
 */
function proxied(url: string): string {
  return `${BASE_URL}/images/proxy?url=${encodeURIComponent(url)}`;
}

/**
 * Renders an article body that may contain inline markdown image markers.
 * Splits on the marker pattern and emits a real {@code <img>} for each one,
 * with text chunks rendered as paragraphs that preserve newlines.
 */
export default function BodyContent({ body, query = "" }: { body: string; query?: string }) {
  if (!body) return null;
  const parts: Array<{ kind: "text" | "img"; value: string; alt?: string }> = [];
  let last = 0;
  for (const m of body.matchAll(IMG_MARKER)) {
    if (m.index! > last) {
      parts.push({ kind: "text", value: body.slice(last, m.index!) });
    }
    parts.push({ kind: "img", value: m[2], alt: m[1] });
    last = m.index! + m[0].length;
  }
  if (last < body.length) {
    parts.push({ kind: "text", value: body.slice(last) });
  }
  return (
    <div className="leading-relaxed">
      {parts.map((p, i) =>
        p.kind === "img" ? (
          <img
            key={i}
            src={proxied(p.value)}
            alt={p.alt ?? ""}
            loading="lazy"
            className="my-3 max-w-full rounded border border-zinc-200 dark:border-zinc-800"
          />
        ) : p.value.trim() ? (
          <p key={i} className="whitespace-pre-line">{highlight(p.value, query)}</p>
        ) : null
      )}
    </div>
  );
}
