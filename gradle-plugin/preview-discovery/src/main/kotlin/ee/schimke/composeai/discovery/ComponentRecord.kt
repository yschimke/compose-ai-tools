package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * Wire version of `components.json`.
 *
 * A published data product needs one from the start: the file persists in bundles and is read by
 * the preview browser, the UI builder and MCP clients, so an old bundle and a detached consumer
 * must be able to tell an additive record from an incompatible future one — the lesson
 * `docs/API_STABILITY.md` records for `previews.json`.
 *
 * Evolution rules: an added field with a default bumps nothing; a removed field, or one whose
 * meaning changes, bumps this. A reader that does not know a major version refuses the file rather
 * than guessing at it.
 */
const val COMPONENT_RECORD_SCHEMA_VERSION: Int = 1

/**
 * `components.json` — the **components** a module's previews render, as opposed to
 * `previews.json`'s *renders*.
 *
 * The distinction is the point. A preview is one capture of one configuration; a component is the
 * API underneath, and until now it was written down nowhere: a catalog's 59 `@CatalogComponent`
 * entries stand for 148 distinct library symbols whose signatures appear in no artifact. This file
 * is that missing record, derived rather than authored — every field comes from `@kotlin.Metadata`
 * or from discovery's own inference, so there is nothing to keep in sync by hand.
 */
@Serializable
data class ComponentRecordFile(
  val schemaVersion: Int = COMPONENT_RECORD_SCHEMA_VERSION,
  val module: String,
  val variant: String,
  val components: List<ComponentRecord>,
)

/**
 * One composable, with the previews that render it.
 *
 * @property canonicalId the always-present identity — `<module>/<jvmOwner>.<name>`. Deliberately
 *   not [componentId]: a catalog id comes from `@CatalogComponent`, which an ordinary application
 *   preview does not carry, so it is absent for exactly the typical-app records this file exists to
 *   publish, and several absent ids would collide as a key.
 *
 *   **Overloads still collide here.** Two overloads share this id, so they merge into one record
 *   rather than appearing as two. Discovery now records the JVM descriptor that tells them apart
 *   ([ComponentSymbol.descriptor]) — the gap `:renderer-android`'s `findDefaultedComposableMethod`
 *   names when it refuses to guess between two fully-defaulted overloads — but recording it does
 *   not un-merge the records, and putting the id itself on a descriptor basis would rewrite every
 *   id in the file for a case no consumer has asked to resolve. So the merge is what a reader sees,
 *   and [ComponentSymbol.descriptor] is deliberately **null** when the merged targets disagreed: a
 *   single descriptor on a merged symbol would claim a precision the record does not have. Null
 *   descriptor with `signatureKnown` true is how a collision announces itself.
 *
 * @property componentIds every published catalog identity associated with this symbol, deduplicated
 *   and ordered. A **list**, not a scalar: one symbol is routinely rendered by several catalog
 *   entries, and keeping only the first would hand the component an arbitrary,
 *   manifest-order-dependent alias — or none at all, when an ordinary preview happened to come
 *   first. Aliases, never the key; [ComponentBinding.componentId] says which preview contributed
 *   which.
 */
