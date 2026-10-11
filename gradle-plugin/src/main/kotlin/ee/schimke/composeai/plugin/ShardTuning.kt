package ee.schimke.composeai.plugin

import kotlin.math.max

/**
 * Measured Robolectric render costs for auto shard sizing; update when the profile changes.
 * Benchmarked 2026-04-14 on `samples/android` (5 previews, sdk 34, NATIVE graphics): first preview
 * in a JVM 4.03s, later ones 0.11–0.22s.
 *
 * Cost-aware: estimates scale by each capture's manifest `cost` (static/TOP 1, END 3, LONG 20, GIF
 * 40, animated 50), since a few GIFs can outweigh many statics. Fork overhead is an estimate.
 */
internal object ShardTuning {
  /**
   * Per-fork sandbox + classloader setup before any Compose work; compose work is
   * [SECONDS_PER_COST_UNIT] × cost.
   */
  const val PER_FORK_SETUP_SECONDS = 3.85

  /** Wall time per cost unit (a warm static preview ≈ 0.15s). */
  const val SECONDS_PER_COST_UNIT = 0.15

  /**
   * Extra seconds per additional fork (worker startup, report aggregation); setup runs in parallel.
   */
  const val FORK_OVERHEAD_SECONDS = 1.0

  /** Hard upper bound on shards, regardless of preview count or CPU. */
  const val MAX_SHARDS = 8

  /**
   * Generous estimated peak memory per render fork, bounding [autoShards] to `availableMemoryMb /
   * PER_FORK_MEMORY_MB` so small runners don't OOM. On standard GitHub runners the CPU cap usually
   * binds first.
   */
  const val PER_FORK_MEMORY_MB = 2048L

  /**
   * Sharding must clear both thresholds versus one fork: forking has visible costs, so only pay for
   * unambiguous gains.
   */
  const val MIN_SAVING_SECONDS = 3.0
  const val MIN_SAVING_FRACTION = 0.30

  /**
   * Predicted wall time for K shards:
   * ```
   * makespanCost = max(totalCost / K, maxIndividualCost)
   * T(K) = PER_FORK_SETUP + makespanCost × SECONDS_PER_COST_UNIT + (K − 1) × FORK_OVERHEAD
   * ```
   * `makespanCost` is the LPT lower bound, floored by the largest item; setup is concurrent, so
   * only extra fork overhead is summed. Seconds.
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
   * The K ≥ 2 minimising [predictedSeconds], if it beats K = 1 by both [MIN_SAVING_SECONDS] and
   * [MIN_SAVING_FRACTION]; else 1. K is capped by `min(MAX_SHARDS, cores − 1, availableMemoryMb /
   * PER_FORK_MEMORY_MB, shardableRows)`, leaving one core for Gradle.
   *
   * Sized by preview rows, not captures: the renderer keeps a row's captures on one shard
   * (`RobolectricRenderTest.assignToShard`), so counting captures would add idle forks. See
   * [ShardTuning.perPreviewRowCosts].
   *
   * @param totalCost sum of every row's cost
   * @param maxIndividualCost largest single row cost (the makespan floor)
   * @param shardableRows number of indivisible preview rows
   * @param cores CPU cores visible to the build
   * @param availableMemoryMb host memory bound; defaults to [Long.MAX_VALUE] so tests aren't
   *   machine-dependent. Production passes [hostMemoryMb].
   */
  fun autoShards(
    totalCost: Double,
    maxIndividualCost: Double,
    shardableRows: Int,
    cores: Int = Runtime.getRuntime().availableProcessors(),
    availableMemoryMb: Long = Long.MAX_VALUE,
  ): Int {
    if (shardableRows < 2 || totalCost <= 0.0) return 1
    val memForks = (availableMemoryMb / PER_FORK_MEMORY_MB).coerceAtLeast(1L)
    val maxK =
      minOf(
          MAX_SHARDS.toLong(),
          (cores - 1).coerceAtLeast(1).toLong(),
          memForks,
          shardableRows.toLong(),
        )
        .toInt()
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

  /**
   * Host physical memory in MB via HotSpot's `OperatingSystemMXBean`, or [Long.MAX_VALUE] when
   * unavailable. Container limits may not be reflected; [PER_FORK_MEMORY_MB]'s margin absorbs that.
   */
  fun hostMemoryMb(): Long =
    try {
      val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
      val sunOs = os as? com.sun.management.OperatingSystemMXBean
      val bytes = sunOs?.totalMemorySize ?: -1L
      if (bytes > 0L) bytes / (1024L * 1024L) else Long.MAX_VALUE
    } catch (_: Throwable) {
      Long.MAX_VALUE
    }

  private val COST_FIELD = Regex("\"cost\"\\s*:\\s*([0-9.]+)")
  private val RENDER_OUTPUT_FIELD = Regex("\"renderOutput\"\\s*:")

  /**
   * One cost per preview row (the renderer's sharding unit; see [autoShards]): each entry's
   * captures + data products, priced 1.0 when `cost` is absent.
   *
   * A brace-depth scan instead of kotlinx.serialization to keep that off the plugin classpath.
   * `@PreviewParameter` expansion only adds rows later, so this is a safe lower bound. ACTIVITY /
   * APP_TOUR entries are excluded: they render in the unsharded app-tour lane.
   */
  fun perPreviewRowCosts(manifestText: String): List<Double> {
    val previewsKey = manifestText.indexOf("\"previews\"")
    if (previewsKey < 0) return emptyList()
    val arrStart = manifestText.indexOf('[', previewsKey)
    if (arrStart < 0) return emptyList()

    val rows = mutableListOf<Double>()
    var depth = 0
    var inString = false
    var escaped = false
    var entryStart = -1
    var i = arrStart + 1
    while (i < manifestText.length) {
      val c = manifestText[i]
      if (inString) {
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = false
        }
      } else {
        when (c) {
          '"' -> inString = true
          '{' -> {
            if (depth == 0) entryStart = i
            depth++
          }
          '}' -> {
            depth--
            if (depth == 0 && entryStart >= 0) {
              val entry = manifestText.substring(entryStart, i + 1)
              if (!isAppLevelEntry(entry)) rows += rowCost(entry)
              entryStart = -1
            }
          }
          ']' -> if (depth == 0) break // end of the previews array
        }
      }
      i++
    }
    return rows
  }

  /**
   * Whether an entry is in the app-tour lane. Matching the two kind names is safe: nested
   * data-product `kind`s never take those values.
   */
  private val APP_LEVEL_KIND = Regex("\"kind\"\\s*:\\s*\"(ACTIVITY|APP_TOUR)\"")

  private fun isAppLevelEntry(entryJson: String): Boolean =
    APP_LEVEL_KIND.containsMatchIn(entryJson)

  /**
   * Cost of one preview entry: sum of its `cost` fields, or 1.0 per capture on a pre-cost manifest.
   */
  private fun rowCost(entryJson: String): Double {
    val costs =
      COST_FIELD.findAll(entryJson).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
    if (costs.isNotEmpty()) return costs.sum()
    val captures = RENDER_OUTPUT_FIELD.findAll(entryJson).count()
    return captures.coerceAtLeast(1).toDouble()
  }
}
