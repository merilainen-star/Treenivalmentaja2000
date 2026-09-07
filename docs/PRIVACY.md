# Privacy Policy — Treenivalmentaja

**Last updated: 7 September 2026** — clarified whole-programme reports, next-programme generation,
standing constraints and deletion controls.

Previous revision, 24 August 2026: *(revised: AI plan proposals can send the open schedule and the
standing constraints entered in Settings to the selected AI provider, only after an explicit tap.
Unaccepted proposals are not stored; accepted typed operations are recorded in the local event
log. Automatic Android backup and device transfer are
disabled for the entire app, including the Room training/health database, settings and all
credentials. In the optional **AI analysis** feature, data is sent only when you tap
"AI-analyysi" on a workout: that workout's data and your recent recovery readings go to the
**AI provider you selected** — Anthropic, OpenAI or Google. This is the first time any health data
leaves your device for anything other than the service it came from, so read "What leaves your
device" below. The same revision starts reading your **nightly HRV and resting heart rate** from
Oura. Previously revised on 15 August 2026 to read activities from intervals.icu, and on
10 August 2026 to add the Oura `heartrate` scope.)*

Treenivalmentaja is a private, single-user Android training-companion app. It is built and installed
by its author for their own use and is not distributed through any app store. This policy describes
what the app does with data, and it is written to describe the actual implementation rather than to
reserve rights the app does not exercise. The source code is public at
<https://github.com/merilainen-star/Treenivalmentaja2000>, so every claim below can be checked.

## The short version

The app has **no server of its own**. Nothing you enter, and nothing it reads from Oura, is sent to
the author or to any analytics service. Data lives on your phone until you delete it.

**AI requests are the exception, and happen only when you ask for them.** A workout analysis sends
that workout and recent recovery. A plan proposal sends the open schedule and constraints; an
interim or final report sends a whole-programme summary with completed runs and recovery trends;
next-programme generation sends the previous programme's summary, goals, feedback, standing
constraints and any open final report. These go to **one** provider, selected in Settings. Nothing is sent
unless you tap the button; the app shows you the exact text it sent; and if you enter no API key the
feature does nothing at all. Only the selected provider is ever contacted: the app does not ask two
of them, and a key stored for a provider you are not using is never sent anywhere.

## What the app stores on your device

- **Your training plan** — sessions, dates, statuses, and a log of changes to them. Imported by you
  from a JSON file or the clipboard.
- **Oura data, cached** — daily readiness, sleep and activity scores; the night's average heart-rate
  variability, lowest heart rate and average heart rate; and completed workouts, for the days the
  app has fetched. Stored so the app works offline.
- **Activity data from intervals.icu, cached** — your activities for the days the app has fetched: sport, start time,
  moving and elapsed time, distance, heart rate, elevation gain, calories and training load; your
  **heart-rate zone table and the time you spent in each zone** on those activities; **each run
  broken into kilometres** — pace, average heart rate and climb per kilometre, computed on the
  device from the recording intervals.icu holds; plus
  your **daily fitness and fatigue figures** (CTL, ATL) for those days. Stored so the app works
  offline. The app does **not** read the heart-rate, resting-heart-rate or sleep fields that
  intervals.icu also holds — Oura is the source for those. The second-by-second recording a run's
  kilometres are computed from is **not** kept: it is read once, reduced to those few lines per
  run, and discarded.
- **Your Oura tokens and Oura application's Client ID and Secret, your intervals.icu API key, and
  any AI provider API keys you have entered (Anthropic, OpenAI, Google)** — encrypted with
  AES-256-GCM under keys held in the Android Keystore, which cannot be extracted from the device.
  Each service has its own file and its own key, so removing one cannot affect another. All are
  excluded from Android cloud backup and device transfer. The intervals.icu and AI provider keys are
  never redisplayed in the app once saved, and are never written to a log.
- **AI analyses and unaccepted plan proposals are not stored at all.** They live on screen until
  you close it or leave the screen. Accepted proposal effects are ordinary local plan changes with
  `AI_ADVISOR` audit events; the provider's prose is not stored.
- **AI advisor constraints**, such as "long runs only on weekends", are stored locally in the
  settings DataStore and sent when you request a plan proposal or a new programme.
- **App settings**, such as reminder times.

