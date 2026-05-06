package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.JsonElement

/**
 * Holds every [Extension] the daemon knows about plus the live "publicly enabled" set. Every wire
 * surface that needs to filter by activation goes through this object — JSON-RPC handlers consult
 * it for capabilities and routing, render-time hooks consult it to decide which producers run, and
 * the `extensions/{list,enable,disable}` methods mutate it.
 *
 * **Activation states.** Each extension is in one of three states at any moment:
 *
 * - **inactive** — neither publicly enabled nor pulled in by another active extension. Its
 *   contributions are entirely dormant.
 * - **active as dependency** — pulled in transitively because another publicly-enabled extension
 *   declared it. Render-time hooks (`onRender`, override planning) run so the depending extension
 *   sees its data; client-visible surfaces (capabilities lists, `data/fetch`, `data/subscribe`,
 *   render attachments, `extensions/list`'s "enabled" set) stay empty for this id.
 * - **publicly enabled** — turned on by an `extensions/enable` call. Contributes to every wire
 *   surface as well as render-time hooks.
 *
 * The registry is thread-safe; `enable`/`disable` take a write lock, every read path takes a read
 * lock and snapshots into an immutable view. This matches the JSON-RPC server's existing
 * concurrency shape (read thread + worker thread + watcher thread).
 */
class ExtensionRegistry(extensions: List<Extension>) {
  private val byId: Map<String, Extension>
  private val lock = ReentrantReadWriteLock()
  private val publicIds = HashSet<String>()
  // Cached transitive closure of `publicIds` plus dependencies, recomputed on each mutation.
  private var activeIdsCache: Set<String> = emptySet()

  init {
    val seen = HashMap<String, Extension>()
    for (ext in extensions) {
      val prior = seen.put(ext.id, ext)
      require(prior == null) { "Duplicate extension id '${ext.id}'" }
    }
    byId = seen
    for (ext in extensions) {
      for (dep in ext.dependencies) {
        require(dep in byId) { "Extension '${ext.id}' depends on unknown extension '$dep'" }
      }
    }
    detectCycle()
  }

  private fun detectCycle() {
    val visiting = HashSet<String>()
    val done = HashSet<String>()
    fun visit(id: String, path: List<String>) {
      if (id in done) return
      require(id !in visiting) { "Extension dependency cycle: ${(path + id).joinToString(" -> ")}" }
      visiting += id
      for (dep in byId.getValue(id).dependencies) visit(dep, path + id)
      visiting -= id
      done += id
    }
    for (id in byId.keys) visit(id, emptyList())
  }

  /** All registered extensions, in the order they were supplied. */
  fun all(): List<Extension> = byId.values.toList()

  /** True iff [id] was made public via [enable] (and not since [disable]d). */
  fun isPubliclyEnabled(id: String): Boolean = lock.read { id in publicIds }

  /** True iff [id] is publicly enabled or pulled in transitively. */
  fun isActive(id: String): Boolean = lock.read { id in activeIdsCache }

  /** Snapshot of publicly enabled ids. */
  fun publicIds(): Set<String> = lock.read { publicIds.toSet() }

  /** Snapshot of all ids that should run during a render (public + transitive deps). */
  fun activeIds(): Set<String> = lock.read { activeIdsCache.toSet() }

  /**
   * Enable [requested] ids. Unknown ids land in [EnableOutcome.unknown] and the rest are processed.
   * Already-enabled ids are reported separately. Transitive dependencies that come online land in
   * [EnableOutcome.pulledIn].
   */
  fun enable(requested: Iterable<String>): EnableOutcome {
    val asked = requested.toSet()
    val unknown = asked.filter { it !in byId }.sorted()
    val known = asked - unknown.toSet()
    return lock.write {
      val priorActive = activeIdsCache
      val alreadyEnabled = known.filter { it in publicIds }.sorted()
      val newlyEnabled = (known - publicIds).sorted()
      publicIds += newlyEnabled
      recomputeActive()
      val pulledIn = (activeIdsCache - priorActive - newlyEnabled.toSet()).sorted()
      EnableOutcome(
        newlyEnabled = newlyEnabled,
        pulledIn = pulledIn,
        alreadyEnabled = alreadyEnabled,
        unknown = unknown,
      )
    }
  }

