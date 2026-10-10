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
  fun from(manifest: PreviewManifest): ComponentRecordFile = select(manifest) { emptySet() }.record

  /** A record, and what choosing its components' overloads had to say about it. */
  data class Selection(
    val record: ComponentRecordFile,
    val diagnostics: List<UiBuilderDiagnostic> = emptyList(),
  )

  /**
   * [from], with each component's overload chosen against the names [supplied] says a builder
   * policy authors for it (see [OverloadSelection]). [supplied] receives the policy-free record, so
   * a caller can join it to a policy by canonical id or derived builder id.
   */
  fun select(
    manifest: PreviewManifest,
    supplied: (ComponentRecord) -> Set<String>,
  ): Selection {
    val diagnostics = mutableListOf<UiBuilderDiagnostic>()
    val components =
      collectAll(manifest).values.map { component ->
        val names = supplied(component.toRecord(emptySet()).first)
        val (record, diagnostic) = component.toRecord(names)
        diagnostic?.let(diagnostics::add)
        record
      }
    return Selection(
      ComponentRecordFile.Builder(
          module = manifest.module,
          variant = manifest.variant,
          components = components.sortedBy { it.canonicalId },
        )
        .also { b -> b.builderOrphans = collectOrphans(manifest) }
        .build(),
      diagnostics.sortedBy { it.subject },
    )
  }

  private fun collectOrphans(manifest: PreviewManifest): List<BuilderOrphan> {
    val orphans = mutableListOf<BuilderOrphan>()
    for (preview in manifest.previews) builderSubject(preview, manifest.module, orphans)
    return orphans.sortedBy { it.previewId }
  }

  private fun collectAll(manifest: PreviewManifest): Map<String, MutableComponent> {
    val byId = linkedMapOf<String, MutableComponent>()
    val orphans = mutableListOf<BuilderOrphan>()
    for (preview in manifest.previews) {
      val subject = builderSubject(preview, manifest.module, orphans)
      collect(
        preview,
        preview.componentTargets,
        ComponentOrigin.LIBRARY,
        manifest.module,
        byId,
        subject,
      )
      collect(preview, preview.targets, ComponentOrigin.PROJECT, manifest.module, byId, subject)
    }
    return byId
  }

  private fun collect(
    preview: PreviewInfo,
    targets: List<PreviewTarget>,
    origin: ComponentOrigin,
    module: String,
    into: MutableMap<String, MutableComponent>,
    builderSubject: BuilderSubject?,
  ) {
    for (target in targets) {
      val id = canonicalId(module, target)
      val existing =
        into.getOrPut(id) {
          MutableComponent(
            canonicalId = id,
            symbol =
              ComponentSymbol.Builder(
                  jvmOwner = target.className,
                  callable = callableFqn(target),
                  name = target.functionName,
                  origin = origin,
                )
                .also { b ->
                  b.jvmName = target.jvmName
                  b.descriptor = target.descriptor
                  b.sourceFile = target.sourceFile
                  b.receiver = target.receiver
                }
                .build(),
            parameters = target.parameters,
            signatureKnown = target.signatureKnown,
            jvmName = target.jvmName,
            descriptor = target.descriptor,
            callableFromAnotherFile = target.callableFromAnotherFile,
            hasTypeParameters = target.hasTypeParameters,
            hasContextReceivers = target.hasContextReceivers,
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
      target.descriptor?.let { d ->
        existing.overloads.getOrPut(d) { OverloadSeen(target) }.previews += preview.id
      }
      // Every overload the owner declares, in declaration order, whichever preview reported it.
      for (overload in target.overloads) existing.known.putIfAbsent(overload.descriptor, overload)
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
        existing.hasContextReceivers = target.hasContextReceivers
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
        ComponentBinding.Builder(previewId = preview.id)
          .also { b ->
            b.componentId = preview.catalog?.componentId?.takeIf { it.isNotBlank() }
            // Already resolved by discovery: the per-component override, else the file's
            // `@CatalogGroup`, else `Components`. Carried so the builder's shelf can be built for
            // components that annotate nothing.
            b.group = preview.catalog?.group?.takeIf { it.isNotBlank() }
          }
          .build()
      // Builder policy travels with the preview that declared it — but onto ONE component, not
      // every component the preview renders. A sticker is routinely `Button { Text(label) }`, and
      // both calls are recorded here; writing the button's builder id, canvas adapter and state
      // callbacks onto `Text` as well would hand a second component an identity that belongs to
      // the first.
      if (builderSubject != null && builderSubject.canonicalId == id) {
        existing.builderDeclarations += preview.id to builderSubject.policy
      }
    }
  }

  /** The one component a preview's `@BuilderComponent` is about, and the policy it carries. */
  private data class BuilderSubject(val canonicalId: String, val policy: BuilderPolicy)

  /**
   * Which component a preview's builder policy is about, or null when it declares none.
   *
   * The candidates are every component the preview renders, library targets first: a catalog
   * sticker exists to demonstrate the library component it wraps, and its own project composables —
   * where it has any — are the wrapper rather than the subject.
   *
   * Three cases, in order:
   *
   * 1. **The annotation names one** (`component = "…CheckboxButton"`, by FQN or simple name). That
   *    wins, and a name matching nothing the preview renders binds nothing — a policy attached to a
   *    component that is not there is a rename that got away, and quietly attaching it to whatever
   *    else was in the list would hide it.
   * 2. **One candidate.** The ordinary sticker. No ambiguity to record.
   * 3. **Several, unnamed.** Bound to the first, with the rest recorded in
   *    [BuilderPolicy.ambiguousWith] for the generator to report by name. The first is discovery's
   *    inference order — the outermost call, usually, but a guess either way. It is a guess rather
   *    than a refusal because the alternative is an annotation somebody wrote that silently does
   *    nothing, and a wrong-but-reported binding is the one a person can see and fix.
   */
  private fun builderSubject(
    preview: PreviewInfo,
    module: String,
    orphans: MutableList<BuilderOrphan>,
  ): BuilderSubject? {
    val policy = preview.builder ?: return null
    val candidates =
      (preview.componentTargets + preview.targets)
        .map { canonicalId(module, it) to it }
        .distinctBy { it.first }
    // The catalog identity of the sticker that declared this, so a derived builder id comes from
    // THIS sticker rather than from the alphabetically first of a shared callable's aliases.
    val declared =
      policy
        .newBuilder()
        .also { b ->
          b.declaredForCatalogId = preview.catalog?.componentId?.takeIf { it.isNotBlank() }
        }
        .build()
    if (candidates.isEmpty()) {
      // Reported whether or not the annotation named a subject. A policy that bound to nothing is
      // an annotation somebody wrote whose every field does nothing, and that is true of an
      // ordinary `@BuilderComponent(canvas = "…")` on a preview whose targets could not be inferred
      // exactly as it is true of a misspelled `component = "…"`. Recording only the named case left
      // the commoner one silent — the author sees no canvas, no starter and no diagnostic.
      orphans +=
        BuilderOrphan.Builder(
            previewId = preview.id,
            component = policy.component?.takeIf { it.isNotBlank() } ?: "(no subject named)",
          )
          .also { b -> b.candidates = emptyList() }
          .build()
      return null
    }

    val named = policy.component?.takeIf { it.isNotBlank() }
    if (named != null) {
      // An FQN is unique by construction, so it wins outright. A SIMPLE name is only accepted when
      // it matches one target: two callables named `Text` from different packages is an ordinary
      // shape, and picking the first would attach the canvas, callbacks and saved-design identity
      // to whichever the scan happened to reach first — silently, because naming a subject
      // suppresses the `ambiguousWith` that would otherwise record the alternatives. Reported as an
      // orphan instead, listing the candidates, so the fix (write the FQN) is in the message.
      val exact = candidates.filter { (_, target) -> callableFqn(target) == named }
      val bySimpleName = candidates.filter { (_, target) -> target.functionName == named }
      val match = exact.firstOrNull() ?: bySimpleName.singleOrNull()
      if (match == null && bySimpleName.size > 1) {
        orphans +=
          BuilderOrphan.Builder(previewId = preview.id, component = named)
            .also { b -> b.candidates = bySimpleName.map { it.first } }
            .build()
        return null
      }
      if (match == null) {
        // Reported rather than dropped. A subject naming nothing the preview renders is a rename
        // that got away, and the generator reads the record rather than the manifest — so if the
        // orphan does not travel in the file, it cannot be reported anywhere a person will look.
        orphans +=
          BuilderOrphan.Builder(previewId = preview.id, component = named)
            .also { b -> b.candidates = candidates.map { it.first } }
            .build()
        return null
      }
      return BuilderSubject(match.first, declared)
    }

    val (subject, rest) = candidates.first() to candidates.drop(1)
    return BuilderSubject(
      subject.first,
      if (rest.isEmpty()) declared
      else declared.newBuilder().also { b -> b.ambiguousWith = rest.map { it.first } }.build(),
    )
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
        ComponentSlot.Builder(name = parameter.name, required = !parameter.hasDefault)
          .also { b ->
            // The QUALIFIED receiver recorded from metadata, not a slice of the human-readable
            // rendered type: `RowScope` alone cannot be imported, and two libraries can define it.
            b.receiverScope = parameter.composableSlotReceiver
          }
          .build()
      }

  /** One overload seen under a canonical id, and the previews whose call sites invoked it. */
  private class OverloadSeen(val target: PreviewTarget) {
    val previews: MutableSet<String> = linkedSetOf()
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
    var hasContextReceivers: Boolean = false,
    var requiredOptIns: List<String> = emptyList(),
    var androidxOptIns: List<String> = emptyList(),
  ) {
    /**
     * Set when two targets under this id disagreed about which method they are. Distinct from a
     * null [descriptor], which is also what an unrecorded one looks like.
     */
    var overloadsCollided: Boolean = false

    /**
     * Every overload a preview called under this id, by descriptor, with the previews calling it.
     */
    val overloads: MutableMap<String, OverloadSeen> = linkedMapOf()

    /** Every overload the owner declares under this name, by descriptor, declaration order. */
    val known: MutableMap<String, TargetOverload> = linkedMapOf()

    var receiver: String? = symbol.receiver

    var bindings: List<ComponentBinding> = emptyList()

    /**
     * Every `@BuilderComponent` policy declared for this component, with the preview that declared
     * it. Reduced by [mergedBuilderPolicy]; kept as a list until then because the reduction needs
     * to know how many there were and whether they agreed.
     */
    var builderDeclarations: List<Pair<String, BuilderPolicy>> = emptyList()

    /**
     * The one policy this component publishes, or null when no sticker declared one.
     *
     * Several previews may render one component and any of them may carry the annotation. Where
     * they agree — the ordinary case, including one preview declaring it and the rest declaring
     * nothing — the agreed policy is published and [BuilderPolicy.declaredBy] names every preview
     * that said it. Where they disagree, the **lowest preview id wins** and the rest are named in
     * [BuilderPolicy.conflicting].
     *
     * Lowest-id rather than first-seen because manifest order is not a fact anybody controls, and a
     * record that changes which policy it publishes when a preview is renamed is a record nobody
     * can review. Recorded rather than resolved silently for the same reason the descriptor merge
     * drops to null: the resolution is arbitrary, and the disagreement is what somebody has to fix.
     */
    fun mergedBuilderPolicy(): BuilderPolicy? {
      if (builderDeclarations.isEmpty()) return null
      // Deduplicated first: `collect` runs once over a preview's componentTargets and again over
      // its targets, so a sticker that resolves the same component through both paths declares its
      // policy twice and `declaredBy` would name the preview twice for saying it once.
      val ordered = builderDeclarations.distinct().sortedBy { it.first }
      val winner = ordered.first().second
      val agreed = ordered.filter { it.second == winner }.map { it.first }
      val conflicting = ordered.filterNot { it.second == winner }.map { it.first }
      return winner
        .newBuilder()
        .also { b ->
          b.declaredBy = agreed
          b.conflicting = conflicting
        }
        .build()
    }

    /**
     * When previews called more than one overload under this id, the one most of them called.
     *
     * Discovery records the overload each call site actually invoked, so a catalog whose stickers
     * reach one function two ways — `Button(onClick, content)` beside `Button(onClick, shapes, …)`,
     * or `OutlinedTextField(value, onValueChange)` beside a screen using the `TextFieldState` one —
     * arrives here with several descriptors. Refusing code for all of them would withdraw every
     * component a second call site touches; keeping whichever arrived first would publish a
     * signature by manifest order. So the overload most previews call speaks for the record, with
     * its whole signature rather than a merge of several, and only a TIE stays collided — that
     * disagreement is real and somebody has to resolve it.
     */
    private fun adoptMajorityOverload() {
      if (!overloadsCollided || overloads.size < 2) return
      val ranked = overloads.values.sortedByDescending { it.previews.size }
      if (ranked[0].previews.size == ranked[1].previews.size) return
      val t = ranked[0].target
      descriptor = t.descriptor
      jvmName = t.jvmName
      parameters = t.parameters
      receiver = t.receiver
      signatureKnown = t.signatureKnown
      callableFromAnotherFile = t.callableFromAnotherFile
      hasTypeParameters = t.hasTypeParameters
      hasContextReceivers = t.hasContextReceivers
      requiredOptIns = t.requiredOptIns
      androidxOptIns = t.androidxOptIns
      overloadsCollided = false
    }

    /**
     * The record this component publishes when a policy supplies [supplied], and the selection's
     * diagnostic if it had one. Several known overloads go through [OverloadSelection]; a single
     * deprecated one is refused there too; otherwise the merged signature stands as before.
     */
    fun toRecord(supplied: Set<String>): Pair<ComponentRecord, UiBuilderDiagnostic?> {
      val alternatives = alternatives()
      if (alternatives.isEmpty()) return build() to null
      val choice = OverloadSelection.choose(alternatives, supplied)
      return choice.record to choice.diagnostic
    }

    private fun alternatives(): List<OverloadAlternative> {
      if (known.size >= 2) {
        return known.values.mapIndexed { index, overload ->
          OverloadAlternative(
            record = build(overload),
            previewCount = overloads[overload.descriptor]?.previews?.size ?: 0,
            declarationIndex = index,
            deprecated = overload.deprecated,
          )
        }
      }
      // One overload, and the call that reached it is deprecated: still a choice, of nothing.
      val called = overloads.values.singleOrNull()?.target
      if (called != null && called.deprecated && !overloadsCollided) {
        val count = overloads.values.single().previews.size
        return listOf(OverloadAlternative(build(), count, 0, deprecated = true))
      }
      return emptyList()
    }

    /** [build] with [overload]'s signature in place of the merged one, which is left as it was. */
    private fun build(overload: TargetOverload): ComponentRecord {
      val saved =
        listOf<Any?>(
          descriptor,
          jvmName,
          parameters,
          receiver,
          signatureKnown,
          callableFromAnotherFile,
          hasTypeParameters,
          hasContextReceivers,
          requiredOptIns,
          androidxOptIns,
          overloadsCollided,
        )
      try {
        return buildWith(overload)
      } finally {
        @Suppress("UNCHECKED_CAST")
        run {
          descriptor = saved[0] as String?
          jvmName = saved[1] as String?
          parameters = saved[2] as List<TargetParameter>
          receiver = saved[3] as String?
          signatureKnown = saved[4] as Boolean
          callableFromAnotherFile = saved[5] as Boolean
          hasTypeParameters = saved[6] as Boolean
          hasContextReceivers = saved[7] as Boolean
          requiredOptIns = saved[8] as List<String>
          androidxOptIns = saved[9] as List<String>
          overloadsCollided = saved[10] as Boolean
        }
      }
    }

    private fun buildWith(overload: TargetOverload): ComponentRecord {
      descriptor = overload.descriptor
      jvmName = overload.jvmName
      parameters = overload.parameters
      receiver = overload.receiver
      signatureKnown = true
      callableFromAnotherFile = overload.callableFromAnotherFile
      hasTypeParameters = overload.hasTypeParameters
      hasContextReceivers = overload.hasContextReceivers
      requiredOptIns = overload.requiredOptIns
      androidxOptIns = overload.androidxOptIns
      overloadsCollided = false
      return build()
    }

    private fun build(): ComponentRecord {
      adoptMajorityOverload()
      val resolvedBindings = bindings.distinctBy { it.previewId }.sortedBy { it.previewId }
      val record =
        ComponentRecord.Builder(
            canonicalId = canonicalId,
            symbol =
              symbol
                .newBuilder()
                .also { b ->
                  b.receiver = receiver
                  b.jvmName = jvmName
                  b.descriptor = descriptor
                }
                .build(),
          )
          .also { b ->
            // Every alias any preview published this symbol under, not whichever the manifest
            // listed
            // first — a shared component such as `Card` is rendered by several previews and would
            // otherwise take an arbitrary, order-dependent id.
            b.componentIds = resolvedBindings.mapNotNull { it.componentId }.distinct().sorted()
            b.parameters = parameters
            b.slots = slotsOf(parameters)
            b.bindings = resolvedBindings
            b.signatureKnown = signatureKnown
            b.callableFromAnotherFile = callableFromAnotherFile
            b.hasTypeParameters = hasTypeParameters
            b.overloadsCollided = overloadsCollided
            b.hasContextReceivers = hasContextReceivers
            b.requiredOptIns = requiredOptIns
            b.androidxOptIns = androidxOptIns
            b.builder = mergedBuilderPolicy()
          }
          .build()
      // Printed from the finished record, so the snippet is answering the same symbol, parameters
      // and receiver a consumer will read beside it.
      return record.newBuilder().also { b -> b.code = ComponentSnippets.codeFor(record) }.build()
    }
  }
}
