package ee.schimke.composeai.discovery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The `ui-builder.json` wire types live in compose-preview-contracts' `component-catalog-protocol`;
// this file is the generator that writes one.

/**
 * The annotation's policy with the authored one laid over it, field by field: a null authored
 * field means "not stated" and keeps the annotation's answer, while an authored empty list (e.g.
 * `modifierCapabilities: []`) means "none".
 */
internal fun UiBuilderComponentPolicy.mergedWith(
  authored: UiBuilderAuthoredComponent?
): UiBuilderComponentPolicy {
  if (authored == null) return this
  return newBuilder()
    .also { b ->
      b.record = authored.record ?: record
      b.displayName = authored.displayName ?: displayName
      b.canvas = authored.canvas ?: canvas
      b.canvasMapping = authored.canvasMapping ?: canvasMapping
      b.unrolled = authored.unrolled ?: unrolled
      b.nativeOnly = authored.nativeOnly ?: nativeOnly
      b.traits = authored.traits ?: traits
      b.excluded = authored.excluded ?: excluded
      b.propertyCapabilities = authored.propertyCapabilities ?: propertyCapabilities
      b.slotCapabilities = authored.slotCapabilities ?: slotCapabilities
      b.modifierCapabilities = authored.modifierCapabilities ?: modifierCapabilities
      b.insertContent = authored.insertContent ?: insertContent
      b.shelfRole = authored.shelfRole ?: shelfRole
    }
    .build()
}

/**
 * Generates a [UiBuilderCatalogFile] from the discovered record, the cover sheet and the authored
 * policy. Pure, so it runs in the Gradle discovery task, in tests and (via `:screen-model`) in the
 * browser.
 */
object UiBuilderCatalogs {

  /** Diagnostic codes, named so a gate can assert on them without matching prose. */
  object Diagnostics {
    const val POLICY_SCHEMA_UNKNOWN = "policy.schema.unknown"
    const val BUILTIN_ROLE_UNKNOWN = "policy.builtin.role.unknown"
    const val TEMPLATE_ROLE_UNKNOWN = "policy.code.template.role.unknown"
    const val TEMPLATE_MALFORMED = "policy.code.template.malformed"
    const val TEMPLATE_HOLE_UNKNOWN = "policy.code.template.hole.unknown"
    const val TEMPLATES_WITHOUT_STRATEGY = "policy.code.templates.withoutStrategy"
    const val STRATEGY_WITHOUT_TEMPLATES = "policy.code.strategy.withoutTemplates"
    const val CANVAS_UNCLAIMED = "component.canvas.unclaimed"
    const val COMPONENT_EXCLUDED = "component.excluded"
    const val STATE_CALLBACK_UNKNOWN = "component.stateCallback.unknownParameter"
    const val SLOT_UNKNOWN = "component.slot.unknown"
    const val POLICY_CONFLICT = "component.policy.conflict"
    const val POLICY_AMBIGUOUS_SUBJECT = "component.policy.ambiguousSubject"
    const val POLICY_MALFORMED_ENTRY = "component.policy.malformedEntry"
    const val POLICY_ORPHANED = "component.policy.orphaned"
    const val STATE_CALLBACK_NOT_A_PARAMETER = "component.stateCallback.notAParameter"
    const val STATE_CALLBACK_MALFORMED = "component.stateCallback.malformed"
    const val STARTER_UNKNOWN_PARAMETER = "component.starter.unknownParameter"
    const val VARIANT_PROPERTY_UNKNOWN = "component.variantProperty.unknownParameter"
    const val VARIANTS_WITHOUT_PROPERTY = "component.variants.withoutProperty"
    const val STATE_CALLBACK_NOT_A_FUNCTION = "component.stateCallback.notAFunction"
    const val STATE_CALLBACK_TYPE_MISMATCH = "component.stateCallback.typeMismatch"
    const val ID_COLLISION = "component.id.collision"
    const val ID_PREFIX_MALFORMED = "policy.componentIdPrefix.malformed"
    const val PLATFORM_MALFORMED = "policy.platform.malformed"
    const val STATE_CALLBACK_ARITY = "component.stateCallback.arity"
    const val BUILTIN_SHADOWS_RECORD = "policy.builtin.shadowsRecord"
    const val BUILTIN_SLOT_ROLE_UNKNOWN = "policy.builtin.slot.role.unknown"
    const val BUILTIN_SHELF_ROLE_UNKNOWN = "policy.builtin.shelfRole.unknown"
    const val COMPONENT_SHELF_ROLE_UNKNOWN = "policy.component.shelfRole.unknown"
    const val BUILTIN_WASM_STATUS_UNKNOWN = "policy.builtin.wasm.adapterStatus.unknown"
    const val BUILTIN_CODE_EMPTY = "policy.builtin.code.symbol.empty"
    const val STRATEGY_UNKNOWN = "policy.code.strategy.unknown"
  }

  /**
   * The cover-sheet fields a builder catalog needs, and no more: `catalog.spec.json` is owned by
   * the design-artifacts pipeline and this shouldn't break on additions it never reads.
   */
  data class CoverSheet(val system: String, val title: String)

