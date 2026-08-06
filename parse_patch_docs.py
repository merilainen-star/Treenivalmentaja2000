import re

# DATA_MODEL.md
with open("app/applet/docs/DATA_MODEL.md", "r") as f:
    data_model = f.read()
data_model = data_model.replace("`scheduledAtUtc`", "`remindAtUtc`")
data_model = data_model.replace("`scheduledTime` | `string` | Local time, e.g. `07:00`", "`scheduledTime` | `string?` | Local time, e.g. `07:00`")
# We just append to section 2
idx = data_model.find("### 3.")
if idx != -1:
    data_model = data_model[:idx] + """
#### Reminder Resolution (`remindAtUtc`)
`remindAtUtc` is a derived value, not a frozen snapshot from import. It dictates when the AlarmManager fires and controls the display order in the UI. It is recalculated automatically when:
- A plan is imported.
- Notification defaults are changed.
- A user overrides a specific session's time.

The order of priority is:
1. `reminderOverride` (user's manual adjustment for a session)
2. `scheduledTime` minus offset (if `timeIsFixed` is true)
3. Default notification setting for the `WorkoutType`
4. Fallback (18:00)

""" + data_model[idx:]
with open("app/applet/docs/DATA_MODEL.md", "w") as f:
    f.write(data_model)

# NOTIFICATIONS.md
with open("app/applet/docs/NOTIFICATIONS.md", "a") as f:
    f.write("""
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
""")

# ARCHITECTURE.md
with open("app/applet/docs/ARCHITECTURE.md", "a") as f:
    f.write("""
### Notification Scheduling Flow
`RescheduleAlarmsUseCase` computes the correct `remindAtUtc` for `PLANNED` sessions based on `ResolveReminderUseCase` and updates the database, ensuring AlarmManager fires at the exact desired times.
""")

# CHANGELOG.md
with open("app/applet/CHANGELOG.md", "a") as f:
    f.write("""
## [Unreleased]
- **Changed**: Erotettiin treenin suoritusaika ja muistutusaika toisistaan.
- **Added**: `timeIsFixed` ja valinnainen `time` JSON-skeemaan v1.
- **Added**: Room-migraatio versioon 2, jossa lisättiin `remindAtUtc`, `timeIsFixed`, `reminderOverride`.
- **Added**: `NotificationSettingsStore` (Datastore) lajikohtaisille hälytysasetuksille.
""")

# PROJECT_STATUS.md
with open("app/applet/PROJECT_STATUS.md", "a") as f:
    f.write("""
## Nykyinen tila
- Suoritusaika ja muistutusaika erotettu (Migraatio versioon 2).
- Datastore asetuksille lisätty.
""")
