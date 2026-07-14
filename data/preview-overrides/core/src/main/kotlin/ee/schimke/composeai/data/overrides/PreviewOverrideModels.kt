package ee.schimke.composeai.data.overrides

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/overrides` data product. Lifted out of the daemon-side registry
 * (as `RemoteComposeProduct` is) so the bundle producer and MCP clients can depend on the payload
 * schema without pulling in the connector, Compose, or any backend.
 */
object PreviewOverridesProduct {
  const val KIND: String = "compose/overrides"
  const val SCHEMA_VERSION: Int = 1
}

/**
 * Type tags for a [PreviewOverrideDeclaration] — what kind of control a viewer renders for the
 * knob. Kept as small string constants (rather than an enum on the wire) so an older reader
 * tolerates a future type it doesn't recognise. Matches the [PreviewOverrideValue] variants the
 * runtime emits.
 */
object PreviewOverrideType {
  const val STRING: String = "string"
  const val INT: String = "int"
  const val FLOAT: String = "float"
  const val BOOL: String = "bool"
  const val COLOR: String = "color"
}

/**
 * One author-declared editable knob a preview exposes through a `previewOverride*` lookup.
 *
 * Named distinctly from the protocol's [ee.schimke.composeai.daemon.protocol.PreviewOverrides] (the
 * display-knob bag — size / theme / locale) to avoid confusion: a *declaration* is "this preview
 * offers an editable string named `label`, default `Tap me`", produced by the preview itself,
 * whereas `PreviewOverrides.namedOverrides` is the daemon's seed of *replacement values* for those
 * declarations.
 *
 * **Repeated components.** A scalar knob (the common case) has [index] = null. A knob declared
 * inside a repeat (a per-item value on a list) is recorded once per item with [index] = 0, 1, 2, …;
 * the on-wire key the daemon seeds against is then [seedKey] — the base key with the index appended
 * in brackets. The item count is itself just an ordinary int knob the author feeds into
 * `repeat(n)`.
 */
@Serializable
data class PreviewOverrideDeclaration(
  /**
   * Author-chosen key, e.g. `"label"` or `"rowCount"`. Stable across renders for the same call
   * site.
   */
  val key: String,
  /** One of [PreviewOverrideType]. The viewer picks the control widget from this. */
  val type: String,
  /** Human label for the control; defaults to [key]. */
  val label: String = key,
  /** The author-supplied fallback value (what renders with no override applied). */
  val default: PreviewOverrideValue,
  /**
   * The effective value after the latest render — the daemon-seeded replacement, or the [default]
   * when none was seeded. Null in a bundle sidecar captured by a standalone (non-daemon) render
   * that only knows the declared default. A viewer shows this as the control's current state.
   */
  val current: PreviewOverrideValue? = null,
  /** Non-null for one instance of a repeated/indexed knob; the wire key is then [seedKey]. */
  val index: Int? = null,
) {
  /**
   * The composite key the daemon seeds against: the bare [key] for a scalar knob, or the key with
   * the [index] appended in square brackets (`rowLabel` at index 2 seeds against `rowLabel` then
   * `2` in brackets) when indexed.
   */
  val seedKey: String
    get() = if (index == null) key else "$key[$index]"
}

/**
 * Wire shape returned by `data/fetch?kind=compose/overrides` and carried in a bundle's
 * `previews/<id>.overrides.json` sidecar: the set of editable knobs the preview declared during its
 * latest render, in declaration order.
 */
@Serializable
data class PreviewOverridesPayload(val declarations: List<PreviewOverrideDeclaration> = emptyList())

/**
 * Marks a [PreviewOverrideDeclaration] that the render backend synthesised for a **string loaded
 * from resources** (Android `stringResource` / CMP `org.jetbrains.compose.resources.stringResource`)
 * rather than an author's explicit `previewOverride*` call. Any string a preview pulls from
 * resources is editable without the author having to wrap it — a resource lookup already tells the
 * backend what text was rendered and gives us a stable key to seed a replacement against.
 *
 * The marker is a **key prefix** rather than a new schema field so older readers of the
 * `compose/overrides` payload tolerate it unchanged: to them a resource knob is just another string
 * knob whose `key` happens to start with `res:`. The backend mints the key deterministically (the
 * Android resource entry name, or the CMP resource `path#offset`) so the same key is recomputed at
 * declaration time and at substitution time, keeping the `namedOverrides` seed round-trip intact.
 */
const val RESOURCE_OVERRIDE_KEY_PREFIX: String = "res:"

/** True when [key] was minted for a resource-loaded string (see [RESOURCE_OVERRIDE_KEY_PREFIX]). */
fun isResourceOverrideKey(key: String): Boolean = key.startsWith(RESOURCE_OVERRIDE_KEY_PREFIX)

/** True when this declaration was synthesised for a resource-loaded string. */
fun PreviewOverrideDeclaration.isResourceOverride(): Boolean = isResourceOverrideKey(key)

/**
 * Collapse the "same string offered twice" case the auto-resource surface can create: when a preview
 * both loads a string from resources **and** passes it through an explicit `previewOverride*` call,
 * the render records two knobs whose author default is the same text — one resource-synthesised, one
 * explicit. Drop the resource-synthesised duplicate so the viewer shows a single control; the
 * explicit knob wins because the author named it deliberately.
 *
 * A resource knob is dropped only when its author [PreviewOverrideDeclaration.default] equals the
 * default of some **explicit** (non-resource) string knob. Resource knobs that no explicit knob
 * shadows are kept, and explicit knobs are never removed. Declaration order is otherwise preserved.
 */
fun dedupeResourceOverrideDeclarations(
  declarations: List<PreviewOverrideDeclaration>
): List<PreviewOverrideDeclaration> {
  if (declarations.isEmpty()) return declarations
  val explicitStringDefaults: Set<String> =
    declarations
      .asSequence()
      .filterNot { it.isResourceOverride() }
      .mapNotNull { (it.default as? PreviewOverrideValue.StringValue)?.value }
      .toSet()
  if (explicitStringDefaults.isEmpty()) return declarations
  return declarations.filterNot { declaration ->
    declaration.isResourceOverride() &&
      (declaration.default as? PreviewOverrideValue.StringValue)?.value in explicitStringDefaults
  }
}
