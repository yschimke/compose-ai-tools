package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The `schema` a generated `ui-builder.json` carries. */
const val UI_BUILDER_CATALOG_SCHEMA: String = "compose-ui-builder-catalog/v1"

/** The record file a generated builder catalog is paired with, on the branch and in `build/`. */
const val UI_BUILDER_RECORD_FILE: String = "components.json"

/**
 * `ui-builder.json` — the builder catalog a repository publishes, generated and never edited.
 *
 * Produced by [UiBuilderCatalogs.generate] from three inputs the catalog repository owns: the
 * discovered component record, the `catalog.spec.json` cover sheet, and the authored
 * [UiBuilderPolicyFile]. Written by the discovery task into `build/compose-previews/` beside
 * `components.json`, and copied by the design-artifacts pipeline to the delivery branch root where
 * `catalog.json` names it as `uiBuilderFile`.
 *
 * ### It is policy, and the record beside it is the inventory
 *
 * This file deliberately does **not** restate the components. Every parameter, slot, call site and
 * opt-in marker is already in `components.json`, which travels with it, is published by the same
 * run and is pinned by the same revision; a consumer reads the two together, joining on
 * [UiBuilderComponentPolicy.record]. Deriving a second, fuller component list here would put a
 * second implementation of the derivation rules into the pipeline while the first is still running
 * in the preview server — and two implementations of a rule this exacting is how the two sides of a
 * contract come to disagree.
 *
 * That the derivation eventually moves upstream is the plan
 * ([UI_BUILDER_CATALOG_CONTRACT.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md));
 * moving it *before* the server reads a published file at all would be a rewrite with nothing to
 * check it against. Publishing policy first is the step that can be proved equivalent, because the
 * server composes it with the same record it already derives from today.
 *
 * ### Every reader may ignore what it does not know
 *
 * The file is published once and read by builders of several vintages that the publisher cannot
 * upgrade — a deployment, a `serve` on a laptop, a local `compose-preview-server ui`, an editor
 * extension reading through one of those. [schema] refuses a future *major*; an unknown field never
 * fails a load, and an adapter or template role a build does not ship costs a placeholder and a log
 * line rather than the catalog.
 */
@Serializable
data class UiBuilderCatalogFile(
  val schema: String = UI_BUILDER_CATALOG_SCHEMA,
  val catalog: UiBuilderCatalogIdentity,
  /** Which record this file was generated against, so a consumer can tell they are a pair. */
  val record: UiBuilderRecordRef,
  /**
   * Everything a builder reads, in the one place every existing reader already looks.
   *
   * Until compose-preview-contracts can carry typed fields on `CatalogCapabilityV1`, these ride in
   * `statusSemantics` exactly as `platform`, `previewSurfaces` and `componentMenu` already do.
   * Making them typed is the right change and is sequenced separately; it is not a prerequisite,
   * because every reader reads `statusSemantics` today.
   */
  val statusSemantics: UiBuilderStatusSemantics,
  /**
   * What the generator noticed, in a stable, machine-readable vocabulary.
   *
   * Published in the file rather than only logged. A catalog's shelf is drawn from data now, and
   * the two questions somebody asks of it — "why is this component not on the shelf" and "why is
   * everything a placeholder" — have to be answerable from the artifact, by a person who was not
   * watching the build that produced it.
   */
  val diagnostics: List<UiBuilderDiagnostic> = emptyList(),
)

/** Who this catalog is, in the vocabulary the chooser and the pack merge read. */
@Serializable
data class UiBuilderCatalogIdentity(
  val id: String,
  val title: String,
  val platform: String,
  val platformLabel: String,
  /**
   * The Gradle module the record was discovered from, and its variant. Diagnostic, not identity.
   */
  val module: String? = null,
  val variant: String? = null,
)

/** The component record this file is the policy half of. */
@Serializable
data class UiBuilderRecordRef(
  val file: String = UI_BUILDER_RECORD_FILE,
  val schemaVersion: Int,
  val components: Int,
)

