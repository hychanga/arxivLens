import type { Paper } from "@/types";

export function parseAuthors(p: Paper): string[] {
  try {
    const v = JSON.parse(p.authorsJson);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}

export function fmtDate(isoOrMs: string | number | null | undefined): string {
  if (isoOrMs == null) return "—";
  const d = new Date(isoOrMs);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleDateString();
}

export function fmtSize(mb: number | null | undefined): string {
  if (mb == null) return "—";
  if (mb < 1) return `${(mb * 1024).toFixed(0)} KB`;
  return `${mb.toFixed(1)} MB`;
}

export function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, n));
}

/** Strip protocol + trailing slash so URLs render compactly (e.g. arxiv.org/abs/2501.00001). */
export function shortenUrl(url: string | null | undefined): string {
  if (!url) return "";
  return url.replace(/^https?:\/\//, "").replace(/\/$/, "");
}
