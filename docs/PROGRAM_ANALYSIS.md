# Whole-programme analysis

An interim report the person can ask for at any point in a plan, and a final report when the plan
runs out. Both answer the same question the per-session analysis cannot: **has this training done
anything, and is the rest of the plan still the right plan.**

Specified 2026-09-06. This document is the design; the implementation slices are listed at the end
with what is built and what is not.

## The principle this is built on

> The app's job is to compute and structure the data as reliably as it can. The model's job is to
> interpret it and act as a coach.

Everything below follows from that one sentence. A model handed a hundred sessions of raw rows will
do arithmetic badly, silently, and differently every time it is asked. So the app does the
arithmetic — counts, percentages, weekly rollups, first-versus-last comparisons — and hands over a
structure in which the numbers are already settled and only the meaning is open.

The second rule is the one the rest of this app already keeps: **absent is not zero**. A trend with
too few runs behind it is not rendered at all. The model is told the comparison could not be made,
never handed a delta computed from one run against one run.

## 1. What the model is given

One deterministic object, `TrainingProgramAnalysis`, built by a pure function from the plan, its
sessions, their completion payloads, the matched intervals.icu activities and the Oura days. It is
rendered to text by the prompt builder; nothing else reads it.

| Part | What it holds | Why at this resolution |
| --- | --- | --- |
| `identity` | Name, the plan's own description (its goals, as written), start date, end date, week count | The goals are the plan author's words and are quoted, not summarised |
| `adherence` | Counts per status, completion %, the same split per workout type | The single number the first question asks for |
| `weeks` | One row per week: planned, done, skipped, minutes, kilometres, load, mean RPE | Regularity and load progression are weekly facts, not per-session ones |
| `runs` | **One row per completed run**, chronological | The comparison the owner asked for lives here and nowhere else |
| `runTrends` | Per planned intensity: first half against second half — pace, heart rate, distance | Computed by the app precisely so the model does not compute it |
| `strength` | Sessions, completion, the movements that recur, RPE trend | Per-set data answers no question this report asks |
| `feedback` | RPE distribution and trend, "miltä tuntui" counts, early against late | The subjective half, aggregated the same way as the objective half |
| `recovery` | HRV, resting heart rate and readiness, early against late | Whether the body agreed with the training |
| `changes` | Reschedules, lighter versions, cancellations, illness pauses | "Muutokset alkuperäiseen ohjelmaan" |
| `remaining` | Weeks and sessions left, by type, and what they ask for | Question 7 cannot be answered without it |

### Runs stay detailed; strength does not

A run carries measurements that are comparable across two months: distance, the watch's own
duration, pace, average and maximum heart rate, elevation, training load, TRIMP. Two runs eight
weeks apart at the same planned intensity are the whole evidence base for "has the fitness moved",
and averaging them into a weekly mean throws that away. So **every completed run is a row**, and a
row is about fifteen tokens: a hundred runs is fifteen hundred tokens, which is affordable.

A strength session's raw data is a list of movements the plan already stated, ticked off. The
useful facts are: was it done as prescribed, how long did it take, how hard did it feel, and did
the movements change. None of that needs a per-set dump, and the timing detail that the guided
mode records — each movement's seconds, each rest — answers a question about *that session*, which
the per-session analysis already asks. It is not sent here.

### What is deliberately not sent

Individual `session_events` rows, per-movement seconds, per-rest seconds, clock times of day,
Oura's per-day contributor breakdown, and the guide references. Each of these is either already
covered by an aggregate or answers no question the report asks. If a future question needs one, it
gets added to the aggregate as a computed figure — not as raw rows.

## 2. The comparison the app makes

The model must not be the thing that decides whether the runner got faster. The app computes it:

- Completed runs are grouped **by the planned intensity of their session** — an easy run and an
  interval session are not comparable, and comparing them is the commonest way to invent progress.
- Each group is split into a first half and a second half by date.
- A group produces a trend **only if both halves have at least two runs.** Fewer than that and the
  group is reported as "too few runs to compare", which is a fact, and the model is told not to
  reach past it.
- The trend carries mean pace, mean heart rate and mean distance for each half, and the deltas.

That is enough for the three readings the owner named: the same pace at a lower heart rate, a
faster pace at the same heart rate, and a longer distance at the same effort. The model names which
one happened; it does not have to find it in the numbers first.

## 3. Facts, interpretation, recommendation

The report is required to keep the three apart, because a coach who cannot tell you which is which
is not giving advice, only prose. The prompt asks for three named sections and forbids a fourth:

1. **Näin ohjelma toteutui** — what the data says, no reading between lines.
2. **Tulkinta** — what it probably means, hedged where the data is thin.
3. **Suositus** — what to do, and only where 1 and 2 support it.

