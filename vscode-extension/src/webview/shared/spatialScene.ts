// GENERATED FILE — DO NOT EDIT.
// Source of truth: schema/spatial-scene.schema.json
// Regenerate: node scripts/codegen/gen-spatial-scene.mjs (CI checks with --check).

/**
 * The wire contract between the offline renderer (producer) and the webview's 3D spatial-layout viewer (consumer). All linear quantities are dp; axes are right-handed (+x right, +y up, +z toward the viewer); rotation is a unit quaternion. Prose spec: docs/design/SPATIAL_SCENE_CONTRACT.md.
 */
export const SPATIAL_SCENE_VERSION = 1;

/**
 * A point or vector, in dp.
 */
export interface Vec3 {
    x: number;
    y: number;
    z: number;
}

/**
 * A unit quaternion; identity is `(0, 0, 0, 1)`.
 */
export interface Quat {
    x: number;
    y: number;
    z: number;
    w: number;
}

/**
 * A rigid transform in the subspace root's frame: `translation` in dp, `rotation` a unit
 * quaternion.
 */
export interface SpatialPose {
    translation: Vec3;
    rotation: Quat;
}

/**
 * Panel extent in dp (the layout output, not the texture's pixel size).
 */
export interface SizeDp {
    width: number;
    height: number;
}

/**
 * A spatial panel: a flat quad hosting 2D Compose content.
 */
export interface SpatialPanel {
    id: string;
    /**
     * Human-readable label for overlays; not required for rendering.
     */
    label?: string;
    poseInRoot: SpatialPose;
    sizeDp: SizeDp;
    /**
     * Path to the panel's 2D-content PNG, relative to the scene file (or a resolvable URI).
     */
    texture: string;
    /**
     * Id of the containing panel/group, or null/omitted for top-level panels.
     */
    parentId?: string | null;
}

/**
 * An Orbiter affordance — a control strip anchored to a panel edge. `edge` is
 * top/bottom/start/end.
 */
export interface OrbiterAffordance {
    id: string;
    label?: string;
    edge: "top" | "bottom" | "start" | "end";
    poseInRoot: SpatialPose;
    sizeDp: SizeDp;
    texture: string;
}

/**
 * Default viewing camera. Only `kind = "orbit"` is defined today.
 */
export interface OrbitCamera {
    kind: "orbit";
    /**
     * Look-at point in dp.
     */
    target: Vec3;
    /**
     * Camera distance from `target`, in dp.
     */
    distance: number;
    yawDeg: number;
    pitchDeg: number;
}

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
export interface SpatialEnvironment {
    kind: "color" | "skybox" | "gradient";
    color?: string;
    texture?: string;
    /**
     * Named gradient preset (e.g. `"warm-room"`, `"studio-dark"`); ignored when `kind == "color"`.
     */
    preset?: string;
    /**
     * Gradient colour straight up (`#RRGGBB`); overrides the preset.
     */
    sky?: string;
    /**
     * Gradient colour at eye level (`#RRGGBB`); overrides the preset. Doubles as the clear colour.
     */
    horizon?: string;
    /**
     * Gradient colour straight down (`#RRGGBB`); overrides the preset and enables a 3-stop floor.
     */
    floor?: string;
    /**
     * Gradient glow intensity; overrides the preset's glow. Consumed by the native compositor's room backdrop.
     */
    glow?: number;
}

/**
 * The full scene the 3D viewer renders. [version] must equal [SPATIAL_SCENE_VERSION].
 */
export interface SpatialScene {
    /**
     * Bumped on breaking changes. Producers stamp it into `SpatialScene.version`.
     */
    version: number;
    /**
     * All linear quantities are dp.
     */
    units: "dp";
    /**
     * The preview this scene was projected from, if any.
     */
    previewId?: string;
    camera: OrbitCamera;
    panels: SpatialPanel[];
    orbiters?: OrbiterAffordance[];
    environment?: SpatialEnvironment | null;
}

/**
 * Minimal structural guard — rejects payloads the consumer can't safely render: a `version`
 * other than {@link SPATIAL_SCENE_VERSION}, or missing required fields. Shallow otherwise; the viewer
 * should still tolerate missing optional fields.
 */
export function isSpatialScene(value: unknown): value is SpatialScene {
    if (typeof value !== "object" || value === null) {
        return false;
    }
    const scene = value as Partial<SpatialScene>;
    return (
        scene.units === "dp" &&
        scene.version === SPATIAL_SCENE_VERSION &&
        Array.isArray(scene.panels) &&
        typeof scene.camera === "object" &&
        scene.camera !== null
    );
}
