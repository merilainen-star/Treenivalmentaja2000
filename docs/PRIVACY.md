# Privacy Policy — Treenivalmentaja

**Last updated: 17 August 2026** *(revised: an optional **AI analysis** feature. When you tap
"AI-analyysi" on a workout, that workout's data and your recent recovery readings are sent to the
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

**One exception, and it only happens when you ask for it.** If you set up the optional AI analysis
and tap "AI-analyysi" on a workout, that workout's numbers and your recent recovery readings are
sent to **one** AI provider — whichever you picked in Settings — to be commented on. Nothing is sent
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
  moving and elapsed time, distance, heart rate, elevation gain, calories and training load; plus
  your **daily fitness and fatigue figures** (CTL, ATL) for those days. Stored so the app works
  offline. The app does **not** read the heart-rate, resting-heart-rate or sleep fields that
  intervals.icu also holds — Oura is the source for those.
- **Your Oura tokens and Oura application's Client ID and Secret, your intervals.icu API key, and
  any AI provider API keys you have entered (Anthropic, OpenAI, Google)** — encrypted with
  AES-256-GCM under keys held in the Android Keystore, which cannot be extracted from the device.
  Each service has its own file and its own key, so removing one cannot affect another. All are
  excluded from Android cloud backup and device transfer. The intervals.icu and AI provider keys are
  never redisplayed in the app once saved, and are never written to a log.
- **AI analyses are not stored at all.** An analysis lives on screen until you close it or leave the
  screen, and is never written to the database. There is no history of them.
- **App settings**, such as reminder times.

All of it is stored inside the app's private storage, protected by the Android application sandbox.

## What leaves your device, and where it goes

The app makes network requests to exactly eight places, and to nobody else — and the three AI
providers are contacted **only** when you tap the button, and only the one you selected:

| Destination | What is sent | Why |
| --- | --- | --- |
| `api.ouraring.com`, `cloud.ouraring.com` | Your Oura credentials and tokens; requests for date ranges | To sign in to Oura and read your own Oura data |
| `intervals.icu` | Your intervals.icu API key; requests for date ranges | To read your own activities, which arrive there from your Suunto watch |
| `api.anthropic.com` | **Only when you tap "AI-analyysi" with Claude selected:** your Anthropic key, one workout's details, and about a week of recovery readings | To get a coach's comment on that workout |
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

It does **not** send your name, your email, your Oura or intervals.icu credentials, your training
plan as a whole, or any workout other than the one you tapped. Readings the app does not have are
simply left out — nothing is filled in with a placeholder.

**You can see the request.** Every analysis has a "Näytä pyyntö" control that shows the exact text
that was sent, character for character. Nothing is sent that you cannot read afterwards.

**The provider's handling of what it receives** is governed by their own terms and privacy policy
and your account settings with them, not by this policy. If you would rather not send health data to
a third party at all, enter no AI key — the feature is then invisible and sends nothing.

**One provider difference is worth stating outright.** Google's Gemini has a free tier and a paid
tier, and they treat your data differently: on the free tier submitted content may be used to
improve Google's products, while the paid tier states it is not. Because these requests carry health
measurements, **this app is used with the paid Gemini tier**, and the Settings hint says so at the
point the key is pasted. That is what keeps the "no use of your data for training machine-learning
models" statement below true.

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

The app makes **one kind of request**: a list of your own activities between two dates. It names
the fifteen fields it uses, of the 183 the API offers, so nothing else is even sent.

It **never writes anything** to intervals.icu — no activity is created, edited, uploaded or
deleted, and no note, plan or calendar entry is posted. It does not read your profile, your athlete
settings, other athletes, or anything belonging to anyone else.

A personal API key is used rather than OAuth, because this app is used by one person for their own
account; see [INTERVALS_SETUP.md](INTERVALS_SETUP.md#why-an-api-key-rather-than-oauth).

## What the app does not do

- No analytics, telemetry, crash reporting or advertising. There are no such SDKs in the build.
- No account, no sign-up, no user profile on any server.
- No selling, sharing, renting or transferring of data to anyone.
- No use of your data for training machine-learning models.
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
- **Uninstalling the app** removes everything it has stored.

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
