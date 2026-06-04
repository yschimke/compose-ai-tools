package ee.schimke.composeai.xr

import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of the `SpatialScene` wire contract — the format the offline renderer
 * (`:renderer-xr`, the producer) emits and the VS Code webview's 3D spatial-layout viewer (the
 * consumer) reads.
 *
 * The **authoritative** definition is the TypeScript source
 * (`vscode-extension/src/webview/shared/spatialScene.ts`) plus the prose spec
 * (`docs/design/SPATIAL_SCENE_CONTRACT.md`). These data classes must stay byte-compatible with it:
 * property names are the JSON keys, so they match the TS field names exactly. `SpatialSceneTest`
 * deserializes the committed fixture to keep the two languages locked together — if you change a
 * shape here, change it there too (and bump [SPATIAL_SCENE_VERSION]).
 *
 * Conventions (see the spec): all linear quantities are **dp**; axes are right-handed (+x right, +y
 * up, +z toward the viewer); `rotation` is a unit quaternion.
 */
public const val SPATIAL_SCENE_VERSION: Int = 1

/** A point or vector, in dp. */
@Serializable public data class Vec3(val x: Double, val y: Double, val z: Double)

/** A unit quaternion; identity is `(0, 0, 0, 1)`. */
@Serializable public data class Quat(val x: Double, val y: Double, val z: Double, val w: Double)

/**
 * A rigid transform in the subspace root's frame: `translation` in dp, `rotation` a unit
 * quaternion.
 */
@Serializable public data class SpatialPose(val translation: Vec3, val rotation: Quat)

/** Panel extent in dp (the layout output, not the texture's pixel size). */
@Serializable public data class SizeDp(val width: Int, val height: Int)

/** A spatial panel: a flat quad hosting 2D Compose content. */
@Serializable
public data class SpatialPanel(
  val id: String,
  val label: String? = null,
  val poseInRoot: SpatialPose,
  val sizeDp: SizeDp,
  /** Path to the panel's 2D-content PNG, relative to the scene file (or a resolvable URI). */
  val texture: String,
  val parentId: String? = null,
)

/**
 * An Orbiter affordance — a control strip anchored to a panel edge. `edge` is top/bottom/start/end.
 */
@Serializable
public data class OrbiterAffordance(
  val id: String,
  val label: String? = null,
  val edge: String,
  val poseInRoot: SpatialPose,
  val sizeDp: SizeDp,
  val texture: String,
)

/** Default viewing camera. Only `kind = "orbit"` is defined today. */
@Serializable
public data class OrbitCamera(
  val kind: String = "orbit",
  val target: Vec3,
  val distance: Double,
  val yawDeg: Double,
  val pitchDeg: Double,
)

/**
 * Optional scene backdrop. `kind` is "color" (`#RRGGBB` in [color]) or "skybox" ([texture]).
 *
 * For gradient backdrops (any `kind` other than "color"), the offline compositor supports **named
 * presets** ([preset], e.g. `"warm-room"` — the default — or `"studio-dark"`) plus explicit
 * gradient stops that **override** the chosen preset: [sky] (straight up), [horizon] (eye level),
 * and [floor] (straight down; its presence turns the 2-stop gradient into a 3-stop, room-like one).
 * These knobs are optional; omit them to take the compositor's default `warm-room` backdrop. The
 * compositor's `--environment` CLI flag overrides whatever the scene specifies.
 */
@Serializable
public data class SpatialEnvironment(
  val kind: String,
  val color: String? = null,
  val texture: String? = null,
  /**
   * Named gradient preset (e.g. `"warm-room"`, `"studio-dark"`); ignored when `kind == "color"`.
   */
  val preset: String? = null,
  /** Gradient colour straight up (`#RRGGBB`); overrides the preset. */
  val sky: String? = null,
  /**
   * Gradient colour at eye level (`#RRGGBB`); overrides the preset. Doubles as the clear colour.
   */
  val horizon: String? = null,
  /** Gradient colour straight down (`#RRGGBB`); overrides the preset and enables a 3-stop floor. */
  val floor: String? = null,
)

/** The full scene the 3D viewer renders. [version] must equal [SPATIAL_SCENE_VERSION]. */
@Serializable
public data class SpatialScene(
  val version: Int = SPATIAL_SCENE_VERSION,
  val units: String = "dp",
  val previewId: String? = null,
  val camera: OrbitCamera,
  val panels: List<SpatialPanel>,
  val orbiters: List<OrbiterAffordance> = emptyList(),
  val environment: SpatialEnvironment? = null,
)
