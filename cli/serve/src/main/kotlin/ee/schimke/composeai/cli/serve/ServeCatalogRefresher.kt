package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class CatalogRefreshResult {
  UPDATED,
  CURRENT,
  UNAVAILABLE,
  FAILED,
  NOT_FOUND,
}

/**
 * Keeps a running `serve` fresh against routinely-changing catalog branches.
 *
 * `serve --catalogs <system>` fetches each system's `design-artifacts/<system>` branch — its
 * `catalog.json`, baked renders, `web/wasm/` app, and `liveBundle` — **once at startup**. Nothing
 * re-checks it, so a regenerated branch (the `design-artifacts.yml` force-push) never reaches a
 * live server until the container restarts. But serving content that changes routinely is exactly
 * what this multi-catalog server is for — `compose-m3` is no different from the external apps it
 * also serves (`cadence`, `meshcore-mobile`, …), all of which go stale the same way.
 *
 * This closes that gap without a restart, a per-project server release, or baking content into the
 * image: a daemon thread periodically resolves each catalog branch's head commit and, when it has
 * moved, re-runs the same [reload] path (`ServeCatalogStore.load`) that the initial fetch used —
 * which re-fetches into the same on-disk dir and re-registers the host in place (the registry
 * closes the replaced host's daemon; the `/wasm/<system>/` route serves the rewritten dir on the
 * next request). A branch whose head can't be resolved (offline, `git` absent) is simply skipped —
 * the server keeps serving what it already has, exactly as today.
 *
 * @param entries the catalog branches to watch: `system` id + owning `repo` + full `branch` ref.
 *   Evaluated per pass rather than captured, because the catalog set is runtime config: a catalog
 *   published through the admin API starts being polled on the next tick, and a retired one stops.
 * @param reload re-fetch + re-register one system — the `store.load(system, sourceRepo = repo)`
 *   seam — handing back whatever the store said, or null when there was nothing to load. What that
 *   result *means* for the recorded head is decided here, in [checkOne], rather than by the caller:
 *   a load that registered but could not read everything is serving and not settled, and only one
 *   of those two facts belongs to the caller.
 * @param headResolver resolve a branch's head commit sha (or null when it can't be determined).
 *   Defaults to [gitLsRemoteHead]; injected so tests drive change detection without a network.
 * @param intervalMillis poll cadence; the first tick fires one interval after [start].
 */
