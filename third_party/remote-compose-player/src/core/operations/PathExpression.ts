import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { intBitsToFloat, idFromBits, isNaNBits, floatToRawIntBits } from './Utils';
import { FloatExpression } from './FloatExpression';

// Finite sentinel for array/collection ids carried on the eval stack (mirrors
// FloatExpression). 2^42 is exact in float64 and far above any real RC value.
const ARR_SENTINEL = 2 ** 42;

export class PathExpression extends Operation {
    static readonly OP_CODE = 193;
    private static readonly LOOP = 1;
    private static readonly POLAR = 8;
    private static readonly WINDING_MASK = 0x3000000;
    private static readonly OFFSET = 0x310000;
    private static readonly VAR1_ID = 0x310000 + 70;
    // Path-command markers as raw float32 int bits (NaN-with-payload). Emitted
    // directly into the Int32Array path data so they survive NaN-payload
    // canonicalizing engines (Safari/Firefox).
    private static readonly MOVE_BITS = (10 | -0x800000) | 0;
    private static readonly CUBIC_BITS = (14 | -0x800000) | 0;
    private static readonly CLOSE_BITS = (15 | -0x800000) | 0;

    private mId: number;
    private mFlags: number;
    // min/max/count as raw float32 int bits (may be NaN-encoded variable refs).
    private mMinBits: number;
    private mMaxBits: number;
    private mCountBits: number;
    // RPN expression tokens as raw int bits (operators/vars/array ids are
    // NaN-with-payload). Bits survive engines that canonicalize NaN payloads.
    private mExprX: Int32Array;
    private mExprY: Int32Array;
    private mWinding: number;

    constructor(id: number, flags: number, minBits: number, maxBits: number, countBits: number,
                exprX: Int32Array, exprY: Int32Array) {
        super();
        this.mId = id; this.mFlags = flags;
        this.mMinBits = minBits; this.mMaxBits = maxBits; this.mCountBits = countBits;
        this.mExprX = exprX; this.mExprY = exprY;
        this.mWinding = (flags & PathExpression.WINDING_MASK) >> 24;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        // PathExpression isn't a VariableSupport (it resolves live in apply());
        // listen via an any-typed shim like the matrix ops do.
        const listen = (b: number) => {
            if (isNaNBits(b)) (context as any).listensTo(idFromBits(b), this);
        };
        listen(this.mMinBits);
        listen(this.mMaxBits);
        listen(this.mCountBits);
        const OFFSET = PathExpression.OFFSET;
        const isOp = (b: number) => {
            if (!isNaNBits(b)) return false;
            const id = idFromBits(b);
            return (id > OFFSET && id <= OFFSET + 79)
                || id === PathExpression.VAR1_ID
                || (id & PathExpression.ID_REGION_MASK) === PathExpression.ID_REGION_ARRAY;
        };
        for (const b of this.mExprX) {
            if (isNaNBits(b) && !isOp(b)) listen(b);
        }
        for (const b of this.mExprY) {
            if (isNaNBits(b) && !isOp(b)) listen(b);
        }
    }

    apply(context: RemoteContext): void {
        const count = Math.round(this.rv(this.mCountBits, context));
        if (count < 1) return;
        const min = this.rv(this.mMinBits, context);
        const max = this.rv(this.mMaxBits, context);
        const loop = (this.mFlags & PathExpression.LOOP) !== 0;
        const polar = (this.mFlags & PathExpression.POLAR) !== 0;
        const gap = max - min;
        const step = loop ? gap / count : gap / (count - 1);

        const exprX = this.resolveExpr(this.mExprX, context);
        const exprY = this.resolveExpr(this.mExprY, context);

        const xData = new Float32Array(count);
        const yData = new Float32Array(count);

        const ca = context.getCollectionsAccess();
        if (polar) {
            const cx = exprY.length > 0 ? exprY[0] : 0;
            const cy = exprY.length > 1 ? exprY[1] : 0;
            for (let i = 0; i < count; i++) {
                const angle = min + i * step;
                const r = this.evalRPN(exprX, angle, ca);
                xData[i] = cx + r * Math.cos(angle);
                yData[i] = cy + r * Math.sin(angle);
            }
        } else {
            for (let i = 0; i < count; i++) {
                const val = min + i * step;
                xData[i] = this.evalRPN(exprX, val, ca);
                yData[i] = this.evalRPN(exprY, val, ca);
            }
        }

        const pathData = this.splinePath(xData, yData, loop);
        context.loadPathData(this.mId, this.mWinding, pathData);
    }