/** Everything a builder reads about the catalog, carried where every reader already looks. */
@Serializable
data class UiBuilderStatusSemantics(
  val platform: String,
  val platformLabel: String,
  /**
   * The resolved prefix every derived builder id carries — `componentIdPrefix`, or `<catalogId>/`.
   *
   * Published because it is the only way a consumer can name a component this file says nothing
   * about. An unannotated record component is deliberately absent from [components] and still
   * belongs on the shelf, so its id has to be DERIVABLE: without this a consumer holding
   * m3-catalog's record has to guess between `m3/card` and `m3-catalog/card`, and guessing wrong
   * changes the identity every saved design stores for most of the default shelf.
   */
  val componentIdPrefix: String,
  val previewSurfaces: JsonElement? = null,
  val componentMenu: UiBuilderComponentMenu,
  val frame: JsonElement? = null,
  val code: UiBuilderCode? = null,
  val templates: List<String> = emptyList(),
  val colorTokens: JsonElement? = null,
  val assetRegistry: JsonElement? = null,
  val builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
  /** Per-component policy, keyed by builder id. The record beside this file is the inventory. */
  val components: Map<String, UiBuilderComponentPolicy> = emptyMap(),
)

/** Group order, and the group each policy-carrying component belongs to. */
@Serializable
data class UiBuilderComponentMenu(
  val groupOrder: List<String> = emptyList(),
  val components: Map<String, UiBuilderMenuEntry> = emptyMap(),
)

@Serializable data class UiBuilderMenuEntry(val group: String)

/**
 * One component's builder policy as published: [BuilderPolicy], resolved, plus the join back to the
 * record.
 *
 * [record] is the load-bearing field. It is the record's `canonicalId`, so a consumer holding
 * `components.json` and this file can pair a builder id with the signature, the slots and the call
 * site it stands for — without either file restating the other.
 */
@Serializable
data class UiBuilderComponentPolicy(
  /** The record's `canonicalId` — `<module>/<jvmOwner>.<name>`. */
  val record: String,
  /** The catalog identity this component publishes under, when it has one. */
  val catalogId: String? = null,
  val displayName: String? = null,
  val canvas: String? = null,
  val nativeOnly: Boolean = false,
  val traits: List<String> = emptyList(),
  val slots: Map<String, List<String>> = emptyMap(),
  val stateCallbacks: Map<String, String> = emptyMap(),
  val starter: Map<String, String> = emptyMap(),
  val variantProperty: String? = null,
  val variants: Map<String, String> = emptyMap(),
  /** Present only when the component is kept off the shelf; the value is the stated reason. */
  val excluded: String? = null,
)

/**
 * Something the generator noticed, addressed to a person reading the published file.
 *
 * [code] is a stable slug so a gate can assert on it; [subject] is the component, builtin or field
 * it is about; [message] is for the person. Never a build failure on its own — a catalog that is
 * half-annotated is a catalog in progress, and refusing to publish it would leave the author with
 * nothing to look at.
 */
@Serializable
data class UiBuilderDiagnostic(val code: String, val subject: String, val message: String)

