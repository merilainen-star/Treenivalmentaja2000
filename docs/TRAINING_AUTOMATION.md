# Training review and automation — 27 September 2026

## Daily view and themes

The approved light concept uses sage/lavender; dark uses midnight/cyan/violet. Both follow the
existing System / Light / Dark preference and Material 3 semantic roles. No mockup bitmaps ship.
Before running, `runSteps` are shown in their authored order with width proportional to duration
(distance / target speed for distance stages, explicitly labelled as estimated). Height/colour
describe phase type, not measured effort. The whole sequence stays visible. Tapping
a bar or using accessible Previous/Next buttons shows its full cue, time/distance and pace target.
Relaxed accelerations saying “ei sprinttiä” remain accelerations. Unknown names stay neutral.
Counts retain different pace targets; full prose and the individual stages remain expandable.

After recording/completing a run, results and AI analysis lead the card. Planned stages remain
visible. Full instructions, all three duration measurements and separate Oura/watch metrics are
under “Kaikki ohjeet ja mittarit”. Recovery and its actions remain expandable. This intentionally
changes the former decision to hide analysis in the overflow menu and always expand the prose.
The completed card now also overlays measured heart rate on recorded elapsed lap boundaries in a
taller chart; see [timeline rules and missing-data behavior](RUN_TIMELINE.md).

## Scheduling and settings

`TrainingAutomationWorker` uses unique WorkManager periodic work (15 minutes, network required)
and unique one-time work after connection, settings, foreground refresh and session changes.
`TrainingAutomation` serializes overlap. Android may defer work; there is no finish-time guarantee
and no server/webhook. The three requested opt-out switches default on: automatic completion,
AI analysis and export. The AI switch explains provider data transfer and possible usage charges;
the existing user-entered key/model are used. Notification permission is managed in Settings.

## Completion

The ordinary activity matcher still changes only links. The separate completion rule considers
today and two preceding days in the active plan's timezone. It requires one running session and
one run on that day, an explicit `SUUNTO` source and a matching session id, at least 500 metres,
at least five minutes and half each specified duration/distance target, and a recorded end two
minutes in the past. Distance-only plans are supported; an absent target stays absent.
Only planned/notified/started/lightened sessions qualify. Skipped/interrupted/cancelled/illness
states are preserved. Ambiguous records retain manual completion. This heuristic is not proof of
using a particular SuuntoGuide or executing every planned interval.

The transaction rechecks the expected session snapshot. An `INTERVALS_SYNC` event records the
activity id and prior status. “Peru merkintä” restores that status with an appended USER event.
History is never removed, and a prior automatic event prevents re-completion after undo. This is
the explicitly requested, narrowly scoped exception to completed sessions being terminal.

## Durable analysis

`SessionAnalysisRepository` serves manual and automatic calls and reads inputs from repositories,
never UI subscriptions. The worker waits for sync, the watch-lap file fetch and, for kilometre-length
runs, the stream fetch. Manual analysis fetches missing laps for its matched activity before any
provider request; a failed fetch shows a retry message without an AI charge or receipt. See
[watch-lap restoration](WATCH_LAP_ANALYSIS.md). Missing successful detail stays missing; a failed
detail request is tried by later syncs. Oura
recovery is refreshed when connected. Structured planned run steps enter the prompt with an
explicit warning: kilometre splits are not measured interval laps. No lap/sprint result is invented.

Completed-run prompts distinguish watch active duration (net time), Intervals.icu moving time,
and recording duration (gross time, including pauses), emitting only available measurements.
Kilometre split pace uses the recording timeline and can include traffic-light or other pauses;
a slow split alone does not prove slower running or fatigue. Source-reported heart-rate zone
times may include pauses: exceeding net duration alone is not a measurement error. The prompt
asks the model to compare against available gross duration without inventing pause locations
or claiming that the source's zone pause handling is known.

Room v18 includes `session_analyses`, keyed by session and analysis kind, with model, time, exact
prompt, result and typed state. Its foreign key cascades when a session is deleted, including a
confirmed program replacement/reset. Updates retain results. KSP generates the schema; the explicit
17→18 migration reconciles both already-published v17 variants without deleting records.
Existing database/DataStore backup exclusions still apply.

A receipt is persisted before the provider call. Concurrent requests for one session coalesce.
Automatic jobs never repeat a saved attempt, even after failure/process death: the provider may
already have billed it. The UI exposes an explicit retry and explains interrupted requests.
Results remain readable after removing the key. A private notification on its own AI channel
opens that exact stored analysis on cold start or in a running app. Denied notification permission
does not discard the result. AI plan proposals continue to require explicit user approval.

## Export

Automatic and manual UI exports cover today plus thirteen days. The repository takes an explicit
window, validates all stages before writing, and reconciles only this app's namespaced events in
that window. Stable ids prevent duplicates; obsolete owned events are removed. User events and
trial exports retain their protections. A persisted date/plan fingerprint avoids unchanged writes;
failure retains a visible message and retries. Credential changes invalidate the fingerprint.

The UI says “viety Intervals.icu:hun”, never claims confirmed watch arrival. Suunto's upload setting
and phone/watch synchronization are still necessary. No strength export or fourteen-day watch
capacity is promised.

## Exercise lookup

`ExerciseNames` strips known set/side/warm-up/light-version labels while preserving movement and
equipment. Explicit guide references still win and never silently fall back after failure.
Curated equivalent references and conservative English search terms resolve Finnish names.
Name-derived guides remain labelled suggestions. Each request retains its own title and notes,
even when another set's guide data came from cache. Program notes are shown while loading,
offline and after a no-result response; catalogue media remains memory-only.

These exact public ExerciseDB names were checked on 27 September 2026:

| Program name | Reference | Provider name |
| --- | --- | --- |
| Yhden käden kahvakuulasoutu | `g9AsZ8P` | kettlebell one arm row |
| Pystypunnerrus käsipainoilla | `A6wtbuL` | dumbbell standing overhead press |
| Dead bug | `iny3m5y` | dead bug |
| Askelkyykky paikallaan | `9E25EOx` | split squats |

No exact kettlebell Romanian deadlift was returned. Barbell/dumbbell variants are not silently
substituted; the program's own notes remain available.

## Verification

The supplied eight-week program has 40 sessions (24 runs, 16 strength workouts) and 174
running stages, at most 18 per workout. Its 318 main strength entries are individual sets,
sides and preparation/cool-down phases, not 318 different movements. Of these, 109 have
explicit guide references and 209 rely on names; all 209 include authored notes. The four
verified aliases above cover another 109 entries. The remaining entries retain notes and
conservative catalogue search. The imported JSON and prescribed program were not rewritten.

Several easy runs specify distance without duration; completion must handle that valid shape.
Stable session ids sometimes retain older labels (for example “cooper-tonnit”) although the
current prescription is easy running. Classification uses the current step cues, never the id.
The Cooper session's top-level 12 minutes / 3 km describes the test; its runSteps additionally
include preparation and cool-down. Neither chart nor export drops those stages.

Tests cover date boundaries, ambiguity, user statuses, undo/stale decisions, persistent and
interrupted analyses, concurrent requests, the sync/completion/analysis/notification coordinator,
opt-out, 14-day reconciliation, name aliases and per-set notes. Instrumented tests cover the
16→18 and both 17→18 migration paths and Compose interactions; screenshots cover both themes before/after.
Actual results and commands are recorded in the newest block of `PROJECT_STATUS.md`.
The Android test asset merge depends on KSP so a changed schema cannot be packaged from a
stale earlier build. Generated JSON is never edited manually.
