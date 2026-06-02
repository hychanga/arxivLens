---
name: auth-login
description: >-
  Implement or extend web-app authentication end to end — email/password + JWT,
  social/OAuth login (Google, Sign in with Apple), TOTP two-factor, and
  password reset — using a proven Spring Boot (backend) + Next.js (frontend)
  pattern. TRIGGER when the user asks to add or change login / sign-in / signup,
  social or OAuth login (Google/Apple/etc.), 2FA/TOTP, JWT sessions, or
  password reset. Adapt the patterns to the project's actual stack.
---

# Web auth: login, OAuth, 2FA, password reset

A reusable playbook distilled from a production hobby app (Spring Boot 4 / Java
backend on Render, Next.js 16 / React 19 frontend on Vercel, JWT sessions). The
**decisions and gotchas** transfer to any stack; translate the snippets.

## 0. Decide scope first
Ask which of these the project needs, then build only those:
- Email + password (always the baseline).
- Social/OAuth — which providers? (Google, Apple, …)
- 2FA (TOTP authenticator apps).
- Password reset (email link).
Each is independent; ship incrementally.

## 1. Core principles (apply to all of it)
- **Stateless JWT.** Issue an HS256 JWT on every successful auth; client stores
  it (localStorage is fine for a hobby app; httpOnly cookie is stricter).
  Secret must be **≥ 32 bytes**. Put `sub`=userId, `email`, `role`, `exp`.
- **One user table, multiple credentials.** A user row carries
  `password_hash` (nullable for OAuth-only), `oauth_provider`, `oauth_subject`,
  `totp_secret` (nullable). Add a unique constraint on
  `(oauth_provider, oauth_subject)`.
- **BCrypt** for passwords. Never log or return the hash (`@JsonIgnore`).
- **Config-gated providers with a mock fallback.** If a provider's client ID
  isn't configured, fall back to a deterministic demo user so local dev / CI /
  a public demo keep working without real credentials. The frontend hides/shows
  the real button based on a `NEXT_PUBLIC_*` env var.
- **Anti-enumeration.** Login and password-reset must not reveal whether an
  email exists. Same error for "no such user" and "wrong password"; reset always
  returns 204.

## 2. Endpoints (REST shape that worked well)
```
POST /api/auth/register         -> {token, expiresIn, user}
POST /api/auth/login            -> {token,...}  (body: email, password, otp?)
POST /api/auth/oauth/{provider} -> {token,...}  (body: {idToken})
POST /api/auth/forgot-password  -> 204 always
POST /api/auth/reset-password   -> 204          (body: token, password)
GET/POST /api/auth/2fa/{status|setup|enable|disable}   (authenticated)
```
Security config: `/api/auth/**` permitAll, EXCEPT `/api/auth/2fa/**` which must
come first as `authenticated()` (you can't set up 2FA for an account you can't
log into). First matching rule wins.

## 3. Email + password
- Register: reject duplicate email (409), BCrypt-encode, save, return JWT.
- Login: look up by email; if `password_hash` null or BCrypt mismatch → **same**
  401 "Invalid credentials" for both cases.
- Issue JWT via a small `JwtService` (issue/parse, validate issuer + signature +
  exp). Reject a secret < 32 bytes at startup with a clear message.

## 4. OAuth / social login
Route by provider; verify the provider's identity token **server-side**; upsert
the user keyed on `(provider, subject)`, falling back to email match so an
existing password user attaches to the same row instead of duplicating.

```java
case "google" -> googleOauthLogin(idToken); // verify, upsert, issue JWT
case "apple"  -> appleOauthLogin(idToken);
```

### Google
- Frontend: Google Identity Services (`accounts.google.com/gsi/client`) renders
  its own button and returns a `credential` (ID token) on success.
- Backend: verify by GET to `https://oauth2.googleapis.com/tokeninfo?id_token=…`
  (fine for low volume — no extra deps). Then assert `iss ∈
  {accounts.google.com, https://accounts.google.com}` and `aud == your client
  ID`; require `sub` + `email`. The verified `sub` is the stable subject.