public class ServeCatalogRefresher(
  private val entries: () -> List<Entry>,
  private val reload: (system: String, repo: String) -> ServeCatalogStore.Result?,
  private val intervalMillis: Long,
  private val headResolver: (repo: String, branch: String) -> String? = ::gitLsRemoteHead,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /** One watched catalog branch. */
  data class Entry(val system: String, val repo: String, val branch: String)

  private val lastHead = ConcurrentHashMap<String, String>()

  /**
   * How many times each system has been declared unsettled — monotonic, never consumed.
   *
   * A *count* rather than a pending flag, because the question a head-recorder has to answer is not
   * "is there an invalidation outstanding?" but "did one arrive since I started reading?". Those
   * differ, and the difference is the whole bug: the post-publish lanes run on their own executor
   * and [seedInitialHeads] only runs once every startup load has finished, so an invalidation for
   * one catalog lands while another is still loading. Removing a head that is not there yet is a
   * no-op; a flag consumed *before* the branch-head resolution that follows is consumed before the
   * window it was meant to cover. Both lose the invalidation, and the seed then records exactly the
   * revision the lane was reporting as incomplete.
   *
   * So a recorder takes a [invalidationMark] before it starts and hands it back to [recordHead],
   * which writes only if the count still matches. Reads and writes of the count happen inside
   * [lastHead]'s per-key `compute` lock, so the check and the write are one step rather than two
   * with a window between them — see [recordHead].
   */
  private val invalidations = ConcurrentHashMap<String, Long>()
  private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serve-catalog-refresh").apply { isDaemon = true }
  }

  /**
   * Record the current head for catalogs that loaded **completely** at boot, so their first tick
   * only reloads after a branch move. [systems] deliberately excludes startup failures: leaving
   * their head absent makes the first tick retry the unchanged branch, then every later tick until
   * it succeeds. Previously every configured head was seeded, permanently suppressing retries for
   * an initial fetch/parse failure until someone happened to publish a new commit.
   *
   * It excludes an *incomplete* load for the same reason. A catalog that came up serving but could
   * not fetch, say, its issue index because the branch host throttled us is not a settled revision
   * — and seeding it would make that one throttled request permanent for the life of the process.
   */
  fun seedInitialHeads(systems: Set<String> = entries().mapTo(linkedSetOf()) { it.system }) {
    for (e in entries()) {
      if (e.system !in systems) continue
      val head = headResolver(e.repo, e.branch) ?: continue
      // The mark is `0`, not the current count: what this seed vouches for is the **boot load**,
      // which finished before this method was even called, so *any* invalidation reported since the
      // process started necessarily postdates it. That covers both windows at once — a lane that
      // reported while the startup loader was still working through the other catalogs, and one
      // that reported while this loop was blocked in `git ls-remote`, which is the widest window
      // in this class.
      recordHead(e.system, head, mark = 0L)
    }
  }

  /**
   * Forget the recorded head for [systems], so the next [tick] re-fetches them even though their
   * branch hasn't moved.
   *
   * The SHA short-circuit in [checkOne] is what makes polling cheap, but it also means a catalog
   * can only be re-verified when its branch changes. Revoking a producer's trust has to re-verify
   * *now* — otherwise the revoked catalog keeps whatever verdict it loaded with, potentially
   * indefinitely.
   */
  fun forgetHeads(systems: Collection<String>) {
    for (system in systems) {
      // Inside `compute` so the bump and the removal are one step, and so they cannot interleave
      // with a [recordHead] for the same system: that is what makes "did one arrive since I
      // started?" answerable without a window. Returning null removes the entry.
      lastHead.compute(system) { _, _ ->
        invalidations.merge(system, 1L, Long::plus)
        null
      }
    }
  }

  /**
   * The invalidation count to hand back to [recordHead] once the work being vouched for is done.
   */
  private fun invalidationMark(system: String): Long = invalidations[system] ?: 0L

  /**
   * Record [head] for [system] — settling that revision — unless [forgetHeads] ran for it since
   * [mark] was taken, in which case the invalidation wins and the head stays absent.
   *
   * The check and the write are one `compute` on [lastHead], against which [forgetHeads] also
   * computes. Two statements would leave a window between them, which is the same shape of bug one
   * level in: an invalidation landing there would be read as "none since I started" and then
   * overwritten by the very head it was rejecting.
   */
  private fun recordHead(system: String, head: String, mark: Long) {
    lastHead.compute(system) { _, current ->
      if (invalidationMark(system) == mark) head else current
    }
  }

  /** Start the daemon poller. Idempotent-safe to call once after [seedInitialHeads]. */
  fun start() {
    exec.scheduleWithFixedDelay(
      {
        runCatching { tick() }
          .onFailure { onLog("serve: catalog refresh tick failed: ${it.message}") }
      },
      intervalMillis,
      intervalMillis,
      TimeUnit.MILLISECONDS,
    )
  }

  /**
   * One poll pass over every watched branch. Package-visible so a test can drive it
   * deterministically.
   */
  @Synchronized
  fun tick() {
    for (e in entries()) checkOne(e)
  }

  /**
   * Check one catalog immediately, using the same branch-head + reload path as the poller.
   *
   * [force] re-fetches even when the head has not moved, by dropping the recorded head exactly as
   * [forgetHeads] does for a trust revocation. The short-circuit in [checkOne] is what makes
   * polling cheap and is right almost always, but it also leaves no way to say "read it again
   * anyway" — which is what an operator wants after discarding the blob cache, or when they would
   * rather see the published bytes re-read than reason about whether they need to be.
   */
  @Synchronized
  fun refresh(system: String, force: Boolean = false): CatalogRefreshResult {
    val entry =
      entries().firstOrNull { it.system == system } ?: return CatalogRefreshResult.NOT_FOUND
    // [forgetHeads] rather than a bare removal, for the reason its own doc gives: the mark is what
    // stops a concurrent seed re-recording the head this operator just asked to be re-read.
    if (force) forgetHeads(listOf(system))
    return checkOne(entry)
  }

  private fun checkOne(e: Entry): CatalogRefreshResult {
    // Can't resolve the head (offline / git missing / private) → leave what we serve untouched.
    val head = headResolver(e.repo, e.branch) ?: return CatalogRefreshResult.UNAVAILABLE
    if (head == lastHead[e.system]) return CatalogRefreshResult.CURRENT
    val prev = lastHead[e.system]
    onLog(
      "serve: catalog ${e.system} (${e.branch}) moved ${prev?.take(7) ?: "?"}→${head.take(7)} — re-fetching"
    )
    // Taken before the reload: an invalidation arriving *during* it belongs to this attempt, and
    // making the head write conditional on the mark is what closes the window between the load
    // returning and the head being recorded.
    val mark = invalidationMark(e.system)
    val loaded =
      runCatching { reload(e.system, e.repo) }.getOrNull() as? ServeCatalogStore.Result.Ok
    if (loaded == null) {
      onLog("serve: catalog ${e.system} refresh failed — keeping the current copy, will retry")
      return CatalogRefreshResult.FAILED
    }
    // **Serving and settled are two answers, and only the first is the caller's.** The catalog is
    // registered either way — it genuinely IS this revision, which is why both arms report
    // `UPDATED`. What an incomplete read withholds is the recorded head, so the next tick re-reads
    // what the branch would not give us this time instead of short-circuiting on an unmoved sha.
    // A post-publish lane that failed while this reload ran counts the same as the load itself
    // coming back incomplete — the revision is serving and it is not settled.
    if (loaded.incomplete) {
      // Recorded as an invalidation, not merely left unrecorded. `refresh()` is reachable before
      // [seedInitialHeads] runs — the route is live while the startup loader is still working
      // through the other catalogs — so a catalog that booted complete, was refreshed
      // incompletely, and is therefore still in the loader's `loaded` set would otherwise be
      // settled by the seed at exactly the sha this read could not finish. Withholding a head only
      // outlives the operation that withheld it if something says so.
      forgetHeads(listOf(e.system))
      onLog(
        "serve: catalog ${e.system} refreshed to ${head.take(7)}, but some assets could not be " +
          "fetched — will re-read next tick"
      )
      return CatalogRefreshResult.UPDATED
    }
    // A post-publish lane that failed while this reload ran counts the same, and says so by having
    // bumped the mark — [recordHead] then leaves the head absent and the next tick re-reads.
    recordHead(e.system, head, mark)
    onLog(
      if (lastHead[e.system] == head) "serve: catalog ${e.system} refreshed to ${head.take(7)}"
      else
        "serve: catalog ${e.system} refreshed to ${head.take(7)}, but some assets could not be " +
          "fetched — will re-read next tick"
    )
    return CatalogRefreshResult.UPDATED
  }

  override fun close() {
    exec.shutdownNow()
  }
}

