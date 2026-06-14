package ee.schimke.composeai.daemon.history

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-render provenance cache ([GitProvenance.snapshot]) — the optimization that keeps
 * history-on-by-default cheap. A render burst must collapse to a single git fetch; once the TTL
 * elapses the next call re-resolves. Uses an injected git runner + clock so it asserts the exact
 * number of `git` invocations without spawning subprocesses or needing a real repo.
 */
class GitProvenanceCacheTest {

  /** Fake `git` for a clean repo at /repo on branch main; counts invocations. */
  private fun countingRunner(counter: AtomicInteger): (String, List<String>) -> String? =
    { _, args ->
      counter.incrementAndGet()
      when (args.firstOrNull()) {
        "rev-parse" -> if (args.contains("--show-toplevel")) "/repo" else "deadbeefcafebabe"
        "remote" -> "https://example.com/repo.git"
        "symbolic-ref" -> "main"
        "status" -> "" // clean working tree
        else -> null
      }
    }

  @Test
  fun caches_snapshot_within_ttl_and_refreshes_after_expiry() {
    val calls = AtomicInteger(0)
    var nowNanos = 0L
    val provenance =
      GitProvenance(
        workspaceRoot = null,
        env = emptyMap(),
        cacheTtlMs = 1000L,
        nowNanos = { nowNanos },
        gitRunner = countingRunner(calls),
      )
    // Construction resolved the worktree root + remote (2 git calls); none since.
    val afterCtor = calls.get()
    assertEquals(2, afterCtor)

    // First snapshot resolves branch + commit + dirty (3 git calls).
    val first = provenance.snapshot()
    val afterFirst = calls.get()
    assertEquals(afterCtor + 3, afterFirst)
    assertEquals("main", first.second?.branch)
    assertEquals(false, first.second?.dirty)

    // A render burst of 49 more calls within the TTL must not spawn any more git.
    repeat(49) { provenance.snapshot() }
    assertEquals("burst served from cache", afterFirst, calls.get())

    // Once the TTL elapses, the next snapshot re-resolves (3 more git calls).
    nowNanos += 1_000_000_000L + 1L
    provenance.snapshot()
    assertEquals(afterFirst + 3, calls.get())
  }

  @Test
  fun ttl_zero_disables_caching_every_call_resolves() {
    val calls = AtomicInteger(0)
    val provenance =
      GitProvenance(
        workspaceRoot = null,
        env = emptyMap(),
        cacheTtlMs = 0L,
        nowNanos = { 0L }, // clock never advances; caching would otherwise serve a hit forever
        gitRunner = countingRunner(calls),
      )
    val afterCtor = calls.get()
    provenance.snapshot()
    val afterFirst = calls.get()
    provenance.snapshot()
    val afterSecond = calls.get()

    assertTrue(afterFirst > afterCtor)
    // Each snapshot does the same number of git calls — no caching despite the frozen clock.
    assertEquals(afterFirst - afterCtor, afterSecond - afterFirst)
  }
}
