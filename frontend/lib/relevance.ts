import type { Paper } from "@/types";

export interface ScoredPaper extends Paper {
  score: number;
}

/**
 * Per-keyword priority weight: #1 = 10, #2 = 9 … floored at 1.
 * Match contribution: title × 3 + authors × 2 + abstract × 1.
 * Final score normalized to 0–10 (heuristic divisor 6).
 */
export function score(paper: Paper, keywords: string[]): number {
  if (!keywords || keywords.length === 0) return 0;
  const title = paper.title.toLowerCase();
  const authors = paper.authorsJson.toLowerCase();
  const abstract = paper.abstract.toLowerCase();

  let total = 0;
  keywords.forEach((raw, idx) => {
    const k = raw.trim().toLowerCase();
    if (!k) return;
    const weight = Math.max(1, 10 - idx);
    let m = 0;
    if (title.includes(k))    m += 3;
    if (authors.includes(k))  m += 2;
    if (abstract.includes(k)) m += 1;
    total += m * weight;
  });

  return Math.min(10, Math.max(0, Math.round(total / 6)));
}

export function attachScores(papers: Paper[], keywords: string[]): ScoredPaper[] {
  return papers.map((p) => ({ ...p, score: score(p, keywords) }));
}

export function priorityClass(idx: number, total: number): string {
  if (total === 0) return "bg-zinc-200 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200";
  const third = Math.max(1, Math.ceil(total / 3));
  if (idx < third) return "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200";
  if (idx < third * 2) return "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200";
  return "bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-200";
}

export function scoreBadgeClass(score: number): string {
  if (score >= 8) return "bg-emerald-500 text-white";
  if (score >= 5) return "bg-sky-500 text-white";
  if (score >= 3) return "bg-orange-500 text-white";
  return "bg-zinc-300 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200";
}
