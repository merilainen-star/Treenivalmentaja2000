# Active Workout Mode

**Status: designed 2026-08-22, not built.** This page is the specification and the survey of what
building it would cost. Nothing in §1–§8 is implemented; where the text says "already exists" it
names the file that proves it.

Surveyed against `0d274cc` and **re-checked against `2f79a9e`**, which is where one of its
recommendations had already been built by the time this landed: §3 below proposed recording the
outcome as a payload on the `COMPLETED` event, and `GuidedProgress`, `SessionPayloadJson` and
`TrainingRepository.completeGuided` now do exactly that for the count of ticked movements. The
sections concerned say so rather than still proposing it.

## Implementation status

Built 2026-08-24. The full-screen route follows the flow below, uses an elapsed-realtime deadline
for rest clocks, keeps the display awake, sounds and vibrates at zero, and stores the finish
summary under `activeWorkout` on the immutable completion event. Plans without a structured
`exercises` array stay on the original Today card. Optional `equipment` and `roundRestSec` are now
part of Plan Schema v1; Room is schema v13 for the latter.

## What it is

A guided session that shows **one thing at a time** and carries the workout from the first movement
to the last. The person should be able to put the phone down beside them, press "Aloita treeni", and
finish the whole session without having to remember what comes next, count rounds, time a hold, time
a rest, or go back to the day's list to check what was prescribed.

V1 is deliberately small and aimed at home training — kettlebell, dumbbells, a bench, a mat,
bodyweight — where the gaps between movements are real: equipment has to be fetched, the mat put
down, the bench adjusted.

## The V1 flow

### 1. Starting

The day's session offers **Aloita treeni**. The first screen states what is ahead: name, estimated
duration, number of movements and rounds, and the equipment the session needs.

```
Keskivartalo + voima
3 kierrosta · noin 25 min
Tarvitset: jumppamaton, 6 kg kahvakuulan ja 5 kg käsipainot
                                              [ Aloita ]
```

### 2. Preparing — the step that makes this more than a bigger list

Before **every** movement, a preparation screen: what is next, how many repetitions or how long, what
it needs, and a short instruction with a way into the exercise guide.

```
Seuraavaksi
Goblet-kyykky · 12 toistoa
Tarvitset: 6 kg kahvakuulan
                                          [ Olen valmis ]
```

Nothing starts on a timer here. The person is fetching a kettlebell, moving to the floor, adjusting a
bench. **The work begins on their press, never on a countdown they did not ask for.**

### 3. Performing

*Repetition-based:* the movement, its prescription and the round it belongs to, and a **Valmis**
button. V1 does not ask for each repetition to be logged.

*Time-based:* a **Käynnistä** button, a countdown, a sound and a vibration at zero, then **Valmis**.
Interruptible and restartable.

### 4. Resting

Where the plan states a rest, a rest screen counts it down and names what follows, with **Ohita lepo**
and **+30 s**. When the rest ends the app says so — and stops. The next movement's preparation screen
is what comes up, not the next movement itself.

### 5. Rounds

A circuit is followed automatically: movement, rest, movement, rest, and after the last movement of
the round a longer round break and **Aloita seuraava kierros**.

### 6. Always available

Pause the session, go back a movement, skip a movement, open the exercise guide, end the session
early. A skipped movement is recorded as skipped rather than done. Ending early asks for
confirmation and keeps what was already done.

### 7. Light outcome data

**Valmis** is enough by itself. Optionally and quickly, per movement: *Helppo · Sopiva · Raskas*.

### 8. Finishing

A summary — duration, movements done out of planned, rounds, skipped — an optional session RPE
(1–10), and **Tallenna treeni**, which marks the session done through the machinery that already
exists.

### The principle underneath

The app may start a rest timer, advance the round number, show what is next and announce that a time
has run out. **It may not start the next movement.** That happens on "Olen valmis" and nothing else.

## Not in V1

Weight-progression tracking, per-set actual repetitions, automatic progression, AI changes
mid-session, Wear OS, live heart rate, Health Connect, training statistics, automatic movement
recognition. Each of these is buildable on top of the data this produces; none of them is needed for
the person to get through a session.

## What already exists

More than the specification assumes. This is mostly a rearrangement of built parts plus one new step.

