// PaintBundle: flat int-array encoding of paint changes, matching Java PaintBundle format.
// Uses a dual-buffer pattern: mArray stores original data (with NaN-encoded variable IDs),
// mOutArray stores resolved values for rendering.

import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';

// Shared DataView for int-bits-to-float conversion
const _f32dv = new DataView(new ArrayBuffer(4));

export function intBitsToFloat(bits: number): number {
    _f32dv.setInt32(0, bits);
    return _f32dv.getFloat32(0);
}

function floatToRawIntBits(v: number): number {
    _f32dv.setFloat32(0, v);
    return _f32dv.getInt32(0);
}

/**
 * True if raw float32 int bits encode a NaN-with-payload (a variable ref token).
 * Classifies directly from the bits — never round-trips through a float value —
 * so the encoded id survives engines that canonicalize NaN payloads (Safari/Firefox).
 */
function isNaNBitsLocal(bits: number): boolean {
    return (bits & 0x7F800000) === 0x7F800000 && (bits & 0x007FFFFF) !== 0;
}

/** Decode the variable id from raw float32 int bits. */
function idFromBitsLocal(bits: number): number {
    return bits & 0x3FFFFF;
}

/** Resolve a NaN-encoded float variable ID (raw bits) to its current value, returning int bits. */
function fixFloatVar(val: number, context: RemoteContext): number {
    if (isNaNBitsLocal(val)) {
        return floatToRawIntBits(context.getFloat(idFromBitsLocal(val)));
    }
    return val;
}

/** Resolve a color ID to the actual ARGB color value. */
function fixColor(val: number, context: RemoteContext): number {
    return context.getColor(val);
}

function listenFloatVar(val: number, context: any, op: any): void {
    if (isNaNBitsLocal(val)) {
        context.listensTo(idFromBitsLocal(val), op);
    }
}

export class PaintBundle {
    // Paint property tags (matching Java PaintBundle constants)
    static readonly TEXT_SIZE = 1;
    static readonly COLOR = 4;
    static readonly STROKE_WIDTH = 5;
    static readonly STROKE_MITER = 6;
    static readonly STROKE_CAP = 7;
    static readonly STYLE = 8;
    static readonly SHADER = 9;
    static readonly IMAGE_FILTER_QUALITY = 10;
    static readonly GRADIENT = 11;
    static readonly ALPHA = 12;
    static readonly COLOR_FILTER = 13;
    static readonly ANTI_ALIAS = 14;
    static readonly STROKE_JOIN = 15;
    static readonly TYPEFACE = 16;
    static readonly FILTER_BITMAP = 17;
    static readonly BLEND_MODE = 18;
    static readonly COLOR_ID = 19;
    static readonly COLOR_FILTER_ID = 20;
    static readonly CLEAR_COLOR_FILTER = 21;
    static readonly SHADER_MATRIX = 22;
    static readonly FONT_AXIS = 23;
    static readonly TEXTURE = 24;
    static readonly PATH_EFFECT = 25;
    static readonly FALLBACK_TYPEFACE = 26;

    // Gradient types (wire format values)
    static readonly LINEAR_GRADIENT = 0;
    static readonly RADIAL_GRADIENT = 1;
    static readonly SWEEP_GRADIENT = 2;

    // Style constants
    static readonly FILL = 0;
    static readonly STROKE = 1;
    static readonly FILL_AND_STROKE = 2;

    // The flat int array and current position
    mArray: number[] = [];
    mOutArray: number[] | null = null;
    mPos = 0;

    read(buffer: WireBuffer): void {
        const len = buffer.readInt();
        if (len <= 0 || len > 1024) {
            throw new Error(`PaintBundle: invalid length ${len}`);
        }
        this.mArray = new Array(len);
        for (let i = 0; i < len; i++) {
            this.mArray[i] = buffer.readInt();
        }
        this.mPos = len;
    }

    /** Returns the resolved array (mOutArray if available, otherwise mArray). */
    getArray(): number[] { return this.mOutArray ?? this.mArray; }
    getLength(): number { return this.mPos; }

    // --- Programmatic setters for building paint bundles at runtime ---

    reset(): void {
        this.mArray = [];
        this.mOutArray = null;
        this.mPos = 0;
    }

    setStyle(style: number): void {
        this.mArray[this.mPos++] = PaintBundle.STYLE | (style << 16);
    }

    setColor(color: number): void;
    setColor(a: number, r: number, g: number, b: number): void;
    setColor(aOrColor: number, r?: number, g?: number, b?: number): void {
        if (r !== undefined && g !== undefined && b !== undefined) {
            const ai = Math.round(aOrColor * 255) & 0xFF;
            const ri = Math.round(r * 255) & 0xFF;
            const gi = Math.round(g * 255) & 0xFF;
            const bi = Math.round(b * 255) & 0xFF;
            aOrColor = ((ai << 24) | (ri << 16) | (gi << 8) | bi) >>> 0;
        }
        this.mArray[this.mPos++] = PaintBundle.COLOR;
        this.mArray[this.mPos++] = aOrColor;
    }

