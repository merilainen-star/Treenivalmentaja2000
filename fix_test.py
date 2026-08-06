import urllib.request
url = "https://raw.githubusercontent.com/robolectric/robolectric/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowAlarmManager.java"
req = urllib.request.Request(url)
with urllib.request.urlopen(req) as response:
    content = response.read().decode('utf-8')
    print("Found nextScheduledAlarm:")
    for line in content.split('\n'):
        if "nextScheduledAlarm" in line or "peekNextScheduledAlarm" in line:
            print(line)
