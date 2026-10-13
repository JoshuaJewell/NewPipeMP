# Adaptive Shuffle: consolidated specification

Component: NewPipeMP (Acetate), with one offline pipeline on the desktop side.
Supersedes: *Adaptive Shuffle design spec (v1)*, *Adaptive Shuffle v1.1 repetition fixes*, *Audio-feature extraction pipeline brief*.
Grounded against: `dev` @ `5a9b0f0` ("Post-merge features fix"), 26 September 2026.

This document merges the three earlier specs into one and corrects them against the code as pushed. Where the earlier specs and the code disagree, the code is described as it is and the disagreement is flagged.

---

## 0. Source of truth

The build that ran on the phone (v1.1) was ahead of `dev`: its debug screenshots at n=241 and n=570 show features that `dev` never had (screen state, output route, seek direction, pause subtypes, volume gestures, drop-off consistency, average leave-point). Its source was never pushed and is now lost (13 October 2026). Its data survives: a database it exported (schema version 18) shows what its versions 15 to 18 added, and its decision log holds about 1,800 events in its own blob formats.

So there is no other branch to wait for. `claude/amazing-archimedes-vdf3b0` is the main line: §5 was checked against `dev` and fixed there, §4 is rebuilt there from this spec and the exported data (§10), and it opens the v1.1 build's databases (schema version 19, a no-op step from 18).

---

## 1. Purpose and scope

A shuffle mode in which the next track is chosen by a contextual bandit trained on the user's own listening behaviour. It sits alongside the existing plain and rating-weighted shuffle modes and does not replace them.

It is a per-step decision: given everything known now, which single track plays next. Cohesive playlist generation (sequence shape, mood arcs) is a separate feature with a separate objective and is out of scope here.

The model is LinUCB: a linear reward model with closed-form incremental updates and a calibrated uncertainty term. The choice rests on three properties: trivial inference cost, a weight vector that can be read in the debug view, and an uncertainty estimate that can be surfaced as "exploiting" versus "exploring". A neural-linear variant is a possible later graduation; everything here stays linear.

Decisions carried over from the v1 design conversation:

- The rating feature is always on and treated like any other feature. Whether it carries signal is for the weights to show, not for configuration to assume.
- Model state (`A`, `b`, scaler) persists to disk and updates incrementally; a rebuild from history is available on demand.
- UI surfaces: an exploit/explore indicator and a confidence value on now-playing, plus a debug view showing weights and recent decisions.

Out of scope throughout: cross-device model sync, multi-profile models, any telemetry leaving the device.

---

## 2. As-built architecture (`dev`)

### 2.1 Files

`app/src/main/java/org/schabi/newpipe/adaptive/`

| File | Role |
|---|---|
| `AdaptiveShuffleConfig.kt` | Constants: ridge λ, α, reward weights, thresholds, cold-start defaults |
| `FeatureSchema.kt` | Feature names (27), scaled indices, schema hash, debug descriptions; `DecisionContext`, `CandidateItem`, `QueueOrigin` |
| `FeatureExtractor.kt` | Raw feature vector from context + candidate |
| `FeatureScaler.kt` | Welford running mean/variance on 4 continuous indices |
| `LinUCB.kt` | Model, scoring, rank-1 updates, serialisation |
| `Matrix.kt` | Hand-rolled SPD inverse, outer product, quadratic form |
| `RewardCalculator.kt` | Step-function reward (§2.4) |
| `RawInputsCodec.kt` | Versioned binary encoding of decision-time inputs (v2) |
| `CandidateMaterialiser.kt` | Queue items to `CandidateItem`, reading stats from DB |
| `AdaptiveShuffleEngine.kt` | Owns model and scaler; `chooseNext`, `resolveOutcome`, `rebuildFromPending` |
| `AdaptivePlayerIntegration.kt` | Player glue: candidate sampling, context, session state, confidence badge |
| `AdaptiveShuffleHolder.kt` | Process singleton; runs `Bootstrap` |
| `Bootstrap.kt` | One-shot prior from aggregate `playback_statistics` |
| `TelemetryLog.kt` | On-device JSONL, 10 MB rotation |
| `ui/AdaptiveShuffleDebug{Activity,Fragment}.kt` | Debug view, reached from debug settings |

`database/adaptive/`: model-state and pending-event entities and DAOs, plus `AdaptiveShuffleFeatureQueriesDAO` (uploader aggregates, playlist co-membership, offline flag).

Tests in `app/src/test/java/org/schabi/newpipe/adaptive/` include synthetic-regret and replay tests as the v1 spec required.

### 2.2 Player hook and candidate scope

Two entry points in `Player.java`, both gated on `adaptive_shuffle_enabled_key` plus ExoPlayer shuffle mode:

- `adaptiveResolveAndPrePick`: on automatic inter-track transition. Resolves the ended track (as a full completion; progress is passed as duration) and pre-places the engine's pick after the now-current track.
- `advanceQueueWithAdaptiveSelection`: on manual advance. Resolves with real progress, picks, moves the pick to `current + 1`, advances.

