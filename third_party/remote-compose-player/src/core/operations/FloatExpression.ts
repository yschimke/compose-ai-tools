// FloatExpression: RPN-based float expression evaluator with animation support.
// Matches Java FloatExpression.java — extends Operation, implements VariableSupport.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { idFromNan, floatToRawIntBits, intBitsToFloat, isNaNBits, idFromBits } from './Utils';
import { FloatAnimation } from './utilities/easing/FloatAnimation';
import { SpringStopEngine } from './utilities/easing/SpringStopEngine';

// Finite sentinel for array/collection ids carried on the RPN eval stack. 2^42
// is exact in float64 and far above any real RC value; array ids are <= 0x3FFFFF.
const ARR_SENTINEL = 2 ** 42;
/** Decode an array/collection id popped from the eval stack (sentinel or legacy NaN). */
function arrIdFromStack(x: number): number {
    return x >= ARR_SENTINEL ? ((x - ARR_SENTINEL) | 0) : idFromNan(x);
}

export class FloatExpression extends Operation implements VariableSupport {
    static readonly OP_CODE = 81;
    mId: number;
    // Raw float32 bit pattern of each RPN token (operators/vars are NaN-with-
    // payload; literals are real floats). Kept as int bits so the encoded ids
    // survive JS engines that canonicalize NaN payloads (Safari/Firefox).
    mBits: Int32Array;
    private mAnimation: Float32Array | null;

    // Animation support
    private mFloatAnimation: FloatAnimation | null = null;
    private mSpring: SpringStopEngine | null = null;
    private mLastChange = NaN;
    private mLastCalculatedValue = NaN;
    private mLastAnimatedValue = NaN;

    // Registers for STORE/LOAD ops
    private mR0 = 0;
    private mR1 = 0;
    private mR2 = 0;
    private mR3 = 0;
    // Variables for animation callbacks
    private mVar: number[] = [0, 0, 0];

