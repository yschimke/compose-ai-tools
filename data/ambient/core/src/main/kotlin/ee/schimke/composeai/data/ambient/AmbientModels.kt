package ee.schimke.composeai.data.ambient

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/ambient` data product. Lifted out of `AmbientDataProductRegistry`
 * so MCP clients and other connectors can depend on the payload schema without pulling in the
 * daemon-side registry, Robolectric, or `androidx.wear.ambient`.
 */
object Material3AmbientProduct {
  const val KIND: String = "compose/ambient"
  const val SCHEMA_VERSION: Int = 1
}

/**
 * Wire-shape returned by `data/fetch?kind=compose/ambient`. Mirrors the fields exposed by
 * horologist's `AmbientState.Ambient(...)` plus the synthetic minute-tick timestamp the controller
 * forwards to `onUpdateAmbient(...)`.
 */
@Serializable
data class AmbientPayload(
  /** Lower-case wire spelling — `"interactive"`, `"ambient"`, or `"inactive"`. */
  val state: String,
  /**
   * Mirrors `AmbientDetails.burnInProtectionRequired` — `false` when [state] is not `"ambient"`.
   */
  val burnInProtectionRequired: Boolean,
  /** Mirrors `AmbientDetails.deviceHasLowBitAmbient` — `false` when [state] is not `"ambient"`. */
  val deviceHasLowBitAmbient: Boolean,
  /** Synthetic minute-tick timestamp; falls back to render-time wall clock when no override set. */
  val updateTimeMillis: Long,
)
