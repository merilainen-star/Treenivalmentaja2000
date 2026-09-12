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

## 5. What next — built

The final report ends with a recommendation for the next block. The person is then offered
`[ Luo seuraava ohjelma ]`, which asks first how the finished block felt and then what to
emphasise. Three separate consents, deliberately: asking for a programme is not agreeing to one.

**How did it feel** — liian kevyt · hieman liian kevyt · sopiva · hieman liian raskas · liian
raskas. Skippable, and a skip is recorded as *absent* rather than as "sopiva". This is the one
piece of evidence the measurements cannot supply: a programme can look successful in every figure
and still have been more than the person wanted to carry. Each answer carries its own instruction
into the prompt — "liian raskas" says *kevennä kokonaiskuormaa, vaikka mittarit näyttäisivät
kehitystä*.

**What to emphasise:**

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

**The summary is computed from the plan, not asked from the model.** `summariseProgramPlan` counts
the generated sessions: how many, per week, by type, weekly kilometres from the first week to the
last, the longest run, which movements recur, which way the volume moves. Asking the model to
describe what it just wrote invites a description that flatters the plan or quietly disagrees with
it; counting the sessions cannot. The one thing quoted from the model is the plan's own
`description`, which is its statement of intent and belongs to it.

Two comparisons are made against the **previous programme as it was actually run**, not as it was
planned: sessions per week, and weekly kilometres. The old plan's intentions are not the level the
person reached, and a next block anchored to them would be anchored to a wish.

A plan that fails validation is shown with the validator's own Finnish messages — the same ones a
hand-written import produces, naming the field and the rule — and a retry, because that is the
failure a second attempt actually fixes.

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
| 3 | Repository assembly and the ViewModel entry point | **built** |
| 4 | UI: the card on the calendar, request, read, show the prompt | **built** |
| 5 | The report table and schema migration | planned |
| 6 | ~~Richer post-session feedback fields~~ — dropped, see below | **not doing** |
| 7 | "Mitä seuraavaksi", the goal picker and plan generation | **built** |
| 8 | Heart-rate zones and app-computed kilometre splits | **built** |

Slices 1 and 2 are the ones the principle at the top is about, and they are the ones the rest
cannot be written without.

**Slice 6 was dropped on the owner's reading, and the reading was better than the design.** More
fields at the end of every session is friction on every session, for a question that is really
about the *block*. What replaced it is one question asked once, when the programme ends: **miltä
päättynyt ohjelma kokonaisuutena tuntui** — liian kevyt · hieman liian kevyt · sopiva · hieman
liian raskas · liian raskas. It is not stored yet (nor is the report), and it exists for exactly one
consumer: the next programme's prompt. See § 5.

### What slices 3 and 4 settled

**Where the card lives.** On the calendar, above the day list, not on a workout card. It is about
the plan and not about a day, and a card about the plan sitting between Tuesday and Wednesday would
read as belonging to one of them. That position also earns it something `AiAnalysisSection` is
forbidden: it may explain itself when it has nothing to offer. An explanation repeated across every
session in a ten-day window is an advertisement; one card at the top of the calendar is an answer.

**"Not enough data" is a state, not a failure.** A plan two sessions old genuinely has no report in
it, and the error treatment would claim something went wrong when nothing did. The threshold is
three completed sessions — enough for a shape, few enough that a plan is not silent for a month.

**The report opens in a sheet, not in the card.** This changed on 2026-09-07, after the owner
installed it and found what a phone shows and a screenshot test did not: the calendar lays its
children out in a `Column`, which does not scroll and gives each child what is left over, so a
loaded report filled the screen, could not be scrolled past its first screenful, and left the day
rows measured at zero height. The feature had eaten the screen it was a feature of.

The card on the calendar now stays a fixed few lines — title, the report's own first paragraph
capped at two, and *Lue raportti* — and the document itself opens in a full-height
`ModalBottomSheet` with one scrolling region and nothing competing underneath. That is also the
right shape for what it is: a document asked for deliberately, organised into fact, reading and
advice, which a third of a screen serves badly. The day list additionally carries
`Modifier.weight(1f)`, so nothing placed above it can starve it again.

**The report's headings are rendered as headings.** The first version printed the model's `##`
markers literally, which turned the one thing the report is organised around into punctuation. The
card now splits the text into headings and paragraphs — deliberately not with a Markdown parser,
because the prompt asks for a fixed set of headings and prose between them, and no other syntax can
appear.

**Nothing is stored yet.** A report lives as long as the ViewModel, exactly as a session analysis
does. That is a limitation here rather than a design — see § 6 — and slice 5 is what fixes it.

