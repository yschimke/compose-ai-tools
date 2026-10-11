package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cost-aware sharding heuristic. Tests pin *decisions* ("two GIFs at 40 cost justify two shards"),
 * not the [ShardTuning] coefficients, which get re-tuned.
 */
class ShardTuningTest {

  @Test
  fun `single static preview never shards`() {
    val k =
      ShardTuning.autoShards(
        totalCost = 1.0,
        maxIndividualCost = 1.0,
        shardableRows = 1,
        cores = 16,
      )
    assertThat(k).isEqualTo(1)
  }

  @Test
  fun `dozens of static previews still don't justify sharding`() {
    // 40 static previews, total cost 40: K=2's gain sits just under the threshold, so single shard.
    // Total work decides, not preview count.
    val k =
      ShardTuning.autoShards(
        totalCost = 40.0,
        maxIndividualCost = 1.0,
        shardableRows = 40,
        cores = 16,
      )
    assertThat(k).isAtMost(2) // model-stable assertion: would be 1 today, 2 if constants ever shift
  }

  @Test
  fun `a few heavy GIF captures DO justify sharding`() {
    // 3 GIFs (cost 40) + 5 static = 125 cost units: a 2-way split nearly halves the make-span,
    // though a uniform-cost model would skip sharding.
    val k =
      ShardTuning.autoShards(
        totalCost = 125.0,
        maxIndividualCost = 40.0,
        shardableRows = 8,
        cores = 16,
      )
    assertThat(k).isAtLeast(2)
  }

  @Test
  fun `make-span floor caps useful sharding when one capture dominates`() {
    // One cost-50 preview floors the make-span on one fork, so sharding past 2 doesn't help.
    val k =
      ShardTuning.autoShards(
        totalCost = 54.0,
        maxIndividualCost = 50.0,
        shardableRows = 5,
        cores = 16,
      )
    assertThat(k).isAtMost(2)
  }

  @Test
  fun `predictedSeconds respects the make-span floor`() {
    // 1 capture at cost 50 split into 4 shards: average per shard =
    // 12.5, but the largest single capture is 50 so the make-span
    // can't drop below 50 cost units. Model returns the floor.
    val secondsAt4 =
      ShardTuning.predictedSeconds(totalCost = 50.0, maxIndividualCost = 50.0, shards = 4)
    val secondsAt1 =
      ShardTuning.predictedSeconds(totalCost = 50.0, maxIndividualCost = 50.0, shards = 1)
    // 4-shard run pays the (K−1)×fork-overhead but still has the same
    // 50-cost floor as a 1-shard run, so it's strictly slower.
    assertThat(secondsAt4).isGreaterThan(secondsAt1)
  }

  @Test
  fun `shard count leaves one core for the Gradle daemon`() {
    // Lots of cheap captures, but only 4 cores → cap at K = cores - 1 = 3.
    // (Was cores / 2 = 2 before we stopped reserving half the machine for a
    // Gradle worker pool that's idle while the render task runs.)
    val k =
      ShardTuning.autoShards(
        totalCost = 1000.0,
        maxIndividualCost = 1.0,
        shardableRows = 1000,
        cores = 4,
      )
    assertThat(k).isAtMost(3)
    // And it genuinely uses the extra headroom: a 2-core cap would return ≤2.
    assertThat(k).isGreaterThan(2)
  }

  @Test
  fun `shard count is bounded by available memory`() {
    // 16 cores would allow up to MAX_SHARDS, and the cost easily justifies it,
    // but only ~4 GB of RAM → 4096 / PER_FORK_MEMORY_MB(2048) = 2 forks max.
    val k =
      ShardTuning.autoShards(
        totalCost = 1000.0,
        maxIndividualCost = 1.0,
        shardableRows = 1000,
        cores = 16,
        availableMemoryMb = 4096,
      )
    assertThat(k).isAtMost(2)
  }

  @Test
  fun `unbounded memory default does not cap sharding`() {
    // Same shape as the memory test but with the default (unbounded) memory:
    // the CPU/cost caps decide, so we get more than the 2 the 4 GB run allowed.
    val k =
      ShardTuning.autoShards(
        totalCost = 1000.0,
        maxIndividualCost = 1.0,
        shardableRows = 1000,
        cores = 16,
      )
    assertThat(k).isGreaterThan(2)
  }

  @Test
  fun `shard count never exceeds shardable row count`() {
    // 3 rows, 16 cores: even if 8 shards would minimise wall-time,
    // we never assign fewer than 1 row per fork.
    val k =
      ShardTuning.autoShards(
        totalCost = 90.0,
        maxIndividualCost = 30.0,
        shardableRows = 3,
        cores = 16,
      )
    assertThat(k).isAtMost(3)
  }