@Serializable
data class ComponentRecord(
  val canonicalId: String,
  val componentIds: List<String> = emptyList(),
  val symbol: ComponentSymbol,
  val parameters: List<TargetParameter> = emptyList(),
  val slots: List<ComponentSlot> = emptyList(),
  val bindings: List<ComponentBinding> = emptyList(),
  /**
   * The Kotlin call site for this component, printed by `ComponentSnippets` — or the reason there
   * isn't one.
   *
   * Persisted rather than left for a consumer to compute, because the three things that make a
   * refusal sound ([signatureKnown], [ComponentSymbol.receiver], and the `…Kt`-facade evidence in
   * [ComponentSymbol.callable]) are producer-side knowledge. A consumer re-deriving the call site
   * would have to rediscover all three, and a second implementation of a rule this exacting is how
   * two sides of a contract start disagreeing. It also makes this file answerable on its own: a
   * reader gets the call site without linking the discovery library that produced it.
   *
   * Null only in a record written before this field existed — never "no call site", which
   * [ComponentCode.refusedReason] says explicitly.
   */
  val code: ComponentCode? = null,
  /**
   * Whether [parameters], [slots] and [ComponentSymbol.receiver] were read from `@kotlin.Metadata`
   * rather than defaulted away.
   *
   * An empty [parameters] means "takes no arguments" only when this is true; when it is false it
   * means "not recovered", and the two are not interchangeable. A consumer scaffolding a call site
   * for a human to finish may ignore the distinction; one generating code it claims will compile
   * must not.
   */
  val signatureKnown: Boolean = false,
  /** See `PreviewTarget.callableFromAnotherFile`. Defaults to the permissive reading. */
  val callableFromAnotherFile: Boolean = true,
  /** See `PreviewTarget.hasTypeParameters`. */
  val hasTypeParameters: Boolean = false,
  /**
   * True when overloads collided into this record — they share [canonicalId], so `collect` merged
   * them and kept one signature. No call site can be printed for the merged pair: two fully
   * defaulted overloads make `Chip()` ambiguous, and the record no longer says which one the
   * signature belongs to.
   */
  val overloadsCollided: Boolean = false,
  /**
   * Fully-qualified `@RequiresOptIn` markers the declaration carries. Copied onto
   * [ComponentCode.requiredOptIns] for the emitted call; see that field for what a caller does with
   * them.
   */
  val requiredOptIns: List<String> = emptyList(),
)

/**
 * How to name a component — three ways, because one name cannot serve all three readers.
 *
 * @property jvmOwner the reflection handle. For a top-level function this is the synthetic file
 *   facade (`androidx.compose.material3.ButtonKt`), which is what `Class.forName` needs.
 * @property callable the **source-level** FQN generated Kotlin imports
 *   (`androidx.compose.material3.Button`). Deriving an import from [jvmOwner] would print `import
 *   androidx.compose.material3.ButtonKt`, which does not resolve.
 * @property jvmName the JVM method name — what `Class.forName(jvmOwner).getMethod(…)` needs, and
 *   not the same string as [name] whenever Kotlin mangled it (a signature mentioning a value class
 *   gives `AppTile-a1b2c3d`). Null when not recorded, and — like [descriptor] — when overloads
 *   merged into this record disagreed: mangling is per-signature, so `Chip(label: String)` and
 *   `Chip(width: Dp)` share a source name and a canonical id while their JVM names differ.
 * @property descriptor the JVM method descriptor, which is what actually identifies *which* method
 *   is meant: two overloads mentioning no value class share both [name] and [jvmName] exactly. Null
 *   when not recorded, and also when overloads merged into this record disagreed — see
 *   [ComponentRecord.canonicalId].
 * @property origin where the symbol lives. Explicit rather than inferred from [sourceFile] being
 *   null, which also happens for a project-local file discovery could not resolve (a generated
 *   source, say) — reading that null as "library" would send a local component down the sources-jar
 *   path and discard the KDoc and fixtures available from the project.
 * @property sourceFile availability only, never a proxy for [origin].
 * @property docs where KDoc and default expressions came from. `"unavailable"` in v1 for every
 *   symbol: Kotlin metadata carries neither, and resolving a `-sources.jar` is the next step. Said
 *   explicitly so a consumer can tell "no KDoc" from "KDoc not recovered".
 * @property receiver the fully-qualified type this composable is declared as an extension on, or
 *   null when it is an ordinary function. Decides whether a call site resolves at all:
 *   `AnimatedVisibility` is declared on `ColumnScope`, so printing it at file scope is an
 *   unresolved reference rather than a style choice. Only meaningful when
 *   [ComponentRecord.signatureKnown].
 */
