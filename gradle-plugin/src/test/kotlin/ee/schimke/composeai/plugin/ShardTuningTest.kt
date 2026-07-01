package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavioural tests for the cost-aware sharding heuristic.
 *
 * Each test pins a *decision*, not a model coefficient — the constants in [ShardTuning]
 * (PER_FORK_SETUP_SECONDS, SECONDS_PER_COST_UNIT, FORK_OVERHEAD_SECONDS) get re-tuned over time as
 * hardware and the Robolectric stack evolve. What we want to lock in is "two GIFs at 40 cost each
 * justify two shards" rather than "K=2 takes exactly 7.85s".
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
    // 40 static previews × 1.0 cost = 40 total. Single shard:
    // 3.85 + 40×0.15 ≈ 9.85s. K=2 saves only ~3s, and the relative
    // gain (~30%) is right at the threshold; with FORK_OVERHEAD it
    // dips just under, so we stay single-shard. The point is the
    // decision is governed by total work, not preview count.
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
    // 8 captures, three of them GIFs (cost 40). totalCost = 5*1 + 3*40 = 125.
    // Under the OLD uniform-cost model this looked like 8 previews at
    // 0.15s each — well below the saving threshold, so sharding was
    // (incorrectly) skipped. Under the new model, 125×0.15 ≈ 18.75s of
    // compose work, and a 2-way split nearly halves the make-span.
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
    // One animated preview at cost 50 + 4 cheap ones at cost 1.
    // totalCost=54, maxIndividualCost=50. The animated capture sets a
    // floor of 50×0.15=7.5s — adding more shards past 2 doesn't help
    // because the largest preview lives entirely on one fork. The
    // model is supposed to recognise this and not over-shard.
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
    // The regression this guards: a single paused-clock / GIF preview with
    // eight cost-40 captures. The renderer keeps all eight captures on one
    // shard (one indivisible row), so shardableRows = 1 even though there are
    // eight captures and 320 units of work. Sizing by capture count would have
    // picked multiple forks that then sit idle — slower than a single fork.
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
    // Two previews: the first is a heavy paused-clock (3 captures + 1 data
    // product), the second a plain static. Each preview must collapse to ONE
    // row whose cost is the sum of its captures + products — never one row per
    // capture.
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
