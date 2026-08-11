// CanvasPaintContext: concrete PaintContext that renders to an HTML5 Canvas 2D.

import { PaintContext } from '../core/PaintContext';
import { PaintBundle, intBitsToFloat } from '../core/operations/paint/PaintBundle';
import { isNaNBits, idFromBits, floatToRawIntBits } from '../core/operations/Utils';
import { transpileAgslToGlsl } from '../core/shader/AgslTranspiler';
import { WebGLShaderRenderer } from './shader/WebGLShaderRenderer';
import { RemoteComposeState } from '../core/RemoteComposeState';
import { ensureWebFont, parseFamily, cssQuoted, registerEmbeddedFont } from './WebFonts';
import type { ShaderData } from '../core/operations/ShaderData';
import type { RemoteContext } from '../core/RemoteContext';

/** One font-variation axis of the current paint: an OpenType tag and the value asked for. */
interface FontAxis {
    readonly tag: string;
    readonly value: number;
}

/**
 * The nine `font-stretch` keywords and the percentage each stands for.
 *
 * Canvas is the constraint here, not CSS. The `font` shorthand rejects a `font-stretch` percentage
 * outright — assigning `ctx.font = "25% 32px RF"` leaves the context at its `10px sans-serif`
 * default, i.e. the whole assignment is voided — and `ctx.fontStretch` is an *enum* attribute whose
 * only accepted values are these keywords (`'25%'` logs "not a valid enum value of type
 * CanvasFontStretch" and is ignored). So a `wdth` axis reaches a canvas quantised to these nine
 * steps, and no finer. It is a real narrowing next to the CSS property, which does take a
 * percentage, and next to what the other lanes apply — recorded in the audit doc rather than
 * papered over.
 */
/**
 * The nine keywords `ctx.fontStretch` accepts. Spelled as a union rather than `string` because that
 * property is typed as this same enum in lib.dom: widening it here makes the assignment below a
 * type error, and casting at the assignment would lose the check that these spellings are the ones
 * canvas actually takes — a typo'd step is silently ignored at runtime.
 */
type FontStretchKeyword =
    | 'ultra-condensed' | 'extra-condensed' | 'condensed' | 'semi-condensed' | 'normal'
    | 'semi-expanded' | 'expanded' | 'extra-expanded' | 'ultra-expanded';

const FONT_STRETCH_STEPS: ReadonlyArray<readonly [number, FontStretchKeyword]> = [
    [50, 'ultra-condensed'],
    [62.5, 'extra-condensed'],
    [75, 'condensed'],
    [87.5, 'semi-condensed'],
    [100, 'normal'],
    [112.5, 'semi-expanded'],
    [125, 'expanded'],
    [150, 'extra-expanded'],
    [200, 'ultra-expanded'],
];

/** The keyword whose percentage is closest to [percent]; the face clamps it to its own range. */
export function nearestFontStretch(percent: number): FontStretchKeyword {
    let best = FONT_STRETCH_STEPS[0];
    for (const step of FONT_STRETCH_STEPS) {
        if (Math.abs(step[0] - percent) < Math.abs(best[0] - percent)) best = step;
    }
    return best[1];
}

function argbToRgba(argb: number): string {
    const a = ((argb >>> 24) & 0xFF) / 255;
    const r = (argb >>> 16) & 0xFF;
    const g = (argb >>> 8) & 0xFF;
    const b = argb & 0xFF;
    return `rgba(${r},${g},${b},${a.toFixed(3)})`;
}

/**
 * CSS font stack for an Android typeface id (`0=DEFAULT, 1=SANS_SERIF, 2=SERIF, 3=MONOSPACE`).
 *
 * Each stack names the concrete face Android's own `fonts.xml` resolves the family to, then falls
 * back to the CSS generic. That first name is what makes a browser render match the baked raster:
 * Android's `DEFAULT`/`sans-serif` is **Roboto**, not whatever the host calls `sans-serif` (a
 * headless Linux container typically answers DejaVu or Liberation). Naming only the generic — as
 * this did before — guarantees a different typeface from the snapshot renderer for every string
 * drawn, which reads as a permanent few-percent parity residual that no amount of layout work can
 * close.
 *
 * The concrete names are only a *request*: a page that has not registered the faces falls straight
 * through to the generic and renders exactly as it did before, so this is safe in viewers that ship
 * no fonts. The parity harness (`scripts/design-artifacts/rc-compare.mjs --fonts`) registers them
 * from the same vendored files the snapshot renderer rasterizes with, which is what turns the
 * request into a match. Families are kept in sync with `FAMILY_FILES` in
 * `scripts/design-artifacts/render-fonts-manifest.mjs`.
 *
 * Multi-word names are quoted because this is fed to the canvas `font` shorthand, where a bare
 * `Noto Serif` is a parse error that silently voids the whole assignment.
 */
export function cssFontStackFor(fontType: number): string {
    switch (fontType) {
        case 2: return '"Noto Serif", serif';
        case 3: return '"Droid Sans Mono", monospace';
        // 1 = SANS_SERIF is Android's own name for the Roboto stack, so it and
        // DEFAULT (0, and anything unrecognised) resolve to the same face.
        default: return 'Roboto, sans-serif';
    }
}

/**
 * CSS font stack for a *named* family (`RemoteFontFamily.Named("Orbitron")`), with the default
 * stack behind it.
 *
 * The fallback is the whole safety story: the name is only a request, so a page that could not
 * register the family (no network, a webview CSP, a family Google doesn't serve) renders the same
 * Roboto it rendered before this existed, rather than whatever the host picks for an unknown family.
 *
 * Quoted because this is fed to the canvas `font` shorthand, where a bare multi-word `Space Grotesk`
 * is a parse error that silently voids the entire assignment — leaving the previous font in place,
 * which reads as "the named family was ignored" rather than as the syntax error it is.
 *
 * `cssQuoted` escapes `\` as well as `"`; escaping only the quote would let a family ending in a
 * backslash swallow the rest of the stack, which is the same silent-void failure the quoting exists
 * to prevent.
 */
export function namedFontStack(family: string): string {
    return `"${cssQuoted(family)}", ${cssFontStackFor(0)}`;
}

/** Fallback text size when the paint carries none. */
const DEFAULT_TEXT_SIZE = 14;
/** Ascent / descent as fractions of the text size — roughly Roboto's, i.e. a phone's. */
const ASCENT_RATIO = 0.92;
const DESCENT_RATIO = 0.24;
/** Mean advance per character as a fraction of text size, measured on a device. */
const AVG_ADVANCE = 0.528;

export class CanvasPaintContext extends PaintContext {
    private ctx: CanvasRenderingContext2D;

    // Current paint state
    private color = 'rgba(0,0,0,1)';
    private style = 0; // 0=FILL, 1=STROKE, 2=FILL_AND_STROKE
    private strokeWidth = 1;
    private textSize = 14;
    private alpha = 1;
    private lineCap: CanvasLineCap = 'butt';
    private lineJoin: CanvasLineJoin = 'miter';
    private miterLimit = 10;
    private blendMode: GlobalCompositeOperation = 'source-over';
    private antiAlias = true;
    private filterBitmap = true;
    private letterSpacing = 0;
    private gradientStyle: CanvasGradient | CanvasPattern | null = null;
    private fontFamily = cssFontStackFor(0);
    private fontWeight = 400;
    private fontItalic = false;
    /** The document's font-variation axes for the current paint, resolved to their tag names. */
    private fontAxes: FontAxis[] = [];
    /**
     * The bare `google:` family of the current paint, or null when it names none.
     *
     * Kept because the axes arrive *after* the family does: a paint bundle serialises `setTextStyle`
     * (TYPEFACE) before `setTextAxis` (FONT_AXIS), so the request made while resolving the typeface
     * necessarily has no axes yet. Re-requesting once they are decoded is what actually asks the API
     * for the variable face; without it a networked render keeps the enumerated static stylesheet and
     * paints a `wdth` ramp as identical lines. (It looks fine on a page that vendored the variable
     * face itself, which is exactly how this hid.)
     */
    private googleFamily: string | null = null;
    private colorFilterColor: string | null = null;
    private colorFilterArgb = 0;
    private colorFilterMode = 3; // SRC_OVER default

    // Active shader (set via PaintBundle.SHADER)
    private activeShaderData: ShaderData | null = null;
    private shaderRenderer: WebGLShaderRenderer | null = null;

    /** Release the WebGL context this paint context lazily created, if any. */
    destroy(): void {
        if (this.shaderRenderer) {
            this.shaderRenderer.destroy();
            this.shaderRenderer = null;
        }
    }
    private glslCache = new Map<number, string>();  // shaderTextId -> transpiled GLSL

    // Paint stack for save/restore
    private paintStack: Array<{
        color: string; style: number; strokeWidth: number; textSize: number;
        alpha: number; lineCap: CanvasLineCap; lineJoin: CanvasLineJoin;
        miterLimit: number; blendMode: GlobalCompositeOperation; antiAlias: boolean;
        letterSpacing: number; gradientStyle: CanvasGradient | CanvasPattern | null;
        fontFamily: string; fontWeight: number; fontItalic: boolean;
        fontAxes: FontAxis[]; googleFamily: string | null;
        filterBitmap: boolean;
        colorFilterColor: string | null; colorFilterArgb: number; colorFilterMode: number;
        activeShaderData: ShaderData | null;
    }> = [];

    // Path cache: id -> Int32Array (raw float32 int bits; markers/variable refs
    // are NaN-with-payload — decoded from bits, never via Number.isNaN, so the
    // ids survive on engines that canonicalize NaN payloads (Safari/Firefox)).
    private pathDataCache = new Map<number, Int32Array>();
    private pathWindingCache = new Map<number, number>();

    // Bitmap cache: id -> ImageBitmap or HTMLImageElement
    private bitmapCache = new Map<number, HTMLImageElement | ImageBitmap>();
    private bitmapPromises = new Map<number, Promise<void>>();

