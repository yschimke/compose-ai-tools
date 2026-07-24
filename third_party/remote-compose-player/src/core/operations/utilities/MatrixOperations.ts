// MatrixOperations: RPN matrix expression evaluator, matching Java MatrixOperations.java

import { Matrix } from './Matrix';
import { floatToRawIntBits, intBitsToFloat, isNaNBits, idFromBits } from '../Utils';

const OFFSET = 0x320000;

function asNan(v: number): number {
    return intBitsToFloat(v | -0x800000);
}

function fromNaN(v: number): number {
    return floatToRawIntBits(v) & 0x3FFFFF;
}

const ID_REGION_ARRAY = 0x200000;

function isDataVariable(v: number): boolean {
    const id = fromNaN(v);
    return (id & 0x700000) === ID_REGION_ARRAY;
}

function isDataVariableBits(b: number): boolean {
    return (idFromBits(b) & 0x700000) === ID_REGION_ARRAY;
}

export class MatrixOperations {
    static readonly OFFSET = OFFSET;
    static readonly LAST_OP = OFFSET + 54;

    // Operator NaN constants
    static readonly IDENTITY = asNan(OFFSET + 1);
    static readonly ROT_X = asNan(OFFSET + 2);
    static readonly ROT_Y = asNan(OFFSET + 3);
    static readonly ROT_Z = asNan(OFFSET + 4);
    static readonly TRANSLATE_X = asNan(OFFSET + 5);
    static readonly TRANSLATE_Y = asNan(OFFSET + 6);
    static readonly TRANSLATE_Z = asNan(OFFSET + 7);
    static readonly TRANSLATE2 = asNan(OFFSET + 8);
    static readonly TRANSLATE3 = asNan(OFFSET + 9);
    static readonly SCALE_X = asNan(OFFSET + 10);
    static readonly SCALE_Y = asNan(OFFSET + 11);
    static readonly SCALE_Z = asNan(OFFSET + 12);
    static readonly SCALE2 = asNan(OFFSET + 13);
    static readonly SCALE3 = asNan(OFFSET + 14);
    static readonly MUL = asNan(OFFSET + 15);
    static readonly ROT_PZ = asNan(OFFSET + 16);
    static readonly ROT_AXIS = asNan(OFFSET + 17);
    static readonly PROJECTION = asNan(OFFSET + 18);

    // Op codes
    private static readonly OP_IDENTITY = OFFSET + 1;
    private static readonly OP_ROT_X = OFFSET + 2;
    private static readonly OP_ROT_Y = OFFSET + 3;
    private static readonly OP_ROT_Z = OFFSET + 4;
    private static readonly OP_TRANSLATE_X = OFFSET + 5;
    private static readonly OP_TRANSLATE_Y = OFFSET + 6;
    private static readonly OP_TRANSLATE_Z = OFFSET + 7;
    private static readonly OP_TRANSLATE2 = OFFSET + 8;
    private static readonly OP_TRANSLATE3 = OFFSET + 9;
    private static readonly OP_SCALE_X = OFFSET + 10;
    private static readonly OP_SCALE_Y = OFFSET + 11;
    private static readonly OP_SCALE_Z = OFFSET + 12;
    private static readonly OP_SCALE2 = OFFSET + 13;
    private static readonly OP_SCALE3 = OFFSET + 14;
    private static readonly OP_MUL = OFFSET + 15;
    private static readonly OP_ROT_PZ = OFFSET + 16;
    private static readonly OP_ROT_AXIS = OFFSET + 17;
    private static readonly OP_PROJECTION = OFFSET + 18;

    private mMatrices: Matrix[] = [];
    private mTmpMatrix = new Matrix();
    private mMatrixIndex = -1;
    private mStack: number[] | Float32Array = [];

    constructor() {
        for (let i = 0; i < 10; i++) {
            this.mMatrices.push(new Matrix(4, 4));
        }
    }

    static isOperator(v: number): boolean {
        if (Number.isNaN(v)) {
            if (isDataVariable(v)) return false;
            const pos = fromNaN(v);
            return pos > OFFSET && pos <= MatrixOperations.LAST_OP;
        }
        return false;
    }

    /** Bit-aware operator test: true if raw float32 int bits encode an operator token. */
    static isOperatorBits(b: number): boolean {
        if (isNaNBits(b)) {
            if (isDataVariableBits(b)) return false;
            const pos = idFromBits(b);
            return pos > OFFSET && pos <= MatrixOperations.LAST_OP;
        }
        return false;
    }

