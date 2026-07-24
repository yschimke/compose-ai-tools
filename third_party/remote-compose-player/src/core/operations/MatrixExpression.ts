import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { isNaNBits, idFromBits, floatToRawIntBits } from './Utils';
import { MatrixOperations } from './utilities/MatrixOperations';

export class MatrixExpression extends Operation {
    static readonly OP_CODE = 187;
    private mMatrixId: number;
    private mType: number;
    private mValues = new Float32Array(16);
    // Raw float32 bit pattern of each token (operators are NaN-with-payload).
    // Kept as int bits so encoded operator/variable ids survive engines that
    // canonicalize NaN payloads (Safari/Firefox).
    private mBits: Int32Array;
    private mOutBits: Int32Array | null = null;
    private mMatrixOperations = new MatrixOperations();

    constructor(matrixId: number, type: number, bits: Int32Array) {
        super();
        this.mMatrixId = matrixId;
        this.mType = type;
        this.mBits = bits;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        for (const b of this.mBits) {
            if (isNaNBits(b) && !MatrixOperations.isOperatorBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }

    updateVariables(context: RemoteContext): void {
        if (this.mOutBits === null || this.mOutBits.length !== this.mBits.length) {
            this.mOutBits = new Int32Array(this.mBits.length);
        }
        for (let i = 0; i < this.mBits.length; i++) {
            const b = this.mBits[i];
            if (isNaNBits(b) && !MatrixOperations.isOperatorBits(b)) {
                // Resolve the variable to a literal float and store its bits.
                this.mOutBits[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
            } else {
                this.mOutBits[i] = b;
            }
        }
    }

    apply(context: RemoteContext): void {
        const m = this.mMatrixOperations.evalBits(this.mOutBits);
        m.putValues(this.mValues);
        context.putObject(this.mMatrixId, this);
        context.loadFloat(this.mMatrixId, performance.now() * 1e6);
    }

    getValues(): Float32Array { return this.mValues; }

    deepToString(indent: string): string { return `${indent}MatrixExpression(${this.mMatrixId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const type = buffer.readInt();
        const len = buffer.readInt();
        if (len > 32 || len < 0) throw new Error(`Invalid matrix expression length ${len}`);
        const bits = new Int32Array(len);
        for (let i = 0; i < len; i++) bits[i] = buffer.readInt();
        operations.push(new MatrixExpression(id, type, bits));
    }
}