/**
 * Resolve a branch's head commit via `git ls-remote` — unauthenticated and unrated (unlike the
 * GitHub commits API's 60/hr), so it scales to any number of watched catalogs. Returns null on any
 * failure (git absent, network error, unknown branch), which the refresher treats as "can't check,
 * skip". Best-effort with a bounded wait so a hung remote can't wedge the poll thread.
 */
public fun gitLsRemoteHead(repo: String, branch: String): String? = runCatching {
  val proc =
    ProcessBuilder("git", "ls-remote", "https://github.com/$repo.git", "refs/heads/$branch")
      .redirectErrorStream(true)
      .start()
  proc.outputStream.close()
  // Drain stdout on a daemon thread: if git hangs *without* closing stdout (a DNS/TLS/network
  // stall), a direct `readText()` would block on EOF forever and never reach the `waitFor`
  // timeout below — wedging the single catalog-refresh thread so no branch ever updates again.
  // The reader thread lets `waitFor(20s)` bound the wait; `join` after the process exits reads
  // the (now-complete) output safely.
  val captured = StringBuilder()
  val reader = Thread {
    runCatching { proc.inputStream.bufferedReader().use { r -> captured.append(r.readText()) } }
  }
    .apply {
      isDaemon = true
      start()
    }
  if (!proc.waitFor(20, TimeUnit.SECONDS)) {
    proc.destroyForcibly()
    return null
  }
  reader.join(2_000)
  Regex("\\b([0-9a-f]{40})\\b").find(captured.toString())?.groupValues?.get(1)
}
  .getOrNull()
