"use client";

import { Suspense, useActionState, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuthStore } from "@/store/auth";
import { useLocaleStore } from "@/store/locale";
import { useThemeStore } from "@/store/theme";
import { useT } from "@/lib/i18n";
import { HttpError } from "@/lib/api";
import LocaleSelector from "@/components/LocaleSelector";
import ThemeToggle from "@/components/ThemeToggle";
import GoogleSignInButton from "@/components/GoogleSignInButton";
import AppleSignInButton from "@/components/AppleSignInButton";

type Mode = "login" | "register" | "forgot";

interface FormState {
  ok: boolean;
  /** Set on a successful forgot-password submission. Used to render the "check your email" message. */
  forgotSent: boolean;
  error: string | null;
  /**
   * Set when the backend responds {@code 401 code=OTP_REQUIRED} — credentials
   * were OK but the account has 2FA enabled. The form re-renders with an OTP
   * field; the email/password inputs stay mounted so the user's typed values
   * roll forward on the resubmit.
   */
  otpRequired: boolean;
}

const INITIAL_STATE: FormState = { ok: false, forgotSent: false, error: null, otpRequired: false };

/**
 * React 19 client action — runs on form submit. Pulls fields from FormData,
 * delegates to the auth store, and returns a {@link FormState} that
 * {@link useActionState} threads back into the component.
 */
async function submitAuth(_prev: FormState, formData: FormData): Promise<FormState> {
  const mode = (formData.get("mode") as Mode) ?? "login";
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const displayName = String(formData.get("displayName") ?? "").trim();
  const otp = String(formData.get("otp") ?? "").trim();

  if (!email) {
    return { ok: false, forgotSent: false, error: "Email is required", otpRequired: false };
  }
  if (mode !== "forgot" && !password) {
    return { ok: false, forgotSent: false, error: "Password is required", otpRequired: false };
  }

  try {
    const store = useAuthStore.getState();
    if (mode === "register") {
      await store.register(email, password, displayName);
    } else if (mode === "forgot") {
      await store.requestPasswordReset(email);
      return { ok: false, forgotSent: true, error: null, otpRequired: false };
    } else {
      await store.login(email, password, otp || undefined);
    }
    return { ok: true, forgotSent: false, error: null, otpRequired: false };
  } catch (err) {
    if (err instanceof HttpError) {
      const code = err.payload?.code;
      // Password matched but the account requires a 2FA code — surface the
      // OTP field and let the user retry. OTP_INVALID re-renders the same
      // step with an error so the user can correct a typo without starting
      // over from the email field.
      if (code === "OTP_REQUIRED") {
        return { ok: false, forgotSent: false, error: null, otpRequired: true };
      }
      if (code === "OTP_INVALID") {
        return {
          ok: false,
          forgotSent: false,
          error: err.payload?.message ?? "Invalid OTP",
          otpRequired: true,
        };
      }
    }
    return {
      ok: false,
      forgotSent: false,
      error: err instanceof Error ? err.message : "Authentication failed",
      otpRequired: false,
    };
  }
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginPageInner />
    </Suspense>
  );
}