All of it is stored inside the app's private storage, protected by the Android application sandbox.
The app opts out of Android cloud backup and device-to-device transfer. Moving to a new device
therefore requires setting up the app and importing a plan again; health caches and settings are
not restored silently. The Room database is not encrypted with SQLCipher: for this private app the
chosen boundary is Android's app sandbox plus the complete backup opt-out.

## What leaves your device, and where it goes

The app makes network requests to exactly eight places, and to nobody else — and the three AI
providers are contacted **only** when you tap the button, and only the one you selected:

| Destination | What is sent | Why |
| --- | --- | --- |
| `api.ouraring.com`, `cloud.ouraring.com` | Your Oura credentials and tokens; requests for date ranges | To sign in to Oura and read your own Oura data |
| `intervals.icu` | Your intervals.icu API key; requests for date ranges | To read your own activities, which arrive there from your Suunto watch |
| `api.anthropic.com` | **Only after an explicit AI action with Claude selected:** your Anthropic key and the context for the requested workout analysis, plan proposal, whole-programme report or new programme (detailed below) | To answer the AI action you requested |
| `api.openai.com` | The same, when ChatGPT is selected | The same |
| `generativelanguage.googleapis.com` | The same, when Gemini is selected | The same |
| `oss.exercisedb.dev` (ExerciseDB) | The name or catalogue id of an exercise in your plan | To show an animation and instructions when you tap a movement |
| `wger.de` | The same | The same, for movements ExerciseDB does not have |
| `api.github.com` / `github.com` | Nothing about you — only a request for the latest release metadata | To tell you whether your installed build is the current one |

**No health data is ever sent to the exercise-guide sources or to GitHub.** They receive only an
exercise name such as "plank". Nothing fetched from the exercise-guide sources is stored: there is
no disk cache for it, and the in-memory cache is discarded when the app closes.

### What the AI analysis sends, exactly

This is the only feature that sends health data somewhere it did not come from, so it is worth being
precise. One tap sends **one** request containing:

- The workout in question: its date, sport, planned duration, planned intensity and description.
- What was recorded for it, if anything: duration, distance, heart rate, calories, pace, training
  load and intensity.
- **About a week of recovery readings** around that date: readiness, sleep and activity scores, and
  the nightly HRV and resting heart rate.
- For an upcoming workout, your current acute and chronic training load.

This per-workout request does **not** add your account name, email, Oura or intervals.icu
credentials, or your whole plan. Your own descriptions and free text are sent as written, so avoid
putting information in them that you do not want the selected provider to receive. Readings the app does not have are
simply left out — nothing is filled in with a placeholder.

### What whole-programme reports and next-programme generation send

An explicit **Väliraportti** or **Loppuraportti** request sends the active programme's name, goals,
dates, adherence and weekly totals; completed runs with recorded duration, distance, pace, heart
rate, training load and available heart-rate zone distributions; strength and subjective RPE/feel summaries;
and recovery and performance trends. An interim report also describes the remaining programme.
This covers the programme, not just one workout or seven days. Raw event rows, second-by-second
recordings and per-movement timers are not included.

Requesting a **new programme** sends a summary of the previous programme and its ending level,
feedback, the chosen goal and length, requested start date and time zone, the standing constraints
from Settings and the open final report when available. Opening the goal form alone sends nothing.
The generated JSON is validated and previewed locally; replacing an existing plan requires a
separate confirmation that explicitly warns that its sessions and event history will be deleted.
Reports and unimported responses remain in memory; an accepted programme is stored as a plan.

### What an AI plan proposal sends

An explicit proposal request sends the target workout, the currently open plan sessions (ids,
dates, times, sport, intensity and duration), today's date and the standing constraints written in
Settings. A clarification answer is sent only after you type it and tap **Jatka**. The response is
accepted only as a clarification question or typed `MOVE`/`LIGHTEN` JSON; it remains read-only until
you tap **Hyväksy muutokset**.

**You can see the request.** Every analysis has a "Näytä pyyntö" control that shows the exact text
that was sent, character for character. Nothing is sent that you cannot read afterwards.

**The provider's handling of what it receives** is governed by their own terms and privacy policy
and your account settings with them, not by this policy. If you would rather not send health data to
a third party at all, enter no AI key — the feature is then invisible and sends nothing.

**One provider difference is worth stating outright.** Google's Gemini has a free tier and a paid
tier, and they treat your data differently: on the free tier submitted content may be used to
improve Google's products, while the paid tier states it is not. Because these requests carry health
measurements, **this app is used with the paid Gemini tier**, and the Settings hint says so at the
point the key is pasted. The app cannot verify your account tier or enforce a provider's handling of requests.

