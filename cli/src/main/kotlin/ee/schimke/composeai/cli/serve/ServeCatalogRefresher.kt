package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
 * @param reload re-fetch + re-register one system; the `store.load(system, sourceRepo = repo)`
 *   seam. Its boolean result is whether the reload succeeded (a failure keeps the old head so the
 *   next tick retries).
 * @param headResolver resolve a branch's head commit sha (or null when it can't be determined).
 *   Defaults to [gitLsRemoteHead]; injected so tests drive change detection without a network.
 * @param intervalMillis poll cadence; the first tick fires one interval after [start].
 */
internal class ServeCatalogRefresher(
  private val entries: List<Entry>,
  private val reload: (system: String, repo: String) -> Boolean,
  private val intervalMillis: Long,
  private val headResolver: (repo: String, branch: String) -> String? = ::gitLsRemoteHead,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /** One watched catalog branch. */
  data class Entry(val system: String, val repo: String, val branch: String)

  private val lastHead = ConcurrentHashMap<String, String>()
  private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serve-catalog-refresh").apply { isDaemon = true }
  }

  /**
   * Record each branch's current head so the first tick only reloads a branch that has *moved since
   * boot*, not every branch once. Best-effort — an unresolvable branch stays absent, so its first
   * successful resolve later counts as "changed" and triggers one reload (harmless, idempotent).
   */
  fun seedInitialHeads() {
    for (e in entries) headResolver(e.repo, e.branch)?.let { lastHead[e.system] = it }
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
  fun tick() {
    for (e in entries) checkOne(e)
  }

  private fun checkOne(e: Entry) {
    // Can't resolve the head (offline / git missing / private) → leave what we serve untouched.
    val head = headResolver(e.repo, e.branch) ?: return
    if (head == lastHead[e.system]) return
    val prev = lastHead[e.system]
    onLog(
      "serve: catalog ${e.system} (${e.branch}) moved ${prev?.take(7) ?: "?"}→${head.take(7)} — re-fetching"
    )
    if (runCatching { reload(e.system, e.repo) }.getOrDefault(false)) {
      // Only advance the recorded head on success, so a failed reload retries next tick.
      lastHead[e.system] = head
      onLog("serve: catalog ${e.system} refreshed to ${head.take(7)}")
    } else {
      onLog("serve: catalog ${e.system} refresh failed — keeping the current copy, will retry")
    }
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
internal fun gitLsRemoteHead(repo: String, branch: String): String? =
  runCatching {
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
      val reader =
        Thread {
            runCatching {
              proc.inputStream.bufferedReader().use { r -> captured.append(r.readText()) }
            }
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
