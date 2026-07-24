// BooleanConstant: stores a constant boolean value by ID.
// Matches Java BooleanConstant.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class BooleanConstant extends Operation {
    static readonly OP_CODE = 143;
    private mId: number;
    private mValue: boolean;

    constructor(id: number, value: boolean) {
        super(); this.mId = id; this.mValue = value;
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* no-op in Java */ }

    deepToString(indent: string): string { return `${indent}BooleanConstant(${this.mId}, ${this.mValue})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new BooleanConstant(buffer.readInt(), buffer.readBoolean()));
    }
}
