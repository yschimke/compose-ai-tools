// GENERATED FILE — DO NOT EDIT.
// Source of truth: schema/spatial-scene.schema.json
// Regenerate: node scripts/codegen/gen-spatial-scene.mjs (CI checks with --check).

package ee.schimke.composeai.xr

import kotlinx.serialization.Serializable

/**
 * The wire contract between the offline renderer (producer) and the webview's 3D spatial-layout
 * viewer (consumer). All linear quantities are dp; axes are right-handed (+x right, +y up, +z
 * toward the viewer); rotation is a unit quaternion. Prose spec:
 * docs/design/SPATIAL_SCENE_CONTRACT.md.
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
  /** Human-readable label for overlays; not required for rendering. */
  val label: String? = null,
  val poseInRoot: SpatialPose,
  val sizeDp: SizeDp,
  /** Path to the panel's 2D-content PNG, relative to the scene file (or a resolvable URI). */
  val texture: String,
  /** Id of the containing panel/group, or null/omitted for top-level panels. */
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
  /** Look-at point in dp. */
  val target: Vec3,
  /** Camera distance from `target`, in dp. */
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
  /**
   * Gradient glow intensity; overrides the preset's glow. Consumed by the native compositor's room
   * backdrop.
   */
  val glow: Double? = null,
)

/** The full scene the 3D viewer renders. [version] must equal [SPATIAL_SCENE_VERSION]. */
@Serializable
public data class SpatialScene(
  /** Bumped on breaking changes. Producers stamp it into `SpatialScene.version`. */
  val version: Int = SPATIAL_SCENE_VERSION,
  /** All linear quantities are dp. */
  val units: String = "dp",
  /** The preview this scene was projected from, if any. */
  val previewId: String? = null,
  val camera: OrbitCamera,
  val panels: List<SpatialPanel>,
  val orbiters: List<OrbiterAffordance> = emptyList(),
  val environment: SpatialEnvironment? = null,
)
