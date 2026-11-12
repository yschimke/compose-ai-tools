package ee.schimke.composeai.rccreation.dwf

import androidx.compose.runtime.Immutable

/** Parsed Declarative Watch Face (Watch Face Format v1–v4) document. */
@Immutable
public data class DwfDocument(
  public val width: Int = 450,
  public val height: Int = 450,
  public val clipShape: DwfClipShape = DwfClipShape.CIRCLE,
  public val cornerRadiusX: Float = 0f,
  public val cornerRadiusY: Float = 0f,
  public val metadata: Map<String, String> = emptyMap(),
  public val userConfigurations: List<DwfUserConfiguration> = emptyList(),
  public val defaultFlavorId: String? = null,
  public val flavors: List<DwfFlavor> = emptyList(),
  public val bitmapFonts: List<DwfBitmapFont> = emptyList(),
  public val scene: DwfScene = DwfScene(),
) {
  /** Author-declared `PREVIEW_TIME` metadata (for example `"10:10:30"`), or `null`. */
  public val previewTime: String?
    get() = metadata["PREVIEW_TIME"]

  /** Author-declared `CLOCK_TYPE` metadata (`"ANALOG"` or `"DIGITAL"`), or `null`. */
  public val clockType: String?
    get() = metadata["CLOCK_TYPE"]
}

/** Preset `<Flavor id="...">` declared inside `<Flavors>`. */
@Immutable
public data class DwfFlavor(
  public val id: String,
  public val displayName: String? = null,
  public val icon: String? = null,
  public val configurations: Map<String, String> = emptyMap(),
  public val complications: Map<Int, ComplicationPolicy> = emptyMap(),
) {
  @Immutable
  public data class ComplicationPolicy(
    public val defaultSystemProvider: String? = null,
    public val defaultSystemProviderType: String? = null,
  )
}

/** Watch face outer clip silhouette declared on `<WatchFace clipShape="...">`. */
public enum class DwfClipShape {
  CIRCLE,
  RECTANGLE,
  NONE,
}

/** User-customizable setting declared inside `<UserConfigurations>`. */
@Immutable
public sealed interface DwfUserConfiguration {
  public val id: String
  public val displayName: String?
  public val screenReaderText: String?

  @Immutable
  public data class ColorConfiguration(
    override val id: String,
    override val displayName: String? = null,
    override val screenReaderText: String? = null,
    public val defaultValue: String? = null,
    public val options: List<ColorOption> = emptyList(),
  ) : DwfUserConfiguration {
    @Immutable
    public data class ColorOption(
      public val id: String,
      public val displayName: String? = null,
      public val colors: List<Int> = emptyList(),
    )
  }

  @Immutable
  public data class ListConfiguration(
    override val id: String,
    override val displayName: String? = null,
    override val screenReaderText: String? = null,
    public val defaultValue: String? = null,
    public val options: List<ListOption> = emptyList(),
  ) : DwfUserConfiguration {
    @Immutable
    public data class ListOption(
      public val id: String,
      public val displayName: String? = null,
      public val icon: String? = null,
    )
  }

  @Immutable
  public data class BooleanConfiguration(
    override val id: String,
    override val displayName: String? = null,
    override val screenReaderText: String? = null,
    public val defaultValue: Boolean = false,
  ) : DwfUserConfiguration

  @Immutable
  public data class PhotosConfiguration(
    override val id: String,
    override val displayName: String? = null,
    override val screenReaderText: String? = null,
    public val configType: String = "SINGLE",
  ) : DwfUserConfiguration
}

/** Bitmap font declaration under `<BitmapFonts>`. */
@Immutable
public data class DwfBitmapFont(
  public val name: String,
  public val glyphs: List<Glyph> = emptyList(),
) {
  @Immutable
  public data class Glyph(
    public val text: String,
    public val resource: String,
    public val width: Float,
    public val height: Float,
  )
}

/** Root `<Scene>` element of a Watch Face Format document. */
@Immutable
public data class DwfScene(
  public val backgroundColor: String? = "#FF000000",
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
  public val children: List<DwfSceneNode> = emptyList(),
)

/** Dynamic property binding `<Transform target="..." value="..." mode="..."/>`. */
@Immutable
public data class DwfTransform(
  public val target: String,
  public val value: String,
  public val mode: String = "TO",
)

/** Mode-specific property override `<Variant mode="AMBIENT" target="..." value="..."/>`. */
@Immutable
public data class DwfVariant(
  public val mode: String,
  public val target: String,
  public val value: String,
)

