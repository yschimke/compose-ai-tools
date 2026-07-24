import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class TextLookup extends Operation {
    static readonly OP_CODE = 151;
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

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) context.listensTo(idFromBits(this.mIndexBits), this);
    }

    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) {
            this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
        }
    }

    apply(context: RemoteContext): void {
        if (isNaNBits(this.mIndexBits)) {
            this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
        }
        const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.trunc(this.mOutIndex));
        if (id >= 0) {
            const text = context.getText(id);
            if (text !== null) {
                context.loadText(this.mTextId, text);
            }
        }
    }

    deepToString(indent: string): string {
        return `${indent}TextLookup(textId=${this.mTextId}, dataSet=${this.mDataSetId}, index=${this.mOutIndex})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const dataSetId = buffer.readInt();
        const index = buffer.readInt();
        operations.push(new TextLookup(textId, dataSetId, index));
    }
}
