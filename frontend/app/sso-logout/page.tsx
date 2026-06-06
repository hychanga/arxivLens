"use client";

import { useEffect, useRef } from "react";
import { useAuthStore } from "@/store/auth";

/**
 * Single-logout hop for the Workspace Gateway.
 *
 * Reached as a top-level navigation (first-party context, so we can actually
 * clear our own localStorage — an iframe couldn't, due to storage partitioning).
 * We clear the arXivLens session, then continue to `next` — the next app in the
 * logout chain, or finally the Gateway login page.
 */
export default function SsoLogout() {
  const logout = useAuthStore((s) => s.logout);
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;

    try {
      logout();
    } catch {
      // ignore — best effort
    }

    const next = new URLSearchParams(window.location.search).get("next");
    const safe = next && (next.startsWith("https://") || next.startsWith("/")) ? next : "/login";
    window.location.replace(safe);
  }, [logout]);

  return (
    <main
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontFamily: "system-ui, sans-serif",
        color: "#64748b",
      }}
    >
      正在登出…
    </main>
  );
}
