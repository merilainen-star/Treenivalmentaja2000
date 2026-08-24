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
- **Sovelluksen päivitykset (Default Priority):** one notice after the app has been replaced —
  its own channel so that muting training reminders does not silently mute it, and so that it
  cannot make a sound the reminder channel's settings were meant to govern.
- **Daily Summaries (Default Priority):** Morning readiness and plan overview.

### After an update: a notice, never an automatic restart
Installing an update kills the process being replaced — that is what an update is — and Android
then refuses to let the new one start an activity: background activity launches have been blocked
since Android 10, and a receiver handling `ACTION_MY_PACKAGE_REPLACED` is on none of the exemption
lists. Calling `startActivity` there would fail silently on every phone this app runs on, and the
code would read as though the app relaunched itself. `BootReceiver` therefore posts a notification
(`data/update/UpdateInstalledNotification.kt`) that names the installed version and opens the app
when tapped. `UpdateInstalledNotificationTest` holds the boundary: the notice appears for
`ACTION_MY_PACKAGE_REPLACED` and for neither of the other two actions the receiver handles.

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
