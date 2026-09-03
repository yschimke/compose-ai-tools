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
            symbol =
              ComponentSymbol(
                jvmOwner = target.className,
                callable = callableFqn(target),
                name = target.functionName,
                origin = origin,
                jvmName = target.jvmName,
                descriptor = target.descriptor,
                sourceFile = target.sourceFile,
                receiver = target.receiver,
              ),
            parameters = target.parameters,
            signatureKnown = target.signatureKnown,
            jvmName = target.jvmName,
            descriptor = target.descriptor,
            callableFromAnotherFile = target.callableFromAnotherFile,
            hasTypeParameters = target.hasTypeParameters,
            requiredOptIns = target.requiredOptIns,
            androidxOptIns = target.androidxOptIns,
          )
        }
      // Overloads share a canonical id and merge into this one record. Both JVM handles identify
      // ONE method, so keeping the first seen would label the merged record with whichever preview
      // the manifest happened to list first. Disagreement drops each to null instead — the record
      // then says "several methods, and I cannot tell you which", which is true, rather than
      // naming one of them.
      //
      // `jvmName` needs the same rule as `descriptor` and not merely the same rule as `name`:
      // overloads always agree on the source name, and can disagree on the JVM one, because
      // mangling is per-signature. `Chip(label: String)` and `Chip(width: Dp)` are `Chip` and
      // `Chip-a1b2c3d`.
      if (existing.jvmName != target.jvmName) {
        existing.jvmName = null
        existing.overloadsCollided = true
      }
      if (existing.descriptor != target.descriptor) {
        existing.descriptor = null
        existing.overloadsCollided = true
      }
      // One component, many previews: keep the richest signature seen. A target resolved through a
      // path that could not read metadata reports no parameters, and letting that overwrite a
      // populated signature would lose the API for everyone.
      //
      // A read signature always beats an unread one, even when the read one has fewer parameters:
      // "no arguments, and we checked" is strictly more information than "we could not look", and
      // it is the only form a code generator is allowed to act on.
      if (target.signatureKnown && !existing.signatureKnown) {
        existing.parameters = target.parameters
        existing.receiver = target.receiver
        existing.signatureKnown = true
        existing.callableFromAnotherFile = target.callableFromAnotherFile
        existing.hasTypeParameters = target.hasTypeParameters
        existing.requiredOptIns = target.requiredOptIns
        existing.androidxOptIns = target.androidxOptIns
      } else if (
        target.signatureKnown == existing.signatureKnown &&
          target.parameters.size > existing.parameters.size
      ) {
        existing.parameters = target.parameters
        existing.receiver = target.receiver
      }
      existing.bindings +=
        ComponentBinding(
          previewId = preview.id,
          componentId = preview.catalog?.componentId?.takeIf { it.isNotBlank() },
        )
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
    // A nested class, object or companion arrives with the JVM binary separator
    // (`com.example.Controls$Companion`). Emitting that verbatim would print an import no Kotlin
    // compiler accepts, which is the one thing this field exists to avoid.
    val owner = target.className.replace('$', '.')
    val simpleName = owner.substringAfterLast('.')
    if (!simpleName.endsWith("Kt") || simpleName.length == 2) return "$owner.${target.functionName}"
    val packageName = owner.substringBeforeLast('.', missingDelimiterValue = "")
    return if (packageName.isEmpty()) target.functionName else "$packageName.${target.functionName}"
  }

  /**
   * A `@Composable` lambda parameter is a slot, carrying the qualified receiver
   * ([TargetParameter.composableSlotReceiver]) when it has one.
   */
  internal fun slotsOf(parameters: List<TargetParameter>): List<ComponentSlot> =
    parameters
      .filter { it.composableSlot }
      .map { parameter ->
        ComponentSlot(
          name = parameter.name,
          required = !parameter.hasDefault,
          // The QUALIFIED receiver recorded from metadata, not a slice of the human-readable
          // rendered type: `RowScope` alone cannot be imported, and two libraries can define it.
          receiverScope = parameter.composableSlotReceiver,
        )
      }

  private class MutableComponent(
    val canonicalId: String,
    val symbol: ComponentSymbol,
    var parameters: List<TargetParameter>,
    var signatureKnown: Boolean = false,
    var jvmName: String? = null,
    var descriptor: String? = null,
    var callableFromAnotherFile: Boolean = true,
    var hasTypeParameters: Boolean = false,
    var requiredOptIns: List<String> = emptyList(),
    var androidxOptIns: List<String> = emptyList(),
  ) {
    /**
     * Set when two targets under this id disagreed about which method they are. Distinct from a
     * null [descriptor], which is also what an unrecorded one looks like.
     */
    var overloadsCollided: Boolean = false

    var receiver: String? = symbol.receiver

    var bindings: List<ComponentBinding> = emptyList()

    fun toRecord(): ComponentRecord {
      val resolvedBindings = bindings.distinctBy { it.previewId }.sortedBy { it.previewId }
      val record =
        ComponentRecord(
          canonicalId = canonicalId,
          // Every alias any preview published this symbol under, not whichever the manifest listed
          // first — a shared component such as `Card` is rendered by several previews and would
          // otherwise take an arbitrary, order-dependent id.
          componentIds = resolvedBindings.mapNotNull { it.componentId }.distinct().sorted(),
          symbol = symbol.copy(receiver = receiver, jvmName = jvmName, descriptor = descriptor),
          parameters = parameters,
          slots = slotsOf(parameters),
          bindings = resolvedBindings,
          signatureKnown = signatureKnown,
          callableFromAnotherFile = callableFromAnotherFile,
          hasTypeParameters = hasTypeParameters,
          overloadsCollided = overloadsCollided,
          requiredOptIns = requiredOptIns,
          androidxOptIns = androidxOptIns,
        )
      // Printed from the finished record, so the snippet is answering the same symbol, parameters
      // and receiver a consumer will read beside it.
      return record.copy(code = ComponentSnippets.codeFor(record))
    }
  }
}
