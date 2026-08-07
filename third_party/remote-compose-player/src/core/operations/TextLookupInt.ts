import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

/**
 * `TEXT_LOOKUP_INT` (153) — the integer-indexed sibling of {@link TextLookup} (151).
 *
 * Both resolve a string out of a text collection and publish it under `textId`. They differ only in
 * where the index comes from: `TextLookup` carries the index as raw float32 bits that may be a
 * NaN-encoded *float* variable reference, while this one carries a plain **integer variable id** —
 * so the index is read with `getInteger`, not `getFloat`, and needs no NaN decoding.
 *
 * Wire shape is three ints (`outId`, `listId`, `indexId`), matching `TextLookupIntCodec` in
 * `rc-player/protocol`'s `RcDocumentCodec.kt`.
 *
 * Without this operation the reader hit `Unknown operation opcode: 153` and abandoned the rest of
 * the buffer, so any document containing one rendered blank or half-drawn rather than merely losing
 * the looked-up string.
 */
export class TextLookupInt extends Operation {
    static readonly OP_CODE = 153;
    private mTextId: number;
    private mDataSetId: number;
    private mIndexId: number;
    private mOutIndex: number = 0;

    constructor(textId: number, dataSetId: number, indexId: number) {
        super();
        this.mTextId = textId;
        this.mDataSetId = dataSetId;
        this.mIndexId = indexId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mIndexId, this);
    }

    updateVariables(context: RemoteContext): void {
        this.mOutIndex = context.getInteger(this.mIndexId);
    }

    apply(context: RemoteContext): void {
        this.mOutIndex = context.getInteger(this.mIndexId);
        const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.trunc(this.mOutIndex));
        if (id >= 0) {
            const text = context.getText(id);
            if (text !== null) {
                context.loadText(this.mTextId, text);
            }
        }
    }

    deepToString(indent: string): string {
        return `${indent}TextLookupInt(textId=${this.mTextId}, dataSet=${this.mDataSetId}, indexId=${this.mIndexId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const dataSetId = buffer.readInt();
        const indexId = buffer.readInt();
        operations.push(new TextLookupInt(textId, dataSetId, indexId));
    }
}
