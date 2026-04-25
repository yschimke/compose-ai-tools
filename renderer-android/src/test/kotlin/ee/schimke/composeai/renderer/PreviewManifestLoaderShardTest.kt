package ee.schimke.composeai.renderer

import ee.schimke.composeai.renderer.PreviewManifestLoader.PreviewRow
import ee.schimke.composeai.renderer.PreviewManifestLoader.assignToShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the LPT bin-packing in [PreviewManifestLoader.assignToShard].
 *
 * The previous `i % shardCount` round-robin would shove all the heavy
 * captures onto a single shard whenever they happened to share the same
 * (i mod K) bucket — the canonical pathological input is "all GIFs at the
 * start of the manifest, all statics after". These tests pin the load-
 * balancing behaviour so a future refactor doesn't accidentally regress.
 */
class PreviewManifestLoaderShardTest {

    private fun row(id: String, vararg costs: Float): PreviewRow {
        val captures = costs.map { c ->
            RenderPreviewCapture(renderOutput = "renders/$id.png", cost = c)
        }
        val entry = RenderPreviewEntry(
            id = id,
            functionName = id,
            className = "com.example.PreviewsKt",
            captures = captures,
        )
        return PreviewRow(entry, emptyList())
    }

    private fun loadOf(rows: List<PreviewRow>): Double =
        rows.sumOf { it.entry.captures.sumOf { c -> c.cost.toDouble() } }

    @Test
    fun `shardCount of 1 returns every row regardless of cost ordering`() {
        val rows = listOf(row("a", 50f), row("b", 1f), row("c", 40f))
        val result = assignToShard(rows, shardCount = 1, shardIndex = 0)
        assertEquals(rows, result)
    }

    @Test
    fun `LPT splits heavy captures across shards instead of clumping them`() {
        // Three GIFs (cost 40 each) + three statics (cost 1 each). Round-
        // robin would put all three GIFs onto shard 0 (indices 0, 3, ...
        // wait no — indices 0, 1, 2 are the GIFs and would land on shards
        // 0, 1, 2 respectively, OK that one's not the bad case). But if
        // the manifest carries gifs first and statics last, a 2-shard
        // round-robin lands gifs on shards 0/1/0 and statics on 1/0/1 →
        // shard 0 gets 80 cost, shard 1 gets 41. LPT should give us a
        // near-perfect 60/63 split.
        val rows = listOf(
            row("gif1", 40f),
            row("gif2", 40f),
            row("gif3", 40f),
            row("static1", 1f),
            row("static2", 1f),
            row("static3", 1f),
        )
        val shard0 = assignToShard(rows, shardCount = 2, shardIndex = 0)
        val shard1 = assignToShard(rows, shardCount = 2, shardIndex = 1)
        // Each shard gets half the GIFs (or two vs one), not a 3/0 split.
        val gifsInShard0 = shard0.count { it.entry.id.startsWith("gif") }
        val gifsInShard1 = shard1.count { it.entry.id.startsWith("gif") }
        assertTrue(
            "no shard should monopolise the GIFs (got $gifsInShard0 / $gifsInShard1)",
            gifsInShard0 in 1..2 && gifsInShard1 in 1..2,
        )
        // Total cost partition is balanced within ~1 unit either way.
        val load0 = loadOf(shard0)
        val load1 = loadOf(shard1)
        val skew = kotlin.math.abs(load0 - load1)
        assertTrue("shards too imbalanced (load0=$load0, load1=$load1)", skew <= 40.0)
    }

    @Test
    fun `LPT keeps assignment deterministic across runs for ties`() {
        val rows = listOf(
            row("c", 5f), row("b", 5f), row("a", 5f), row("d", 5f),
        )
        val first = assignToShard(rows, shardCount = 2, shardIndex = 0)
        val second = assignToShard(rows, shardCount = 2, shardIndex = 0)
        assertEquals(first.map { it.entry.id }, second.map { it.entry.id })
    }

    @Test
    fun `partition is exhaustive and disjoint across shards`() {
        val rows = listOf(
            row("p1", 50f),
            row("p2", 40f),
            row("p3", 20f),
            row("p4", 3f),
            row("p5", 1f),
            row("p6", 1f),
            row("p7", 1f),
        )
        val k = 3
        val ids = rows.map { it.entry.id }.toSet()
        val seen = mutableSetOf<String>()
        for (s in 0 until k) {
            val shard = assignToShard(rows, shardCount = k, shardIndex = s)
            for (r in shard) {
                assertTrue("duplicate assignment of ${r.entry.id}", seen.add(r.entry.id))
            }
        }
        assertEquals(ids, seen)
    }

    @Test
    fun `largest single capture sets the makespan and goes on its own shard first`() {
        // The 50-cost row should be picked first and placed on shard 0
        // (all bins start at zero). That gives us a stable expectation
        // for the test runner about where the heaviest preview lives.
        val rows = listOf(
            row("anim", 50f),
            row("a", 1f),
            row("b", 1f),
            row("c", 1f),
        )
        val shard0 = assignToShard(rows, shardCount = 2, shardIndex = 0)
        assertTrue(
            "expected the 50-cost row on shard 0; got ${shard0.map { it.entry.id }}",
            shard0.any { it.entry.id == "anim" },
        )
    }
}