    private rv(bits: number, ctx: RemoteContext): number {
        return isNaNBits(bits) ? ctx.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
    }

    private static readonly ID_REGION_MASK = 0x700000;
    private static readonly ID_REGION_ARRAY = 0x200000;

    // Resolve variable tokens to literal float bits; keep operator/VAR1/array
    // tokens as their NaN bits so evalRPN can decode them robustly.
    private resolveExpr(expr: Int32Array, ctx: RemoteContext): Int32Array {
        const out = new Int32Array(expr.length);
        const OFF = PathExpression.OFFSET;
        for (let i = 0; i < expr.length; i++) {
            const b = expr[i];
            if (isNaNBits(b)) {
                const id = idFromBits(b);
                if ((id > OFF && id <= OFF + 79) || id === PathExpression.VAR1_ID
                    || (id & PathExpression.ID_REGION_MASK) === PathExpression.ID_REGION_ARRAY) {
                    out[i] = b;
                } else {
                    out[i] = floatToRawIntBits(ctx.getFloat(id));
                }
            } else {
                out[i] = b;
            }
        }
        return out;
    }

    private evalRPN(expr: Int32Array, x: number, ca: any): number {
        const s: number[] = [];
        let sp = -1;
        const OFF = PathExpression.OFFSET;
        // Decode an array id popped from the eval stack (finite sentinel).
        const arrId = (v: number) => (v - ARR_SENTINEL) | 0;
        for (let i = 0; i < expr.length; i++) {
            const b = expr[i];
            if (isNaNBits(b)) {
                const id = idFromBits(b);
                if (id === PathExpression.VAR1_ID) {
                    s[++sp] = x;
                } else if ((id & PathExpression.ID_REGION_MASK) === PathExpression.ID_REGION_ARRAY) {
                    // Push the array id as a finite sentinel (survives canonicalization).
                    s[++sp] = ARR_SENTINEL + id;
                } else if (id > OFF && id <= OFF + 79) {
                    const op = id - OFF;
                    switch (op) {
                        case 1: sp--; s[sp] += s[sp + 1]; break;
                        case 2: sp--; s[sp] -= s[sp + 1]; break;
                        case 3: sp--; s[sp] *= s[sp + 1]; break;
                        case 4: sp--; s[sp] /= s[sp + 1]; break;
                        case 5: sp--; s[sp] %= s[sp + 1]; break;
                        case 6: sp--; s[sp] = Math.min(s[sp], s[sp + 1]); break;
                        case 7: sp--; s[sp] = Math.max(s[sp], s[sp + 1]); break;
                        case 8: sp--; s[sp] = Math.pow(s[sp], s[sp + 1]); break;
                        case 9: s[sp] = Math.sqrt(s[sp]); break;
                        case 10: s[sp] = Math.abs(s[sp]); break;
                        case 11: s[sp] = Math.sign(s[sp]); break;
                        case 12: sp--; s[sp] = Math.abs(s[sp]) * Math.sign(s[sp + 1]); break;
                        case 13: s[sp] = Math.exp(s[sp]); break;
                        case 14: s[sp] = Math.floor(s[sp]); break;
                        case 15: s[sp] = Math.log10(s[sp]); break;
                        case 16: s[sp] = Math.log(s[sp]); break;
                        case 17: s[sp] = Math.round(s[sp]); break;
                        case 18: s[sp] = Math.sin(s[sp]); break;
                        case 19: s[sp] = Math.cos(s[sp]); break;
                        case 20: s[sp] = Math.tan(s[sp]); break;
                        case 21: s[sp] = Math.asin(s[sp]); break;
                        case 22: s[sp] = Math.acos(s[sp]); break;
                        case 23: s[sp] = Math.atan(s[sp]); break;
                        case 24: sp--; s[sp] = Math.atan2(s[sp], s[sp + 1]); break;
                        case 25: sp -= 2; s[sp] = s[sp + 2] + s[sp + 1] * s[sp]; break;
                        case 26: { const c = s[sp]; sp -= 2; s[sp] = c > 0 ? s[sp + 1] : s[sp]; break; }
                        case 27: sp -= 2; s[sp] = Math.min(Math.max(s[sp], s[sp + 2]), s[sp + 1]); break;
                        case 28: s[sp] = Math.pow(s[sp], 1 / 3); break;
                        case 29: s[sp] = s[sp] * (180 / Math.PI); break;
                        case 30: s[sp] = s[sp] * (Math.PI / 180); break;
                        case 31: s[sp] = Math.ceil(s[sp]); break;
                        case 32: { const idx = s[sp]; const aId = arrId(s[sp - 1]); sp--; s[sp] = ca.getFloatValue(aId, Math.trunc(idx)); break; }
                        case 33: { const aId = arrId(s[sp]); const fl = ca.getFloats(aId); let mx = 0; if (fl && fl.length > 0) { mx = fl[0]; for (let j = 1; j < fl.length; j++) mx = Math.max(mx, fl[j]); } s[sp] = mx; break; }
                        case 34: { const aId = arrId(s[sp]); const fl = ca.getFloats(aId); let mn = 0; if (fl && fl.length > 0) { mn = fl[0]; for (let j = 1; j < fl.length; j++) mn = Math.min(mn, fl[j]); } s[sp] = mn; break; }
                        case 35: { const aId = arrId(s[sp]); const fl = ca.getFloats(aId); let sm = 0; if (fl) for (let j = 0; j < fl.length; j++) sm += fl[j]; s[sp] = sm; break; }
                        case 36: { const aId = arrId(s[sp]); const fl = ca.getFloats(aId); let sm = 0; if (fl && fl.length > 0) { for (let j = 0; j < fl.length; j++) sm += fl[j]; s[sp] = sm / fl.length; } else s[sp] = 0; break; }
                        case 37: { const aId = arrId(s[sp]); s[sp] = ca.getListLength(aId); break; }
                        case 38: { const pos = s[sp]; const aId = arrId(s[sp - 1]); const fl = ca.getFloats(aId); sp--; s[sp] = fl ? FloatExpression.splineInterp(fl, pos) : 0; break; }
                        case 44: sp--; s[sp] = s[sp] > s[sp + 1] ? 1 : 0; break;
                        case 45: s[sp] = s[sp] * s[sp]; break;
                        case 46: s[sp + 1] = s[sp]; sp++; break;
                        case 47: sp--; s[sp] = Math.hypot(s[sp], s[sp + 1]); break;
                        case 48: { const tmp = s[sp]; s[sp] = s[sp - 1]; s[sp - 1] = tmp; break; }
                        case 49: { const t = s[sp]; sp -= 2; s[sp] = s[sp] + (s[sp + 1] - s[sp]) * t; break; }
                        case 52: s[sp] = 1 / s[sp]; break;
                        case 53: s[sp] = s[sp] - Math.floor(s[sp]); break;
                        case 73: s[sp] = -s[sp]; break;
                        default: break;
                    }
                } else {
                    // Variable reference (already resolved to literal bits by resolveExpr).
                    s[++sp] = intBitsToFloat(b);
                }
            } else {
                s[++sp] = intBitsToFloat(b);
            }
        }
        return sp >= 0 ? s[sp] : 0;
    }

