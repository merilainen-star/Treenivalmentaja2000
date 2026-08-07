import re
import json
from datetime import datetime, timedelta

def parse_ics(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

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
                current_event['DESCRIPTION'] = line[12:].replace('\\,', ',')
            elif line.startswith('DTSTART;TZID='):
                val = line.split(':')[1]
                current_event['DTSTART'] = val
            elif line.startswith('DTEND;TZID='):
                val = line.split(':')[1]
                current_event['DTEND'] = val

    return events

events = parse_ics('treeniohjelma_16-7-2026_kaikki.ics')

def parse_date(date_str):
    return datetime.strptime(date_str, '%Y%m%dT%H%M%S')

def extract_week(desc):
    match = re.search(r'Viikko (\d+)/', desc)
    if match:
        return int(match.group(1))
    return None

def extract_rounds(desc):
    match = re.search(r'(\d+)\s*(?:-|–)?\s*(\d+)?\s*kierros', desc.lower())
    if match:
        min_r = int(match.group(1))
        max_r = int(match.group(2)) if match.group(2) else min_r
        return min_r, max_r
    return None, None

def extract_duration(desc):
    match = re.search(r'(\d+)\s*s', desc.lower())
    if match:
        return int(match.group(1))
    return None

def extract_reps(desc):
    match = re.search(r'(\d+)\s*(?:-|–)\s*(\d+)', desc)
    if match:
        return int(match.group(1)), int(match.group(2))
    
    match2 = re.search(r'(\d+)', desc)
    if match2:
        return int(match2.group(1)), int(match2.group(1))
    return None, None

def parse_exercises(exercise_sentence):
    """Split a comma-separated movement sentence into exercise objects.

    A fragment that yields neither reps nor a duration is prose, not a movement, and is
    dropped. The schema requires one or the other (docs/PLAN_SCHEMA.md), and an exercise
    carrying only a name says nothing anyway.
    """
    exercises = []
    if not exercise_sentence:
        return exercises
    parts = exercise_sentence.rstrip('.').split(',')
    for part in parts:
        part = part.strip()
        if not part: continue

        name = part
        reps_min, reps_max = None, None
        duration = None
        per_side = False

        if 'lankku' in part.lower() or 'venytys' in part.lower():
            duration = extract_duration(part)
        else:
            reps_min, reps_max = extract_reps(part)

        if reps_min is None and duration is None:
            continue

        if '/puoli' in part.lower() or '/jalka' in part.lower():
            per_side = True

        ex = {
            "name": name,
        }
        if reps_min is not None:
            ex["repsMin"] = reps_min
            # `!=`, not `is not`: identity holds for small ints by interning only.
            if reps_max != reps_min:
                ex["repsMax"] = reps_max
            ex["reps"] = reps_min
        if duration is not None:
            ex["durationSec"] = duration
        if per_side:
            ex["perSide"] = True

        exercises.append(ex)
    return exercises

weeks = {}

for i, ev in enumerate(events):
    dtstart = parse_date(ev['DTSTART'])
    dtend = parse_date(ev['DTEND'])
    duration_min = int((dtend - dtstart).total_seconds() / 60)
    
    date_str = dtstart.strftime('%Y-%m-%d')
    time_str = dtstart.strftime('%H:%M')
    
    desc = ev.get('DESCRIPTION', '')
    week_num = extract_week(desc) or 1
    
    rounds_min, rounds_max = extract_rounds(desc)
    
    summary = ev.get('SUMMARY', '')
    session_type = "RUNNING" if "juoksu" in summary.lower() or "vetoja" in summary.lower() else "STRENGTH"

    # Only strength sessions list their movements comma-separated. On a run the one sentence
    # with a comma is ordinary prose — "Pidä vauhti sellaisena, että pystyt puhumaan." — and
    # splitting it produced two nameless "exercises" that the importer rightly rejected.
    exercises = []
    if session_type == "STRENGTH":
        sentences = [s.strip() for s in re.split(r'(?<=\.)\s+|\n+', desc) if s.strip()]
        exercise_sentence = None
        for s in sentences:
            if s.count(',') > 0 and 'kierro' not in s.lower() and 'tauko' not in s.lower():
                exercise_sentence = s
                break
        exercises = parse_exercises(exercise_sentence)

    # Clean description
    clean_desc = desc

    session = {
        "id": f"s-{i}",
        "type": session_type,
        "date": date_str,
        "time": time_str,
        "durationMin": duration_min,
        "description": clean_desc,
    }
    if exercises:
        session["exercises"] = exercises
    if rounds_min:
        session["roundsMin"] = rounds_min
        session["roundsMax"] = rounds_max
        session["rounds"] = rounds_min
        
    if week_num not in weeks:
        weeks[week_num] = []
    weeks[week_num].append(session)

plan = {
    "schemaVersion": 1,
    "plan": {
        "id": "plan-kesa-2026",
        "name": "Mikon voimaharjoittelu",
        "timeZone": "Europe/Helsinki",
        "startDate": "2026-07-16",
        "description": "8 viikon harjoitusohjelma",
        "author": "Valmentaja"
    },
    "weeks": []
}

for wk in sorted(weeks.keys()):
    plan["weeks"].append({
        "weekNumber": wk,
        "sessions": weeks[wk]
    })

with open('plan.json', 'w') as f:
    json.dump(plan, f, indent=2, ensure_ascii=False)

print("Created plan.json")