/**
 * Generates a [UiBuilderCatalogFile] from the discovered record, the cover sheet and the authored
 * policy.
 *
 * Pure, and deliberately: everything it needs is already in its arguments, so it runs in the Gradle
 * discovery task, in a test, and — since it lives in the shared source `:screen-model` also
 * compiles — in the browser, without a second implementation anywhere.
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
    const val ID_COLLISION = "component.id.collision"
    const val BUILTIN_SHADOWS_RECORD = "policy.builtin.shadowsRecord"
  }

  /**
   * The cover-sheet fields a builder catalog needs, and no more.
   *
   * `catalog.spec.json` has a large schema owned by the design-artifacts pipeline; parsing all of
   * it here would make this generator a second reader of a contract it does not own, breaking on
   * additions it never looks at.
   */
  data class CoverSheet(val system: String, val title: String)

  /**
   * Generate the builder catalog, or return `null` when [policy] is absent — a catalog that authors
   * no policy publishes no builder file, which is what makes this contract cost nothing for the
   * catalogs that have not adopted it.
   */
  fun generate(
    record: ComponentRecordFile,
    cover: CoverSheet,
    policy: UiBuilderPolicyFile?,
  ): UiBuilderCatalogFile? {
    if (policy == null) return null
    val diagnostics = mutableListOf<UiBuilderDiagnostic>()
    if (policy.schema != UI_BUILDER_POLICY_SCHEMA) {
      diagnostics +=
        UiBuilderDiagnostic(
          code = Diagnostics.POLICY_SCHEMA_UNKNOWN,
          subject = policy.schema,
          message =
            "ui-builder.policy.json declares schema '${policy.schema}'; this generator writes " +
              "$UI_BUILDER_POLICY_SCHEMA. Generated anyway — refusing would leave the author with " +
              "nothing to look at — but read the result against the schema it was written for.",
        )
    }
    val catalogId = policy.catalogId?.takeIf { it.isNotBlank() } ?: cover.system
    val idPrefix = policy.componentIdPrefix?.takeIf { it.isNotBlank() } ?: "$catalogId/"
    val platformLabel =
      policy.platformLabel?.takeIf { it.isNotBlank() } ?: titleCase(policy.platform)

    validateCode(policy, diagnostics)
    validateBuiltins(policy, record, idPrefix, diagnostics)
    for (orphan in record.builderOrphans) {
      diagnostics +=
        UiBuilderDiagnostic(
          code = Diagnostics.POLICY_ORPHANED,
          subject = orphan.previewId,
          message =
            "@BuilderComponent(component = \"${orphan.component}\") names nothing ${orphan.previewId} " +
              "renders, so its policy was attached to no component and every field in it does " +
              "nothing. That preview renders: " +
              (orphan.candidates.takeIf { it.isNotEmpty() }?.joinToString() ?: "no components"),
        )
    }

    val components = linkedMapOf<String, UiBuilderComponentPolicy>()
    val menuEntries = linkedMapOf<String, UiBuilderMenuEntry>()
    // Which record each derived id belongs to, over EVERY admitted component rather than only the
    // annotated ones. An unannotated component is still shelved by the consumer, which derives its
    // id from `componentIdPrefix` exactly as this does — so two of them colliding, or one colliding
    // with an annotated component's explicit id, is two records claiming one saved-design identity.
    // Skipping the unannotated ones here made the check blind to the majority of the shelf, which
    // is the same mistake the builtin check had and was fixed for one commit earlier.
    val idOwners = linkedMapOf<String, String>()
    for (component in record.components) {
      val builderId = builderIdFor(idPrefix, component, component.builder ?: BuilderPolicy())
      val owner = idOwners[builderId]
      if (owner == null) {
        idOwners[builderId] = component.canonicalId
        continue
      }
      diagnostics +=
        UiBuilderDiagnostic(
          code = Diagnostics.ID_COLLISION,
          subject = builderId,
          message =
            "'$builderId' is claimed by both $owner and ${component.canonicalId}. The first wins; " +
              "give one of them an explicit @BuilderComponent(id = …), because a saved design " +
              "stores this string and cannot be told which component it meant.",
        )
    }
    for (component in record.components) {
      val builder = component.builder ?: continue
      val builderId = builderIdFor(idPrefix, component, builder)
      val existing = components[builderId]
      // Reported by the sweep above, which sees the unannotated components too. Still skipped here
      // so the first claimant keeps the entry.
      if (existing != null) continue
      diagnose(component, builder, builderId, diagnostics)
      components[builderId] = policyFor(component, builder)
      val group = builder.group ?: continue
      menuEntries[builderId] = UiBuilderMenuEntry(group)
    }

    return UiBuilderCatalogFile(
      catalog =
        UiBuilderCatalogIdentity(
          id = catalogId,
          title = cover.title,
          platform = policy.platform,
          platformLabel = platformLabel,
          module = record.module,
          variant = record.variant,
        ),
      record =
        UiBuilderRecordRef(
          schemaVersion = record.schemaVersion,
          components = record.components.size,
        ),
      statusSemantics =
        UiBuilderStatusSemantics(
          platform = policy.platform,
          platformLabel = platformLabel,
          componentIdPrefix = idPrefix,
          previewSurfaces = policy.previewSurfaces,
          componentMenu =
            UiBuilderComponentMenu(
              groupOrder = policy.menu?.groupOrder.orEmpty(),
              components = menuEntries,
            ),
          frame = policy.frame,
          code = policy.code,
          templates = policy.templates,
          colorTokens = policy.colorTokens,
          assetRegistry = policy.assetRegistry,
          builtins = policy.builtins,
          components = components,
        ),
      diagnostics = diagnostics,
    )
  }

  /**
   * The builder id for a record component: the annotation's, else [prefix] plus a slug of the
   * catalog identity's last segment, else of the symbol's own name.
   *
   * Derived rather than required so the common case costs nothing, and overridable because a
   * published design stores this string: a component renamed in the catalog can keep the id designs
   * already reference.
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
    // The DECLARING sticker's catalog id, not the record's first alias. One callable is routinely
    // published under several — `Button/Filled` and `Button/Tonal` over one `Button` — and
    // `componentIds` is the sorted union across every preview, so the first of it can belong to a
    // different sticker than the one that declared this policy. The id a saved design stores must
    // come from the sticker whose author chose it.
    val leaf =
      (builder.declaredForCatalogId ?: component.componentIds.firstOrNull())
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() } ?: component.symbol.name
    return "$prefix${slug(leaf)}"
  }

  /**
   * `CheckboxButton` → `checkbox-button`, `TopAppBar` → `top-app-bar`, `Button2` → `button2`.
   *
   * Splits on a lower-to-upper boundary and on any run of non-alphanumerics. A run of capitals is
   * one word (`RTLText` → `rtl-text`), because splitting it letter by letter produces ids nobody
   * would type.
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

  private fun policyFor(component: ComponentRecord, builder: BuilderPolicy) =
    UiBuilderComponentPolicy(
      record = component.canonicalId,
      // The DECLARING sticker's alias, matching the builder id derived from it. Publishing the
      // sorted record's first alias instead would have this entry contradict its own id — keyed
      // `…/tonal` while linking a consumer to `Buttons/Filled` — and a consumer following it lands
      // on a different sticker than the one whose author wrote this policy.
      catalogId = builder.declaredForCatalogId ?: component.componentIds.firstOrNull(),
      displayName = builder.displayName,
      canvas = builder.canvas,
      nativeOnly = builder.nativeOnly,
      traits = builder.traits,
      slots =
        builder.slots.associate { pair ->
          pair.key to pair.value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        },
      stateCallbacks = builder.stateCallbacks.associate { it.key to it.value },
      starter = builder.starter.associate { it.key to it.value },
      variantProperty = builder.variantProperty,
      variants = builder.variants.associate { it.key to it.value },
      excluded = builder.exclude,
    )

  /**
   * What is worth saying about one component, in the published file.
   *
   * Each of these is a claim the catalog made that the record can check, and checking is the whole
   * argument for policy-as-data over an emitter jar: a misspelt parameter in a `stateCallbacks`
   * entry is otherwise invisible until an export silently stops hoisting a `remember` and somebody
   * ships a picture of a checkbox.
   */
  private fun diagnose(
    component: ComponentRecord,
    builder: BuilderPolicy,
    builderId: String,
    into: MutableList<UiBuilderDiagnostic>,
  ) {
    if (builder.conflicting.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.POLICY_CONFLICT,
          subject = builderId,
          message =
            "several previews declare a different @BuilderComponent for ${component.canonicalId}: " +
              "${builder.declaredBy.joinToString()} won, ${builder.conflicting.joinToString()} " +
              "was dropped. The resolution is by preview id and is arbitrary; make them agree.",
        )
    }
    if (builder.ambiguousWith.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.POLICY_AMBIGUOUS_SUBJECT,
          subject = builderId,
          message =
            "the sticker renders ${builder.ambiguousWith.size + 1} components and the annotation " +
              "names none of them, so the policy was bound to ${component.canonicalId} rather " +
              "than to ${builder.ambiguousWith.joinToString()}. That is a guess: name the subject " +
              "with @BuilderComponent(component = \"…\").",
        )
    }
    for (entry in builder.malformed) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.POLICY_MALFORMED_ENTRY,
          subject = builderId,
          message =
            "@BuilderComponent carries `$entry`, which is not a `key=value` entry and was " +
              "dropped. The component keeps the default this entry meant to change.",
        )
    }
    builder.exclude?.let { reason ->
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.COMPONENT_EXCLUDED,
          subject = builderId,
          message = "kept off the builder's shelf: $reason",
        )
    }
    if (builder.canvas.isNullOrBlank()) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.CANVAS_UNCLAIMED,
          subject = builderId,
          message =
            "no canvas adapter claimed, so it draws as a placeholder. That is the honest default " +
              "and needs no fixing; it is reported so a shelf drawn entirely in placeholders is " +
              "visible rather than mysterious.",
        )
    }
    // The two claims the record can check. Only worth checking against a signature that was
    // actually read: an unrecovered one reports "no parameters", and every entry would look wrong.
    if (!component.signatureKnown) return
    val parameterNames = component.parameters.map { it.name }.toSet()
    for (pair in builder.stateCallbacks) {
      // The CALLBACK has to be a parameter as well as the state. A `onChekedChange` typo passes a
      // state-only check, publishes the misspelled key, and the export then has nothing to hoist
      // against — a component that draws, compiles and does not tick, with no diagnostic.
      if (pair.key !in parameterNames) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_NOT_A_PARAMETER,
            subject = "$builderId.${pair.key}",
            message =
              "'${pair.key}' is not a parameter of ${component.canonicalId}, so nothing hoists " +
                "against it and the component exports as a picture of itself.",
          )
      }
      // `<state>:<type>`, both halves. `onCheckedChange=checked` parses to a valid state name and
      // no type at all, and `checked:bool` to a type nothing knows — both passed, because only the
      // part before the colon was ever looked at. The type is what the export prints the initial
      // `remember` value from, so without it the component publishes a hoist nothing can complete.
      val rawState = pair.value.substringBefore(':').trim()
      val rawType = pair.value.substringAfter(':', "").trim()
      if (rawState.isEmpty() || !pair.value.contains(':') || rawType !in STATE_TYPES) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_MALFORMED,
            subject = "$builderId.${pair.key}",
            message =
              "'${pair.value}' is not a non-empty '<state>:<type>' with a type from " +
                "${STATE_TYPES.sorted().joinToString()}. The export prints the hoisted state's " +
                "initial value from that type, so it cannot complete the hoist without one.",
          )
      }
      val state = rawState
      if (state.isNotEmpty() && state !in parameterNames) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_UNKNOWN,
            subject = "$builderId.${pair.key}",
            message =
              "declares state '$state', which is not a parameter of ${component.canonicalId}. The " +
                "export cannot thread a state the component does not take, so this entry does " +
                "nothing.",
          )
      }
    }
    // A starter value is printed as a NAMED ARGUMENT at the call site, so a misspelled key is
    // either dropped by a lenient consumer or compiled into a call to a parameter that does not
    // exist. Same check as the callbacks above, for the same reason: the catalog said something
    // about this component that the component cannot honour, and only the record knows that.
    for (pair in builder.starter) {
      if (pair.key !in parameterNames) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STARTER_UNKNOWN_PARAMETER,
            subject = "$builderId.${pair.key}",
            message =
              "starter names '${pair.key}', which is not a parameter of " +
                "${component.canonicalId}, so the value is either dropped or printed as a named " +
                "argument that does not compile.",
          )
      }
    }
    // The promoted parameter a variant control writes to. A `styel` typo publishes a control the
    // builder renders and the export cannot honour — the variant switches in the panel and the
    // generated call never changes, which is the kind of wrong that looks like a builder bug.
    val variantProperty = builder.variantProperty?.takeIf { it.isNotBlank() }
    if (variantProperty != null && variantProperty !in parameterNames) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.VARIANT_PROPERTY_UNKNOWN,
          subject = "$builderId.$variantProperty",
          message =
            "variantProperty is '$variantProperty', which is not a parameter of " +
              "${component.canonicalId}, so every variant it offers writes to nothing.",
        )
    }
    val slotNames = component.slots.map { it.name }.toSet()
    for (pair in builder.slots) {
      if (pair.key !in slotNames) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.SLOT_UNKNOWN,
            subject = "$builderId.${pair.key}",
            message =
              "declares slot policy for '${pair.key}', which ${component.canonicalId} does not " +
                "have. Either a rename, or a `@Composable` lambda discovery could not recover.",
          )
      }
    }
  }

  private fun validateCode(policy: UiBuilderPolicyFile, into: MutableList<UiBuilderDiagnostic>) {
    val code = policy.code ?: return
    for (role in code.templates.keys) {
      // `previews` and `file` are whole-file templates rather than node roles, so they are not in
      // the structural set and are not an error.
      if (role in UI_BUILDER_STRUCTURAL_ROLES || role == "previews" || role == "file") continue
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.TEMPLATE_ROLE_UNKNOWN,
          subject = role,
          message =
            "no template engine role named '$role'. Known roles: " +
              "${UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString()}, plus 'previews' and 'file'. " +
              "A build that does not know a role refuses that export, not the catalog.",
        )
    }
    // The templates are read as templates, not merely as strings. A `${'$'}{contnet}` that no
    // builder
    // will ever resolve is a message for the person editing this policy; without this it is a
    // refused export weeks later, for somebody who did not write it.
    for ((role, template) in code.templates) {
      when (val holes = StructuralTemplate.holes(template)) {
        is StructuralTemplate.Result2.Failed ->
          into +=
            UiBuilderDiagnostic(
              code = Diagnostics.TEMPLATE_MALFORMED,
              subject = role,
              message =
                "the template cannot be read: ${holes.reasons.joinToString("; ")}. A template is " +
                  "${'$'}{name} substitution and ${'$'}{call(...)} call sites, and nothing else.",
            )
        is StructuralTemplate.Result2.Ok -> {
          // A `${'$'}{contnet}` typo is a perfectly valid NAME, so nothing about the syntax catches
          // it.
          // What catches it is knowing which names this role will have values for — the reason
          // UI_BUILDER_TEMPLATE_HOLES is a contract rather than an implementation detail. Without
          // this the refusal arrives at export, weeks from the person who typed it, naming a hole
          // rather than the mistake.
          val known = UI_BUILDER_TEMPLATE_HOLES[role].orEmpty()
          val unknown =
            holes.value
              .filterIsInstance<StructuralTemplate.Hole.Named>()
              .map { it.name }
              .filterNot { it in known }
          for (name in unknown.distinct()) {
            into +=
              UiBuilderDiagnostic(
                code = Diagnostics.TEMPLATE_HOLE_UNKNOWN,
                subject = "$role.$name",
                message =
                  "no value is supplied for ${'$'}{$name} in a `$role` template, so an export " +
                    "through it is refused. Holes this role supplies: " +
                    "${known.sorted().joinToString()}.",
              )
          }
        }
      }
    }
    if (code.strategy == "templates" && code.templates.isEmpty()) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.STRATEGY_WITHOUT_TEMPLATES,
          subject = "code.strategy",
          message =
            "code.strategy is 'templates' but no templates are declared, so every node falls back " +
              "to a record call site — which is what 'record' means.",
        )
    }
    if (code.strategy != "templates" && code.templates.isNotEmpty()) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.TEMPLATES_WITHOUT_STRATEGY,
          subject = "code.templates",
          message =
            "templates are declared but code.strategy is '${code.strategy}', so none of them is " +
              "read. Set code.strategy to 'templates'.",
        )
    }
  }

  private fun validateBuiltins(
    policy: UiBuilderPolicyFile,
    record: ComponentRecordFile,
    idPrefix: String,
    into: MutableList<UiBuilderDiagnostic>,
  ) {
    // EVERY admitted record component, not only the annotated ones. A component with no
    // `@BuilderComponent` is still shelved under its derived id — that is the honest default the
    // whole contract rests on — so a builtin colliding with one is two components claiming one
    // saved-design identity, which is exactly what this check exists to catch.
    val recordIds =
      record.components
        .map { component ->
          builderIdFor(idPrefix, component, component.builder ?: BuilderPolicy())
        }
        .toSet()
    for ((id, builtin) in policy.builtins) {
      if (builtin.role !in UI_BUILDER_STRUCTURAL_ROLES) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.BUILTIN_ROLE_UNKNOWN,
            subject = id,
            message =
              "role '${builtin.role}' is not one the template engine knows. Known roles: " +
                UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString(),
          )
      }
      if (id in recordIds) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.BUILTIN_SHADOWS_RECORD,
            subject = id,
            message =
              "declared as a builtin, but a record component already publishes under this id. A " +
                "builtin is for a component with no call site; this one has one, so put its " +
                "policy on the sticker with @BuilderComponent instead.",
          )
      }
    }
  }

  private fun titleCase(word: String): String =
    word
      .split('-', '_')
      .filter { it.isNotEmpty() }
      .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
