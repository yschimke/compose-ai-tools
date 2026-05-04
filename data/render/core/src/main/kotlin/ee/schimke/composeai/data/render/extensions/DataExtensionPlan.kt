package ee.schimke.composeai.data.render.extensions

import kotlinx.serialization.Serializable

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

object RecordingScriptDataExtensions {
  const val PROBE_EVENT: String = "recording.probe"
  const val STATE_SAVE_EVENT: String = "state.save"
  const val STATE_RESTORE_EVENT: String = "state.restore"
  const val PREVIEW_RELOAD_EVENT: String = "preview.reload"
  const val LIFECYCLE_EVENT: String = "lifecycle.event"

  val descriptors: List<DataExtensionDescriptor> =
    listOf(
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
      ),
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
      ),
      DataExtensionDescriptor(
        id = DataExtensionId("preview"),
        displayName = "Preview script controls",
        recordingScriptEvents =
          listOf(
            RecordingScriptEventDescriptor(
              id = PREVIEW_RELOAD_EVENT,
              displayName = "Reload preview",
              summary = "Requests preview reload during a recording script.",
            )
          ),
      ),
      DataExtensionDescriptor(
        id = DataExtensionId("lifecycle"),
        displayName = "Lifecycle script controls",
        recordingScriptEvents =
          listOf(
            RecordingScriptEventDescriptor(
              id = LIFECYCLE_EVENT,
              displayName = "Lifecycle event",
              summary = "Requests a lifecycle transition during a recording script.",
            )
          ),
      ),
    )
}

data class SimplePlannedDataExtension(
  override val id: DataExtensionId,
  override val hooks: Set<DataExtensionHookKind> = emptySet(),
  override val constraints: DataExtensionConstraints = DataExtensionConstraints(),
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
}