**Candidate scope differs from the v1 spec.** The spec said "entire library". The code scores a random sample of at most 30 items from the *queue tail* (`pickNextIndex`, `samplingCap = 30`). Played items fall behind the queue index, so nothing repeats within one pass of a queue. Repetition the user hears is therefore **across sessions**: with ~1500 tracks and 30 sampled per decision, a given track enters about 2% of candidate sets; across a 50-decision session that is roughly a 64% chance of appearing at least once, and a track carrying a large structural boost wins whenever it appears. This matches the reported pattern of the same few tracks turning up most sessions without recurring back to back.

Whether sample-of-30 or whole-queue scoring is wanted is a decision for Joshua (§9). At this library size whole-queue scoring is cheap; the sample was presumably added for materialisation cost (each candidate costs several blocking queries).

### 2.3 Features (27, `dev`)

Context: hour sin/cos, day-of-week sin/cos, log session position, recent early-skip rate (last 10), previous auto-queued, previous completed, queue origin one-hot (single, playlist, channel, other).

Item: log duration, rating centred to [-1, 1], log lifetime plays, lifetime skip ratio, lifetime completion ratio (cold-start defaults below 3 plays), log days since last play, audio-only, live.

Cross and aggregate: same uploader as previous, uploader average rating, uploader completion rate, uploader skip rate, log playlist co-membership with previous, downloaded offline.

Bias: constant 1.

### 2.4 Reward (`dev`)

v1 step function on completion ratio `cr = progress / duration`:

```
+1.0  if cr >= 0.85
-1.5  if cr <  0.15
-0.2  otherwise
+0.5 * min(2, restart_count_delta)
```

### 2.5 Decision rule

Deterministic UCB: `argmax(θᵀx + α·sqrt(xᵀA⁻¹x))`, α = 1.0, λ = 1.0.

### 2.6 Persistence

- `adaptive_shuffle_model_state`: singleton row with LinUCB blob, scaler blob, `n_events`, `feature_schema_hash`.
- `adaptive_shuffle_pending_events`: one row per decision with scaled feature vector, **raw inputs blob** (codec v2), predicted reward, uncertainty, candidate count, resolved flag, reward, resolved time.
- A schema-hash mismatch on launch triggers `rebuildFromPending`, which re-derives feature vectors from raw inputs and replays stored rewards.

Pending events store the scalar reward only, not the completion ratio or restart delta. Telemetry resolution lines do record `completion_ratio` and `reward` per stream and timestamp (§6.4 depends on this).

---

## 3. What the model's behaviour showed

Observed through the debug view at n = 78, 124, 241, 570 (on-device build):

- Weights reallocated violently as features were added. Time of day started as the dominant feature and decayed to near zero once screen state and output route existed. Offline availability flipped from the strongest positive to a stable negative.
- By n=570 the two largest weights were both uploader aggregates (uploader early-skip rate +1.24, uploader completion rate +1.17).
- The listening experience was poor. The same handful of tracks from one uploader recurred, including tracks the user abandons most of the time (one sits under 50% average completion from an album where most tracks sit under 10%).

The debug-view narration at the time read stabilising weights as convergence. That was wrong: stable weights coexisted with popularity collapse. Section 5 shows that several of the headline weights were artefacts of collinearity and were never interpretable individually. The validation in §7 exists so the experience is measured directly.

---

## 4. v1.1 instrumentation

Specified from the debug screenshots. The v1.1 build's code is lost (§0); this is rebuilt in `claude/amazing-archimedes-vdf3b0`, recording into the columns that build left, in the same form, so its counts carry on (§10).

Context:
- screen on during playback
- output route one-hot: phone speaker, Bluetooth, wired headphones, other (HDMI, USB, cast)

Item (lifetime, per play):
- forward seeks per play
- backward seeks per play
- pauses then resumed per play
- pauses then skipped per play
- volume-up gestures per play
- volume-down gestures per play
- average completion ratio at which the user leaves this track
- consistency of drop-off point (dispersion of leave points)

Interpretation notes from the data, for the debug-view descriptions: forward seek settled weakly positive, which argues against labelling it "this bit drags"; it may indicate active engagement. The descriptions should not assert meanings the data has contradicted.

Still unlogged (from the hooks audit, not required here): share events, notification-click attribution, explicit thumbs-down, session boundaries.

---

## 5. Defects found in `dev`

Each is stated with its location and consequence, as found in `dev`. All are addressed in `claude/amazing-archimedes-vdf3b0` (§10).

### D1. Queue origin is hard-coded, making one feature identical to the bias

Both `Player.java` entry points pass `QueueOrigin.OTHER`. So `queue_origin_other` is 1 on every decision and the other three are always 0. `queue_origin_other` is the same column as `bias`; ridge regression splits the weight evenly between them.

Evidence: the n=570 screenshot shows "Queue came from search or related" and "Constant baseline" both at exactly −0.5869; at n=241 both at −0.3433. The three zero-weighted queue-origin features in every screenshot are the same bug.

