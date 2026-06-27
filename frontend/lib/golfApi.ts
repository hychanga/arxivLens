import { apiFetch, BASE_URL, getStoredToken } from "@/lib/api";

export interface GolfResource {
  id: number;
  title: string;
  summary: string | null;
  content: string | null;
  category: string | null;
  tags: string | null;
  videoUrl: string | null;
  pdfUrl: string | null;
  source: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface GolfResourceInput {
  title: string;
  summary?: string | null;
  content?: string | null;
  category?: string | null;
  tags?: string | null;
  videoUrl?: string | null;
  pdfUrl?: string | null;
  source?: string | null;
}

export const GOLF_CATEGORIES = [
  "揮桿技巧",
  "規則",
  "裝備",
  "球場知識",
  "禮儀",
  "體能訓練",
  "心理建設",
  "比賽策略",
] as const;

const BASE = "/golf";

export const listGolf   = (q?: string) => apiFetch<GolfResource[]>(q ? `${BASE}?q=${encodeURIComponent(q)}` : BASE);
export const createGolf = (input: GolfResourceInput) => apiFetch<GolfResource>(BASE, { method: "POST", body: input });
export const updateGolf = (id: number, input: GolfResourceInput) => apiFetch<GolfResource>(`${BASE}/${id}`, { method: "PUT", body: input });
export const removeGolf = (id: number) => apiFetch<void>(`${BASE}/${id}`, { method: "DELETE" });

export async function uploadGolfPdf(file: File): Promise<string> {
  const fd = new FormData();
  fd.append("file", file);
  const token = getStoredToken();
  const res = await fetch(`${BASE_URL}/upload/pdf`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: fd,
  });
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = ((await res.json()) as { message: string }).message || msg; } catch { /* ignore */ }
    throw new Error(msg);
  }
  return ((await res.json()) as { url: string }).url;
}

export const suggestGolfTags = (title: string, summary: string, content: string) =>
  apiFetch<string[]>("/golf/suggest-tags", { method: "POST", body: { title, summary, content } });

export function splitTags(tags: string | null): string[] {
  return tags ? tags.split(",").map(t => t.trim()).filter(Boolean) : [];
}

export function youtubeEmbed(url: string | null): string | null {
  if (!url) return null;
  const m = url.match(/(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/)|youtu\.be\/)([\w-]{11})/);
  return m ? `https://www.youtube.com/embed/${m[1]}` : null;
}
