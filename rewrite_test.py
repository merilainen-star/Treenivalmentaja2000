import re

with open("app/src/test/java/fi/merilainen/treenivalmentaja/domain/RescheduleAlarmsUseCaseTest.kt", "r") as f:
    content = f.read()

# Replace FakeNotificationSettingsStore with just using the real one
content = re.sub(r'class FakeNotificationSettingsStore.*?\n}\n\n', '', content, flags=re.DOTALL)
content = content.replace("FakeNotificationSettingsStore", "NotificationSettingsStore")
content = content.replace("settingsStore = FakeNotificationSettingsStore()", "settingsStore = NotificationSettingsStore(context)")

# Fix WorkoutSessionEntity constructor
content = content.replace("createdAt = now, ", "")
content = content.replace("distanceKm = null, ", "weekNumber = 1, distanceKm = null, ")

with open("app/src/test/java/fi/merilainen/treenivalmentaja/domain/RescheduleAlarmsUseCaseTest.kt", "w") as f:
    f.write(content)
