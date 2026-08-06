import re
import json

def parse_ics(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Unfold lines
    content = re.sub(r'\n[ \t]', '', content)
    
    events = []
    current_event = {}
    in_event = False
    
    for line in content.splitlines():
        if line.startswith('BEGIN:VEVENT'):
            in_event = True
            current_event = {}
        elif line.startswith('END:VEVENT'):
            in_event = False
            events.append(current_event)
        elif in_event:
            if line.startswith('UID:'):
                current_event['UID'] = line[4:]
            elif line.startswith('SUMMARY:'):
                current_event['SUMMARY'] = line[8:]
            elif line.startswith('DESCRIPTION:'):
                current_event['DESCRIPTION'] = line[12:].replace('\\,', ',').replace('\\n', '\n')
            elif line.startswith('DTSTART;TZID='):
                val = line.split(':')[1]
                current_event['DTSTART'] = val
            elif line.startswith('DTEND;TZID='):
                val = line.split(':')[1]
                current_event['DTEND'] = val

    return events

events = parse_ics('treeniohjelma_16-7-2026_kaikki.ics')
print(events[0])
