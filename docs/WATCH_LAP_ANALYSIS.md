# Watch-lap analysis restoration — 27 September 2026

The published `45fad49` version read Suunto lap messages from the original FIT file. The GUI
working tree was based on `9e3be95` and omitted that implementation. Startup fix1 retained both
historical database shapes, including lap rows, but did not restore the reader or prompt wiring.
The reported prompt consequently contained planned stages and kilometre splits without measured
laps. The model correctly said those inputs could not establish the repetitions' actual pace.

This correction restores the reviewed lap-reading changes from `45fad49`, without replacing the
GUI files or the v18 migration. No schema version change is needed. The full path is now:

`IntervalsClient.originalFile` → `FitLaps` → `IntervalsDao.replaceLaps` →
`IntervalsRepository.observeMatchedRunMetrics` → `SessionAnalysisRepository` →
`AnalysisPromptBuilder` → the configured AI provider.

Normal sync requests up to six previously unfetched running files, newest first. A 404 records
that no original file exists; a temporary request failure leaves the fetch pending for retry.
Only timer time, distance and heart-rate lap summaries are persisted. The original file, including
any GPS track, is discarded. Disconnecting clears lap rows and fetch markers with the other
Intervals caches; the existing connection-generation guard prevents a late request restoring them.

Automatic analysis waits for the lap fetch as well as the existing split-fetch requirement.
A manual completed-run analysis fetches missing laps for its specific matched activity, even
outside the current sync window. If that request fails, a retryable UI message appears and no
provider call or durable billing receipt is created. A confirmed no-file response still permits
analysis of the available measurements. Previously saved AI results are not automatically billed
again or rewritten; close the saved result and explicitly request a new analysis to use new data.

The prompt contains watch laps before kilometre splits. Equal lap/stage counts permit an ordered
comparison, explicitly labelled as a proposed correspondence that must also fit the measured
durations/distances; the count alone does not prove the Guide was followed. Other counts keep
unlabelled measured laps. Distance targets include computed target time and signed time difference;
timed efforts include actual lap pace beside the planned pace. Planned stages remain labelled as
planned and kilometre splits are never presented as interval laps.

The completed card and expanded watch metrics offer `Kierrokset (N)` with measured lap time,
distance, pace and mean heart rate. No lap rows are fabricated for missing measurements.

Regression coverage includes synthetic FIT/gzip/zip inputs, HTTP/cache/retry/disconnect behavior,
both manual and automatic provider paths, a twelve-stage 3 × 6 minute prompt, and an Android UI
test that expands and collapses the actual completed card's lap list. Synthetic times are test
fixtures, not claims about the user's recorded workout. Exact measured results are recorded in
`PROJECT_STATUS.md`.