    // Emits resolved path data as raw float32 int bits: command markers as
    // their NaN-marker bits, coordinates via floatToRawIntBits. Internal float
    // math is unchanged; only the final emitted array is bits.
    private splinePath(x: Float32Array, y: Float32Array, loop: boolean): Int32Array {
        const n = x.length;
        if (n === 0) return new Int32Array(0);
        const segs = loop ? n : n - 1;
        const out = new Int32Array(3 + segs * 9 + (loop ? 1 : 0));
        let p = 0;
        out[p++] = PathExpression.MOVE_BITS;
        out[p++] = floatToRawIntBits(x[0]);
        out[p++] = floatToRawIntBits(y[0]);
        if (n <= 1) return out.subarray(0, p);

        const h = new Float32Array(segs);
        const dxS = new Float32Array(segs);
        const dyS = new Float32Array(segs);
        for (let i = 0; i < segs; i++) {
            const i1 = (i + 1) % n;
            const sx = x[i1] - x[i], sy = y[i1] - y[i];
            let d = Math.hypot(sx, sy);
            if (d === 0) d = 1e-12;
            h[i] = d; dxS[i] = sx / d; dyS[i] = sy / d;
        }
        const tn = loop ? segs : segs + 1;
        const dxT = new Float32Array(tn);
        const dyT = new Float32Array(tn);
        this.smoothTan(dxT, dxS, h, loop);
        this.smoothTan(dyT, dyS, h, loop);

        let cx = x[0], cy = y[0];
        for (let i = 0; i < segs; i++) {
            const i1 = (i + 1) % n;
            const ti1 = loop ? (i + 1) % segs : i + 1;
            const hi = h[i];
            out[p++] = PathExpression.CUBIC_BITS;
            out[p++] = floatToRawIntBits(cx); out[p++] = floatToRawIntBits(cy);
            out[p++] = floatToRawIntBits(x[i] + dxT[i] * hi / 3);
            out[p++] = floatToRawIntBits(y[i] + dyT[i] * hi / 3);
            out[p++] = floatToRawIntBits(x[i1] - dxT[ti1] * hi / 3);
            out[p++] = floatToRawIntBits(y[i1] - dyT[ti1] * hi / 3);
            out[p++] = floatToRawIntBits(x[i1]); out[p++] = floatToRawIntBits(y[i1]);
            cx = x[i1]; cy = y[i1];
        }
        if (loop) out[p++] = PathExpression.CLOSE_BITS;
        return out.subarray(0, p);
    }

