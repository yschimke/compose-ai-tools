// UpdateDynamicFloatList: updates a value in a dynamic float list.
// Port of Java UpdateDynamicFloatList.java.

import { Operation } from '../Operation';
import type { VariableSupport } from '../VariableSupport';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class UpdateDynamicFloatList extends Operation implements VariableSupport {
    static readonly OP_CODE = 198;

    private mArrayId: number;
    // index / value as raw float32 int bits (may be NaN-encoded variable refs).
    private mIndexBits: number;
    private mIndexOut: number;
    private mValueBits: number;
    private mValueOut: number;

    constructor(arrayId: number, indexBits: number, valueBits: number) {
        super();
        this.mArrayId = arrayId;
        this.mIndexBits = indexBits;
        this.mIndexOut = isNaNBits(indexBits) ? 0 : intBitsToFloat(indexBits);
        this.mValueBits = valueBits;
        this.mValueOut = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }

    updateVariables(context: RemoteContext): void {
        this.mIndexOut = isNaNBits(this.mIndexBits) ? context.getFloat(idFromBits(this.mIndexBits)) : intBitsToFloat(this.mIndexBits);
        this.mValueOut = isNaNBits(this.mValueBits) ? context.getFloat(idFromBits(this.mValueBits)) : intBitsToFloat(this.mValueBits);
    }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) {
            context.listensTo(idFromBits(this.mIndexBits), this);
        }
        if (isNaNBits(this.mValueBits)) {
            context.listensTo(idFromBits(this.mValueBits), this);
        }
    }

    apply(context: RemoteContext): void {
        const state = context.mRemoteComposeState;
        const values = state.getDynamicFloats(this.mArrayId);
        if (values !== null) {
            const index = Math.floor(this.mIndexOut);
            if (index < values.length) {
                values[index] = this.mValueOut;
            }
            state.markVariableDirty(this.mArrayId);
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(UpdateDynamicFloatList.OP_CODE);
        buffer.writeInt(this.mArrayId);
        buffer.writeFloat(this.mIndexOut);
        buffer.writeFloat(this.mValueOut);
    }

    deepToString(indent: string): string {
        return `${indent}UpdateDynamicFloatList(arrayId=${this.mArrayId}, index=${this.mIndexOut}, value=${this.mValueOut})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const index = buffer.readInt();
        const value = buffer.readInt();
        operations.push(new UpdateDynamicFloatList(id, index, value));
    }
}
