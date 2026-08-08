# Treenivalmentaja Training Plan Schema v1

The JSON format used to import a training plan into Treenivalmentaja, from a file or from the
clipboard. It is also the format the future AI advisor must produce for a proposal.

**This document is the source to write a plan against.** When a plan is drafted with AI
assistance, give the model this file and have it emit JSON directly. The ICS route
(`tools/parse_ics*.py`) exists only because the first plan arrived as a calendar export; it
infers structure from Finnish prose with regular expressions and gets it wrong in ways nobody
notices until the import fails. It is a legacy path, not the intended one.

- **`schemaVersion`: `1`** — a document without it, or with an unknown value, is rejected outright.
- Validation happens **before** anything is written to Room. A document is imported whole or not
  at all.
- All dates are local dates in the plan's `timeZone`, never UTC. Absolute alarm timestamps are
  derived at import time.
- **The dates in the file are not always where the plan should land.** Import asks: keep them, or
  move the whole plan so its first day is today. The second option shifts every session by one
  delta, so the spacing and the rest days survive, and recomputes each reminder from its new date
  rather than shifting the timestamps — otherwise a plan crossing a daylight-saving boundary would
  fire an hour out on the far side of it. Write the dates you mean; the reader decides.

## Top-level structure

```json
{
  "schemaVersion": 1,
  "plan": { },
  "weeks": [ ]
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `schemaVersion` | integer | **yes** | Must be exactly `1`. |
| `plan` | object | **yes** | Plan metadata, see below. |
| `weeks` | array of week objects | **yes** | At least one week. |

## `plan` — metadata

```json
{
  "id": "plan-2026-syyskausi",
  "name": "Syyskauden peruskuntokausi",
  "timeZone": "Europe/Helsinki",
  "startDate": "2026-08-10",
  "description": "10 viikon peruskestävyysjakso ennen hiihtokautta.",
  "author": "Valmentaja"
}
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `id` | string | **yes** | Non-blank, unique. Importing a plan whose `id` already exists is a duplicate. |
| `name` | string | **yes** | Non-blank. Shown in the UI. |
| `timeZone` | string | **yes** | IANA zone id, e.g. `Europe/Helsinki`. Must be resolvable by `java.time.ZoneId`. |
| `startDate` | string | **yes** | `YYYY-MM-DD`. The plan's first day. |
| `description` | string | no | Free text. |
| `author` | string | no | Free text. |

## `weeks[]`

```json
{
  "weekNumber": 1,
  "focus": "Peruskestävyys",
  "sessions": [ ]
}
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `weekNumber` | integer | **yes** | ≥ 1, unique within the plan. Weeks need not be contiguous but must not repeat. |
| `focus` | string | no | Short label for the week's emphasis. |
| `sessions` | array of session objects | **yes** | May be empty (a full rest week). |

## `sessions[]`

```json
{
  "id": "s-w1-ma-voima",
  "type": "STRENGTH",
  "date": "2026-08-10",
  "time": "07:00",
  "durationMin": 45,
  "distanceKm": null,
  "intensity": "EASY",
  "rounds": 3,
  "description": "Aamun keskivartalo ja liikkuvuus.",
  "exercises": [
    { "name": "Kyykky",      "sets": 3, "reps": 10, "weightKg": 60.0, "restSec": 90, "notes": null },
    { "name": "Lankku",      "sets": 3, "reps": null, "durationSec": 45, "notes": "Kädet suorina." }
  ],
  "lighterAlternative": {
    "durationMin": 25,
    "distanceKm": null,
    "intensity": "EASY",
    "rounds": 2,
    "description": "Pelkkä liikkuvuusosuus.",
    "exercises": [
      { "name": "Lankku", "sets": 2, "reps": null, "durationSec": 30, "notes": null }
    ]
  }
}
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `id` | string | **yes** | Non-blank and **unique across the entire document**. Duplicates are an error, not a silent overwrite. |
| `type` | string enum | **yes** | One of `RUNNING`, `STRENGTH`, `SKIING`. |
| `date` | string | **yes** | `YYYY-MM-DD`. Must be on or after `plan.startDate`. |
| `time` | string | **yes** | `HH:mm`, 24-hour, local time in `plan.timeZone`. |
| `timeIsFixed` | `boolean` | **Valinnainen** (oletus: `false`). Jos `true`, `time` on oltava oikea aloitusaika ja on pakollinen. |
| `durationMin` | integer | no | > 0 if present. |
| `distanceKm` | number | no | > 0 if present. |
| `intensity` | string enum | no | One of `EASY`, `MODERATE`, `HARD`, `MAX`. |
| `rounds` | integer | no | > 0 if present. Number of circuit rounds. |
| `roundsMin` | integer | no | > 0 if present. |
| `roundsMax` | integer | no | > 0 if present. |
| `targetPace` | string | no | e.g. "5:25-5:35" |
| `warmupSec` | integer | no | > 0 if present. |
| `description` | string | no | Free text shown on the session card. |
| `exercises` | array of exercise objects | no | Movements, in order. Typically present for `STRENGTH`. |
| `lighterAlternative` | object | no | The explicit lighter variant offered by "Kevyempi versio". |