    private smoothTan(d: Float32Array, delta: Float32Array, h: Float32Array, loop: boolean): void {
        const segs = delta.length;
        const n = loop ? segs : segs + 1;
        if (loop) {
            for (let i = 0; i < n; i++) {
                const im = (i - 1 + segs) % segs;
                const ip = i % segs;
                d[i] = (h[im] * delta[ip] + h[ip] * delta[im]) / (h[im] + h[ip]);
            }
        } else {
            d[0] = delta[0]; d[n - 1] = delta[segs - 1];
            for (let i = 1; i < n - 1; i++) {
                d[i] = (h[i-1] * delta[i] + h[i] * delta[i-1]) / (h[i-1] + h[i]);
            }
        }
    }

    deepToString(indent: string): string { return `${indent}PathExpression`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const flags = buffer.readInt();
        const min = buffer.readInt();
        const max = buffer.readInt();
        const count = buffer.readInt();
        const lenX = buffer.readInt();
        const exprX = new Int32Array(lenX);
        for (let i = 0; i < lenX; i++) exprX[i] = buffer.readInt();
        const lenY = buffer.readInt();
        const exprY = new Int32Array(lenY);
        for (let i = 0; i < lenY; i++) exprY[i] = buffer.readInt();
        operations.push(new PathExpression(id, flags, min, max, count, exprX, exprY));
    }
}
