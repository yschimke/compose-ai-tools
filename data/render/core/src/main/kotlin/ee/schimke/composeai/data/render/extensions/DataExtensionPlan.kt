package ee.schimke.composeai.data.render.extensions

import kotlinx.serialization.Serializable

/**
 * Stable, typed identity for one data product emitted by the extension graph.
 *
 * [kind] is the protocol/on-disk name clients already know (`"a11y/hierarchy"`). [type] is the
 * in-process payload shape extension authors consume from [DataProductStore]. Keeping both on the
 * same key lets planner code stay protocol-compatible while extension implementations depend on
 * compile-time payload types instead of string joins.
 */
data class DataProductKey<T : Any>(val kind: String, val schemaVersion: Int, val type: Class<T>) :
  Comparable<DataProductKey<*>> {
  init {
    require(kind.isNotBlank()) { "Data product kind must not be blank." }
    require(schemaVersion > 0) { "Data product schema version must be positive." }
  }

  override fun compareTo(other: DataProductKey<*>): Int =
    compareValuesBy(this, other, DataProductKey<*>::kind, DataProductKey<*>::schemaVersion)

  override fun toString(): String = "$kind@v$schemaVersion"
}

/** Read-only view of declared inputs for an extension. */
interface DataProductSource {
  fun <T : Any> get(key: DataProductKey<T>): T?

  fun <T : Any> require(key: DataProductKey<T>): T =
    get(key) ?: error("Data product '$key' is not available.")
}

/** Write-only view for emitting an extension's declared outputs. */
interface DataProductSink {
  fun <T : Any> put(key: DataProductKey<T>, value: T)
}

/** A small in-process product store used by downstream extensions to consume declared inputs. */
interface DataProductStore : DataProductSource, DataProductSink {
  /**
   * Returns a per-extension view that enforces the declared `inputs`/`outputs` contract: `get` only
   * succeeds for keys in [PlannedDataExtension.inputs] (or already produced), `put` only for keys
   * in [PlannedDataExtension.outputs]. Use this to wrap the shared store before handing it to a
   * hook so contract drift is caught at runtime, not days later in a downstream consumer.
   */
  fun scopedFor(extension: PlannedDataExtension): DataProductStore =
    ScopedDataProductStore(this, extension)
}

class RecordingDataProductStore : DataProductStore {
  private val values: MutableMap<DataProductKey<*>, Any> = linkedMapOf()

  override fun <T : Any> get(key: DataProductKey<T>): T? {
    val value = values[key] ?: return null
    return key.type.cast(value)
  }

  override fun <T : Any> put(key: DataProductKey<T>, value: T) {
    values[key] = key.type.cast(value)
  }
}

private class ScopedDataProductStore(
  private val delegate: DataProductStore,
  private val extension: PlannedDataExtension,
) : DataProductStore {
  override fun <T : Any> get(key: DataProductKey<T>): T? {
    require(key in extension.inputs) {
      "Data extension '${extension.id}' read undeclared product '$key'; " + "declare it in inputs."
    }
    return delegate.get(key)
  }

  override fun <T : Any> put(key: DataProductKey<T>, value: T) {
    require(key in extension.outputs) {
      "Data extension '${extension.id}' wrote undeclared product '$key'; " +
        "declare it in outputs."
    }
    delegate.put(key, value)
  }
}

data class RenderImageArtifact(val path: String, val mediaType: String = "image/png")

data class RenderSemanticsSnapshot(val handle: String)

data class RenderDensity(val density: Float)

object CommonDataProducts {
  val ImageArtifact: DataProductKey<RenderImageArtifact> =
    DataProductKey("render/image", schemaVersion = 1, RenderImageArtifact::class.java)

  val SemanticsSnapshot: DataProductKey<RenderSemanticsSnapshot> =
    DataProductKey(
      "render/semanticsSnapshot",
      schemaVersion = 1,
      RenderSemanticsSnapshot::class.java,
    )

  val Density: DataProductKey<RenderDensity> =
    DataProductKey("render/density", schemaVersion = 1, RenderDensity::class.java)
}

enum class DataExtensionTarget {
  Android,
  Desktop,
}

/**
 * Renderer-agnostic identity for a data extension.
 *
 * Extension ids are stable protocol/configuration names, not Kotlin class names. They are used in
 * request input maps, ordering constraints, and diagnostic messages.
 */
@Serializable
@JvmInline
value class DataExtensionId(val value: String) : Comparable<DataExtensionId> {
  init {
    require(value.isNotBlank()) { "Data extension id must not be blank." }
  }

  override fun compareTo(other: DataExtensionId): Int = value.compareTo(other.value)

  override fun toString(): String = value
}

