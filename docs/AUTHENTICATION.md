# Authentication

*(Note: this flow is **implemented** in `data/oura` and reachable from Settings. It has **never been
run against Oura**: that needs a registered developer application and a local `.env`, which only the
owner's account can produce. Everything below therefore describes code that exists and is tested
against a local server, not a login anyone has completed.)*

## Overview
The app requires Oura API access to fetch biometric data. Per [ADR-006](DECISIONS.md#adr-006-no-separate-backend-in-the-mvp)
the MVP has **no backend**: the OAuth2 authorization-code exchange happens **inside the app**.
The Oura client secret is injected into `BuildConfig` from a git-ignored `.env` file, and tokens
are encrypted with an Android Keystore key
([ADR-008](DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences) —
this used to say `EncryptedSharedPreferences`, whose library is deprecated).

This is acceptable **only** because Treenivalmentaja is a private single-user APK that is never
published. See ADR-006 for the reasoning and the revisit triggers.

## OAuth2 Flow (Oura)
We use the standard OAuth2 Authorization Code flow with PKCE.

```mermaid
sequenceDiagram
    participant User
    participant Android App
    participant Oura API

    User->>Android App: Taps "Yhdistä Oura"
    Android App->>Android App: Generates `state` + PKCE `code_verifier`
    Android App->>Android App: Stores both, encrypted, before opening anything
    Android App->>User: Opens the system browser at the Oura auth URL (with `code_challenge`)
    User->>Oura API: Grants Permission
    Oura API-->>Android App: Redirects to OuraCallbackActivity with `code` & `state`
    Android App->>Android App: Validates `state` — a mismatch ends it here
    Android App->>Oura API: POST /oauth/token (code, code_verifier, client_id, client_secret)
    Oura API-->>Android App: Access & Refresh Tokens
    Android App->>Android App: Stores tokens under an Android Keystore key; clears the verifier
```

## Details

### Client Credentials
- **Entered in the app**, on the Settings screen, and stored encrypted under the same Keystore key
  as the tokens ([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)).
  This is what makes the feature usable on a phone that only ever receives an APK from a GitHub
  release — a build from CI has no `.env` and never could connect otherwise.
- Read at run time, **store first, `BuildConfig` second**, so a local build with a real `.env` keeps
  working with nothing typed in.
- `OURA_CLIENT_ID` and `OURA_CLIENT_SECRET` may still be put in a local `.env` at the repository
  root. `.env` is git-ignored and **must never be committed**; `.env.example` documents the keys
  with placeholders only, and the Secrets Gradle Plugin injects them as `BuildConfig` fields.
- No secret is ever written into Kotlin source, resources, or version control.

### Personal access tokens are not an option
Oura's specification declares a `BearerAuth` scheme on 69 of its operations, which would allow a
single pasted token and no OAuth at all. **The specification is stale on this point.** Oura withdrew
personal access tokens in December 2025, and their current authentication documentation describes
OAuth2 only. A registered application is the only way in. Recorded here because the spec in this
repository still says otherwise, and the next person to read it will have the same idea.

### PKCE
- A random `code_verifier` is generated per authorization attempt — 32 random bytes as unpadded
  base64url, 43 characters — and stored **encrypted on disk**, not in memory or a
  `SavedStateHandle`. The round trip leaves the app in the background where the process may be
  killed outright, and a verifier lost that way turns a successful login into a failed exchange.
  It is cleared after one attempt, successful or not.
- The `code_challenge` (S256) is sent with the authorization request; the `code_verifier` is sent
  with the token exchange. An intercepted authorization code is therefore not usable on its own.

### The browser
- An **external browser** (`ACTION_VIEW`), not a WebView. A WebView would let this app read the
  Oura password as it is typed, which is the thing the authorization-code flow exists to prevent.
  RFC 8252 permits either an in-app browser tab or the system browser; it forbids the WebView.
- No `androidx.browser` dependency was added for a Custom Tab. The system browser satisfies the
  same requirement, and this project counts APK bytes.

### Redirect URI
- A custom scheme `treenivalmentaja://oauth2callback` is configured in the AndroidManifest and in
  the Oura Developer Console.
- `OuraCallbackActivity` receives it. The activity is **exported** — a browser has to be able to
  start it — and acts on nothing itself: it forwards the URI to `OuraConnection`, which discards
  anything whose `state` is not the exact value this device generated.

### State Validation
- A secure random `state` string is generated locally, passed to Oura, and compared on redirect.
  A mismatch aborts the flow with a security warning and no token exchange is attempted.

### Token Storage
- AES-256-GCM under an Android Keystore key, in ordinary `SharedPreferences` —
  `data/oura/OuraTokenStore.kt`, and
  [ADR-008](DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences)
  for why not the deprecated library.
- **Expiry:** stored as an absolute timestamp, not a duration, so "is this still good" does not
  depend on when it is asked. `0` means the token endpoint did not say — in which case nothing
  refreshes proactively and a `401` is what triggers renewal.
- Excluded from cloud backup and device transfer: the Keystore key does not travel with a backup,
  so restored ciphertext would be unreadable.

### Token Renewal
- Handled locally. An OkHttp `Authenticator` intercepts `401 Unauthorized`, performs
  `grant_type=refresh_token` against the Oura token endpoint using the `BuildConfig` credentials,
  persists the rotated tokens, and retries the original request once.
- Concurrent refresh attempts are serialised so a rotated refresh token is not spent twice.

### Disconnect and Deletion Flow
- User taps "Katkaise Oura-yhteys" in Settings.
- App clears the stored tokens and deletes cached Oura rows from Room (`OuraDailySummary`,
  `OuraWorkout`). Training plan data is untouched.
- **No revoke call.** This document used to promise one. The vendored specification
  (`docs/api/oura-openapi-1.37.json`) declares **no `/oauth` paths at all** — the authorize and
  token URLs appear only in `securitySchemes` — so there is no documented revoke endpoint to call,
  and guessing a URL would be inventing an API. Access is given up locally; revoking the
  application itself is done from Oura's own account settings, and the Settings card says so.

### Failure Cases
| Case | Behaviour |
| --- | --- |
| Network error during exchange | Error shown, tokens untouched, user can retry. |
| Invalid `state` | Abort flow, show security warning, do not exchange the code. |
| Missing `OURA_CLIENT_SECRET` in `.env` | Build succeeds (placeholder from `.env.example`), and the Settings card shows "Oura-tunnuksia ei ole määritetty" with **no connect button at all** — a disabled button invites tapping to find out why. |
| Process killed during the browser round-trip | The verifier and `state` are on disk, so returning to a freshly started process still completes the login. |
| Verifier lost anyway | Reported as an interrupted login, with an invitation to connect again. Nothing is exchanged without one. |
| Token expiration | `Authenticator` refreshes transparently and retries once. |
| Refresh token rejected | Tokens cleared, user is prompted to reconnect. |

### What is explicitly NOT used
- Firebase Authentication (no anonymous auth, no Firebase UID).
- Firebase Functions (no proxy backend).
- Any remote token store.
