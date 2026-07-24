// LongConstant: stores a constant long value by ID.
// Matches Java LongConstant.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class LongConstant extends Operation {
    static readonly OP_CODE = 148;
    mId: number;
    private mValue: number;

    constructor(id: number, value: number) {
        super(); this.mId = id; this.mValue = value;
    }

    update(other: LongConstant): void { this.mValue = other.mValue; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.putObject(this.mId, this);
    }

    deepToString(indent: string): string { return `${indent}LongConstant(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new LongConstant(buffer.readInt(), buffer.readLong()));
    }
}