/**
 * Named capability in the render/data-extension pipeline.
 *
 * Keep these stringly-typed for now so product modules can add capabilities without changing a
 * central enum on every extension migration.
 */
@Serializable
@JvmInline
value class DataExtensionCapability(val value: String) : Comparable<DataExtensionCapability> {
  init {
    require(value.isNotBlank()) { "Data extension capability must not be blank." }
  }

  override fun compareTo(other: DataExtensionCapability): Int = value.compareTo(other.value)

  override fun toString(): String = value
}

@Serializable
enum class DataExtensionPhase {
  OuterEnvironment,
  UserEnvironment,
  Instrumentation,
  Scenario,
  Capture,
  PostProcess,
  Publish,
}

@Serializable
enum class DataExtensionLifecycle {
  OneShot,
  Subscribed,
  AttachOnRender,
  ExplicitRerender,
  MultiFrame,
}

@Serializable
enum class DataExtensionHookKind {
  AroundComposable,
  ComposableExtractor,
  CompositionObserver,
  BeforeRender,
  AfterCapture,
  AfterRender,
  RenderFailure,
  Fetch,
  Subscription,
  ScenarioDriver,
}

@Serializable
data class DataExtensionConstraints(
  val phase: DataExtensionPhase = DataExtensionPhase.Instrumentation,
  val before: Set<DataExtensionId> = emptySet(),
  val after: Set<DataExtensionId> = emptySet(),
  val requires: Set<DataExtensionCapability> = emptySet(),
  val provides: Set<DataExtensionCapability> = emptySet(),
  val conflictsWith: Set<DataExtensionCapability> = emptySet(),
  val lifecycle: DataExtensionLifecycle = DataExtensionLifecycle.OneShot,
)

interface PlannedDataExtension {
  val id: DataExtensionId
  val hooks: Set<DataExtensionHookKind>
  val constraints: DataExtensionConstraints
  val inputs: Set<DataProductKey<*>>
    get() = emptySet()

  val outputs: Set<DataProductKey<*>>
    get() = emptySet()

  val targets: Set<DataExtensionTarget>
    get() = emptySet()
}

interface DataExtension<in Request> {
  val id: DataExtensionId
  val defaultConstraints: DataExtensionConstraints
    get() = DataExtensionConstraints()

  fun plan(request: Request): PlannedDataExtension?
}

@Serializable
data class DataExtensionDescriptor(
  val id: DataExtensionId,
  val displayName: String = id.value,
  val recordingScriptEvents: List<RecordingScriptEventDescriptor> = emptyList(),
)

@Serializable
data class RecordingScriptEventDescriptor(
  val id: String,
  val displayName: String = id,
  val summary: String = "",
  val supported: Boolean = false,
) {
  init {
    require(id.contains('.')) {
      "Recording script event id '$id' must be namespaced, e.g. '${id}.event'."
    }
    require(id.isNotBlank()) { "Recording script event id must not be blank." }
  }
}

/**
 * Built-in recording-script descriptor namespace. Splits cleanly between two halves:
 *
 * - [recordingDescriptor] — the `recording` extension, advertising `recording.probe` as `supported
 *   = true`. Each [ee.schimke.composeai.daemon.RenderHost] returns this from its
 *   `recordingScriptEventDescriptors()` override, alongside any host-specific extensions, so the
 *   daemon's `dataExtensions` capability set tracks what the host actually dispatches.
 * - [roadmapDescriptors] — `state` / `lifecycle` / `preview`, all `supported = false`. Advertised
 *   by `DaemonMain` regardless of host so agents can see what's planned via `list_data_products`.
 *   `record_preview` rejects them up front (see compose-ai-tools#714); the descriptors flip to
 *   `supported = true` only when a real handler lands in the host's session registry, at which
 *   point the descriptor moves out of `roadmapDescriptors` and into the host's own contribution.
 *
 * The legacy aggregate [descriptors] (probe + roadmap) is retained for callers that haven't
 * migrated yet — see the deprecation note.
 */
object RecordingScriptDataExtensions {
  const val PROBE_EVENT: String = "recording.probe"
  const val STATE_SAVE_EVENT: String = "state.save"
  const val STATE_RESTORE_EVENT: String = "state.restore"
  const val PREVIEW_RELOAD_EVENT: String = "preview.reload"
  const val LIFECYCLE_EVENT: String = "lifecycle.event"

