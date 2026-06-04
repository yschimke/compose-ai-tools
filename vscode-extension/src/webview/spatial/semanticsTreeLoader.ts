// Consumer-side loading for the `SpatialSemanticsTree` wire contract
// ([../shared/spatialSemanticsTree.ts](../shared/spatialSemanticsTree.ts)) — the 3D-over-2D tree the
// recorder (`SubspaceSceneRecorder.recordTree`) produces. The spatial viewer already renders the
// `SpatialScene` (panel geometry + screenshot textures + camera); this module turns the companion
// semantics tree into the per-panel **2D wireframe overlay** the viewer composites onto each panel's
// textured face — keyed by panel id so it lines up with the scene's panels.
//
// Pure — no three.js, no DOM — so it runs under the Mocha unit suite. The actual canvas
// compositing lives in the viewer (spatialViewer.ts); here we only derive the boxes.

import { type OverlayBox } from "../preview/components/BoxOverlay";
import { parseBounds } from "../preview/cardData";
import {
    isSpatialSemanticsTree,
    SPATIAL_SEMANTICS_TREE_VERSION,
    type SemanticsTreeNode,
    type SpatialSemanticsNode,
    type SpatialSemanticsTree,
} from "../shared/spatialSemanticsTree";

/** Thrown when a payload fails the contract guard or version check. */
export class SpatialSemanticsTreeParseError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "SpatialSemanticsTreeParseError";
    }
}

/**
 * Validate an untyped value against the contract and normalise it. Throws
 * {@link SpatialSemanticsTreeParseError} on a failed {@link isSpatialSemanticsTree} guard or a
 * `version` mismatch (the contract says `version` *must equal*
 * {@link SPATIAL_SEMANTICS_TREE_VERSION}). Normalisation is shallow — additive optional fields at the
 * same version pass through untouched.
 */
export function parseSpatialSemanticsTree(raw: unknown): SpatialSemanticsTree {
    if (
        typeof raw === "object" &&
        raw !== null &&
        typeof (raw as { version?: unknown }).version === "number" &&
        (raw as { version: number }).version !== SPATIAL_SEMANTICS_TREE_VERSION
    ) {
        throw new SpatialSemanticsTreeParseError(
            `unsupported SpatialSemanticsTree version ${(raw as { version: number }).version}: ` +
                `this viewer was built against version ${SPATIAL_SEMANTICS_TREE_VERSION}`,
        );
    }
    if (!isSpatialSemanticsTree(raw)) {
        throw new SpatialSemanticsTreeParseError(
            "not a SpatialSemanticsTree: expected an object with units:'dp', version " +
                `${SPATIAL_SEMANTICS_TREE_VERSION}, and a root node`,
        );
    }
    return raw;
}

/** Convenience wrapper: parse a JSON string into a {@link SpatialSemanticsTree}. */
export function parseSpatialSemanticsTreeJson(
    text: string,
): SpatialSemanticsTree {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch (err) {
        throw new SpatialSemanticsTreeParseError(
            `invalid JSON: ${(err as Error).message}`,
        );
    }
    return parseSpatialSemanticsTree(parsed);
}

/**
 * Every content-hosting node in the tree (a `panel`/`orbiter` carrying `panelContent`), in
 * depth-first order. Pure container nodes (`subspaceRoot`/`row`/`column`/`box`) are walked through
 * but not returned; a container that *also* hosts content (rare) is included.
 */
export function flattenPanels(
    tree: SpatialSemanticsTree,
): SpatialSemanticsNode[] {
    const out: SpatialSemanticsNode[] = [];
    const walk = (node: SpatialSemanticsNode): void => {
        if (node.panelContent) out.push(node);
        for (const child of node.children ?? []) walk(child);
    };
    walk(tree.root);
    return out;
}

/** A panel's 2D wireframe: its content coordinate-space size plus the boxes to draw over it. */
export interface PanelWireframe {
    /** Matches the `SpatialScene` panel id (both derive from the panel's testTag). */
    panelId: string;
    /**
     * The content coordinate-space extent (from the `panelContent` root's `boundsInRoot`). The
     * viewer scales boxes from this space onto the panel texture's natural pixel size.
     */
    contentSize: { width: number; height: number };
    /** Wireframe boxes in content coordinate space, ready for the overlay/compositor. */
    boxes: OverlayBox[];
}

/** Walk a 2D semantics subtree into flat {@link OverlayBox}es, parsing each node's bounds. */
function boxesFromSemantics(
    panelId: string,
    root: SemanticsTreeNode,
): OverlayBox[] {
    const out: OverlayBox[] = [];
    const walk = (node: SemanticsTreeNode): void => {
        const bounds = parseBounds(node.boundsInRoot);
        if (bounds) {
            out.push({
                // Namespaced by panel so ids stay unique across the whole tree.
                id: `${panelId}:${node.nodeId}`,
                bounds,
                // Mirror the 2D inspector overlay: a merge boundary is the one
                // distinction worth surfacing; everything else is plain info.
                level:
                    node.mergeMode === "mergeDescendants" ? "warning" : "info",
                tooltip: wireframeTooltip(node),
            });
        }
        for (const child of node.children ?? []) walk(child);
    };
    walk(root);
    return out;
}

/** A short hover label — the most identifying text the node carries, plus role/tag when present. */
function wireframeTooltip(node: SemanticsTreeNode): string | undefined {
    const primary = node.label ?? node.text ?? node.testTag;
    const role = node.role ? `[${node.role}]` : undefined;
    const parts = [primary, role].filter((p): p is string => !!p);
    return parts.length > 0 ? parts.join(" ") : undefined;
}

/**
 * Per-panel wireframes keyed by panel id, for every panel that carries 2D content. The viewer looks
 * each panel's entry up by its `SpatialScene` panel id and composites the boxes onto its screenshot
 * face; a panel with no entry simply renders its plain texture.
 */
export function panelWireframesById(
    tree: SpatialSemanticsTree,
): Map<string, PanelWireframe> {
    const byId = new Map<string, PanelWireframe>();
    for (const panel of flattenPanels(tree)) {
        const content = panel.panelContent;
        if (!content) continue;
        const rootBounds = parseBounds(content.boundsInRoot);
        // The content root's bounds define the coordinate space the child boxes live in. Without a
        // parseable root extent we can't scale the boxes onto the texture, so skip the panel's
        // overlay (its plain screenshot still renders).
        if (!rootBounds) continue;
        const contentSize = {
            width: rootBounds.right - rootBounds.left,
            height: rootBounds.bottom - rootBounds.top,
        };
        if (contentSize.width <= 0 || contentSize.height <= 0) continue;
        byId.set(panel.id, {
            panelId: panel.id,
            contentSize,
            boxes: boxesFromSemantics(panel.id, content),
        });
    }
    return byId;
}