Fix: derive origin from the `PlayQueue` subclass (`SinglePlayQueue`, `PlaylistPlayQueue`, `ChannelTabPlayQueue`, other), or remove the queue-origin features until it is derived. Until then, neither the bias nor the queue-origin weight is interpretable.

### D2. Uploader completion and skip rates sum to one

`AdaptiveShuffleRepository.getUploaderStats` computes both as fractions of `completions + skips`, so `uploader_completion_rate + uploader_skip_rate = 1` for every uploader with data (cold default 0.5 + 0.2 = 0.7). Together with the bias this is exact collinearity. The model's contribution from the pair is `w_skip + (w_comp − w_skip)·c`: an intercept plus a slope equal to the *difference* of the two weights.

At n=570 (+1.24, +1.17) that is a near-flat slope on uploader completion and an offset of about +1.2 for any track whose uploader has play history, against about +0.83 for a cold uploader. The two "strongest weights" were a split of one mostly constant term, and reading them as separate findings (as the n=570 commentary did) was a mistake.

The same near-collinearity holds for the track-level `skip_ratio` and `completion_ratio` (both over lifetime plays, with mid-skips making up the remainder).

Fix: part of Fix 2 (§6.3). Keep one rate per level, never both.

### D3. The live path feeds scaled vectors to the scaler

`AdaptiveShuffleEngine.resolveOutcome` calls `scaler.observe(featureVector)` where `featureVector` is decoded from `featureVectorBlob`, which holds the **scaled** vector. `rebuildFromPending` correctly observes raw vectors. On the live path the running mean and variance for duration, play count, days since played and session position are progressively contaminated with standardised values, so the transform drifts and `A`/`b` accumulate vectors under a moving transform. This likely contributed to the weight instability attributed to feature reallocation.

Fix: Fix 0 (§6.1).

### D4. Pending-event lookup is bounded to the last 50 rows

`resolveOutcome` finds the event id with `findLatestUnresolvedForStream` and then looks it up in `getRecent(50)`. An event older than the 50 most recent rows resolves with no model update and no error. Rare in normal use; possible after a long pause with many decisions queued.

Fix: fetch by id (add `getById` to the DAO).

### D5. Uploader aggregates are computed over policy-chosen plays

The uploader aggregates count every play of every track by that uploader, and since adaptive shuffle has been on, those plays were largely chosen by the model. Boosted uploaders get played more, accumulate more history, and D2's history offset grows. This is the feedback loop in miniature, and it applies to any analysis of the pending-events table: it records what the policy chose, not an unbiased sample of preference.

Mitigation: Fix 2 removes the absolute uploader term; §7 requires the contamination caveat on every replay result.

### D6. Mid-skips barely register

Covered by Fix 3. With the step reward, a 45% listen is worth −0.2, too weak to sink a track riding the D2 offset.

### D7. The bootstrap prior outvotes real evidence and leaks its own target

Found while implementing. `Bootstrap` writes one synthetic example per played stream (around 1500) at full weight, against a few hundred real decisions, so on a fresh install the prior dominates. Its reward is derived from the track's completion and skip counts, and those same counts are features, so the prior mostly teaches that `completion_ratio` predicts itself. It only runs on a fresh install (a schema mismatch rebuilds from pending events instead), so it probably did not shape the n=570 model, but it would shape any reinstall.

Fix: weight synthetic rows at 0.25, withhold the track's outcome counts from their features, and report zero events after bootstrapping.

### D8. Superseded pre-picks are never resolved

Found while implementing. On an automatic transition the engine pre-picks the next track and records a pending event. If the user then skips the current track, a fresh decision is made and the pre-pick never plays, but its pending event and snapshot remain. These are the many "Awaiting" rows in the debug view. They inflate the decision list, and a stale snapshot can later be resolved by an unrelated play of the same track.

Fix: at each decision, if the previous decision's track is not the one now playing, delete its pending event and snapshot.

---

## 6. Remediation plan

Ordered for attribution: each stage is shipped and observed before the next, so a behaviour change can be pinned on its cause. Each stage is independently revertible.

### 6.1 Fix 0: correctness (D1, D3, D4)

- D3: in `resolveOutcome`, observe the raw vector. Either rebuild it from `rawInputsBlob` via `RawInputsCodec.decode` + `FeatureExtractor.buildRawVector`, or persist the raw vector alongside the scaled one. Do not change the `update` input (the scaled vector used at decision time is correct for `A`/`b`).
- D1: derive `QueueOrigin` from the play-queue subclass at both call sites.
- D4: fetch the pending event by id.
- After shipping, run "Rebuild from history" once. The rebuild path is already correct for D3, and a rebuild re-derives features, so D1 applies to new events only (historical raw inputs recorded OTHER). Note that in the debug view.

No schema change beyond D1's effect on new data. Ship first.

### 6.2 Fix 1: cross-session recency exclusion

Because within-queue repetition is already impossible (§2.2), the exclusion must span sessions.

