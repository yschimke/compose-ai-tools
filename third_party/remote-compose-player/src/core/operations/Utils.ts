// Utils: NaN encoding, color math, and other utilities.

// Shared DataView for float<->int bit reinterpretation
const _dv = new DataView(new ArrayBuffer(4));

export function intBitsToFloat(bits: number): number {
    _dv.setInt32(0, bits, false);
    return _dv.getFloat32(0, false);
}

export function floatToRawIntBits(value: number): number {
    _dv.setFloat32(0, value, false);
    return _dv.getInt32(0, false);
}

/** Encode an integer id as a NaN float */
export function asNan(v: number): number {
    return intBitsToFloat((v | 0) | (-0x800000));
}

/** Extract an integer id from a NaN-encoded float */
export function idFromNan(value: number): number {
    return floatToRawIntBits(value) & 0x3FFFFF;
}

/**
 * True if these raw IEEE-754 float32 *integer bits* encode a NaN (exponent all
 * ones, mantissa non-zero). Detects variable/operator/array reference tokens
 * directly from the wire bits — robust across JS engines that canonicalize NaN
 * payloads (Safari/Firefox) when a NaN passes through a float value.
 */
export function isNaNBits(bits: number): boolean {
    return (bits & 0x7F800000) === 0x7F800000 && (bits & 0x007FFFFF) !== 0;
}

/** Decode the RemoteCompose variable/operator id from raw float32 int bits. */
export function idFromBits(bits: number): number {
    return bits & 0x3FFFFF;
}

/** Convert a NaN-encoded float to a long id */
export function longIdFromNan(value: number): number {
    return idFromNan(value) + 0x100000000;
}

/** Convert a long to an ID */
export function idFromLong(v: number): number {
    return v - 0x100000000;
}

/** Convert a NaN-encoded float to its string representation */
export function idStringFromNan(value: number): string {
    const b = floatToRawIntBits(value) & 0x3FFFFF;
    return idString(b);
}

/** Format an id as a string */
export function idString(b: number): string {
    return (b > 0xFFFFF) ? `A_${b & 0xFFFFF}` : `${b}`;
}

/** Returns true if the id is a system global id (Tier 1) — never remapped. */
export function isSystemGlobal(id: number): boolean {
    return id >= 0 && id <= 41;
}

/** Returns true if the id is a macro-local id (Tier 2) — always uniqueified. */
export function isMacroLocal(id: number): boolean {
    return id >= 0x4000 && id <= 0x4FFF;
}

/** Check if a float is a variable (NaN-encoded ID that's not a system constant) */
export function isVariable(v: number): boolean {
    if (Number.isNaN(v)) {
        const id = idFromNan(v);
        if (id === 0) return false;
        return id > 40 || id < 10;
    }
    return false;
}

/**
 * Bit-aware variant of isVariable: true if the raw float32 int bits encode a
 * variable reference (NaN with a non-system, non-zero id). Robust on engines
 * that canonicalize NaN payloads.
 */
export function isVariableBits(bits: number): boolean {
    if (isNaNBits(bits)) {
        const id = idFromBits(bits);
        if (id === 0) return false;
        return id > 40 || id < 10;
    }
    return false;
}

/** Convert float to string, rendering NaN ids in bracket notation [n] */
export function floatToString(value: number): string;
export function floatToString(idValue: number, value: number): string;
export function floatToString(a: number, b?: number): string {
    if (b !== undefined) {
        // Two-arg: floatToString(idValue, value)
        if (Number.isNaN(a)) {
            if (idFromNan(b) === 0) return 'NaN';
            return `[${idFromNan(a)}]${floatToString(b)}`;
        }
        return floatToString(b);
    }
    // One-arg: floatToString(value)
    if (Number.isNaN(a)) {
        if (idFromNan(a) === 0) return 'NaN';
        return `[${idFromNan(a)}]`;
    }
    return a.toString();
}

/** Trim a string to n chars, ending with "..." if trimmed */
export function trimString(str: string, n: number): string {
    if (str.length > n) {
        return str.substring(0, n - 3) + '...';
    }
    return str;
}

/** Print color as 0xAARRGGBB hex string */
export function colorInt(color: number): string {
    const hex = (color >>> 0).toString(16).padStart(8, '0');
    return '0x' + hex;
}

/** Efficient 0-255 clamping */
export function clamp(c: number): number {
    let n = 255;
    c &= ~(c >> 31);
    c -= n;
    c &= (c >> 31);
    c += n;
    return c;
}

