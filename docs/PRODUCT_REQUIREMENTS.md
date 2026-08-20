# Product Requirements

## User Problem
Athletes and fitness enthusiasts often struggle to adapt their rigid training plans when life gets in the way (e.g., illness, fatigue, or missed sessions). Standard calendar events lack flexibility and context.

## Target User
Individuals following a progressive training programme who want an adaptive, context-aware training assistant rather than a static calendar.

## Product Goals
- Replace static calendar events with actionable Android training notifications.
- Provide a flexible engine to easily complete, skip, reschedule, or lighten workouts.
- Propose deterministic adaptations for missed sessions and apply them only after user approval.
- Utilize physiological data (recovery, sleep, activity) to inform training decisions.

## Primary User Journeys
1. **Daily View:** User wakes up, checks the app, sees today's planned session and their current recovery state.
2. **Actionable Notification:** User receives a notification at the scheduled time, clicks it to view workout details, and later marks it as "Completed".
3. **Rescheduling:** User is too busy today and taps "Move to Tomorrow", pushing the remaining schedule back.
4. **Illness:** User marks themselves as sick, pausing the programme and initiating a return-to-play progression upon recovery.

## Functional Requirements
- Display current day's and week's training plan.
- Provide buttons to complete, skip, lighten, or reschedule a session.
- Handle illness mode (pause and gradual return).
- Sync data from Oura API V2 (implemented).
- Read the watch's own activities from intervals.icu, for the pace and load Oura does not carry
  (implemented — see [INTERVALS_SETUP.md](INTERVALS_SETUP.md)).
- Match imported Oura workouts to planned sessions (implemented — same day, nearest in time, and
  the activity must fit; a match attaches Oura's numbers and does not complete the session).
- Import training plans (JSON Treenivalmentaja Schema v1).
- Generate actionable inexact Android notifications via AlarmManager.

## Non-Functional Requirements
- **Offline First:** All core scheduling, rescheduling, and notifications must work offline.
- **Privacy:** No secret is committed to the repository, and none is compiled into a published
  build. The Oura client secret is **entered by the user and stored encrypted on the device** under
  an Android Keystore key — see
  [ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it).
  This requirement used to read "client secrets must not be stored in the app", which the shipped
  design contradicts; what it was protecting against was a secret in the source or in the APK, and
  that still holds.
- **Performance:** UI must be responsive, relying on local Room database.
- **Localization:** Finnish is the default UI language. Each plan carries its own IANA timezone;
  plan days, matching and missed-session decisions use it even while the device is travelling.

## MVP Scope
- UI for Today and Week views (implemented, backed by Room — the mock data is gone).
- Local deterministic rule engine for rescheduling (implemented in `TrainingEngine`).
- Manual completion/skipping of workouts (implemented, persisted in Room with an event log).
- Local Room storage (implemented, schema version 12).
- Android AlarmManager notifications (implemented).

## Excluded Scope (Out of MVP)
- AI-proposed plan changes (planned; read-only per-workout AI analysis is implemented).
- Complex analytics dashboards.
- Direct integration with Garmin/Polar (reliant on Oura and intervals.icu pass-through).

## Acceptance Criteria
- App compiles and runs without crashes.
- User can view a sequence of workouts.
- Skipping or shifting a workout updates the schedule deterministically.
- Notifications fire at the specified time.
- Oura sync runs successfully in the background (implemented: a daily WorkManager job, plus a fetch
  whenever the Today or week screen resumes).

## Future Features
- Remote AI-based training plan adjustments based on continuous biometric feedback.
- Further activity sources beyond Oura and intervals.icu.
