// DataListIds: store a list of integer IDs as a collection.
// Matches Java DataListIds.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class DataListIds extends Operation {
    static readonly OP_CODE = 146;
    private mId: number;
    private mIds: Int32Array;

    constructor(id: number, ids: Int32Array) {
        super(); this.mId = id; this.mIds = ids;
    }

    getCollectionId(): number { return this.mId; }
    getLength(): number { return this.mIds.length; }
    getId(index: number): number { return this.mIds[index]; }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        context.addCollection(this.mId, { ids: this.mIds, getLength: () => this.mIds.length, getFloatValue: (i: number) => this.mIds[i], getId: (i: number) => this.mIds[i] });
    }

    deepToString(indent: string): string { return `${indent}DataListIds(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const count = buffer.readInt();
        const ids = new Int32Array(count);
        for (let i = 0; i < count; i++) {
            ids[i] = buffer.readInt();
        }
        operations.push(new DataListIds(id, ids));
    }
}