  /**
   * Generate the builder catalog, or return `null` when [policy] is absent, so catalogs that
   * haven't adopted the contract publish nothing.
   */
  fun generate(
    record: ComponentRecordFile,
    cover: CoverSheet,
    policy: UiBuilderPolicyFile?,
    /** Findings made before generation — [OverloadSelection]'s — published with the rest. */
    extraDiagnostics: List<UiBuilderDiagnostic> = emptyList(),
  ): UiBuilderCatalogFile? {
    if (policy == null) return null
    val diagnostics = extraDiagnostics.toMutableList()
    if (policy.schema != UI_BUILDER_POLICY_SCHEMA) {
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_SCHEMA_UNKNOWN,
            subject = policy.schema,
            message =
              "ui-builder.policy.json declares schema '${policy.schema}'; this generator writes " +
                "$UI_BUILDER_POLICY_SCHEMA. Generated anyway — refusing would leave the author with " +
                "nothing to look at — but read the result against the schema it was written for.",
          )
          .build()
    }
    val catalogId = policy.catalogId?.takeIf { it.isNotBlank() } ?: cover.system
    val idPrefix = policy.componentIdPrefix?.takeIf { it.isNotBlank() } ?: "$catalogId/"
    // Shape checks repeated here because this is the authoritative generator: the schema and
    // workflow pre-flight don't run for a local `compose-preview-server ui` or a direct `bundle
    // pack`. Reported rather than corrected, since the prefix is the identity. Only the authored
    // prefix is checked; the `<catalogId>/` fallback isn't something the author wrote. The platform
    // word is compared by equality, so it gets the same check.
    if (!PLATFORM_WORD.matches(policy.platform)) {
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.PLATFORM_MALFORMED,
            subject = policy.platform,
            message =
              "'${policy.platform}' is the word catalogs are grouped by and equality is " +
                "compatibility, so it is a lower-case word (mobile, wear, remote-compose) rather " +
                "than a label. As written it joins no consumer expecting the lower-case form.",
          )
          .build()
    }
    val authoredPrefix = policy.componentIdPrefix?.takeIf { it.isNotBlank() }
    if (authoredPrefix != null && !ID_PREFIX.matches(authoredPrefix)) {
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.ID_PREFIX_MALFORMED,
            subject = idPrefix,
            message =
              "'$idPrefix' prefixes every derived builder id and has to end in '/' " +
                "(lower-case letters, digits and hyphens, e.g. 'm3/'). As written it derives ids " +
                "like '${idPrefix}button', which is the string every saved design stores.",
          )
          .build()
    }
    val platformLabel =
      policy.platformLabel?.takeIf { it.isNotBlank() } ?: titleCase(policy.platform)

    validateCode(policy, diagnostics)
    validateBuiltins(policy, record, idPrefix, diagnostics)
    for (orphan in record.builderOrphans) {
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_ORPHANED,
            subject = orphan.previewId,
            message =
              "@BuilderComponent(component = \"${orphan.component}\") names nothing ${orphan.previewId} " +
                "renders, so its policy was attached to no component and every field in it does " +
                "nothing. That preview renders: " +
                (orphan.candidates.takeIf { it.isNotEmpty() }?.joinToString() ?: "no components"),
          )
          .build()
    }

    val components = linkedMapOf<String, UiBuilderComponentPolicy>()
    val menuEntries = linkedMapOf<String, UiBuilderMenuEntry>()
    // `record` is the authoritative join for an authored component whose builder id differs from
    // the derived one (preserving saved-design ids across a rename). Resolve it once so collision
    // ownership, published policy and the menu agree.
    val authoredByRecord = linkedMapOf<String, Map.Entry<String, UiBuilderAuthoredComponent>>()
    for (entry in policy.components.entries) {
      val recordId = entry.value.record?.takeIf { it.isNotBlank() } ?: continue
      val previous = authoredByRecord[recordId]
      if (previous == null) {
        authoredByRecord[recordId] = entry
      } else {
        diagnostics +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.POLICY_CONFLICT,
              subject = recordId,
              message =
                "ui-builder.policy.json publishes both \"${previous.key}\" and " +
                  "\"${entry.key}\" for record $recordId. The first wins; one record is one " +
                  "saved-design component identity.",
            )
            .build()
      }
    }
    val builderIdsByRecord =
      record.components.associate { component ->
        component.canonicalId to
          (authoredByRecord[component.canonicalId]?.key
            ?: builderIdFor(
              idPrefix,
              component,
              component.builder ?: BuilderPolicy.Builder().build(),
            ))
      }
    val authoredPoliciesByRecord =
      record.components.associate { component ->
        val builderId = builderIdsByRecord.getValue(component.canonicalId)
        val joined = authoredByRecord[component.canonicalId]
        component.canonicalId to
          (joined?.value
            ?: policy.components[builderId]?.takeIf {
              it.record.isNullOrBlank() || it.record == component.canonicalId
            })
      }
    val consumedPolicyIds =
      record.components
        .mapNotNull { component ->
          authoredByRecord[component.canonicalId]?.key
            ?: builderIdsByRecord.getValue(component.canonicalId).takeIf { builderId ->
              authoredPoliciesByRecord[component.canonicalId] != null &&
                builderId in policy.components
            }
        }
        .toSet()
    // Which record owns each derived id, over every admitted component: unannotated ones are
    // shelved under derived ids too, so they can collide.
    val idOwners = linkedMapOf<String, String>()
    for (component in record.components) {
      val builderId = builderIdsByRecord.getValue(component.canonicalId)
      val owner = idOwners[builderId]
      if (owner == null) {
        idOwners[builderId] = component.canonicalId
        continue
      }
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.ID_COLLISION,
            subject = builderId,
            message =
              "'$builderId' is claimed by both $owner and ${component.canonicalId}. The first wins; " +
                "give one of them an explicit @BuilderComponent(id = …), because a saved design " +
                "stores this string and cannot be told which component it meant.",
          )
          .build()
    }
    for (component in record.components) {
      val builder = component.builder ?: BuilderPolicy.Builder().build()
      val builderId = builderIdsByRecord.getValue(component.canonicalId)
      // Use the owner the sweep established so policy, diagnostic and menu all agree.
      if (idOwners[builderId] != component.canonicalId) continue
      // Every admitted component gets an entry, so the published file names the record for every id
      // and consumers never have to re-derive ids (a second derivation diverges).
      val authored = authoredPoliciesByRecord[component.canonicalId]
      val fromAnnotation =
        if (component.builder != null) policyFor(component, builder)
        else UiBuilderComponentPolicy.Builder(record = component.canonicalId).build()
      val resolved = fromAnnotation.mergedWith(authored)
      // Diagnose the resolved policy, not just the annotation: the policy file can claim a canvas
      // adapter or exclude a component without any `@BuilderComponent`.
      diagnose(component, builder, resolved, builderId, diagnostics)
      components[builderId] = resolved
    }

    // An authored entry joining neither by `record` nor by resolved id is reported, not dropped: it
    // is most likely a typo or a rename that got away. A shelf role outside the known set is
    // reported too.
    for ((builderId, authored) in policy.components) {
      val shelfRole = authored.shelfRole ?: continue
      if (shelfRole in UI_BUILDER_SHELF_ROLES) continue
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.COMPONENT_SHELF_ROLE_UNKNOWN,
            subject = builderId,
            message =
              "shelfRole '$shelfRole' is not a shelf role. It is one of " +
                UI_BUILDER_SHELF_ROLES.sorted().joinToString() +
                "; the consumer ignores it and derives the role from whether the component has slots.",
          )
          .build()
    }
    for ((builderId, _) in policy.components) {
      if (builderId in consumedPolicyIds) continue
      diagnostics +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_ORPHANED,
            subject = builderId,
            message =
              "ui-builder.policy.json states a policy for \"$builderId\", which joins no component " +
                "in this record, so every field in it does nothing. Check its `record` canonicalId " +
                "or its builder id against components.json.",
          )
          .build()
    }

    // One catalog id is one shelf. Only the declaring sticker's binding carries a group, so other
    // callables a preview reaches are grouped by their catalog id. First writer wins so the answer
    // doesn't depend on record order. Containment check rather than `putIfAbsent`, which is
    // JVM-only (this also compiles for `wasmJs`).
    val groupByCatalogId = buildMap {
      for (component in record.components) {
        for (binding in component.bindings) {
          val catalogId = binding.componentId ?: continue
          val group = binding.group?.takeIf(String::isNotBlank) ?: continue
          if (catalogId !in this) put(catalogId, group)
        }
      }
    }

    // The shelf covers every admitted component, so the menu must too; the annotation's group is
    // only an override.
    for (component in record.components) {
      val builderId = builderIdsByRecord.getValue(component.canonicalId)
      if (idOwners[builderId] != component.canonicalId) continue
      // An excluded component gets no menu entry (the consumer refuses to serve it); its reason
      // still ships in `statusSemantics.components`.
      val excluded =
        authoredPoliciesByRecord[component.canonicalId]?.excluded?.takeIf { it.isNotBlank() }
          ?: component.builder?.exclude?.takeIf { it.isNotBlank() }
      if (excluded != null) continue
      // The group of the alias the id was derived from (as in `builderIdFor`): the declaring
      // sticker if any, else the first sorted `componentIds` — so the entry agrees with its own id.
      val idAlias = component.builder?.declaredForCatalogId ?: component.componentIds.firstOrNull()
      val group =
        // The policy file first, then the annotation, then the catalog's own grouping.
        authoredPoliciesByRecord[component.canonicalId]?.group?.takeIf { it.isNotBlank() }
          ?: component.builder?.group?.takeIf { it.isNotBlank() }
          ?: component.bindings
            .firstOrNull { it.componentId == idAlias && !it.group.isNullOrBlank() }
            ?.group
          ?: component.bindings.firstNotNullOfOrNull { it.group?.takeIf(String::isNotBlank) }
          // The shelf of the catalog id this component is published under; beyond this only the
          // policy file can place it.
          ?: idAlias?.let { groupByCatalogId[it] }
          ?: continue
      menuEntries[builderId] = UiBuilderMenuEntry.Builder(group = group).build()
    }

    return UiBuilderCatalogFile.Builder(
        catalog =
          UiBuilderCatalogIdentity.Builder(
              id = catalogId,
              title = cover.title,
              platform = policy.platform,
              platformLabel = platformLabel,
            )
            .also { b ->
              b.module = record.module
              b.variant = record.variant
            }
            .build(),
        record =
          UiBuilderRecordRef.Builder(
              schemaVersion = record.schemaVersion,
              components = record.components.size,
            )
            .build(),
        statusSemantics =
          UiBuilderStatusSemantics.Builder(
              platform = policy.platform,
              platformLabel = platformLabel,
              componentIdPrefix = idPrefix,
              componentMenu =
                UiBuilderComponentMenu.Builder()
                  .also { b ->
                    b.groupOrder = policy.menu?.groupOrder.orEmpty()
                    b.components = menuEntries
                  }
                  .build(),
            )
            .also { b ->
              b.previewSurfaces = policy.previewSurfaces
              b.browserPreview = policy.browserPreview
              b.frame = policy.frame
              b.code = policy.code
              b.composeSourceExport = policy.composeSourceExport
              b.templates = policy.templates.map { it.path }
              b.newDesign = newDesignSemantics(policy)
              b.colorTokens = policy.colorTokens
              b.assetRegistry = policy.assetRegistry
              b.supersedes = policy.supersedes
              b.builtins = policy.builtins
              b.components = components
            }
            .build(),
      )
      .also { b -> b.diagnostics = diagnostics }
      .build()
  }

  /** The shape a `componentIdPrefix` has to have, mirroring `ui-builder.policy.schema.json`. */
  private val ID_PREFIX = Regex("^[a-z0-9][a-z0-9-]*/$")

  /** And the platform word's, from the same schema. */
  private val PLATFORM_WORD = Regex("^[a-z0-9][a-z0-9-]*$")

  /**
   * The parameter names [policy] authors for a component, for [OverloadSelection]: capability names
   * plus a sticker's state callbacks, starters and slots. Joined exactly as [generate] joins them
   * so the chosen overload is the one the published policy describes.
   */
  fun authoredNames(
    cover: CoverSheet,
    policy: UiBuilderPolicyFile,
  ): (ComponentRecord) -> Set<String> {
    val catalogId = policy.catalogId?.takeIf { it.isNotBlank() } ?: cover.system
    val idPrefix = policy.componentIdPrefix?.takeIf { it.isNotBlank() } ?: "$catalogId/"
    val byRecord =
      policy.components.values
        .filter { !it.record.isNullOrBlank() }
        .groupBy { it.record!! }
        .mapValues { it.value.first() }
    return { component ->
      val builder = component.builder ?: BuilderPolicy.Builder().build()
      val authored =
        byRecord[component.canonicalId]
          ?: policy.components[builderIdFor(idPrefix, component, builder)]?.takeIf {
            it.record.isNullOrBlank() || it.record == component.canonicalId
          }
      val names = mutableSetOf<String>()
      fun nameOf(capability: kotlinx.serialization.json.JsonElement) =
        ((capability as? JsonObject)?.get("name") as? JsonPrimitive)?.content
      authored?.propertyCapabilities?.mapNotNullTo(names, ::nameOf)
      authored?.slotCapabilities?.mapNotNullTo(names, ::nameOf)
      builder.stateCallbacks.forEach {
        names += it.key
        names += it.value
      }
      builder.starter.forEach { names += it.key }
      builder.slots.forEach { names += it.key }
      names
    }
  }

  /**
   * The builder id for a record component: the annotation's, else [prefix] plus a slug of the
   * component symbol's name, else of the catalog identity's last segment. Overridable because saved
   * designs store this string.
   */
  internal fun builderIdFor(
    prefix: String,
    component: ComponentRecord,
    builder: BuilderPolicy,
  ): String {
    builder.id
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return it
      }
    // Derived from the component's own symbol, not a catalog id: one callable backs several
    // stickers (`Button/Filled`, `Button/Tonal`), so catalog-id last segments name the variant and
    // collide (`m3/filled`). The symbol is 1:1 with the record and matches the established ids
    // (`m3/button`); deliberate renames use `@BuilderComponent(id = …)`. The catalog id is the
    // fallback for an unreadable symbol.
    val fromCatalogId =
      (builder.declaredForCatalogId ?: component.componentIds.firstOrNull())
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    return "$prefix${slug(component.symbol.name.takeIf { it.isNotBlank() } ?: fromCatalogId.orEmpty())}"
  }

  /**
   * `CheckboxButton` → `checkbox-button`, `TopAppBar` → `top-app-bar`, `Button2` → `button2`. A run
   * of capitals is one word (`RTLText` → `rtl-text`).
   */
  internal fun slug(name: String): String {
    val out = StringBuilder()
    name.forEachIndexed { index, ch ->
      when {
        ch.isLetterOrDigit() -> {
          val previous = name.getOrNull(index - 1)
          val next = name.getOrNull(index + 1)
          val startsWord =
            previous != null &&
              ch.isUpperCase() &&
              (previous.isLowerCase() ||
                previous.isDigit() ||
                (previous.isUpperCase() && next?.isLowerCase() == true))
          if (startsWord && out.isNotEmpty() && out.last() != '-') out.append('-')
          out.append(ch.lowercaseChar())
        }
        out.isNotEmpty() && out.last() != '-' -> out.append('-')
      }
    }
    return out.toString().trim('-')
  }

  /**
   * The bare classifier of a rendered type: `kotlin.Boolean?` → `Boolean`, matching how
   * [STATE_TYPE_CLASSIFIERS] is keyed.
   */
  internal fun classifierOf(type: String): String =
    type.trim().removeSuffix("?").substringAfterLast('.')

  /**
   * The arguments of a rendered `(X) -> R`, or null for every other shape. Deliberately narrow:
   * receivers, nested function types and typealiases return null rather than risk a false mismatch.
   */
  internal fun functionInputs(type: String): List<String>? {
    val trimmed = type.trim()
    if (!trimmed.startsWith("(")) return null
    var depth = 0
    var close = -1
    for ((index, ch) in trimmed.withIndex()) {
      when (ch) {
        '(' -> depth++
        ')' -> {
          depth--
          if (depth == 0) {
            close = index
            break
          }
        }
      }
    }
    if (close < 0) return null
    if (!trimmed.substring(close + 1).trimStart().startsWith("->")) return null
    val inside = trimmed.substring(1, close).trim()
    if (inside.isEmpty()) return emptyList()
    val arguments = inside.split(',').map { it.trim() }
    // Nested function types and generics are past what a rendering can settle.
    if (arguments.any { it.isEmpty() || it.contains("->") || it.contains('<') }) return null
    return arguments
  }

  /** The single argument of a rendered `(X) -> R`, or null for every other shape. */
  internal fun soleFunctionInput(type: String): String? = functionInputs(type)?.singleOrNull()

  private fun policyFor(component: ComponentRecord, builder: BuilderPolicy) =
    UiBuilderComponentPolicy.Builder(record = component.canonicalId)
      .also { b ->
        // The declaring sticker's alias, matching the builder id derived from it.
        b.catalogId = builder.declaredForCatalogId ?: component.componentIds.firstOrNull()
        b.displayName = builder.displayName
        b.canvas = builder.canvas
        b.canvasMapping = null
        b.nativeOnly = builder.nativeOnly
        b.traits = builder.traits
        b.slots =
          builder.slots.associate { pair ->
            pair.key to pair.value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
          }
        b.stateCallbacks = builder.stateCallbacks.associate { it.key to it.value }
        b.starter = builder.starter.associate { it.key to it.value }
        b.variantProperty = builder.variantProperty
        b.variants = builder.variants.associate { it.key to it.value }
        b.excluded = builder.exclude
      }
      .build()

  /**
   * What is worth saying about one component in the published file: each is a catalog claim the
   * record can check, which would otherwise surface only as a silently broken export.
   */
  private fun diagnose(
    component: ComponentRecord,
    builder: BuilderPolicy,
    resolved: UiBuilderComponentPolicy,
    builderId: String,
    into: MutableList<UiBuilderDiagnostic>,
  ) {
    if (builder.conflicting.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_CONFLICT,
            subject = builderId,
            message =
              "several previews declare a different @BuilderComponent for ${component.canonicalId}: " +
                "${builder.declaredBy.joinToString()} won, ${builder.conflicting.joinToString()} " +
                "was dropped. The resolution is by preview id and is arbitrary; make them agree.",
          )
          .build()
    }
    if (builder.ambiguousWith.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_AMBIGUOUS_SUBJECT,
            subject = builderId,
            message =
              "the sticker renders ${builder.ambiguousWith.size + 1} components and the annotation " +
                "names none of them, so the policy was bound to ${component.canonicalId} rather " +
                "than to ${builder.ambiguousWith.joinToString()}. That is a guess: name the subject " +
                "with @BuilderComponent(component = \"…\").",
          )
          .build()
    }
    for (entry in builder.malformed) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.POLICY_MALFORMED_ENTRY,
            subject = builderId,
            message =
              "@BuilderComponent carries `$entry`, which is not a `key=value` entry and was " +
                "dropped. The component keeps the default this entry meant to change.",
          )
          .build()
    }
    resolved.excluded?.let { reason ->
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.COMPONENT_EXCLUDED,
            subject = builderId,
            message = "kept off the builder's shelf: $reason",
          )
          .build()
    }
    // An excluded component is never drawn, so it needs no canvas adapter.
    if (resolved.excluded.isNullOrBlank() && resolved.canvas.isNullOrBlank()) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.CANVAS_UNCLAIMED,
            subject = builderId,
            message =
              "no canvas adapter claimed, so it draws as a placeholder. That is the honest default " +
                "and needs no fixing; it is reported so a shelf drawn entirely in placeholders is " +
                "visible rather than mysterious.",
          )
          .build()
    }
    // Callback syntax (`<state>:<type>`) needs no signature, so check it before the
    // `signatureKnown` guard.
    for (pair in builder.stateCallbacks) {
      val rawState = pair.value.substringBefore(':').trim()
      val rawType = pair.value.substringAfter(':', "").trim()
      if (rawState.isEmpty() || !pair.value.contains(':') || rawType !in STATE_TYPES) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_MALFORMED,
              subject = "$builderId.${pair.key}",
              message =
                "'${pair.value}' is not a non-empty '<state>:<type>' with a type from " +
                  "${STATE_TYPES.sorted().joinToString()}. The export prints the hoisted state's " +
                  "initial value from that type, so it cannot complete the hoist without one.",
            )
            .build()
      }
    }
    // Variants with no `variantProperty`: the control would move but the generated call wouldn't.
    if (builder.variants.isNotEmpty() && builder.variantProperty?.isNotBlank() != true) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.VARIANTS_WITHOUT_PROPERTY,
            subject = builderId,
            message =
              "declares ${builder.variants.size} variant(s) but no variantProperty, so nothing " +
                "receives the selected value and every variant is inert.",
          )
          .build()
    }

    // A key named twice in any list that `policyFor` collapses into a map (`associate` keeps the
    // last silently). All four lists, since they are collapsed together.
    for ((label, pairs) in
      listOf(
        "stateCallbacks" to builder.stateCallbacks,
        "starter" to builder.starter,
        "slots" to builder.slots,
        "variants" to builder.variants,
      )) {
      for ((key, entries) in pairs.groupBy { it.key }.filterValues { it.size > 1 }) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.POLICY_MALFORMED_ENTRY,
              subject = "$builderId.$key",
              message =
                "'$label' names '$key' ${entries.size} times " +
                  "(${entries.joinToString { it.value }}). Only the last survives being read into a " +
                  "map, so the others do nothing and the one that wins is whichever was written " +
                  "last — say it once.",
            )
            .build()
      }
    }

    // The claims below need a signature that was actually read; otherwise every entry looks wrong.
    if (!component.signatureKnown) return
    val parameterNames = component.parameters.map { it.name }.toSet()
    val parametersByName = component.parameters.associateBy { it.name }
    for (pair in builder.stateCallbacks) {
      // The callback must be a parameter as well as the state.
      if (pair.key !in parameterNames) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_NOT_A_PARAMETER,
              subject = "$builderId.${pair.key}",
              message =
                "'${pair.key}' is not a parameter of ${component.canonicalId}, so nothing hoists " +
                  "against it and the component exports as a picture of itself.",
            )
            .build()
      }
      // The callback must be function-typed, or the export would emit a lambda where e.g. a String
      // goes.
      val target = parametersByName[pair.key]
      if (target != null && "->" !in target.type) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_NOT_A_FUNCTION,
              subject = "$builderId.${pair.key}",
              message =
                "'${pair.key}' is a parameter of ${component.canonicalId} but its type is " +
                  "'${target.type}', which is not function-typed, so the export would emit a lambda " +
                  "where the component wants a value. (A typealias for a function type renders " +
                  "under its own name and will report here too; the type above is what the record " +
                  "holds.)",
            )
            .build()
      }
      val state = pair.value.substringBefore(':').trim()
      // The declared JSON type must match the state parameter, or the export won't compile.
      val declaredType = pair.value.substringAfter(':', "").trim()
      val stateParam = parametersByName[state]
      // Compare the simple name: records hold `kotlin.Boolean`, the table is keyed on `Boolean`.
      val classifier = stateParam?.type?.let(::classifierOf)
      val expected = STATE_TYPE_CLASSIFIERS[declaredType]
      val declaredTypeWrong = classifier != null && expected != null && classifier !in expected
      if (declaredTypeWrong) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_TYPE_MISMATCH,
              subject = "$builderId.${pair.key}",
              message =
                "declares state '$state' as '$declaredType', but ${component.canonicalId} takes it " +
                  "as '${stateParam.type}'. The export would initialise a $declaredType and thread " +
                  "it into a ${stateParam.type}, which does not compile. " +
                  "'$declaredType' means ${expected.sorted().joinToString(" or ")}.",
            )
            .build()
      }
      // The callback's own input must match the state type too. Only the `(X) -> R` shape is read;
      // other renderings are left alone.
      val callbackInputType = target?.type?.let(::soleFunctionInput)
      val callbackInput = callbackInputType?.let(::classifierOf)
      // Arity: the export writes `{ checked = it }`, which needs exactly one argument.
      val callbackInputs = target?.type?.let(::functionInputs)
      if (callbackInputs != null && callbackInputs.size != 1) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_ARITY,
              subject = "$builderId.${pair.key}",
              message =
                "'${pair.key}' takes ${callbackInputs.size} argument(s) ('${target.type}'), and the " +
                  "export writes it as `{ $state = it }`, which needs exactly one. A callback that " +
                  "fires without carrying the new value cannot update '$state'.",
            )
            .build()
      }
      // Nullability in the direction the export assigns: a `(Boolean?) -> Unit` over a `Boolean`
      // state doesn't compile; the reverse is fine.
      val nullableIntoNonNull =
        callbackInputType?.trim()?.endsWith("?") == true &&
          stateParam?.type?.trim()?.endsWith("?") == false
      // One entry, one diagnostic: skip when the branch above already fired.
      if (
        !declaredTypeWrong &&
          callbackInput != null &&
          classifier != null &&
          (callbackInput != classifier || nullableIntoNonNull)
      ) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_TYPE_MISMATCH,
              subject = "$builderId.${pair.key}",
              message =
                "state '$state' is a ${stateParam?.type}, but '${pair.key}' takes " +
                  "'${target.type}'. The export writes the callback's argument back into the hoisted " +
                  "state, so the two have to agree; one of the component's two parameters is not " +
                  "the one this entry means.",
            )
            .build()
      }
      if (state.isNotEmpty() && state !in parameterNames) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STATE_CALLBACK_UNKNOWN,
              subject = "$builderId.${pair.key}",
              message =
                "declares state '$state', which is not a parameter of ${component.canonicalId}. The " +
                  "export cannot thread a state the component does not take, so this entry does " +
                  "nothing.",
            )
            .build()
      }
    }
    // A starter value is printed as a named argument, so a misspelled key wouldn't compile or would
    // be dropped.
    for (pair in builder.starter) {
      if (pair.key !in parameterNames) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.STARTER_UNKNOWN_PARAMETER,
              subject = "$builderId.${pair.key}",
              message =
                "starter names '${pair.key}', which is not a parameter of " +
                  "${component.canonicalId}, so the value is either dropped or printed as a named " +
                  "argument that does not compile.",
            )
            .build()
      }
    }
    // A misspelled variant property would render a control the export can't honour.
    val variantProperty = builder.variantProperty?.takeIf { it.isNotBlank() }
    if (variantProperty != null && variantProperty !in parameterNames) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.VARIANT_PROPERTY_UNKNOWN,
            subject = "$builderId.$variantProperty",
            message =
              "variantProperty is '$variantProperty', which is not a parameter of " +
                "${component.canonicalId}, so every variant it offers writes to nothing.",
          )
          .build()
    }
    val slotNames = component.slots.map { it.name }.toSet()
    for (pair in builder.slots) {
      if (pair.key !in slotNames) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.SLOT_UNKNOWN,
              subject = "$builderId.${pair.key}",
              message =
                "declares slot policy for '${pair.key}', which ${component.canonicalId} does not " +
                  "have. Either a rename, or a `@Composable` lambda discovery could not recover.",
            )
            .build()
      }
    }
  }

  private fun validateCode(policy: UiBuilderPolicyFile, into: MutableList<UiBuilderDiagnostic>) {
    val code = policy.code ?: return
    for (role in code.templates.keys) {
      // `previews` and `file` are whole-file templates, not node roles.
      if (role in UI_BUILDER_STRUCTURAL_ROLES || role == "previews" || role == "file") continue
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.TEMPLATE_ROLE_UNKNOWN,
            subject = role,
            message =
              "no template engine role named '$role'. Known roles: " +
                "${UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString()}, plus 'previews' and 'file'. " +
                "A build that does not know a role refuses that export, not the catalog.",
          )
          .build()
    }
    // Check template holes, so a typo is reported to the policy author rather than at export.
    for ((role, template) in code.templates) {
      when (val holes = StructuralTemplate.holes(template)) {
        is StructuralTemplate.Result2.Failed ->
          into +=
            UiBuilderDiagnostic.Builder(
                code = Diagnostics.TEMPLATE_MALFORMED,
                subject = role,
                message =
                  "the template cannot be read: ${holes.reasons.joinToString("; ")}. A template is " +
                    "${'$'}{name} substitution and ${'$'}{call(...)} call sites, and nothing else.",
              )
              .build()
        is StructuralTemplate.Result2.Ok -> {
          // A misspelled hole is a valid name, so check it against the names this role will have
          // values for (which is why UI_BUILDER_TEMPLATE_HOLES is a contract).
          val known = UI_BUILDER_TEMPLATE_HOLES[role].orEmpty()
          val unknown =
            holes.value
              .filterIsInstance<StructuralTemplate.Hole.Named>()
              .map { it.name }
              .filterNot { it in known }
          for (name in unknown.distinct()) {
            into +=
              UiBuilderDiagnostic.Builder(
                  code = Diagnostics.TEMPLATE_HOLE_UNKNOWN,
                  subject = "$role.$name",
                  message =
                    "no value is supplied for ${'$'}{$name} in a `$role` template, so an export " +
                      "through it is refused. Holes this role supplies: " +
                      "${known.sorted().joinToString()}.",
                )
                .build()
          }
        }
      }
    }
    // Assert the strategy is one of the two schema words first: a misspelled strategy with no
    // templates would satisfy both comparisons below.
    if (code.strategy !in UI_BUILDER_CODE_STRATEGIES) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.STRATEGY_UNKNOWN,
            subject = "code.strategy",
            message =
              "code.strategy is '${code.strategy}', which no exporter implements. It is " +
                UI_BUILDER_CODE_STRATEGIES.sorted().joinToString(" or ") { "'$it'" } +
                ".",
          )
          .build()
    }
    if (code.strategy == "templates" && code.templates.isEmpty()) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.STRATEGY_WITHOUT_TEMPLATES,
            subject = "code.strategy",
            message =
              "code.strategy is 'templates' but no templates are declared, so every node falls back " +
                "to a record call site — which is what 'record' means.",
          )
          .build()
    }
    if (code.strategy != "templates" && code.templates.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic.Builder(
            code = Diagnostics.TEMPLATES_WITHOUT_STRATEGY,
            subject = "code.templates",
            message =
              "templates are declared but code.strategy is '${code.strategy}', so none of them is " +
                "read. Set code.strategy to 'templates'.",
          )
          .build()
    }
  }

  private fun validateBuiltins(
    policy: UiBuilderPolicyFile,
    record: ComponentRecordFile,
    idPrefix: String,
    into: MutableList<UiBuilderDiagnostic>,
  ) {
    // Every admitted record component, annotated or not: unannotated ones are shelved under derived
    // ids.
    val recordIds =
      record.components
        .map { component ->
          builderIdFor(idPrefix, component, component.builder ?: BuilderPolicy.Builder().build())
        }
        .toSet()
    for ((id, builtin) in policy.builtins) {
      if (builtin.role !in UI_BUILDER_STRUCTURAL_ROLES) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.BUILTIN_ROLE_UNKNOWN,
              subject = id,
              message =
                "role '${builtin.role}' is not one the template engine knows. Known roles: " +
                  UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString(),
            )
            .build()
      }
      // A shelf role outside `Scaffold` / `Container` / `Leaf` files the component nowhere. Null
      // asks for the consumer's derivation.
      if (builtin.shelfRole != null && builtin.shelfRole !in UI_BUILDER_SHELF_ROLES) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.BUILTIN_SHELF_ROLE_UNKNOWN,
              subject = id,
              message =
                "shelfRole '${builtin.shelfRole}' is not a shelf role. It is one of " +
                  UI_BUILDER_SHELF_ROLES.sorted().joinToString() +
                  ", and it is not the structural `role` beside it — that one says which template " +
                  "writes this component.",
            )
            .build()
      }
      val adapterStatus = builtin.wasm?.adapterStatus
      if (adapterStatus != null && adapterStatus !in UI_BUILDER_WASM_ADAPTER_STATUSES) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.BUILTIN_WASM_STATUS_UNKNOWN,
              subject = id,
              message =
                "wasm.adapterStatus is '$adapterStatus', which no consumer decodes. It is " +
                  UI_BUILDER_WASM_ADAPTER_STATUSES.sorted().joinToString() +
                  ". A status the consumer cannot read fails the whole capability document, not " +
                  "one field.",
            )
            .build()
      }
      // A blank `code.symbol` publishes an export that calls nothing while telling consumers not to
      // draw a placeholder. Reported, not refused: the packaged vocabulary does this for
      // `layout/for-each`.
      if (builtin.code?.symbol?.isBlank() == true) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.BUILTIN_CODE_EMPTY,
              subject = id,
              message =
                "declares a `code` block with no symbol, so an export through it writes a call to " +
                  "nothing. State the callable, or omit the block and keep the placeholder.",
            )
            .build()
      }
      // A slot's role selects a template like a builtin's does, so check it here too (not only in
      // the workflow pre-flight). Unreadable slot shapes are left to the loader.
      for ((slot, spec) in builtin.slots) {
        val role = ((spec as? JsonObject)?.get("role") as? JsonPrimitive)?.takeIf { it.isString }
        val name = role?.content ?: continue
        if (name !in UI_BUILDER_STRUCTURAL_ROLES) {
          into +=
            UiBuilderDiagnostic.Builder(
                code = Diagnostics.BUILTIN_SLOT_ROLE_UNKNOWN,
                subject = "$id/$slot",
                message =
                  "slot role '$name' is not one the template engine knows, so the slot selects no " +
                    "template. Known roles: " +
                    UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString(),
              )
              .build()
        }
      }
      if (id in recordIds) {
        into +=
          UiBuilderDiagnostic.Builder(
              code = Diagnostics.BUILTIN_SHADOWS_RECORD,
              subject = id,
              message =
                "declared as a builtin, but a record component already publishes under this id. A " +
                  "builtin is for a component with no call site; this one has one, so put its " +
                  "policy on the sticker with @BuilderComponent instead.",
            )
            .build()
      }
    }
  }

  private fun titleCase(word: String): String =
    word
      .split('-', '_')
      .filter { it.isNotEmpty() }
      .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}

/** The chooser copy [policy] authors, or null when it authors none. */
internal fun newDesignSemantics(policy: UiBuilderPolicyFile): UiBuilderNewDesignSemantics? {
  val described = policy.templates.any { it.describesItself }
  if (policy.newDesign == null && !described) return null
  return UiBuilderNewDesignSemantics.Builder()
    .also { builder ->
      builder.label = policy.newDesign?.label
      builder.order = policy.newDesign?.order
      builder.templates =
        policy.templates.map {
          UiBuilderNewDesignTemplateSemantics.Builder(id = it.resolvedId, path = it.path)
            .also { b ->
              b.label = it.label
              b.supportingText = it.supportingText
              b.group = it.group
              b.default = it.default
              b.order = it.order
            }
            .build()
        }
    }
    .build()
}