  /**
   * Disable [requested] ids from the public set. Ids that remain active because another publicly
   * enabled extension still depends on them land in [DisableOutcome.stillActiveAsDependency] —
   * disabling them publicly is fine, the client just sees the dep didn't fully deactivate.
   */
  fun disable(requested: Iterable<String>): DisableOutcome {
    val asked = requested.toSet()
    val unknown = asked.filter { it !in byId }.sorted()
    val known = asked - unknown.toSet()
    return lock.write {
      val priorActive = activeIdsCache
      val notEnabled = known.filter { it !in publicIds }.sorted()
      val disabled = (known intersect publicIds).sorted()
      publicIds -= disabled.toSet()
      recomputeActive()
      val deactivated = (priorActive - activeIdsCache).sorted()
      val stillActive = disabled.filter { it in activeIdsCache }.sorted()
      DisableOutcome(
        disabled = disabled,
        deactivated = deactivated,
        stillActiveAsDependency = stillActive,
        notEnabled = notEnabled,
        unknown = unknown,
      )
    }
  }

  private fun recomputeActive() {
    val out = HashSet<String>()
    fun pull(id: String) {
      if (!out.add(id)) return
      for (dep in byId.getValue(id).dependencies) pull(dep)
    }
    for (id in publicIds) pull(id)
    activeIdsCache = out
  }

  // -------------------------------------------------------------------------
  // Wire-facing snapshots — only publicly enabled extensions contribute.
  // -------------------------------------------------------------------------

  fun publicDataProductCapabilities(): List<DataProductCapability> = lock.read {
    publicIds.sorted().flatMap { byId.getValue(it).dataProductCapabilities }
  }

  fun publicDataExtensionDescriptors(): List<DataExtensionDescriptor> = lock.read {
    publicIds.sorted().flatMap { byId.getValue(it).dataExtensionDescriptors }
  }

  fun publicPreviewExtensionDescriptors(): List<PreviewExtensionDescriptor> = lock.read {
    publicIds.sorted().flatMap { byId.getValue(it).previewExtensionDescriptors }
  }

  fun infoList(): List<ExtensionInfo> = lock.read {
    byId.values.map { ext ->
      ExtensionInfo(
        id = ext.id,
        displayName = ext.displayName,
        dependencies = ext.dependencies,
        publiclyEnabled = ext.id in publicIds,
        active = ext.id in activeIdsCache,
        dataProductKinds = ext.dataProductCapabilities.map { it.kind },
        dataExtensionIds = ext.dataExtensionDescriptors.map { it.id.value },
        previewExtensionIds = ext.previewExtensionDescriptors.map { it.id },
      )
    }
  }

  // -------------------------------------------------------------------------
  // Render-time + JSON-RPC delegation surfaces consumed by JsonRpcServer.
  //
  // Two facades over the registered registries:
  //  - [publicDataProducts] — the client-facing view. Capabilities, fetch, attachments, subscribe
  //    only delegate to publicly enabled extensions. Dep-only extensions stay invisible.
  //  - [activeDataProducts] — the render-time view. Run for any extension that's active, public or
  //    dependency. This is what feeds dependencies their producer-side data so a public extension
  //    that depends on them can read derived state.
  //
  // Both facades poll the live mutable state on every call so an `extensions/enable` mid-render
  // takes effect on the next render cycle without rebuilding the renderer.
  // -------------------------------------------------------------------------

  /** Composite over publicly enabled extensions. */
  fun publicDataProducts(): DataProductRegistry = ScopedDataProducts {
    lock.read { publicIds.toList() }
  }

  /** Composite over publicly enabled or transitively active extensions. */
  fun activeDataProducts(): DataProductRegistry = ScopedDataProducts {
    lock.read { activeIdsCache.toList() }
  }