@Serializable
data class ComponentSymbol(
  val jvmOwner: String,
  val callable: String,
  val name: String,
  val origin: ComponentOrigin,
  val jvmName: String? = null,
  val descriptor: String? = null,
  val sourceFile: String? = null,
  val docs: String = "unavailable",
  val receiver: String? = null,
)

@Serializable
enum class ComponentOrigin {
  /** Compiled from this project's own sources. */
  PROJECT,
  /** A dependency's symbol — a design-system component. */
  LIBRARY,
}

/**
 * A `@Composable` lambda parameter: somewhere a child can go.
 *
 * @property required whether the **lambda argument** must be supplied. This is all the signature
 *   decides. It is emphatically *not* child cardinality: `Button`'s required `content` lambda may
 *   legally emit zero children or five, so projecting `required` as a minimum of one would reject
 *   valid documents.
 * @property receiverScope the lambda's receiver (`androidx.compose.foundation.layout.RowScope`), or
 *   null when it has none. Recorded because it decides what a child's modifier may call —
 *   `Modifier.weight` compiles inside a `RowScope` slot and nowhere else. It does **not** determine
 *   which components are accepted; that stays authored policy, absent from this record by design
 *   rather than by omission.
 */
@Serializable
data class ComponentSlot(
  val name: String,
  val required: Boolean,
  val receiverScope: String? = null,
)

/**
 * How to call this component — exactly one of [call] and [refusedReason] is set.
 *
 * The refusal is the load-bearing half. A generator handed a call site it cannot prove will produce
 * source that looks right and does not build, which is worse than an admitted gap: a consumer given
 * a [refusedReason] can ask a human or a model for the one missing value, while a consumer handed
 * broken source has to discover the breakage itself.
 *
 * It is also a **tier signal**, and a mechanical one. A component whose call site cannot be printed
 * cannot reach a Compose exporter, so the question "which components can this pipeline actually
 * generate code for?" is answered by this field rather than by an authored allowlist that someone
 * has to remember to extend.
 *
 * @property call the call expression — `Button(onClick = {}, content = {})`. An **expression**, not
 *   a file: it calls a `@Composable`, so it compiles only inside a `@Composable` body, and the
 *   caller supplies that wrapper.
 * @property imports the FQNs [call] needs. Today always exactly the callable, because every
 *   placeholder written is a literal or an empty lambda — a property of the placeholder table
 *   rather than a coincidence, and one that stops holding the moment the table admits a constructor
 *   call.
 * @property refusedReason why there is no call site, phrased for a human or a model to act on,
 *   since supplying the missing value is exactly what a consumer would escalate.
 */
@Serializable
data class ComponentCode(
  val call: String? = null,
  val imports: List<String> = emptyList(),
  val refusedReason: String? = null,
  /**
   * Fully-qualified `@RequiresOptIn` markers the wrapper around [call] must apply.
   *
   * This is the one part of the contract the caller has to act on rather than paste. [call] already
   * only compiles inside a `@Composable` body the caller supplies; when this is non-empty that body
   * also needs `@OptIn(Marker::class)` and an import for each marker. Emitting the call and saying
   * so beats refusing: opting in is mechanical, and refusing would drop most of Material 3 over a
   * problem the caller fixes in one annotation.
   */
  val requiredOptIns: List<String> = emptyList(),
)

/**
 * One preview that renders this component.
 *
 * The render filenames are deliberately not repeated here: they are derived from the preview id by
 * the same rule every consumer already applies to `previews.json`, and duplicating a derived value
 * into a second file is how the two start disagreeing.
 *
 * @property componentId the catalog identity *this* preview published the symbol under, when it
 *   carried one. Per binding rather than per component, because the association belongs to the
 *   preview: the same `Card` can be one catalog's `Containment/Card` and another preview's
 *   incidental container.
 */
@Serializable data class ComponentBinding(val previewId: String, val componentId: String? = null)