- Maintain the last N played `stream_id`s, seeded on launch from `stream_history` ordered by `access_date` descending, updated on each resolution.
- In `pickNextIndex`, drop excluded ids from the tail *before* sampling, so the sample of 30 is drawn from eligible items.
- Default N = 40; config key `adaptive_shuffle_recency_exclusion_n`.
- Starvation guard: if fewer than 20 eligible items remain, shrink the window until 20 remain. Never return no candidates because of exclusion.
- Exclusion affects candidates only, never the update path.

### 6.3 Fix 2: relativise uploader features (D2, D5)

Replace absolute uploader aggregates with within-uploader deviations, and keep one rate per level:

| Remove | Replace with |
|---|---|
| `uploader_completion_rate` | `completion_dev = track_completion_rate − uploader_mean_completion_rate` |
| `uploader_skip_rate` | removed (collinear with the above) |
| `uploader_avg_rating` | `rating_dev = track_rating − uploader_mean_rating` |
| track `skip_ratio` + `completion_ratio` | keep `completion_ratio`; replace `skip_ratio` with `early_skip_share` of non-completed plays, or drop it |

Deviations are uploader-neutral in expectation: they ask whether this track is better or worse than its uploader's baseline. A track can no longer inherit its uploader's standing, which is what let Desperate Measures carry Manifest Manifesto.

Cold handling: if the track has fewer than 3 plays or the uploader has no other played tracks, the deviation is 0.

Recommendation: keep no absolute uploader-affinity term in this stage. Whether genuine artist loyalty exists beyond sound is what the audio-feature experiment (§8) tests; putting the float back now would contaminate that test.

The raw-inputs blob already carries track counts and uploader aggregates, so deviations can be computed for historical events and the rebuild is retroactive. The uploader *mean* needed for the deviation must be the one at decision time; the codec stores uploader rates, not means over tracks, so either add uploader means to the codec (version bump) or accept current means for history and note it.

### 6.4 Fix 3: continuous reward (D6)

```
cr = completion_ratio
base = 1.78 * cr − 1.10          # cr=0 → −1.10; cr≈0.62 → 0; cr=1 → +0.68
early_skip_extra = cr < 0.15 ? −0.70 : 0
rewind_bonus = 0.50 * min(2, restart_delta)
reward = base + early_skip_extra + rewind_bonus
```

Requirements: monotonic in `cr`; clearly negative below about half; neutral around 0.6; clearly positive above 0.85; early skip penalised beyond the curve. Constants are tuned on the replay harness, not fixed by hand.

Pins: `cr` 0.08 → about −1.66; 0.45 → about −0.30; 0.85 → about +0.41; 1.0 → +0.68 (before rewind bonus).

Automatic transitions currently resolve with `progress = duration` (§2.2). That is correct for a track that played out, and remains so.

**Retroactive rebuild.** Pending events store only the reward scalar. Two routes:
1. From now on, store `completion_ratio` and `restart_delta` on the pending event (Room migration) so any future reward change replays exactly.
2. For history, join telemetry resolution lines (`stream_id`, `timestamp`, `completion_ratio`, `reward`) to pending events by stream id and nearest resolution time. The restart delta is recoverable because the old reward is `step(cr) + 0.5·min(2, delta)`. Events with no matching telemetry line (rotated out, or resolved before telemetry existed) keep the old reward and are counted in the rebuild report.

Batch Fix 2 and Fix 3 into one schema-hash bump and one rebuild.

### 6.5 Fix 4: Thompson sampling

Replace the UCB bonus with posterior sampling, one draw per decision shared across candidates:

```
θ̂ = A⁻¹b
θ̃ = θ̂ + α · chol(A⁻¹) · z,   z ~ N(0, I)
choose argmax θ̃ᵀx
```

Behind a `UCB | THOMPSON` config flag, compared on the harness before becoming default. Cholesky of a ~30×30 matrix per decision is negligible; hand-roll it in `Matrix.kt`.

Thompson sampling adds variety where the model is uncertain. It does not correct a confident wrong estimate, so it is ordered last and must not be presented as the repetition fix. The exploit/explore indicator needs redefining under Thompson (e.g. whether the sampled argmax equals the mean argmax).

---

## 7. Validation

### 7.1 Repetition metrics (new, live and in replay)

Surface in the debug view, computed over the last 100 decisions:

- `unique_tracks_per_100`
- `top_uploader_share_100`
- `top_track_share_100`

Also over the last 10 sessions: fraction of sessions containing each of the top 5 most-served tracks. This is the metric that captures the cross-session pattern in §2.2, which per-100-decision counts can miss.

These numbers track the experience. The weight vector does not, as §3 shows.

### 7.2 Replay harness

Extend the existing replay test to run historical pending events under: baseline, +Fix 0, +Fix 1, +Fix 2+3, +Fix 4. Report the repetition metrics and Spearman correlation of predicted versus actual reward for each.

Every harness report must print this caveat: *historical events were chosen by the old policy; replay is biased toward configurations that resemble it. Replay filters gross regressions; it does not show live improvement.*

The live test is the §7.1 metrics over several hundred events after each stage ships.

### 7.3 Existing tests