/** Interpolate two colors with gamma correction */
export function interpolateColor(c1: number, c2: number, t: number): number {
    if (Number.isNaN(t) || t === 0.0) return c1;
    if (t === 1.0) return c2;

    let a = (c1 >> 24) & 0xFF;
    let r = (c1 >> 16) & 0xFF;
    let g = (c1 >> 8) & 0xFF;
    let b = c1 & 0xFF;
    const c1fr = Math.pow(r / 255, 2.2);
    const c1fg = Math.pow(g / 255, 2.2);
    const c1fb = Math.pow(b / 255, 2.2);
    const c1fa = a / 255;

    a = (c2 >> 24) & 0xFF;
    r = (c2 >> 16) & 0xFF;
    g = (c2 >> 8) & 0xFF;
    b = c2 & 0xFF;
    const c2fr = Math.pow(r / 255, 2.2);
    const c2fg = Math.pow(g / 255, 2.2);
    const c2fb = Math.pow(b / 255, 2.2);
    const c2fa = a / 255;

    const f_r = c1fr + t * (c2fr - c1fr);
    const f_g = c1fg + t * (c2fg - c1fg);
    const f_b = c1fb + t * (c2fb - c1fb);
    const f_a = c1fa + t * (c2fa - c1fa);

    const outr = clamp(Math.trunc(Math.pow(f_r, 1.0 / 2.2) * 255));
    const outg = clamp(Math.trunc(Math.pow(f_g, 1.0 / 2.2) * 255));
    const outb = clamp(Math.trunc(Math.pow(f_b, 1.0 / 2.2) * 255));
    const outa = clamp(Math.trunc(f_a * 255));

    return ((outa << 24) | (outr << 16) | (outg << 8) | outb) | 0;
}

/** Convert HSV to RGB (all values 0..1) */
export function hsvToRgb(hue: number, saturation: number, value: number): number {
    const h = Math.trunc(hue * 6);
    const f = hue * 6 - h;
    const p = Math.trunc(0.5 + 255 * value * (1 - saturation));
    const q = Math.trunc(0.5 + 255 * value * (1 - f * saturation));
    const t = Math.trunc(0.5 + 255 * value * (1 - (1 - f) * saturation));
    const v = Math.trunc(0.5 + 255 * value);
    switch (h) {
        case 0: return (0xFF000000 | (v << 16) | (t << 8) | p) | 0;
        case 1: return (0xFF000000 | (q << 16) | (v << 8) | p) | 0;
        case 2: return (0xFF000000 | (p << 16) | (v << 8) | t) | 0;
        case 3: return (0xFF000000 | (p << 16) | (q << 8) | v) | 0;
        case 4: return (0xFF000000 | (t << 16) | (p << 8) | v) | 0;
        case 5: return (0xFF000000 | (v << 16) | (p << 8) | q) | 0;
    }
    return 0;
}

/** Convert float ARGB to int ARGB */
export function toARGB(alpha: number, red: number, green: number, blue: number): number {
    const a = Math.trunc(alpha * 255.0 + 0.5);
    const r = Math.trunc(red * 255.0 + 0.5);
    const g = Math.trunc(green * 255.0 + 0.5);
    const b = Math.trunc(blue * 255.0 + 0.5);
    return ((a << 24) | (r << 16) | (g << 8) | b) | 0;
}

/** Extract hue from ARGB int (returns 0..1) */
export function getHue(argb: number): number {
    const r = (argb >> 16) & 0xFF;
    const g = (argb >> 8) & 0xFF;
    const b = argb & 0xFF;
    const rf = r / 255, gf = g / 255, bf = b / 255;
    const max = Math.max(rf, gf, bf);
    const min = Math.min(rf, gf, bf);
    const delta = max - min;
    let h: number;
    if (max === min) {
        h = 0;
    } else if (max === rf) {
        h = ((gf - bf) / delta) % 6;
    } else if (max === gf) {
        h = ((bf - rf) / delta) + 2;
    } else {
        h = ((rf - gf) / delta) + 4;
    }
    h = (h * 60) % 360;
    if (h < 0) h += 360;
    return h / 360;
}

/** Extract saturation from ARGB int (returns 0..1) */
export function getSaturation(argb: number): number {
    const r = (argb >> 16) & 0xFF;
    const g = (argb >> 8) & 0xFF;
    const b = argb & 0xFF;
    const rf = r / 255, gf = g / 255, bf = b / 255;
    const max = Math.max(rf, gf, bf);
    const min = Math.min(rf, gf, bf);
    if (max === min) return 0;
    return (max - min) / max;
}

/** Extract brightness/value from ARGB int (returns 0..1) */
export function getBrightness(argb: number): number {
    const r = (argb >> 16) & 0xFF;
    const g = (argb >> 8) & 0xFF;
    const b = argb & 0xFF;
    return Math.max(r / 255, g / 255, b / 255);
}

/** Resolve a float that may be a NaN-encoded variable reference */
export function resolveFloat(value: number, context: { getFloat(id: number): number }): number {
    if (Number.isNaN(value)) {
        return context.getFloat(idFromNan(value));
    }
    return value;
}

/** Register a NaN-encoded variable for listening */
export function listenFloat(v: number, context: { listensTo(id: number, op: any): void }, op: any): void {
    if (Number.isNaN(v)) context.listensTo(idFromNan(v), op);
}

/** Simple log for debugging */
export function log(str: string): void {
    console.log(str);
}