- Env: `GOOGLE_CLIENT_ID` (backend audience) + `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
  (must match).

### Sign in with Apple (the fiddly one)
- **Needs a paid Apple Developer Program ($99/yr).** A free account can't create
  the Services ID. Until configured, keep the mock fallback so the demo works.
- Apple has **no tokeninfo endpoint** — you must verify the JWS yourself:
  1. Fetch Apple's JWKS from `https://appleid.apple.com/auth/keys` (RSA public
     keys). **Cache it** (e.g. 6h TTL) and **re-fetch on an unknown `kid`**
     (Apple rotates keys).
  2. Match the token header's `kid`, verify the RS256 signature with that key
     (a JWT lib's key-locator + JWKS parser does this; JJWT 0.12 has `Jwks`).
  3. Manually assert `iss == https://appleid.apple.com`, `aud` ∈ your Services
     ID(s), and `exp` (the parse enforces exp). Extract `sub` (+ `email` when
     present — Apple sends the name only on the FIRST authorization's form post,
     not in the id_token, so fall back to email for the display name).
- Frontend: Apple JS SDK
  (`appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/<locale>/appleid.auth.js`),
  `AppleID.auth.init({clientId, scope:"name email", redirectURI, usePopup:true})`,
  then `AppleID.auth.signIn()` → `authorization.id_token`. Treat
  `popup_closed_by_user` as a non-error.
- Style your own button (don't use Apple's default if you have a design system),
  but use Apple's **official localized label**: en "Sign in with Apple",
  zh-TW "透過 Apple 登入", zh-CN "通过 Apple 登录", ja "Appleでサインイン",
  de "Mit Apple anmelden".
- Setup: Apple portal → App ID with Sign in with Apple → **Services ID** (this
  is your `client_id`/`aud`, e.g. `com.acme.web`) → register the domain + a
  Return URL (HTTPS, must match `redirectURI` exactly, even in popup mode) →
  verify the domain (serve `apple-developer-domain-association.txt`).
- Env: `APPLE_CLIENT_ID` (Services ID; comma-separate for multiple audiences) +
  `NEXT_PUBLIC_APPLE_CLIENT_ID` + `NEXT_PUBLIC_APPLE_REDIRECT_URI`.

## 5. Two-factor (TOTP)
- **Two-step setup so you never half-enable.** `setup` generates a secret +
  `otpauth://` URI but persists NOTHING; the client shows the QR, the user
  scans + enters the first code, `enable` verifies that code against the secret
  and only then saves it. `disable` clears it.
- **Login OTP step.** If the account has a TOTP secret and the request has no
  `otp`, respond `401` with a code `OTP_REQUIRED`; an invalid code →
  `OTP_INVALID`. The client re-renders an OTP field and resubmits with the same
  email/password + the code.

## 6. Password reset
- `forgot-password`: always 204. If the email exists, create a single-use,
  time-boxed token row and email a link `{frontendBaseUrl}/reset-password?token=…`.
- `reset-password`: validate token (exists, unused, unexpired), set new BCrypt
  hash, consume the token.
- **Email delivery on PaaS:** many hosts (Render free) block outbound SMTP —
  use an HTTP email API (Resend) instead. Make sending best-effort: log and
  swallow provider errors so a mail hiccup never surfaces as a 5xx.

## 7. Frontend login page (React 19 / Next.js)
- Use `useActionState` with a client action `submitAuth(prev, formData)` that
  returns a typed `FormState {ok, error, otpRequired, forgotSent}`. Switch
  `mode` (login | register | forgot) in local state.
- **Control the email/password inputs.** React 19 auto-resets a form after a
  form action; without controlled values the OTP re-render wipes what the user
  typed. Keep them in `useState`.
- OAuth cell: render the real provider button only when its `NEXT_PUBLIC_*` env
  is set; otherwise a mock button (so the demo always has a working cell).
- After auth: store the token, wipe any per-user client caches/stores (so user
  A's data doesn't bleed into user B), redirect to the app.
- A shared `apiFetch`: attach `Authorization: Bearer …`; on 401 clear the token
  and bounce to `/login`; map 204 → undefined.
- Localize every label/button (including provider buttons).

## 8. Security checklist (run before shipping)
- [ ] JWT secret ≥ 32 bytes, from env, not committed.
- [ ] Password + OAuth-subject + TOTP secret are `@JsonIgnore` / never returned.
- [ ] Login & reset are anti-enumeration (uniform responses).
- [ ] OAuth tokens verified server-side (signature + iss + aud + exp); never
      trust a client-sent email/sub without verification.
- [ ] `(oauth_provider, oauth_subject)` unique; email column unique.
- [ ] CORS allow-list is the exact frontend origin(s); credentials handled
      consistently.
- [ ] 2FA endpoints require an authenticated session.
- [ ] Run the project's security review over the diff (injection, authz, secrets).

## 9. Gotchas seen in practice
- Apple/Google **deploy skew**: a frontend that calls a new endpoint ships
  faster than the backend — expect transient 404/405 until the backend catches
  up. (405 specifically if the path collides with an existing `/{id}` route.)
- `NEXT_PUBLIC_*` vars are **baked at build time** — changing one needs a
  rebuild, not just a restart.
- Apple's id_token has **no name**; don't rely on it for the display name.
- Keep a **mock fallback** for every provider so CI and public demos work
  without paid credentials.