    // Text cache: id -> string
    private textCache = new Map<number, string>();

    // Graphics layer stack for offscreen compositing
    private layerStack: Array<{
        previousCtx: CanvasRenderingContext2D;
        offscreenCanvas: any;
        width: number;
        height: number;
        attributes: Map<number, any>;
    }> = [];

    // Factory for creating offscreen canvases - override for node-canvas
    createLayerCanvas: (w: number, h: number) => CanvasRenderingContext2D = (w, h) => {
        if (typeof OffscreenCanvas !== 'undefined') {
            const c = new OffscreenCanvas(w, h);
            return c.getContext('2d')! as unknown as CanvasRenderingContext2D;
        }
        if (typeof document !== 'undefined') {
            const c = document.createElement('canvas');
            c.width = w;
            c.height = h;
            return c.getContext('2d')!;
        }
        throw new Error('No canvas factory available for graphics layers');
    };

    constructor(context: RemoteContext, canvas: CanvasRenderingContext2D) {
        super(context);
        if (!canvas) {
            throw new Error('CanvasPaintContext: canvas rendering context is null or undefined');
        }
        this.ctx = canvas;
    }

    getCanvas(): CanvasRenderingContext2D { return this.ctx; }

    // --- Text cache ---

    loadText(id: number, text: string): void { this.textCache.set(id, text); }

    getText(id: number): string | null { return this.textCache.get(id) ?? null; }

    /** Embedded FontData families, keyed by the same document id used by TYPEFACE/CoreText. */
    private embeddedFontFamilies = new Map<number, { data: Uint8Array; family: string }>();

    loadFont(fontId: number, data: Uint8Array): void {
        // FontData is applied in both the data and paint passes. Avoid hashing a potentially large
        // font file again on every frame; a changed operation carries a different byte-array view.
        const current = this.embeddedFontFamilies.get(fontId);
        if (current?.data === data) return;
        this.embeddedFontFamilies.set(fontId, {
            data,
            family: registerEmbeddedFont(fontId, data, this.onFontLoaded ?? undefined),
        });
    }

    // --- Typeface resolution ---

    /**
     * Called when a named family finishes loading, so a player that already painted a frame in the
     * fallback face can repaint it in the real one. Null for single-shot renderers, which instead
     * await `webFontsReady()` before painting the frame they keep.
     */
    onFontLoaded: (() => void) | null = null;

    /**
     * CSS stack for a `PaintBundle.TYPEFACE` operand.
     *
     * The operand is overloaded: below `START_ID` it is one of the four generic typeface constants,
     * at or above it the document's *text id* for a named family (`CoreText.updateVariables` ends
     * `else this.mType = this.mFontFamilyId`). The two ranges cannot collide — ids are handed out
     * from `START_ID` upward, so no text id is ever 0..3 — which is what makes this single integer
     * safe to disambiguate by magnitude.
     *
     * A `google:`-namespaced family is fetched; an unprefixed one is only *named*, leaving it to
     * whatever the host already has. Requesting the face is fire-and-forget: resolution has to be
     * synchronous because it happens mid-paint, so the stack names the family now and the face
     * arrives later (repainted via `onFontLoaded`, or awaited by a single-shot renderer). Until then
     * the fallback paints.
     */
    private fontStackForTypeface(fontType: number): string {
        if (fontType < RemoteComposeState.START_ID) return cssFontStackFor(fontType);
        const embedded = this.embeddedFontFamilies.get(fontType);
        if (embedded) {
            this.googleFamily = null;
            return namedFontStack(embedded.family);
        }
        const family = this.getText(fontType);
        // A named family whose text id resolves to nothing means the document referenced a string it
        // never loaded; treat it as unstyled rather than painting a stack named "null".
        if (!family) return cssFontStackFor(0);
        const { source, name } = parseFamily(family);
        if (!name) return cssFontStackFor(0);
        // Only the weight/style this op actually asks for. `fontWeight`/`fontItalic` were decoded
        // from the same operation a few lines up, so the request is exact rather than "every face
        // the family publishes".
        this.googleFamily = source === 'google' ? name : null;
        if (source === 'google') {
            // The axes go with the request: asked for an enumerated weight list the API answers
            // with pinned static instances, and asked for the axis *ranges* the document uses it
            // answers with a variable face. Only the second can be varied at paint time. This first
            // ask carries whatever axes the paint has so far — none, for the usual TYPEFACE-then-
            // FONT_AXIS order — and the FONT_AXIS branch repeats it once they are known.
            ensureWebFont(
                name,
                this.fontWeight,
                this.fontItalic,
                this.onFontLoaded ?? undefined,
                this.fontAxes,
            );
        }
        return namedFontStack(name);
    }

    // --- Bitmap cache ---

    loadBitmap(imageId: number, encoding: number, type: number,
               width: number, height: number, bitmap: Uint8Array): void {
        // Decode bitmap asynchronously and cache
        const blob = new Blob([bitmap.buffer as ArrayBuffer], { type: 'image/png' });
        const url = URL.createObjectURL(blob);
        const img = new Image();
        const promise = new Promise<void>((resolve) => {
            img.onload = () => {
                URL.revokeObjectURL(url);
                this.bitmapCache.set(imageId, img);
                resolve();
            };
            img.onerror = (e) => {
                URL.revokeObjectURL(url);
                console.warn(`CanvasPaintContext: failed to load bitmap ${imageId}`, e);
                resolve();
            };
            img.src = url;
        });
        this.bitmapPromises.set(imageId, promise);
    }

    // --- Path cache ---

    loadPathData(id: number, winding: number, data: Int32Array): void {
        this.pathDataCache.set(id, data);
        this.pathWindingCache.set(id, winding);
    }

    // Path command IDs (NaN-encoded in the float array)
    // PathExpression uses short IDs (10-16), binary PathData uses NanMap IDs (0x300000+)
    private static readonly PATH_MOVE = 10;
    private static readonly PATH_LINE = 11;
    private static readonly PATH_QUADRATIC = 12;
    private static readonly PATH_CONIC = 13;
    private static readonly PATH_CUBIC = 14;
    private static readonly PATH_CLOSE = 15;
    private static readonly PATH_DONE = 16;
    // NanMap path command base (Java convention used in binary PathData)
    private static readonly NANMAP_PATH_BASE = 0x300000;

    // A path operand is either a literal float or a NaN-encoded variable id (a
    // dynamic coordinate, e.g. a button/card fill sized from its component's
    // measured dimensions). Dereference the id to its current float value, the way
    // the command word and every other expression operand are resolved — reading
    // it as a literal `intBitsToFloat` would yield NaN and collapse the path.
    private pathCoord(bits: number): number {
        return isNaNBits(bits) ? this.mContext.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
    }

