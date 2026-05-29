package ee.schimke.composeai.daemon.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-classloader handoff for the runtime-permissions query tracker (issue #1400).
 *
 * **Why a sibling of [DaemonHostBridge].** `PermissionsController.recordQuery(...)` fires from
 * inside the Robolectric sandbox — `ShadowContextWrapperPermissionTracker` intercepts
 * `ContextWrapper.checkPermission(...)` for every consumer `ContextCompat.checkSelfPermission(...)`
 * read, and the shadow runs in sandbox-instrumented code. The sandbox classloader acquires the
 * `ee.schimke.composeai.daemon` package by default, so the controller's static state is loaded
 * fresh per-sandbox and the daemon-host `PermissionsDataProductRegistry` (constructed once on the
 * host thread, in the host classloader) sees an empty `queried` list when it reads
 * `PermissionsController.queried.value` for the `compose/permissions` data product. The grants
 * leg works because the host-side planner sets the host-CL controller directly; queries have no
 * such host-side write path.
 *
 * This bridge is registered as a do-not-acquire package on [SandboxHoldingRunner], so the same
 * single instance is visible from both sides of the sandbox boundary. The shadow's controller
 * call pushes to [recordQuery] here; the host registry drains via [snapshot] / [reset].
 *
 * **Strict bridge-package rules.** Only `java.util.concurrent.*` types and primitives. No
 * Compose, no Robolectric, no `ee.schimke.composeai.*` imports — those would drag the bridge
 * back into the instrumented graph and break the single-instance invariant.
 *
 * **Per-preview scoping (issue #1593).** A naive single JVM-wide map keyed by permission name
 * leaks across concurrent previews: when `sandboxCount > 1`, preview A's sandbox and preview B's
 * sandbox both write into the same shared singleton, and the host's `compose/permissions` readback
 * for A returns answers that belong to B. The same flat map also accumulates across re-uses of a
 * single slot for different previews. To fix it the bridge mirrors
 * [SandboxRecompositionBridge]'s `(previewId)` keying: state is an outer map keyed by previewId,
 * each holding the permission -> arrival-counter inner map. Writers stamp their active previewId
 * (the controller forwards it from the around-composable's `ExtensionComposeContext.previewId`);
 * the host reads back by the same previewId. A render with no previewId (legacy stub payloads)
 * scopes under [NO_PREVIEW_SCOPE].
 *
 * **State shape.** Per previewId, a concurrent map keyed by permission name and valued by a
 * monotonic arrival counter so [snapshot] can return the unique permissions in insertion order
 * (the same shape `PermissionsController.queriedSet` produces in-process). The arrival counter is
 * a single JVM-wide sequence — ordering only matters within a previewId's snapshot, and a global
 * counter keeps cross-preview comparisons meaningless without extra per-preview bookkeeping.
 * Cumulative across renders within the active interactive session; [reset] is the per-preview
 * cleanup hook that `PermissionsController.resetForNewSession` calls and [resetAll] the JVM-wide
 * one for host restart / test isolation.
 */
object SandboxPermissionsBridge {

  /** Scope key used when a render carries no previewId (legacy stub-render payloads). */
  const val NO_PREVIEW_SCOPE: String = ""

  private val arrivalCounter: AtomicLong = AtomicLong(0L)

  /**
   * previewId -> (permission name -> arrival counter). Outer and inner maps are both
   * [ConcurrentHashMap] so writes from any sandbox thread race-free, and concurrent previews each
   * own a private inner map — preview A's queries can never surface in preview B's [snapshot].
   */
  private val queriesByPreview: ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> =
    ConcurrentHashMap()

  /**
   * Sandbox-side: record that [previewId]'s screen queried [permission]. Idempotent within a
   * preview — the first call for a given (previewId, permission) stamps an arrival counter;
   * subsequent calls are no-ops so the snapshot preserves the original insertion order. Mirrors
   * `PermissionsController.recordQuery`'s de-duplication semantics so the bridge and the in-CL
   * controller agree on the queried set.
   */
  @JvmStatic
  fun recordQuery(previewId: String, permission: String) {
    queriesByPreview
      .computeIfAbsent(previewId) { ConcurrentHashMap() }
      .computeIfAbsent(permission) { arrivalCounter.incrementAndGet() }
  }

  /**
   * Host-side: snapshot [previewId]'s queried permissions in insertion order without clearing.
   * Repeated reads — e.g. the panel re-fetching `compose/permissions` between renders — see the
   * cumulative set for that preview, matching the in-CL controller's "session-lifetime" semantics.
   * A previewId the bridge never saw returns an empty array (the registry treats that as "no
   * queries", i.e. `NotAvailable` when grants are empty too).
   *
   * Returns a primitive `String[]` so the cross-loader transport stays in JLS types only —
   * neither side reinterprets a `kotlin.collections.List` from the other's classloader. Mirrors
   * the `arrayOf(String[], long[])` shape `SandboxRecompositionBridge.drainCounters` uses.
   */
  @JvmStatic
  fun snapshot(previewId: String): Array<String> {
    val queries = queriesByPreview[previewId] ?: return emptyArray()
    if (queries.isEmpty()) return emptyArray()
    val entries = queries.entries.toList().sortedBy { it.value }
    return Array(entries.size) { entries[it].key }
  }

  /**
   * Both sides: drop the recorded queries for [previewId] only. Called by
   * `PermissionsController.resetForNewSession` (per-session cleanup) so closing preview A's session
   * doesn't wipe a concurrently-held preview B's queries — the bug a JVM-wide `clear()` would
   * reintroduce.
   */
  @JvmStatic
  fun reset(previewId: String) {
    queriesByPreview.remove(previewId)
  }

  /**
   * Both sides: drop every recorded query across all previews. Used by host-restart paths and test
   * isolation where a full wipe is the intent. Per-session cleanup must use [reset] instead so it
   * stays scoped to the closing preview.
   */
  @JvmStatic
  fun resetAll() {
    queriesByPreview.clear()
  }
}
