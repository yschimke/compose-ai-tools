// SpatialSemanticsTree — the unified "3D-over-2D" wire contract: one tree whose top levels are 3D
// (the subspace layout) and whose every panel carries a normal 2D semantics tree. The TypeScript
// mirror of the Kotlin DTO in
// api/preview-data-api/.../xr/SpatialSemanticsTree.kt (see its KDoc for the shape spec).
// Both languages decode the same committed fixture
// (preview-harness/fixtures/spatial-semantics-tree/tree.json) so the shapes stay locked. Bump
// SPATIAL_SEMANTICS_TREE_VERSION on any breaking change.
//
// Shares the 3D primitives (Vec3, Quat, SpatialPose) with the SpatialScene contract — re-exported
// from spatialScene.ts so there is one definition.

import { type SpatialPose } from "./spatialScene";

export { type Quat, type SpatialPose, type Vec3 } from "./spatialScene";

/** Bumped on breaking changes to the shapes below. Producers stamp it into `SpatialSemanticsTree.version`. */
export const SPATIAL_SEMANTICS_TREE_VERSION = 1;

/** Box extent in dp; `depth` is 0 for a flat panel and non-zero only for a `SpatialBox`. */
export interface Size3dDp {
    width: number;
    height: number;
    /** 0 for flat panels; optional/absent decodes as 0. */
    depth?: number;
}

/** The kind of a {@link SpatialSemanticsNode} — a 3D container type, or a content-hosting panel. */
export type SpatialSemanticsKind =
    | "subspaceRoot"
    | "row"
    | "column"
    | "box"
    | "panel"
    | "orbiter";

/**
 * The 2D semantics node a panel hosts. A structural mirror of the Kotlin `ComposeSemanticsNode`
 * (data/layoutinspector/.../ComposeSemanticsModels.kt) and the panel-side copy in
 * `preview/inspectionPresenters.ts` — kept self-contained here so `shared/` stays leaf (it must not
 * import the webview-heavy presenter module). Only the fields the wireframe/overlay needs are
 * required; the rest are optional so a richer producer payload still decodes.
 */
export interface SemanticsTreeNode {
    nodeId: string;
    /** "left,top,right,bottom" in the panel's root-pixel space. */
    boundsInRoot: string;
    label?: string;
    text?: string;
    role?: string;
    testTag?: string;
    /** "mergeDescendants" | "clearAndSet" | undefined. */
    mergeMode?: string;
    clickable?: boolean;
    children?: SemanticsTreeNode[];
}

/**
 * A node in the spatial semantics tree. Container kinds (`row`/`column`/`box`/`subspaceRoot`) carry
 * `children`; a `panel`/`orbiter` leaf carries `panelContent` (its 2D semantics tree). A node may
 * carry both when a panel nests further subspace content.
 */
export interface SpatialSemanticsNode {
    id: string;
    kind: SpatialSemanticsKind;
    label?: string;
    poseInRoot: SpatialPose;
    sizeDp: Size3dDp;
    /** The 2D semantics tree of this panel's hosted content (absent for pure container nodes). */
    panelContent?: SemanticsTreeNode | null;
    children?: SpatialSemanticsNode[];
}

/** The full 3D-over-2D tree for one preview. */
export interface SpatialSemanticsTree {
    /** Must equal {@link SPATIAL_SEMANTICS_TREE_VERSION} the consumer was built against. */
    version: number;
    /** All linear quantities are dp. */
    units: "dp";
    /** The preview this tree was projected from, if any. */
    previewId?: string;
    root: SpatialSemanticsNode;
}

/**
 * Minimal structural guard — enough to reject payloads the consumer can't safely render: a
 * `version` other than {@link SPATIAL_SEMANTICS_TREE_VERSION} (bumped only on breaking shape
 * changes, so any mismatch means an incompatible shape), or a missing root. Shallow otherwise; the
 * viewer tolerates missing optional fields.
 */
export function isSpatialSemanticsTree(
    value: unknown,
): value is SpatialSemanticsTree {
    if (typeof value !== "object" || value === null) {
        return false;
    }
    const tree = value as Partial<SpatialSemanticsTree>;
    return (
        tree.units === "dp" &&
        tree.version === SPATIAL_SEMANTICS_TREE_VERSION &&
        typeof tree.root === "object" &&
        tree.root !== null
    );
}