Keep the synthetic-regret test (sub-linear regret over 5000 steps) and extend it with a collinearity case: two features summing to one plus a bias, asserting the model's *predictions* stay correct even though individual weights are unidentifiable. The debug view should not present weights of collinear groups as independent findings.

### 7.4 Acceptance criteria

- Fix 0: a unit test drives `resolveOutcome` and asserts the scaler's means match raw-scale values; queue origin varies across the four play-queue types; a pending event older than 50 rows resolves and updates the model.
- Fix 1: over more than N decisions, no track recurs inside the window, including across a simulated session reset; starvation guard verified on a 25-track library.
- Fix 2: no absolute uploader aggregate in the feature vector; two tracks by one uploader with different histories get different uploader-derived values; no two features in the schema are linearly dependent on each other and the bias (checked by a rank test on a synthetic batch).
- Fix 3: reward monotonic in `cr`; pins in §6.4 hold within 0.01; rebuild report states how many historical rewards were recomputed versus carried over.
- Fix 4: with a fixed seed, repeated identical-context decisions differ under Thompson and not under UCB.
- Debug view shows the §7.1 metrics.
- Plain and rating-weighted shuffle modes are unchanged.

---

## 8. Audio-feature pipeline

### 8.1 Why

With no representation of how a track sounds, the model leans on uploader aggregates. After Fix 2 removes the absolute uploader term, the open question becomes sharper: **is the user's apparent artist loyalty reducible to how the music sounds, or is it loyalty to the artist that sound does not explain?** Content features make that testable.

### 8.2 Scope

Offline batch job on the desktop side over the canonical library (~1500 tracks). Not on-device, no learned audio model, no genre or mood tagging, locally held files only.

### 8.3 Language

Julia, with `ffmpeg` for decode.

- Spectral features (centroid, rolloff, RMS, zero-crossing): `DSP.jl`, `FFTW.jl`. No gap.
- MFCCs: `MFCC.jl` or hand-rolled over `DSP.jl`.
- Key and mode: chroma plus Krumhansl-Schmuckler, hand-rolled.
- Tempo: the weak spot. Julia has no established beat tracker; onset-envelope autocorrelation is hand-rolled and gives a coarse BPM. Emit a confidence score and treat low-confidence tempo as missing. If it proves unreliable, drop tempo before reaching for another language.

### 8.4 Join key (decision for Joshua)

Features come from files; the model needs `streams` rows. Ratings already round-trip from file tags into `streams.user_rating` via `HistoryRecordManager.saveStreamRating` and `RatingBackfillJob`, so a file-to-stream mapping exists. Reuse it. Preferred key order: stream URL stored in a tag, then content hash, never file path. **Joshua to confirm which key the rating mechanism uses before implementation.**

### 8.5 Features

Tier A, fed directly (about 7 dimensions): tempo (standardised, confidence-gated), key sin/cos on the circle of fifths, mode, spectral centroid mean, RMS energy mean, zero-crossing mean.

Tier B, stored but not fed raw: 13 MFCC means (optionally stds). Used for one feature: `audio_sim_to_recent_completions`, the cosine similarity between the candidate's MFCC means and the centroid of the last 10 completed tracks. Feeding 13 to 26 raw dimensions into a linear model at this event count would dilute every other weight. If one similarity scalar proves too coarse, the next step is PCA to 4 to 6 components offline.

Summarise frame features per track over the full track, or a central window skipping 10 s at each end if cost requires; record which.

### 8.6 Schema

```
stream_audio_features
  stream_id               INT PK FK -> streams.uid
  join_key                TEXT
  tempo_bpm               REAL
  tempo_confidence        REAL
  key_pitch_class         INT      -- 0–11
  mode                    INT      -- 0 minor, 1 major
  spectral_centroid_mean  REAL
  spectral_centroid_std   REAL
  rms_energy_mean         REAL
  rms_energy_std          REAL
  zero_crossing_mean      REAL
  mfcc_means_blob         BLOB     -- 13 × float64
  mfcc_stds_blob          BLOB     -- nullable
  analysis_window         TEXT     -- 'full' | 'central'
  extractor_version       INT
  extracted_at            INT
```

Room migration on the app side. The Julia job either writes through the desktop side's existing DB access or emits an intermediate merged on import through the existing export/import bundle path. Do not invent a new sync path.

### 8.7 Integration

- `FeatureExtractor` reads `stream_audio_features`; missing or low-confidence values encode as 0 after standardisation, so unanalysed tracks are neutral.
- Schema-hash bump and rebuild. Audio features are static per track, so joining current values onto historical events is valid; note this in the rebuild path.
- Extend `FeatureScaler` indices for the new continuous features.

### 8.8 Execution and validation

Idempotent (skip on matching `extractor_version` and unchanged file), resumable, parallel across files, with a per-track outcome manifest.