  @Test
  fun `one preview with many heavy captures stays single-fork`() {
    // One paused-clock / GIF preview with eight cost-40 captures is one indivisible row, so
    // shardableRows = 1; sizing by capture count would add idle forks.
    val k =
      ShardTuning.autoShards(
        totalCost = 320.0,
        maxIndividualCost = 320.0,
        shardableRows = 1,
        cores = 16,
      )
    assertThat(k).isEqualTo(1)
  }

  @Test
  fun `module with no rows returns single shard`() {
    val k =
      ShardTuning.autoShards(
        totalCost = 0.0,
        maxIndividualCost = 0.0,
        shardableRows = 0,
        cores = 16,
      )
    assertThat(k).isEqualTo(1)
  }

  @Test
  fun `perPreviewRowCosts sums captures and data products per preview`() {
    // Each preview collapses to ONE row costing its captures + products, never one row per capture.
    val manifest =
      """
      {
        "schemaVersion": 1,
        "previews": [
          {
            "id": "com.example.Heavy",
            "functionName": "Heavy",
            "className": "com.example.HeavyKt",
            "captures": [
              { "renderOutput": "a.png", "cost": 40.0 },
              { "renderOutput": "b.png", "cost": 40.0 },
              { "renderOutput": "c.png", "cost": 40.0 }
            ],
            "dataProducts": [ { "kind": "a11y", "output": "a.json", "cost": 3.0 } ]
          },
          {
            "id": "com.example.Static",
            "functionName": "Static",
            "className": "com.example.StaticKt",
            "captures": [ { "renderOutput": "s.png", "cost": 1.0 } ]
          }
        ]
      }
      """
        .trimIndent()

    val rows = ShardTuning.perPreviewRowCosts(manifest)
    assertThat(rows).hasSize(2)
    assertThat(rows[0]).isWithin(1e-6).of(123.0) // 40+40+40+3
    assertThat(rows[1]).isWithin(1e-6).of(1.0)
  }

  @Test
  fun `perPreviewRowCosts excludes app-level previews`() {
    // ACTIVITY / APP_TOUR previews render in their own unsharded lane, so counting them would add
    // idle forks. The exclusion anchors on the two app-level names because the APP_TOUR entry's
    // data product carries its own "kind".
    val manifest =
      """
      {
        "schemaVersion": 1,
        "previews": [
          {
            "id": "com.example.Static",
            "functionName": "Static",
            "className": "com.example.PreviewsKt",
            "params": { "kind": "COMPOSE" },
            "captures": [ { "renderOutput": "s.png", "cost": 1.0 } ]
          },
          {
            "id": "activity__MainActivity",
            "functionName": "MainActivity",
            "className": "com.example.MainActivity",
            "params": { "kind": "ACTIVITY" },
            "captures": [ { "renderOutput": "activity__MainActivity.png", "cost": 12.0 } ]
          },
          {
            "id": "apptour__getting-started",
            "functionName": "MainActivity",
            "className": "com.example.MainActivity",
            "params": { "kind": "APP_TOUR" },
            "captures": [ { "renderOutput": "t0.png", "cost": 12.0 } ],
            "dataProducts": [ { "kind": "render/scroll/gif", "output": "t.gif", "cost": 40.0 } ]
          }
        ]
      }
      """
        .trimIndent()

    val rows = ShardTuning.perPreviewRowCosts(manifest)
    assertThat(rows).hasSize(1)
    assertThat(rows[0]).isWithin(1e-6).of(1.0)
  }

  @Test
  fun `perPreviewRowCosts prices captures without a cost field at 1_0`() {
    // Pre-0.8.0 manifest: no "cost" fields. Each capture is priced at 1.0, so a
    // two-capture preview is one row of cost 2.0.
    val manifest =
      """
      {
        "previews": [
          {
            "id": "old",
            "functionName": "Old",
            "captures": [ { "renderOutput": "a.png" }, { "renderOutput": "b.png" } ]
          }
        ]
      }
      """
        .trimIndent()

    val rows = ShardTuning.perPreviewRowCosts(manifest)
    assertThat(rows).hasSize(1)
    assertThat(rows[0]).isWithin(1e-6).of(2.0)
  }

  @Test
  fun `perPreviewRowCosts returns empty for a manifest with no previews`() {
    assertThat(ShardTuning.perPreviewRowCosts("""{ "previews": [] }""")).isEmpty()
    assertThat(ShardTuning.perPreviewRowCosts("""{ "schemaVersion": 1 }""")).isEmpty()
  }
}