    setTextSize(size: number): void {
        this.mArray[this.mPos++] = PaintBundle.TEXT_SIZE;
        _f32dv.setFloat32(0, size);
        this.mArray[this.mPos++] = _f32dv.getInt32(0);
    }

    setTextStyle(typeface: number, fontWeight: number, italic: boolean): void {
        // Mirror the Java packing in PaintBundle.java:setTextStyle:
        //   cmd  = TYPEFACE | (style << 16)
        //   style = (weight & 0x3FF) | (italic ? 2048 : 0)
        //   value = typeface (the font family enum)
        // Without this packing the weight and italic flags are never
        // serialised, so any doc authored via the TS writer renders bold
        // / italic as regular upright.
        const style = (fontWeight & 0x3FF) | (italic ? 2048 : 0);
        this.mArray[this.mPos++] = PaintBundle.TYPEFACE | (style << 16);
        this.mArray[this.mPos++] = typeface;
    }

    setTextAxis(axisTags: number[], axisValues: number[]): void {
        const count = axisTags.length;
        this.mArray[this.mPos++] = PaintBundle.FONT_AXIS | (count << 16);
        for (let i = 0; i < count; i++) {
            this.mArray[this.mPos++] = axisTags[i];
            _f32dv.setFloat32(0, axisValues[i]);
            this.mArray[this.mPos++] = _f32dv.getInt32(0);
        }
    }

    setStrokeWidth(width: number): void {
        this.mArray[this.mPos++] = PaintBundle.STROKE_WIDTH;
        _f32dv.setFloat32(0, width);
        this.mArray[this.mPos++] = _f32dv.getInt32(0);
    }

