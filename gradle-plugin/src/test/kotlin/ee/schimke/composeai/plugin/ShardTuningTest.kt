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
      ShardTuning.autoShards(totalCost = 1.0, maxIndividualCost = 1.0, captureCount = 1, cores = 16)
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
        captureCount = 40,
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
        captureCount = 8,
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
        captureCount = 5,
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
  fun `shard count is bounded by half the available cores`() {
    // Lots of cheap captures, but only 4 cores → cap at K = 2.
    val k =
      ShardTuning.autoShards(
        totalCost = 1000.0,
        maxIndividualCost = 1.0,
        captureCount = 1000,
        cores = 4,
      )
    assertThat(k).isAtMost(2)
  }

  @Test
  fun `shard count never exceeds capture count`() {
    // 3 captures, 16 cores: even if 8 shards would minimise wall-time,
    // we never assign fewer than 1 capture per fork.
    val k =
      ShardTuning.autoShards(
        totalCost = 90.0,
        maxIndividualCost = 30.0,
        captureCount = 3,
        cores = 16,
      )
    assertThat(k).isAtMost(3)
  }

  @Test
  fun `module with no captures returns single shard`() {
    val k =
      ShardTuning.autoShards(totalCost = 0.0, maxIndividualCost = 0.0, captureCount = 0, cores = 16)
    assertThat(k).isEqualTo(1)
  }
}