Validation:
1. Spot-check tempo, key and energy on 10 well-known tracks.
2. Coverage report: analysed, low-confidence tempo, decode failures.
3. The experiment: after integration plus about 100 further events, compare **predictions**, not weights, with and without audio features for tracks within a single heavily played uploader, and compare §7.1 metrics before and after. If audio features explain little of the within-uploader and between-uploader variance, that suggests artist loyalty that sound does not account for. Record the result either way. Given D2, this comparison is only meaningful after Fix 2 has landed.

---

## 9. Decisions for Joshua

1. ~~Push the on-device branch (§0).~~ Moot: its source is lost. One thing only it could have settled: the order of its output-route values (§10).
2. Candidate scope: keep the sample of 30 from the queue tail, score the whole tail, or score the whole library (§2.2).
3. Recency window N (default 40) and whether it should scale with library size.
4. Whether any absolute artist-affinity term returns after the §8 experiment.
5. Audio join key (§8.4).

Implementation-level choices (class placement, DI versus holder, serialisation format, fragment versus activity) are the implementer's call.

---

## 10. Implementation status

Branch `claude/adaptive-shuffle-improvements`, built on `dev` with TeamNewPipe `upstream/dev` merged in (182 commits; one conflict in `Player.toggleMute`, resolved by taking upstream's ExoPlayer-managed audio focus and keeping `recordVolumeChangeEvent()`). Not yet built or run on a device: the sandbox had no Android SDK. The adaptive core compiles and its 87 unit tests pass under a standalone Kotlin compiler; the player glue type-checks against the real play-queue classes.

Landed:

- **Fix 0.** D1 (`QueueOrigin` from the play-queue type; a `SinglePlayQueue` holding more than one user-chosen track counts as a playlist), D3 (scaler observes raw vectors), D4 (lookup by id).
- **Fix 1.** Recency exclusion seeded from `stream_history`, applied before sampling, with a starvation guard. Decision 9.3 taken by default: the window is a quarter of the queue tail, between 40 and 400 picks.
- **Fix 2.** Schema v3 (25 features). Removed `queue_origin_other`, track `skip_ratio`, and both absolute uploader rates; replaced uploader average rating with `completion_vs_uploader` and `rating_vs_uploader`; added `is_rated`. A test asserts the feature Gram matrix is full rank.
- **Fix 3.** Continuous reward with the §6.4 pins. Outcomes are now stored per decision, and rebuilds recover old outcomes from telemetry and write them back.
- **Fix 4.** Thompson sampling behind a settings toggle (off by default). Exploit/explore now means "the pick was or was not the highest mean prediction", which holds under both rules.
- **§7.1 metrics** in the debug view, over decisions that actually played.
- **D7, D8** as above.

Additions not in the plan:

- **Uploader spacing.** Neither of the last two uploaders may be picked next, relaxed before the recency window when candidates run short. Addresses "the same uploader keeps coming up" directly, without waiting for the model to learn it.
- **Evidence discount** (γ = 0.999, a memory of about 1000 decisions). Taste drifts, and decisions made by the collapsed policy should fade instead of anchoring the model indefinitely. It also stops uncertainty collapsing to zero, so exploration never fully stops.

Deviation from §6.4: outcomes are stored in a v3 trailer on `raw_inputs_blob`, not in new columns. A Room migration from version 14 would collide with any migration on the unpushed v1.1 branch, and two builds disagreeing about what version 15 means would crash on open. No schema change was needed for anything in this branch; only DAO queries were added.

What the simulation shows (`CollapseSimulationTest`, an invented listener, so it shows the mechanism, not the experience): with one uploader holding a track always finished and nine abandoned, the nine are served 0 to 0.2% of the time against a uniform share of 0.7 to 6.9%. With a 130-track library the model still rotates its favourites as fast as the 40-pick floor allows; at around 1200 tracks the scaled window makes the last 100 picks all distinct. In the large library, mean reward is only slightly above uniform after 1200 decisions, because most tracks have been heard at most once. At your listening rate, expect the learning to show slowly. The repetition is what should change quickly.

Validation, landed after the merge into `claude/amazing-archimedes-vdf3b0`:

- **§7.2 replay harness** (`ReplayHarness`, `StagedReplayTest`). Stages: baseline (the v2 schema, step reward, hard-coded origin and scaled-vector scaler of `dev` @ `5a9b0f0`), +Fix 0, +Fix 1, +Fix 2+3, +Fix 4, and the shipped defaults (UCB with the evidence discount). The log holds only the chosen track, so prediction is progressive validation on logged outcomes (Spearman against each stage's own reward, and against completion ratio, which is the column that compares across stages), and the §7.1 metrics are computed over picks simulated against the library seen in the log so far. Every report prints the §7.2 caveat. CI runs it on a synthetic log from a collapsed logging policy; to run it on real data, export resolved events with `sqlite3 -separator $'\t' newpipe.db "SELECT chosen_at, stream_id, reward, hex(raw_inputs_blob) FROM adaptive_shuffle_pending_events WHERE resolved = 1 ORDER BY chosen_at" > events.tsv` (after "Rebuild from history", so outcomes are stored) and set `ADAPTIVE_REPLAY_EVENTS` to that file.
- **§7.3 collinearity case** in `LinUCBSyntheticRegretTest`: two features summing to one plus the bias; regret stays sub-linear, held-out predictions match, `w1 − w2` and `w2 + bias` are recovered, and the individual weights are shown not to be.
- **Scaler fix, found by the harness.** `FeatureScaler` divided by `std + 1e-8`. While every value of a scaled feature seen so far is identical (for example the first two resolved tracks both never played before), the next different value was multiplied by about 10⁸; UCB then favoured it for its uncertainty, and one update made `A` uninvertible, so the next decision threw. The standard deviation is now floored at 1e-3, which leaves every existing test result unchanged.

Synthetic replay, for orientation only (invented listener, 300 tracks, 1500 decisions; the caveat applies): rank correlation with completion is about 0.20 to 0.22 in every stage, and distinct tracks in the last 100 picks go from 25 to about 84 once Fix 1's filter is on, with top-track share from 0.14 to 0.02.

Edge cases in playback and statistics, found by tracing the player rather than the model:

- **Playlist order leaking through.** Picks were only made on a natural track end, into the slot after the current track. A manual skip, a queue tap, previous or an error skip left that slot in playlist order, and turning shuffle on did not pick at all, so the first track after enabling it was always the playlist's second. The player now keeps that slot filled with a pick whenever the current item changes (`Player.maybeAdaptivePrePick`), and "next" uses the waiting pick immediately. All adaptive work runs on one thread in order (`AdaptiveShuffleHolder.SCHEDULER`), so a resolution always lands before the pick that follows it.
- **Candidates from the whole queue** (`ShuffleSession`): a shuffled queue is one pass, and every track not yet played in the pass is a candidate wherever it sits. Before, only tracks after the current one were, so starting a playlist at track 50 never shuffled in 1 to 49, and repeat-all wrapped back into playlist order. A remote playlist now loads further pages as it shuffles. Queue items with no `streams` row (most of a remote playlist) used to be dropped, so never-played tracks could not be chosen and a sample with no rows fell back to playlist order; rows are now created from the queue item, and if nothing can be scored the pick is uniformly random rather than the playlist's next track. The user's "play next" is never pushed back.
- **Shuffle state.** Adaptive mode never created the queue's shuffle backup, so the notification and listeners reported shuffle as off and turning it off did not restore the playlist order. It now enters shuffle mode without reordering (`PlayQueue.markShuffled`).
- **Lifetime statistics were mostly not recorded.** Next (in the app, notification or Bluetooth) sets the queue index before ExoPlayer moves, so the discontinuity handler saw no change and recorded no skip; on a natural transition it read the position after ExoPlayer had moved, so completions were lost except at the end of a queue. Every listen is now closed in one place (`Player.onListenLeft`) with the position it actually reached, and judged by `ListenJudge`. Play time for skips is counted, and leaving while paused no longer counts the paused stretch twice.
- **The playlist's first track.** "Play all" then shuffle made the first track a starting point, not a choice; leaving it within 30 s of turning shuffle on is no longer recorded as anything, in every shuffle mode.
- **`completion_rate`.** `play_count` rises on every resume and rebuffer, and the feature divided by it. It now divides by finished listens (completions plus skips). Renamed from `completion_ratio`, so the schema hash changes and the model rebuilds on first launch. Stored counts from before this change are still the old, sparse ones; they improve as new listens are recorded.
- **Misattribution.** A failed item carries no stream info, and a new track's database ID arrives asynchronously; in both gaps every skip and second of play time went to the previous track. Tracking is now reset synchronously on every item change, and a late ID for a track already left is ignored.
- **Unplayable items.** A removed, blocked or unresolvable track plays two seconds of silence and is skipped; its decision was dropped, so to the model it stayed unexplored and kept being picked. It is now excluded for the rest of the process. Separately, a network-type failure reloaded the same item forever (a downloaded track whose file is gone, offline, looped with a notification every few seconds); `PlaybackFailurePolicy` now retries twice, then skips, and stops after three failed items in a row.
- **Sessions and stragglers.** `resetSession` was never called, so session position and the recent-skip window ran on across queues and days; they now reset on a new queue or after 30 minutes idle. Decisions in flight when the player closes, when shuffle is turned off or when a track is removed are dropped instead of left awaiting.

The pure parts (`ListenJudge`, `PlaybackFailurePolicy`, `ShuffleSession`, the engine changes) are unit tested; the integration type-checks against stubs of the queue types. The `Player.java` glue passes checkstyle and a syntax check but has not been compiled: there is still no Android SDK in the sandbox.

Pick cost. A pick ran about six queries per candidate, thirty candidates a pick, and the two uploader queries each scanned the whole `streams` table (no index on `uploader`), up to sixty scans a pick. Candidate facts are now fetched in one batched query per kind (`AdaptiveShuffleFeatureQueriesDAO`), uploader aggregates in one grouped query and cached for ten minutes (`UploaderStatsCache`), and stream rows by a plain blocking lookup instead of a Room `Flowable`. No schema change; an index on `streams.uploader` would help further and can go in a later migration.

Database version 19 and the v1.1 rebuild (§0, §4):

- **Opening v1.1 databases.** An exported v1.1 database (version 18) crashed this branch ("a migration from 18 to 14 was required"). Its schema, read from the export, adds nine `playback_statistics` columns, two `adaptive_shuffle_pending_events` columns and an empty `stream_audio_features` table. Room checks the identity hash only when the database and app versions match, and the v1.1 hash cannot be reproduced without its source, so this branch is version 19: `MIGRATION_18_19` is a no-op, after which Room validates every table against the entities; `MIGRATION_14_19` adds the same columns and table to a version 14 database. Checked offline against the export: every entity table matches column for column, with the same foreign keys and indices, and a version 14 database after `MIGRATION_14_19` has the same tables. An export from this branch (19) does not import into a v1.1 build (18).
- **Recording** (`Player`, `PlaybackStatisticsDAO`), into the v1.1 columns and in their form, which the exported data shows: seeks split by direction (on top of `seek_count`); media volume steps up and down, from the system's volume broadcast, while a track is current; a pause, once the listen has played, ends as resumed or, if the track is left first, as a pause-skip; and at every judged exit (not the shuffle start grace, a removal or a failure) the leave point (position over duration, 1 for a natural end) goes into a running mean and Welford M2 (`LeavePointStats`) in `dropout_count`, `dropout_mean` and `dropout_m2`.
- **Features, schema v5** (37). Context: `screen_on`, and `route_bluetooth`, `route_wired`, `route_other` against the phone speaker as baseline (`ListeningConditionsReader`: wired or USB headset first, then Bluetooth, then HDMI, dock, line or USB device). Item: six per-listen rates, `ln(1 + count / listens)`, standardised, over the listens whose leave point was recorded (the count all of them were collected alongside), plus `mean_leave_point` and `leave_point_spread` (sample standard deviation). All are neutral below three recorded listens. The debug descriptions say what is counted, not what it means (§4's note on forward seeks).
- **Rating scale.** Ratings are stored 1 to 10 (half stars); `user_rating_norm` assumed 1 to 5, so a full rating encoded as 3.5 and `rating_vs_uploader` was clipped. Both now span −1 to 1. The schema hash changes, so the model rebuilds on first launch.
- **The v1.1 decision log.** Its blobs are versions 4, 5 and 6: the v2 body, then route (byte), screen on (byte), and in 5 and 6 the six counts (backward seeks, forward seeks, volume up, volume down, pauses resumed, pauses skipped), the leave count, mean and M2, then in 6 a 154-byte block (audio features, never filled). The count order was fixed by matching each stream's latest blob against its current counters: one order matches exactly for 335 of 466 streams and never exceeds them for 435, well ahead of any other. This branch reads all three and writes version 7 (the v2 body, that block, the v3 trailer), numbered past them. Its rewards are this branch's `fromRatio`, so for the 518 events with its `completion_ratio` column the restart delta follows from the reward and the full outcome is recovered and folded into the blob. On the exported log, 1,846 of 1,917 events decode (the 71 that do not are version 1, never supported), and a rebuild ingests 1,306 of 1,339 resolved events, against at most 107 before. Each decision's candidates are stored again in `candidate_stream_ids_blob`, in the v1.1 form.
- **Inferred, not known:** the order of the v1.1 build's route values. It wrote only 0 and 2 (2 for 1,547 of its 1,739 decisions); this branch reads them in the order §4 lists the routes, so 2 is wired. If most of that listening was over Bluetooth, swap `BLUETOOTH` and `WIRED` in `OutputRoute` before new decisions are logged.

Not done: the audio pipeline (§8) is untouched; the v1.1 build's `stream_audio_features` table is created but unused.

## 11. Brief for Claude Code

You are working in NewPipeMP, the mobile component of Acetate. Read §0 first: the device build's source is lost, and `claude/amazing-archimedes-vdf3b0` is the main line. The stages below are done there (§10); they are kept as the record of how the work was staged.

Then, in order, one stage per change:

1. Fix 0 (§6.1), then ask the user to run "Rebuild from history".
2. Fix 1 (§6.2).
3. Fix 2 and Fix 3 together, one schema-hash bump, one rebuild, with the rebuild report from §6.4.
4. Fix 4 behind a flag.
5. §7.1 metrics in the debug view from stage 1 onwards, so each stage has a before and after.

Do not narrate improvement from weights or event counts. Report the §7.1 metrics and carry the §7.2 caveat on every replay result.

The audio pipeline (§8) is a separate piece of work on the desktop side; do not start it until §9.5 is answered.

Constraints: match existing style; Kotlin for new files, Java only where surrounding code is Java and interop would be awkward; Room migrations for schema changes; existing RxJava patterns, no new coroutines; no new dependencies for matrix maths; telemetry stays on device; do not modify the plain or rating-weighted shuffle modes. Offline tooling in Julia; flag ecosystem gaps instead of switching language.

Apply the haiku scout protocol before producing the final file list. Report blockers in under 20 lines.
