package ee.schimke.composeai.daemon.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-classloader handoff for the plain-Compose named-override declarations (`compose/overrides`).
 *
 * **Why a sibling of [DaemonHostBridge] / [SandboxPermissionsBridge].** A preview's
 * `previewOverride*` lookups call `PreviewOverrideController.record(...)` from *inside* the
 * Robolectric sandbox — the controller is loaded fresh per-sandbox (the `ee.schimke.composeai`
 * namespace is acquired by the sandbox classloader by default), so the daemon-host
 * `PreviewOverridesDataProductRegistry` (constructed once on the host thread, in the host
 * classloader) reads a different — empty — copy of the controller's static state when it answers
 * `data/fetch?kind=compose/overrides`. The desktop backend has no sandbox and needs no bridge.
 *
 * This bridge is registered as a do-not-acquire package on [SandboxHoldingRunner], so the same
 * single instance is visible from both sides of the sandbox boundary. The controller's `record`
 * pushes here; the host registry drains via [snapshot]; [reset] is the per-preview cleanup the
 * controller's `clearDeclarations` / `resetForNewSession` call.
 *
 * **Strict bridge-package rules.** Only `java.util.concurrent.*` types, primitives, and `String`.
 * No Compose, no Robolectric, no `ee.schimke.composeai.*` imports — those would drag the bridge back
 * into the instrumented graph and break the single-instance invariant. The declarations therefore
 * cross as **JSON strings** (the controller serialises each [PreviewOverrideDeclaration] before
 * forwarding, the host registry decodes them): a typed object built in the sandbox classloader could
 * not be cast to the host classloader's copy of the same class.
 *
 * **Per-preview scoping.** State is keyed by previewId (mirroring [SandboxPermissionsBridge]) so a
 * pooled-sandbox run (`sandboxCount > 1`) where preview A and preview B compose concurrently doesn't
 * leak A's knobs into B's `snapshot`. Writers stamp their active previewId (the controller forwards
 * it from the around-composable's `ExtensionComposeContext.previewId`); the host reads back by the
 * same previewId. A render with no previewId scopes under [NO_PREVIEW_SCOPE].
 */
object SandboxPreviewOverridesBridge {

  /** Scope key used when a render carries no previewId (legacy stub-render payloads). */
  const val NO_PREVIEW_SCOPE: String = ""

  private val arrivalCounter: AtomicLong = AtomicLong(0L)

  /**
   * previewId -> (declaration seedKey -> [Slot]). Both maps are [ConcurrentHashMap] so writes from
   * any sandbox thread race-free and concurrent previews each own a private inner map. The seedKey
   * keying mirrors the controller's own de-dup: re-declaring a key (recomposition) replaces its JSON
   * in place while keeping the first-seen arrival order.
   */
  private val byPreview: ConcurrentHashMap<String, ConcurrentHashMap<String, Slot>> =
    ConcurrentHashMap()

  /** First-seen arrival order + the latest JSON for a declaration. Never crosses the boundary. */
  private class Slot(@JvmField val order: Long, @JvmField @Volatile var json: String)

  /**
   * Sandbox-side: record (or replace) [previewId]'s declaration [seedKey] with its serialised
   * [json]. First call for a (previewId, seedKey) stamps an arrival counter so [snapshot] preserves
   * declaration order; a later call for the same key updates the JSON in place (a recomposition with
   * a fresh effective value) without reordering.
   */
  @JvmStatic
  fun record(previewId: String, seedKey: String, json: String) {
    byPreview
      .computeIfAbsent(previewId) { ConcurrentHashMap() }
      .compute(seedKey) { _, existing ->
        if (existing == null) Slot(arrivalCounter.incrementAndGet(), json)
        else existing.also { it.json = json }
      }
  }

  /**
   * Host-side: snapshot [previewId]'s declarations as serialised JSON strings, in declaration order.
   * Returns a primitive `String[]` so the cross-loader transport stays in JLS types only — neither
   * side reinterprets a `kotlin.collections.List` from the other's classloader. A previewId the
   * bridge never saw (or one whose declarations were [reset]) returns an empty array.
   */
  @JvmStatic
  fun snapshot(previewId: String): Array<String> {
    val inner = byPreview[previewId] ?: return emptyArray()
    if (inner.isEmpty()) return emptyArray()
    val sorted = inner.values.sortedBy { it.order }
    return Array(sorted.size) { sorted[it].json }
  }

  /**
   * Both sides: drop the recorded declarations for [previewId] only. Called by
   * `PreviewOverrideController.clearDeclarations` at the start of each render (so a shrinking list's
   * stale indexed knobs drop) and by `resetForNewSession`. Scoped to the one preview so a
   * concurrently-held preview's declarations survive — a JVM-wide `clear()` would reintroduce the
   * cross-preview leak the per-preview keying fixes.
   */
  @JvmStatic
  fun reset(previewId: String) {
    byPreview.remove(previewId)
  }

  /**
   * Both sides: drop every recorded declaration across all previews. Used by host-restart paths and
   * test isolation where a full wipe is the intent. Per-render/-session cleanup must use [reset].
   */
  @JvmStatic
  fun resetAll() {
    byPreview.clear()
  }
}
