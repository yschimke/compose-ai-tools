import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class MatrixConstant extends Operation {
    static readonly OP_CODE = 186;
    private mMatrixId: number;
    private mType: number;
    private mValues: Float32Array;

    constructor(matrixId: number, type: number, values: Float32Array) {
        super();
        this.mMatrixId = matrixId;
        this.mType = type;
        this.mValues = values;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.putObject(this.mMatrixId, this);
    }

    getValues(): Float32Array { return this.mValues; }

    deepToString(indent: string): string { return `${indent}MatrixConstant(${this.mMatrixId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const type = buffer.readInt();
        const len = buffer.readInt();
        if (len > 16 || len < 0) throw new Error(`Invalid matrix length ${len}`);
        const values = new Float32Array(len);
        for (let i = 0; i < len; i++) values[i] = buffer.readFloat();
        operations.push(new MatrixConstant(id, type, values));
    }
}