  /**
   * `recording.probe` descriptor with `supported = true`. Returned from each
   * `RenderHost.recordingScriptEventDescriptors()` that wires a real probe handler in its
   * recording-session registry (today: both desktop and android backends).
   */
  val recordingDescriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("recording"),
      displayName = "Recording script markers",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = PROBE_EVENT,
            displayName = "Probe marker",
            summary = "Records a named point in the recording timeline.",
            supported = true,
          )
        ),
    )

  /**
   * Renderer-agnostic roadmap descriptors. Advertised by every daemon so `list_data_products`
   * surfaces the planned surface area, but `supported = false` everywhere — `record_preview`
   * rejects up front. When a host wires real dispatch for one of these, advertise the upgraded
   * descriptor from the host's `recordingScriptEventDescriptors()` and remove it from this list.
   */
  val roadmapDescriptors: List<DataExtensionDescriptor> =
    listOf(
      DataExtensionDescriptor(
        id = DataExtensionId("state"),
        displayName = "State restoration script markers",
        recordingScriptEvents =
          listOf(
            RecordingScriptEventDescriptor(
              id = STATE_SAVE_EVENT,
              displayName = "Save state checkpoint",
              summary = "Requests a saved-state checkpoint in a recording script.",
            ),
            RecordingScriptEventDescriptor(
              id = STATE_RESTORE_EVENT,
              displayName = "Restore state checkpoint",
              summary = "Requests restoration from a saved-state checkpoint.",
            ),
          ),
      )
      // `preview.reload` and `lifecycle.event` both moved out of this list — the Android backend
      // wires them via `PreviewReloadRecordingScriptEvents.descriptor` and
      // `LifecycleRecordingScriptEvents.descriptor` respectively, advertised through
      // `RobolectricHost.recordingScriptEventDescriptors()`. Renderer-agnostic descriptors only
      // carry roadmap entries that no host has wired yet (today: just `state.save` /
      // `state.restore`).
    )

  /**
   * Combined list of [recordingDescriptor] + [roadmapDescriptors]. Retained for callers that build
   * their `dataExtensions` from a single source; new code should prefer
   * `host.recordingScriptEventDescriptors() + roadmapDescriptors` so the host can opt in / out of
   * the supported half independently.
   */
  val descriptors: List<DataExtensionDescriptor> = listOf(recordingDescriptor) + roadmapDescriptors
}

data class SimplePlannedDataExtension(
  override val id: DataExtensionId,
  override val hooks: Set<DataExtensionHookKind> = emptySet(),
  override val constraints: DataExtensionConstraints = DataExtensionConstraints(),
  override val inputs: Set<DataProductKey<*>> = emptySet(),
  override val outputs: Set<DataProductKey<*>> = emptySet(),
  override val targets: Set<DataExtensionTarget> = emptySet(),
) : PlannedDataExtension

data class OrderedDataExtensionPlan(
  val extensions: List<PlannedDataExtension>,
  val initialCapabilities: Set<DataExtensionCapability> = emptySet(),
) {
  val providedCapabilities: Set<DataExtensionCapability> =
    extensions.fold(initialCapabilities) { provided, extension ->
      provided + extension.constraints.provides
    }
}

data class DataExtensionPlanningError(
  val code: String,
  val message: String,
  val extensions: List<DataExtensionId> = emptyList(),
)

data class DataExtensionPlanningResult(
  val orderedExtensions: List<PlannedDataExtension>,
  val errors: List<DataExtensionPlanningError>,
) {
  val isValid: Boolean
    get() = errors.isEmpty()
}

