// ParticleOperations: particle system operations for RemoteCompose.
// Implements ParticlesCreateOp (161), ParticlesLoopOp (163), ParticlesCompareOp (194).

import { Operation } from '../Operation';
import { PaintOperation } from '../PaintOperation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { ContextMode } from '../RemoteContext';
import type { PaintContext } from '../PaintContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, floatToRawIntBits } from './Utils';
import { FloatExpression } from './FloatExpression';

// Constants matching FloatExpression
const OFFSET = 0x310000;
const ID_REGION_MASK = 0x700000;
const ID_REGION_ARRAY = 0x200000;

// Equations are kept as raw float32 int bits (operators/array ids are NaN-with-
// payload). Bits survive engines that canonicalize NaN payloads (Safari/Firefox)
// and are fed directly to FloatExpression.evalRPN (which accepts Int32Array).

/** Check if raw float32 int bits encode a math operator token. */
function isMathOperatorBits(b: number): boolean {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id > OFFSET && id <= OFFSET + 79;
}

/** Check if raw float32 int bits encode a data variable (array/collection). */
function isDataVariableBits(b: number): boolean {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return (id & ID_REGION_MASK) === ID_REGION_ARRAY;
}

/**
 * Resolve variable references in an equation (raw bits in/out).
 * Variable NaN refs become the literal float's bits; operators and data
 * variables are preserved as their NaN bits.
 */
/** `Utils.asNan(v)` as raw bits — the same encoding the equations already carry. */
const asNan = (v: number): number => (v | 0xff800000) >>> 0;
const CMD1_BITS = asNan(OFFSET + 64) | 0;
const CMD2_BITS = asNan(OFFSET + 65) | 0;
const NOP_BITS = asNan(OFFSET + 55) | 0;

/**
 * Resolve an equation for a *pair* of particles.
 *
 * A reference to a particle variable followed by CMD1 means "particle1's value for
 * that variable", and CMD2 means particle2's; the command token becomes a NOP. Without
 * this the two command tokens survive into the RPN and the expression evaluates to
 * something meaningless, so a pair condition never passes and the whole body — nested
 * conditionals, run-actions and all — is skipped in silence.
 *
 * Mirrors `ParticlesCompare.update2Body`.
 */
function resolvePairEquation(
    src: Int32Array, context: RemoteContext, varIds: number[],
    particle1: number[], particle2: number[],
): Int32Array {
    const out = new Int32Array(src.length);
    for (let i = 0; i < src.length; i++) {
        const b = src[i];
        out[i] = (isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits(b))
            ? floatToRawIntBits(context.getFloat(idFromBits(b)))
            : b;
        if (i + 1 >= src.length) continue;
        for (let k = 0; k < varIds.length; k++) {
            if (!isNaNBits(b) || idFromBits(b) !== varIds[k]) continue;
            if (src[i + 1] === CMD1_BITS) {
                out[i] = floatToRawIntBits(particle1[k]);
                out[i + 1] = NOP_BITS;
                i++;
            } else if (src[i + 1] === CMD2_BITS) {
                out[i] = floatToRawIntBits(particle2[k]);
                out[i + 1] = NOP_BITS;
                i++;
            }
        }
    }
    return out;
}

