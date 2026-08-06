import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/importer/PlanValidator.kt", "r") as f:
    content = f.read()

# Replace time validation logic
time_logic = """    val timeIsFixed = session.timeIsFixed ?: false
    var time: java.time.LocalTime? = null
    if (session.time != null) {
      time = resolveTime(session.time, "$path.time", errors)
      if (time == null) usable = false
    } else if (timeIsFixed) {
      errors += ImportError("$path.time", "kellonaika puuttuu, vaikka timeIsFixed on true")
      usable = false
    }"""

content = re.sub(r'    val time = resolveTime\(session\.time, "\$path\.time", errors\)\n    if \(time == null\) usable = false', time_logic, content)

# Change the condition at the end of validateSession
end_cond = """    if (!usable || id == null || type == null || date == null || zone == null) {
      return null
    }
    
    // We set remindAtUtc based on time only if provided for now, but RescheduleAlarmsUseCase handles it fully.
    // For import, we just map it out.
    // Wait, the domain model now requires remindAtUtc. We should resolve it properly or use a placeholder.
    // No, wait, if time is null, what is the placeholder for remindAtUtc in PlanValidator? 
    // PlanValidator doesn't have access to settings. Let's just default to 18:00 for the initial mapping, 
    // or RescheduleAlarmsUseCase will overwrite it immediately after import anyway.
    val resolvedTime = time ?: java.time.LocalTime.of(18, 0)"""

content = re.sub(r'    if \(\!usable \|\| id == null \|\| type == null \|\| date == null \|\| time == null \|\| zone == null\) \{\n      return null\n    \}', end_cond, content)

# Also update the TrainingSession instantiation
training_session_args = """      scheduledTime = time?.format(TIME_FORMAT),
      remindAtUtc = ZonedDateTime.of(date, resolvedTime, zone).toInstant().toEpochMilli(),
      timeIsFixed = timeIsFixed,
      reminderOverride = null,"""

content = re.sub(r'      scheduledTime = time.format\(TIME_FORMAT\),\n      scheduledAtUtc = ZonedDateTime.of\(date, time, zone\).toInstant\(\).toEpochMilli\(\),', training_session_args, content)

# Also update resolveTime to not be required if called. Wait, resolveTime checks `if (raw.isNullOrBlank())`.
# Since we only call it if `session.time != null`, it might still be blank. That's fine, it will return null and add error.

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/importer/PlanValidator.kt", "w") as f:
    f.write(content)
