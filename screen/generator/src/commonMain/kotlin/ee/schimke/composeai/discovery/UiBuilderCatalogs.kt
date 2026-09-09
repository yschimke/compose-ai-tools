package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    const val VARIANTS_WITHOUT_PROPERTY = "component.variants.withoutProperty"
    const val STATE_CALLBACK_NOT_A_FUNCTION = "component.stateCallback.notAFunction"
    const val STATE_CALLBACK_TYPE_MISMATCH = "component.stateCallback.typeMismatch"
    const val ID_COLLISION = "component.id.collision"
    const val ID_PREFIX_MALFORMED = "policy.componentIdPrefix.malformed"
    const val PLATFORM_MALFORMED = "policy.platform.malformed"
    const val STATE_CALLBACK_ARITY = "component.stateCallback.arity"
    const val BUILTIN_SHADOWS_RECORD = "policy.builtin.shadowsRecord"
    const val BUILTIN_SLOT_ROLE_UNKNOWN = "policy.builtin.slot.role.unknown"
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
    // The same shape the schema and the pre-flight require, checked HERE because this is the
    // authoritative generator and the other two do not run for every consumer. A local
    // `compose-preview-server ui` and a direct `bundle pack` never see the workflow's pre-flight,
    // so `componentIdPrefix: "m3"` was accepted and every derived id came out as `m3button` — a
    // string every saved design then stores, from a catalog that carried no diagnostic about it.
    // Reported rather than corrected: the prefix is the identity, and inventing the author's
    // missing slash would publish an id they did not write.
    //
    // The AUTHORED field only. The fallback is `<catalogId>/`, whose shape follows from the cover
    // sheet rather than from anything anybody wrote here, and pointing a diagnostic at a field the
    // author never set would send them looking for something that is not in their policy.
    // The platform word, checked here for the reason the prefix beside it is: equality IS
    // compatibility, so `Wear` never joins a consumer expecting `wear`, and the workflow pre-flight
    // that would have caught it does not run for a local `compose-preview-server ui` or a direct
    // `bundle pack`. Same rule as the schema's, stated where every consumer passes.
    if (!PLATFORM_WORD.matches(policy.platform)) {
      diagnostics +=
        UiBuilderDiagnostic(
          code = Diagnostics.PLATFORM_MALFORMED,
          subject = policy.platform,
          message =
            "'${policy.platform}' is the word catalogs are grouped by and equality is " +
              "compatibility, so it is a lower-case word (mobile, wear, remote-compose) rather " +
              "than a label. As written it joins no consumer expecting the lower-case form.",
        )
    }
    val authoredPrefix = policy.componentIdPrefix?.takeIf { it.isNotBlank() }
    if (authoredPrefix != null && !ID_PREFIX.matches(authoredPrefix)) {
      diagnostics +=
        UiBuilderDiagnostic(
          code = Diagnostics.ID_PREFIX_MALFORMED,
          subject = idPrefix,
          message =
            "'$idPrefix' prefixes every derived builder id and has to end in '/' " +
              "(lower-case letters, digits and hyphens, e.g. 'm3/'). As written it derives ids " +
              "like '${idPrefix}button', which is the string every saved design stores.",
        )
    }
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
      val builder = component.builder ?: BuilderPolicy()
      val builderId = builderIdFor(idPrefix, component, builder)
      // The owner the SWEEP established, not "the first annotated component to reach this loop".
      // Keying off `components` alone consulted a map only annotated components ever enter, so an
      // unannotated first claimant left it empty and the later annotated component published its
      // policy under the contested id — while the diagnostic said the first won and the menu, which
      // reads `idOwners`, agreed with the diagnostic. Three loops, two answers. They read one now.
      if (idOwners[builderId] != component.canonicalId) continue
      // Every admitted component, not only the annotated ones. `component.canvas.unclaimed` says in
      // its own message that it exists "so a shelf drawn entirely in placeholders is visible rather
      // than mysterious" — and a shelf drawn entirely in placeholders is the all-unannotated
      // catalog, which never reached this loop. The one diagnostic written for that case was the
      // one case it could not fire in.
      diagnose(component, builder, builderId, diagnostics)
      if (component.builder != null) components[builderId] = policyFor(component, builder)
    }

    // The shelf covers EVERY admitted component, so the menu has to as well.
    //
    // `builder.group` was the only source, which meant a menu entry existed solely for a component
    // whose annotation overrode its group — and unannotated components never reached this loop at
    // all. Every other component landed on the shelf with no group a consumer could recover, since
    // the record's binding did not carry one either. That is most of the default shelf for a
    // catalog that has adopted nothing yet, which is the case the contract is most careful to keep
    // working: "a catalog that annotates nothing still publishes every component, grouped by its
    // @CatalogGroup" was a claim with nothing behind it.
    //
    // The annotation is an override, which is what it was always documented as.
    for (component in record.components) {
      val builderId = builderIdFor(idPrefix, component, component.builder ?: BuilderPolicy())
      if (idOwners[builderId] != component.canonicalId) continue
      // The DECLARING sticker's group, matching the id and `catalogId` derived from the same
      // sticker. One callable is routinely published under several — `Button/Filled` and
      // `Button/Tonal` — and taking the first binding's group shelved a component whose id says
      // `…/tonal` under Filled's group, so the entry disagreed with its own identity. Falls back to
      // the first binding that names one, which is what an unannotated component has.
      // The alias the ID WAS DERIVED FROM, computed the same way `builderIdFor` computes it — the
      // declaring sticker when there is one, the first of the sorted `componentIds` when there is
      // not. I fixed this for the annotated branch and left the fallback taking the first BINDING's
      // group, which is preview-id order: an unannotated `Button` published as `Buttons/Filled` and
      // `Buttons/Tonal`, whose previews sort the other way round, was keyed `…/filled` and shelved
      // under Tonal's group. Same defect as the one above, in the branch I did not change.
      val idAlias = component.builder?.declaredForCatalogId ?: component.componentIds.firstOrNull()
      val group =
        component.builder?.group?.takeIf { it.isNotBlank() }
          ?: component.bindings
            .firstOrNull { it.componentId == idAlias && !it.group.isNullOrBlank() }
            ?.group
          ?: component.bindings.firstNotNullOfOrNull { it.group?.takeIf(String::isNotBlank) }
          ?: continue
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
  /** The shape a `componentIdPrefix` has to have, mirroring `ui-builder.policy.schema.json`. */
  private val ID_PREFIX = Regex("^[a-z0-9][a-z0-9-]*/$")

  /** And the platform word's, from the same schema. */
  private val PLATFORM_WORD = Regex("^[a-z0-9][a-z0-9-]*$")

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

  /**
   * The bare classifier of a rendered type: `kotlin.Boolean?` → `Boolean`.
   *
   * Both halves matter. Nullability is not a different classifier — `Boolean?` is still a boolean
   * state — and a package qualifier is not either, while [STATE_TYPE_CLASSIFIERS] is keyed on the
   * simple name a person writes.
   */
  internal fun classifierOf(type: String): String =
    type.trim().removeSuffix("?").substringAfterLast('.')

  /**
   * The single argument of a rendered `(X) -> R`, or null for every other shape.
   *
   * DELIBERATELY narrow. A rendered type is not a parse tree, and the shapes that would need one —
   * a receiver (`Foo.(Bar) -> Unit`), a nested function type, a typealias standing for one, more
   * than one argument — are returned as null rather than guessed at, because a wrong guess here
   * reports a mismatch against a component that is correct, and a diagnostic that cries wolf is
   * worse for the reader than the silence it replaced. What it does catch is the common form every
   * `on…Change` in a Material catalog is written in.
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
    // A nested function type or a generic argument is past what a rendering can settle, and a
    // generic's own comma would have been split by the line above.
    if (arguments.any { it.isEmpty() || it.contains("->") || it.contains('<') }) return null
    return arguments
  }

  /** The single argument of a rendered `(X) -> R`, or null for every other shape. */
  internal fun soleFunctionInput(type: String): String? = functionInputs(type)?.singleOrNull()

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
    // A callback entry's own SYNTAX needs no signature at all — `<state>:<type>` is wrong on its
    // face whatever the component turns out to take — so it is checked before the guard below.
    // Putting it behind `signatureKnown` meant a component whose metadata was not recovered
    // published `checked:bool` unremarked, which is the case with the least other information
    // available to whoever reads the catalog.
    for (pair in builder.stateCallbacks) {
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
    }
    // Variants with nothing to write to. The builder renders the choices and the export has no
    // parameter to put the selected value in, so the control moves and the generated call does not
    // — indistinguishable from a broken builder unless the catalog says so.
    if (builder.variants.isNotEmpty() && builder.variantProperty?.isNotBlank() != true) {
      into +=
        UiBuilderDiagnostic(
          code = Diagnostics.VARIANTS_WITHOUT_PROPERTY,
          subject = builderId,
          message =
            "declares ${builder.variants.size} variant(s) but no variantProperty, so nothing " +
              "receives the selected value and every variant is inert.",
        )
    }

    // A key named twice in any list that later becomes a MAP.
    //
    // `policyFor` collapses four of these with `associate`, which keeps the last silently — so
    // `stateCallbacks = ["onChange=checked:boolean", "onChange=value:number"]` publishes whichever
    // the author happened to write second, and the contradiction never appears anywhere. Both
    // entries pass every check above, because every check above asks about ONE entry.
    //
    // All four lists, not just the callbacks: they are collapsed by the same call in the same
    // expression, so a check covering one of them would be a rule somebody has to remember to
    // extend, and this file already has a history of that.
    for ((label, pairs) in
      listOf(
        "stateCallbacks" to builder.stateCallbacks,
        "starter" to builder.starter,
        "slots" to builder.slots,
        "variants" to builder.variants,
      )) {
      for ((key, entries) in pairs.groupBy { it.key }.filterValues { it.size > 1 }) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.POLICY_MALFORMED_ENTRY,
            subject = "$builderId.$key",
            message =
              "'$label' names '$key' ${entries.size} times " +
                "(${entries.joinToString { it.value }}). Only the last survives being read into a " +
                "map, so the others do nothing and the one that wins is whichever was written " +
                "last — say it once.",
          )
      }
    }

    // The claims that need the record. Only worth checking against a signature that was actually
    // read: an unrecovered one reports "no parameters", and every entry would look wrong.
    if (!component.signatureKnown) return
    val parameterNames = component.parameters.map { it.name }.toSet()
    val parametersByName = component.parameters.associateBy { it.name }
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
      // The callback must be FUNCTION-typed, not merely a parameter that exists. `label=…` names a
      // real parameter of most components, and the export would then emit a lambda where the
      // component wants a String — source that does not compile, from a catalog that looked valid.
      val target = parametersByName[pair.key]
      if (target != null && "->" !in target.type) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_NOT_A_FUNCTION,
            subject = "$builderId.${pair.key}",
            message =
              "'${pair.key}' is a parameter of ${component.canonicalId} but its type is " +
                "'${target.type}', which is not function-typed, so the export would emit a lambda " +
                "where the component wants a value. (A typealias for a function type renders " +
                "under its own name and will report here too; the type above is what the record " +
                "holds.)",
          )
      }
      val state = pair.value.substringBefore(':').trim()
      // The declared JSON type has to MATCH the state parameter, not merely be a word this
      // vocabulary knows. `checked:string` over a `Boolean` passes every other check — supported
      // type, both parameters present, callback function-typed — and tells the export to initialise
      // a String state and thread it into a Boolean, which does not compile.
      val declaredType = pair.value.substringAfter(':', "").trim()
      val stateParam = parametersByName[state]
      // The SIMPLE name, because a record holds `kotlin.Boolean` and this table is keyed on
      // `Boolean`. Comparing the qualified string against it meant the check could only ever fail,
      // so a correct `checked:boolean` over a `kotlin.Boolean` was reported as a mismatch — a
      // diagnostic that fires on every catalog it was written to protect. The one test that
      // covered the matching case wrote the type unqualified, which is not what a record holds.
      val classifier = stateParam?.type?.let(::classifierOf)
      val expected = STATE_TYPE_CLASSIFIERS[declaredType]
      val declaredTypeWrong = classifier != null && expected != null && classifier !in expected
      if (declaredTypeWrong) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_TYPE_MISMATCH,
            subject = "$builderId.${pair.key}",
            message =
              "declares state '$state' as '$declaredType', but ${component.canonicalId} takes it " +
                "as '${stateParam.type}'. The export would initialise a $declaredType and thread " +
                "it into a ${stateParam.type}, which does not compile. " +
                "'$declaredType' means ${expected.sorted().joinToString(" or ")}.",
          )
      }
      // And the CALLBACK's own input, which is the last half of this that nothing compared.
      //
      // I declined this one round ago, arguing that pulling a parameter type out of a rendered
      // function type is parsing a display rendering. That argument was already spent: the
      // function-typed check two branches up reads `"->" in target.type`, which is the same
      // rendering. The real limit is not that it is a rendering, it is that only SOME renderings
      // can be read confidently — so this reads exactly one shape, `(X) -> R` with a single
      // argument and no receiver, and says nothing about any other. `checked: Boolean` with
      // `onCheckedChange: (String) -> Unit` is the case: every check above passes and the export
      // threads a Boolean into a String-taking lambda.
      val callbackInputType = target?.type?.let(::soleFunctionInput)
      val callbackInput = callbackInputType?.let(::classifierOf)
      // Arity, which is a different mistake from a type mismatch and was folded into silence.
      //
      // `soleFunctionInput` returns null for a zero- or two-argument callback, and null meant "do
      // not diagnose" — so `onClick=checked:boolean` over an `onClick: () -> Unit` passed every
      // check. The export writes `onCheckedChange = { checked = it }`, which needs exactly one
      // argument, so both shapes produce source that does not compile. I declined this one round
      // ago on the grounds that it wanted its own message; that was an argument for writing the
      // message, not for staying quiet.
      val callbackInputs = target?.type?.let(::functionInputs)
      if (callbackInputs != null && callbackInputs.size != 1) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_ARITY,
            subject = "$builderId.${pair.key}",
            message =
              "'${pair.key}' takes ${callbackInputs.size} argument(s) ('${target.type}'), and the " +
                "export writes it as `{ $state = it }`, which needs exactly one. A callback that " +
                "fires without carrying the new value cannot update '$state'.",
          )
      }
      // Nullability, in the direction the export actually assigns. The generated lambda writes the
      // callback's argument back into the hoisted state — `onCheckedChange = { checked = it }` — so
      // a `(Boolean?) -> Unit` over a `Boolean` state assigns a nullable into a non-null var and
      // does not compile, while the reverse is ordinary and correct. Comparing bare classifiers
      // strips the `?` off both sides and could see neither.
      val nullableIntoNonNull =
        callbackInputType?.trim()?.endsWith("?") == true &&
          stateParam?.type?.trim()?.endsWith("?") == false
      // Not when the branch above already fired: one entry, one disagreement, one diagnostic. Two
      // messages under the same code about the same three names read as two separate defects.
      if (
        !declaredTypeWrong &&
          callbackInput != null &&
          classifier != null &&
          (callbackInput != classifier || nullableIntoNonNull)
      ) {
        into +=
          UiBuilderDiagnostic(
            code = Diagnostics.STATE_CALLBACK_TYPE_MISMATCH,
            subject = "$builderId.${pair.key}",
            message =
              "state '$state' is a ${stateParam?.type}, but '${pair.key}' takes " +
                "'${target.type}'. The export writes the callback's argument back into the hoisted " +
                "state, so the two have to agree; one of the component's two parameters is not " +
                "the one this entry means.",
          )
      }
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
      // A slot's role selects a template exactly as the builtin's own role does, and it was checked
      // in the JavaScript pre-flight and nowhere else. That pre-flight runs in the two workflow
      // lanes; local discovery and a direct `bundle pack` never see it, and those are the paths
      // this contract exists to make first-class. So a misspelled nested role was published without
      // a diagnostic, selecting no template, on exactly the consumers that have no other check.
      //
      // Slots are held as raw `JsonElement` because their shape is the loader's business, not this
      // generator's. Reading one field out of that is deliberate: an unreadable slot is left to the
      // loader rather than diagnosed here, so this cannot start rejecting shapes it does not own.
      for ((slot, spec) in builtin.slots) {
        val role = ((spec as? JsonObject)?.get("role") as? JsonPrimitive)?.takeIf { it.isString }
        val name = role?.content ?: continue
        if (name !in UI_BUILDER_STRUCTURAL_ROLES) {
          into +=
            UiBuilderDiagnostic(
              code = Diagnostics.BUILTIN_SLOT_ROLE_UNKNOWN,
              subject = "$id/$slot",
              message =
                "slot role '$name' is not one the template engine knows, so the slot selects no " +
                  "template. Known roles: " +
                  UI_BUILDER_STRUCTURAL_ROLES.sorted().joinToString(),
            )
        }
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