function resolveEquation(src: Int32Array, context: RemoteContext): Int32Array {
    const out = new Int32Array(src.length);
    for (let i = 0; i < src.length; i++) {
        const b = src[i];
        if (isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits(b)) {
            out[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
        } else {
            out[i] = b;
        }
    }
    return out;
}

/**
 * Register variable dependencies from an equation (raw bits).
 */
function registerEquationListening(eq: Int32Array, context: RemoteContext, op: VariableSupport): void {
    for (let i = 0; i < eq.length; i++) {
        const b = eq[i];
        if (isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits(b)) {
            context.listensTo(idFromBits(b), op);
        }
    }
}

// ── ParticlesCreateOp (161) ────────────────────────────────────────────
export class ParticlesCreateOp extends Operation implements VariableSupport {
    static readonly OP_CODE = 161;

    mId: number;
    private mParticleCount: number;
    private mVarId: number[];
    private mEquations: Int32Array[];
    private mOutEquations: Int32Array[];
    private mParticles: number[][];
    private mInitialized = false;
    private mContext: RemoteContext | null = null;

    constructor(id: number, particleCount: number, varId: number[], equations: Int32Array[]) {
        super();
        this.mId = id;
        this.mParticleCount = particleCount;
        this.mVarId = varId;
        this.mEquations = equations;
        this.mOutEquations = equations.map(eq => new Int32Array(eq));
        this.mParticles = [];
        for (let i = 0; i < particleCount; i++) {
            this.mParticles.push(new Array(varId.length).fill(0));
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.putObject(this.mId, this);
        for (const eq of this.mEquations) {
            registerEquationListening(eq, context, this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mContext = context;
        for (let j = 0; j < this.mEquations.length; j++) {
            this.mOutEquations[j] = resolveEquation(this.mEquations[j], context);
        }
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.PAINT && !this.mInitialized) {
            this.mContext = context;
            for (let i = 0; i < this.mParticleCount; i++) {
                this.initializeParticle(i, context);
            }
            this.mInitialized = true;
        }
    }

    initializeParticle(i: number, context: RemoteContext): void {
        const varCount = this.mVarId.length;
        for (let j = 0; j < varCount; j++) {
            this.mParticles[i][j] = FloatExpression.evalRPN(
                context, this.mOutEquations[j], [i, 0, 0]
            );
        }
    }

    getParticles(): number[][] { return this.mParticles; }
    getVariableIds(): number[] { return this.mVarId; }

    deepToString(indent: string): string {
        return `${indent}ParticlesCreateOp(id=${this.mId}, count=${this.mParticleCount}, vars=${this.mVarId.length})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const particleCount = buffer.readInt();
        const varLen = buffer.readInt();
        const varIds: number[] = [];
        const equations: Int32Array[] = [];
        for (let i = 0; i < varLen; i++) {
            varIds.push(buffer.readInt());
            const equLen = buffer.readInt();
            const eq = new Int32Array(equLen);
            for (let j = 0; j < equLen; j++) eq[j] = buffer.readInt();
            equations.push(eq);
        }
        operations.push(new ParticlesCreateOp(id, particleCount, varIds, equations));
    }
}

// ── ParticlesLoopOp (163) ──────────────────────────────────────────────
export class ParticlesLoopOp extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 163;

    private mId: number;
    private mRestart: Int32Array;
    private mOutRestart: Int32Array;
    private mEquations: Int32Array[];
    private mOutEquations: Int32Array[];
    mList: Operation[] = [];
    private mSource: ParticlesCreateOp | null = null;

    constructor(id: number, restart: Int32Array, equations: Int32Array[]) {
        super();
        this.mId = id;
        this.mRestart = restart;
        this.mOutRestart = new Int32Array(restart);
        this.mEquations = equations;
        this.mOutEquations = equations.map(eq => new Int32Array(eq));
    }

    getList(): Operation[] { return this.mList; }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        registerEquationListening(this.mRestart, context, this);
        for (const eq of this.mEquations) {
            registerEquationListening(eq, context, this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mOutRestart = resolveEquation(this.mRestart, context);
        for (let j = 0; j < this.mEquations.length; j++) {
            this.mOutEquations[j] = resolveEquation(this.mEquations[j], context);
        }
    }

    paint(paintContext: PaintContext): void {
        const context = paintContext.getContext();
        if (context.mMode !== ContextMode.PAINT) return;

        // Resolve source ParticlesCreateOp
        if (!this.mSource) {
            const obj = context.getObject(this.mId);
            if (obj instanceof ParticlesCreateOp) {
                this.mSource = obj;
            } else {
                return;
            }
        }

        const source = this.mSource;
        const particles = source.getParticles();
        const varIds = source.getVariableIds();
        const varCount = varIds.length;

        for (let i = 0; i < particles.length; i++) {
            // Load particle variable values into context
            for (let j = 0; j < varCount; j++) {
                context.loadFloat(varIds[j], particles[i][j]);
            }

            // Re-resolve equations with current particle var values loaded
            this.updateVariables(context);

            // Evaluate update equations
            for (let j = 0; j < this.mOutEquations.length && j < varCount; j++) {
                particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations[j]);
                context.loadFloat(varIds[j], particles[i][j]);
            }

            // Check restart condition
            const restartVal = FloatExpression.evalRPN(context, this.mOutRestart);
            if (restartVal > 0) {
                source.initializeParticle(i, context);
                // Reload initialized values and re-run updates so derived
                // variables (e.g. tail position) are consistent with the
                // new birth position — avoids stray lines on restart frame
                for (let j = 0; j < varCount; j++) {
                    context.loadFloat(varIds[j], particles[i][j]);
                }
                this.updateVariables(context);
                for (let j = 0; j < this.mOutEquations.length && j < varCount; j++) {
                    particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations[j]);
                    context.loadFloat(varIds[j], particles[i][j]);
                }
            }

            // Execute child operations
            for (const child of this.mList) {
                if (child.isDirty() && typeof (child as any).updateVariables === 'function') {
                    child.markNotDirty();
                    (child as any).updateVariables(context);
                }
                context.incrementOpCount();
                child.apply(context);
            }
        }

        context.needsRepaint();
    }

    deepToString(indent: string): string {
        return `${indent}ParticlesLoopOp(id=${this.mId}, children=${this.mList.length})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const restartLen = buffer.readInt();
        const restart = new Int32Array(restartLen);
        for (let i = 0; i < restartLen; i++) restart[i] = buffer.readInt();
        const varLen = buffer.readInt();
        const equations: Int32Array[] = [];
        for (let i = 0; i < varLen; i++) {
            const equLen = buffer.readInt();
            const eq = new Int32Array(equLen);
            for (let j = 0; j < equLen; j++) eq[j] = buffer.readInt();
            equations.push(eq);
        }
        operations.push(new ParticlesLoopOp(id, restart, equations));
    }
}

// ── ParticlesCompareOp (194) ───────────────────────────────────────────
export class ParticlesCompareOp extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 194;

    private mId: number;
    private mFlags: number;
    private mMin: number;
    private mMax: number;
    private mCondition: Int32Array;
    private mOutCondition: Int32Array;
    private mEquations1: Int32Array[];
    private mOutEquations1: Int32Array[];
    private mEquations2: Int32Array[];
    mList: Operation[] = [];
    private mSource: ParticlesCreateOp | null = null;

    constructor(
        id: number, flags: number, min: number, max: number,
        condition: Int32Array, equations1: Int32Array[], equations2: Int32Array[]
    ) {
        super();
        this.mId = id;
        this.mFlags = flags;
        this.mMin = min;
        this.mMax = max;
        this.mCondition = condition;
        this.mOutCondition = new Int32Array(condition);
        this.mEquations1 = equations1;
        this.mOutEquations1 = equations1.map(eq => new Int32Array(eq));
        this.mEquations2 = equations2;
    }

    getList(): Operation[] { return this.mList; }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        registerEquationListening(this.mCondition, context, this);
        for (const eq of this.mEquations1) {
            registerEquationListening(eq, context, this);
        }
        for (const eq of this.mEquations2) {
            registerEquationListening(eq, context, this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mOutCondition = resolveEquation(this.mCondition, context);
        for (let j = 0; j < this.mEquations1.length; j++) {
            this.mOutEquations1[j] = resolveEquation(this.mEquations1[j], context);
        }
    }

    paint(paintContext: PaintContext): void {
        const context = paintContext.getContext();
        if (context.mMode !== ContextMode.PAINT) return;

        // Resolve source ParticlesCreateOp
        if (!this.mSource) {
            const obj = context.getObject(this.mId);
            if (obj instanceof ParticlesCreateOp) {
                this.mSource = obj;
            } else {
                return;
            }
        }

        const source = this.mSource;
        const particles = source.getParticles();
        const varIds = source.getVariableIds();
        const varCount = varIds.length;

        const startIdx = this.mMin < 0 ? 0 : Math.min(this.mMin, particles.length);
        const endIdx = this.mMax < 0 ? particles.length : Math.min(this.mMax, particles.length);

        // Two equation sets means a *pair* comparison, which is a different algorithm
        // entirely — see `ParticlesCompare.condition2Body`. Running the single-particle
        // loop for those documents leaves the condition tokens unsubstituted, so it
        // never fires and every nested action is silently dropped.
        if (this.mEquations2 && this.mEquations2.length > 0) {
            this.paintPairs(context, particles, varIds, varCount, startIdx, endIdx);
            context.needsRepaint();
            return;
        }

        for (let i = startIdx; i < endIdx; i++) {
            // Load particle variable values into context
            for (let j = 0; j < varCount; j++) {
                context.loadFloat(varIds[j], particles[i][j]);
            }

            // Re-resolve equations with current particle var values loaded
            this.updateVariables(context);

            // Evaluate condition
            const condVal = FloatExpression.evalRPN(context, this.mOutCondition);
            if (condVal > 0) {
                // Apply equations1 to update particle variables
                for (let j = 0; j < this.mOutEquations1.length && j < varCount; j++) {
                    particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations1[j]);
                    context.loadFloat(varIds[j], particles[i][j]);
                }

                // Re-resolve the children's variables *unconditionally*, as
                // `ParticlesCompare.runChildren` does. The particle's variables were
                // just loaded into the context, so a child whose operands depend on
                // them is stale by definition — but it is not marked dirty, because
                // nothing told it the particle moved. Gating on `isDirty()` meant a
                // conditional inside a particle comparison resolved its operands once
                // and then kept them forever: one branch permanently true, the other
                // permanently false.
                this.runChildren(context);
            }
        }

        context.needsRepaint();
    }

    /** Run the child operations, as `ParticlesCompare.runChildren` does. */
    private runChildren(context: RemoteContext): void {
        for (const child of this.mList) {
            if (typeof (child as any).updateVariables === 'function') {
                child.markNotDirty();
                (child as any).updateVariables(context);
            }
            context.incrementOpCount();
            child.apply(context);
        }
    }

    /**
     * Pairwise comparison over distinct particles — the `condition2Body` algorithm.
     *
     * Note the inner loop starts at `k + 1`: only distinct pairs, so a single particle
     * produces nothing at all. Children run twice per matching pair, once after each
     * particle's equation set is applied, which is what the reference does.
     */
    private paintPairs(
        context: RemoteContext, particles: number[][], varIds: number[],
        varCount: number, startIdx: number, endIdx: number,
    ): void {
        for (let k = startIdx; k < endIdx; k++) {
            const particle2 = particles[k];
            for (let i = k + 1; i < endIdx; i++) {
                const particle1 = particles[i];
                for (let j = 0; j < varCount; j++) context.loadFloat(varIds[j], particle1[j]);

                const cond = resolvePairEquation(
                    this.mCondition, context, varIds, particle1, particle2);
                context.incrementOpCount();
                if (!(FloatExpression.evalRPN(context, cond) > 0)) continue;

                const eq1 = this.mEquations1.map((e) =>
                    resolvePairEquation(e, context, varIds, particle1, particle2));
                for (let j = 0; j < varCount; j++) context.loadFloat(varIds[j], particle2[j]);
                const eq2 = this.mEquations2.map((e) =>
                    resolvePairEquation(e, context, varIds, particle1, particle2));

                for (let j = 0; j < eq1.length && j < varCount; j++) {
                    particle1[j] = FloatExpression.evalRPN(context, eq1[j]);
                    context.loadFloat(varIds[j], particle1[j]);
                }
                this.runChildren(context);

                for (let j = 0; j < eq2.length && j < varCount; j++) {
                    particle2[j] = FloatExpression.evalRPN(context, eq2[j]);
                    context.loadFloat(varIds[j], particle2[j]);
                }
                this.runChildren(context);
            }
        }
    }


    deepToString(indent: string): string {
        return `${indent}ParticlesCompareOp(id=${this.mId}, flags=${this.mFlags})`;
    }

    // Read an equation as raw int32 token bits (NaN operator/array ids survive).
    private static readEquationBits(buffer: WireBuffer): Int32Array {
        const len = buffer.readInt();
        const arr = new Int32Array(len);
        for (let i = 0; i < len; i++) arr[i] = buffer.readInt();
        return arr;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const flags = buffer.readShort();
        const min = buffer.readFloat();
        const max = buffer.readFloat();
        const condition = ParticlesCompareOp.readEquationBits(buffer);
        const result1Len = buffer.readInt();
        const equations1: Int32Array[] = [];
        for (let i = 0; i < result1Len; i++) {
            equations1.push(ParticlesCompareOp.readEquationBits(buffer));
        }
        const result2Len = buffer.readInt();
        const equations2: Int32Array[] = [];
        for (let i = 0; i < result2Len; i++) {
            equations2.push(ParticlesCompareOp.readEquationBits(buffer));
        }
        operations.push(new ParticlesCompareOp(id, flags, min, max, condition, equations1, equations2));
    }
}