    private buildPath2D(data: Int32Array, start = 0, end = 1): Path2D {
        const path = new Path2D();
        let i = 0;
        while (i < data.length) {
            const bits = data[i];
            // Command codes are NaN-encoded IDs; decode from raw bits so the
            // payload survives Safari/Firefox NaN canonicalization. Real coords
            // (non-NaN bits) are reinterpreted as floats.
            let cmd = isNaNBits(bits) ? idFromBits(bits) : intBitsToFloat(bits);
            // Normalize NanMap IDs (0x300000+) to short IDs (10+)
            if (cmd >= CanvasPaintContext.NANMAP_PATH_BASE && cmd <= CanvasPaintContext.NANMAP_PATH_BASE + 6) {
                cmd = CanvasPaintContext.PATH_MOVE + (cmd - CanvasPaintContext.NANMAP_PATH_BASE);
            }
            switch (cmd) {
                case CanvasPaintContext.PATH_MOVE:
                    // Format: [MOVE, x, y] = 3 positions
                    i++;
                    path.moveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]));
                    i += 2;
                    break;
                case CanvasPaintContext.PATH_LINE:
                    // Format: [LINE, startX, startY, endX, endY] = 5 positions
                    // startX,startY are redundant (previous endpoint), skip them
                    i += 3;
                    path.lineTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]));
                    i += 2;
                    break;
                case CanvasPaintContext.PATH_QUADRATIC:
                    // Format: [QUAD, startX, startY, cpX, cpY, endX, endY] = 7 positions
                    i += 3;
                    path.quadraticCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]));
                    i += 4;
                    break;
                case CanvasPaintContext.PATH_CONIC:
                    // Format: [CONIC, startX, startY, cpX, cpY, endX, endY, weight] = 8 positions
                    // Approximate conic as quadratic (ignore weight)
                    i += 3;
                    path.quadraticCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]));
                    i += 5;
                    break;
                case CanvasPaintContext.PATH_CUBIC:
                    // Format: [CUBIC, startX, startY, cp1X, cp1Y, cp2X, cp2Y, endX, endY] = 9 positions
                    i += 3;
                    path.bezierCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]), this.pathCoord(data[i + 4]), this.pathCoord(data[i + 5]));
                    i += 6;
                    break;
                case CanvasPaintContext.PATH_CLOSE:
                    path.closePath();
                    i++;
                    break;
                case CanvasPaintContext.PATH_DONE:
                    return path;
                default:
                    i++; // skip unknown command
                    break;
            }
        }
        return path;
    }

    // --- Apply paint ---

    private getEffectiveColor(): string | CanvasGradient | CanvasPattern {
        if (this.colorFilterColor === null) {
            return this.gradientStyle ?? this.color;
        }
        // Apply PorterDuff color filter: source=filter color, destination=paint color
        // For opaque source (filter) and opaque destination (paint), per mode:
        switch (this.colorFilterMode) {
            case 0:  return 'rgba(0,0,0,0)'; // CLEAR: transparent
            case 1:  return this.colorFilterColor; // SRC: filter color
            case 2:  return this.gradientStyle ?? this.color; // DST: paint color
            case 3:  return this.colorFilterColor; // SRC_OVER: opaque filter wins
            case 4:  return this.gradientStyle ?? this.color; // DST_OVER: opaque paint wins
            case 5:  return this.colorFilterColor; // SRC_IN: filter × paint_alpha = filter
            case 6:  return this.gradientStyle ?? this.color; // DST_IN: paint × filter_alpha = paint
            case 7:  return 'rgba(0,0,0,0)'; // SRC_OUT: filter × (1-paint_alpha) = 0
            case 8:  return 'rgba(0,0,0,0)'; // DST_OUT: paint × (1-filter_alpha) = 0
            case 9:  return this.colorFilterColor; // SRC_ATOP: filter
            case 10: return this.gradientStyle ?? this.color; // DST_ATOP: paint
            case 11: return 'rgba(0,0,0,0)'; // XOR: both opaque → transparent
            default: {
                // For advanced blend modes (12+), compute per-channel
                return this.computeColorFilterBlend();
            }
        }
    }

    private computeColorFilterBlend(): string {
        // Parse filter color components
        const sA = ((this.colorFilterArgb >>> 24) & 0xFF) / 255;
        const sR = (this.colorFilterArgb >>> 16) & 0xFF;
        const sG = (this.colorFilterArgb >>> 8) & 0xFF;
        const sB = this.colorFilterArgb & 0xFF;
        // Parse paint color - extract from rgba string
        const m = this.color.match(/rgba?\((\d+),(\d+),(\d+),?([^)]*)\)/);
        const dR = m ? parseInt(m[1]) : 0;
        const dG = m ? parseInt(m[2]) : 0;
        const dB = m ? parseInt(m[3]) : 0;
        const dA = m && m[4] ? parseFloat(m[4]) : 1;
        let rR: number, rG: number, rB: number, rA: number;
        const blend1 = (a: number, b: number) => {
            switch (this.colorFilterMode) {
                case 12: return Math.min(255, a + b); // PLUS
                case 13: return (a * b) / 255; // MODULATE
                case 14: return a + b - (a * b) / 255; // SCREEN
                case 15: // OVERLAY: 2*Cb*Cs/255 if Cb≤127, else 255-2*(255-Cb)*(255-Cs)/255
                    return b <= 127 ? (2 * a * b) / 255 : 255 - (2 * (255 - a) * (255 - b)) / 255;
                case 16: return Math.min(a, b); // DARKEN
                case 17: return Math.max(a, b); // LIGHTEN
                case 18: return b === 0 ? 0 : Math.min(255, (a * 255) / (255 - b)); // COLOR_DODGE
                case 19: return b === 255 ? 255 : Math.max(0, 255 - ((255 - a) * 255) / b); // COLOR_BURN
                case 20: // HARD_LIGHT: overlay with swapped args
                    return a <= 127 ? (2 * a * b) / 255 : 255 - (2 * (255 - a) * (255 - b)) / 255;
                case 21: { // SOFT_LIGHT
                    const t = (a / 255);
                    return b <= 127 ? b - (1 - 2 * t) * b * (1 - b / 255) * 255
                        : b + (2 * t - 1) * (Math.sqrt(b / 255) * 255 - b);
                }
                case 22: return Math.abs(a - b); // DIFFERENCE
                case 23: return a + b - (2 * a * b) / 255; // EXCLUSION
                case 24: return (a * b) / 255; // MULTIPLY
                default: return a; // Fallback: use filter channel
            }
        };
        rR = blend1(sR, dR);
        rG = blend1(sG, dG);
        rB = blend1(sB, dB);
        rA = Math.min(1, sA + dA - sA * dA);
        return `rgba(${Math.round(rR)},${Math.round(rG)},${Math.round(rB)},${rA.toFixed(3)})`;
    }

    private createSweepGradientPattern(
        cx: number, cy: number, colors: number[], stops: number[]
    ): CanvasPattern | null {
        // Determine canvas dimensions needed to cover the sweep from center
        const ctx = this.mContext;
        const w = ctx ? ctx.mWidth || 256 : 256;
        const h = ctx ? ctx.mHeight || 256 : 256;
        try {
            const layerCtx = this.createLayerCanvas(w, h);
            const imgData = layerCtx.createImageData(w, h);
            const data = imgData.data;
            // Parse ARGB colors into RGBA arrays
            const colorComponents: Array<[number, number, number, number]> = colors.map(argb => [
                (argb >>> 16) & 0xFF, // R
                (argb >>> 8) & 0xFF,  // G
                argb & 0xFF,          // B
                ((argb >>> 24) & 0xFF) / 255 // A
            ]);
            for (let y = 0; y < h; y++) {
                for (let x = 0; x < w; x++) {
                    let angle = Math.atan2(y - cy, x - cx); // -PI..PI
                    if (angle < 0) angle += Math.PI * 2;
                    const t = angle / (Math.PI * 2); // 0..1
                    // Find the two stops this t falls between
                    let idx = 0;
                    for (let s = 0; s < stops.length - 1; s++) {
                        if (t >= stops[s]) idx = s;
                    }
                    const t0 = stops[idx];
                    const t1 = stops[Math.min(idx + 1, stops.length - 1)];
                    const range = t1 - t0;
                    const frac = range > 0 ? (t - t0) / range : 0;
                    const c0 = colorComponents[idx];
                    const c1 = colorComponents[Math.min(idx + 1, colorComponents.length - 1)];
                    const off = (y * w + x) * 4;
                    data[off] = c0[0] + (c1[0] - c0[0]) * frac;
                    data[off + 1] = c0[1] + (c1[1] - c0[1]) * frac;
                    data[off + 2] = c0[2] + (c1[2] - c0[2]) * frac;
                    data[off + 3] = (c0[3] + (c1[3] - c0[3]) * frac) * 255;
                }
            }
            layerCtx.putImageData(imgData, 0, 0);
            return this.ctx.createPattern(layerCtx.canvas as any, 'no-repeat');
        } catch (_e) {
            return null;
        }
    }

    private applyFillStyle(): void {
        this.ctx.fillStyle = this.getEffectiveColor();
        this.ctx.globalAlpha = this.alpha;
        this.ctx.globalCompositeOperation = this.blendMode;
    }

    private applyStrokeStyle(): void {
        this.ctx.strokeStyle = this.getEffectiveColor();
        this.ctx.lineWidth = this.strokeWidth;
        this.ctx.lineCap = this.lineCap;
        this.ctx.lineJoin = this.lineJoin;
        this.ctx.miterLimit = this.miterLimit;
        this.ctx.globalAlpha = this.alpha;
        this.ctx.globalCompositeOperation = this.blendMode;
    }

    private fillOrStroke(doFill: () => void, doStroke: () => void): void {
        if (this.style === 0 || this.style === 2) {
            this.applyFillStyle();
            doFill();
        }
        if (this.style === 1 || this.style === 2) {
            this.applyStrokeStyle();
            doStroke();
        }
    }

    /**
     * The axis name a [tag] int stands for, in either encoding the format uses.
     *
     * A `CoreText` style interns its axis names in the text table like any other string and puts the
     * *text id* in the array; the paint bundle's own `setTextAxis` carries the **raw OpenType tag**
     * packed into four bytes (`0x77676874` = `wght`). Reading the text table first (ids start at
     * `START_ID`, so the two ranges can't collide) and unpacking the bytes otherwise covers both
     * without having to know which writer produced the document. Anything that is neither is dropped
     * rather than guessed at.
     */
    private axisName(tag: number): string | null {
        if (tag >= RemoteComposeState.START_ID) {
            const name = this.getText(tag);
            if (name) return name;
        }
        let packed = '';
        for (let shift = 24; shift >= 0; shift -= 8) {
            const code = (tag >> shift) & 0xff;
            if (code < 0x21 || code > 0x7e) return null;
            packed += String.fromCharCode(code);
        }
        return packed;
    }

    private axisValue(tag: string): number | null {
        const axis = this.fontAxes.find((a) => a.tag === tag);
        return axis ? axis.value : null;
    }

    private setFont(): void {
        // CSS canvas font shorthand: [style] [weight] size family.
        // Anything skipped here is silently ignored at render time, so
        // weight + italic must always be folded in for them to take effect.
        //
        // Font-variation axes reach the canvas through the shorthand's *own* properties rather than
        // a `font-variation-settings` (which `ctx.font` has no room for): `wght` is the weight and
        // `wdth` is `fontStretch` as a percentage, both of which the browser resolves against a
        // registered variable face's declared ranges. That covers the two axes a document can
        // actually be seen to vary; anything else — `opsz`, `GRAD`, a custom axis — has no canvas
        // expression at all and is dropped, which is a platform limit rather than a decode gap.
        const wght = this.axisValue('wght');
        const ital = this.axisValue('ital');
        const italic = this.fontItalic || (ital !== null && ital >= 0.5);
        const effectiveWeight = wght !== null ? Math.round(wght) : this.fontWeight;
        const style = italic ? 'italic ' : '';
        const weight = effectiveWeight !== 400 ? `${effectiveWeight} ` : '';
        this.ctx.font = `${style}${weight}${this.textSize}px ${this.fontFamily}`;
        // After `font`, not before: assigning the shorthand resets `fontStretch` to the shorthand's
        // own (absent, so `normal`) value, which would undo this.
        const stretchable = this.ctx as CanvasRenderingContext2D & { fontStretch?: FontStretchKeyword };
        if ('fontStretch' in stretchable) {
            const wdth = this.axisValue('wdth');
            stretchable.fontStretch = wdth !== null ? nearestFontStretch(wdth) : 'normal';
        }
    }

    // --- PaintContext abstract methods ---

    applyPaint(paintData: PaintBundle): void {
        const arr = paintData.getArray();
        const len = paintData.getLength();
        let i = 0;
        while (i < len) {
            const cmd = arr[i++];
            const tag = cmd & 0xFFFF;
            const upper = (cmd >> 16) & 0xFFFF;
            switch (tag) {
                case PaintBundle.TEXT_SIZE:
                    this.textSize = intBitsToFloat(arr[i++]);
                    this.setFont();
                    break;
                case PaintBundle.COLOR:
                    this.color = argbToRgba(arr[i++]);
                    this.gradientStyle = null;
                    break;
                case PaintBundle.STROKE_WIDTH:
                    this.strokeWidth = intBitsToFloat(arr[i++]);
                    break;
                case PaintBundle.STROKE_MITER:
                    this.miterLimit = intBitsToFloat(arr[i++]);
                    break;
                case PaintBundle.STROKE_CAP:
                    this.lineCap = upper === 0 ? 'butt' : upper === 1 ? 'round' : 'square';
                    break;
                case PaintBundle.STYLE:
                    this.style = upper;
                    break;
                case PaintBundle.SHADER: {
                    const shaderId = arr[i++];
                    if (shaderId === 0) {
                        this.activeShaderData = null;
                    } else {
                        const sd = this.getContext()?.getShader(shaderId);
                        this.activeShaderData = sd ?? null;
                    }
                    break;
                }
                case PaintBundle.IMAGE_FILTER_QUALITY:
                    // value in upper bits, no data int
                    break;
                case PaintBundle.GRADIENT: {
                    const meta = arr[i++];
                    const numColors = meta & 0xFF;
                    const colors: number[] = [];
                    for (let c = 0; c < numColors; c++) {
                        colors.push(arr[i++]);
                    }
                    const numStops = arr[i++];
                    const stops: number[] = [];
                    if (numStops > 0) {
                        for (let s = 0; s < numStops; s++) {
                            stops.push(intBitsToFloat(arr[i++]));
                        }
                    } else {
                        // Auto-generate evenly spaced stops
                        for (let s = 0; s < numColors; s++) {
                            stops.push(numColors > 1 ? s / (numColors - 1) : 0);
                        }
                    }
                    let gradient: CanvasGradient | null = null;
                    if (upper === PaintBundle.SWEEP_GRADIENT) {
                        const cx = intBitsToFloat(arr[i++]);
                        const cy = intBitsToFloat(arr[i++]);
                        // Sweep gradient has NO tileMode
                        if (typeof (this.ctx as any).createConicGradient === 'function') {
                            gradient = (this.ctx as any).createConicGradient(0, cx, cy);
                        } else {
                            // Polyfill: render sweep gradient to a pattern
                            const sweepPattern = this.createSweepGradientPattern(cx, cy, colors, stops);
                            if (sweepPattern) {
                                this.gradientStyle = sweepPattern;
                            }
                        }
                    } else if (upper === PaintBundle.RADIAL_GRADIENT) {
                        const cx = intBitsToFloat(arr[i++]);
                        const cy = intBitsToFloat(arr[i++]);
                        const radius = intBitsToFloat(arr[i++]);
                        i++; // tileMode
                        gradient = this.ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
                    } else {
                        // LINEAR_GRADIENT (upper=0)
                        const startX = intBitsToFloat(arr[i++]);
                        const startY = intBitsToFloat(arr[i++]);
                        const endX = intBitsToFloat(arr[i++]);
                        const endY = intBitsToFloat(arr[i++]);
                        i++; // tileMode
                        gradient = this.ctx.createLinearGradient(startX, startY, endX, endY);
                    }
                    if (gradient) {
                        for (let s = 0; s < stops.length && s < colors.length; s++) {
                            gradient.addColorStop(
                                Math.max(0, Math.min(1, stops[s])),
                                argbToRgba(colors[s])
                            );
                        }
                        this.gradientStyle = gradient;
                    }
                    break;
                }
                case PaintBundle.ALPHA:
                    this.alpha = intBitsToFloat(arr[i++]);
                    break;
                case PaintBundle.COLOR_FILTER: {
                    const cfArgb = arr[i++];
                    this.colorFilterArgb = cfArgb;
                    this.colorFilterColor = argbToRgba(cfArgb);
                    this.colorFilterMode = upper;
                    break;
                }
                case PaintBundle.ANTI_ALIAS:
                    this.antiAlias = upper !== 0;
                    break;
                case PaintBundle.STROKE_JOIN:
                    this.lineJoin = upper === 0 ? 'miter' : upper === 1 ? 'round' : 'bevel';
                    break;
                case PaintBundle.TYPEFACE: {
                    // upper packs (weight & 0x3FF) | (italic ? 2048 : 0)
                    // — see PaintBundle.java:881 / setTextStyle. Decode
                    // both flags so they actually affect the rendered font.
                    const weight = upper & 0x3FF;
                    const italic = (upper & 2048) !== 0;
                    this.fontWeight = weight > 0 ? weight : 400;
                    this.fontItalic = italic;

                    const fontType = arr[i++];
                    this.fontFamily = this.fontStackForTypeface(fontType);
                    this.setFont();
                    break;
                }
                case PaintBundle.FILTER_BITMAP:
                    this.filterBitmap = upper !== 0;
                    this.ctx.imageSmoothingEnabled = this.filterBitmap;
                    break;
                case PaintBundle.BLEND_MODE:
                    this.blendMode = this.mapBlendMode(upper);
                    break;
                case PaintBundle.COLOR_ID: {
                    // Value is already resolved to ARGB by PaintBundle.updateVariables()
                    this.color = argbToRgba(arr[i++]);
                    this.gradientStyle = null;
                    break;
                }
                case PaintBundle.COLOR_FILTER_ID: {
                    // Value is already resolved to ARGB by PaintBundle.updateVariables()
                    const cfColor = arr[i++];
                    this.colorFilterColor = argbToRgba(cfColor);
                    this.colorFilterArgb = cfColor;
                    break;
                }
                case PaintBundle.CLEAR_COLOR_FILTER:
                    this.colorFilterColor = null;
                    break;
                case PaintBundle.SHADER_MATRIX:
                    i++; // skip float-as-int
                    break;
                case PaintBundle.FONT_AXIS: {
                    const axisCount = upper;
                    const axes: FontAxis[] = [];
                    for (let k = 0; k < axisCount; k++) {
                        const tag = arr[i++];
                        const value = intBitsToFloat(arr[i++]);
                        const name = this.axisName(tag);
                        if (name) axes.push({ tag: name, value });
                    }
                    this.fontAxes = axes;
                    // Now that the axes are known, ask again: this is the request that can come
                    // back variable. `ensureWebFont` is idempotent per (family, weight, style,
                    // axes), so the repeat costs one map lookup when nothing changed.
                    if (this.googleFamily && axes.length > 0) {
                        ensureWebFont(
                            this.googleFamily,
                            this.fontWeight,
                            this.fontItalic,
                            this.onFontLoaded ?? undefined,
                            axes,
                        );
                    }
                    this.setFont();
                    break;
                }
                case PaintBundle.TEXTURE: {
                    const bitmapId = arr[i++];
                    const tileModes = arr[i++];
                    i++; // filter (Canvas2D uses its own sampling)

                    const tileX = tileModes & 0xF;
                    const tileY = (tileModes >> 16) & 0xF;

                    const img = this.bitmapCache.get(bitmapId);
                    if (img) {
                        // Map tile modes to Canvas2D pattern repetition
                        // 0=CLAMP(no-repeat), 1=REPEAT, 2=MIRROR(repeat), 3=DECAL(no-repeat)
                        const xRepeat = (tileX === 1 || tileX === 2);
                        const yRepeat = (tileY === 1 || tileY === 2);
                        let repetition: string;
                        if (xRepeat && yRepeat) repetition = 'repeat';
                        else if (xRepeat) repetition = 'repeat-x';
                        else if (yRepeat) repetition = 'repeat-y';
                        else repetition = 'no-repeat';

                        const pattern = this.ctx.createPattern(img as CanvasImageSource, repetition);
                        if (pattern) {
                            this.gradientStyle = pattern;
                        }
                    }
                    break;
                }
                case PaintBundle.PATH_EFFECT: {
                    const count = upper;
                    if (count === 0) {
                        this.ctx.setLineDash([]);
                        this.ctx.lineDashOffset = 0;
                    } else {
                        const intervals: number[] = [];
                        for (let k = 0; k < count; k++) {
                            intervals.push(intBitsToFloat(arr[i++]));
                        }
                        this.ctx.setLineDash(intervals);
                    }
                    break;
                }
                case PaintBundle.FALLBACK_TYPEFACE:
                    i++; // skip font type int
                    break;
                default:
                    if (tag === 0) break; // sentinel / padding - skip silently
                    // Unknown tag - can't safely skip, stop parsing
                    console.warn(`PaintBundle: unknown tag ${tag} at index ${i - 1}`);
                    return;
            }
        }
    }

    replacePaint(paintBundle: PaintBundle): void {
        this.resetPaintState();
        this.applyPaint(paintBundle);
    }

    private resetPaintState(): void {
        this.color = 'rgba(0,0,0,1)';
        this.style = 0;
        this.strokeWidth = 1;
        this.textSize = 14;
        this.alpha = 1;
        this.lineCap = 'butt';
        this.lineJoin = 'miter';
        this.miterLimit = 10;
        this.blendMode = 'source-over';
        this.antiAlias = true;
        this.filterBitmap = true;
        this.letterSpacing = 0;
        this.gradientStyle = null;
        this.fontFamily = cssFontStackFor(0);
        this.fontWeight = 400;
        this.fontItalic = false;
        // Axes belong to the paint like weight and slant do. Left behind, the previous op's `wdth`
        // would keep being applied to text that asked for none — and, worse, keep being *requested*,
        // widening a family's axis span with values no document line uses.
        this.fontAxes = [];
        this.googleFamily = null;
        this.colorFilterColor = null;
        this.colorFilterArgb = 0;
        this.colorFilterMode = 3;
        this.activeShaderData = null;
        this.ctx.setLineDash([]);
        this.ctx.lineDashOffset = 0;
        this.setFont();
    }

    private mapBlendMode(mode: number): GlobalCompositeOperation {
        switch (mode) {
            case 0: return 'clear' as GlobalCompositeOperation;  // CLEAR
            case 1: return 'copy';               // SRC
            case 2: return 'destination' as GlobalCompositeOperation; // DST (keep destination only)
            case 3: return 'source-over';        // SRC_OVER (default)
            case 4: return 'destination-over';   // DST_OVER
            case 5: return 'source-in';          // SRC_IN
            case 6: return 'destination-in';     // DST_IN
            case 7: return 'source-out';         // SRC_OUT
            case 8: return 'destination-out';    // DST_OUT
            case 9: return 'source-atop';        // SRC_ATOP
            case 10: return 'destination-atop';  // DST_ATOP
            case 11: return 'xor';               // XOR
            case 12: return 'lighter';           // PLUS/ADD
            case 13: return 'multiply';          // MODULATE (closest to multiply)
            case 14: return 'screen';            // SCREEN
            case 15: return 'overlay';           // OVERLAY
            case 16: return 'darken';            // DARKEN
            case 17: return 'lighten';           // LIGHTEN
            case 18: return 'color-dodge';       // COLOR_DODGE
            case 19: return 'color-burn';        // COLOR_BURN
            case 20: return 'hard-light';        // HARD_LIGHT
            case 21: return 'soft-light';        // SOFT_LIGHT
            case 22: return 'difference';        // DIFFERENCE
            case 23: return 'exclusion';         // EXCLUSION
            case 24: return 'multiply';          // MULTIPLY
            case 25: return 'hue';               // HUE
            case 26: return 'saturation';        // SATURATION
            case 27: return 'color';             // COLOR
            case 28: return 'luminosity';        // LUMINOSITY
            case 30: return 'lighter';           // PORTER_MODE_ADD
            default: return 'source-over';
        }
    }

    savePaint(): void {
        this.paintStack.push({
            color: this.color, style: this.style, strokeWidth: this.strokeWidth,
            textSize: this.textSize, alpha: this.alpha, lineCap: this.lineCap,
            lineJoin: this.lineJoin, miterLimit: this.miterLimit,
            blendMode: this.blendMode, antiAlias: this.antiAlias,
            letterSpacing: this.letterSpacing, gradientStyle: this.gradientStyle,
            activeShaderData: this.activeShaderData,
            fontFamily: this.fontFamily, fontWeight: this.fontWeight,
            fontItalic: this.fontItalic, fontAxes: this.fontAxes,
            googleFamily: this.googleFamily, filterBitmap: this.filterBitmap,
            colorFilterColor: this.colorFilterColor,
            colorFilterArgb: this.colorFilterArgb, colorFilterMode: this.colorFilterMode
        });
    }

    restorePaint(): void {
        const state = this.paintStack.pop();
        if (state) {
            Object.assign(this, state);
            this.setFont();
        }
    }

    /**
     * If an active shader is set, render it via WebGL2 into the given
     * rectangle and return true. Otherwise return false (caller does
     * the normal fill).
     */
    private tryRenderShader(left: number, top: number, right: number, bottom: number): boolean {
        const ctx = this.getContext();
        if (!this.activeShaderData || !ctx) return false;
        const sd = this.activeShaderData;

        // Get shader source text
        const textId = sd.getShaderTextId();
        const shaderText = this.textCache.get(textId);
        if (!shaderText) return false;

        // Transpile (cached by string textId)
        const cacheKey = String(textId);
        let glsl = this.glslCache.get(textId);
        if (!glsl) {
            try {
                const result = transpileAgslToGlsl(shaderText);
                glsl = result.glsl;
                this.glslCache.set(textId, glsl);
            } catch (e) {
                console.error(`[shader] transpile failed (textId=${textId}):`, e, '\nAGSL:', shaderText);
                return false;
            }
        }

        // One renderer per document. Sharing a single renderer page-wide looks
        // tempting (a browser allows only ~16 live WebGL contexts) but is wrong: the
        // renderer draws into its own offscreen canvas, so documents sharing one read
        // back whichever shader drew last. Every card on a page then shows the same
        // shader. Contexts are bounded by paginating the page, not by sharing.
        if (!this.shaderRenderer) {
            this.shaderRenderer = new WebGLShaderRenderer();
            if (!this.shaderRenderer.isAvailable()) {
                this.shaderRenderer = null;
                return false;
            }
        }

        let w = Math.round(right - left);
        let h = Math.round(top > bottom ? top - bottom : bottom - top);
        if (w <= 0 || h <= 0) return false;

        // Cap to actual canvas size — avoid creating absurdly large WebGL
        // surfaces for documents taller/wider than the viewport.
        const maxW = this.ctx.canvas.width || 4096;
        const maxH = this.ctx.canvas.height || 4096;
        if (w > maxW) w = maxW;
        if (h > maxH) h = maxH;

        // Resolve NaN-encoded uniform IDs to current values
        sd.updateVariables(ctx);

        // Collect float uniforms
        const floats = new Map<string, Float32Array>();
        for (const name of sd.getUniformFloatNames()) {
            const vals = sd.getUniformFloats(name);
            floats.set(name, vals);
        }

        // Collect int uniforms
        const ints = new Map<string, Int32Array>();
        for (const name of sd.getUniformIntegerNames()) {
            ints.set(name, sd.getUniformInts(name));
        }

        // Collect texture uniforms
        let textures: Map<string, TexImageSource> | undefined;
        const bitmapNames = sd.getUniformBitmapNames();
        if (bitmapNames.length > 0) {
            textures = new Map();
            for (const name of bitmapNames) {
                const bmpId = sd.getUniformBitmapId(name);
                if (bmpId < 0) continue;
                const offCtx = this.bitmapCanvasCache.get(bmpId);
                if (offCtx) {
                    textures.set(name, offCtx.canvas as any);
                } else {
                    const img = this.bitmapCache.get(bmpId);
                    if (img) textures.set(name, img);
                }
            }
        }

        // Render via WebGL — pass document rect size for coordinate scaling
        const docW = Math.round(right - left);
        const docH = Math.round(Math.abs(bottom - top));
        const ok = this.shaderRenderer.render(
            glsl, w, h, floats, ints, textures, cacheKey, docW, docH);
        if (!ok) {
            console.error(`[shader] WebGL render FAILED textId=${textId}`);
            return false;
        }

        // Composite into Canvas2D at the original document rect (the canvas
        // transform handles scaling from document coords to screen pixels).
        this.ctx.globalAlpha = this.alpha;
        this.ctx.globalCompositeOperation = this.blendMode;
        const dstW = right - left;
        const dstH = Math.abs(bottom - top);
        this.ctx.drawImage(this.shaderRenderer.getCanvas(),
                           left, Math.min(top, bottom), dstW, dstH);
        return true;
    }

    // --- Drawing ---

    drawRect(left: number, top: number, right: number, bottom: number): void {
        // If an AGSL shader is active, render it via WebGL2 instead of the
        // normal Canvas2D fill.  Falls back to regular fill on failure.
        if (this.activeShaderData && this.tryRenderShader(left, top, right, bottom)) {
            return;
        }
        this.fillOrStroke(
            () => { this.ctx.fillRect(left, top, right - left, bottom - top); },
            () => { this.ctx.strokeRect(left, top, right - left, bottom - top); }
        );
    }

    drawCircle(centerX: number, centerY: number, radius: number): void {
        if (radius <= 0) return;
        this.ctx.beginPath();
        this.ctx.arc(centerX, centerY, radius, 0, Math.PI * 2);
        this.fillOrStroke(
            () => { this.ctx.fill(); },
            () => { this.ctx.stroke(); }
        );
    }

    drawLine(x1: number, y1: number, x2: number, y2: number): void {
        this.applyStrokeStyle();
        this.ctx.beginPath();
        this.ctx.moveTo(x1, y1);
        this.ctx.lineTo(x2, y2);
        this.ctx.stroke();
    }

    drawOval(left: number, top: number, right: number, bottom: number): void {
        const cx = (left + right) / 2;
        const cy = (top + bottom) / 2;
        const rx = (right - left) / 2;
        const ry = (bottom - top) / 2;
        this.ctx.beginPath();
        this.ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
        this.fillOrStroke(
            () => { this.ctx.fill(); },
            () => { this.ctx.stroke(); }
        );
    }

    drawRoundRect(left: number, top: number, right: number, bottom: number, rx: number, ry: number): void {
        const w = right - left;
        const h = bottom - top;
        this.ctx.beginPath();
        this.ctx.roundRect(left, top, w, h, [Math.min(rx, ry)]);
        this.fillOrStroke(
            () => { this.ctx.fill(); },
            () => { this.ctx.stroke(); }
        );
    }

    drawArc(left: number, top: number, right: number, bottom: number, startAngle: number, sweepAngle: number): void {
        const cx = (left + right) / 2;
        const cy = (top + bottom) / 2;
        const rx = (right - left) / 2;
        const ry = (bottom - top) / 2;
        const start = (startAngle * Math.PI) / 180;
        const end = ((startAngle + sweepAngle) * Math.PI) / 180;
        this.ctx.beginPath();
        this.ctx.ellipse(cx, cy, rx, ry, 0, start, end, sweepAngle < 0);
        this.fillOrStroke(
            () => { this.ctx.fill(); },
            () => { this.ctx.stroke(); }
        );
    }

    drawSector(left: number, top: number, right: number, bottom: number, startAngle: number, sweepAngle: number): void {
        const cx = (left + right) / 2;
        const cy = (top + bottom) / 2;
        const rx = (right - left) / 2;
        const ry = (bottom - top) / 2;
        const start = (startAngle * Math.PI) / 180;
        const end = ((startAngle + sweepAngle) * Math.PI) / 180;
        this.ctx.beginPath();
        this.ctx.moveTo(cx, cy);
        this.ctx.ellipse(cx, cy, rx, ry, 0, start, end, sweepAngle < 0);
        this.ctx.closePath();
        this.fillOrStroke(
            () => { this.ctx.fill(); },
            () => { this.ctx.stroke(); }
        );
    }

    drawPath(id: number, start: number, end: number): void {
        const data = this.pathDataCache.get(id);
        if (!data) return;
        const path = this.buildPath2D(data, start, end);
        this.fillOrStroke(
            () => { this.ctx.fill(path); },
            () => { this.ctx.stroke(path); }
        );
    }

    drawTextRun(textId: number, start: number, end: number,
                _contextStart: number, _contextEnd: number,
                x: number, y: number, _rtl: boolean): void {
        const text = this.textCache.get(textId);
        if (!text) return;
        const s = start >= 0 ? start : 0;
        const e = end >= 0 ? Math.min(end, text.length) : text.length;
        const substr = text.substring(s, e);
        this.setFont();
        this.fillOrStroke(
            () => { this.applyFillStyle(); this.ctx.fillText(substr, x, y); },
            () => { this.applyStrokeStyle(); this.ctx.strokeText(substr, x, y); }
        );
    }

    drawTextOnPath(textId: number, pathId: number, hOffset: number, vOffset: number): void {
        const text = this.textCache.get(textId);
        if (!text) return;
        const data = this.pathDataCache.get(pathId);
        if (!data) return;

        const { segments, totalLen } = this.collectPathSegments(data);
        if (totalLen === 0 || segments.length === 0) return;

        this.setFont();
        this.ctx.textBaseline = 'alphabetic';
        let progress = hOffset;

        for (let ci = 0; ci < text.length; ci++) {
            const ch = text[ci];
            const charWidth = this.ctx.measureText(ch).width;
            const charCenter = progress + charWidth / 2;

            if (charCenter >= 0 && charCenter <= totalLen) {
                this.ctx.save();
                this.positionOnPath(segments, charCenter, vOffset);
                this.fillOrStroke(
                    () => { this.applyFillStyle(); this.ctx.fillText(ch, -charWidth / 2, 0); },
                    () => { this.applyStrokeStyle(); this.ctx.strokeText(ch, -charWidth / 2, 0); }
                );
                this.ctx.restore();
            }
            progress += charWidth;
        }
    }

    getTextBounds(textId: number, start: number, end: number, flags: number, bounds: Float32Array): void {
        const text = this.textCache.get(textId);
        if (!text) { bounds.fill(0); return; }
        const s = start >= 0 ? start : 0;
        const e = end >= 0 ? Math.min(end, text.length) : text.length;
        const substr = text.substring(s, e);
        this.setFont();
        const metrics: any = this.ctx.measureText(substr);

        // Vertical extent, in order of preference:
        //   1. the font box, which is what TEXT_MEASURE_FONT_HEIGHT asks for and what
        //      makes a line of digits the same height as one with descenders;
        //   2. the glyph ink box;
        //   3. an estimate proportional to the text size.
        //
        // The estimate is the important part. Where the canvas reports no metrics at all
        // — some fonts, some emoji, some headless builds — the first two are 0, height
        // came back 0, and text laid out with a real width and no height whatsoever, so
        // it never painted. The ratios are approximate on purpose: a proportional,
        // deterministic height is what layout needs, and exact agreement with a real font
        // was never on offer here.
        const size = this.textSize > 0 ? this.textSize : DEFAULT_TEXT_SIZE;
        const wantFontBox = (flags & 0x02 /* MEASURE_MAX_HEIGHT_FLAG */) !== 0;
        const pick = (...vals: any[]) => {
            for (const v of vals) if (typeof v === 'number' && isFinite(v) && v > 0) return v;
            return 0;
        };
        let ascent = wantFontBox
            ? pick(metrics.fontBoundingBoxAscent, metrics.actualBoundingBoxAscent)
            : pick(metrics.actualBoundingBoxAscent, metrics.fontBoundingBoxAscent);
        let descent = wantFontBox
            ? pick(metrics.fontBoundingBoxDescent, metrics.actualBoundingBoxDescent)
            : pick(metrics.actualBoundingBoxDescent, metrics.fontBoundingBoxDescent);
        if (ascent <= 0 && descent <= 0) {
            // Calibrated against a device: text size 18 measured a ~20.8px line there,
            // split about 0.92 / 0.24 the way Roboto's own metrics are.
            ascent = ASCENT_RATIO * size;
            descent = DESCENT_RATIO * size;
        }

        const advance = pick(metrics.width, substr.length * AVG_ADVANCE * size);

        // Canvas reports distances from the alignment point, whereas Android's
        // Paint.getTextBounds returns baseline-relative coordinates. Negating the left distance
        // converts it to the same coordinate vocabulary (a glyph beginning 3px after the pen has
        // left=3, not actualBoundingBoxLeft=-3). Engines without ink metrics retain the historical
        // advance box fallback.
        const actualLeft = metrics.actualBoundingBoxLeft;
        const actualRight = metrics.actualBoundingBoxRight;
        let left = typeof actualLeft === 'number' && isFinite(actualLeft) ? -actualLeft : 0;
        let right = typeof actualRight === 'number' && isFinite(actualRight) ? actualRight : advance;

        // Match AndroidPaintContext's flag order exactly. 0x04 is the unnamed advance flag; 0x01
        // is AndroidX's MEASURE_MONOSPACE_FLAG. When both are present, advance wins.
        if ((flags & 0x04) !== 0) {
            left = 0;
            right = advance;
        } else if ((flags & 0x01) !== 0) {
            right = advance - left;
        }

        bounds[0] = left;
        bounds[1] = -ascent;
        bounds[2] = right;
        bounds[3] = descent;
    }

    layoutComplexText(textId: number, start: number, end: number, alignment: number,
                      overflow: number, maxLines: number, maxWidth: number, maxHeight: number,
                      _letterSpacing: number, _lineHeightAdd: number, lineHeightMultiplier: number,
                      _lineBreakStrategy: number, _hyphenationFrequency: number,
                      _justificationMode: number, _useUnderline: boolean,
                      _strikethrough: boolean, _flags: number): any {
        const str = this.textCache.get(textId);
        if (!str) return null;
        const s = start >= 0 ? start : 0;
        const e = (end === -1 || end > str.length) ? str.length : end;
        const text = str.substring(s, e);

        this.setFont();
        // Guard the text size the same way getTextBounds does. A zero size here produced
        // a layout with a real width and zero height, so the text occupied no space and
        // never painted — with nothing anywhere reporting an error.
        const size = this.textSize > 0 ? this.textSize : DEFAULT_TEXT_SIZE;
        const lineHeight = size * (lineHeightMultiplier || 1.2);

        // AndroidX keeps a one-line clip/visible layout unwrapped and lets the component clip the
        // glyph run. Wrapping it first loses the partial word at the right edge.
        if (maxLines === 1 && (overflow === 1 || overflow === 2)) {
            return {
                lines: [text.replace(/\n/g, '')], alignment, lineHeight,
                width: Math.min(this.ctx.measureText(text).width, maxWidth),
                height: Math.min(lineHeight, maxHeight), naturalHeight: lineHeight, visibleLines: 1
            };
        }

        // Word-wrap text to fit maxWidth, honoring embedded newlines as hard breaks. Build the
        // complete paragraph first: the Java player ignores maxLines for multi-line clip/visible
        // text, while ellipsis modes cap it after layout.
        const lines: string[] = [];
        // First split on hard newlines, then word-wrap each paragraph
        const paragraphs = text.split('\n');
        for (let pi = 0; pi < paragraphs.length; pi++) {
            const para = paragraphs[pi];
            const words = para.split(/(\s+)/);
            let currentLine = '';
            for (const word of words) {
                const testLine = currentLine + word;
                const metrics = this.ctx.measureText(testLine);
                if (metrics.width > maxWidth && currentLine.length > 0) {
                    lines.push(currentLine);
                    currentLine = word.trimStart();
                } else {
                    currentLine = testLine;
                }
            }
            if (currentLine.length > 0) {
                lines.push(currentLine);
            } else if (para.length === 0) {
                // Empty paragraph = blank line from consecutive \n
                lines.push('');
            }
        }

        const ellipsizeEnd = (value: string): string => {
            while (this.ctx.measureText(value + '…').width > maxWidth && value.length > 0) {
                value = value.substring(0, value.length - 1);
            }
            return value + '…';
        };
        const ellipsizeStart = (value: string): string => {
            while (this.ctx.measureText('…' + value).width > maxWidth && value.length > 0) {
                value = value.substring(1);
            }
            return '…' + value;
        };
        const ellipsizeMiddle = (value: string): string => {
            let left = Math.ceil(value.length / 2);
            let right = left;
            while (this.ctx.measureText(value.substring(0, left) + '…' +
                    value.substring(right)).width > maxWidth && left > 0) {
                if ((value.length - right) < left) left--; else right++;
            }
            return value.substring(0, left) + '…' + value.substring(right);
        };

        // StaticLayout only makes maxLines truncate these fixtures when ellipsizing. Compose's
        // stricter clip cap is intentionally not copied here.
        if (overflow >= 3 && overflow <= 5 && maxLines > 0 && lines.length > maxLines) {
            const lastIdx = maxLines - 1;
            const value = maxLines === 1 ? text.replace(/\n/g, '') : lines[lastIdx];
            lines[lastIdx] = overflow === 4 ? ellipsizeStart(value)
                : overflow === 5 ? ellipsizeMiddle(value) : ellipsizeEnd(value);
            lines.length = maxLines;
        }

        // Compute total dimensions
        let totalWidth = 0;
        for (const line of lines) {
            const w = this.ctx.measureText(line).width;
            if (w > totalWidth) totalWidth = w;
        }
        const totalHeight = lines.length * lineHeight;

        return {
            lines, alignment, lineHeight,
            width: Math.min(totalWidth, maxWidth),
            height: Math.min(totalHeight, maxHeight),
            naturalHeight: totalHeight, visibleLines: lines.length
        };
    }

    drawComplexText(computedTextLayout: any): void {
        if (!computedTextLayout) return;
        const { lines, alignment, lineHeight, width } = computedTextLayout;
        this.setFont();
        this.ctx.textBaseline = 'top';
        for (let i = 0; i < lines.length; i++) {
            let x = 0;
            if (alignment === 2 || alignment === 6) {
                // RIGHT / END
                x = width - this.ctx.measureText(lines[i]).width;
            } else if (alignment === 3) {
                // CENTER
                x = (width - this.ctx.measureText(lines[i]).width) / 2;
            }
            this.fillOrStroke(
                () => { this.applyFillStyle(); this.ctx.fillText(lines[i], x, i * lineHeight); },
                () => { this.applyStrokeStyle(); this.ctx.strokeText(lines[i], x, i * lineHeight); }
            );
        }
    }

    drawBitmap(imageId: number, srcLeft: number, srcTop: number, srcRight: number, srcBottom: number,
               dstLeft: number, dstTop: number, dstRight: number, dstBottom: number, _cdId: number): void {
        const img = this.bitmapCache.get(imageId);
        if (!img) return;
        this.ctx.globalAlpha = this.alpha;
        this.ctx.globalCompositeOperation = this.blendMode;
        this.ctx.imageSmoothingEnabled = this.filterBitmap;
        const sw = srcRight - srcLeft;
        const sh = srcBottom - srcTop;
        const dw = dstRight - dstLeft;
        const dh = dstBottom - dstTop;
        if (sw > 0 && sh > 0) {
            this.ctx.drawImage(img, srcLeft, srcTop, sw, sh, dstLeft, dstTop, dw, dh);
        } else {
            this.ctx.drawImage(img, dstLeft, dstTop, dw, dh);
        }
    }

    drawBitmapSimple(id: number, left: number, top: number, right: number, bottom: number): void {
        const img = this.bitmapCache.get(id);
        if (!img) return;
        this.ctx.globalAlpha = this.alpha;
        this.ctx.globalCompositeOperation = this.blendMode;
        this.ctx.imageSmoothingEnabled = this.filterBitmap;
        this.ctx.drawImage(img, left, top, right - left, bottom - top);
    }

    drawTweenPath(path1Id: number, path2Id: number, tween: number, start: number, end: number): void {
        const data1 = this.pathDataCache.get(path1Id);
        const data2 = this.pathDataCache.get(path2Id);
        if (!data1 || !data2) {
            if (data1) this.drawPath(path1Id, start, end);
            return;
        }
        // Interpolate path data: commands stay the same, coordinates are lerped.
        // Operate on raw bits; decode coords via intBitsToFloat, re-encode result.
        const len = Math.min(data1.length, data2.length);
        const tweened = new Int32Array(len);
        for (let i = 0; i < len; i++) {
            const b1 = data1[i];
            if (isNaNBits(b1)) {
                // Command code / marker - keep bits from path1
                tweened[i] = b1;
            } else {
                // Coordinate value - interpolate in float space, re-encode to bits
                const f1 = intBitsToFloat(b1);
                const f2 = intBitsToFloat(data2[i]);
                tweened[i] = floatToRawIntBits(f1 + (f2 - f1) * tween);
            }
        }
        const path = this.buildPath2D(tweened, start, end);
        this.fillOrStroke(
            () => { this.ctx.fill(path); },
            () => { this.ctx.stroke(path); }
        );
    }

    tweenPath(out: number, path1: number, path2: number, tween: number): void {
        if (tween === 0) {
            const data = this.pathDataCache.get(path1);
            if (data) this.pathDataCache.set(out, Int32Array.from(data));
            return;
        }
        if (tween === 1) {
            const data = this.pathDataCache.get(path2);
            if (data) this.pathDataCache.set(out, Int32Array.from(data));
            return;
        }
        const data1 = this.pathDataCache.get(path1);
        const data2 = this.pathDataCache.get(path2);
        if (!data1 || !data2) return;
        const len = Math.min(data1.length, data2.length);
        const result = new Int32Array(len);
        for (let i = 0; i < len; i++) {
            if (isNaNBits(data1[i]) || isNaNBits(data2[i])) {
                result[i] = data1[i]; // command code / marker - keep bits from path1
            } else {
                const f1 = intBitsToFloat(data1[i]);
                const f2 = intBitsToFloat(data2[i]);
                result[i] = floatToRawIntBits((f2 - f1) * tween + f1);
            }
        }
        this.pathDataCache.set(out, result);
    }

    combinePath(_out: number, _path1: number, _path2: number, _operation: number): void {
        // Path boolean operations (DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE)
        // not natively supported in Canvas 2D API
    }

    // --- Matrix / transforms ---

    scale(scaleX: number, scaleY: number): void {
        // Clamp exactly-zero scale to near-zero to avoid corrupting canvas state.
        // node-canvas (Cairo) permanently breaks after ctx.scale(0, ...) — even
        // ctx.restore() cannot undo the damage.  A tiny epsilon is visually
        // identical to zero but keeps the transform matrix non-singular.
        const eps = 1e-10;
        if (scaleX === 0) scaleX = eps;
        if (scaleY === 0) scaleY = eps;
        this.ctx.scale(scaleX, scaleY);
    }
    translate(translateX: number, translateY: number): void { this.ctx.translate(translateX, translateY); }

    matrixSave(): void { this.ctx.save(); }
    matrixRestore(): void { this.ctx.restore(); }

    matrixTranslate(tx: number, ty: number): void { this.ctx.translate(tx, ty); }
    matrixScale(sx: number, sy: number, cx: number, cy: number): void {
        // Clamp exactly-zero scale to near-zero to avoid corrupting canvas state.
        // node-canvas (Cairo) permanently breaks after ctx.scale(0, ...) — even
        // ctx.restore() cannot undo the damage.  A tiny epsilon is visually
        // identical to zero but keeps the transform matrix non-singular.
        const eps = 1e-10;
        if (sx === 0) sx = eps;
        if (sy === 0) sy = eps;
        this.ctx.translate(cx, cy);
        this.ctx.scale(sx, sy);
        this.ctx.translate(-cx, -cy);
    }

    matrixRotate(angle: number, px: number, py: number): void {
        this.ctx.translate(px, py);
        this.ctx.rotate((angle * Math.PI) / 180);
        this.ctx.translate(-px, -py);
    }

    matrixSkew(sx: number, sy: number): void {
        this.ctx.transform(1, sy, sx, 1, 0, 0);
    }

    matrixFromPath(pathId: number, fraction: number, vOffset: number, _flags: number): void {
        const data = this.pathDataCache.get(pathId);
        if (!data) return;

        const { segments, totalLen } = this.collectPathSegments(data);
        if (totalLen === 0) return;

        let target = (totalLen * fraction) % totalLen;
        if (target < 0) target += totalLen;

        this.positionOnPath(segments, target, vOffset);
    }

    // --- Path measurement helpers ---

    /** Flatten path data into line segments (curves are subdivided). */
    private collectPathSegments(data: Int32Array): {
        segments: { x1: number; y1: number; x2: number; y2: number; len: number }[];
        totalLen: number;
    } {
        type Seg = { x1: number; y1: number; x2: number; y2: number; len: number };
        const segments: Seg[] = [];
        let curX = 0, curY = 0, startX = 0, startY = 0;
        let i = 0;

        const addLine = (x1: number, y1: number, x2: number, y2: number) => {
            const dx = x2 - x1, dy = y2 - y1;
            const len = Math.sqrt(dx * dx + dy * dy);
            if (len > 0) segments.push({ x1, y1, x2, y2, len });
        };

        while (i < data.length) {
            const bits = data[i];
            let cmd = isNaNBits(bits) ? idFromBits(bits) : intBitsToFloat(bits);
            if (cmd >= CanvasPaintContext.NANMAP_PATH_BASE && cmd <= CanvasPaintContext.NANMAP_PATH_BASE + 6) {
                cmd = CanvasPaintContext.PATH_MOVE + (cmd - CanvasPaintContext.NANMAP_PATH_BASE);
            }
            switch (cmd) {
                case CanvasPaintContext.PATH_MOVE:
                    i++; curX = startX = intBitsToFloat(data[i]); curY = startY = intBitsToFloat(data[i + 1]); i += 2; break;
                case CanvasPaintContext.PATH_LINE: {
                    i += 3;
                    const ex = intBitsToFloat(data[i]), ey = intBitsToFloat(data[i + 1]); i += 2;
                    addLine(curX, curY, ex, ey);
                    curX = ex; curY = ey; break;
                }
                case CanvasPaintContext.PATH_QUADRATIC: {
                    i += 3;
                    const cpx = intBitsToFloat(data[i]), cpy = intBitsToFloat(data[i + 1]);
                    const ex = intBitsToFloat(data[i + 2]), ey = intBitsToFloat(data[i + 3]); i += 4;
                    this.flattenQuadratic(curX, curY, cpx, cpy, ex, ey, addLine);
                    curX = ex; curY = ey; break;
                }
                case CanvasPaintContext.PATH_CONIC: {
                    i += 3;
                    const cpx = intBitsToFloat(data[i]), cpy = intBitsToFloat(data[i + 1]);
                    const ex = intBitsToFloat(data[i + 2]), ey = intBitsToFloat(data[i + 3]);
                    i += 5; // skip weight
                    this.flattenQuadratic(curX, curY, cpx, cpy, ex, ey, addLine);
                    curX = ex; curY = ey; break;
                }
                case CanvasPaintContext.PATH_CUBIC: {
                    i += 3;
                    const cp1x = intBitsToFloat(data[i]), cp1y = intBitsToFloat(data[i + 1]);
                    const cp2x = intBitsToFloat(data[i + 2]), cp2y = intBitsToFloat(data[i + 3]);
                    const ex = intBitsToFloat(data[i + 4]), ey = intBitsToFloat(data[i + 5]); i += 6;
                    this.flattenCubic(curX, curY, cp1x, cp1y, cp2x, cp2y, ex, ey, addLine);
                    curX = ex; curY = ey; break;
                }
                case CanvasPaintContext.PATH_CLOSE: {
                    i++;
                    addLine(curX, curY, startX, startY);
                    curX = startX; curY = startY; break;
                }
                case CanvasPaintContext.PATH_DONE: i = data.length; break;
                default: i++; break;
            }
        }
        let totalLen = 0;
        for (const s of segments) totalLen += s.len;
        return { segments, totalLen };
    }

    /** Subdivide a quadratic bezier into line segments. */
    private flattenQuadratic(
        x0: number, y0: number, cpx: number, cpy: number, x1: number, y1: number,
        addLine: (x1: number, y1: number, x2: number, y2: number) => void
    ): void {
        const N = 16;
        let px = x0, py = y0;
        for (let j = 1; j <= N; j++) {
            const t = j / N, mt = 1 - t;
            const x = mt * mt * x0 + 2 * mt * t * cpx + t * t * x1;
            const y = mt * mt * y0 + 2 * mt * t * cpy + t * t * y1;
            addLine(px, py, x, y);
            px = x; py = y;
        }
    }

    /** Subdivide a cubic bezier into line segments. */
    private flattenCubic(
        x0: number, y0: number, cp1x: number, cp1y: number,
        cp2x: number, cp2y: number, x1: number, y1: number,
        addLine: (x1: number, y1: number, x2: number, y2: number) => void
    ): void {
        const N = 16;
        let px = x0, py = y0;
        for (let j = 1; j <= N; j++) {
            const t = j / N, mt = 1 - t;
            const x = mt*mt*mt*x0 + 3*mt*mt*t*cp1x + 3*mt*t*t*cp2x + t*t*t*x1;
            const y = mt*mt*mt*y0 + 3*mt*mt*t*cp1y + 3*mt*t*t*cp2y + t*t*t*y1;
            addLine(px, py, x, y);
            px = x; py = y;
        }
    }

    /** Translate and rotate the canvas to a point at the given distance along segments. */
    private positionOnPath(
        segments: { x1: number; y1: number; x2: number; y2: number; len: number }[],
        distance: number, vOffset: number
    ): void {
        let accum = 0;
        for (const s of segments) {
            if (accum + s.len >= distance || s === segments[segments.length - 1]) {
                const t = s.len > 0 ? (distance - accum) / s.len : 0;
                const px = s.x1 + (s.x2 - s.x1) * t;
                const py = s.y1 + (s.y2 - s.y1) * t;
                const angle = Math.atan2(s.y2 - s.y1, s.x2 - s.x1);
                // Apply vOffset perpendicular to the path tangent
                const nx = -Math.sin(angle) * vOffset;
                const ny =  Math.cos(angle) * vOffset;
                this.ctx.translate(px + nx, py + ny);
                this.ctx.rotate(angle);
                return;
            }
            accum += s.len;
        }
    }

    // --- Clipping ---

    clipRect(left: number, top: number, right: number, bottom: number): void {
        this.ctx.beginPath();
        this.ctx.rect(left, top, right - left, bottom - top);
        this.ctx.clip();
    }

    clipPath(pathId: number, _regionOp: number): void {
        const data = this.pathDataCache.get(pathId);
        if (!data) return;
        const path = this.buildPath2D(data);
        this.ctx.clip(path);
    }

    roundedClipRect(width: number, height: number, topStart: number, topEnd: number,
                    bottomStart: number, bottomEnd: number): void {
        // `roundRect` silently ignores the whole call when a radius is non-finite — leaving `clip()`
        // an empty path, which hides every draw inside the component rather than merely losing its
        // rounded corners — and throws outright on a negative one, which aborts the paint. Sanitise
        // just those two cases (unresolvable or negative → square corner) so a radius bug is cosmetic
        // (#2930). Over-large radii are deliberately passed through: Canvas scales an *overlapping*
        // set down proportionally on its own, and a single large corner on an oblong rect is valid
        // geometry — clamping every corner to half the shorter side would rewrite shapes that
        // render correctly today and that the embedded player draws unmodified.
        const r = (v: number) => (Number.isFinite(v) && v > 0 ? v : 0);
        this.ctx.beginPath();
        this.ctx.roundRect(0, 0, width, height,
            [r(topStart), r(topEnd), r(bottomEnd), r(bottomStart)]);
        this.ctx.clip();
    }

    // --- Graphics layer (offscreen compositing) ---

    // GraphicsLayer attribute IDs (from GraphicsLayerModifierOperation.java)
    private static readonly GL_SCALE_X = 0;
    private static readonly GL_SCALE_Y = 1;
    private static readonly GL_ROTATION_Z = 4;
    private static readonly GL_TRANSFORM_ORIGIN_X = 5;
    private static readonly GL_TRANSFORM_ORIGIN_Y = 6;
    private static readonly GL_TRANSLATION_X = 7;
    private static readonly GL_TRANSLATION_Y = 8;
    private static readonly GL_ALPHA = 11;
    private static readonly GL_SHAPE = 20;
    private static readonly GL_SHAPE_RADIUS = 21;

    startGraphicsLayer(w: number, h: number): void {
        this.ctx.save();
        try {
            const offCtx = this.createLayerCanvas(w, h);
            this.layerStack.push({
                previousCtx: this.ctx,
                offscreenCanvas: offCtx.canvas,
                width: w,
                height: h,
                attributes: new Map()
            });
            this.ctx = offCtx;
        } catch (_e) {
            // Fallback: no layer isolation, just use save/restore
        }
    }

    setGraphicsLayer(attributes: Map<number, any>): void {
        const layer = this.layerStack[this.layerStack.length - 1];
        if (layer) {
            layer.attributes = attributes;
        }
    }

    endGraphicsLayer(): void {
        const layer = this.layerStack.pop();
        if (!layer) {
            this.ctx.restore();
            return;
        }

        const layerCanvas = layer.offscreenCanvas;
        this.ctx = layer.previousCtx;

        // Apply transforms from layer attributes before compositing
        const attrs = layer.attributes;
        const scaleX = (attrs.get(CanvasPaintContext.GL_SCALE_X) as number) ?? 1;
        const scaleY = (attrs.get(CanvasPaintContext.GL_SCALE_Y) as number) ?? 1;
        const rotationZ = (attrs.get(CanvasPaintContext.GL_ROTATION_Z) as number) ?? 0;
        const transX = (attrs.get(CanvasPaintContext.GL_TRANSLATION_X) as number) ?? 0;
        const transY = (attrs.get(CanvasPaintContext.GL_TRANSLATION_Y) as number) ?? 0;
        const originX = (attrs.get(CanvasPaintContext.GL_TRANSFORM_ORIGIN_X) as number) ?? 0.5;
        const originY = (attrs.get(CanvasPaintContext.GL_TRANSFORM_ORIGIN_Y) as number) ?? 0.5;
        const alpha = (attrs.get(CanvasPaintContext.GL_ALPHA) as number) ?? 1;
        const shape = attrs.get(CanvasPaintContext.GL_SHAPE) as number | undefined;
        const shapeRadius = (attrs.get(CanvasPaintContext.GL_SHAPE_RADIUS) as number) ?? 0;

        const pivotX = originX * layer.width;
        const pivotY = originY * layer.height;

        const hasTransform = scaleX !== 1 || scaleY !== 1 || rotationZ !== 0 || transX !== 0 || transY !== 0;

        if (hasTransform) {
            this.ctx.translate(pivotX + transX, pivotY + transY);
            if (rotationZ !== 0) {
                this.ctx.rotate((rotationZ * Math.PI) / 180);
            }
            if (scaleX !== 1 || scaleY !== 1) {
                this.ctx.scale(scaleX, scaleY);
            }
            this.ctx.translate(-pivotX, -pivotY);
        }

        // Apply clip shape if specified
        if (shape !== undefined) {
            this.ctx.beginPath();
            if (shape === 2) {
                // Circle
                const r = Math.min(layer.width, layer.height) / 2;
                this.ctx.arc(layer.width / 2, layer.height / 2, r, 0, Math.PI * 2);
            } else if (shape === 1 && shapeRadius > 0) {
                // Round rect
                this.ctx.roundRect(0, 0, layer.width, layer.height, [shapeRadius]);
            } else {
                // Rect
                this.ctx.rect(0, 0, layer.width, layer.height);
            }
            this.ctx.clip();
        }

        // Set layer alpha
        if (alpha !== 1) {
            this.ctx.globalAlpha = alpha;
        }

        // Composite the offscreen layer onto the main canvas
        this.ctx.drawImage(layerCanvas, 0, 0);

        this.ctx.restore();
    }

    private mainCanvas: CanvasRenderingContext2D | null = null;
    private bitmapCanvasCache = new Map<number, CanvasRenderingContext2D>();

    drawToBitmap(bitmapId: number, mode: number, color: number): void {
        if (this.mainCanvas === null) {
            this.mainCanvas = this.ctx;
        }
        if (bitmapId === 0) {
            // Return to main canvas
            this.ctx = this.mainCanvas;
            return;
        }
        // Get or create an offscreen canvas for this bitmap
        let offCtx = this.bitmapCanvasCache.get(bitmapId);
        if (!offCtx) {
            // Look up the bitmap dimensions
            const img = this.bitmapCache.get(bitmapId);
            const w = img ? (img as any).width || 256 : 256;
            const h = img ? (img as any).height || 256 : 256;
            offCtx = this.createLayerCanvas(w, h);
            this.bitmapCanvasCache.set(bitmapId, offCtx);
        }
        if ((mode & 1) === 0) {
            // Clear with the specified color
            const canvas = offCtx.canvas;
            offCtx.clearRect(0, 0, (canvas as any).width, (canvas as any).height);
            if (color !== 0) {
                offCtx.fillStyle = argbToRgba(color);
                offCtx.fillRect(0, 0, (canvas as any).width, (canvas as any).height);
            }
        }
        this.ctx = offCtx;
    }

    reset(): void {
        this.resetPaintState();
        this.clearNeedsRepaint();
    }
}
