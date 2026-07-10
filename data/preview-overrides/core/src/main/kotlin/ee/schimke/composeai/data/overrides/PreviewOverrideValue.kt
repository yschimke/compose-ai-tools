package ee.schimke.composeai.data.overrides

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Preview-neutral typed value for an author-declared `PreviewOverrides.namedOverrides` knob.
 *
 * Lives in `:data-preview-overrides-core` (not the daemon protocol) so the bundle producer, the
 * runtime, and MCP clients can depend on the `compose/overrides` payload schema without dragging in
 * the render daemon — the same reason [PreviewOverrideDeclaration] and [PreviewOverridesProduct]
 * were lifted out. The daemon protocol imports this type for its `PreviewOverrides.namedOverrides`
 * seed.
 *
 * Deliberately **not** the Remote-Compose `RemoteNamedValue` sum (a `dp` variant wrapped with
 * `.rdp`, mapped onto the `RcPlatformProfiles` creation DSL). These values seed plain Compose
 * `previewOverride*` lookups, so the variant set is the small JVM/Compose-native one (string / int /
 * float / bool / color). A `Dp` knob is carried as [FloatValue] — the runtime helper wraps the float
 * in `.dp` at the API edge.
 *
 * `@JsonClassDiscriminator("kind")` so payloads read `{ "kind": "string", "value": "Tap me" }`
 * rather than carrying the polymorphic class name.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class PreviewOverrideValue {
  @Serializable
  @SerialName("string")
  data class StringValue(val value: String) : PreviewOverrideValue()

  @Serializable @SerialName("int") data class IntValue(val value: Int) : PreviewOverrideValue()

  @Serializable
  @SerialName("float")
  data class FloatValue(val value: Float) : PreviewOverrideValue()

  @Serializable
  @SerialName("bool")
  data class BooleanValue(val value: Boolean) : PreviewOverrideValue()

  /** Color as `#AARRGGBB`. The runtime helper parses it back to a Compose `Color`. */
  @Serializable
  @SerialName("color")
  data class ColorValue(val argb: String) : PreviewOverrideValue()
}
