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
 * **State shape.** A single concurrent map per JVM keyed by permission name, valued by a
 * monotonic arrival counter so [snapshot] can return the unique permissions in insertion order
 * (the same shape `PermissionsController.queriedSet` produces in-process). Cumulative across
 * renders within the active interactive session; [reset] is the per-session cleanup hook that
 * `PermissionsController.resetForNewSession` calls.
 */
object SandboxPermissionsBridge {

  private val arrivalCounter: AtomicLong = AtomicLong(0L)

  /** Permission name -> arrival counter. ConcurrentHashMap so writes from any sandbox thread race-free. */
  private val queries: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

  /**
   * Sandbox-side: record that the screen queried [permission]. Idempotent — the first call for
   * a given permission stamps an arrival counter; subsequent calls are no-ops so the snapshot
   * preserves the original insertion order. Mirrors `PermissionsController.recordQuery`'s
   * de-duplication semantics so the bridge and the in-CL controller agree on the queried set.
   */
  @JvmStatic
  fun recordQuery(permission: String) {
    queries.computeIfAbsent(permission) { arrivalCounter.incrementAndGet() }
  }

  /**
   * Host-side: snapshot the queried permissions in insertion order without clearing. Repeated
   * reads — e.g. the panel re-fetching `compose/permissions` between renders — see the cumulative
   * set, matching the in-CL controller's "session-lifetime" semantics.
   *
   * Returns a primitive `String[]` so the cross-loader transport stays in JLS types only —
   * neither side reinterprets a `kotlin.collections.List` from the other's classloader. Mirrors
   * the parallel-primitive-arrays shape `SandboxRecompositionBridge.drainCounters` uses.
   */
  @JvmStatic
  fun snapshot(): Array<String> {
    if (queries.isEmpty()) return emptyArray()
    val entries = queries.entries.toList().sortedBy { it.value }
    return Array(entries.size) { entries[it].key }
  }

  /**
   * Both sides: drop every recorded query. Called by `PermissionsController.resetForNewSession`
   * (per-session cleanup) and by [DaemonHostBridge]-style host restart paths so a fresh sandbox
   * doesn't see the previous session's queries.
   */
  @JvmStatic
  fun reset() {
    queries.clear()
  }
}