### Where the data comes from today

Everything the aggregate needs already exists in the data layer, with one gap:

- Plan, sessions, statuses, events → `TrainingRepository`
- Completion outcome, RPE and "miltä tuntui" → `activeWorkoutOutcomeFor`, from the completion
  event's payload
- Run measurements → `IntervalsRepository`, matched to sessions by `matchedSessionId`
- Recovery → `OuraRepository`

**The gap was heart-rate zones and lap splits, and slice 8 closed it.** The owner asked for both,
twice: the per-session analyses kept saying they could not judge the effort properly, one of them
in as many words — *"Kierrosjakojen puuttuessa rauhallisen alun ja lopun toteutumista ei voi
varmistaa."*

- **Zones** come free with the activities request. intervals.icu publishes `icu_hr_zones` (the
  upper beat bound of each zone) and `icu_hr_zone_times` (the seconds in each) on the activity
  itself, so it costs two entries on the `fields` line and two nullable columns. Both are stored
  per activity rather than per athlete, because a zone table is not a constant — it moves whenever
  the threshold is recomputed, and a run from March has to be read against March's zones.
- **Splits do not exist as an endpoint.** intervals.icu publishes no per-kilometre data: an
  activity has one average pace and one average heart rate, and `icu_intervals` is its *detected*
  interval structure, which on a steady run is one block. So the app computes the splits itself
  from the recorded streams — `time`, `distance`, `heartrate`, `altitude` — which is the principle
  at the top of this document applied to the one measurement that most needed it. The kilometre
  mark is interpolated between the samples that straddle it rather than charged to whichever
  sample lands past it; at one sample a second that is worth up to three seconds a kilometre.

Streams are the only per-activity request in the integration, so the sync rations them: runs only,
a kilometre or more, newest first, six per sync, and **every attempt is recorded whether or not it
produced anything** — otherwise a treadmill run with no distance channel would be re-requested
forever and would crowd real runs out of the budget.

Where each lands:

| | Per-session prompt | Whole-programme prompt |
| --- | --- | --- |
| Zones | every zone, its beat range, its time and its share | totals per planned intensity |
| Splits | every kilometre: pace, heart rate, climb | not sent |

Splits are deliberately absent from the programme report. Forty runs at ten lines each is four
hundred lines to answer a question the per-intensity zone totals answer in twenty. What the
programme report gains instead is the one comparison no summary figure could make: **whether the
easy days were actually easy.** A block of easy runs averaging 148 bpm might be eight easy runs, or
six easy ones and two that were raced, and the averages cannot tell those apart.

Two things are refused rather than guessed at. A run the strap recorded nothing for is counted out
of the group entirely, not folded in as zeros — that would make every group look easier than it
was. And where the zone table moved mid-programme, the beat ranges are dropped and only the zone
numbers are quoted, because a range that was true in week 1 and false in week 7 is a fabricated
fact about half the runs.

## Import safeguards (7 September 2026)

Next-programme generation reads standing constraints directly from Settings persistence and
includes them in the request, alongside the goal and available final report. Local validation
requires the requested start date, exact time-zone id, weeks 1 through the requested count, and
each session date inside its declared programme week. Free-text constraints are prompt context,
not rules the JSON validator can prove; the owner checks their fulfilment in the preview.

Choosing the generated programme first runs the ordinary unconfirmed import. When it would
replace the active plan, the existing import dialog explicitly warns that its sessions and
history will be deleted. Cancel keeps them; only confirmation retries with `confirmed = true`.
The sample reset in Settings has its own explicit destructive confirmation.


## Programme output budget (12 September 2026)

Next-programme requests explicitly select `AnalysisTask.PROGRAM`: 32,768 output tokens,
compared with 8,192 for prose analyses. All three provider clients send that task budget.
The previous shared prose ceiling could be exhausted by reasoning before any visible answer.
OpenAI documents this possibility in its [reasoning guide](https://developers.openai.com/api/docs/guides/reasoning).
The screenshot alone does not establish which provider stop reason occurred.

Provider token-limit stop reasons now produce a specific Finnish error when the response is
empty or the task requires complete programme JSON. Retrying the identical request is not
offered for a known ceiling; the message directs the owner to select another model. Partial
prose remains visible, as before. Refusal guards, local plan validation, and explicit import
confirmation remain in place. No automatic extra paid request is made. The larger ceiling
allows more billable output when used; it is not a guarantee of successful live generation.

Local HTTP fixtures cover budgets, empty token-limited output, partial programme rejection,
and preservation of partial prose for all providers. The ViewModel flow test checks that the
report uses the prose task and its successor uses the programme task.