/**
 * Parallax tilt parameters `<Gyro x="..." y="..." scaleX="..." scaleY="..." angle="..."
 * alpha="..."/>`.
 */
@Immutable
public data class DwfGyro(
  public val x: Float = 0f,
  public val y: Float = 0f,
  public val scaleX: Float = 0f,
  public val scaleY: Float = 0f,
  public val angle: Float = 0f,
  public val alpha: Float = 0f,
)

/** Tap launch target `<Launch target="..."/>`. */
@Immutable
public data class DwfLaunch(
  public val target: String,
)

/** Spatial layout properties shared by positioned DWF scene nodes. */
@Immutable
public data class DwfNodeGeometry(
  public val name: String? = null,
  public val x: Float = 0f,
  public val y: Float = 0f,
  public val width: Float = 0f,
  public val height: Float = 0f,
  public val pivotX: Float = 0.5f,
  public val pivotY: Float = 0.5f,
  public val angle: Float = 0f,
  public val scaleX: Float = 1f,
  public val scaleY: Float = 1f,
  public val alpha: Int = 255,
  public val tintColor: String? = null,
  public val renderMode: DwfRenderMode = DwfRenderMode.SOURCE,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
  public val gyro: DwfGyro? = null,
  public val launch: DwfLaunch? = null,
)

/** Render compositing mode for scene nodes. */
public enum class DwfRenderMode {
  SOURCE,
  MASK,
  ALL,
}

