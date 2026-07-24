// AnimatedFloatExpression: standalone RPN float expression evaluator.
// Port of Java AnimatedFloatExpression.java — used by TouchExpression.

import { idFromNan, isNaNBits, idFromBits, intBitsToFloat, floatToRawIntBits } from '../Utils';
import { FloatExpression } from '../FloatExpression';

// Finite sentinel for array/collection ids carried on the eval stack (mirrors
// FloatExpression). 2^42 is exact in float64 and far above any real RC value.
const ARR_SENTINEL = 2 ** 42;
/** Decode an array/collection id popped from the eval stack (sentinel or legacy NaN). */
function arrIdFromStack(x: number): number {
    return x >= ARR_SENTINEL ? ((x - ARR_SENTINEL) | 0) : idFromNan(x);
}

export class AnimatedFloatExpression {
    static readonly OFFSET = 0x310000;
    static readonly LAST_OP = AnimatedFloatExpression.OFFSET + 79;

    private static readonly ID_REGION_MASK = 0x700000;
    private static readonly ID_REGION_ARRAY = 0x200000;

    private mStack = new Float32Array(128);
    private mR0 = 0;
    private mR1 = 0;
    private mR2 = 0;
    private mR3 = 0;

    static isMathOperator(v: number): boolean {
        if (!Number.isNaN(v)) return false;
        const id = idFromNan(v);
        if ((id & AnimatedFloatExpression.ID_REGION_MASK) === AnimatedFloatExpression.ID_REGION_ARRAY) {
            return false;
        }
        return id > AnimatedFloatExpression.OFFSET && id <= AnimatedFloatExpression.LAST_OP;
    }

    /** Bit-aware variant: true if raw float32 int bits encode a math operator token. */
    static isMathOperatorBits(b: number): boolean {
        if (!isNaNBits(b)) return false;
        const id = idFromBits(b);
        if ((id & AnimatedFloatExpression.ID_REGION_MASK) === AnimatedFloatExpression.ID_REGION_ARRAY) {
            return false;
        }
        return id > AnimatedFloatExpression.OFFSET && id <= AnimatedFloatExpression.LAST_OP;
    }

    eval(exp: Float32Array | number[] | Int32Array, len: number, ...vars: number[]): number;
    eval(ca: any, exp: Float32Array | number[] | Int32Array, len: number, ...vars: number[]): number;
    eval(...args: any[]): number {
        let ca: any = null;
        let exp: Float32Array | number[] | Int32Array;
        let len: number;
        let vars: number[] = [];

        if (typeof args[0] === 'number' || args[0] instanceof Float32Array
            || args[0] instanceof Int32Array || Array.isArray(args[0])) {
            // eval(exp, len, ...vars)
            exp = args[0] as Float32Array | number[] | Int32Array;
            len = args[1];
            vars = args.slice(2);
        } else {
            // eval(ca, exp, len, ...vars)
            ca = args[0];
            exp = args[1];
            len = args[2];
            vars = args.slice(3);
        }

        // When given raw int32 token bits (the robust path), classify tokens
        // directly from bits so NaN-encoded operator/array ids survive engines
        // that canonicalize NaN payloads (Safari/Firefox).
        const useBits = exp instanceof Int32Array;
        const s = this.mStack;
        let sp = -1;

        for (let i = 0; i < len; i++) {
            const raw = (exp as any)[i];
            const bits = useBits ? (raw | 0) : floatToRawIntBits(raw);
            if (isNaNBits(bits)) {
                const id = idFromBits(bits);
                if ((id & AnimatedFloatExpression.ID_REGION_MASK) === AnimatedFloatExpression.ID_REGION_ARRAY) {
                    // Push the array id as a finite sentinel (decoded by opEval).
                    s[++sp] = ARR_SENTINEL + id;
                } else {
                    sp = this.opEval(sp, id, s, ca, vars);
                }
            } else {
                s[++sp] = useBits ? intBitsToFloat(bits) : raw;
            }
        }
        return sp >= 0 ? s[sp] : 0;
    }

