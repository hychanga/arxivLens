"use client";

import { create } from "zustand";
import { apiFetch, getStoredToken, setStoredToken } from "@/lib/api";
import type { AuthResponse, UserSummary } from "@/types";
import { useFavoritesStore } from "./favorites";
import { useDownloadsStore } from "./downloads";
import { usePreferencesStore } from "./preferences";
import { useSourcesStore } from "./sources";
import { useTopicsStore } from "./topics";
import { usePapersStore } from "./papers";
import { useTranslationsStore } from "./translations";

export type OAuthProvider = "google" | "apple";

interface AuthState {
  user: UserSummary | null;
  token: string | null;
  status: "idle" | "loading" | "authed" | "anon";
  error: string | null;
  login: (email: string, password: string, otp?: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  oauthLogin: (provider: OAuthProvider, idToken?: string) => Promise<void>;
  requestPasswordReset: (email: string) => Promise<void>;
  resetPassword: (token: string, password: string) => Promise<void>;
  logout: () => void;
  hydrate: () => void;
}

/**
 * Hard-reset every user-scoped store. Called both on logout and right after a
 * successful login, so that user A's cached favorites/downloads/preferences
 * never bleed into user B's session.
 */
function resetUserScopedStores() {
  useFavoritesStore.getState().reset();
  useDownloadsStore.getState().reset();
  usePreferencesStore.getState().reset();
  useSourcesStore.getState().reset();
  useTopicsStore.getState().reset();
  usePapersStore.getState().reset();
  // Translation cache is per-paper, not per-user, but flushing it on auth changes avoids
  // confusing stale-state when admins toggle papers off and on between sessions.
  useTranslationsStore.getState().reset();
}

function applyAuthSuccess(set: (partial: Partial<AuthState>) => void, res: AuthResponse) {
  // Wipe any stale per-user data from the previous session before storing the new token,
  // otherwise the (app)/layout effects see `loaded: true` and skip re-fetching.
  resetUserScopedStores();
  setStoredToken(res.token);
  set({ user: res.user, token: res.token, status: "authed", error: null });
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  status: "idle",
  error: null,

  hydrate: () => {
    const token = getStoredToken();
    set({ token, status: token ? "authed" : "anon" });
  },

  login: async (email, password, otp) => {
    set({ status: "loading", error: null });
    try {
      const res = await apiFetch<AuthResponse>("/auth/login", {
        method: "POST",
        // otp is only included when present so an OTP-disabled account still
        // gets the same compact request shape as before.
        body: otp && otp.length > 0 ? { email, password, otp } : { email, password },
        auth: false,
      });
      applyAuthSuccess(set, res);
    } catch (e) {
      set({ status: "anon", error: e instanceof Error ? e.message : "Login failed" });
      throw e;
    }
  },

  register: async (email, password, displayName) => {
    set({ status: "loading", error: null });
    try {
      const res = await apiFetch<AuthResponse>("/auth/register", {
        method: "POST",
        body: { email, password, displayName },
        auth: false,
      });
      applyAuthSuccess(set, res);
    } catch (e) {
      set({ status: "anon", error: e instanceof Error ? e.message : "Register failed" });
      throw e;
    }
  },

  oauthLogin: async (provider, idToken) => {
    set({ status: "loading", error: null });
    try {
      const res = await apiFetch<AuthResponse>(`/auth/oauth/${provider}`, {
        method: "POST",
        // idToken is the provider's identity token (Google Identity Services or
        // Apple's JS SDK). When a provider isn't configured server-side the
        // backend ignores an empty token and falls back to a mock demo user.
        body: idToken ? { idToken } : { idToken: "" },
        auth: false,
      });
      applyAuthSuccess(set, res);
    } catch (e) {
      set({ status: "anon", error: e instanceof Error ? e.message : `${provider} sign-in failed` });
      throw e;
    }
  },

  requestPasswordReset: async (email) => {
    // Backend returns 204 regardless of whether the email is registered
    // (anti-enumeration). We surface the same outcome to the UI.
    await apiFetch<void>("/auth/forgot-password", {
      method: "POST",
      body: { email },
      auth: false,
    });
  },

  resetPassword: async (token, password) => {
    await apiFetch<void>("/auth/reset-password", {
      method: "POST",
      body: { token, password },
      auth: false,
    });
  },

  logout: () => {
    setStoredToken(null);
    resetUserScopedStores();
    set({ user: null, token: null, status: "anon", error: null });
  },
}));
