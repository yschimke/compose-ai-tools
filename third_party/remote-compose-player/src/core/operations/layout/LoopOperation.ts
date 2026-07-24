// LoopOperation: repeats child operations for a range of values.
// Binary: [indexId:INT] [from:FLOAT] [step:FLOAT] [until:FLOAT]
// Container: children follow, terminated by ContainerEnd.

import { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';
import { ContextMode } from '../../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from '../Utils';

export class LoopOperation extends Operation {
    static readonly OP_CODE = 215;

    private mIndexId: number;
    // Raw float32 int bits of from/step/until (NaN-with-payload => variable ref).
    private mFromBits: number;
    private mStepBits: number;
    private mUntilBits: number;
    private mList: Operation[] = [];

    constructor(indexId: number, fromBits: number, stepBits: number, untilBits: number) {
        super();
        this.mIndexId = indexId; this.mFromBits = fromBits;
        this.mStepBits = stepBits; this.mUntilBits = untilBits;
    }

    getList(): Operation[] { return this.mList; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) {
            // During data pass, just apply children once to register variables
            for (const op of this.mList) {
                op.apply(context);
            }
            return;
        }

        const from = this.rv(this.mFromBits, context);
        const step = this.rv(this.mStepBits, context);
        const until = this.rv(this.mUntilBits, context);

        if (step <= 0 || !isFinite(from) || !isFinite(until) || !isFinite(step)) return;

        if (this.mIndexId === 0) {
            for (let i = from; i < until; i += step) {
                for (const op of this.mList) {
                    context.incrementOpCount();
                    op.apply(context);
                }
            }
        } else {
            for (let i = from; i < until; i += step) {
                context.loadFloat(this.mIndexId, i);
                for (const op of this.mList) {
                    // Re-evaluate expressions that depend on the loop variable
                    if (typeof (op as any).updateVariables === 'function') {
                        (op as any).updateVariables(context);
                    }
                    context.incrementOpCount();
                    op.apply(context);
                }
            }
        }
    }

    private rv(bits: number, ctx: RemoteContext): number {
        return isNaNBits(bits) ? ctx.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
    }

    deepToString(indent: string): string {
        return `${indent}LoopOperation(${intBitsToFloat(this.mFromBits)}..${intBitsToFloat(this.mUntilBits)} step ${intBitsToFloat(this.mStepBits)})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const indexId = buffer.readInt();
        const from = buffer.readInt();   // raw bits; NaN-with-payload => variable ref
        const step = buffer.readInt();
        const until = buffer.readInt();
        operations.push(new LoopOperation(indexId, from, step, until));
    }
}
