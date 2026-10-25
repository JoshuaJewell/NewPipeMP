# Adaptive shuffle feature ablation

`FeatureAblationTest` compares the current hybrid model with a smaller baseline:

- Both use the same hybrid model, per-track preference offsets and recency slopes,
  priors, discount, candidate filtering and random seed.
- The baseline retains rating presence/value, completion rate, recency, same-uploader
  and playlist co-membership transition features, and bias. Other shared inputs are
  zeroed **after scaling**, in both prediction/training and simulated selection.
- The current model retains all features. No shipping model or schema is changed.

The replay sorts resolved events by decision time, predicts before updating, and
observes each raw vector in the scaler only after predicting. It reports reward
mean squared error (lower is better) and reward/completion Spearman correlations
(higher is better), excluding the first 50 events as warm-up. This is progressive
validation, not a random train/test split. Both configurations see the same events.

CI runs a deterministic synthetic smoke test, baseline-mask checks and a regression
showing that a shared additive time term cancels from a candidate score difference.
Synthetic outcomes do not establish which configuration is better for a real listener.

## Running on listening history

Use the resolved decision TSV format documented next to `ReplayHarness.load`:

```sql
SELECT chosen_at, stream_id, reward, hex(raw_inputs_blob), completion_ratio
FROM adaptive_shuffle_pending_events
WHERE resolved = 1
ORDER BY chosen_at;
```

Export with tab separators, without a header. Keep the database/export private.
If appropriate, use the app's existing Rebuild from history first to recover stored
outcomes. Then, on a development machine with the Android build environment:

```sh
ADAPTIVE_REPLAY_EVENTS=/absolute/path/events.tsv ./gradlew \
  :app:testDebugUnitTest \
  --tests 'org.schabi.newpipe.adaptive.FeatureAblationTest' --info
```

The optional real-history test is skipped when no export is supplied. Its output is
also available in the Gradle test report under the test's standard output. There is
no asserted winner. Compare across multiple time periods/listeners rather than
optimising for one history. The report prints excluded undecodable/missing-outcome
counts so sparse exports are not silently treated as complete histories.

## Limits

Only the played track has a logged outcome. Historical selection bias means this
comparison cannot prove that changing the serving policy would improve listening;
that needs a controlled on-device comparison. Repetition statistics are simulated,
not observed counterfactual outcomes. Playlist co-membership with a simulated
previous track is unknown and is set to zero by the existing harness. The comparison
holds the candidate policy fixed; it does not evaluate alternate filters.

Shared context can still explain baseline reward and protect track estimates from
session-wide effects. This ablation removes those baseline adjustments as well as
extra item inputs; it does not isolate time alone and introduces no interactions.
