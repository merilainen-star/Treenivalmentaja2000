# Exercise Guide (planned)

*(Status: **not implemented**. This document is the plan. Everything in "Findings" was measured
on 2026-08-09 and should be re-checked before the work starts, because it depends on a third
party's terms.)*

## The problem

A session lists movements by name:

```
Kissa-lehmä 10
Lonkankoukistajan venytys 30 s / puoli
Bird dog 10 / puoli
Lankku 25 s
```

A name is enough to recognise a movement you already know and useless for one you do not. Nobody
should have to search the web mid-session to find out what a bird dog is.

The goal is small: tap a movement, see an animation and a few lines of instruction, close it.
A memory aid, not an exercise library.

## Findings: ExerciseDB terms of use

Two separate documents govern the service and they do not say the same thing. Both were read on
2026-08-09.

**The free V1 API** (<https://oss.exercisedb.dev/docs>) states its own restrictions:

| | |
| --- | --- |
| Allowed | personal projects, prototypes, educational tools, **non-commercial apps** |
| Not allowed | commercial products, SaaS, any monetised use |
| Media | 180p GIF only |
| Rate limits | "strict rate limits apply" — no number given |
| Attribution | **required**: "Credit to AscendAPI is required when using this dataset in any project" |
| Authentication | none |

Treenivalmentaja is a private, single-user, unpublished app, so it falls inside the permitted
category. V1 is also the right version: the v2 endpoints are marked "not recommended for
production integration".

**The Terms of Use** (<https://dub.sh/exercisedb-api-tos>, last updated July 2025) say something
the free page does not. Section 3, *Strict Storage Prohibition*, forbids storing anything obtained
from the API — "text and metadata", "images and GIFs" — "locally on devices, computers, or mobile
applications", or "in cache beyond temporary operational needs (not exceeding 1 hour)". Section 6
requires "real-time, on-demand access only".

Those terms address "subscribers with a valid subscription" throughout and never mention a free
tier, so which document governs free use is genuinely unclear.

**Conclusion.** Free personal use is clearly permitted and attribution is clearly required.
Persistent caching is not clearly permitted, and the only explicit statement anywhere forbids it.
The design below therefore stores nothing that comes from the API. If the terms are later
clarified, adding a cache is a change to one class.

## Design

Three things stay separate, and only the first two are ours:

1. **What the plan prescribes** — reps, load, duration. Already in the schema; ExerciseDB has no
   such fields and must never become the source of them.
2. **Which movement it is** — a reference the plan may carry.
3. **What the movement looks like** — fetched at view time, never stored.

### 1. Schema: an optional reference on an exercise

```json
{
  "name": "Bird dog",
  "reps": 10,
  "perSide": true,
  "guide": { "provider": "exercisedb", "id": "xxxxxxx" }
}
```

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `provider` | string | **yes** | Currently only `exercisedb`. Unknown values are an import error. |
| `id` | string | **yes** | Non-blank. The provider's own identifier. |

Optional and backwards compatible: a plan without `guide` imports exactly as before.

This is a reference the plan's author wrote, like a URL — not content fetched from the API — so
storing it raises none of the questions in section 3 of the terms.

Add it to `ExerciseDto`, `Exercise`, and `PlanValidator.validateExercises`, and document it in
[PLAN_SCHEMA.md](PLAN_SCHEMA.md). No Room migration: exercises are one JSON column.

### 2. A provider interface, so the app is not welded to one source

```kotlin
interface ExerciseGuideProvider {
  /** Metadata for a known reference. */
  suspend fun byId(id: String): ExerciseGuide

  /** Best-effort lookup for an exercise that carries no reference. */
  suspend fun search(name: String): List<ExerciseGuide>

  /** Shown wherever the guide is; required by the provider's terms. */
  val attribution: String
}

data class ExerciseGuide(
  val id: String,
  val name: String,
  val imageUrl: String,
  val instructions: List<String>,
  val targetMuscles: List<String>,
  val equipment: List<String>,
)
```

`ExerciseDbProvider` implements it against `https://oss.exercisedb.dev/api/v1`. Its response
carries exactly eight fields — `exerciseId, name, gifUrl, instructions, targetMuscles,
secondaryMuscles, bodyParts, equipments` — and no duration, reps or sets, which is why point 1
above matters.

Nothing outside this package should mention ExerciseDB. Follow the existing repository rule in
[AGENTS.md](../AGENTS.md): UI and ViewModel never touch it directly.

### 3. No persistence, anywhere

- Metadata is held in memory for the lifetime of the process and no longer.
- The image loader must be configured with **disk cache disabled** for these URLs. Coil caches to
  disk by default; that default is a terms violation here.
- Nothing goes in Room. No new table, no new column beyond the plan's own `guide` reference.

A short in-memory map keyed by provider id is fine and stays far inside the one-hour limit the
terms mention.

### 4. Finnish names and uncertain matches

Plans are written in Finnish; ExerciseDB is in English. An exercise carrying `guide` needs no
matching at all, which is the point of having the field — write it once and the lookup is settled.

When there is no `guide`, the app may search by name at view time. The result is a **suggestion,
never an answer**:

- Show it as "Tarkoititko: *barbell bench press*?" with the image, not as the exercise itself.
- Offer no way to silently adopt a wrong match.
- A translated Finnish name will usually miss. That is acceptable: the fix is to add `guide` to
  the plan, and the UI should say so.

Do not build a learned Finnish→English mapping table. It would be persisted metadata derived from
the API, which is what the terms forbid, and a wrong entry would be invisible afterwards.

### 5. UI

Follow the existing components. Exercises are rendered by `WorkoutDetails`, shared by the Today
card and the expanded Week row.

- The exercise row becomes tappable when it has a `guide` or a name worth searching.
- Tapping opens a **`ModalBottomSheet`** — the app has no other sheet yet, so match Material 3
  defaults and the existing card typography.
- Contents: the movement's name, the animation, the numbered instructions, target muscles and
  equipment as secondary text, and the attribution line.
- The attribution is not optional. Something like "Liiketiedot: ExerciseDB / AscendAPI".
- Do not put a thumbnail in the exercise row: it would fetch every image in the session on open,
  against a service with unspecified "strict" rate limits.

### 6. Error handling

Every one of these is a normal state, not an exception to be logged and forgotten:

| Situation | What the sheet shows |
| --- | --- |
| No network | "Liiketiedot vaativat verkkoyhteyden." The name, reps and timer still work. |
| API does not answer, or 5xx | The same, with a retry action. |
| Rate limited (429) | "Liiketietoja haettiin liian tiheästi. Yritä hetken päästä." |
| `guide.id` not found (404) | "Liikettä ei löytynyt lähteestä." Do not fall back to a name search — the plan named this exercise deliberately. |
| Name search finds nothing | "Ei osumaa. Lisää `guide` suunnitelmaan." |
| Name search is uncertain | Present as a suggestion, per section 4. |
| Image fails to load | Keep the instructions; show a placeholder rather than an empty box. |

The session itself must remain fully usable in every one of these cases. The guide is an extra.

## Verification

- Unit tests for the provider's response parsing against a captured real payload, and for the
  mapping to `ExerciseGuide`. A Moshi mismatch in this project has shipped before, compiling
  cleanly and failing on the phone, so parse a real body rather than a hand-written one.
- Unit tests for each error state, driving a fake provider.
- A screenshot baseline per sheet state: loaded, offline, not found, uncertain.
- Check on a device that the image loader writes nothing to disk.
- Confirm the attribution is visible wherever guide data appears.

## Out of scope

- Video, multiple resolutions, or anything behind the paid RapidAPI tier.
- A bundled offline exercise dataset. If offline guides become a requirement, the terms make
  ExerciseDB the wrong source and a permissively licensed dataset should be evaluated instead —
  for example <https://github.com/sergei-argutin/exercise-dataset>, which permits in-app use with
  attribution and could be shipped inside the APK.
- Any use of guide data to alter a plan.

## Before starting

Re-read both terms documents. They are a third party's and they change; the caching rule in
particular is the one this design bends around, and if it has loosened the design should be
revisited rather than followed out of habit.
