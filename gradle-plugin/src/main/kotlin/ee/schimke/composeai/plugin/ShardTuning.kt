package ee.schimke.composeai.plugin

import kotlin.math.ceil

/**
 * Measured costs for Robolectric-driven Compose preview rendering, used to pick a
 * shard count in auto mode. Update these when the cost profile changes (faster
 * hardware, a slimmer sandbox, a new Robolectric release).
 *
 * Benchmarked on 2026-04-14 against the `sample-android` module (5 previews,
 * Robolectric `sdk=34`, `graphicsMode=NATIVE`). Numbers come from the per-testcase
 * times in `build/test-results/renderPreviews/` (one XML per task run):
 *  - First preview in a JVM: 4.03s (sandbox + classloader + first Compose setContent).
 *  - Previews 2–5 in the same JVM: 0.11–0.22s each (warm sandbox, cached classes).
 *
 * Fork-startup overhead is an estimate — Gradle's worker-forking and JVM spin-up
 * cost above and beyond the shared sandbox warmup. Measure again if fork counts
 * ever seem wildly off.
 */
internal object ShardTuning {
    /** Seconds to render the first preview in a fresh JVM (sandbox + class init). */
    const val COLD_FORK_WARMUP_SECONDS = 4.0

    /** Steady-state cost per preview once the sandbox is warm. */
    const val PER_PREVIEW_SECONDS = 0.15

    /**
     * Extra seconds per additional fork, on top of warmup — Gradle worker
     * startup, test-report aggregation, scheduler overhead. Warmups themselves
     * run in parallel across forks, so only the excess cost is counted.
     */
    const val FORK_OVERHEAD_SECONDS = 1.0

    /** Hard upper bound on shards, regardless of preview count or CPU. */
    const val MAX_SHARDS = 8

    /**
     * Auto mode only turns sharding on when both thresholds are met versus the
     * single-fork baseline. Rationale: forking is an externally visible cost
     * (more JVMs, more memory, more log noise) — we should only pay it when
     * the gain is unambiguous. A 2s→1s speedup is not worth the complexity.
     */
    const val MIN_SAVING_SECONDS = 3.0
    const val MIN_SAVING_FRACTION = 0.30

    /**
     * Predicted wall time for K shards under the model
     *
     *   T(K) = warmup + (ceil(N / K) − 1) * perPreview + (K − 1) * forkOverhead
     *
     * The warmup is paid concurrently across forks, so only the `(K − 1)` extra
     * fork-startup cost is summed. Returned in seconds.
     */
    fun predictedSeconds(previewCount: Int, shards: Int): Double {
        if (previewCount <= 0 || shards <= 0) return 0.0
        val perShard = ceil(previewCount.toDouble() / shards).toInt()
        return COLD_FORK_WARMUP_SECONDS +
            (perShard - 1).coerceAtLeast(0) * PER_PREVIEW_SECONDS +
            (shards - 1).coerceAtLeast(0) * FORK_OVERHEAD_SECONDS
    }

    /**
     * Picks the K ≥ 2 that minimises [predictedSeconds] over the allowed range,
     * and returns it only if the improvement over K = 1 clears BOTH the
     * absolute ([MIN_SAVING_SECONDS]) and relative ([MIN_SAVING_FRACTION])
     * thresholds. Otherwise returns 1.
     *
     * Upper bound is `min(MAX_SHARDS, cores / 2, previewCount)` — leave CPU
     * headroom for Gradle workers; never shard below one preview per fork.
     */
    fun autoShards(previewCount: Int, cores: Int = Runtime.getRuntime().availableProcessors()): Int {
        if (previewCount < 2) return 1
        val maxK = minOf(MAX_SHARDS, (cores / 2).coerceAtLeast(1), previewCount)
        if (maxK < 2) return 1

        val baseline = predictedSeconds(previewCount, 1)
        var bestK = 1
        var bestT = baseline
        for (k in 2..maxK) {
            val t = predictedSeconds(previewCount, k)
            if (t < bestT) { bestK = k; bestT = t }
        }
        val saved = baseline - bestT
        val fraction = if (baseline > 0) saved / baseline else 0.0
        return if (bestK >= 2 && saved >= MIN_SAVING_SECONDS && fraction >= MIN_SAVING_FRACTION) bestK else 1
    }
}