At least one of `durationMin`, `distanceKm`, or a non-empty `exercises` array must be present —
a session with none of them describes no actual work and is rejected.

### `exercises[]`

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `name` | string | **yes** | Non-blank. |
| `sets` | integer | no | > 0 if present. |
| `reps` | integer | no | > 0 if present. Repetitions per set. |
| `repsMin` | integer | no | > 0 if present. |
| `repsMax` | integer | no | > 0 if present. |
| `perSide` | boolean | no | true if repetitions are per side |
| `weightKg` | number | no | ≥ 0 if present. |
| `durationSec` | integer | no | > 0 if present. For held movements (plank, hang) instead of `reps`. |
| `restSec` | integer | no | ≥ 0 if present. |
| `notes` | string | no | Free text. |

An exercise must have at least one of `reps` or `durationSec`.

### `lighterAlternative`
Same optional fields as a session — `durationMin`, `distanceKm`, `intensity`, `rounds`,
`description`, `exercises` — and no `id`, `type`, `date`, or `time`: it inherits those from its
parent session. Every field is optional, but the object must not be empty.

## Uniqueness rules
| Scope | Must be unique |
| --- | --- |
| Whole document | every `sessions[].id` |
| Whole document | every `weeks[].weekNumber` |
| Database | `plan.id` — a plan with the same `id` already in Room is a duplicate |
| Database | every `sessions[].id` — a session id already in Room is a duplicate |

## Duplicate detection on import
The importer distinguishes two cases and reports them differently:

1. **Identical re-import** — `plan.id` already exists **and** the SHA-256 of the normalised source
   JSON equals the stored `contentHash`. Reported as *"Tämä suunnitelma on jo tuotu."* Nothing is
   written.
2. **Conflicting re-import** — `plan.id` (or any `session.id`) exists but the content differs.
   Reported as a conflict listing the colliding ids. The user must explicitly choose to replace the
   existing plan; the importer never overwrites on its own.

## Validation and error reporting
Validation collects **all** errors before returning — it does not stop at the first one. Each error
carries a JSON path and a Finnish message, for example:

```
weeks[0].sessions[2].time: kellonaika "25:00" ei ole muotoa HH:mm
weeks[0].sessions[3].id: sama tunniste "s-w1-ma-voima" esiintyy jo kohdassa weeks[0].sessions[0]
plan.timeZone: tuntematon aikavyöhyke "Europe/Helsinky"
```

If the error list is non-empty, **nothing** is written to Room.

## Versioning policy
- `schemaVersion` is a single integer, bumped only on a breaking change.
- Adding a new **optional** field does not bump the version. Unknown fields are ignored, so an
  older build can read a newer document as long as the version matches.
- Removing a field, making an optional field required, or changing a field's meaning bumps the
  version. The importer then either migrates the document or rejects it with a clear message.
- The stored `TrainingPlan.schemaVersion` records which version a plan was imported under.

## Minimal valid document

```json
{
  "schemaVersion": 1,
  "plan": {
    "id": "plan-minimal",
    "name": "Yksi viikko",
    "timeZone": "Europe/Helsinki",
    "startDate": "2026-08-10"
  },
  "weeks": [
    {
      "weekNumber": 1,
      "sessions": [
        {
          "id": "s-1",
          "type": "RUNNING",
          "date": "2026-08-10",
          "time": "16:30",
          "durationMin": 45
        }
      ]
    }
  ]
}
```

## Versiointi

- **v1**: Ensimmäinen versio. `timeIsFixed` ja valinnainen `time` lisättiin taaksepäin yhteensopivasti. Huom. vanhat buildit hylkäävät ajattoman dokumentin.
