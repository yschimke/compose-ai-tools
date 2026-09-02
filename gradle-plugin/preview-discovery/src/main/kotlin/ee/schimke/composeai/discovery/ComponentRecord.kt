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
 *   **Overloads collide here, and that is a known v1 limitation.** Distinguishing them needs the
 *   JVM descriptor, which discovery does not record yet — the same gap `:renderer-android`'s
 *   `findDefaultedComposableMethod` names when it refuses to guess between two fully-defaulted
 *   overloads. [ComponentSymbol.descriptor] is where it lands; until then a reader that finds two
 *   records with one id should treat the pair as unresolved rather than pick one.
 *
 * @property componentId the published catalog identity, when the preview carried one. An alias, not
 *   the key.
 */
@Serializable
data class ComponentRecord(
  val canonicalId: String,
  val componentId: String? = null,
  val symbol: ComponentSymbol,
  val parameters: List<TargetParameter> = emptyList(),
  val slots: List<ComponentSlot> = emptyList(),
  val bindings: List<ComponentBinding> = emptyList(),
)

/**
 * How to name a component — three ways, because one name cannot serve all three readers.
 *
 * @property jvmOwner the reflection handle. For a top-level function this is the synthetic file
 *   facade (`androidx.compose.material3.ButtonKt`), which is what `Class.forName` needs.
 * @property callable the **source-level** FQN generated Kotlin imports
 *   (`androidx.compose.material3.Button`). Deriving an import from [jvmOwner] would print `import
 *   androidx.compose.material3.ButtonKt`, which does not resolve.
 * @property descriptor the JVM method descriptor, once discovery records one. Null in v1 — see
 *   [ComponentRecord.canonicalId] for what that costs.
 * @property origin where the symbol lives. Explicit rather than inferred from [sourceFile] being
 *   null, which also happens for a project-local file discovery could not resolve (a generated
 *   source, say) — reading that null as "library" would send a local component down the sources-jar
 *   path and discard the KDoc and fixtures available from the project.
 * @property sourceFile availability only, never a proxy for [origin].
 * @property docs where KDoc and default expressions came from. `"unavailable"` in v1 for every
 *   symbol: Kotlin metadata carries neither, and resolving a `-sources.jar` is the next step. Said
 *   explicitly so a consumer can tell "no KDoc" from "KDoc not recovered".
 */
@Serializable
data class ComponentSymbol(
  val jvmOwner: String,
  val callable: String,
  val name: String,
  val origin: ComponentOrigin,
  val descriptor: String? = null,
  val sourceFile: String? = null,
  val docs: String = "unavailable",
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
 * One preview that renders this component.
 *
 * The render filenames are deliberately not repeated here: they are derived from the preview id by
 * the same rule every consumer already applies to `previews.json`, and duplicating a derived value
 * into a second file is how the two start disagreeing.
 */
@Serializable data class ComponentBinding(val previewId: String)
