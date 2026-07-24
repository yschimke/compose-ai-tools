// IdLookup: looks up an ID from an ID collection by index.
// Port of Java IdLookup.java.

import { Operation } from '../Operation';
import type { VariableSupport } from '../VariableSupport';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class IdLookup extends Operation implements VariableSupport {
    static readonly OP_CODE = 192;

    private mTextId: number;
    private mDataSetId: number;
    // index as raw float32 int bits (may be a NaN-encoded variable ref).
    private mIndexBits: number;
    private mOutIndex: number;

    constructor(textId: number, dataSetId: number, indexBits: number) {
        super();
        this.mTextId = textId;
        this.mDataSetId = dataSetId;
        this.mIndexBits = indexBits;
        this.mOutIndex = isNaNBits(indexBits) ? 0 : intBitsToFloat(indexBits);
    }

    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) {
            this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
        }
    }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) {
            context.listensTo(idFromBits(this.mIndexBits), this);
        }
    }

    apply(context: RemoteContext): void {
        const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.floor(this.mOutIndex));
        context.loadInteger(this.mTextId, id);
    }

    write(buffer: WireBuffer): void {
        buffer.start(IdLookup.OP_CODE);
        buffer.writeInt(this.mTextId);
        buffer.writeInt(this.mDataSetId);
        buffer.writeInt(this.mIndexBits);
    }

    deepToString(indent: string): string {
        return `${indent}IdLookup(textId=${this.mTextId}, dataSetId=${this.mDataSetId}, index=${this.mOutIndex})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const dataSetId = buffer.readInt();
        const index = buffer.readInt();
        operations.push(new IdLookup(textId, dataSetId, index));
    }
}
