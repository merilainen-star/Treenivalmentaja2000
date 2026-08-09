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
| `setPlan` | array of set objects | no | The sets spelled out, when they are not all alike. See below. |
| `guide` | object | no | Which movement this is in an outside catalogue. See below. |

An exercise must have at least one of `reps` or `durationSec`.

#### `setPlan[]` — sets that differ from each other

**Added after schema v1 shipped. Optional and backwards compatible: a plan without it is read
exactly as before, and one is never written for you.**

`sets`, `reps` and `weightKg` describe sets that are all the same. A gym session often is not —
the load ramps, and the reps may fall as it climbs:

```json
{ "name": "Alasoutu", "setPlan": [
  { "weightKg": 25, "reps": 10 },
  { "weightKg": 35, "reps": 10 },
  { "weightKg": 45, "reps": 10 },
  { "weightKg": 55, "reps": 10 }
] }
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `weightKg` | number | no | >= 0 if present. |
| `reps` | integer | no | > 0 if present. |
| `durationSec` | integer | no | > 0 if present. |

Each set needs at least one of `reps` or `durationSec`, the same rule the exercise itself follows.

**An exercise carrying `setPlan` must not also carry `sets`, `reps` or `weightKg`.** Two
descriptions of the same sets could disagree, and nothing in the document would say which was
meant, so the importer rejects it rather than applying a precedence rule nobody would remember.
`perSide`, `restSec` and `notes` still apply to the exercise as a whole.

Nothing changed in the database: exercises live in a single JSON column, so this needs no
migration and no schema version bump.

#### Circuits versus straight sets

These are different shapes and the schema distinguishes them:

- **Circuit** — the whole exercise list is repeated. Put `rounds` on the *session*.
- **Straight sets** — every set of one exercise is done before moving to the next, as in a gym.
  Leave `rounds` off and give each exercise its own `sets` or `setPlan`.

Writing a gym session with session-level `rounds` would tell the app to send you back to the
same machine between sets.

#### Timed exercises and how often the clock runs

`durationSec` is what makes an exercise timed — not its name. The app shows a clock for any
exercise that carries it, and runs that clock once per repetition of the hold:

| Fields | Clock |
| --- | --- |
| `durationSec: 30` | once |
| `durationSec: 20`, `perSide: true` | twice, labelled Vasen and Oikea |
| `durationSec: 45`, `sets: 3` | three times, labelled Sarja 1-3 |

`perSide` takes precedence over `sets` rather than multiplying into a count nobody wrote.

This matters because the alternative is counting in your head while holding a side plank. Write
`"sivulankku"` with `durationSec: 20, perSide: true` and the app asks for both sides; write the
duration into the name only, as `"sivulankku 20 s/puoli"` with no fields, and it cannot.

#### `guide` — which movement this is

**Added after schema v1 shipped. Optional and backwards compatible: a plan without it is read
exactly as before.**

A name is enough to recognise a movement you already know and useless for one you do not. Tapping
an exercise opens an animation and a few lines of instruction, and `guide` is how the plan says
which movement to fetch:

```json
{ "name": "Penkkipunnerrus", "sets": 3, "reps": 8,
  "guide": { "provider": "exercisedb", "id": "EIeI8Vf" } }
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `provider` | string | **yes** | `exercisedb` or `wger`. Any other value is an import error. |
| `id` | string | **yes** | Non-blank. The provider's own identifier. |

Two sources because neither has everything. ExerciseDB carries an animation for all 1500 of its
movements but has no plank, side plank, plain squat, bird dog or cat-cow at all; wger has those,
but only a third of its movements carry a picture. Pin each movement to whichever one has it.

An unknown `provider` is rejected rather than ignored: a catalogue this build cannot read means
guides that silently never appear, and the writer should hear about it at import.

This is a reference the plan's author wrote, like a URL — **not** content fetched from the
catalogue, so storing it is fine. Nothing that comes back from the lookup is ever stored; see
[EXERCISE_GUIDE.md](EXERCISE_GUIDE.md).

An exercise without `guide` is still tappable: the app searches the catalogue by name and offers
what it finds as a suggestion. Finnish names almost never match, so writing `guide` is the only
way to settle the question — and once written, it is settled for good.

**Do not rename movements to English to make them match.** The `name` field is what you read
mid-session and it stays Finnish; `guide` is what resolves the movement. The references already
looked up for the current programme are listed in
[EXERCISE_GUIDE.md](EXERCISE_GUIDE.md#the-references-this-programme-uses), along with the
movements the catalogue simply does not have.

Nothing changed in the database: exercises live in a single JSON column, so this needs no
migration and no schema version bump.

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
2. **Correction** — the same `plan.id` with different content, where every session already
   stored still exists in the document. The app offers to **update** it: each session's content is
   rewritten in place and its status, its event history and any reschedule chain hanging off it
   are untouched. Sessions the document adds are inserted. This is what correcting a typo or
   adding `guide` references three weeks into a programme actually is, and it costs nothing.
3. **Replacement** — anything else that would overwrite stored rows: a different `plan.id`, or the
   same one with sessions dropped. The app offers to **replace**, and says how many recorded
   sessions that would destroy, because there is nowhere to put the history of a session the
   document no longer contains.

Nothing is written in either case until the user says so. That includes importing a plan with a
brand-new `plan.id`, which used to delete whatever was stored without a word.

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