    eval(exp: number[] | Float32Array | null): Matrix {
        if (!exp) {
            this.mMatrices[0].setIdentity();
            return this.mMatrices[0];
        }
        this.mStack = exp;
        this.mMatrixIndex = 0;
        this.mMatrices[0].setIdentity();
        for (let i = 0; i < exp.length; i++) {
            const v = exp[i];
            if (Number.isNaN(v)) {
                this.opEval(i, fromNaN(v));
            }
        }
        return this.mMatrices[0];
    }

    /**
     * Evaluate from raw float32 int bits. Operator tokens are detected directly
     * from the bits (robust against engines that canonicalize NaN payloads); the
     * stack holds decoded literal floats so opEval reads ordinary numbers.
     */
    evalBits(bits: Int32Array | null): Matrix {
        if (!bits) {
            this.mMatrices[0].setIdentity();
            return this.mMatrices[0];
        }
        // Decode token bits into a float stack once; operator positions still
        // carry their NaN value but opEval only reads operand slots (never the
        // operator slot itself), so the decoded NaN there is harmless.
        const stack = new Float32Array(bits.length);
        for (let i = 0; i < bits.length; i++) stack[i] = intBitsToFloat(bits[i]);
        this.mStack = stack;
        this.mMatrixIndex = 0;
        this.mMatrices[0].setIdentity();
        for (let i = 0; i < bits.length; i++) {
            if (isNaNBits(bits[i])) {
                this.opEval(i, idFromBits(bits[i]));
            }
        }
        return this.mMatrices[0];
    }

    private opEval(sp: number, id: number): void {
        const s = this.mStack;
        const m = this.mMatrices;
        switch (id) {
            case MatrixOperations.OP_IDENTITY:
                m[++this.mMatrixIndex].setIdentity();
                return;
            case MatrixOperations.OP_ROT_X:
                m[this.mMatrixIndex].rotateX(s[sp - 1]);
                return;
            case MatrixOperations.OP_ROT_Y:
                m[this.mMatrixIndex].rotateY(s[sp - 1]);
                return;
            case MatrixOperations.OP_ROT_Z:
                m[this.mMatrixIndex].rotateZ(s[sp - 1]);
                return;
            case MatrixOperations.OP_TRANSLATE_X:
                m[this.mMatrixIndex].translate(s[sp - 1], 0, 0);
                return;
            case MatrixOperations.OP_TRANSLATE_Y:
                m[this.mMatrixIndex].translate(0, s[sp - 1], 0);
                return;
            case MatrixOperations.OP_TRANSLATE_Z:
                m[this.mMatrixIndex].translate(0, 0, s[sp - 1]);
                return;
            case MatrixOperations.OP_TRANSLATE2:
                m[this.mMatrixIndex].translate(s[sp - 2], s[sp - 1], 0);
                return;
            case MatrixOperations.OP_TRANSLATE3:
                m[this.mMatrixIndex].translate(s[sp - 3], s[sp - 2], s[sp - 1]);
                return;
            case MatrixOperations.OP_SCALE_X:
                m[this.mMatrixIndex].setScale(s[sp - 1], 1, 1);
                return;
            case MatrixOperations.OP_SCALE_Y:
                m[this.mMatrixIndex].setScale(1, s[sp - 1], 1);
                return;
            case MatrixOperations.OP_SCALE_Z:
                m[this.mMatrixIndex].setScale(1, 1, s[sp - 1]);
                return;
            case MatrixOperations.OP_SCALE2:
                m[this.mMatrixIndex].setScale(s[sp - 2], s[sp - 1], 0);
                return;
            case MatrixOperations.OP_SCALE3:
                m[this.mMatrixIndex].setScale(s[sp - 3], s[sp - 2], s[sp - 1]);
                return;
            case MatrixOperations.OP_MUL:
                Matrix.multiply(m[this.mMatrixIndex - 1], m[this.mMatrixIndex], this.mTmpMatrix);
                m[this.mMatrixIndex - 1].copyFromMatrix(this.mTmpMatrix);
                this.mMatrixIndex--;
                return;
            case MatrixOperations.OP_ROT_PZ:
                // angle, pivot x, pivot y, ROT_PZ
                m[this.mMatrixIndex].rotateZ(s[sp - 2], s[sp - 1], s[sp - 3]);
                return;
            case MatrixOperations.OP_ROT_AXIS:
                // angle, x, y, z, ROT_AXIS
                m[this.mMatrixIndex].rotateAroundAxis(s[sp - 3], s[sp - 2], s[sp - 1], s[sp - 4]);
                return;
            case MatrixOperations.OP_PROJECTION:
                // fovDegrees, aspectRatio, near, far, PROJECTION
                m[this.mMatrixIndex].projection(s[sp - 4], s[sp - 3], s[sp - 2], s[sp - 1]);
                return;
        }
    }
}
