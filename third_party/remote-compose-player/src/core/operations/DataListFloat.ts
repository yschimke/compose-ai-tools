// DataListFloat: store a list of float values as a collection.
// Matches Java DataListFloat.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { idFromBits, isVariableBits, intBitsToFloat } from './Utils';

export class DataListFloat extends Operation implements VariableSupport {
    static readonly OP_CODE = 147;
    mId: number;
    // Raw float32 int bits of each entry (entries may be NaN-encoded variable
    // refs). Bits survive engines that canonicalize NaN payloads (Safari/Firefox).
    private mBits: Int32Array;
    // Float view used by collection accessors (literals decoded; unresolved
    // variable-ref slots remain NaN, matching the Java TODO).
    private mValues: Float32Array;

    constructor(id: number, bits: Int32Array) {
        super();
        this.mId = id;
        this.mBits = bits;
        this.mValues = new Float32Array(bits.length);
        for (let i = 0; i < bits.length; i++) this.mValues[i] = intBitsToFloat(bits[i]);
    }

    update(other: DataListFloat): void { this.mBits = other.mBits; this.mValues = other.mValues; }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.addCollection(this.mId, {
            values: this.mValues,
            getLength: () => this.mValues.length,
            getFloatValue: (i: number) => this.mValues[i],
            getFloats: () => this.mValues
        });
        for (const b of this.mBits) {
            if (isVariableBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }

    updateVariables(_context: RemoteContext): void {
        // TODO: add support for variables in arrays (matches Java TODO)
    }

    apply(context: RemoteContext): void {
        context.addCollection(this.mId, {
            values: this.mValues,
            getLength: () => this.mValues.length,
            getFloatValue: (i: number) => this.mValues[i],
            getFloats: () => this.mValues
        });
    }

    deepToString(indent: string): string { return `${indent}DataListFloat(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const count = buffer.readInt();
        const bits = new Int32Array(count);
        for (let i = 0; i < count; i++) {
            bits[i] = buffer.readInt();
        }
        operations.push(new DataListFloat(id, bits));
    }
}
