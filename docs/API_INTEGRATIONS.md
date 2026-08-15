# API Integrations

*(Note: the client that speaks this API is built —
`app/src/main/java/fi/merilainen/treenivalmentaja/data/oura/`, on OkHttp per
[ADR-007](DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client). Authentication, a daily
sync and the recovery card on the Today screen are all built on top of it. Everything below about
the API itself was read out of the vendored specification, not from memory. A real account is now connected and the app fetches
from it, but no field it parses has been checked against what Oura's own app shows for the same
day.)*

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

### Third-party imports — this document was wrong

It used to say that workouts synced into Oura from elsewhere (Suunto → Strava → Oura) appear in
`/workout` with `source` naming the origin. **Measured on 2026-08-10 against a real account, they do
not.** Oura's own app showed an "Afternoon Run / 6.2 km, 14:55, 38 min, 540 Cal — Imported from
Strava" on 2026-08-09, and a request for 08-06..08-10 returned seven workouts including a walk from
that same day at 10:37 — and not the run. Every row that did come back carried `source: confirmed`.

So a third-party import is visible in Oura's app and absent from the workout collection, at least
promptly. What that means for this app: a run recorded on a watch and synced through Strava will not
appear as a completed session here, and no amount of matching logic fixes that.

**Two things left to establish**, neither of them guessed at here: whether such an import appears
later once Oura has processed it, and whether it carries a different `source` when it does.

### The `end_date` boundary is not the same for every collection

Measured in the same request. Asking `start_date=2026-08-06&end_date=2026-08-10` returned:

| Collection | Days back |
| --- | --- |
| `daily_readiness` | 5 — includes 08-10 |
| `daily_sleep` | 5 — includes 08-10 |
| `daily_activity` | 4 — stops at 08-09 |
| `workout` | nothing after 08-09 |

The client therefore sends `end_date` as **one day past** the range it was asked for, which is the
only request that means "up to and including this day" for all of them at once. See
`OuraClient.url`.

**This is what motivated the Strava integration below**: a run recorded on a watch reaches Oura's
own app and not its API, so the only way to see it here is to ask Strava directly.

## Strava API v3

*(Built — `app/src/main/java/fi/merilainen/treenivalmentaja/data/strava/`, on OkHttp for the same
reason the Oura client is. The setup a user performs on Strava's side is in
[STRAVA_SETUP.md](STRAVA_SETUP.md). Written against Strava's published developer documentation; no
specification is vendored, because Strava does not publish one this app could pin to. **No real
account has been connected yet** — the tests run against a local HTTP server, so what has been
verified is the client's behaviour, not that Strava's answers match the shapes below.)*

### Base information

| | |
| --- | --- |
| Base URL | `https://www.strava.com` |
| Activities | `GET /api/v3/athlete/activities` |
| Authorization URL | `https://www.strava.com/oauth/mobile/authorize` |
| Token URL | `https://www.strava.com/oauth/token` |
| Scope this app requests | `activity:read_all`, and nothing else |
| Redirect | `treenivalmentaja://localhost/strava` |

### Differences from Oura worth knowing

Both are OAuth2 authorization-code flows over OkHttp, and the two clients look alike on purpose.
Four things genuinely differ:

1. **No PKCE.** Strava's token endpoint does not accept a `code_verifier`; the client secret
   authenticates the exchange. So `state` carries the whole burden of tying a redirect to the
   request this device made, and `StravaOAuth.readRedirect` is correspondingly strict.
2. **Paging is by page number**, not by an opaque token. There is no `next_token`: request
   `page=1,2,…` with `per_page=100` and stop when a page comes back short.
3. **The host validates the redirect.** Strava checks the redirect URI's *host* against the API
   application's "Authorization Callback Domain" field. `localhost` is what that field accepts for
   an app with no web domain, hence the odd-looking `treenivalmentaja://localhost/strava`.
4. **Expiry is absolute.** The token response carries `expires_at` in epoch seconds rather than
   Oura's relative `expires_in`.

### The activity shape

```
GET /api/v3/athlete/activities?after=&before=&page=&per_page=
→ [ { … }, … ]          // a bare array, not an envelope
```

`after`/`before` are epoch **seconds**, and the app derives them from local dates in the device's
zone — a run at 23:30 belongs to that local day, not to the UTC one.

| Field | Notes |
| --- | --- |
| `id` | The activity id; also this app's primary key, so a re-fetch overwrites itself. |
| `sport_type` | A closed enum — `Run`, `TrailRun`, `VirtualRun`, `Walk`, `Ride`, `WeightTraining`, … Unlike Oura's free-form `activity`, this is documented. |
| `start_date` | UTC, `Z`-suffixed. |
| `moving_time` | Seconds. **Pace is computed from this**, not from `elapsed_time`: a pause at a crossing is not part of how fast the running was. |
| `elapsed_time` | Seconds, pauses included. |
| `distance` | Metres. |
| `average_heartrate`, `max_heartrate` | Doubles, and absent entirely without a sensor. |
| `total_elevation_gain` | Metres. |

**`calories` is deliberately not read.** The summary does not carry it — only `DetailedActivity`
does, one request per activity — and a run's load is its pace, distance, time and heart rate, all
of which the summary has. Spending the rate budget to add a number nothing decides on would be a
bad trade.

### Missing data

The same rule as Oura, for the same reason: a treadmill run may have no distance, a run without a
strap has no heart rate, and a flat run reports `0.0` elevation. None of those is rendered, and
none becomes a zero. `StravaMappers` drops rows lacking an id, a sport, a parseable start or a
moving time — those cannot be placed on the clock or reduced to a pace, which is all these rows
exist for.
