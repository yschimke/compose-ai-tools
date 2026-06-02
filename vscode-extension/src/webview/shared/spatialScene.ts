// SpatialScene — the wire contract between the offline renderer (producer) and the webview's 3D
// spatial-layout viewer (consumer). The renderer (`:renderer-xr`, Phase A) recovers each panel's
// pose/size from the Compose-XR subspace layout and renders its 2D content to a PNG; the webview
// loads this JSON + those textures and draws the panels as textured quads in a WebGL scene.
//
// This file is the single source of truth for the TypeScript side; the prose spec (units, axes,
// versioning, producer mapping) lives in docs/design/SPATIAL_SCENE_CONTRACT.md. Keep the two in
// sync. Bump SPATIAL_SCENE_VERSION on any breaking shape change.

/** Bumped on breaking changes to the shapes below. Producers stamp it into `SpatialScene.version`. */
export const SPATIAL_SCENE_VERSION = 1;

/** A point or vector, in density-independent pixels (dp). See the spec for the axis convention. */
export interface Vec3 {
    x: number;
    y: number;
    z: number;
}

/** A unit quaternion. Identity (no rotation) is `{ x: 0, y: 0, z: 0, w: 1 }`. */
export interface Quat {
    x: number;
    y: number;
    z: number;
    w: number;
}

/**
 * A rigid transform in the subspace root's frame. Right-handed: +x right, +y up, +z toward the
 * viewer (camera looks down −z). `translation` is in dp; `rotation` is a unit quaternion.
 */
export interface SpatialPose {
    translation: Vec3;
    rotation: Quat;
}

/** Panel size in dp (the layout output, not the texture's pixel dimensions). */
export interface SizeDp {
    width: number;
    height: number;
}

/** A spatial panel: a flat, axis-aligned-by-default quad hosting 2D Compose content. */
export interface SpatialPanel {
    /** Stable id (the subspace node's testTag / semantics id). */
    id: string;
    /** Human-readable label for overlays; not required for rendering. */
    label?: string;
    /** Pose in the subspace root frame. */
    poseInRoot: SpatialPose;
    /** Panel extent in dp. */
    sizeDp: SizeDp;
    /**
     * Path to the panel's 2D-content PNG, relative to the scene file's directory (or an absolute
     * URI the consumer can resolve to a webview resource). The image is mapped onto the quad.
     */
    texture: string;
    /** Id of the containing panel/group, or null/omitted for top-level panels. */
    parentId?: string | null;
}

/** An Orbiter affordance — a control strip anchored to a panel edge in Full Space. */
export interface OrbiterAffordance {
    id: string;
    label?: string;
    edge: "top" | "bottom" | "start" | "end";
    poseInRoot: SpatialPose;
    sizeDp: SizeDp;
    texture: string;
}

/** Default viewing camera. Only orbit is defined today. */
export interface OrbitCamera {
    kind: "orbit";
    /** Look-at point in dp. */
    target: Vec3;
    /** Camera distance from `target`, in dp. */
    distance: number;
    yawDeg: number;
    pitchDeg: number;
}

/** Optional scene backdrop. */
export interface SpatialEnvironment {
    kind: "color" | "skybox";
    /** `#RRGGBB` for `kind: "color"`. */
    color?: string;
    /** Texture path/URI for `kind: "skybox"`. */
    texture?: string;
}

/** The full scene the 3D viewer renders. */
export interface SpatialScene {
    /** Must equal {@link SPATIAL_SCENE_VERSION} the consumer was built against. */
    version: number;
    /** All linear quantities are dp. */
    units: "dp";
    /** The preview this scene was projected from, if any. */
    previewId?: string;
    camera: OrbitCamera;
    panels: SpatialPanel[];
    orbiters?: OrbiterAffordance[];
    environment?: SpatialEnvironment | null;
}

/**
 * Minimal structural guard — enough to reject obviously-wrong payloads (wrong version, missing
 * panels) before the viewer touches them. It is intentionally shallow; the viewer should still
 * tolerate missing optional fields.
 */
export function isSpatialScene(value: unknown): value is SpatialScene {
    if (typeof value !== "object" || value === null) {
        return false;
    }
    const scene = value as Partial<SpatialScene>;
    return (
        scene.units === "dp" &&
        typeof scene.version === "number" &&
        Array.isArray(scene.panels) &&
        typeof scene.camera === "object" &&
        scene.camera !== null
    );
}
