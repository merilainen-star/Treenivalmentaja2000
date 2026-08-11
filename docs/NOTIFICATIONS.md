# Notifications

*(Status: **implemented**. `RescheduleAlarmsUseCase` keeps a 7-day sliding window of alarms,
`BootReceiver` re-arms them after a reboot, a reinstall or a timezone change, and Settings
checks the notification permission. Nothing Oura reports changes a notification: the recovery
reading is shown on screen and acts on nothing, deliberately — see
[TRAINING_ENGINE.md](TRAINING_ENGINE.md).)*

## Overview
The app replaces static calendar events with dynamic, actionable Android notifications using `AlarmManager`.

## Alarm Scheduling
- `AlarmManager.setExactAndAllowWhileIdle` is used to ensure precise delivery of training reminders.
- A Use Case (`ScheduleAlarmsUseCase`) observes the Room database. Whenever the plan changes, old alarms are canceled and new ones are scheduled.

## Notification Channels
- **Training Reminders (High Priority):** For immediate workout start times.
- **Daily Summaries (Default Priority):** Morning readiness and plan overview.

## Notification Actions
Notifications feature actionable buttons via PendingIntents:
- **"Start Now"** - Opens the app to the session screen.
- **"Snooze"** - Delays the alarm by 1 hour.
- **"Skip"** - Marks the session as skipped in the database.

## Permission Handling
- Requests `POST_NOTIFICATIONS` permission on Android 13+.
- Requests `SCHEDULE_EXACT_ALARM` if required by the OS.

## OS Lifecycle Events
- **Reboot Restoration:** A `BroadcastReceiver` listens for `ACTION_BOOT_COMPLETED` to reschedule all alarms from the Room database.
- **Timezone/DST Changes:** Listens for `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` to recalculate absolute Unix timestamps for alarms based on the local time definition.

## Deep Links
Notifications use deep links (e.g., `treenivalmentaja://session/{id}`) to route the user directly to the correct screen via Jetpack Navigation Compose.

## Ratkaisujärjestys ja Ajastus

1. `session.reminderOverride` — käyttäjän per-sessio-säätö
2. `session.scheduledTime`, **jos** `session.timeIsFixed` → miinus `settings.reminderOffsetMin`
3. lajikohtainen oletus asetuksista (`RUNNING`/`STRENGTH`/`SKIING`)
4. globaali fallback (18:00)

Uudelleenlaskennan liipaisimet (`RescheduleAlarmsUseCase`):
- Suunnitelma tuodaan
- Ilmoitusasetus muuttuu
- Käyttäjä säätää yhden session muistutusta
- Sessio siirretään toiselle päivälle
- ACTION_BOOT_COMPLETED / ACTION_TIMEZONE_CHANGED

Kolme reunatapausta:
a) Viikkonäkymän järjestys seuraa muistutusaikaa (remindAtUtc ASC).
b) `NOTIFIED`-sessiota (tai pitemmälle edenneitä) ei ajasteta uudelleen.
c) Muistutusajan muutos ei ole tilasiirtymä, eikä se kirjoita `session_events`-rivimäärään.