object DataExtensionPlanner {
  fun planOutputs(
    extensions: List<PlannedDataExtension>,
    requestedOutputs: Set<DataProductKey<*>>,
    initialProducts: Set<DataProductKey<*>> = emptySet(),
    initialCapabilities: Set<DataExtensionCapability> = emptySet(),
    target: DataExtensionTarget? = null,
  ): DataExtensionPlanningResult {
    val eligibleExtensions =
      if (target == null) extensions
      else
        extensions.filter { extension ->
          extension.targets.isEmpty() || target in extension.targets
        }
    val duplicateErrors =
      duplicateIdErrors(eligibleExtensions) + duplicateOutputErrors(eligibleExtensions)
    if (duplicateErrors.isNotEmpty()) {
      return DataExtensionPlanningResult(emptyList(), duplicateErrors)
    }

    val byOutput =
      eligibleExtensions
        .flatMap { extension -> extension.outputs.map { output -> output to extension } }
        .associate { it.first to it.second }
    val selected = linkedMapOf<DataExtensionId, PlannedDataExtension>()
    val visiting = linkedSetOf<DataProductKey<*>>()
    val errors = mutableListOf<DataExtensionPlanningError>()

    fun resolve(product: DataProductKey<*>) {
      if (product in initialProducts) return
      val provider = byOutput[product]
      if (provider == null) {
        errors +=
          DataExtensionPlanningError(
            code = "MissingProductProvider",
            message = "No data extension provides requested product '$product'.",
          )
        return
      }
      if (provider.id in selected) return
      if (!visiting.add(product)) {
        errors +=
          DataExtensionPlanningError(
            code = "ProductDependencyCycle",
            message = "Data product dependency graph contains a cycle at '$product'.",
            extensions = listOf(provider.id),
          )
        return
      }
      provider.inputs.sorted().forEach(::resolve)
      visiting.remove(product)
      selected[provider.id] = provider
    }

    requestedOutputs.sorted().forEach(::resolve)
    if (errors.isNotEmpty()) {
      return DataExtensionPlanningResult(emptyList(), errors)
    }

    val sorted = plan(selected.values.toList(), initialCapabilities = initialCapabilities)
    return DataExtensionPlanningResult(
      orderedExtensions = sorted.orderedExtensions,
      errors = sorted.errors + validateProducts(sorted.orderedExtensions, initialProducts),
    )
  }

  fun <Request> planRequest(
    extensions: List<DataExtension<Request>>,
    request: Request,
    initialCapabilities: Set<DataExtensionCapability> = emptySet(),
  ): DataExtensionPlanningResult =
    plan(
      extensions = extensions.mapNotNull { it.plan(request) },
      initialCapabilities = initialCapabilities,
    )

  fun plan(
    extensions: List<PlannedDataExtension>,
    initialCapabilities: Set<DataExtensionCapability> = emptySet(),
  ): DataExtensionPlanningResult {
    val duplicateErrors = duplicateIdErrors(extensions)
    if (duplicateErrors.isNotEmpty()) {
      return DataExtensionPlanningResult(emptyList(), duplicateErrors)
    }

    val sorted = sort(extensions)
    val errors = sorted.errors + validateCapabilities(sorted.ordered, initialCapabilities)
    return DataExtensionPlanningResult(orderedExtensions = sorted.ordered, errors = errors)
  }

  private fun duplicateIdErrors(
    extensions: List<PlannedDataExtension>
  ): List<DataExtensionPlanningError> =
    extensions
      .groupBy { it.id }
      .filterValues { it.size > 1 }
      .map { (id, matches) ->
        DataExtensionPlanningError(
          code = "DuplicateExtensionId",
          message = "Data extension '$id' is planned ${matches.size} times.",
          extensions = listOf(id),
        )
      }

  private fun duplicateOutputErrors(
    extensions: List<PlannedDataExtension>
  ): List<DataExtensionPlanningError> =
    extensions
      .flatMap { extension -> extension.outputs.map { output -> output to extension.id } }
      .groupBy { it.first }
      .filterValues { it.size > 1 }
      .map { (output, matches) ->
        DataExtensionPlanningError(
          code = "DuplicateProductProvider",
          message =
            "Data product '$output' is provided by multiple extensions: " +
              matches.joinToString { it.second.value } +
              ".",
          extensions = matches.map { it.second },
        )
      }

  private data class SortResult(
    val ordered: List<PlannedDataExtension>,
    val errors: List<DataExtensionPlanningError>,
  )