function LoginPageInner() {
  const router = useRouter();
  const params = useSearchParams();
  const justReset = params.get("reset") === "ok";
  const hydrate = useAuthStore((s) => s.hydrate);
  const hydrateLocale = useLocaleStore((s) => s.hydrate);
  const hydrateTheme = useThemeStore((s) => s.hydrate);
  const token = useAuthStore((s) => s.token);
  const oauthLogin = useAuthStore((s) => s.oauthLogin);
  const t = useT();

  const [mode, setMode] = useState<Mode>("login");
  const [state, formAction, pending] = useActionState(submitAuth, INITIAL_STATE);
  const [oauthBusy, setOauthBusy] = useState<"google" | "apple" | null>(null);
  const [oauthError, setOauthError] = useState<string | null>(null);

  // Controlled so React 19's auto form-reset after a form action runs doesn't
  // wipe the user's typed credentials. Without this, the second-step OTP
  // re-render shows the demo defaults again, which is what the user typed
  // over in the first place.
  const [email, setEmail] = useState("demo@arxivlens.local");
  const [password, setPassword] = useState("demo123");

  useEffect(() => {
    hydrate();
    hydrateLocale();
    hydrateTheme();
  }, [hydrate, hydrateLocale, hydrateTheme]);

  // If already authed (or just authed), bounce to /feed.
  useEffect(() => {
    if (token || state.ok) router.replace("/feed");
  }, [token, state.ok, router]);

  async function startOAuth(provider: "google" | "apple") {
    setOauthBusy(provider);
    setOauthError(null);
    try {
      await oauthLogin(provider);
    } catch (e) {
      setOauthError(e instanceof Error ? e.message : `${provider} sign-in failed`);
    } finally {
      setOauthBusy(null);
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
        <p className="mt-1 text-sm text-zinc-500">
          {mode === "login"
            ? t("login.heading_signin")
            : mode === "register"
            ? t("login.heading_register")
            : t("login.forgot_heading")}
        </p>

        {justReset && mode === "login" && (
          <p role="status" className="mt-4 text-sm text-green-700 dark:text-green-400">
            {t("reset.success")}
          </p>
        )}

        <form action={formAction} className="mt-6 space-y-4" aria-busy={pending}>
          <input type="hidden" name="mode" value={mode} />

          {mode === "register" && (
            <Field name="displayName" type="text" label={t("login.display_name")} autoComplete="name" />
          )}
          <Field
            name="email"
            type="email"
            label={t("login.email")}
            required
            value={email}
            onValueChange={setEmail}
            autoComplete="email"
          />
          {mode !== "forgot" && (
            <Field
              name="password"
              type="password"
              label={t("login.password")}
              required
              value={password}
              onValueChange={setPassword}
              minLength={mode === "register" ? 8 : undefined}
              autoComplete={mode === "register" ? "new-password" : "current-password"}
            />
          )}

          {mode === "login" && state.otpRequired && (
            <div>
              <label htmlFor="f_otp" className="block text-sm mb-1">{t("login.otp_label")}</label>
              <input
                id="f_otp"
                name="otp"
                type="text"
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                required
                autoFocus
                autoComplete="one-time-code"
                placeholder="123456"
                className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm tracking-widest text-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              />
              <p className="mt-1 text-xs text-zinc-500">{t("login.otp_hint")}</p>
            </div>
          )}

          {mode === "login" && !state.otpRequired && (
            <div className="-mt-2 text-right">
              <button
                type="button"
                onClick={() => setMode("forgot")}
                className="text-xs text-blue-600 dark:text-blue-400 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded"
              >
                {t("login.forgot_password")}
              </button>
            </div>
          )}

          {mode === "forgot" && (
            <p className="text-xs text-zinc-500">{t("login.forgot_subtitle")}</p>
          )}

          {state.error && (
            <p role="alert" className="text-sm text-red-600 dark:text-red-400">{state.error}</p>
          )}

          {state.forgotSent && mode === "forgot" && (
            <p role="status" className="text-sm text-green-700 dark:text-green-400">
              {t("login.forgot_sent")}
            </p>
          )}

          <button
            type="submit"
            disabled={pending}
            className="w-full rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 py-2.5 text-sm font-medium shadow-sm hover:opacity-90 transition-opacity disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            {pending
              ? t("login.please_wait")
              : mode === "login"
              ? t("login.signin_password")
              : mode === "register"
              ? t("login.create")
              : t("login.forgot_send")}
          </button>

          {mode === "forgot" && (
            <button
              type="button"
              onClick={() => setMode("login")}
              className="block w-full text-center text-xs text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded"
            >
              {t("login.forgot_back")}
            </button>
          )}
        </form>

        {mode !== "forgot" && (
          <div className="mt-6">
            <div className="flex items-center gap-3 text-xs text-zinc-500">
              <span aria-hidden className="flex-1 h-px bg-zinc-200 dark:bg-zinc-800" />
              <span>{t("login.continue_with")}</span>
              <span aria-hidden className="flex-1 h-px bg-zinc-200 dark:bg-zinc-800" />
            </div>

            {/*
              Hide the Google cell entirely when NEXT_PUBLIC_GOOGLE_CLIENT_ID
              isn't baked into the build. Previously we showed the custom
              mock button as a fallback — but the backend now strictly requires
              a real ID token, so the fallback path 400'd silently with
              "Missing Google ID token". A missing button is clearer than a
              broken one: it signals that env-var setup is incomplete instead
              of looking like a real auth feature.
            */}
            {/*
              Stack the OAuth buttons in a single full-width column rather than a
              2-up grid. Side-by-side, each cell is only ~188px wide inside the
              max-w-md card, but Google's GIS button enforces a ~200px minimum
              width and overflows into the Apple button — they visibly overlap.
              Stacking gives every button the full card width (Google renders
              comfortably; the Apple label never crowds) and matches the
              full-width submit button above. Works across all locales without
              having to trim labels per-language.
            */}
            <div className="mt-3 grid grid-cols-1 gap-2">
              {process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID && (
                <GoogleSignInButton onError={setOauthError} />
              )}
              {/*
                Real Sign in with Apple when the Services ID + redirect URI are
                baked into the build; otherwise the mock button so the demo and
                offline dev keep a working Apple cell (mirrors the Google path).
              */}
              {process.env.NEXT_PUBLIC_APPLE_CLIENT_ID && process.env.NEXT_PUBLIC_APPLE_REDIRECT_URI ? (
                <AppleSignInButton onError={setOauthError} />
              ) : (
                <OAuthButton
                  provider="apple"
                  label={t("login.continue_apple")}
                  busyLabel={t("login.connecting")}
                  busy={oauthBusy === "apple"}
                  disabled={oauthBusy !== null || pending}
                  onClick={() => startOAuth("apple")}
                />
              )}
            </div>

            {oauthError && (
              <p role="alert" className="mt-3 text-xs text-red-600 dark:text-red-400">{oauthError}</p>
            )}

            <p className="mt-3 text-[11px] text-zinc-400 dark:text-zinc-500">
              {t("login.oauth_hint")}
            </p>
          </div>
        )}

        {mode !== "forgot" && (
          <div className="mt-6 pt-5 border-t border-zinc-200 dark:border-zinc-800 text-center text-sm">
            {mode === "login" ? (
              <>
                <span className="text-zinc-500">{t("login.no_account")} </span>
                <button
                  type="button"
                  onClick={() => setMode("register")}
                  className="font-medium text-blue-600 dark:text-blue-400 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded"
                >
                  {t("login.tab_register")}
                </button>
              </>
            ) : (
              <>
                <span className="text-zinc-500">{t("login.have_account")} </span>
                <button
                  type="button"
                  onClick={() => setMode("login")}
                  className="font-medium text-blue-600 dark:text-blue-400 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded"
                >
                  {t("login.tab_login")}
                </button>
              </>
            )}
          </div>
        )}

        {mode === "login" && (
          <p className="mt-4 text-xs text-zinc-500 text-center">
            {t("login.demo_hint")} <code className="font-mono">demo@arxivlens.local</code> / <code className="font-mono">demo123</code>
          </p>
        )}
      </div>
    </main>
  );
}

