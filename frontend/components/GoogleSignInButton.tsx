"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/store/auth";
import { useLocaleStore, type Locale } from "@/store/locale";

/**
 * Renders Google's official "Sign in with Google" button via Google Identity
 * Services (GIS). On click, GIS opens its popup, returns an ID token, and we
 * hand that token to {@code POST /api/auth/oauth/google} where the backend
 * verifies it against Google's tokeninfo endpoint.
 *
 * <p>Activated only when {@code NEXT_PUBLIC_GOOGLE_CLIENT_ID} is set at build
 * time on Vercel. Without it, this component renders {@code null} and the
 * caller falls back to the mock button — handy for offline dev / CI.
 */
declare global {
  interface Window {
    google?: {
      accounts?: {
        id?: {
          initialize: (config: {
            client_id: string;
            callback: (resp: { credential: string }) => void;
            ux_mode?: "popup" | "redirect";
            auto_select?: boolean;
          }) => void;
          renderButton: (
            parent: HTMLElement,
            options: {
              theme?: "outline" | "filled_blue" | "filled_black";
              size?: "small" | "medium" | "large";
              text?: "signin_with" | "signup_with" | "continue_with" | "signin";
              shape?: "rectangular" | "pill" | "circle" | "square";
              logo_alignment?: "left" | "center";
              width?: number;
              locale?: string;
            }
          ) => void;
        };
      };
    };
  }
}

const GSI_SRC = "https://accounts.google.com/gsi/client";

/**
 * Lazily injects the GSI script tag exactly once. Resolves when {@code window.google.accounts.id}
 * is callable. Re-callers within the same SPA session piggyback on the existing script.
 */
function loadGoogleIdentityServices(): Promise<void> {
  if (typeof window === "undefined") return Promise.reject(new Error("SSR"));
  if (window.google?.accounts?.id) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GSI_SRC}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("GIS script failed to load")));
      return;
    }
    const s = document.createElement("script");
    s.src = GSI_SRC;
    s.async = true;
    s.defer = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("GIS script failed to load"));
    document.head.appendChild(s);
  });
}

interface Props {
  /** Renders an error message into the parent's UI when sign-in fails. */
  onError?: (message: string) => void;
}

/**
 * Map our app's locale codes to whatever GIS expects. GIS uses BCP-47 codes
 * and accepts {@code zh_TW}/{@code zh-TW} interchangeably; we send the underscore
 * form because Google's own examples use it.
 */
function gisLocale(locale: Locale): string {
  switch (locale) {
    case "zh-TW": return "zh_TW";
    case "zh-CN": return "zh_CN";
    case "ja": return "ja";
    case "de": return "de";
    case "en":
    default: return "en";
  }
}

export default function GoogleSignInButton({ onError }: Props) {
  const clientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  const oauthLogin = useAuthStore((s) => s.oauthLogin);
  const locale = useLocaleStore((s) => s.locale);
  const router = useRouter();
  const buttonRef = useRef<HTMLDivElement>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!clientId) return;
    let cancelled = false;

    loadGoogleIdentityServices()
      .then(() => {
        if (cancelled || !buttonRef.current || !window.google?.accounts?.id) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          ux_mode: "popup",
          callback: async (resp) => {
            setBusy(true);
            try {
              await oauthLogin("google", resp.credential);
              router.replace("/feed");
            } catch (e) {
              onError?.(e instanceof Error ? e.message : "Google sign-in failed");
            } finally {
              setBusy(false);
            }
          },
        });
        // Clear the container so re-renders (e.g. on locale change) don't
        // stack a second button next to the old one.
        buttonRef.current.innerHTML = "";
        // Match the visual rhythm of the adjacent custom button. Width is a
        // pixel value — we use the container's measured width if available so
        // the button stretches to fill its grid cell.
        const width = buttonRef.current.clientWidth || 240;
        window.google.accounts.id.renderButton(buttonRef.current, {
          theme: "outline",
          size: "large",
          text: "signin_with",
          shape: "rectangular",
          logo_alignment: "center",
          width,
          locale: gisLocale(locale),
        });
      })
      .catch((e) => {
        onError?.(e instanceof Error ? e.message : "Could not load Google sign-in");
      });

    return () => {
      cancelled = true;
    };
    // Re-render on locale change so the button label localizes alongside the
    // rest of the UI rather than sticking to whatever the browser default is.
  }, [clientId, locale, oauthLogin, router, onError]);

  if (!clientId) return null;

  return (
    <div
      ref={buttonRef}
      // Keep cell height stable while GIS is still loading so the grid doesn't reflow.
      style={{ minHeight: 40 }}
      aria-busy={busy}
    />
  );
}
