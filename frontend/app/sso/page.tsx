"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/store/auth";

/**
 * SSO landing — entry point from the Workspace Gateway.
 *
 * The Gateway sends the user here with a Google id_token in the URL *fragment*
 * (`/sso#id_token=<jwt>`). A fragment is never sent to the server, so the token
 * stays out of access logs and Referer headers. We read it client-side and feed
 * it to the existing `oauthLogin("google", idToken)` flow — the same path the
 * native Google button uses — which exchanges it for arXivLens's own session and
 * stores the app JWT. Then we drop the token from the URL and continue to /feed.
 *
 * This works because the Gateway and arXivLens share the same Google OAuth
 * client id, so the id_token's `aud` matches what arXivLens's backend verifies.
 */
export default function SsoLanding() {
  const router = useRouter();
  const oauthLogin = useAuthStore((s) => s.oauthLogin);
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;

    const hash = window.location.hash.startsWith("#")
      ? window.location.hash.slice(1)
      : window.location.hash;
    const idToken = new URLSearchParams(hash).get("id_token");

    // Scrub the token from the address bar immediately.
    history.replaceState(null, "", window.location.pathname);

    if (!idToken) {
      router.replace("/login");
      return;
    }

    oauthLogin("google", idToken)
      .then(() => router.replace("/feed"))
      .catch(() => setError("單一登入失敗，請改用一般登入。"));
  }, [oauthLogin, router]);

  return (
    <main
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "column",
        gap: 16,
        fontFamily: "system-ui, sans-serif",
      }}
    >
      {error ? (
        <>
          <p>{error}</p>
          <a href="/login" style={{ color: "#2563eb" }}>
            前往登入
          </a>
        </>
      ) : (
        <>
          <div
            style={{
              width: 32,
              height: 32,
              border: "3px solid #cbd5e1",
              borderTopColor: "#2563eb",
              borderRadius: "50%",
              animation: "sso-spin 0.8s linear infinite",
            }}
          />
          <p>正在登入…</p>
          <style>{`@keyframes sso-spin{to{transform:rotate(360deg)}}`}</style>
        </>
      )}
    </main>
  );
}
