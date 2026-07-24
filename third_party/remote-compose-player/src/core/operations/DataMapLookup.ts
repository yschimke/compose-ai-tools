// DataMapLookup: look up a value in a DataMap by string key.
// Matches Java DataMapLookup.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { DataMap } from './DataMapIds';

// Type constants matching Java DataMapIds
const TYPE_STRING = 0;
const TYPE_INT = 1;
const TYPE_FLOAT = 2;
const TYPE_LONG = 3;
const TYPE_BOOLEAN = 4;

export class DataMapLookup extends Operation {
    static readonly OP_CODE = 154;
    private mId: number;
    private mMapId: number;
    private mStringId: number;

    constructor(id: number, mapId: number, stringId: number) {
        super();
        this.mId = id;
        this.mMapId = mapId;
        this.mStringId = stringId;
    }

    write(_buffer: WireBuffer): void { /* not needed */ }

    apply(context: RemoteContext): void {
        const str = context.getText(this.mStringId);
        if (!str) return;
        const data: DataMap = context.getDataMap(this.mMapId);
        if (!data) return;
        const pos = data.getPos(str);
        if (pos < 0) return;
        const type = data.mTypes[pos];
        const dataId = data.mIds[pos];

        switch (type) {
            case TYPE_STRING: {
                const text = context.getText(dataId);
                if (text !== null) context.loadText(this.mId, text);
                break;
            }
            case TYPE_INT:
                context.loadInteger(this.mId, context.getInteger(dataId));
                break;
            case TYPE_FLOAT:
                context.loadFloat(this.mId, context.getFloat(dataId));
                break;
            case TYPE_LONG:
                context.loadInteger(this.mId, context.getLong(dataId) | 0);
                break;
            case TYPE_BOOLEAN:
                context.loadInteger(this.mId, context.getInteger(dataId) ? 1 : 0);
                break;
        }
    }

    deepToString(indent: string): string {
        return `${indent}DataMapLookup(${this.mId}, map=${this.mMapId}, key=${this.mStringId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const mapId = buffer.readInt();
        const stringId = buffer.readInt();
        operations.push(new DataMapLookup(id, mapId, stringId));
    }
}
