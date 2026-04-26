package ee.schimke.composeai.plugin

import kotlin.math.max

/**
 * Measured costs for Robolectric-driven Compose preview rendering, used to pick a shard count in
 * auto mode. Update these when the cost profile changes (faster hardware, a slimmer sandbox, a new
 * Robolectric release).
 *
 * Benchmarked on 2026-04-14 against the `samples/android` module (5 previews, Robolectric `sdk=34`,
 * `graphicsMode=NATIVE`). Numbers come from the per-testcase times in
 * `build/test-results/renderPreviews/` (one XML per task run):
 * - First preview in a JVM: 4.03s (sandbox + classloader + first Compose setContent).
 * - Previews 2–5 in the same JVM: 0.11–0.22s each (warm sandbox, cached classes).
 *
 * **Cost-aware model.** Earlier versions modelled every preview as a flat [SECONDS_PER_COST_UNIT];
 * the per-capture `cost` field landed in the manifest (catalogue: static / TOP = 1, END = 3, LONG =
 * 20, GIF = 40, animated = 50) lets us scale that estimate by the actual work the renderer is going
 * to do for each preview. A module with three GIF captures dwarfs a module with thirty static ones,
 * even though the preview count says otherwise.
 *
 * Fork-startup overhead is an estimate — Gradle's worker-forking and JVM spin-up cost above and
 * beyond the shared sandbox warmup. Measure again if fork counts ever seem wildly off.
 */
internal object ShardTuning {
  /**
   * Per-fork sandbox + classloader setup, **before any Compose work**. The historical 4.0s "warmup"
   * included the first capture's render; we split it out so the same constant works for both fast
   * (1-cost static) and heavy (50-cost animation) first captures. Subsequent compose work is priced
   * at [SECONDS_PER_COST_UNIT] × the capture's cost.
   */
  const val PER_FORK_SETUP_SECONDS = 3.85

  /**
   * Wall-time per cost unit. A static `@Preview` is `cost = 1.0`, which historically rendered in
   * ~0.15s once the sandbox was warm. Every other catalogue entry — END = 3, LONG = 20, GIF = 40,
   * animated = 50 — is a multiple of that baseline.
   */
  const val SECONDS_PER_COST_UNIT = 0.15

  /**
   * Extra seconds per additional fork, on top of warmup — Gradle worker startup, test-report
   * aggregation, scheduler overhead. Warmups themselves run in parallel across forks, so only the
   * excess cost is counted.
   */
  const val FORK_OVERHEAD_SECONDS = 1.0

  /** Hard upper bound on shards, regardless of preview count or CPU. */
  const val MAX_SHARDS = 8

  /**
   * Auto mode only turns sharding on when both thresholds are met versus the single-fork baseline.
   * Rationale: forking is an externally visible cost (more JVMs, more memory, more log noise) — we
   * should only pay it when the gain is unambiguous. A 2s→1s speedup is not worth the complexity.
   */
  const val MIN_SAVING_SECONDS = 3.0
  const val MIN_SAVING_FRACTION = 0.30

  /**
   * Predicted wall time for K shards under the cost-aware model:
   *
   * makespanCost = max(totalCost / K, maxIndividualCost) T(K) = PER_FORK_SETUP + makespanCost ×
   * SECONDS_PER_COST_UNIT + (K − 1) × FORK_OVERHEAD
   *
   * `makespanCost` is the lower bound on what an LPT-balanced K-way split can achieve: the average
   * load per shard, floored by the largest individual capture (no shard can finish before its
   * biggest preview completes). The setup is paid concurrently across forks, so only the `(K − 1)`
   * extra fork-startup cost is summed. Returned in seconds.
   */
  fun predictedSeconds(totalCost: Double, maxIndividualCost: Double, shards: Int): Double {
    if (totalCost <= 0.0 || shards <= 0) return 0.0
    val avgPerShard = totalCost / shards
    val makespanCost = max(avgPerShard, maxIndividualCost)
    return PER_FORK_SETUP_SECONDS +
      makespanCost * SECONDS_PER_COST_UNIT +
      (shards - 1).coerceAtLeast(0) * FORK_OVERHEAD_SECONDS
  }

  /**
   * Picks the K ≥ 2 that minimises [predictedSeconds] over the allowed range, and returns it only
   * if the improvement over K = 1 clears BOTH the absolute ([MIN_SAVING_SECONDS]) and relative
   * ([MIN_SAVING_FRACTION]) thresholds. Otherwise returns 1.
   *
   * Upper bound is `min(MAX_SHARDS, cores / 2, captureCount)` — leave CPU headroom for Gradle
   * workers; never shard below one capture per fork.
   *
   * @param totalCost sum of `Capture.cost` across every capture in the manifest
   * @param maxIndividualCost largest single `Capture.cost` value (sets the makespan floor)
   * @param captureCount total number of captures (caps shard count so each fork gets ≥ 1)
   */
  fun autoShards(
    totalCost: Double,
    maxIndividualCost: Double,
    captureCount: Int,
    cores: Int = Runtime.getRuntime().availableProcessors(),
  ): Int {
    if (captureCount < 2 || totalCost <= 0.0) return 1
    val maxK = minOf(MAX_SHARDS, (cores / 2).coerceAtLeast(1), captureCount)
    if (maxK < 2) return 1

    val baseline = predictedSeconds(totalCost, maxIndividualCost, 1)
    var bestK = 1
    var bestT = baseline
    for (k in 2..maxK) {
      val t = predictedSeconds(totalCost, maxIndividualCost, k)
      if (t < bestT) {
        bestK = k
        bestT = t
      }
    }
    val saved = baseline - bestT
    val fraction = if (baseline > 0) saved / baseline else 0.0
    return if (bestK >= 2 && saved >= MIN_SAVING_SECONDS && fraction >= MIN_SAVING_FRACTION) bestK
    else 1
  }
}