  private inner class ScopedDataProducts(private val ids: () -> List<String>) :
    DataProductRegistry {
    override val capabilities: List<DataProductCapability>
      get() = ids().flatMap { byId.getValue(it).dataProductCapabilities }

    override fun isKnown(kind: String): Boolean =
      ids().any { byId.getValue(it).dataProductRegistry?.isKnown(kind) == true }

    override fun fetch(
      previewId: String,
      kind: String,
      params: JsonElement?,
      inline: Boolean,
    ): DataProductRegistry.Outcome {
      val reg =
        ids().firstNotNullOfOrNull { id ->
          val r = byId.getValue(id).dataProductRegistry ?: return@firstNotNullOfOrNull null
          if (r.isKnown(kind)) r else null
        } ?: return DataProductRegistry.Outcome.Unknown
      return reg.fetch(previewId, kind, params, inline)
    }

    override fun attachmentsFor(
      previewId: String,
      kinds: Set<String>,
    ): List<DataProductAttachment> =
      ids().flatMap { id ->
        val reg = byId.getValue(id).dataProductRegistry ?: return@flatMap emptyList()
        val supported = kinds.filterTo(mutableSetOf()) { reg.isKnown(it) }
        if (supported.isEmpty()) emptyList() else reg.attachmentsFor(previewId, supported)
      }

    override fun onRender(previewId: String, result: RenderResult) {
      onRender(previewId, result, overrides = null, previewContext = result.previewContext)
    }

    override fun onRender(previewId: String, result: RenderResult, overrides: PreviewOverrides?) {
      onRender(previewId, result, overrides, previewContext = result.previewContext)
    }

    override fun onRender(
      previewId: String,
      result: RenderResult,
      overrides: PreviewOverrides?,
      previewContext: PreviewContext?,
    ) {
      for (id in ids()) {
        byId
          .getValue(id)
          .dataProductRegistry
          ?.onRender(previewId, result, overrides, previewContext)
      }
    }

    override fun onRenderFailed(previewId: String, cause: Throwable) {
      for (id in ids()) byId.getValue(id).dataProductRegistry?.onRenderFailed(previewId, cause)
    }

    override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
      ids()
        .firstOrNull { byId.getValue(it).dataProductRegistry?.isKnown(kind) == true }
        ?.let { byId.getValue(it).dataProductRegistry?.onSubscribe(previewId, kind, params) }
    }

    override fun onUnsubscribe(previewId: String, kind: String) {
      ids()
        .firstOrNull { byId.getValue(it).dataProductRegistry?.isKnown(kind) == true }
        ?.let { byId.getValue(it).dataProductRegistry?.onUnsubscribe(previewId, kind) }
    }
  }

  /**
   * Build a [PreviewOverrideExtensions] aggregator that planners are run from. The aggregator polls
   * "is active" on each `plan(...)` so re-enabling/disabling at runtime takes effect on the next
   * render without rebuilding the renderer. Active-as-dependency overrides plan too — the depending
   * extension may need them to wrap the composable correctly.
   */
  fun activeOverrideExtensions(): PreviewOverrideExtensions {
    val byOverrideId = HashMap<String, String>() // dataExtensionId -> owning extension id
    for (ext in byId.values) {
      for (over in ext.previewOverrideExtensions) {
        byOverrideId[over.id.value] = ext.id
      }
    }
    val all = byId.values.flatMap { it.previewOverrideExtensions }
    return PreviewOverrideExtensions(
      extensions = all,
      isActive = { extension ->
        val owningId = byOverrideId[extension.id.value] ?: return@PreviewOverrideExtensions false
        isActive(owningId)
      },
    )
  }

  companion object {
    val Empty: ExtensionRegistry = ExtensionRegistry(emptyList())
  }
}

/**
 * Outcome of an [ExtensionRegistry.enable] call. Disjoint partition of the requested ids: every id
 * lands in exactly one of [newlyEnabled], [alreadyEnabled], or [unknown]. [pulledIn] is additive —
 * dependencies that came online as a side effect.
 */
data class EnableOutcome(
  val newlyEnabled: List<String>,
  val pulledIn: List<String>,
  val alreadyEnabled: List<String>,
  val unknown: List<String>,
)

/**
 * Outcome of an [ExtensionRegistry.disable] call. [disabled] are ids that were public and have been
 * removed from the public set; [deactivated] are ids that are no longer active at all (no remaining
 * public dependent); [stillActiveAsDependency] are ids that were disabled publicly but remain
 * active because another public extension still depends on them.
 */
data class DisableOutcome(
  val disabled: List<String>,
  val deactivated: List<String>,
  val stillActiveAsDependency: List<String>,
  val notEnabled: List<String>,
  val unknown: List<String>,
)

/** Per-extension snapshot returned by `extensions/list`. */
data class ExtensionInfo(
  val id: String,
  val displayName: String,
  val dependencies: List<String>,
  val publiclyEnabled: Boolean,
  val active: Boolean,
  val dataProductKinds: List<String>,
  val dataExtensionIds: List<String>,
  val previewExtensionIds: List<String>,
)
