package ee.schimke.composeai.discovery

/**
 * Builds [ComponentRecordFile] from a [PreviewManifest]. A pure function of the manifest, so
 * records can be rebuilt from a published manifest and tested without a scan.
 */
object ComponentRecords {

  /**
   * Inverts previews → components into components → previews. Both [PreviewInfo.componentTargets]
   * (library components) and [PreviewInfo.targets] (project composables) contribute;
   * [ComponentSymbol.origin] tells them apart. Ordered by [ComponentRecord.canonicalId] and preview
   * id for byte-reproducible output.
   */
  fun from(manifest: PreviewManifest): ComponentRecordFile = select(manifest) { emptySet() }.record

  /** A record, and what choosing its components' overloads had to say about it. */
  data class Selection(
    val record: ComponentRecordFile,
    val diagnostics: List<UiBuilderDiagnostic> = emptyList(),
  )

  /**
   * [from], choosing each component's overload against the names a builder policy authors
   * ([supplied]; see [OverloadSelection]). [supplied] receives the policy-free record so callers
   * can join by canonical id or builder id.
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
      // Overloads share a canonical id and merge here. On disagreement each JVM handle drops to
      // null ("several methods") rather than naming whichever came first. `jvmName` follows the
      // descriptor rule because mangling is per-signature (`Chip` vs `Chip-a1b2c3d`).
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
      // Keep the richest signature: an unread one must not overwrite a populated one, and a read
      // signature beats an unread one even with fewer parameters, since only it may be acted on.
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
            // Already resolved by discovery (override, file `@CatalogGroup`, else `Components`);
            // carried for unannotated components' shelves.
            b.group = preview.catalog?.group?.takeIf { it.isNotBlank() }
          }
          .build()
      // Builder policy applies to ONE component, not every component the preview renders (e.g. not
      // `Text` inside `Button { Text(label) }`).
      if (builderSubject != null && builderSubject.canonicalId == id) {
        existing.builderDeclarations += preview.id to builderSubject.policy
      }
    }
  }

  /** The one component a preview's `@BuilderComponent` is about, and the policy it carries. */
  private data class BuilderSubject(val canonicalId: String, val policy: BuilderPolicy)

  /**
   * Which component a preview's builder policy is about, or null. Candidates are everything it
   * renders, library targets first.
   * 1. **Named** (`component = "…"`, FQN or simple name): wins; a name matching nothing binds
   *    nothing (reported as an orphan).
   * 2. **One candidate**: the ordinary sticker.
   * 3. **Several, unnamed**: bound to the first (discovery's order), the rest recorded in
   *    [BuilderPolicy.ambiguousWith] for reporting — a visible guess beats an annotation that
   *    silently does nothing.
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
    // The declaring sticker's catalog identity, so a derived builder id comes from it rather than
    // the alphabetically first alias.
    val declared =
      policy
        .newBuilder()
        .also { b ->
          b.declaredForCatalogId = preview.catalog?.componentId?.takeIf { it.isNotBlank() }
        }
        .build()
    if (candidates.isEmpty()) {
      // Reported whether or not a subject was named: an unbound policy does nothing, which is just
      // as true when targets couldn't be inferred.
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
      // An FQN wins outright. A simple name must match exactly one target; otherwise report an
      // orphan listing candidates, since picking one would also suppress `ambiguousWith`.
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
        // Reported, not dropped: the generator reads the record, so the orphan must travel in the
        // file.
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
   * `<module>/<jvmOwner>.<name>`, always present. The module separates projects; the owner
   * separates top-level functions from same-named members. Overloads still collide (see
   * [ComponentRecord.canonicalId]).
   */
  internal fun canonicalId(module: String, target: PreviewTarget): String =
    "$module/${target.className}.${target.functionName}"

  /**
   * The source-level callable FQN an import would name: `<File>Kt` facades are unwrapped
   * (`ButtonKt` + `Button` → `material3.Button`); class members keep their owner. Heuristic: a
   * hand-written class named `FooKt` would be unwrapped too, a known limit not worth a metadata
   * read per component.
   */
  internal fun callableFqn(target: PreviewTarget): String {
    // Nested owners arrive with `$`, which no import accepts.
    val owner = target.className.replace('$', '.')
    val simpleName = owner.substringAfterLast('.')
    if (!simpleName.endsWith("Kt") || simpleName.length == 2) return "$owner.${target.functionName}"
    val packageName = owner.substringBeforeLast('.', missingDelimiterValue = "")
    return if (packageName.isEmpty()) target.functionName else "$packageName.${target.functionName}"
  }

  /**
   * `@Composable` lambda parameters are slots, with their qualified receiver
   * ([TargetParameter.composableSlotReceiver]) when present.
   */
  internal fun slotsOf(parameters: List<TargetParameter>): List<ComponentSlot> =
    parameters
      .filter { it.composableSlot }
      .map { parameter ->
        ComponentSlot.Builder(name = parameter.name, required = !parameter.hasDefault)
          .also { b ->
            // The qualified receiver from metadata; `RowScope` alone can't be imported and may be
            // ambiguous.
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
     * Set when targets under this id disagreed about the method; distinct from an unrecorded (null)
     * [descriptor].
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
     * Every `@BuilderComponent` policy declared for this component, with its preview; kept until
     * [mergedBuilderPolicy] needs to know whether they agreed.
     */
    var builderDeclarations: List<Pair<String, BuilderPolicy>> = emptyList()

    /**
     * The one policy this component publishes, or null. Agreeing declarations publish with
     * [BuilderPolicy.declaredBy] naming every declaring preview. On disagreement the **lowest
     * preview id wins** (manifest order isn't controlled by anyone) and the rest are listed in
     * [BuilderPolicy.conflicting] for someone to fix.
     */
    fun mergedBuilderPolicy(): BuilderPolicy? {
      if (builderDeclarations.isEmpty()) return null
      // Dedupe: `collect` runs over both target lists, so a preview could otherwise be named twice.
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
     * When previews called several overloads under this id, adopt the one most of them called, with
     * its whole signature; only a tie stays collided. Refusing all would withdraw components;
     * first-arrived would depend on manifest order.
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
     * The record published when a policy supplies [supplied], plus any selection diagnostic.
     * Multiple overloads go through [OverloadSelection], which also refuses a single deprecated
     * one.
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
            // Every alias any preview published this symbol under, so shared components don't get
            // an order-dependent id.
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
      // Printed from the finished record so the snippet matches what consumers read.
      return record.newBuilder().also { b -> b.code = ComponentSnippets.codeFor(record) }.build()
    }
  }
}
