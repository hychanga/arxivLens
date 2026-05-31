"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/store/auth";
import { useT } from "@/lib/i18n";

/**
 * "Sign in with Apple" button backed by Apple's JS SDK (AppleID.auth) in popup
 * mode. On click the SDK opens Apple's popup, returns an {@code id_token}, and we
 * hand that token to {@code POST /api/auth/oauth/apple} where the backend verifies
 * it against Apple's JWKS.
 *
 * <p>Activated only when both {@code NEXT_PUBLIC_APPLE_CLIENT_ID} (the Services
 * ID) and {@code NEXT_PUBLIC_APPLE_REDIRECT_URI} are set at build time on Vercel.
 * Without them this component renders {@code null} and the caller falls back to
 * the mock button — handy for offline dev / CI and the public demo.
 *
 * <p>The button is styled to match the adjacent OAuth buttons rather than using
 * Apple's auto-rendered element, per the project's frontend design guide.
 */
declare global {
  interface Window {
    AppleID?: {
      auth: {
        init: (config: {
          clientId: string;
          scope: string;
          redirectURI: string;
          usePopup: boolean;
          state?: string;
        }) => void;
        signIn: () => Promise<{
          authorization: { id_token: string; code: string; state?: string };
          user?: { name?: { firstName?: string; lastName?: string }; email?: string };
        }>;
      };
    };
  }
}

const APPLE_JS_SRC =
  "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js";

/**
 * Lazily injects the Apple JS SDK script tag exactly once. Resolves when
 * {@code window.AppleID.auth} is callable. Re-callers piggyback on the existing tag.
 */
function loadAppleIdSdk(): Promise<void> {
  if (typeof window === "undefined") return Promise.reject(new Error("SSR"));
  if (window.AppleID?.auth) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${APPLE_JS_SRC}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("Apple JS SDK failed to load")));
      return;
    }
    const s = document.createElement("script");
    s.src = APPLE_JS_SRC;
    s.async = true;
    s.defer = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("Apple JS SDK failed to load"));
    document.head.appendChild(s);
  });
}

interface Props {
  /** Renders an error message into the parent's UI when sign-in fails. */
  onError?: (message: string) => void;
}

export default function AppleSignInButton({ onError }: Props) {
  const clientId = process.env.NEXT_PUBLIC_APPLE_CLIENT_ID;
  const redirectURI = process.env.NEXT_PUBLIC_APPLE_REDIRECT_URI;
  const oauthLogin = useAuthStore((s) => s.oauthLogin);
  const router = useRouter();
  const t = useT();
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!clientId || !redirectURI) return;
    let cancelled = false;

    loadAppleIdSdk()
      .then(() => {
        if (cancelled || !window.AppleID) return;
        window.AppleID.auth.init({
          clientId,
          scope: "name email",
          redirectURI,
          usePopup: true,
        });
        setReady(true);
      })
      .catch((e) => onError?.(e instanceof Error ? e.message : "Could not load Apple sign-in"));

    return () => {
      cancelled = true;
    };
  }, [clientId, redirectURI, onError]);

  if (!clientId || !redirectURI) return null;

  async function handleClick() {
    if (!window.AppleID) return;
    setBusy(true);
    try {
      const data = await window.AppleID.auth.signIn();
      const idToken = data?.authorization?.id_token;
      if (!idToken) throw new Error("Apple did not return an identity token");
      await oauthLogin("apple", idToken);
      router.replace("/feed");
    } catch (e) {
      // The user dismissing Apple's popup isn't an error worth surfacing.
      if (e && typeof e === "object" && "error" in e && (e as { error: string }).error === "popup_closed_by_user") {
        return;
      }
      onError?.(e instanceof Error ? e.message : "Apple sign-in failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={!ready || busy}
      aria-label={t("login.continue_apple")}
      className="flex items-center justify-center gap-2 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 hover:bg-zinc-50 dark:hover:bg-zinc-900 px-3 py-2 text-sm disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
    >
      <svg
        aria-hidden
        width="16"
        height="16"
        viewBox="0 0 24 24"
        className="fill-zinc-900 dark:fill-zinc-100"
      >
        <path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
      </svg>
      <span>{busy ? t("login.connecting") : t("login.continue_apple")}</span>
    </button>
  );
}