    private opEval(sp: number, id: number, s: Float32Array, ca: any, vars: number[]): number {
        const OFFSET = AnimatedFloatExpression.OFFSET;
        switch (id) {
            case OFFSET + 1: s[sp - 1] += s[sp]; return sp - 1; // ADD
            case OFFSET + 2: s[sp - 1] -= s[sp]; return sp - 1; // SUB
            case OFFSET + 3: s[sp - 1] *= s[sp]; return sp - 1; // MUL
            case OFFSET + 4: s[sp - 1] /= s[sp]; return sp - 1; // DIV
            case OFFSET + 5: s[sp - 1] %= s[sp]; return sp - 1; // MOD
            case OFFSET + 6: s[sp - 1] = Math.min(s[sp - 1], s[sp]); return sp - 1; // MIN
            case OFFSET + 7: s[sp - 1] = Math.max(s[sp - 1], s[sp]); return sp - 1; // MAX
            case OFFSET + 8: s[sp - 1] = Math.pow(s[sp - 1], s[sp]); return sp - 1; // POW
            case OFFSET + 9: s[sp] = Math.sqrt(s[sp]); return sp; // SQRT
            case OFFSET + 10: s[sp] = Math.abs(s[sp]); return sp; // ABS
            case OFFSET + 11: s[sp] = Math.sign(s[sp]); return sp; // SIGN
            case OFFSET + 12: s[sp - 1] = Math.abs(s[sp - 1]) * Math.sign(s[sp]); return sp - 1; // COPY_SIGN
            case OFFSET + 13: s[sp] = Math.exp(s[sp]); return sp; // EXP
            case OFFSET + 14: s[sp] = Math.floor(s[sp]); return sp; // FLOOR
            case OFFSET + 15: s[sp] = Math.log10(s[sp]); return sp; // LOG
            case OFFSET + 16: s[sp] = Math.log(s[sp]); return sp; // LN
            case OFFSET + 17: s[sp] = Math.round(s[sp]); return sp; // ROUND
            case OFFSET + 18: s[sp] = Math.sin(s[sp]); return sp; // SIN
            case OFFSET + 19: s[sp] = Math.cos(s[sp]); return sp; // COS
            case OFFSET + 20: s[sp] = Math.tan(s[sp]); return sp; // TAN
            case OFFSET + 21: s[sp] = Math.asin(s[sp]); return sp; // ASIN
            case OFFSET + 22: s[sp] = Math.acos(s[sp]); return sp; // ACOS
            case OFFSET + 23: s[sp] = Math.atan(s[sp]); return sp; // ATAN
            case OFFSET + 24: s[sp - 1] = Math.atan2(s[sp - 1], s[sp]); return sp - 1; // ATAN2
            case OFFSET + 25: s[sp - 2] = s[sp] + s[sp - 1] * s[sp - 2]; return sp - 2; // MAD
            case OFFSET + 26: { // IFELSE
                const c = s[sp]; s[sp - 2] = c > 0 ? s[sp - 1] : s[sp - 2]; return sp - 2;
            }
            case OFFSET + 27: s[sp - 2] = Math.min(Math.max(s[sp - 2], s[sp]), s[sp - 1]); return sp - 2; // CLAMP
            case OFFSET + 28: s[sp] = Math.pow(s[sp], 1 / 3); return sp; // CBRT
            case OFFSET + 29: s[sp] = s[sp] * 57.29578; return sp; // DEG
            case OFFSET + 30: s[sp] = s[sp] * 0.017453292; return sp; // RAD
            case OFFSET + 31: s[sp] = Math.ceil(s[sp]); return sp; // CEIL
            case OFFSET + 32: { // A_DEREF
                const arrId = arrIdFromStack(s[sp - 1]);
                if (ca) s[sp - 1] = ca.getFloatValue(arrId, Math.trunc(s[sp]));
                return sp - 1;
            }
            case OFFSET + 33: { // A_MAX
                const arrId = arrIdFromStack(s[sp]);
                if (ca) { const arr = ca.getFloats(arrId); let mx = arr[0]; for (let i = 1; i < arr.length; i++) mx = Math.max(mx, arr[i]); s[sp] = mx; }
                return sp;
            }
            case OFFSET + 34: { // A_MIN
                const arrId = arrIdFromStack(s[sp]);
                if (ca) { const arr = ca.getFloats(arrId); if (arr.length > 0) { let mn = arr[0]; for (let i = 1; i < arr.length; i++) mn = Math.min(mn, arr[i]); s[sp] = mn; } }
                return sp;
            }
            case OFFSET + 35: { // A_SUM
                const arrId = arrIdFromStack(s[sp]);
                if (ca) { const arr = ca.getFloats(arrId); let sum = 0; for (let i = 0; i < arr.length; i++) sum += arr[i]; s[sp] = sum; }
                return sp;
            }
            case OFFSET + 36: { // A_AVG
                const arrId = arrIdFromStack(s[sp]);
                if (ca) { const arr = ca.getFloats(arrId); let sum = 0; for (let i = 0; i < arr.length; i++) sum += arr[i]; s[sp] = arr.length > 0 ? sum / arr.length : 0; }
                return sp;
            }
            case OFFSET + 37: { // A_LEN
                const arrId = arrIdFromStack(s[sp]);
                if (ca) s[sp] = ca.getListLength(arrId);
                return sp;
            }
            case OFFSET + 38: { // A_SPLINE
                const arrId = arrIdFromStack(s[sp - 1]);
                if (ca) { const fl = ca.getFloats(arrId); s[sp - 1] = fl ? FloatExpression.splineInterp(fl, s[sp]) : 0; }
                return sp - 1;
            }
            case OFFSET + 39: // RAND
                s[++sp] = Math.random();
                return sp;
            case OFFSET + 40: // RAND_SEED (ignore seed in JS)
                return sp - 1;
            case OFFSET + 41: { // NOISE_FROM
                let x = Math.trunc(s[sp]);
                x = (x << 13) ^ x;
                s[sp] = 1.0 - ((x * (x * x * 15731 + 789221) + 1376312589) & 0x7fffffff) / 1.0737418e9;
                return sp;
            }
            case OFFSET + 42: // RAND_IN_RANGE
                s[sp] = Math.random() * (s[sp] - s[sp - 1]) + s[sp - 1];
                return sp;
            case OFFSET + 43: // SQUARE_SUM
                s[sp - 1] = s[sp - 1] * s[sp - 1] + s[sp] * s[sp];
                return sp - 1;
            case OFFSET + 44: // STEP
                s[sp - 1] = s[sp - 1] > s[sp] ? 1 : 0;
                return sp - 1;
            case OFFSET + 45: // SQUARE
                s[sp] = s[sp] * s[sp];
                return sp;
            case OFFSET + 46: // DUP
                s[sp + 1] = s[sp];
                return sp + 1;
            case OFFSET + 47: // HYPOT
                s[sp - 1] = Math.hypot(s[sp - 1], s[sp]);
                return sp - 1;
            case OFFSET + 48: { // SWAP
                const tmp = s[sp - 1]; s[sp - 1] = s[sp]; s[sp] = tmp;
                return sp;
            }
            case OFFSET + 49: { // LERP
                const t = s[sp]; s[sp - 2] = s[sp - 2] + (s[sp - 1] - s[sp - 2]) * t;
                return sp - 2;
            }
            case OFFSET + 50: { // SMOOTH_STEP
                const val = s[sp - 2], max2 = s[sp - 1], min1 = s[sp];
                if (val < min1) s[sp - 2] = 0;
                else if (val > max2) s[sp - 2] = 1;
                else { const vv = (val - min1) / (max2 - min1); s[sp - 2] = vv * vv * (3 - 2 * vv); }
                return sp - 2;
            }
            case OFFSET + 51: // LOG2
                s[sp] = Math.log(s[sp]) / Math.log(2);
                return sp;
            case OFFSET + 52: // INV
                s[sp] = 1 / s[sp];
                return sp;
            case OFFSET + 53: // FRACT
                s[sp] = s[sp] - Math.trunc(s[sp]);
                return sp;
            case OFFSET + 54: { // PINGPONG
                const max2 = s[sp] * 2;
                const tmp = s[sp - 1] % max2;
                s[sp - 1] = tmp < s[sp] ? tmp : max2 - tmp;
                return sp - 1;
            }
            case OFFSET + 55: return sp; // NOP
            case OFFSET + 56: this.mR0 = s[sp]; return sp - 1; // STORE_R0
            case OFFSET + 57: this.mR1 = s[sp]; return sp - 1; // STORE_R1
            case OFFSET + 58: this.mR2 = s[sp]; return sp - 1; // STORE_R2
            case OFFSET + 59: this.mR3 = s[sp]; return sp - 1; // STORE_R3
            case OFFSET + 60: s[++sp] = this.mR0; return sp; // LOAD_R0
            case OFFSET + 61: s[++sp] = this.mR1; return sp; // LOAD_R1
            case OFFSET + 62: s[++sp] = this.mR2; return sp; // LOAD_R2
            case OFFSET + 63: s[++sp] = this.mR3; return sp; // LOAD_R3
            case OFFSET + 70: // VAR1
                s[++sp] = vars.length > 0 ? vars[0] : 0; return sp;
            case OFFSET + 71: // VAR2
                s[++sp] = vars.length > 1 ? vars[1] : 0; return sp;
            case OFFSET + 72: // VAR3
                s[++sp] = vars.length > 2 ? vars[2] : 0; return sp;
            case OFFSET + 73: // CHANGE_SIGN
                s[sp] = -s[sp]; return sp;
            case OFFSET + 74: { // CUBIC
                const x1 = s[sp - 4], y1 = s[sp - 3], x2 = s[sp - 2], y2 = s[sp - 1], pos = s[sp];
                s[sp - 4] = FloatExpression.cubicEasing(x1, y1, x2, y2, pos);
                return sp - 4;
            }
            case OFFSET + 75: { // A_SPLINE_LOOP
                const arrId = arrIdFromStack(s[sp - 1]);
                const frac = s[sp] - Math.floor(s[sp]);
                const r = frac < 0 ? frac + 1 : frac;
                if (ca) { const fl = ca.getFloats(arrId); s[sp - 1] = fl ? FloatExpression.splineInterp(fl, r) : 0; }
                return sp - 1;
            }
            case OFFSET + 76: { // A_SUM_TILL
                const arrId = arrIdFromStack(s[sp - 1]);
                const last = Math.trunc(s[sp]);
                let sum = 0;
                if (ca) { for (let j = 0; j <= last; j++) sum += ca.getFloatValue(arrId, j); }
                s[sp - 1] = sum;
                return sp - 1;
            }
            case OFFSET + 77: { // A_SUM_XY
                const idX = arrIdFromStack(s[sp - 1]);
                const idY = arrIdFromStack(s[sp]);
                if (ca) { const ax = ca.getFloats(idX); const ay = ca.getFloats(idY); let sum = 0; for (let i = 0; i < ax.length; i++) sum += ax[i] * ay[i]; s[sp - 1] = sum; }
                return sp - 1;
            }
            case OFFSET + 78: { // A_SUM_SQR
                const arrId = arrIdFromStack(s[sp]);
                if (ca) { const arr = ca.getFloats(arrId); let sum = 0; for (let i = 0; i < arr.length; i++) sum += arr[i] * arr[i]; s[sp] = sum; }
                return sp;
            }
            case OFFSET + 79: { // A_LERP
                const arrId = arrIdFromStack(s[sp - 1]);
                if (ca) {
                    const arr = ca.getFloats(arrId);
                    const p = s[sp] * (arr.length - 1);
                    const idx = Math.trunc(p);
                    if (idx < 0) s[sp - 1] = arr[0];
                    else if (idx >= arr.length - 1) s[sp - 1] = arr[arr.length - 1];
                    else { const t = p - idx; s[sp - 1] = arr[idx] + t * (arr[idx + 1] - arr[idx]); }
                }
                return sp - 1;
            }
        }
        return sp;
    }
}
