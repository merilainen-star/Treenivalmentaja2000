# API Integrations

*(Note: the client that speaks this API is built —
`app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/`, on OkHttp per
[ADR-007](DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client). **Nothing calls it yet**:
the authentication that would give it a token, the sync that would schedule it and the card that
would show the result are all still planned. Everything below about the API itself was read out of
the vendored specification, not from memory, and the client has never been run against the live
service.)*

## Oura API V2

### The specification is in this repository

[`docs/api/oura-openapi-1.37.json`](api/oura-openapi-1.37.json) — OpenAPI 3.1.0, document revision
1.37, API version 2.0, 72 paths and 75 schemas. Vendored so the shape of a response can be checked
without an account, a token, or a network connection, and so a later session sees the same document
this one was written against.

**Do not take the base URL from it.** The spec's `servers[0].url` is `https://api.None.com` — a
placeholder that was never filled in, and what a code generator pointed at this file would produce.
The real host is below.

### Base information

| | |
| --- | --- |
| Base URL | `https://api.ouraring.com` |
| Collections | `/v2/usercollection/{collection}` |
| Authorization URL | `https://cloud.ouraring.com/oauth/authorize` |
| Token URL | `https://api.ouraring.com/oauth/token` |
| Scopes offered | `email`, `personal`, `daily`, `heartrate`, `workout`, `tag`, `session`, `spo2Daily` |
| Scopes this app needs | `daily`, `workout` — and `personal` only if the profile is ever shown |

Two auth schemes are accepted on every endpoint: `BearerAuth` (a personal access token) and
`OAuth2` (authorization code). See [AUTHENTICATION.md](AUTHENTICATION.md).

### There is a sandbox, and it still needs a token

Every collection is mirrored under `/v2/sandbox/usercollection/…` and returns synthetic data. It is
useful for developing against realistic payloads without wearing a ring for a week, but it is **not
a way around the credentials**: the spec declares the same `BearerAuth`/`OAuth2` requirement on the
sandbox paths as on the live ones. The only difference in the contract is that sandbox endpoints do
not accept the `fields` query parameter.

### Every collection has the same shape

This matters more than it sounds. `daily_readiness`, `daily_sleep`, `daily_activity` and `workout`
are the same request and the same envelope with a different item type:

```
GET /v2/usercollection/{collection}?start_date=&end_date=&next_token=&fields=
→ { "data": [ … ], "next_token": "…" | null }
```

| Parameter | Notes |
| --- | --- |
| `start_date`, `end_date` | Optional. Accept a date **or** a date-time. |
| `next_token` | Optional. Present in the response until the last page; feed it back to continue. |
| `fields` | Optional, comma-separated. Live endpoints only. |

One generic "fetch this collection between these dates, following `next_token`" function therefore
covers all of them.

### Endpoints used

| Endpoint | For | Item schema |
| --- | --- | --- |
| `/v2/usercollection/daily_readiness` | the readiness reading on the Today screen | `PublicDailyReadiness` |
| `/v2/usercollection/workout` | matching completed workouts to planned sessions | `PublicWorkout` |
| `/v2/usercollection/daily_sleep` | sleep score, if readiness alone proves too blunt | `PublicDailySleep` |
| `/v2/usercollection/personal_info` | only if the profile is ever shown | `PersonalInfoResponse` |

`daily_activity` is available and returns 25 fields; nothing in the app needs them yet.

### Fields that actually exist

The names below are from the spec. An earlier revision of this document listed `activity_type` and
`active_calories` for workouts; neither exists.

**`PublicWorkout`** — required: `id`, `activity`, `day`, `end_datetime`, `intensity`, `source`,
`start_datetime`. Optional: `calories`, `distance`, `label`.

**`PublicDailyReadiness`** — required: `id`, `contributors`, `day`, `timestamp`. Optional:
`score`, `temperature_deviation`, `temperature_trend_deviation`.

**`score` is optional, and that is the whole design constraint for the recovery card.** A day the
ring was not worn returns a document with no score rather than no document. The card must be able
to say "ei tietoa" for a day that exists.

Readiness `contributors`: `activity_balance`, `body_temperature`, `hrv_balance`,
`previous_day_activity`, `previous_night`, `recovery_index`, `resting_heart_rate`, `sleep_balance`,
`sleep_regularity`.

### Errors, and which of them are the user's problem

Every collection endpoint declares the same set. They are not all the same kind of thing:

| Status | Spec's wording | What it means here |
| --- | --- | --- |
| `400` | Client Exception | A bug in our request. Not retryable. |
| `401` | "access token is expired, malformed or revoked" | Refresh once and retry — the `Authenticator`'s job. |
| `403` | "the user's subscription to Oura has expired and their data is not available via the API" | **Not an error to retry.** A state to show: the ring works, the API does not. |
| `422` | Validation Error | A bad parameter. Not retryable. |
| `429` | Request Rate Limit Exceeded | Back off and retry. No `Retry-After` is documented. |

The spec documents no rate-limit numbers and no `Retry-After` header, so the backoff has to be
chosen rather than read.

### What the client makes of all that

`OuraClient` turns each of them into a type carrying an already-Finnish message and a `canRetry`
flag, so a caller decides what to do without re-reading status codes:

| Status | Type | `canRetry` |
| --- | --- | --- |
| no token stored | `OuraNotConnectedException` | no — connect Oura |
| `401` | `OuraAuthException` | no — refresh, once there is a refresh token to use |
| `403` | `OuraSubscriptionExpiredException` | no — a state to show |
| `429` | `OuraRateLimitException` | yes |
| `400`, `422` | `OuraRequestException` | no — our bug |
| network failure, `5xx`, unreadable body | `OuraUnavailableException` | yes |

Paging is followed to the end rather than capped, with one exception: a service that never stops
returning a `next_token` ends the run with `OuraUnavailableException` after 50 pages. Returning part
of an answer as though it were all of it is the one outcome that is not allowed.

### Timestamps, timezones and units

- All internal scheduling uses Unix timestamps (UTC).
- The UI and the Oura query parameters use local dates, defaulting to `Europe/Helsinki`.
- Metric units throughout.

### Missing data

Oura returns nothing for days the ring was not worn, and returns documents with missing optional
fields for days it was worn incompletely. Neither is zero, and neither may be rendered as zero.

### Third-party imports

Workouts synced into Oura from elsewhere (Suunto → Strava → Oura) appear in `/workout` with
`source` naming the origin. The app uses them transparently when matching planned sessions.

**Future extension:** direct Strava integration for richer telemetry, bypassing Oura.
