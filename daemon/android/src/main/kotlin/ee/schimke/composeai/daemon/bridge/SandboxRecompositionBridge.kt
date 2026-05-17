package ee.schimke.composeai.daemon.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-classloader counter store for the `compose/recomposition` data product on Android (see
 * issue #1204).
 *
 * **Why a sibling of [DaemonHostBridge]?** Same do-not-acquire reason: the recomposition observer
 * is installed inside the Robolectric sandbox (against the sandbox-loaded `Recomposer`), but the
 * host-side `AndroidRecompositionDataProductRegistry` reads counter snapshots from the host JVM.
 * Both sides must see the same instance of this object, so it lives in the
 * `ee.schimke.composeai.daemon.bridge` package which is registered as a do-not-acquire package on
 * [ee.schimke.composeai.daemon.SandboxHoldingRunner].
 *
 * **Strict bridge-package rules apply.** Only `java.util.concurrent.*` types and primitives. No
 * Compose, no Robolectric, no `ee.schimke.composeai.*` imports — those would drag the bridge back
 * into the instrumented graph and break the single-instance invariant.
 *
 * **Counter shape.** Per (previewId, streamId), the bridge holds a `ConcurrentHashMap<Int, Int>`
 * keyed by `System.identityHashCode(RecomposeScope)` and valued by the recomposition delta count.
 * The sandbox-side observer ([ee.schimke.composeai.daemon.RobolectricHost]'s held-rule loop)
 * increments via [markRecomposed]; the host-side registry drains via [drainCounters], which atomically
 * snapshots and clears the entries so each subsequent call carries only the delta since the previous
 * drain.
 *
 * **Wire shape across the bridge.** [drainCounters] returns `arrayOf(String[], long[])`: the first
 * element holds the base-16 scope ids (sorted), the second holds the matching counts. Mirrors the
 * parallel-primitive-arrays convention `DaemonHostBridge` already uses (and what the issue brief
 * spells out as the prescribed seam shape) — keeps the cross-loader transport in pure JLS types so
 * neither side reinterprets the other's `Map<*,*>` or `Pair<*,*>`.
 */
object SandboxRecompositionBridge {

  /** Composite key. Two strings + separator: previewId is supplied by the host, streamId by the host
   *  acquire path. Kept as a single [String] so the inner map's hashing is one lookup, not two. */
  private fun key(previewId: String, streamId: String): String = "$previewId|$streamId"

  /** Per-stream counter maps. Outer map is concurrent; inner map is concurrent per the issue brief
   *  ("thread-safe ConcurrentHashMap per (previewId, frameStreamId)"). */
  private val counters: ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>> = ConcurrentHashMap()

  /**
   * Sandbox-side: open a counter slot for [previewId] + [streamId]. Idempotent — re-calling on a
   * live slot is a no-op (returns the same inner map). The host-side registry calls into this via
   * the held-rule loop after enqueueing an `InteractiveCommand.StartObserveRecomposition`.
   */
  @JvmStatic
  fun open(previewId: String, streamId: String) {
    counters.computeIfAbsent(key(previewId, streamId)) { ConcurrentHashMap() }
  }

  /**
   * Sandbox-side: tear down counters for [previewId] + [streamId]. Idempotent. The held-rule loop
   * calls this in response to `InteractiveCommand.StopObserveRecomposition` or at session close.
   */
  @JvmStatic
  fun close(previewId: String, streamId: String) {
    counters.remove(key(previewId, streamId))
  }

  /**
   * Sandbox-side: bump the recomposition counter for [scopeHash] within [previewId] + [streamId].
   * Called from the `CompositionObserver.onScopeExit` callback the held-rule loop installs. Silently
   * dropped if the slot has been closed in the meantime (race-free against [close]).
   */
  @JvmStatic
  fun markRecomposed(previewId: String, streamId: String, scopeHash: Int) {
    val map = counters[key(previewId, streamId)] ?: return
    map.merge(scopeHash, 1, Int::plus)
  }

  /**
   * Sandbox-side: drop the counter for [scopeHash] when the recompose scope is disposed. Keeps the
   * map from leaking entries across a long-running session.
   */
  @JvmStatic
  fun markDisposed(previewId: String, streamId: String, scopeHash: Int) {
    val map = counters[key(previewId, streamId)] ?: return
    map.remove(scopeHash)
  }

  /**
   * Host-side: snapshot + reset the per-scope counters for [previewId] + [streamId].
   *
   * Wire shape: `arrayOf(String[] ids, long[] counts)` where `ids[i]` is `Integer.toHexString(hash)`
   * for the i-th entry (sorted ascending by id for deterministic payloads) and `counts[i]` is the
   * delta count since the previous drain. Returns `arrayOf(String[0], long[0])` when no observer
   * is active for the slot — the caller treats that the same as a live-but-quiet slot, i.e. an
   * empty `nodes: []` attachment.
   *
   * Reset is per-entry (`ConcurrentHashMap.remove`) so concurrent observer increments don't lose
   * a count between snapshot and reset — same atomic pattern the desktop producer uses.
   */
  @JvmStatic
  fun drainCounters(previewId: String, streamId: String): Array<Any> {
    val map = counters[key(previewId, streamId)] ?: return arrayOf(emptyArray<String>(), LongArray(0))
    if (map.isEmpty()) return arrayOf(emptyArray<String>(), LongArray(0))
    val keys = map.keys.toList()
    val pairs = ArrayList<Pair<String, Long>>(keys.size)
    for (k in keys) {
      val v = map.remove(k) ?: continue
      pairs.add(Integer.toHexString(k) to v.toLong())
    }
    pairs.sortBy { it.first }
    val ids = Array(pairs.size) { pairs[it].first }
    val counts = LongArray(pairs.size) { pairs[it].second }
    return arrayOf(ids, counts)
  }

  /**
   * Host-side: drop every counter slot. Called from [DaemonHostBridge.reset] so a host restart
   * within a single JVM doesn't leak counters from the previous lifecycle. Bridge-package
   * convention: the [DaemonHostBridge] reset path owns the cross-restart cleanup.
   */
  @JvmStatic
  fun resetAll() {
    counters.clear()
  }
}
