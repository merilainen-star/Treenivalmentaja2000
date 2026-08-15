# Inspiration

Not a roadmap. This is a parking lot for ideas seen elsewhere that might be worth a proper design
someday. See [ROADMAP.md](ROADMAP.md) for what is actually planned — since 2026-08-15 its Next
Milestone lays out a phased path to an AI coach (Strava first, then a deterministic readiness
rule, then read-only AI comments, then AI-proposed changes behind user approval), and the ideas
below fed that plan. What has not been adopted there remains just an idea.

## AI coach, seen in a friend's app (2026-08)

A friend built a training app around the same Oura-plus-training-log idea, but the opposite way
round: the user logs sets/reps/kg by hand, and an AI coach (their own OpenAI key) both writes the
next workout and reviews the one just finished. A few things stood out as good *ideas*, independent
of whether this app ever grows an AI feature:

- **Show the actual prompt.** Before generating a workout, the app has a "Show AI request context"
  panel that displays exactly what is being sent — coach instructions, recent history, and so on. If
  this app ever calls out to an LLM, showing the real request rather than just the response would
  keep faith with how [SECURITY.md](SECURITY.md) and [PRIVACY.md](PRIVACY.md) already treat data
  leaving the device: nothing invisible, nothing assumed.
- **Liftwise PR detection, honestly hedged.** Post-workout analysis calls out a new personal record
  per exercise (e.g. "22 kg × 10 beats 22 kg × 9 from 11.8"), but explicitly caveats it against
  equipment being comparable — cable/machine setups vary gym to gym, so a same-looking set on a
  different machine isn't really the same lift. Any future rep-max tracking here should carry the
  same honesty rather than asserting a PR it cannot actually verify.
- **Flag the gap between plan and reality.** One analysis noticed the session's own title promised
  a squat that was never logged, and used that to shape the next session's advice. This is close to
  what the deterministic Training Engine already does for missed sessions
  ([TRAINING_ENGINE.md](TRAINING_ENGINE.md)) — the same instinct, just applied one exercise at a
  time instead of one session at a time.
- **Three-part post-workout write-up**: coach feedback (prose), progress (bullet list, one arrow per
  exercise), next time (concrete numeric target per exercise). A reusable shape for presenting any
  kind of generated or rule-based summary, AI or not.

None of this fits the current architecture without a real design pass — this app has no manual
set/rep logging at all, and adding an LLM call raises exactly the privacy questions
[PRIVACY.md](PRIVACY.md) and [DECISIONS.md](DECISIONS.md) exist to answer carefully. Written down so
it isn't lost, not because it's next.
