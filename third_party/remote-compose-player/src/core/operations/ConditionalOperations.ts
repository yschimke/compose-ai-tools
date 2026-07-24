// ConditionalOperations: conditional execution of child operations.
// Fixed to extend PaintOperation (matching Java: extends PaintOperation implements Container, VariableSupport).

import { Operation } from '../Operation';
import { PaintOperation } from '../PaintOperation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { PaintContext } from '../PaintContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class ConditionalOperations extends PaintOperation {
    static readonly OP_CODE = 178;
    static readonly TYPE_EQ = 0;
    static readonly TYPE_NEQ = 1;
    static readonly TYPE_LT = 2;
    static readonly TYPE_LTE = 3;
    static readonly TYPE_GT = 4;
    static readonly TYPE_GTE = 5;

    mList: Operation[] = [];
    private mType: number;
    private mVarABits: number;
    private mVarBBits: number;
    private mVarAOut: number;
    private mVarBOut: number;

    constructor(type: number, aBits: number, bBits: number) {
        super();
        this.mType = type;
        this.mVarABits = aBits;
        this.mVarBBits = bBits;
        this.mVarAOut = isNaNBits(aBits) ? 0 : intBitsToFloat(aBits);
        this.mVarBOut = isNaNBits(bBits) ? 0 : intBitsToFloat(bBits);
    }

    getList(): Operation[] { return this.mList; }
    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mVarABits)) context.listensTo(idFromBits(this.mVarABits), this);
        if (isNaNBits(this.mVarBBits)) context.listensTo(idFromBits(this.mVarBBits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.markNotDirty();
        this.mVarAOut = isNaNBits(this.mVarABits) ? context.getFloat(idFromBits(this.mVarABits)) : intBitsToFloat(this.mVarABits);
        this.mVarBOut = isNaNBits(this.mVarBBits) ? context.getFloat(idFromBits(this.mVarBBits)) : intBitsToFloat(this.mVarBBits);
        for (const op of this.mList) {
            if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                (op as any).updateVariables(context);
            }
        }
    }

    paint(paintContext: PaintContext): void {
        const context = paintContext.getContext();
        // Update dirty children's variables
        for (const op of this.mList) {
            if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                op.markNotDirty();
                (op as any).updateVariables(context);
            }
        }
        let run = false;
        switch (this.mType) {
            case ConditionalOperations.TYPE_EQ: run = this.mVarAOut === this.mVarBOut; break;
            case ConditionalOperations.TYPE_NEQ: run = this.mVarAOut !== this.mVarBOut; break;
            case ConditionalOperations.TYPE_LT: run = this.mVarAOut < this.mVarBOut; break;
            case ConditionalOperations.TYPE_LTE: run = this.mVarAOut <= this.mVarBOut; break;
            case ConditionalOperations.TYPE_GT: run = this.mVarAOut > this.mVarBOut; break;
            case ConditionalOperations.TYPE_GTE: run = this.mVarAOut >= this.mVarBOut; break;
        }
        if (run) {
            for (const op of this.mList) {
                context.incrementOpCount();
                if (op instanceof ConditionalOperations) {
                    op.paint(paintContext);
                } else {
                    op.apply(context);
                }
            }
        }
    }

    deepToString(indent: string): string { return `${indent}ConditionalOperations(type=${this.mType})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const type = buffer.readByte();
        const a = buffer.readInt();   // raw bits; NaN-with-payload => variable ref
        const b = buffer.readInt();
        operations.push(new ConditionalOperations(type, a, b));
    }
}
