// IntegerConstant: stores a constant integer value by ID.
// Matches Java IntegerConstant.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class IntegerConstant extends Operation {
    static readonly OP_CODE = 140;
    mId: number;
    private mValue: number;

    constructor(id: number, value: number) {
        super(); this.mId = id; this.mValue = value;
    }

    update(other: IntegerConstant): void { this.mValue = other.mValue; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.loadInteger(this.mId, this.mValue);
    }

    deepToString(indent: string): string { return `${indent}IntegerConstant(${this.mId}, ${this.mValue})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new IntegerConstant(buffer.readInt(), buffer.readInt()));
    }
}
