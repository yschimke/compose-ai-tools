// DataMapIds: store a map of named IDs by collection ID.
// Matches Java DataMapIds.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class DataMap {
    readonly mNames: string[];
    readonly mTypes: Int8Array;
    readonly mIds: Int32Array;

    constructor(names: string[], types: Int8Array, ids: Int32Array) {
        this.mNames = names;
        this.mTypes = types;
        this.mIds = ids;
    }

    getPos(name: string): number {
        return this.mNames.indexOf(name);
    }
}

export class DataMapIds extends Operation {
    static readonly OP_CODE = 145;
    private mId: number;
    private mDataMap: DataMap;

    constructor(id: number, dataMap: DataMap) {
        super(); this.mId = id; this.mDataMap = dataMap;
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        context.putDataMap(this.mId, this.mDataMap);
    }

    deepToString(indent: string): string { return `${indent}DataMapIds(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const len = buffer.readInt();
        const names = new Array<string>(len);
        const types = new Int8Array(len);
        const ids = new Int32Array(len);
        for (let i = 0; i < len; i++) {
            names[i] = buffer.readUTF8();
            types[i] = buffer.readByte();
            ids[i] = buffer.readInt();
        }
        operations.push(new DataMapIds(id, new DataMap(names, types, ids)));
    }
}