/** Node inside a DWF `<Scene>`. */
@Immutable
public sealed interface DwfSceneNode {
  @Immutable
  public data class Group(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val children: List<DwfSceneNode> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class PartDraw(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val shapes: List<DwfDrawShape> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class PartText(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val text: DwfTextSpec? = null,
    public val textCircular: DwfTextCircularSpec? = null,
  ) : DwfSceneNode

  @Immutable
  public data class PartImage(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val resource: String? = null,
    public val imageFilters: List<String> = emptyList(),
    public val resourceChoices: List<String> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class PartAnimatedImage(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val resource: String? = null,
    public val thumbnail: String? = null,
    public val format: String = "IMAGE",
  ) : DwfSceneNode

  @Immutable
  public data class AnalogClock(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val hands: List<DwfClockHand> = emptyList(),
    public val children: List<DwfSceneNode> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class DigitalClock(
    public val id: String? = null,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val timeTexts: List<DwfTimeText> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class Condition(
    public val branches: List<Branch> = emptyList(),
    public val expressions: Map<String, String> = emptyMap(),
  ) : DwfSceneNode {
    @Immutable
    public data class Branch(
      /** Expression name or inline expression, or `null` for `<Default>`. */
      public val expression: String?,
      public val children: List<DwfSceneNode>,
    )
  }

  @Immutable
  public data class ListConfigurationSwitch(
    public val id: String,
    public val options: List<OptionBranch> = emptyList(),
  ) : DwfSceneNode {
    @Immutable
    public data class OptionBranch(
      public val id: String,
      public val children: List<DwfSceneNode>,
    )
  }

  @Immutable
  public data class BooleanConfigurationSwitch(
    public val id: String,
    public val trueChildren: List<DwfSceneNode> = emptyList(),
    public val falseChildren: List<DwfSceneNode> = emptyList(),
  ) : DwfSceneNode

  @Immutable
  public data class ComplicationSlot(
    public val slotId: Int,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
    public val supportedTypes: List<String> = emptyList(),
    public val isCustomizable: Boolean = true,
    public val defaultSystemProvider: String? = null,
    public val defaultSystemProviderType: String? = null,
    public val boundingShape: DwfBoundingShape? = null,
    public val complications: List<ComplicationVariant> = emptyList(),
  ) : DwfSceneNode {
    @Immutable
    public data class ComplicationVariant(
      public val type: String,
      public val children: List<DwfSceneNode>,
    )
  }

  @Immutable
  public data class Reference(
    public val name: String,
    public val source: String,
    public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
  ) : DwfSceneNode
}

/** Complication hit-test bounding box/oval/arc/round-rect. */
@Immutable
public data class DwfBoundingShape(
  public val kind: String,
  public val x: Float = 0f,
  public val y: Float = 0f,
  public val width: Float = 0f,
  public val height: Float = 0f,
  public val outlinePadding: Float = 0f,
)

/** Clock hand inside `<AnalogClock>`. */
@Immutable
public data class DwfClockHand(
  public val kind: HandKind,
  public val resource: String? = null,
  public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
  public val sweepFrequencyHz: Int? = null,
  public val children: List<DwfSceneNode> = emptyList(),
) {
  public enum class HandKind {
    HOUR,
    MINUTE,
    SECOND,
  }
}

/** `<TimeText>` inside `<DigitalClock>`. */
@Immutable
public data class DwfTimeText(
  public val format: String = "HH:mm",
  public val hourFormat: DwfHourFormat = DwfHourFormat.SYNC_TO_DEVICE,
  public val align: DwfHorizontalAlign = DwfHorizontalAlign.START,
  public val geometry: DwfNodeGeometry = DwfNodeGeometry(),
  public val font: DwfFontSpec = DwfFontSpec(),
  public val bitmapFont: DwfBitmapFontUsage? = null,
)

public enum class DwfHourFormat {
  SYNC_TO_DEVICE,
  TWELVE,
  TWENTY_FOUR,
}

public enum class DwfHorizontalAlign {
  START,
  CENTER,
  END,
}

public enum class DwfVerticalAlign {
  TOP,
  CENTER,
  BOTTOM,
}

/** Rectangular `<Text>` block inside `<PartText>`. */
@Immutable
public data class DwfTextSpec(
  public val align: DwfHorizontalAlign = DwfHorizontalAlign.CENTER,
  public val ellipsis: Boolean = false,
  public val maxLines: Int = Int.MAX_VALUE,
  public val elements: List<DwfTextElement> = emptyList(),
  public val font: DwfFontSpec? = null,
  public val bitmapFont: DwfBitmapFontUsage? = null,
)

/** Circular `<TextCircular>` block inside `<PartText>`. */
@Immutable
public data class DwfTextCircularSpec(
  public val centerX: Float,
  public val centerY: Float,
  public val width: Float,
  public val height: Float,
  public val startAngle: Float = 0f,
  public val endAngle: Float = 360f,
  public val direction: DwfArcDirection = DwfArcDirection.CLOCKWISE,
  public val align: DwfHorizontalAlign = DwfHorizontalAlign.CENTER,
  public val ellipsis: Boolean = false,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
  public val elements: List<DwfTextElement> = emptyList(),
  public val font: DwfFontSpec? = null,
  public val bitmapFont: DwfBitmapFontUsage? = null,
)

/** Font style inside `<Font>` or `<TimeText>`. */
@Immutable
public data class DwfFontSpec(
  public val family: String = "SYNC_TO_DEVICE",
  public val size: Float = 24f,
  public val color: String = "#FFFFFFFF",
  public val weight: String = "NORMAL",
  public val slant: String = "NORMAL",
  public val width: String = "NORMAL",
  public val letterSpacing: Float = 0f,
  public val outlineColor: String? = null,
  public val outlineWidth: Float = 0f,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
  public val elements: List<DwfTextElement> = emptyList(),
)

/** `<BitmapFont family="..." size="..." color="...">` inside `<Text>` / `<TimeText>`. */
@Immutable
public data class DwfBitmapFontUsage(
  public val family: String,
  public val size: Float = 24f,
  public val color: String? = null,
  public val elements: List<DwfTextElement> = emptyList(),
)

/** Rich text child element inside `<Font>`, `<BitmapFont>`, `<Text>`, or `<TextCircular>`. */
@Immutable
public sealed interface DwfTextElement {
  @Immutable public data class Literal(public val text: String) : DwfTextElement

  @Immutable public data class Parameter(public val expression: String) : DwfTextElement

  @Immutable
  public data class Template(
    public val format: String,
    public val parameters: List<String>,
  ) : DwfTextElement

  @Immutable public data class Upper(public val elements: List<DwfTextElement>) : DwfTextElement

  @Immutable public data class Lower(public val elements: List<DwfTextElement>) : DwfTextElement

  @Immutable
  public data class Span(
    public val font: DwfFontSpec,
    public val elements: List<DwfTextElement>,
  ) : DwfTextElement
}

/** Vector drawing primitive + fill/stroke styles inside `<PartDraw>`. */
@Immutable
public data class DwfDrawShape(
  public val primitive: DwfDrawPrimitive,
  public val fills: List<DwfFill> = emptyList(),
  public val strokes: List<DwfStroke> = emptyList(),
  public val weightedElements: List<DwfWeightedElement> = emptyList(),
  public val weightedStrokes: List<DwfWeightedStroke> = emptyList(),
)

/** `<WeightedStroke colors="..." weights="..." thickness="..." cap="..." interpolate="..."/>`. */
@Immutable
public data class DwfWeightedStroke(
  public val colors: String = "#FFFFFFFF",
  public val weights: String? = null,
  public val thickness: Float = 1f,
  public val cap: DwfStrokeCap = DwfStrokeCap.BUTT,
  public val interpolate: String? = null,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
)

@Immutable
public data class DwfWeightedElement(
  public val weight: Float,
  public val color: String,
  public val transforms: List<DwfTransform> = emptyList(),
)

/** Geometric primitive inside `<Shape>` / `<PartDraw>`. */
@Immutable
public sealed interface DwfDrawPrimitive {
  public val transforms: List<DwfTransform>
  public val variants: List<DwfVariant>

  @Immutable
  public data class Line(
    public val startX: Float,
    public val startY: Float,
    public val endX: Float,
    public val endY: Float,
    override val transforms: List<DwfTransform> = emptyList(),
    override val variants: List<DwfVariant> = emptyList(),
  ) : DwfDrawPrimitive

  @Immutable
  public data class Arc(
    public val centerX: Float,
    public val centerY: Float,
    public val width: Float,
    public val height: Float,
    public val startAngle: Float,
    public val endAngle: Float,
    public val direction: DwfArcDirection = DwfArcDirection.CLOCKWISE,
    override val transforms: List<DwfTransform> = emptyList(),
    override val variants: List<DwfVariant> = emptyList(),
  ) : DwfDrawPrimitive

  @Immutable
  public data class Rectangle(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    override val transforms: List<DwfTransform> = emptyList(),
    override val variants: List<DwfVariant> = emptyList(),
  ) : DwfDrawPrimitive

  @Immutable
  public data class RoundRectangle(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    public val cornerRadiusX: Float,
    public val cornerRadiusY: Float,
    override val transforms: List<DwfTransform> = emptyList(),
    override val variants: List<DwfVariant> = emptyList(),
  ) : DwfDrawPrimitive

  @Immutable
  public data class Ellipse(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    override val transforms: List<DwfTransform> = emptyList(),
    override val variants: List<DwfVariant> = emptyList(),
  ) : DwfDrawPrimitive
}

public enum class DwfArcDirection {
  CLOCKWISE,
  COUNTER_CLOCKWISE,
}

/** `<Fill color="...">` with optional gradient shader and dynamic transforms. */
@Immutable
public data class DwfFill(
  public val color: String = "#FFFFFFFF",
  public val gradient: DwfGradient? = null,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
)

/**
 * `<Stroke color="..." thickness="..." cap="...">` with optional gradient shader and transforms.
 */
@Immutable
public data class DwfStroke(
  public val color: String = "#FFFFFFFF",
  public val thickness: Float = 1f,
  public val cap: DwfStrokeCap = DwfStrokeCap.BUTT,
  public val dashIntervals: List<Float> = emptyList(),
  public val gradient: DwfGradient? = null,
  public val transforms: List<DwfTransform> = emptyList(),
  public val variants: List<DwfVariant> = emptyList(),
)

public enum class DwfStrokeCap {
  BUTT,
  ROUND,
  SQUARE,
}

/** Gradient shader inside `<Fill>` or `<Stroke>`. */
@Immutable
public sealed interface DwfGradient {
  public val colors: List<String>
  public val positions: List<Float>?
  public val transforms: List<DwfTransform>

  @Immutable
  public data class Linear(
    public val startX: Float,
    public val startY: Float,
    public val endX: Float,
    public val endY: Float,
    override val colors: List<String>,
    override val positions: List<Float>? = null,
    override val transforms: List<DwfTransform> = emptyList(),
  ) : DwfGradient

  @Immutable
  public data class Radial(
    public val centerX: Float,
    public val centerY: Float,
    public val radius: Float,
    override val colors: List<String>,
    override val positions: List<Float>? = null,
    override val transforms: List<DwfTransform> = emptyList(),
  ) : DwfGradient

  @Immutable
  public data class Sweep(
    public val centerX: Float,
    public val centerY: Float,
    public val startAngle: Float = 0f,
    public val endAngle: Float = 360f,
    public val direction: DwfArcDirection = DwfArcDirection.CLOCKWISE,
    override val colors: List<String>,
    override val positions: List<Float>? = null,
    override val transforms: List<DwfTransform> = emptyList(),
  ) : DwfGradient
}
