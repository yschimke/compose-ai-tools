// DataDynamicListFloat: a dynamic float list registered as a collection.
// Port of Java DataDynamicListFloat.java.

import { Operation } from '../Operation';
import type { VariableSupport } from '../VariableSupport';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DataDynamicListFloat extends Operation implements VariableSupport {
    static readonly OP_CODE = 197;
    private static readonly MAX_FLOAT_ARRAY = 2000;

    readonly mId: number;
    readonly isDynamic = true;
    // Raw float32 int bits of the array length (may be a NaN-encoded variable ref).
    private mArrayLengthBits: number;
    private mArrayLengthOut: number;
    private mValues: Float32Array;

    constructor(id: number, nbValuesBits: number) {
        super();
        this.mId = id;
        const nbValues = isNaNBits(nbValuesBits) ? NaN : intBitsToFloat(nbValuesBits);
        if (nbValues > DataDynamicListFloat.MAX_FLOAT_ARRAY) {
            throw new Error('Array too large');
        }
        this.mValues = new Float32Array(Math.floor(nbValues) || 0);
        this.mArrayLengthBits = nbValuesBits;
        this.mArrayLengthOut = nbValues;
    }

    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mArrayLengthBits)) {
            this.mArrayLengthOut = context.getFloat(idFromBits(this.mArrayLengthBits));
        }
        if (Math.floor(this.mArrayLengthOut) !== this.mValues.length) {
            this.mValues = new Float32Array(Math.floor(this.mArrayLengthOut) || 0);
        }
    }

    registerListening(context: RemoteContext): void {
        context.addCollection(this.mId, this);
        if (isNaNBits(this.mArrayLengthBits)) {
            context.listensTo(idFromBits(this.mArrayLengthBits), this);
        }
    }

    apply(context: RemoteContext): void {
        context.addCollection(this.mId, this);
    }

    getFloatValue(index: number): number {
        return this.mValues[index];
    }

    getFloats(): Float32Array {
        return this.mValues;
    }

    getLength(): number {
        return this.mValues.length;
    }

    updateValues(values: number[] | Float32Array): void {
        if (values.length !== this.mValues.length) {
            this.mValues = new Float32Array(values.length);
        }
        this.mValues.set(values);
    }

    write(buffer: WireBuffer): void {
        buffer.start(DataDynamicListFloat.OP_CODE);
        buffer.writeInt(this.mId);
        buffer.writeFloat(this.mValues.length);
    }

    deepToString(indent: string): string {
        return `${indent}DataDynamicListFloat(id=${this.mId}, len=${this.mValues.length})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const lenBits = buffer.readInt();
        const len = isNaNBits(lenBits) ? NaN : intBitsToFloat(lenBits);
        if (len > DataDynamicListFloat.MAX_FLOAT_ARRAY) {
            throw new Error(`${len} entries exceeds max = ${DataDynamicListFloat.MAX_FLOAT_ARRAY}`);
        }
        operations.push(new DataDynamicListFloat(id, lenBits));
    }
}
