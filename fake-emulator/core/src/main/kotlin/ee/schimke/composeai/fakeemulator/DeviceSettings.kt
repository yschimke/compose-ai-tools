package ee.schimke.composeai.fakeemulator

import java.util.concurrent.CopyOnWriteArrayList

/** Light/dark UI mode. [UNSET] keeps the preview's own default. */
enum class UiMode {
  UNSET,
  LIGHT,
  DARK,
}

/** Screen-rotation quadrant, matching Android's `user_rotation` (0=0°, 1=90°, 2=180°, 3=270°). */
enum class RotationQuadrant(val degrees: Int) {
  PORTRAIT(0),
  LANDSCAPE(90),
  REVERSE_PORTRAIT(180),
  REVERSE_LANDSCAPE(270);

  val isLandscape: Boolean
    get() = this == LANDSCAPE || this == REVERSE_LANDSCAPE

  companion object {
    fun fromUserRotation(value: Int): RotationQuadrant =
      when (((value % 4) + 4) % 4) {
        1 -> LANDSCAPE
        2 -> REVERSE_PORTRAIT
        3 -> REVERSE_LANDSCAPE
        else -> PORTRAIT
      }
  }
}

/** Foldable posture, mirroring the emulator's `Posture.PostureValue`. */
enum class DevicePosture {
  UNKNOWN,
  CLOSED,
  HALF_OPENED,
  OPENED,
  FLIPPED,
  TENT,
}

/**
 * Display color-correction mode — Android Studio's "Color correction" / "Color inversion" toggles.
 */
enum class ColorMode {
  NONE,
  INVERTED,
  PROTANOMALY,
  DEUTERANOMALY,
  TRITANOMALY,
  GRAYSCALE,
}

/**
 * The render-relevant device settings the fake emulator exposes. These are the knobs Android Studio
 * flips — via `adb shell` (dark theme, font size, density, locale, TalkBack, color correction) or
 * the emulator gRPC (rotation, posture) — that the app maps onto a `PreviewOverrides` and
 * re-renders.
 *
 * Immutable snapshot; mutate through [DeviceSettingsController].
 */
data class DeviceSettings(
  val uiMode: UiMode = UiMode.UNSET,
  val fontScale: Float? = null,
  val densityDpi: Int? = null,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val localeTag: String? = null,
  val rotation: RotationQuadrant = RotationQuadrant.PORTRAIT,
  val posture: DevicePosture = DevicePosture.UNKNOWN,
  /** TalkBack (or any screen reader) enabled — Studio's a11y toggle. */
  val talkBack: Boolean = false,
  val colorMode: ColorMode = ColorMode.NONE,
  /** `setprop debug.layout true` — draw layout bounds. */
  val showLayoutBounds: Boolean = false,
)

/**
 * Thread-safe, observable holder of [DeviceSettings]. The ADB shell interpreter and the emulator
 * gRPC service both mutate it; the app subscribes once and recomputes the preview overrides
 * whenever it changes.
 */
class DeviceSettingsController(initial: DeviceSettings = DeviceSettings()) {
  private val lock = Any()
  private var value = initial
  private val listeners = CopyOnWriteArrayList<(DeviceSettings) -> Unit>()

  val current: DeviceSettings
    get() = synchronized(lock) { value }

  /** Apply [transform] under lock; notify listeners only when the value actually changes. */
  fun update(transform: (DeviceSettings) -> DeviceSettings) {
    val next: DeviceSettings
    synchronized(lock) {
      val prev = value
      next = transform(prev)
      if (next == prev) return
      value = next
    }
    for (listener in listeners) runCatching { listener(next) }
  }

  fun addListener(listener: (DeviceSettings) -> Unit): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }
}
