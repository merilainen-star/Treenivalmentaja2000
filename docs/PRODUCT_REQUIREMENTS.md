# Product Requirements

## User Problem
Athletes and fitness enthusiasts often struggle to adapt their rigid training plans when life gets in the way (e.g., illness, fatigue, or missed sessions). Standard calendar events lack flexibility and context.

## Target User
Individuals following a progressive training programme who want an adaptive, context-aware training assistant rather than a static calendar.

## Product Goals
- Replace static calendar events with actionable Android training notifications.
- Provide a flexible engine to easily complete, skip, reschedule, or lighten workouts.
- Automatically adapt the training plan based on missed sessions or illness.
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
- Sync data from Oura API V2.
- Match imported Oura workouts to planned sessions.
- Import/Export training plans (JSON Treenivalmentaja Schema v1).
- Generate exact Android notifications via AlarmManager.

## Non-Functional Requirements
- **Offline First:** All core scheduling, rescheduling, and notifications must work offline.
- **Privacy:** Client secrets and AI API keys must not be stored in the app.
- **Performance:** UI must be responsive, relying on local Room database.
- **Localization:** Finnish is the default UI language. Timezone defaults to Europe/Helsinki.

## MVP Scope
- UI for Today and Week views (Implemented with Mock Data).
- Local deterministic rule engine for rescheduling (Planned).
- Manual completion/skipping of workouts (Partially implemented in memory).
- Local Room storage (Planned).
- Android AlarmManager notifications (Planned).

## Excluded Scope (Out of MVP)
- Remote AI advisor (Planned for future).
- Complex analytics dashboards.
- Direct integration with Garmin/Polar (reliant on Oura/Strava pass-through).

## Acceptance Criteria
- App compiles and runs without crashes.
- User can view a sequence of workouts.
- Skipping or shifting a workout updates the schedule deterministically.
- Notifications fire at the specified time.
- Oura sync runs successfully in the background.

## Future Features
- Remote AI-based training plan adjustments based on continuous biometric feedback.
- Strava integration extension point.