| The specification asks for | In the code today |
| --- | --- |
| Starting a guided session (§1) | `TodayScreen.kt` — "Aloita ohjattu treeni" moves the session to `STARTED`. Strength sessions only, which matches V1's scope. |
| Repetition prescriptions (§3) | `Exercise.prescription()` in `ExerciseTimer.kt` — `12`, `3 × 12 · 17,5 kg`, and per-set ramps. |
| Timed movements (§3) | `ExerciseTimer` and its `CountdownDialog` — a 240 dp ring, a 72 sp number, a sound at zero, and `timedRounds()`, which runs the clock once per side or once per set. |
| Round tracking (§5) | The guided checklist in `TodayScreen.kt`: `rounds × exercises` behind one counter, only the next movement tickable, walking back by unticking. |
| The exercise guide (§2, §6) | `ExerciseGuideSheet` opens from the guided rows already — see [EXERCISE_GUIDE.md](EXERCISE_GUIDE.md). |
| Ending early, skipping the session (§6) | The session state machine: `SKIPPED`, `CANCELLED`, and the append-only event log — [TRAINING_ENGINE.md](TRAINING_ENGINE.md). |
| Saving the session (§8) | The existing `COMPLETED` transition. Nothing new. |
| "Kesto: 24 min" (§8) | **Derivable without a new column**: the gap between the `STARTED` and `COMPLETED` event timestamps. |
| Recording an outcome at all (§7, §8) | `TrainingRepository.completeGuided` writes a `GuidedProgress` — how many movements were ticked, against how long a sequence — as JSON on the `COMPLETED` event, and `guidedProgressFor` reads it back from the newest completion carrying one. The mechanism the feel answers and the RPE need is therefore built; only the fields are missing. |
| Rest lengths (§4) | **The data is already stored.** `restSec` is validated (`PlanValidator`) and persisted with every exercise — and nothing reads it. The rest timer is a reader for a field that has been waiting for one, the same shape as `icu_atl`/`icu_ctl` before the analysis feature. |

## What was built

1. **A full-screen mode.** Today the guided session is a card in a scrolling list with every movement
   visible. "One thing at a time" needs its own route and the bottom bar out of the way.
2. **The preparation step (§2).** Entirely new, and the best idea in the specification: it is what
   makes this a guided session rather than the same list at a larger font size.
3. **Rest between movements (§4).** New screen, existing data. **The round break has no field** —
   `restSec` is per exercise, and there is nothing on the session for the pause between rounds.
4. **The equipment line (§1).** Plan Schema v1 has **no equipment field**. `equipment` exists only in
   the exercise-guide providers' responses — English, fetched over the network, and not cacheable
   under ExerciseDB's terms, so it cannot serve this. Either derive a partial line from `weightKg`,
   or add an optional field to the schema.
5. **Skipping one movement, as recorded data.** Unticking a row is not the same as "skipped".
6. **The feel and RPE answers (§7, §8).** No *fields* exist for either — but the place to put them
   does, and it is already carrying the movement count: one more key in `SessionPayloadJson`'s
   payload, written by the same `completeGuided` call.
7. **Keeping the screen awake, and vibration.** Neither exists: no `FLAG_KEEP_SCREEN_ON` anywhere,
   no `Vibrator`, no `VIBRATE` permission. Without the first the whole mode is unusable.

## The hard parts, in order

### 1. The clock, once the screen goes dark

The existing countdown is `delay(1000)` inside a composable — it counts ticks. That is fine for a
plank, which is watched. A rest is not: the phone goes down, the screen sleeps, the process is
frozen, and a tick-counting clock drifts or stops.

The fix is to compute the remaining time from a **deadline on `SystemClock.elapsedRealtime()`**
rather than by counting, and — if the sound has to fire with the screen off — to hang it on a
foreground service or an alarm. The reminder side of the app already has AlarmManager, notification
channels and `POST_NOTIFICATIONS` handling built and tested; that machinery is reusable here.

### 2. Where the progress lives

Today it is `rememberSaveable(workout.id)` inside a composable: it survives rotation, not the app
being closed. A full-screen mode the person leaves and comes back to needs better than that.

`AGENTS.md`'s own rules point the way — stateless `…Content`, no domain logic in the UI — so the
sequence belongs in the domain as a pure function, testable without a screen, exactly like
`EasyRunDriftUseCase`:

