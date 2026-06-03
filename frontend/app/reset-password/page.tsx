"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuthStore } from "@/store/auth";
import { useLocaleStore } from "@/store/locale";
import { useThemeStore } from "@/store/theme";
import { useT } from "@/lib/i18n";
import LocaleSelector from "@/components/LocaleSelector";
import ThemeToggle from "@/components/ThemeToggle";

/**
 * Landing page for password-reset links emailed by the backend. The token is
 * delivered as {@code ?token=…}; this page validates it client-side only by
 * shape (presence + non-empty), letting the backend do the real expiry/use
 * check on submit. On success we bounce to /login with a status flag so the
 * sign-in form can show "Password reset — sign in with your new password."
 *
 * Wrapped in {@code Suspense} because Next.js 16 requires {@code useSearchParams}
 * to be inside a boundary or the page deopts to client-only render with a warning.
 */
export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPasswordInner />
    </Suspense>
  );
}

function ResetPasswordInner() {
  const router = useRouter();
  const params = useSearchParams();
  const token = params.get("token") ?? "";

  const hydrateLocale = useLocaleStore((s) => s.hydrate);
  const hydrateTheme = useThemeStore((s) => s.hydrate);
  const resetPassword = useAuthStore((s) => s.resetPassword);
  const t = useT();

  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    hydrateLocale();
    hydrateTheme();
  }, [hydrateLocale, hydrateTheme]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) {
      setError(t("reset.invalid_token"));
      return;
    }
    if (password.length < 8) {
      setError(t("reset.error_too_short"));
      return;
    }
    if (password !== confirm) {
      setError(t("reset.error_mismatch"));
      return;
    }
    setSubmitting(true);
    try {
      await resetPassword(token, password);
      router.replace("/login?reset=ok");
    } catch (e) {
      setError(e instanceof Error ? e.message : t("reset.invalid_token"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex flex-1 items-center justify-center px-4 py-12">
      <div className="absolute right-4 top-4 flex items-center gap-2">
        <ThemeToggle />
        <LocaleSelector />
      </div>
      <div className="w-full max-w-md rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 sm:p-8 shadow-sm">
        <h1 className="text-2xl font-semibold tracking-tight">arXivLens</h1>
        <p className="mt-1 text-sm text-zinc-500">{t("reset.heading")}</p>

        {!token ? (
          <p role="alert" className="mt-6 text-sm text-red-600 dark:text-red-400">
            {t("reset.invalid_token")}
          </p>
        ) : (
          <form onSubmit={onSubmit} className="mt-6 space-y-4" aria-busy={submitting}>
            <div>
              <label htmlFor="new_password" className="block text-sm mb-1">
                {t("reset.new_password")}
              </label>
              <input
                id="new_password"
                type="password"
                required
                minLength={8}
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              />
            </div>
            <div>
              <label htmlFor="confirm_password" className="block text-sm mb-1">
                {t("reset.confirm_password")}
              </label>
              <input
                id="confirm_password"
                type="password"
                required
                minLength={8}
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-red-600 dark:text-red-400">{error}</p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 py-2 text-sm font-medium disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
              {submitting ? t("login.please_wait") : t("reset.submit")}
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
