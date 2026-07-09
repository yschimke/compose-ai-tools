package ee.schimke.composeai.data.gestures

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/gestures` data product. Lifted out of the daemon-side registry so
 * MCP clients and other connectors can depend on the payload schema without pulling in the daemon,
 * Robolectric, or `androidx.wear.compose.material3`.
 */
object Material3GestureProduct {
  const val KIND: String = "compose/gestures"
  const val SCHEMA_VERSION: Int = 1
}

/**
 * Wire-shape returned by `data/fetch?kind=compose/gestures`.
 *
 * Reports the one-handed-gesture surface a Wear preview registered during its most recent render:
 * which handlers are wired ([registered]), whether gesture recognition is enabled for the tree
 * ([enabled] — mirrors `LocalOneHandedGestureEnabled`), whether the connector force-showed the
 * gesture hints for this render ([hintsShown]), and — for interactive sessions — the label of the
 * handler last invoked via an `input.gesture` script event ([lastInvoked]).
 *
 * The gesture framework (`Modifier.oneHandedGesture` in `wear-compose 1.7.0-alpha`) only dispatches
 * on a Pixel Watch 3+; off-device it silently no-ops. This data product is what makes the otherwise
 * invisible gesture wiring observable under Robolectric / `@Preview`, and lets an agent invoke a
 * handler without the hardware.
 */
@Serializable
data class GesturePayload(
  /** Mirrors `LocalOneHandedGestureEnabled` for the previewed tree. */
  val enabled: Boolean,
  /** `true` when the connector force-showed the gesture hints for this render (immediate mode). */
  val hintsShown: Boolean,
  /**
   * Label of the handler most recently invoked via an `overrides.gestures.invoke` override, or null
   * if none this session.
   */
  val lastInvoked: String? = null,
  /** Gesture handlers the preview registered during its most recent render. */
  val registered: List<RegisteredGesture> = emptyList(),
)

/** One gesture handler the preview registered, as reported to the `compose/gestures` product. */
@Serializable
data class RegisteredGesture(
  /**
   * Lower-case wire spelling of the handler kind — `"primary"`, `"dismiss"`, `"scroll"`, `"page"`.
   */
  val type: String,
  /** Accessibility / hint label supplied to `Modifier.oneHandedGesture(gestureLabel = …)`. */
  val label: String,
  /** Whether a gesture hint (`OneHandedGestureIndicator`) is wired for this handler. */
  val hintAvailable: Boolean,
)