```kotlin
sealed interface ActiveStep {              // domain/ActiveWorkout.kt
  data class Prepare(…) : ActiveStep
  data class PerformReps(…) : ActiveStep
  data class PerformTimed(…) : ActiveStep
  data class Rest(val seconds: Int, …) : ActiveStep
  data class RoundBreak(…) : ActiveStep
}

fun buildSteps(workout: Workout): List<ActiveStep>
```

The state machine over that list — `advance`, `back`, `skip` — is a second pure piece. Resuming a
session the app was killed during then needs either a small stored record or a stated V1 limit that
an interrupted session starts again.

### 3. Recording the outcome without pre-empting the gym log

[ROADMAP.md](ROADMAP.md) already carries "logging what was actually lifted" as later work. §7 is a
small slice of that and should not lock its design in.

**This one is settled, and by code rather than by argument.** `completeGuided` writes the guided
count as a `payloadJson` on the `COMPLETED` event and `guidedProgressFor` reads it back — no
migration, the append-only log stays the single history, and a real per-set table later can read
what is already there. The skipped movements, the per-movement feel and the RPE extend that same
payload rather than needing anything new.

Note the constraint that shaped it, because it also shapes what §7 can be: `TrainingRepository.transition`
writes an event only with a status change, so nothing can be logged movement by movement as it
happens. It is collected in memory — `WorkoutViewModel.recordGuidedProgress` — and written once at
the end, which is exactly what §8's "Tallenna treeni" button is.

`GuidedProgress` also demonstrates the discipline the rest of the outcome data should follow: it
stores the *shape* it was counted against (`rounds`, `perRound`) beside the count, because "6"
alone cannot be read later once "Kevyempi versio" has swapped the list underneath it.

### 4. Two schema questions

`equipment` and the round break are additive optional fields, but they touch
[PLAN_SCHEMA.md](PLAN_SCHEMA.md), `PlanValidator` and its tests. Worth their own change rather than
being mixed into the UI work.

### 5. Collisions with what is already there

Decide these before building, not during:

- **"Kevyempi versio" replaces the exercise list mid-session.** The current checklist already guards
  against this with a `coerceIn` on its counter. A step list has to be *rebuilt*, not resumed at the
  old index.
- **A background sync can complete the session** while the guided mode is open: Oura and
  intervals.icu matching both write `COMPLETED`.
- **Midnight can pass mid-session** — the ViewModel rolls the current date over deliberately.
- **Plans with no `exercises` array** are read out of the description by counting commas. They yield
  no repetitions and no rests. V1 should require a real array and fall back to today's card
  otherwise.

## The other document about this screen

[UI_REDESIGN_SPEC.md](UI_REDESIGN_SPEC.md) — written from a separate brief and stored on the same
day — has a section called **"Aktiivinen Treeni (Workout in Progress)"**, and its mockups include
that screen in both themes. The two pages are about the same thing from opposite ends, and neither
replaces the other: this one settles **what happens and when**, that one settles **what it looks
like**. Read both before building it.

They agree on the substance — a progress meter across the top, a hero card for the movement in
hand, a circular ring for a timed one, and exercise imagery through the existing Coil path.

**One point needs reconciling, and it is a real design question rather than a wording clash.** The
redesign asks for a compact *"Seuraavat harjoitukset"* list of what is still to come in the round;
this page's whole principle is one thing at a time, with the next movement disclosed on its own
preparation screen. The two can be had together — a short "next up" strip is not the same as the
day's full list, and knowing what is coming while you finish a set is genuinely useful — but
whoever builds it should decide deliberately, because the failure mode is exactly the one this
design exists to avoid: a screen that shows the whole session again and leaves the person to find
their own place in it.

## Implementation slices (completed)

1. ~~**Full-screen route, step list, preparation step**~~, reusing the existing timer. No schema change,
   no migration.
2. ~~**Rest timers**~~ — the `restSec` reader — on an elapsed-realtime deadline, plus keeping the screen awake
   and vibration.
3. ~~**The summary, the feel answers and RPE**~~, into the `COMPLETED` event's payload.
4. ~~**The schema**~~: `equipment` and `roundRestSec`, with `PLAN_SCHEMA.md` and validator tests.

## Why the shape of this fits

"The app may guide, but may not force the pace" is the same discipline the rest of the app already
keeps: the readiness rule asks and never acts, the missed-session proposal is applied only on
acceptance, and the easy-run drift note changes nothing at all. A guided session that starts the
next movement on its own would be the first place the app took the decision out of the person's
hands, and it would do it while they were still carrying a kettlebell across the room.
