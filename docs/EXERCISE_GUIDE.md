# Exercise Guide

*(Status: **implemented** on 2026-08-09. Sections 1–6 are the design and describe what was built;
["As built"](#as-built) at the end records where reality differed from the plan and why. Findings
depend on a third party's terms and should be re-checked before this feature is changed.)*

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

## Before changing this

Re-read both terms documents. They are a third party's and they change; the caching rule in
particular is the one this design bends around, and if it has loosened the design should be
revisited rather than followed out of habit.

## As built

Everything below was measured against the live service on 2026-08-09, the day the feature was
built. Where it contradicts the design above, this section is what the code does.

### The terms, re-checked

The free V1 restrictions were re-read from the API's own description (`GET
https://oss.exercisedb.dev/swagger`, the document the docs page renders) and are **unchanged**:
non-commercial use allowed, 180p GIF only, "Strict rate limits apply", and "Credit to AscendAPI is
required when using this dataset in any project".

The Terms of Use at <https://dub.sh/exercisedb-api-tos> could **not** be retrieved: the shortener
answers `403` to non-browser clients. The reading in "Findings" therefore stands unverified on
that document, which is another reason the design stores nothing — the conservative choice is the
one that does not depend on which document governs.

### The endpoints

| Design said | Built as | Why |
| --- | --- | --- |
| `GET /exercises/{id}` | unchanged | Returns the eight documented fields. |
| a name search | `GET /exercises?name=<q>&limit=25` | `/exercises/search` returns only `exerciseId`, `name` and `gifUrl`, so every suggestion would have needed a second request — and it answered `503` under load while `?name=` kept serving. |

The service sits behind Cloudflare Workers and answers `503` with the **plain text** body
`error code: 1102` when it is over its resource limit. The status is therefore checked before the
body is treated as JSON at all.

### The fuzzy search invents, and had to be filtered

This is the finding that changed the design. `?name=` does not miss when there is nothing to find —
it answers confidently with something else:

| Query | What the service returned |
| --- | --- |
| `cat cow` | "cable squat row", "band squat row" |
| `bench press` | "ez bar standing french press" among the top hits |
| `kissa` (via `/search`, default threshold) | seven unrelated results, e.g. "resistance band seated biceps curl" |

"Tarkoititko: *cable squat row*?" for **Kissa-lehmä** is worse than showing nothing. Every result
is now filtered client-side: a match is kept only if the movement's name contains **every** word
of the query that is at least three characters long, and the shortest surviving name is offered
first, because among "front plank with twist" and "kneeling plank tap shoulder (male)" the short
one is the plain movement. A Finnish name survives none of this, which is the honest outcome and
the one the design asked for.

### Smaller differences

- **One hit is shown outright** rather than as a list of one — still labelled `Ehdotus`, because a
  single hit is no more certain than the top of five.
- **The suggestion list carries no thumbnails.** Five suggestions would be five image requests
  against a service whose only published rate limit is the word "strict", four of them for
  movements the user is about to not pick. The image appears once a suggestion is chosen.
- **`ExerciseGuideProvider.search` returns full `ExerciseGuide`s**, as designed, because
  `?name=` returns the whole record. No second round trip.
- **The step numbering is stripped.** Instructions arrive as `"Step:1 Lie flat on a bench…"`, and
  the sheet numbers them itself; printing both would number every line twice.

### The no-storage rule, verified

`TreenivalmentajaApplication.newImageLoader` passes Coil **no disk cache at all**
(`diskCache(null)`) as well as `diskCachePolicy(CachePolicy.DISABLED)`. Both, deliberately: the
policy stops requests reading and writing, and passing no cache means there is no directory to
create, so the guarantee does not rest on every code path honouring a flag.

Measured on the emulator (`treeni-test`, API 36) by loading a real guide GIF through the app's own
image loader: the request returned `SuccessResult`, and `cacheDir` afterwards contained the
directory itself and nothing else — no `image_cache`, no files. `filesDir` was untouched.
`ImageLoaderConfigurationTest` holds the configuration in place; the empirical check was done by
hand because a test that fetched from a rate-limited free service would fail for reasons that have
nothing to do with this app.

## The second source: wger

Added 2026-08-09, after the gaps below turned out to be permanent. <https://wger.de>, API v2.
Everything here was measured against the live service that day.

**Why a second source rather than a replacement.** Neither is enough alone:

| | ExerciseDB | wger |
| --- | --- | --- |
| Movements | 1500 | 834 |
| Media | animated GIF, **every** movement | still image, **264 of 834** (32%) |
| Licence | unclear; the strictest readable clause forbids storing anything | CC-BY-SA, CC-BY, CC0, ODbL — storage permitted |
| Attribution | one line, "AscendAPI" | per image, naming its author |
| Instructions | numbered steps throughout | HTML prose, quality varies |
| Name search | fuzzy, works | **none** — see below |
| Finnish | no | no |

ExerciseDB has the animation, which is the whole reason to open the sheet. wger has the movements
ExerciseDB does not. A plan pins each movement to whichever source actually has it, and
`ExerciseGuideProvider` was an interface from the start precisely so this would cost one class.

**wger does no name search, and makes no request pretending to.** `/exercise/search/` answers
`404` — it was removed — and the filter that remains, `?name=`, is an exact **case-sensitive**
match: `name=Bird Dog` returns four rows, `name=bird dog` returns none. A Finnish movement name
cannot hit that under any capitalisation, so `WgerProvider.search` returns an empty list without
touching the network rather than spending a guaranteed-miss request on a volunteer-run service
every time a movement is tapped. The fuzzy path stays with ExerciseDB, which has a working one.

**Its instructions are HTML.** wger stores prose as `<p>` blocks, so each block becomes one line
and the markup is stripped; the sheet numbers them itself, exactly as it does for ExerciseDB's
`Step:1` prefixes.

**Its attribution is per image.** CC-BY-SA needs the licence and the author named, and each
picture carries its own — so the credit line moved onto [ExerciseGuide] rather than staying a
per-source constant. A movement with no picture credits only the source and the licence. The
states that show no data at all show no credit, because there is nothing to attribute.

**The no-storage rule did not loosen.** wger's licences would permit caching, and even bundling
images into the APK. ExerciseDB's do not, one image loader serves both, and the stricter rule
wins. If ExerciseDB were ever dropped, this is the constraint that would lift with it.

## The references this programme uses

Written down so the next plan does not have to look them up again, and so a movement keeps the
same reference every time it appears. **Finnish names stay Finnish** — the `name` field is what
you read mid-session, and the whole point of `guide` is that the reference settles the question
without translating anything.

Verified against the live service on 2026-08-09; each row was checked by reading the returned
movement, not by trusting the name.

| Plan's name | `provider` | `id` | The source's name |
| --- | --- | --- | --- |
| Punnerrus | exercisedb | `I4hDWkc` | push-up |
| Kevyt punnerrus | exercisedb | `ZOuKWir` | kneeling push-up (male) |
| Timanttipunnerrus | exercisedb | `soIB2rj` | diamond push-up |
| Kahvakuulaheilautus | exercisedb | `UHJlbu3` | kettlebell swing |
| Goblet-kyykky | exercisedb | `ZA8b5hc` | kettlebell goblet squat |
| Käsipainosoutu | exercisedb | `C0MA9bC` | dumbbell one arm bent-over row |
| Vatsarutistus | exercisedb | `TFqbd8t` | crunch floor |
| Vinot vatsarutistukset | exercisedb | `QUDd8WS` | oblique crunches floor |
| Lankku | wger | `458` | Plank |
| Sivulankku | wger | `580` | Side Plank *(no picture)* |
| Kyykky | wger | `615` | Squats *(no picture)* |
| Bird dog | wger | `1572` | Bird Dog |
| Kissanlehmä | wger | `1938` | Cat-Cow *(no picture)* |
| Bulgarialainen askelkyykky | wger | `988` | Bulgarian split squats left |
| Lonkankoukistajan venytys | wger | `1867` | Hip Flexor Stretch |

This is our mapping for our plan, not a copy of either catalogue. Reproducing one is a different
act: ExerciseDB forbids commercial use and sells full dataset access, so the whole list does not
belong in this repository however convenient it would be. wger's licence would permit it, but
there is still no reason to carry 834 rows we do not use.

### Where each source falls short

**ExerciseDB is missing bodyweight basics.** Checked against all 1500 free-tier exercises, these
are absent in every spelling: `plank` · `side plank` · `squat` (plain) · `bird dog` · `cat cow`.
It leans heavily towards gym machines. Every one of them exists in wger, which is why wger is
here — and why five of this programme's movements are pinned to it.

**wger is missing pictures.** Two thirds of its movements have none, including Side Plank, Squats
and Cat-Cow above. Those sheets still carry the name, the instructions, the muscles and the
equipment, which beats the alternative of nothing at all.

One movement has a reference from neither: **Vatsarutistus penkillä**. ExerciseDB's nearest is
`9Ap7miY decline crunch` — a decline bench, not a flat one — and wger has no equivalent. It was
left out rather than approximated, because `guide` is the plan author's assertion that the
movement *is* this one. Two others that were rejected on ExerciseDB for the same reason are now
pinned to wger instead, exactly:

| Plan's name | Rejected on ExerciseDB | Resolved on wger |
| --- | --- | --- |
| Bulgarialainen askelkyykky | `9E25EOx` split squats — rear foot not elevated | `988`, and per side |
| Lonkankoukistajan venytys | `2LQkNPW` — needs a stability ball | `1867` |

### Where the code is

| Piece | File |
| --- | --- |
| Provider interface, `ExerciseGuide`, error types | `data/guide/ExerciseGuideProvider.kt` |
| One GET and one reading of what went wrong, shared | `data/guide/GuideHttp.kt` |
| ExerciseDB implementation and the relevance filter | `data/guide/ExerciseDbProvider.kt` |
| wger implementation and the HTML stripping | `data/guide/WgerProvider.kt` |
| States, the two lookup paths, the in-memory cache | `domain/LoadExerciseGuideUseCase.kt` |
| The sheet | `ExerciseGuideSheet.kt` |
| The tappable rows | `WorkoutDetails.kt` |
| The image loader's configuration | `TreenivalmentajaApplication.kt` |

Captured real payloads used by the parsing tests live in `app/src/test/resources/guide/`.
