package ee.schimke.composeai.discovery

/**
 * Builds [ComponentRecordFile] from a discovered [PreviewManifest].
 *
 * A pure function of the manifest, deliberately: everything it needs — the resolved targets, their
 * signatures, the preview ids — is already recorded there, so the record can be rebuilt from a
 * published manifest without re-scanning, and this can be tested without a ClassGraph scan.
 */
object ComponentRecords {

  /**
   * Group every preview's targets by component and invert the relation: `previews.json` says "this
   * render came from that component", this says "this component is rendered by those previews".
   *
   * Both [PreviewInfo.componentTargets] and [PreviewInfo.targets] contribute, because they answer
   * different questions and a module can have either or both: a catalog sticker wrapping
   * `material3.Button` has only the former, an application preview of `HomeScreen` only the latter.
   * [ComponentSymbol.origin] is what tells them apart in the output, so a consumer never has to
   * know which list a record came from.
   *
   * Components are emitted in a stable order (by [ComponentRecord.canonicalId]) and bindings in
   * preview-id order, so the file is byte-reproducible across runs — a data product that reorders
   * itself between builds is a diff nobody can read.
   */
  fun from(manifest: PreviewManifest): ComponentRecordFile {
    val byId = linkedMapOf<String, MutableComponent>()
    for (preview in manifest.previews) {
      collect(preview, preview.componentTargets, ComponentOrigin.LIBRARY, manifest.module, byId)
      collect(preview, preview.targets, ComponentOrigin.PROJECT, manifest.module, byId)
    }
    return ComponentRecordFile(
      module = manifest.module,
      variant = manifest.variant,
      components = byId.values.map { it.toRecord() }.sortedBy { it.canonicalId },
    )
  }

  private fun collect(
    preview: PreviewInfo,
    targets: List<PreviewTarget>,
    origin: ComponentOrigin,
    module: String,
    into: MutableMap<String, MutableComponent>,
  ) {
    for (target in targets) {
      val id = canonicalId(module, target)
      val existing =
        into.getOrPut(id) {
          MutableComponent(
            canonicalId = id,
            componentId = preview.catalog?.componentId?.takeIf { it.isNotBlank() },
            symbol =
              ComponentSymbol(
                jvmOwner = target.className,
                callable = callableFqn(target),
                name = target.functionName,
                origin = origin,
                sourceFile = target.sourceFile,
              ),
            parameters = target.parameters,
          )
        }
      // One component, many previews: keep the richest signature seen. A target resolved through a
      // path that could not read metadata reports no parameters, and letting that overwrite a
      // populated signature would lose the API for everyone.
      if (target.parameters.size > existing.parameters.size) {
        existing.parameters = target.parameters
      }
      existing.bindings += ComponentBinding(previewId = preview.id)
    }
  }

  /**
   * `<module>/<jvmOwner>.<name>` — always present, unlike a catalog id.
   *
   * The module prefix keeps two projects' same-named components apart in an aggregated view; the
   * JVM owner keeps a top-level function apart from a same-named member of a class in the same
   * package. Overloads still collide — see [ComponentRecord.canonicalId].
   */
  internal fun canonicalId(module: String, target: PreviewTarget): String =
    "$module/${target.className}.${target.functionName}"

  /**
   * The source-level callable FQN a generated import would name.
   *
   * A top-level function compiles into a synthetic `<File>Kt` facade, so its callable is the
   * package plus the function name — `androidx.compose.material3.ButtonKt` + `Button` becomes
   * `androidx.compose.material3.Button`. A member of a real class keeps its owner.
   *
   * The `Kt` suffix is a heuristic and it can be wrong: a hand-written class genuinely named
   * `FooKt` would be unwrapped here. Kotlin's own convention makes that rare, and the alternative —
   * reading the `@kotlin.Metadata` kind for every target — costs a class-file read per component
   * for a case nobody has hit. Recorded as a known limit rather than hidden.
   */
  internal fun callableFqn(target: PreviewTarget): String {
    val owner = target.className
    val simpleName = owner.substringAfterLast('.')
    if (!simpleName.endsWith("Kt") || simpleName.length == 2) return "$owner.${target.functionName}"
    val packageName = owner.substringBeforeLast('.', missingDelimiterValue = "")
    return if (packageName.isEmpty()) target.functionName else "$packageName.${target.functionName}"
  }

  /**
   * A `@Composable` lambda parameter is a slot. The receiver, when the rendered type carries one,
   * is everything before the `.(` — `RowScope.() -> Unit` yields `RowScope`.
   */
  internal fun slotsOf(parameters: List<TargetParameter>): List<ComponentSlot> =
    parameters
      .filter { it.composableSlot }
      .map { parameter ->
        ComponentSlot(
          name = parameter.name,
          required = !parameter.hasDefault,
          receiverScope =
            parameter.type.substringBefore(".(", missingDelimiterValue = "").ifEmpty { null },
        )
      }

  private class MutableComponent(
    val canonicalId: String,
    val componentId: String?,
    val symbol: ComponentSymbol,
    var parameters: List<TargetParameter>,
  ) {
    var bindings: List<ComponentBinding> = emptyList()

    fun toRecord(): ComponentRecord =
      ComponentRecord(
        canonicalId = canonicalId,
        componentId = componentId,
        symbol = symbol,
        parameters = parameters,
        slots = slotsOf(parameters),
        bindings = bindings.distinctBy { it.previewId }.sortedBy { it.previewId },
      )
  }
}
