# Notifications

*(Note: Notification scheduling is **planned**).*

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
