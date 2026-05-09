import { BASE_URL, getStoredToken, HttpError } from "./api";

/**
 * Opens the cached PDF for {@code paperId} in a new browser tab.
 *
 * Why a blob URL instead of {@code window.open(href)}:
 * - The backend endpoint requires a JWT in the {@code Authorization} header.
 * - {@code <a href>} / {@code window.open} can't carry custom headers.
 * - So we fetch the PDF bytes ourselves and hand the browser a local
 *   {@code blob:} URL it can render in its native PDF viewer.
 *
 * The blob URL is revoked after a minute, which is plenty of time for the new
 * tab to finish loading; revoking earlier would race with the tab and leave a
 * blank page.
 */
export async function openCachedPdf(paperId: number): Promise<void> {
  const token = getStoredToken();
  const res = await fetch(`${BASE_URL}/downloads/${paperId}/file`, {
    method: "GET",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });

  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const err = await res.json();
      if (err?.message) message = String(err.message);
    } catch {
      // body wasn't JSON — keep the HTTP status as the message
    }
    throw new HttpError(res.status, message, null);
  }

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  // popup blockers can swallow this — we accept that and let the user re-click;
  // the alternative (anchor.click()) loses the noopener guarantee.
  window.open(url, "_blank", "noopener,noreferrer");
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
