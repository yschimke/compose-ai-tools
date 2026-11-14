package ee.schimke.composeai.bundle

import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Data product kind advertising Declarative Watch Face (`<WatchFace>`) complication slot metadata
 * and live slot state (`get_preview_data(kind = "watchface/complications")`), plus the static
 * `previews/<id>.watchface.json` bundle sidecar.
 */
public object WatchFaceComplicationsProduct {
  public const val KIND: String = "watchface/complications"
  public const val SCHEMA_VERSION: Int = 1
}

/**
 * Portable watch-face metadata and active complication/configuration state for a `<WatchFace>`
 * preview. Carried inline in `watchface/complications` data-product responses and persisted beside
 * baked renders as `previews/<id>.watchface.json`.
 */
@Serializable
public data class WatchFaceComplicationsPayload(
  public val previewId: String,
  public val dwfAssetPath: String,
  public val widthPx: Int,
  public val heightPx: Int,
  public val clipShape: String,
  public val previewTime: String,
  public val isAmbient: Boolean,
  public val userConfigurations: List<WatchFaceUserConfigDto> = emptyList(),
  public val slots: List<WatchFaceComplicationSlotDto> = emptyList(),
)

@Serializable
public data class WatchFaceUserConfigDto(
  public val id: String,
  public val kind: String,
  public val displayName: String,
  public val defaultValue: String,
  public val activeValue: String,
  public val options: List<String> = emptyList(),
  public val optionColors: Map<String, List<String>> = emptyMap(),
)

@Serializable
public data class WatchFaceSlotBoundsDto(
  public val x: Float,
  public val y: Float,
  public val width: Float,
  public val height: Float,
)

@Serializable
public data class WatchFaceComplicationSlotDto(
  public val slotId: Int,
  public val bounds: WatchFaceSlotBoundsDto,
  public val boundingShape: String? = null,
  public val isCustomizable: Boolean = true,
  public val supportedTypes: List<String> = emptyList(),
  public val authoredTypes: List<String> = emptyList(),
  public val defaultSystemProvider: String? = null,
  public val defaultSystemProviderType: String? = null,
  public val activeState: WatchFaceActiveComplicationDto,
)

@Serializable
public data class WatchFaceActiveComplicationDto(
  public val type: String,
  public val typeCode: Int,
  public val text: String,
  public val title: String,
  public val value: Float,
  public val min: Float,
  public val max: Float,
  public val hasMonochromaticImage: Boolean,
  public val hasSmallImage: Boolean,
  public val hasPhotoImage: Boolean,
  public val imageColor: String,
  public val accentColor: String,
  public val hasRangedValueColors: Boolean,
  public val rangedValueColorsInterpolate: Boolean,
  public val hasGoalProgressColors: Boolean,
  public val goalProgressColorsInterpolate: Boolean,
  public val hasWeightedElements: Boolean,
  public val colorCount: Int,
  public val colors: List<String> = emptyList(),
  public val weights: List<Float> = emptyList(),
) {
  public fun semanticText(): String =
    when (type) {
      "EMPTY" -> ""
      "MONOCHROMATIC_IMAGE",
      "SMALL_IMAGE",
      "PHOTO_IMAGE" -> listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
      "RANGED_VALUE",
      "GOAL_PROGRESS" -> {
        val label = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (label.isNotEmpty()) label
        else "${formatWatchFaceNumber(value)}/${formatWatchFaceNumber(max)}"
      }
      else -> listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
    }

  public fun accessibilitySummary(slotId: Int): String =
    when (type) {
      "EMPTY" -> "Complication slot $slotId: EMPTY"
      "RANGED_VALUE",
      "GOAL_PROGRESS" -> {
        val parts = buildList {
          add("Complication slot $slotId ($type)")
          if (title.isNotBlank()) add(title)
          if (text.isNotBlank()) add(text)
          add(
            "value=${formatWatchFaceNumber(value)} " +
              "[${formatWatchFaceNumber(min)}..${formatWatchFaceNumber(max)}]",
          )
        }
        parts.joinToString(": ")
      }
      "WEIGHTED_ELEMENTS" -> {
        val parts = buildList {
          add("Complication slot $slotId (WEIGHTED_ELEMENTS)")
          if (title.isNotBlank()) add(title)
          if (text.isNotBlank()) add(text)
          if (weights.isNotEmpty()) {
            add("weights=${weights.joinToString(",") { formatWatchFaceNumber(it) }}")
          }
        }
        parts.joinToString(": ")
      }
      else -> {
        val content = listOf(title, text).filter { it.isNotBlank() }.joinToString(" - ")
        if (content.isNotEmpty()) {
          "Complication slot $slotId ($type): $content"
        } else {
          "Complication slot $slotId ($type)"
        }
      }
    }
}

private fun formatWatchFaceNumber(v: Float): String {
  val asInt = v.toInt()
  return if (abs(v - asInt.toFloat()) < 0.001f) asInt.toString() else "%.1f".format(Locale.ROOT, v)
}
