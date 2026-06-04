// Composites a panel's 2D wireframe boxes ([./semanticsTreeLoader.ts]) onto its screenshot face for
// the 3D spatial viewer: the panel quad keeps showing the real render, with the semantics boxes
// drawn over it (the "screenshot + wireframe overlay" panel face). The box geometry is scaled from
// the panel's *content* coordinate space (the `panelContent` root bounds) onto the texture's natural
// pixel size, so it lines up regardless of capture density.
//
// `drawWireframeBoxes` is pure (takes a minimal 2D-context surface) and unit-tested; the three.js
// `composePanelTexture` wrapper just owns the canvas + `CanvasTexture` and is exercised by the
// viewer at runtime.

import * as THREE from "three";
import { type PanelWireframe } from "./semanticsTreeLoader";

/** Stroke colours per overlay level — mirrors the 2D inspector accents (info matches the focus blue). */
const LEVEL_STROKE: Record<string, string> = {
    error: "#ff5c5c",
    warning: "#ffb84a",
    info: "#4a9eff",
};
const DEFAULT_STROKE = "#4a9eff";

/** The minimal 2D-context surface {@link drawWireframeBoxes} needs (so tests pass a fake recorder). */
export interface WireframeContext2D {
    lineWidth: number;
    strokeStyle: string | CanvasGradient | CanvasPattern;
    strokeRect(x: number, y: number, w: number, h: number): void;
}

/**
 * Draw a panel's wireframe boxes, scaled from {@link PanelWireframe.contentSize} onto a target of
 * the given pixel size. Pure — no canvas/DOM/three.js. Boxes are stroked in their level colour (or
 * an explicit `box.color` override); the line width scales gently with the target so it stays
 * visible on high-density captures without overwhelming small panels.
 */
export function drawWireframeBoxes(
    ctx: WireframeContext2D,
    wireframe: PanelWireframe,
    targetWidth: number,
    targetHeight: number,
): void {
    const { width: contentW, height: contentH } = wireframe.contentSize;
    if (contentW <= 0 || contentH <= 0) return;
    const sx = targetWidth / contentW;
    const sy = targetHeight / contentH;
    ctx.lineWidth = Math.max(2, Math.round(targetHeight / 240));
    for (const box of wireframe.boxes) {
        ctx.strokeStyle =
            box.color ?? LEVEL_STROKE[box.level ?? "info"] ?? DEFAULT_STROKE;
        ctx.strokeRect(
            box.bounds.left * sx,
            box.bounds.top * sy,
            (box.bounds.right - box.bounds.left) * sx,
            (box.bounds.bottom - box.bounds.top) * sy,
        );
    }
}

/** A drawable image source carrying its natural pixel dimensions (an `HTMLImageElement`, etc.). */
type SizedImageSource = CanvasImageSource & { width?: number; height?: number };

/**
 * Composite a loaded panel screenshot with its wireframe overlay into a {@link THREE.CanvasTexture}.
 * Returns `null` when there's no DOM (tests / SSR) or the image has no intrinsic size — the caller
 * then falls back to the plain screenshot texture, so a missing overlay degrades gracefully.
 */
export function composePanelTexture(
    image: SizedImageSource,
    wireframe: PanelWireframe,
): THREE.CanvasTexture | null {
    if (typeof document === "undefined") return null;
    const width = typeof image.width === "number" ? image.width : 0;
    const height = typeof image.height === "number" ? image.height : 0;
    if (width <= 0 || height <= 0) return null;

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;

    ctx.drawImage(image, 0, 0, width, height);
    drawWireframeBoxes(ctx, wireframe, width, height);

    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    return texture;
}
