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
| `/v2/usercollection/sleep` | **the night's own HRV and resting heart rate** | `PublicModifiedSleepModel` |
| `/v2/usercollection/personal_info` | only if the profile is ever shown | `PersonalInfoResponse` |

`daily_activity` is available and returns 25 fields; nothing in the app needs them yet.

### `sleep` is not `daily_sleep`, and it is the odd one out

The two paths differ by a prefix and return entirely different documents: `daily_sleep` is a 0–100
score for the night, `sleep` is the night itself. Only the second carries `average_hrv`,
`lowest_heart_rate` and `average_heart_rate`, which is why it is fetched at all — a score is Oura's
opinion of a night relative to the athlete's own baseline, where those three are numbers that mean
the same thing next season. The enum constant is `SLEEP_PERIODS`, not `SLEEP`, for exactly this
reason.

It breaks three assumptions the other four collections share:

- **More than one document per day.** Naps are sleep periods. The `type` field
  (`long_sleep` / `sleep` / `late_nap` / `rest` / `deleted`) separates them; the app takes the
  `long_sleep` period, falls back to the longest remaining one, and discards `rest` and `deleted`
  outright. It does **not** average them — a nap's HRV is not a comparable measurement to a night's.
- **`day` means the morning you wake up**, which the specification states outright ("day that the
  sleep belongs to"). That is already how `oura_daily_summaries` is keyed, so the night merges onto
  the existing row with no offset arithmetic. The opposite convention would have been just as
  plausible and would have shifted every reading by a day.
- **Its failure is caught separately.** Which scope covers this path is an assumption, not a
  reading — the vendored specification declares empty scope arrays on *every* operation, including
  `daily_activity`, which is known to need `daily`. Oura's own documentation puts sleep periods under
  `daily`, so the scope string is unchanged; if that is wrong the endpoint answers `401`, and
  fetching it inline would take the four working collections down with it. See
  `OuraRepository.sleepPeriodsOrNone`.

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

**This is what motivated the second integration below**: a run recorded on a watch reaches Oura's
own app and not its API, so the only way to see it here is to ask elsewhere. That was Strava for
about a day, and is intervals.icu now.

## Intervals.icu API v1

*(Built — `app/src/main/java/fi/merilainen/treenivalmentaja/data/intervals/`, on OkHttp for the same
reason the Oura client is. The setup a user performs is in [INTERVALS_SETUP.md](INTERVALS_SETUP.md).
Written against the vendored [`docs/api/intervals-icu-openapi.json`](api/intervals-icu-openapi.json)
— OpenAPI 3.0.1, 117 paths, 110 schemas — fetched from the service's own `/api/v1/docs`, so field
names and types were read rather than remembered. **A real account is now connected and the app
fetches from it**, confirmed 2026-08-15. What no one has checked field by field is whether every
number it displays matches what intervals.icu's own interface shows for the same activity.)*

### Why this and not Strava

Strava paywalled its API in June 2026 — standard developer access now requires an active Strava
subscription. The Suunto watch's recordings already reach intervals.icu, whose API is free for
personal use, so the same data arrives by a different road. Suunto's **own** API was ruled out
first: its developer FAQ states plainly that access is for "companies/organizations" and that "we
do not provide this for personal use".

### Base information

| | |
| --- | --- |
| Base URL | `https://intervals.icu` |
| Activities | `GET /api/v1/athlete/{id}/activities` |
| Authentication | HTTP Basic — username the literal string `API_KEY`, password the personal key |
| Athlete id | `0` means "the athlete this key belongs to" |

Both an API key and an OAuth bearer token are accepted on every endpoint. This app uses the key:
see [INTERVALS_SETUP.md](INTERVALS_SETUP.md#why-an-api-key-rather-than-oauth) for why OAuth would be
four moving parts serving one person's own credentials.

### The activities endpoint

```
GET /api/v1/athlete/0/activities?oldest=&newest=&limit=&fields=
→ [ { … }, … ]          // a bare array, in descending date order
```

| Parameter | Notes |
| --- | --- |
| `oldest` | **Required.** Local ISO-8601 date or date-time. |
| `newest` | Optional; defaults to now. |
| `limit` | Optional. Used only by the connection test, which asks for one. |
| `fields` | Optional, comma-separated. Also drops nulls from the response. |

**There is no pagination.** No `next_token`, no page numbers — the range comes back in one array,
which is one fewer thing to get wrong than either of the other two APIs this app has spoken to.

### The fields this app reads

Fifteen, of the **183** the `Activity` schema declares. They are named in `fields` so the rest are
never sent.

| Field | Notes |
| --- | --- |
| `id` | **A string**, e.g. `i84461234` — not a number. The app's primary key, and what makes the sync idempotent. |
| `type` | `Run`, `Ride`, `Walk`, `WeightTraining`, … The spec declares no enum, so the app does not treat it as one. |
| `start_date` | UTC, `Z`-suffixed. Preferred, because it is unambiguous. |
| `start_date_local` | A wall clock with no offset. The fallback, read against the device's zone. |
| `moving_time`, `elapsed_time` | Seconds. **Pace is computed from `moving_time`** — a pause at a crossing is not part of how fast the running was. |
| `distance` | Metres. |
| `average_heartrate`, `max_heartrate` | Integers here, unlike Strava's doubles. |
| `total_elevation_gain` | Metres. |
| `average_cadence` | Steps per minute. A float in the schema, read as a whole number. |
| `calories` | Present, where Strava's summary endpoint had none. |
| `icu_training_load` | intervals.icu's own load figure. |
| `icu_intensity` | Effort relative to threshold — the number that tells a *hard* 5 km from an easy one of the same distance and time. **Scale undocumented**; see below. |
| `icu_atl`, `icu_ctl` | Acute and chronic training load — fatigue and fitness. Stored for the fatigue rule in [ROADMAP.md](ROADMAP.md); nothing reads them yet. |
| `source` | A documented enum: `STRAVA`, `UPLOAD`, `MANUAL`, `GARMIN_CONNECT`, `OAUTH_CLIENT`, `DROPBOX`, `POLAR`, **`SUUNTO`**, `COROS`, `WAHOO`, `ZWIFT`, `ZEPP`, `CONCEPT2`, `HUAWEI`. Stored, never filtered on. |
| `device_name` | Kept for diagnostics; shown nowhere. |

**`pace` is deliberately not read**, though the field exists: its unit is undocumented, and a number
whose unit is a guess is worse than one derived from two that are known.

### The three durations, measured

A real Suunto run on 2026-08-15 settled what the time fields mean. The watch reported 9.52 km,
**51:14.8** active, 11:16.5 paused and **1:02:31** total; intervals.icu's own page showed **53:46**
moving. All three appear in the response, and the app now shows all three:

| Value | Seconds | Field | Whose number |
| --- | --- | --- | --- |
| 1:02:31 | 3751 | **`icu_recording_time`** | the watch's Total time, exactly |
| 53:46 | 3226 | `moving_time` | intervals.icu's own, recomputed from the stream |
| 51:14.8 | 3074.8 | **no field** — but recoverable | the watch's own Duration |

`elapsed_time` was 3752 and the interval row said 3753, so of the three near-identical totals it is
`icu_recording_time` that matches the watch.

**The watch's own duration is recoverable without downloading a FIT file**, which is the finding
that matters most here. `average_speed` is anchored to it:

```
distance / average_speed = 9520 / 3.096 = 3074.9 s = 51:14.9   ← the watch's Duration
distance / pace          = 9520 / 2.951 = 3226 s   = 53:46     ← moving_time
```

Two speeds for the same run, both in m/s despite one being called `pace`. They disagree by the
151 s intervals.icu adds when it recomputes moving time. The app therefore leads with the watch's
figures — the ones a runner recognises from their wrist — and keeps intervals.icu's beside them.

`average_cadence` is likewise not what it looks like: **81.228 is cycles per minute, one leg.**
`distance / (cadence × 2 × minutes)` reproduces the response's own `average_stride` of 1.0899 m
exactly, where `× 1` does not, so the runner's figure is about 162 spm and the app doubles it at
display.

`icu_intensity` came back as **77.13892** — already a percentage, not a fraction, which settles the
open question below for this account at least.

### Two fields the specification does not describe

Neither is a small detail, and neither is documented anywhere — not in the schema, which carries no
`description` for either, and not in the integration cookbook or the forum.

**`distance` versus `icu_distance`.** Both are `number/float`, and nothing says how they differ.
The client requests **both** and the mapper prefers `icu_distance`, falling back to `distance` — a
stated preference with a fallback, rather than a choice dressed up as documented fact.

**`icu_intensity`'s scale.** A service of this kind reports intensity either as a fraction of
threshold (`0.78`) or as a percentage (`78`), and the schema does not say which. The value is
therefore **stored raw** and normalised only where it is displayed
(`CompletedRunMetrics.intensityPercent`): at or below 3.0 it is read as a fraction and scaled,
above that it is already a percentage. The bound is what makes that safe rather than a coin flip —
a session at 300 % of threshold and a *fraction* above 3.0 are both impossible, so no real value is
ambiguous, and either reading lands on the number intervals.icu's own interface shows. Keeping the
raw value in the database means that if this reading is ever proved wrong it is one function to
correct and no stored data to migrate.

### Measured, not assumed

An unauthenticated request and one with a wrong key were both fired at the real service on
2026-08-15. **Both answer `401`** — the service does not distinguish "no credentials" from "bad
credentials", so the app's message for that case has to cover both, and it does.

### Endpoints used

| Endpoint | For |
| --- | --- |
| `GET /api/v1/athlete/0/activities` | the sync, with `fields` naming eighteen columns |
| `GET /api/v1/athlete/0/activities` | the raw-data screen, with **no** `fields`, so all 183 arrive |
| `GET /api/v1/activity/{id}` | one activity in full on the raw-data screen, `intervals=true` |

### Re-reading the history

`IntervalsRepository.backfill` walks backwards a year at a time and stores what comes back, and
exists because **adding a column does not fill it**: the ordinary sync looks back a fortnight, so
every activity older than that keeps a null in each column added since it was first stored. That
happened at v8, v9 and v10 in turn.

A year per request rather than one span, because the endpoint has no pagination and the whole
history would arrive as a single array. It stops after **two consecutive empty years** — one is a
season off, two is the end of the history — with a twenty-year backstop in the spirit of the page
cap on the client. It is safe to repeat: rows are keyed on the service's own activity id, the same
property the overlapping sync window relies on.

This is the reason the app does **not** store raw JSON for every activity against some future need.
intervals.icu is the system of record and this app is a cache; when a field turns out to be wanted,
the answer is a column and a backfill, not a hoard. Storing all 183 fields would also put weight,
resting heart rate and threshold heart rate in the database with nothing reading them, which is
exactly what [PRIVACY.md](PRIVACY.md) exists to prevent.

### The raw-data screen

`data/intervals/IntervalsClient.fetchRaw` returns the response body as text and never decodes it.
What reaches the screen is the bytes the server sent, with whitespace inserted for readability by
`prettyPrintJson` — which only ever *inserts* whitespace, and deliberately does not go through
`JSONObject`, whose re-parse would reorder keys, reformat numbers and drop duplicate keys. All
three would corrupt exactly what the screen exists to show.

The `Authorization` header is attached inside the client and recorded nowhere: the displayed
request line is built from the URL's path and query, neither of which can carry the credential.

### Missing data

The same rule as Oura, for the same reason: a treadmill run may have no distance, a run without a
strap has no heart rate, and a flat run reports `0.0` elevation. None of those is rendered, and none
becomes a zero. `IntervalsMappers` drops rows lacking an id, a type, a parseable start or a moving
time — those cannot be placed on the clock or reduced to a pace, which is all these rows exist for.
