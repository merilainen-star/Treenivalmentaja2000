# Intervals.icu setup

How the Suunto watch's own recordings reach this app, and the one thing you do to enable it.

## The data flow

```
Suunto watch  →  Suunto App  →  intervals.icu  →  intervals.icu API  →  Treenivalmentaja
```

The first two arrows are already set up on the account this app is built for, and this app has
nothing to do with them: the Suunto connection lives in intervals.icu's own settings. What this app
does is the last arrow — read the activities that have arrived there.

**Strava is not involved and no longer needs to be.** Strava paywalled its API in June 2026,
requiring an active subscription for developer access. That is what prompted this route; the app
carried a working Strava integration for about a day and it has been removed rather than left to
rot. See [ROADMAP.md](ROADMAP.md).

## What you do, once

1. Open <https://intervals.icu> and sign in. A phone browser is fine.
2. Go to **Settings** and scroll to **Developer Settings**.
3. Copy the **API Key**.
4. On the phone: **Asetukset → Intervals.icu** → paste the key → *Tallenna avain*.

The app then tests the key immediately and says whether it worked. There is also a **Testaa
yhteys** button under the key for checking it again later.

**Finding zero activities is a pass, not a failure.** The test asks for one activity from the last
year; an empty answer means the key authenticated and the account simply had nothing to show, and
the card says exactly that rather than implying the key is broken.

## Why an API key rather than OAuth

intervals.icu supports both. This app is used by one person, for their own account, and OAuth would
add a browser round trip, an exported callback activity, a `state` parameter to validate and a
refresh token that must not be spent twice — four moving parts serving one person's own key. The
specification's own words for the simpler route are "Username is API_KEY, Password is your API key
found in /settings", and that is what the client sends.

The key is stored the way the Oura credentials are: encrypted with AES-256-GCM under a key held in
the Android Keystore, which cannot leave the device, and excluded from cloud backup and device
transfer. It is never written to a log, and once saved it is never redisplayed — the card says only
whether a key is stored.

## What the app reads, and what happens next

Opening the Tänään or Viikko screen fetches the last two weeks of activities. For each one the app
keeps eighteen fields — sport, start, moving and elapsed time, distance, heart rate, cadence,
elevation, calories, and intervals.icu's own training load and intensity — out of the 183 the API
offers, named explicitly in the request so the rest are never sent.

An activity lands under the planned session nearest it in time on the same day, through the **same**
matcher Oura's workouts go through: the sport has to fit, so a `Run` can claim a running session and
a `Walk` claims nothing. A matched session shows a "Kello:" line with pace, time, distance and heart
rate, and below it what the body did and what the session cost.

The three numbers on that last line — calories, training load and intensity — are the ones that make
an *easy* 5 km distinguishable from a hard one of the same distance and duration. Nothing acts on
that yet; it is captured so that something can.

**A match does not complete a session.** Whether a session counts as done is your statement about
your own training, not something a watch recording decides — see
[TRAINING_ENGINE.md](TRAINING_ENGINE.md).

Both an Oura line and a watch line can appear on one session. That is deliberate: the ring and the
watch recorded the same run, and merging them would hide which device said what.

## Notes

- **Re-fetching is safe.** Every sync covers a window that overlaps the last one, because an
  activity can reach intervals.icu late. Rows are keyed on intervals.icu's own activity id, so a
  re-fetched activity rewrites its own row instead of appearing twice. Nothing compares start times
  or distances to guess whether two records are the same activity.
- **Rate limits.** The app spends one request per sync. If intervals.icu ever answers `429` with a
  `Retry-After`, the app carries that number rather than inventing one.
- **Removing the key** (*Poista avain*) deletes it and the cached activities from the phone. Your
  training plan is untouched.
- Activities uploaded by hand are read too — the app records where each came from (`SUUNTO`,
  `MANUAL`, …) but never filters on it. A run you uploaded yourself is still that run.
- See [PRIVACY.md](PRIVACY.md) for what leaves the device, and
  [API_INTEGRATIONS.md](API_INTEGRATIONS.md) § Intervals.icu for the endpoint itself.