## What the app requests from Oura, and what it does not

The app asks Oura for three permission scopes:

- **Daily** — readiness, sleep and activity scores, **and the sleep periods themselves**. The scores
  are Oura's 0–100 summaries of a night; the sleep periods are the night's own measurements, of
  which the app reads three: average heart-rate variability, lowest heart rate (the resting figure)
  and average heart rate. It reads these because a score is Oura's opinion of a night relative to
  your own baseline, where the measurements are numbers that mean the same thing next season — which
  is what makes a trend readable. It does **not** read the per-night sample series behind them, the
  sleep-stage breakdown, or the movement classification.
- **Workout** — completed workouts, so they can be matched against planned sessions.
- **Heartrate** — the heart-rate time series, used **only** to compute an average and a maximum for
  a workout you actually did. Oura provides no heart rate on a workout itself, so there is no other
  way to show one. Samples are reduced to those two numbers, stored against that workout, and the
  series itself is not kept.

It does **not** request your email address, personal information (gender, age, height, weight),
tags, sessions, SpO2, ring configuration, stress, or heart-health data, even though the Oura API
offers them. Data that is not needed for scheduling training is not requested.

Adding a scope requires you to authorise it again: an existing connection keeps the permissions it
was granted with until you disconnect and reconnect.

## What the app reads from intervals.icu, and what it does not

The app reads your activities and daily wellness for date ranges, and fetches streams for running
kilometre splits. Activity fields are explicitly selected; wellness stores CTL/ATL, and streams
are reduced on the device to split rows and then discarded.

It **never writes anything** to intervals.icu — no activity is created, edited, uploaded or
deleted, and no note, plan or calendar entry is posted. It does not read your profile, your athlete
settings, other athletes, or anything belonging to anyone else.

A personal API key is used rather than OAuth, because this app is used by one person for their own
account; see [INTERVALS_SETUP.md](INTERVALS_SETUP.md#why-an-api-key-rather-than-oauth).

## What the app does not do

- No analytics, telemetry, crash reporting or advertising. There are no such SDKs in the build.
- No account, no sign-up, no user profile on any server.
- No selling or renting of data. User-requested AI transfers are described above.
- The app does not train models. Provider retention and training use depend on your provider
  agreement and account tier; the app cannot enforce those settings.
- No location tracking.

## Deleting your data

- **Settings → Oura → "Katkaise Oura-yhteys"** deletes the stored Oura tokens and every cached Oura
  row from the device. Your training plan is untouched.
- **Settings → Intervals.icu → "Poista avain"** deletes the stored API key, every cached activity
  and your cached daily fitness/fatigue figures from the device. Your training plan is untouched.
- **Settings → AI-analyysi → "Poista"** deletes one provider's stored key; each provider has its
  own. There is nothing else to delete: no analysis was ever stored. Removing the key of the
  selected provider stops the feature from being able to send anything.
- **"Vaihda tunnukset"** under Oura additionally deletes its stored Client ID and Secret.
- **Android Settings → Apps → Treenivalmentaja → Storage → Clear storage**, or **uninstalling the
  app**, removes its locally stored data. The app's own Settings has no complete-wipe button.
- **Palauta esimerkkidata** asks for confirmation and deletes plans and their session history;
  it does not clear service credentials or health caches.

The app cannot revoke its own access at Oura, because the Oura API publishes no revocation endpoint.
To withdraw the application's access to your Oura account, remove it in your Oura account settings.

An intervals.icu API key is not revoked from here either: deleting it removes this app's copy, and
the key itself is regenerated from intervals.icu's own Developer Settings if you want the old one
to stop working everywhere.

## Data held by Oura, intervals.icu and the AI providers

This policy covers only what Treenivalmentaja does. The data Oura, intervals.icu, Anthropic, OpenAI
and Google themselves hold about you is governed by their own privacy policies and your account
settings there.
The route your watch data takes into intervals.icu is a matter between those services and you; this
app only reads what has already arrived. What an AI provider does with an analysis request — how
long it is retained, whether it is used for anything else — is likewise governed by your agreement
with them, and is the reason the feature is off until you deliberately turn it on.

## Children

The app is not directed at children and is used only by its author.

## Changes

This file lives in the app's public repository; its history is the change log. Material changes will
be reflected in the "last updated" date above.

## Contact

merilainen@gmail.com