function OAuthButton({
  provider,
  label,
  busyLabel,
  busy,
  disabled,
  onClick,
}: {
  provider: "google" | "apple";
  label: string;
  busyLabel: string;
  busy: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className="flex items-center justify-center gap-2 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 hover:bg-zinc-50 dark:hover:bg-zinc-900 px-3 py-2 text-sm disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
    >
      <ProviderIcon provider={provider} />
      <span>{busy ? busyLabel : label}</span>
    </button>
  );
}

function ProviderIcon({ provider }: { provider: "google" | "apple" }) {
  if (provider === "google") {
    return (
      <svg aria-hidden width="16" height="16" viewBox="0 0 48 48">
        <path fill="#FFC107" d="M43.611,20.083H42V20H24v8h11.303c-1.649,4.657-6.08,8-11.303,8c-6.627,0-12-5.373-12-12c0-6.627,5.373-12,12-12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C12.955,4,4,12.955,4,24c0,11.045,8.955,20,20,20c11.045,0,20-8.955,20-20C44,22.659,43.862,21.35,43.611,20.083z" />
        <path fill="#FF3D00" d="M6.306,14.691l6.571,4.819C14.655,15.108,18.961,12,24,12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C16.318,4,9.656,8.337,6.306,14.691z" />
        <path fill="#4CAF50" d="M24,44c5.166,0,9.86-1.977,13.409-5.192l-6.19-5.238C29.211,35.091,26.715,36,24,36c-5.202,0-9.619-3.317-11.283-7.946l-6.522,5.025C9.505,39.556,16.227,44,24,44z" />
        <path fill="#1976D2" d="M43.611,20.083H42V20H24v8h11.303c-0.792,2.237-2.231,4.166-4.087,5.571c0.001-0.001,0.002-0.001,0.003-0.002l6.19,5.238C36.971,39.205,44,34,44,24C44,22.659,43.862,21.35,43.611,20.083z" />
      </svg>
    );
  }
  return (
    <svg
      aria-hidden
      width="16"
      height="16"
      viewBox="0 0 24 24"
      className="fill-zinc-900 dark:fill-zinc-100"
    >
      <path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
    </svg>
  );
}

function Field({
  name,
  label,
  type,
  required,
  minLength,
  autoComplete,
  defaultValue,
  value,
  onValueChange,
}: {
  name: string;
  label: string;
  type: "text" | "email" | "password";
  required?: boolean;
  minLength?: number;
  autoComplete?: string;
  /** Uncontrolled initial value. Ignored when {@code value}+{@code onValueChange} are supplied. */
  defaultValue?: string;
  /** Controlled value. Pair with {@code onValueChange} to preserve input across form-action re-renders. */
  value?: string;
  onValueChange?: (next: string) => void;
}) {
  const id = `f_${name}`;
  const controlled = value !== undefined;
  return (
    <div>
      <label htmlFor={id} className="block text-sm mb-1">{label}</label>
      <input
        id={id}
        name={name}
        type={type}
        required={required}
        minLength={minLength}
        autoComplete={autoComplete}
        {...(controlled
          ? { value, onChange: (e) => onValueChange?.(e.target.value) }
          : { defaultValue })}
        className="w-full rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      />
    </div>
  );
}