    registerListening(context: any, op: any): void {
        let i = 0;
        const arr = this.mArray;
        while (i < this.mPos) {
            const cmd = arr[i++];
            const tag = cmd & 0xFFFF;
            switch (tag) {
                case PaintBundle.STROKE_MITER:
                case PaintBundle.STROKE_WIDTH:
                case PaintBundle.ALPHA:
                case PaintBundle.TEXT_SIZE:
                    listenFloatVar(arr[i], context, op);
                    i++;
                    break;
                case PaintBundle.COLOR_FILTER_ID:
                case PaintBundle.COLOR_ID:
                    context.listensTo(arr[i], op);
                    i++;
                    break;
                case PaintBundle.COLOR:
                case PaintBundle.TYPEFACE:
                case PaintBundle.SHADER:
                case PaintBundle.COLOR_FILTER:
                case PaintBundle.FALLBACK_TYPEFACE:
                    i++;
                    break;
                case PaintBundle.STROKE_JOIN:
                case PaintBundle.FILTER_BITMAP:
                case PaintBundle.STROKE_CAP:
                case PaintBundle.STYLE:
                case PaintBundle.IMAGE_FILTER_QUALITY:
                case PaintBundle.BLEND_MODE:
                case PaintBundle.ANTI_ALIAS:
                case PaintBundle.CLEAR_COLOR_FILTER:
                    break;
                case PaintBundle.FONT_AXIS: {
                    const count = (cmd >> 16) & 0xFFFF;
                    for (let j = 0; j < count; j++) {
                        i++; // tag ID
                        listenFloatVar(arr[i], context, op);
                        i++;
                    }
                    break;
                }
                case PaintBundle.TEXTURE:
                    i += 3;
                    break;
                case PaintBundle.SHADER_MATRIX:
                    i++;
                    break;
                case PaintBundle.GRADIENT:
                    i = this.registerGradientListening(cmd, arr, i, context, op);
                    break;
                case PaintBundle.PATH_EFFECT: {
                    const count = (cmd >> 16) & 0xFFFF;
                    for (let j = 0; j < count; j++) {
                        listenFloatVar(arr[i], context, op);
                        i++;
                    }
                    listenFloatVar(arr[i], context, op); // phase
                    i++;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private registerGradientListening(cmd: number, arr: number[], startIdx: number,
                                       context: any, op: any): number {
        let i = startIdx;
        const meta = arr[i++];
        const numColors = meta & 0xFF;
        const register = (meta >> 16) & 0xFFFF;
        for (let j = 0; j < numColors; j++) {
            if ((register & (1 << j)) !== 0) {
                context.listensTo(arr[i], op);
            }
            i++;
        }
        const numStops = arr[i++];
        for (let j = 0; j < numStops; j++) {
            listenFloatVar(arr[i], context, op);
            i++;
        }
        const gradType = (cmd >> 16) & 0xFFFF;
        switch (gradType) {
            case PaintBundle.LINEAR_GRADIENT:
                for (let j = 0; j < 4; j++) { listenFloatVar(arr[i], context, op); i++; }
                i++; // tileMode
                break;
            case PaintBundle.RADIAL_GRADIENT:
                for (let j = 0; j < 3; j++) { listenFloatVar(arr[i], context, op); i++; }
                i++; // tileMode
                break;
            case PaintBundle.SWEEP_GRADIENT:
                for (let j = 0; j < 2; j++) { listenFloatVar(arr[i], context, op); i++; }
                break;
        }
        return i;
    }

    /**
     * Resolve NaN-encoded float variable IDs and color IDs in paint parameters.
     * Matches Java PaintBundle.updateVariables().
     */
    updateVariables(context: RemoteContext): void {
        if (this.mOutArray === null) {
            this.mOutArray = this.mArray.slice();
        } else {
            for (let j = 0; j < this.mArray.length; j++) {
                this.mOutArray[j] = this.mArray[j];
            }
        }
        let i = 0;
        const arr = this.mArray;
        const out = this.mOutArray;
        while (i < this.mPos) {
            const cmd = arr[i++];
            const tag = cmd & 0xFFFF;
            switch (tag) {
                case PaintBundle.STROKE_MITER:
                case PaintBundle.STROKE_WIDTH:
                case PaintBundle.ALPHA:
                case PaintBundle.TEXT_SIZE:
                    out[i] = fixFloatVar(arr[i], context);
                    i++;
                    break;
                case PaintBundle.COLOR_FILTER_ID:
                case PaintBundle.COLOR_ID:
                    out[i] = fixColor(arr[i], context);
                    i++;
                    break;
                case PaintBundle.COLOR:
                case PaintBundle.TYPEFACE:
                case PaintBundle.SHADER:
                case PaintBundle.COLOR_FILTER:
                case PaintBundle.FALLBACK_TYPEFACE:
                    i++;
                    break;
                case PaintBundle.STROKE_JOIN:
                case PaintBundle.FILTER_BITMAP:
                case PaintBundle.STROKE_CAP:
                case PaintBundle.STYLE:
                case PaintBundle.IMAGE_FILTER_QUALITY:
                case PaintBundle.BLEND_MODE:
                case PaintBundle.ANTI_ALIAS:
                case PaintBundle.CLEAR_COLOR_FILTER:
                    break;
                case PaintBundle.FONT_AXIS: {
                    const count = (cmd >> 16) & 0xFFFF;
                    for (let j = 0; j < count; j++) {
                        i++; // tag ID
                        out[i] = fixFloatVar(arr[i], context);
                        i++;
                    }
                    break;
                }
                case PaintBundle.TEXTURE:
                    i += 3;
                    break;
                case PaintBundle.SHADER_MATRIX:
                    i++;
                    break;
                case PaintBundle.GRADIENT:
                    i = this.updateGradientVars(cmd, arr, out, i, context);
                    break;
                case PaintBundle.PATH_EFFECT: {
                    const count = (cmd >> 16) & 0xFFFF;
                    for (let j = 0; j < count; j++) {
                        out[i] = fixFloatVar(arr[i], context);
                        i++;
                    }
                    out[i] = fixFloatVar(arr[i], context); // phase
                    i++;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private updateGradientVars(cmd: number, arr: number[], out: number[],
                               startIdx: number, context: RemoteContext): number {
        let i = startIdx;
        const gradType = (cmd >> 16) & 0xFFFF;
        const meta = arr[i++];
        const numColors = meta & 0xFF;
        const register = (meta >> 16) & 0xFFFF;

        // Colors (resolve color IDs if flagged in register bitmask)
        for (let j = 0; j < numColors; j++) {
            if ((register & (1 << j)) !== 0) {
                out[i] = fixColor(arr[i], context);
            }
            i++;
        }

        // Stop positions
        const numStops = arr[i++];
        for (let j = 0; j < numStops; j++) {
            out[i] = fixFloatVar(arr[i], context);
            i++;
        }

        // Gradient-specific coordinates
        switch (gradType) {
            case PaintBundle.LINEAR_GRADIENT:
                out[i] = fixFloatVar(arr[i], context); i++; // startX
                out[i] = fixFloatVar(arr[i], context); i++; // startY
                out[i] = fixFloatVar(arr[i], context); i++; // endX
                out[i] = fixFloatVar(arr[i], context); i++; // endY
                i++; // tileMode
                break;
            case PaintBundle.RADIAL_GRADIENT:
                out[i] = fixFloatVar(arr[i], context); i++; // centerX
                out[i] = fixFloatVar(arr[i], context); i++; // centerY
                out[i] = fixFloatVar(arr[i], context); i++; // radius
                i++; // tileMode
                break;
            case PaintBundle.SWEEP_GRADIENT:
                out[i] = fixFloatVar(arr[i], context); i++; // centerX
                out[i] = fixFloatVar(arr[i], context); i++; // centerY
                break;
        }
        return i;
    }
}