    constructor(id: number, bits: Int32Array, animation: Float32Array | null) {
        super();
        this.mId = id; this.mBits = bits; this.mAnimation = animation;
        if (animation) {
            if (animation.length > 4 && animation[0] === 0) {
                this.mSpring = new SpringStopEngine(animation);
            } else {
                this.mFloatAnimation = new FloatAnimation(animation);
            }
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        for (let i = 0; i < this.mBits.length; i++) {
            const b = this.mBits[i];
            if (isNaNBits(b)
                && !FloatExpression.isMathOperatorBits(b)
                && !FloatExpression.isDataVariableBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }


    updateVariables(context: RemoteContext): void {
        // Evaluate live from the raw token bits (variable refs resolved inside
        // evalRPN). No pre-resolved Float32Array — that would canonicalize the
        // NaN operator tokens on engines like Safari/Firefox.
        let v = FloatExpression.evalRPN(context, this.mBits, this.mVar);
        let valueChanged = (v !== this.mLastCalculatedValue);
        if (valueChanged) {
            if (v !== this.mLastCalculatedValue) {
                this.mLastChange = context.getAnimationTime();
                this.mLastCalculatedValue = v;
            } else {
                valueChanged = false;
            }
        }
        if (valueChanged && this.mFloatAnimation) {
            if (Number.isNaN(this.mFloatAnimation.getTargetValue())) {
                this.mFloatAnimation.setInitialValue(v);
            } else {
                this.mFloatAnimation.setInitialValue(this.mFloatAnimation.getTargetValue());
            }
            this.mFloatAnimation.setTargetValue(v);
        } else if (valueChanged && this.mSpring) {
            this.mSpring.setTargetValue(v);
        }
    }

    private static isMathOperatorBits(b: number): boolean {
        if (!isNaNBits(b)) return false;
        const id = idFromBits(b);
        return id > FloatExpression.OFFSET && id <= FloatExpression.OFFSET + 79;
    }

    private static isDataVariableBits(b: number): boolean {
        if (!isNaNBits(b)) return false;
        const id = idFromBits(b);
        return (id & FloatExpression.ID_REGION_MASK) === FloatExpression.ID_REGION_ARRAY;
    }

    apply(context: RemoteContext): void {
        const t = context.getAnimationTime();
        if (Number.isNaN(this.mLastChange)) {
            this.mLastChange = t;
        }

        if (this.mFloatAnimation) {
            // Animated expression
            if (Number.isNaN(this.mLastCalculatedValue)) {
                this.mLastCalculatedValue = this.evaluate(context);
                this.mFloatAnimation.setTargetValue(this.mLastCalculatedValue);
                if (Number.isNaN(this.mFloatAnimation.getInitialValue())) {
                    this.mFloatAnimation.setInitialValue(this.mLastCalculatedValue);
                }
            }
            const lastComputedValue = this.mFloatAnimation.get(t - this.mLastChange);
            if (lastComputedValue !== this.mLastAnimatedValue
                || t - this.mLastChange <= this.mFloatAnimation.getDuration()) {
                this.mLastAnimatedValue = lastComputedValue;
                context.loadFloat(this.mId, lastComputedValue);
                context.needsRepaint();
            }
        } else if (this.mSpring) {
            // Spring animation
            const lastComputedValue = this.mSpring.get(t);
            if (lastComputedValue !== this.mLastAnimatedValue
                || Math.abs(this.mSpring.getTargetValue() - lastComputedValue) > 0.01) {
                this.mLastAnimatedValue = lastComputedValue;
                context.loadFloat(this.mId, lastComputedValue);
                context.needsRepaint();
            }
        } else {
            // No animation - evaluate directly
            const result = this.evaluate(context);
            context.loadFloat(this.mId, result);
        }
    }

    // Math operator offset: all operator IDs are OFFSET + index
    private static readonly OFFSET = 0x310000; // 3211264

    private static readonly ID_REGION_MASK = 0x700000;
    private static readonly ID_REGION_ARRAY = 0x200000;

    private evaluate(context: RemoteContext): number {
        return FloatExpression.evalRPN(context, this.mBits, this.mVar);
    }

    /**
     * Static RPN expression evaluator, callable from particle operations.
     * @param context - RemoteContext for variable resolution and collections access
     * @param values - the RPN expression tokens (Float32Array or number[])
     * @param vars - VAR1/VAR2/VAR3 values, defaults to [0, 0, 0]
     * @returns the evaluated result
     */
    static evalRPN(context: RemoteContext, values: Float32Array | number[] | Int32Array, vars: number[] = [0, 0, 0]): number {
        const stack: number[] = [];
        const OFFSET = FloatExpression.OFFSET;
        const collectionsAccess = context.getCollectionsAccess();
        // When given raw int32 token bits (the robust path), decode ids directly
        // from bits; otherwise treat entries as floats (legacy V8 callers).
        const useBits = values instanceof Int32Array;
        // Registers are local to each evaluation
        let r0 = 0, r1 = 0, r2 = 0, r3 = 0;
        for (let i = 0; i < values.length; i++) {
            const raw = values[i];
            const bits = useBits ? (raw | 0) : floatToRawIntBits(raw);
            // The float value for literals / array tokens pushed onto the stack.
            const v = useBits ? intBitsToFloat(bits) : raw;
            if (isNaNBits(bits)) {
                const id = idFromBits(bits);
                if ((id & FloatExpression.ID_REGION_MASK) === FloatExpression.ID_REGION_ARRAY) {
                    // Data variable (array/collection ID). Push the id as a
                    // finite sentinel (survives engines that canonicalize NaN);
                    // array ops below decode it via arrIdFromStack().
                    stack.push(ARR_SENTINEL + id);
                } else if (id > OFFSET && id <= OFFSET + 79) {
                    // RPN math operator
                    const op = id - OFFSET;
                    switch (op) {
                        case 1: { // ADD
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a + b);
                            break;
                        }
                        case 2: { // SUB
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a - b);
                            break;
                        }
                        case 3: { // MUL
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a * b);
                            break;
                        }
                        case 4: { // DIV
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(b !== 0 ? a / b : 0);
                            break;
                        }
                        case 5: { // MOD
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a % b);
                            break;
                        }
                        case 6: { // MIN
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(Math.min(a, b));
                            break;
                        }
                        case 7: { // MAX
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(Math.max(a, b));
                            break;
                        }
                        case 8: { // POW
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(Math.pow(a, b));
                            break;
                        }
                        case 9: // SQRT
                            stack.push(Math.sqrt(stack.pop()!));
                            break;
                        case 10: // ABS
                            stack.push(Math.abs(stack.pop()!));
                            break;
                        case 11: // SIGN
                            stack.push(Math.sign(stack.pop()!));
                            break;
                        case 12: { // COPY_SIGN — matches Java's Math.copySign
                            const b = stack.pop()!, a = stack.pop()!;
                            // Math.copySign copies sign bit, so -0 counts as negative
                            stack.push((1 / b < 0 || b < 0) ? -Math.abs(a) : Math.abs(a));
                            break;
                        }
                        case 13: // EXP
                            stack.push(Math.exp(stack.pop()!));
                            break;
                        case 14: // FLOOR
                            stack.push(Math.floor(stack.pop()!));
                            break;
                        case 15: // LOG
                            stack.push(Math.log10(stack.pop()!));
                            break;
                        case 16: // LN
                            stack.push(Math.log(stack.pop()!));
                            break;
                        case 17: // ROUND
                            stack.push(Math.round(stack.pop()!));
                            break;
                        case 18: // SIN
                            stack.push(Math.sin(stack.pop()!));
                            break;
                        case 19: // COS
                            stack.push(Math.cos(stack.pop()!));
                            break;
                        case 20: // TAN
                            stack.push(Math.tan(stack.pop()!));
                            break;
                        case 21: // ASIN
                            stack.push(Math.asin(stack.pop()!));
                            break;
                        case 22: // ACOS
                            stack.push(Math.acos(stack.pop()!));
                            break;
                        case 23: // ATAN
                            stack.push(Math.atan(stack.pop()!));
                            break;
                        case 24: { // ATAN2
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(Math.atan2(a, b));
                            break;
                        }
                        case 25: { // MAD (multiply-add: a*b+c)
                            const c = stack.pop()!, b = stack.pop()!, a = stack.pop()!;
                            stack.push(a * b + c);
                            break;
                        }
                        case 26: { // IFELSE: (sp>0) ? sp-1 : sp-2
                            const cond = stack.pop()!, trueVal = stack.pop()!, falseVal = stack.pop()!;
                            stack.push(cond > 0 ? trueVal : falseVal);
                            break;
                        }
                        case 27: { // CLAMP: match Java min(max(sp-2, sp), sp-1)
                            const top = stack.pop()!, second = stack.pop()!, val = stack.pop()!;
                            stack.push(Math.min(Math.max(val, top), second));
                            break;
                        }
                        case 28: // CBRT — matches Java's Math.pow(x, 1/3.0)
                            stack.push(Math.pow(stack.pop()!, 1 / 3.0));
                            break;
                        case 29: // DEG (radians to degrees)
                            stack.push(stack.pop()! * (180 / Math.PI));
                            break;
                        case 30: // RAD (degrees to radians)
                            stack.push(stack.pop()! * (Math.PI / 180));
                            break;
                        case 31: // CEIL
                            stack.push(Math.ceil(stack.pop()!));
                            break;
                        case 32: { // A_DEREF: array[index]
                            const index = stack.pop()!;
                            const arrayId = arrIdFromStack(stack.pop()!);
                            stack.push(collectionsAccess.getFloatValue(arrayId, Math.trunc(index)));
                            break;
                        }
                        case 33: { // A_MAX: max of array
                            const aId = arrIdFromStack(stack.pop()!);
                            const floats = collectionsAccess.getFloats(aId);
                            if (floats && floats.length > 0) {
                                let mx = floats[0];
                                for (let j = 1; j < floats.length; j++) mx = Math.max(mx, floats[j]);
                                stack.push(mx);
                            } else { stack.push(0); }
                            break;
                        }
                        case 34: { // A_MIN: min of array
                            const aId = arrIdFromStack(stack.pop()!);
                            const floats = collectionsAccess.getFloats(aId);
                            if (floats && floats.length > 0) {
                                let mn = floats[0];
                                for (let j = 1; j < floats.length; j++) mn = Math.min(mn, floats[j]);
                                stack.push(mn);
                            } else { stack.push(0); }
                            break;
                        }
                        case 35: { // A_SUM: sum of array
                            const aId = arrIdFromStack(stack.pop()!);
                            const floats = collectionsAccess.getFloats(aId);
                            let sum = 0;
                            if (floats) { for (let j = 0; j < floats.length; j++) sum += floats[j]; }
                            stack.push(sum);
                            break;
                        }
                        case 36: { // A_AVG: average of array
                            const aId = arrIdFromStack(stack.pop()!);
                            const floats = collectionsAccess.getFloats(aId);
                            let sum = 0;
                            if (floats && floats.length > 0) {
                                for (let j = 0; j < floats.length; j++) sum += floats[j];
                                stack.push(sum / floats.length);
                            } else { stack.push(0); }
                            break;
                        }
                        case 37: { // A_LEN: length of array
                            const aId = arrIdFromStack(stack.pop()!);
                            stack.push(collectionsAccess.getListLength(aId));
                            break;
                        }
                        case 38: { // A_SPLINE: monotonic spline interpolation
                            const pos = stack.pop()!;
                            const aId = arrIdFromStack(stack.pop()!);
                            const floats = collectionsAccess.getFloats(aId);
                            stack.push(floats ? FloatExpression.splineInterp(floats, pos) : 0);
                            break;
                        }
                        case 39: // RAND
                            stack.push(Math.random());
                            break;
                        case 40: // RAND_SEED (no-op for now)
                            stack.pop();
                            break;
                        case 41: { // NOISE_FROM: deterministic noise from float bits
                            let x = floatToRawIntBits(stack.pop()!);
                            x = ((x << 13) ^ x) | 0;
                            stack.push(
                                1.0 - ((Math.imul(x, (Math.imul(x, Math.imul(x, 15731)) + 789221)) + 1376312589) & 0x7fffffff)
                                / 1.0737418E+9
                            );
                            break;
                        }
                        case 42: { // RAND_IN_RANGE: random in [min, max)
                            const max = stack.pop()!, min = stack.pop()!;
                            stack.push(min + Math.random() * (max - min));
                            break;
                        }
                        case 43: { // SQUARE_SUM (x*x + y*y)
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a * a + b * b);
                            break;
                        }
                        case 44: { // STEP: (sp-1 > sp) ? 1 : 0
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(a > b ? 1 : 0);
                            break;
                        }
                        case 45: { // SQUARE (x*x)
                            const x = stack.pop()!;
                            stack.push(x * x);
                            break;
                        }
                        case 46: // DUP
                            stack.push(stack[stack.length - 1]);
                            break;
                        case 47: { // HYPOT
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(Math.hypot(a, b));
                            break;
                        }
                        case 48: { // SWAP
                            const b = stack.pop()!, a = stack.pop()!;
                            stack.push(b, a);
                            break;
                        }
                        case 49: { // LERP ((1-t)*x + t*y)
                            const t = stack.pop()!, y = stack.pop()!, x = stack.pop()!;
                            stack.push(x + (y - x) * t);
                            break;
                        }
                        case 50: { // SMOOTH_STEP
                            const mn = stack.pop()!, mx = stack.pop()!, val = stack.pop()!;
                            if (val < mn) { stack.push(0); }
                            else if (val > mx) { stack.push(1); }
                            else { const t = (val - mn) / (mx - mn); stack.push(t * t * (3 - 2 * t)); }
                            break;
                        }
                        case 51: // LOG2
                            stack.push(Math.log(stack.pop()!) / Math.log(2));
                            break;
                        case 52: // INV (1/x)
                            stack.push(1 / stack.pop()!);
                            break;
                        case 53: { // FRACT (x - trunc(x)) — matches Java's (int) cast
                            const x = stack.pop()!;
                            stack.push(x - Math.trunc(x));
                            break;
                        }
                        case 54: { // PINGPONG
                            const pmax = stack.pop()!;
                            const pval = stack.pop()!;
                            const max2 = pmax * 2;
                            const tmp = pval % max2;
                            stack.push(tmp < pmax ? tmp : max2 - tmp);
                            break;
                        }
                        case 55: // NOP
                            break;
                        case 56: // STORE_R0
                            r0 = stack.pop()!;
                            break;
                        case 57: // STORE_R1
                            r1 = stack.pop()!;
                            break;
                        case 58: // STORE_R2
                            r2 = stack.pop()!;
                            break;
                        case 59: // STORE_R3
                            r3 = stack.pop()!;
                            break;
                        case 60: // LOAD_R0
                            stack.push(r0);
                            break;
                        case 61: // LOAD_R1
                            stack.push(r1);
                            break;
                        case 62: // LOAD_R2
                            stack.push(r2);
                            break;
                        case 63: // LOAD_R3
                            stack.push(r3);
                            break;
                        // 64-67: CMD1-CMD4 reserved (no-op)
                        case 64: case 65: case 66: case 67:
                            break;
                        case 70: // VAR1
                            stack.push(vars[0]);
                            break;
                        case 71: // VAR2
                            stack.push(vars[1]);
                            break;
                        case 72: // VAR3
                            stack.push(vars[2]);
                            break;
                        case 73: // CHANGE_SIGN (-x)
                            stack.push(-stack.pop()!);
                            break;
                        case 74: { // CUBIC (cubic bezier easing)
                            const cpos = stack.pop()!;
                            const cy2 = stack.pop()!;
                            const cx2 = stack.pop()!;
                            const cy1 = stack.pop()!;
                            const cx1 = stack.pop()!;
                            stack.push(FloatExpression.cubicEasing(cx1, cy1, cx2, cy2, cpos));
                            break;
                        }
                        case 75: { // A_SPLINE_LOOP
                            const sli = stack.pop()!;
                            const slId = arrIdFromStack(stack.pop()!);
                            const slr = sli - Math.floor(sli);
                            const slFloats = collectionsAccess.getFloats(slId);
                            stack.push(slFloats ? FloatExpression.splineInterp(slFloats, slr < 0 ? slr + 1 : slr) : 0);
                            break;
                        }
                        case 76: { // A_SUM_TILL (partial sum up to index)
                            const stLast = Math.trunc(stack.pop()!);
                            const stId = arrIdFromStack(stack.pop()!);
                            let stSum = 0;
                            for (let j = 0; j <= stLast; j++) {
                                stSum += collectionsAccess.getFloatValue(stId, j);
                            }
                            stack.push(stSum);
                            break;
                        }
                        case 77: { // A_SUM_XY (sum of products of two arrays)
                            const syIdY = arrIdFromStack(stack.pop()!);
                            const syIdX = arrIdFromStack(stack.pop()!);
                            const syArrX = collectionsAccess.getFloats(syIdX);
                            const syArrY = collectionsAccess.getFloats(syIdY);
                            let sxySum = 0;
                            if (syArrX && syArrY) {
                                for (let j = 0; j < syArrX.length; j++) {
                                    sxySum += syArrX[j] * syArrY[j];
                                }
                            }
                            stack.push(sxySum);
                            break;
                        }
                        case 78: { // A_SUM_SQR (sum of squares)
                            const ssId = arrIdFromStack(stack.pop()!);
                            const ssArr = collectionsAccess.getFloats(ssId);
                            let ssSum = 0;
                            if (ssArr) {
                                for (let j = 0; j < ssArr.length; j++) {
                                    ssSum += ssArr[j] * ssArr[j];
                                }
                            }
                            stack.push(ssSum);
                            break;
                        }
                        case 79: { // A_LERP (linear interpolation in array)
                            const alPos = stack.pop()!;
                            const alId = arrIdFromStack(stack.pop()!);
                            const alArr = collectionsAccess.getFloats(alId);
                            if (alArr && alArr.length > 0) {
                                const alP = alPos * (alArr.length - 1);
                                const alIdx = Math.trunc(alP);
                                if (alIdx < 0) {
                                    stack.push(alArr[0]);
                                } else if (alIdx >= alArr.length - 1) {
                                    stack.push(alArr[alArr.length - 1]);
                                } else {
                                    const alT = alP - alIdx;
                                    stack.push(alArr[alIdx] + alT * (alArr[alIdx + 1] - alArr[alIdx]));
                                }
                            } else {
                                stack.push(0);
                            }
                            break;
                        }
                        default:
                            // Unhandled operator - push 0
                            stack.push(0);
                            break;
                    }
                } else {
                    // Variable reference
                    stack.push(context.getFloat(id));
                }
            } else {
                stack.push(v);
            }
        }
        return stack.length > 0 ? stack[stack.length - 1] : 0;
    }

    /**
     * Monotonic cubic Hermite spline interpolation.
     * Time is implicitly spaced from 0 to 1 over the array.
     * Matches Java MonotonicSpline(null, y).getPos(pos).
     */
    static splineInterp(y: Float32Array | number[], pos: number): number {
        const n = y.length;
        if (n === 0) return 0;
        if (n === 1) return y[0];

        // Build evenly-spaced time array [0 .. 1]
        const t = new Float64Array(n);
        for (let i = 0; i < n; i++) t[i] = i / (n - 1);

        // Compute slopes between consecutive points
        const slope = new Float64Array(n - 1);
        const tangent = new Float64Array(n);
        for (let i = 0; i < n - 1; i++) {
            const dt = t[i + 1] - t[i];
            slope[i] = (y[i + 1] - y[i]) / dt;
            if (i === 0) {
                tangent[i] = slope[i];
            } else {
                tangent[i] = (slope[i - 1] + slope[i]) * 0.5;
            }
        }
        tangent[n - 1] = slope[n - 2];

        // Monotonicity correction
        for (let i = 0; i < n - 1; i++) {
            if (slope[i] === 0) {
                tangent[i] = 0;
                tangent[i + 1] = 0;
            } else {
                const a = tangent[i] / slope[i];
                const b = tangent[i + 1] / slope[i];
                const h = Math.hypot(a, b);
                if (h > 9.0) {
                    const s = 3 / h;
                    tangent[i] = s * a * slope[i];
                    tangent[i + 1] = s * b * slope[i];
                }
            }
        }

        // Extrapolate beyond boundaries
        if (pos <= t[0]) {
            // Linear extrapolation using slope at boundary
            const h0 = t[1] - t[0];
            const d0 = (-6 * 0 * y[1] + 6 * 0 * y[1] + 6 * 0 * y[0] - 6 * 0 * y[0]
                + 3 * h0 * tangent[1] * 0 + 3 * h0 * tangent[0] * 0
                - 2 * h0 * tangent[1] * 0 - 4 * h0 * tangent[0] * 0 + h0 * tangent[0]) / h0;
            return y[0] + (pos - t[0]) * d0;
        }
        if (pos >= t[n - 1]) {
            // Slope at the end boundary (x=1 within the last segment)
            const hn = t[n - 1] - t[n - 2];
            const d1 = (-6 * y[n - 1] + 6 * y[n - 1] + 6 * y[n - 2] - 6 * y[n - 2]
                + 3 * hn * tangent[n - 1] + 3 * hn * tangent[n - 2]
                - 2 * hn * tangent[n - 1] - 4 * hn * tangent[n - 2] + hn * tangent[n - 2]) / hn;
            return y[n - 1] + (pos - t[n - 1]) * d1;
        }

        // Find the segment containing pos
        for (let i = 0; i < n - 1; i++) {
            if (pos < t[i + 1]) {
                const h = t[i + 1] - t[i];
                const x = (pos - t[i]) / h;
                const x2 = x * x;
                const x3 = x2 * x;
                const y1 = y[i], y2v = y[i + 1], t1 = tangent[i], t2 = tangent[i + 1];
                return -2 * x3 * y2v + 3 * x2 * y2v + 2 * x3 * y1 - 3 * x2 * y1 + y1
                    + h * t2 * x3 + h * t1 * x3 - h * t2 * x2 - 2 * h * t1 * x2 + h * t1 * x;
            }
        }
        return y[n - 1];
    }

    /**
     * Cubic bezier easing: given control points (x1,y1) and (x2,y2),
     * find the y value for a given x position using binary search.
     */
    static cubicEasing(x1: number, y1: number, x2: number, y2: number, pos: number): number {
        if (pos <= 0) return 0;
        if (pos >= 1) return 1;
        // Binary search for t where bezierX(t) = pos
        let lo = 0, hi = 1;
        for (let i = 0; i < 20; i++) {
            const mid = (lo + hi) / 2;
            const bx = FloatExpression.bezier(mid, x1, x2);
            if (bx < pos) lo = mid; else hi = mid;
        }
        const t = (lo + hi) / 2;
        return FloatExpression.bezier(t, y1, y2);
    }

    private static bezier(t: number, c1: number, c2: number): number {
        const t1 = 1 - t;
        return 3 * t1 * t1 * t * c1 + 3 * t1 * t * t * c2 + t * t * t;
    }

    deepToString(indent: string): string { return `${indent}FloatExpression(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const len = buffer.readInt();
        const valueLen = len & 0xFFFF;
        const animLen = (len >> 16) & 0xFFFF;
        // Read each token as its exact int32 bit pattern (readInt = getInt32,
        // never canonicalizes), so NaN-encoded operator/variable ids survive.
        const bits = new Int32Array(valueLen);
        for (let i = 0; i < valueLen; i++) {
            bits[i] = buffer.readInt();
        }
        let animation: Float32Array | null = null;
        if (animLen > 0) {
            animation = new Float32Array(animLen);
            for (let i = 0; i < animLen; i++) {
                animation[i] = buffer.readFloat();
            }
        }
        operations.push(new FloatExpression(id, bits, animation));
    }
}