  private fun sort(extensions: List<PlannedDataExtension>): SortResult {
    val byId = extensions.associateBy { it.id }
    val edges = linkedMapOf<DataExtensionId, MutableSet<DataExtensionId>>()
    val incoming = linkedMapOf<DataExtensionId, MutableSet<DataExtensionId>>()
    val errors = mutableListOf<DataExtensionPlanningError>()

    extensions
      .sortedBy { it.id }
      .forEach { extension ->
        edges[extension.id] = linkedSetOf()
        incoming[extension.id] = linkedSetOf()
      }

    fun addEdge(before: DataExtensionId, after: DataExtensionId) {
      if (before == after) {
        errors +=
          DataExtensionPlanningError(
            code = "SelfOrderingConstraint",
            message = "Data extension '$before' cannot order itself before or after itself.",
            extensions = listOf(before),
          )
        return
      }
      if (before !in byId || after !in byId) return
      if (edges.getValue(before).add(after)) {
        incoming.getValue(after).add(before)
      }
    }

    for (extension in extensions) {
      for (before in extension.constraints.before) {
        if (before !in byId) {
          errors += unknownConstraint(extension.id, before, "before")
        } else {
          addEdge(extension.id, before)
        }
      }
      for (after in extension.constraints.after) {
        if (after !in byId) {
          errors += unknownConstraint(extension.id, after, "after")
        } else {
          addEdge(after, extension.id)
        }
      }
    }

    val byPhaseThenId =
      compareBy<PlannedDataExtension> { it.constraints.phase.ordinal }.thenBy { it.id.value }
    val phaseOrdered = extensions.sortedWith(byPhaseThenId)
    for (index in phaseOrdered.indices) {
      val earlier = phaseOrdered[index]
      for (later in phaseOrdered.drop(index + 1)) {
        if (earlier.constraints.phase.ordinal < later.constraints.phase.ordinal) {
          addEdge(earlier.id, later.id)
        }
      }
    }

    val remainingIncoming =
      incoming.mapValuesTo(linkedMapOf()) { (_, value) -> value.toMutableSet() }
    val ready =
      extensions
        .filter { remainingIncoming.getValue(it.id).isEmpty() }
        .sortedWith(byPhaseThenId)
        .toMutableList()
    val ordered = mutableListOf<PlannedDataExtension>()

    while (ready.isNotEmpty()) {
      val next = ready.removeAt(0)
      ordered += next
      for (after in edges.getValue(next.id).sorted()) {
        val afterIncoming = remainingIncoming.getValue(after)
        afterIncoming.remove(next.id)
        if (afterIncoming.isEmpty()) {
          byId[after]?.let { candidate ->
            if (candidate !in ordered && candidate !in ready) {
              ready += candidate
              ready.sortWith(byPhaseThenId)
            }
          }
        }
      }
    }

    if (ordered.size != extensions.size) {
      val cycleIds = extensions.map { it.id }.filter { id -> ordered.none { it.id == id } }.sorted()
      errors +=
        DataExtensionPlanningError(
          code = "OrderingCycle",
          message =
            "Data extension ordering constraints contain a cycle: ${cycleIds.joinToString()}.",
          extensions = cycleIds,
        )
    }

    return SortResult(ordered = ordered, errors = errors)
  }

  private fun unknownConstraint(
    owner: DataExtensionId,
    target: DataExtensionId,
    relation: String,
  ): DataExtensionPlanningError =
    DataExtensionPlanningError(
      code = "UnknownOrderingTarget",
      message =
        "Data extension '$owner' declares '$relation' constraint on unknown extension '$target'.",
      extensions = listOf(owner, target),
    )

  private fun validateCapabilities(
    ordered: List<PlannedDataExtension>,
    initialCapabilities: Set<DataExtensionCapability>,
  ): List<DataExtensionPlanningError> = buildList {
    var provided = initialCapabilities
    val plannedProviders = ordered.flatMap { extension ->
      extension.constraints.provides.map { it to extension.id }
    }

    for (extension in ordered) {
      val conflicts =
        extension.constraints.conflictsWith.intersect(
          provided +
            plannedProviders
              .filter { (_, provider) -> provider != extension.id }
              .map { (capability, _) -> capability }
              .toSet()
        )
      if (conflicts.isNotEmpty()) {
        add(
          DataExtensionPlanningError(
            code = "ConflictingCapability",
            message =
              "Data extension '${extension.id}' conflicts with planned " +
                "capabilities: ${conflicts.joinToString()}.",
            extensions =
              listOf(extension.id) +
                plannedProviders
                  .filter { (capability, _) -> capability in conflicts }
                  .map { it.second },
          )
        )
      }

      val missing = extension.constraints.requires - provided
      if (missing.isNotEmpty()) {
        add(
          DataExtensionPlanningError(
            code = "MissingCapability",
            message =
              "Data extension '${extension.id}' requires capabilities that are not " +
                "available yet: ${missing.joinToString()}.",
            extensions =
              listOf(extension.id) +
                plannedProviders
                  .filter { (capability, _) -> capability in missing }
                  .map { it.second },
          )
        )
      }

      provided += extension.constraints.provides
    }
  }

  private fun validateProducts(
    ordered: List<PlannedDataExtension>,
    initialProducts: Set<DataProductKey<*>>,
  ): List<DataExtensionPlanningError> = buildList {
    var provided = initialProducts
    for (extension in ordered) {
      val missing = extension.inputs - provided
      if (missing.isNotEmpty()) {
        add(
          DataExtensionPlanningError(
            code = "MissingProductInput",
            message =
              "Data extension '${extension.id}' requires data products that are not " +
                "available yet: ${missing.joinToString()}.",
            extensions = listOf(extension.id),
          )
        )
      }
      provided += extension.outputs
    }
  }
}