And the guardrail: *if the data does not show development, say that it does not.* An absent trend
is an answer, and the aggregate makes it possible to give it — the model can see that a comparison
was withheld for want of runs, rather than guessing why a number is missing.

## 4. The two reports

Both render the same aggregate; they differ in phase, in emphasis and in what they may say about
what comes next.

**Väliraportti** (`ProgramReportKind.INTERIM`) — available whenever a plan is active and at least
one session is complete. Answers the owner's eight questions in order, with the last two —
"how does the remaining plan look" and "carry on or change something" — resting on the `remaining`
part of the aggregate. It may recommend a change of emphasis; it may not rewrite the plan, because
nothing in this flow can apply one.

**Loppuraportti** (`ProgramReportKind.FINAL`) — offered when the plan's last session reaches a
terminal status, and available after that from the plan itself. It gets the same aggregate with
`phase = FINISHED` and `remaining = null`, and it ends with two extra things:

- **Alussa → lopussa**, a short before-and-after with the two or three changes that actually
  measured.
- **Suositus seuraavalle jaksolle**, a paragraph of emphasis — not a programme.

## 5. What next

The final report ends with a recommendation for the next block. The person is then offered
`[ Luo seuraava ohjelma ]`, which asks first what to emphasise:

Jatka nykyisellä tasapainolla · Parempi juoksukunto · Nopeampi juoksuvauhti · Pidemmät
juoksumatkat · Voima ja lihaskunto · Painonhallinta · Palautuminen ja kevyempi harjoittelu · Oma
tavoite (free text)

The generation step then asks the model for **plan JSON in the existing schema v1**, and hands it to
`PlanJson.parse` → `PlanValidator.validate` → `TrainingRepository.importPlan`. This is the whole
reason it is affordable: there is no new persistence path, no new validation, and a plan the model
gets wrong is rejected by the same validator that rejects a plan a human gets wrong. See
[PLAN_SCHEMA.md](PLAN_SCHEMA.md).

Two rules the prompt states explicitly, because the failure modes are obvious and bad:

- **The new plan continues from where the last one ended**, not from a generic beginner's baseline.
  The aggregate's last-half run figures are its starting point.
- **A finished programme is not a reason to make the next one harder.** If adherence was poor, or
  the RPE trend rose while the pace did not, or the person kept reporting sessions as too hard, the
  next block holds or eases. The prompt says so; the aggregate gives it the evidence.

Before anything is saved, the plan is shown as a summary — main goal, sessions per week, the shape
of the running and the strength work, the direction of progression, what changed from the previous
plan — with `[ Aloita uusi ohjelma ]` and `[ Muokkaa tavoitteita ]`. Import happens on the first
button and not before.

## 6. Storage, and a reversal

Per-session analyses are deliberately not stored: `AiAnalysisState` says so, and the reason given is
that a machine opinion should not accumulate beside the training log and go stale.

**A programme report is stored, and the difference is real.** A session analysis is about a day that
will be superseded tomorrow; a final report is a document about a period that is closed and cannot
change. Re-running it a month later on the same finished plan costs a request and returns the same
thing. So reports get a table of their own, keyed by plan and kind, holding the text, the prompt
that produced it, the model used and the time — and an interim report is replaced by the next
interim report for the same plan, while a final report is written once.

Regeneration is explicit: a "Luo uudelleen" action, never automatic, because the stored report is
the one the person read.

## 7. Slices

| Slice | Contents | State |
| --- | --- | --- |
| 1 | `TrainingProgramAnalysis` + the pure aggregation, unit-tested | **built** |
| 2 | Both prompts, rendered from the aggregate | **built** |
| 3 | Repository assembly and the ViewModel entry point | planned |
| 4 | UI: request, read, show the prompt, regenerate | planned |
| 5 | The report table and schema migration | planned |
| 6 | Richer post-session feedback fields | planned |
| 7 | "Mitä seuraavaksi", the goal picker and plan generation | planned |

Slices 1 and 2 are the ones the principle at the top is about, and they are the ones the rest
cannot be written without.

### Where the data comes from today

Everything the aggregate needs already exists in the data layer, with one gap:

- Plan, sessions, statuses, events → `TrainingRepository`
- Completion outcome, RPE and "miltä tuntui" → `activeWorkoutOutcomeFor`, from the completion
  event's payload
- Run measurements → `IntervalsRepository`, matched to sessions by `matchedSessionId`
- Recovery → `OuraRepository`

**The gap is heart-rate zones.** The owner asked for them; intervals.icu publishes
`icu_hr_zone_times` on an activity, and this app has never fetched it, so no stored session has
them. Adding them means a DTO field, a column, a migration and a mapper — worth doing, and out of
the first slices rather than smuggled into them. Until then the aggregate carries average and
maximum heart rate, which supports the pace-against-heart-rate comparison the owner actually
described; a zone distribution would add the shape of a session, not its trend.
