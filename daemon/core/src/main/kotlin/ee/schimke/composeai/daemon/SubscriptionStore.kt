package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentHashMap

/**
 * Sticky `(previewId, kind)` subscription bookkeeping installed by `data/subscribe` and torn down
 * by `data/unsubscribe`, [retainVisible] (sticky-while-visible — see PROTOCOL.md), and
 * [removeKinds] (extension disable). All three teardown paths previously inlined identical "for
 * each affected pair, drop it and notify the producer" logic against the same
 * `ConcurrentHashMap<previewId, Set<kind>>`; concentrating the bookkeeping here keeps the map's
 * invariants in one place and lets producer-side `onUnsubscribe` routing stay at the callsite (the
 * daemon dispatches through `publicDataProducts()` for the subscribe / visibility paths and through
 * `activeDataProducts()` for the extension-disable path — same store mutation, different routing of
 * the side effect).
 *
 * Thread-safety: backed by [ConcurrentHashMap] with [ConcurrentHashMap.newKeySet] inner sets so the
 * render thread's [kindsFor] reads can race the read thread's subscribe / unsubscribe / prune
 * writes without locking — same guarantee the inlined version offered.
 */
internal class SubscriptionStore {
  private val byPreview = ConcurrentHashMap<String, MutableSet<String>>()

  /** Records the `(previewId, kind)` pair. Returns true iff the pair was newly added. */
  fun subscribe(previewId: String, kind: String): Boolean {
    val set = byPreview.computeIfAbsent(previewId) { ConcurrentHashMap.newKeySet() }
    return set.add(kind)
  }

  /**
   * Drops the `(previewId, kind)` pair. Returns true iff the pair was previously present — callers
   * use the verdict to decide whether to dispatch `onUnsubscribe` (spurious tear-down notifications
   * invite producers to log "no such subscription" warnings).
   */
  fun unsubscribe(previewId: String, kind: String): Boolean {
    var existed = false
    byPreview.computeIfPresent(previewId) { _, set ->
      existed = set.remove(kind)
      if (set.isEmpty()) null else set
    }
    return existed
  }

  /** Snapshot of currently subscribed kinds for [previewId]. Empty when none — never null. */
  fun kindsFor(previewId: String): Set<String> = byPreview[previewId]?.toSet() ?: emptySet()

  /** True iff at least one subscription is recorded for [previewId]. */
  fun hasAny(previewId: String): Boolean = !byPreview[previewId].isNullOrEmpty()

  /**
   * Sticky-while-visible prune: drop every pair whose `previewId` is not in [visible]. Returns the
   * dropped pairs in insertion-agnostic order so the caller can dispatch `onUnsubscribe` with the
   * routing it controls.
   */
  fun retainVisible(visible: Set<String>): List<Pair<String, String>> {
    val toDrop = byPreview.keys - visible
    if (toDrop.isEmpty()) return emptyList()
    val drops = mutableListOf<Pair<String, String>>()
    for (id in toDrop) {
      val kinds = byPreview.remove(id) ?: continue
      for (kind in kinds) drops += id to kind
    }
    return drops
  }

  /**
   * Extension-disable prune: drop every pair whose `kind` is in [kinds]. Returns the dropped pairs
   * so the caller can dispatch `onUnsubscribe` against the appropriate producer set (the extension
   * that owns the kind has already been deactivated, so the public producer surface no longer
   * advertises it — callers reach through `activeDataProducts()` instead).
   */
  fun removeKinds(kinds: Set<String>): List<Pair<String, String>> {
    if (kinds.isEmpty()) return emptyList()
    val drops = mutableListOf<Pair<String, String>>()
    for ((previewId, current) in byPreview) {
      val toRemove = current.intersect(kinds)
      if (toRemove.isEmpty()) continue
      current.removeAll(toRemove)
      for (k in toRemove) drops += previewId to k
    }
    byPreview.values.removeIf { it.isEmpty() }
    return drops
  }
}
