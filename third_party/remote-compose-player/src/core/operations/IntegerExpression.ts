// IntegerExpression: RPN-based integer expression evaluator.
// Matches Java IntegerExpression.java — extends Operation, implements VariableSupport.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';

export class IntegerExpression extends Operation implements VariableSupport {
    static readonly OP_CODE = 144;
    private mId: number;
    private mMask: number;
    private mValues: Int32Array;
    private mPreCalcValues: Int32Array | null = null;
    private mVar: number[] = [0, 0, 0];

    private static readonly OFFSET = 0x10000;

    constructor(id: number, mask: number, values: Int32Array) {
        super();
        this.mId = id; this.mMask = mask; this.mValues = values;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    private isId(mask: number, i: number, value: number): boolean {
        return ((1 << i) & mask) !== 0 && value < IntegerExpression.OFFSET;
    }

    registerListening(context: RemoteContext): void {
        for (let i = 0; i < this.mValues.length; i++) {
            if (this.isId(this.mMask, i, this.mValues[i])) {
                context.listensTo(this.mValues[i], this);
            }
        }
    }

    updateVariables(context: RemoteContext): void {
        if (!this.mPreCalcValues || this.mPreCalcValues.length !== this.mValues.length) {
            this.mPreCalcValues = new Int32Array(this.mValues.length);
        }
        for (let i = 0; i < this.mValues.length; i++) {
            if (this.isId(this.mMask, i, this.mValues[i])) {
                this.mPreCalcValues[i] = context.getInteger(this.mValues[i]);
            } else {
                this.mPreCalcValues[i] = this.mValues[i];
            }
        }
    }

    apply(context: RemoteContext): void {
        const vals = this.mPreCalcValues || this.mValues;
        const result = this.evaluate(this.mMask, vals);
        context.loadInteger(this.mId, result);
    }

    private evaluate(mask: number, exp: Int32Array): number {
        const stack = new Int32Array(128);
        let sp = -1;
        const OFFSET = IntegerExpression.OFFSET;
        for (let i = 0; i < exp.length; i++) {
            const v = exp[i];
            if (((1 << i) & mask) !== 0) {
                // Operator
                const op = v - OFFSET;
                switch (op) {
                    case 1: stack[sp - 1] = (stack[sp - 1] + stack[sp]) | 0; sp--; break; // ADD
                    case 2: stack[sp - 1] = (stack[sp - 1] - stack[sp]) | 0; sp--; break; // SUB
                    case 3: stack[sp - 1] = Math.imul(stack[sp - 1], stack[sp]); sp--; break; // MUL
                    case 4: stack[sp - 1] = stack[sp] !== 0 ? (stack[sp - 1] / stack[sp]) | 0 : 0; sp--; break; // DIV
                    case 5: stack[sp - 1] = (stack[sp - 1] % stack[sp]) | 0; sp--; break; // MOD
                    case 6: stack[sp - 1] = stack[sp - 1] << stack[sp]; sp--; break; // SHL
                    case 7: stack[sp - 1] = stack[sp - 1] >> stack[sp]; sp--; break; // SHR
                    case 8: stack[sp - 1] = stack[sp - 1] >>> stack[sp]; sp--; break; // USHR
                    case 9: stack[sp - 1] = stack[sp - 1] | stack[sp]; sp--; break; // OR
                    case 10: stack[sp - 1] = stack[sp - 1] & stack[sp]; sp--; break; // AND
                    case 11: stack[sp - 1] = stack[sp - 1] ^ stack[sp]; sp--; break; // XOR
                    case 12: // COPY_SIGN
                        stack[sp - 1] = (stack[sp - 1] ^ (stack[sp] >> 31)) - (stack[sp] >> 31);
                        sp--; break;
                    case 13: stack[sp - 1] = Math.min(stack[sp - 1], stack[sp]); sp--; break; // MIN
                    case 14: stack[sp - 1] = Math.max(stack[sp - 1], stack[sp]); sp--; break; // MAX
                    case 15: stack[sp] = -stack[sp]; break; // NEG
                    case 16: stack[sp] = Math.abs(stack[sp]); break; // ABS
                    case 17: stack[sp] = (stack[sp] + 1) | 0; break; // INCR
                    case 18: stack[sp] = (stack[sp] - 1) | 0; break; // DECR
                    case 19: stack[sp] = ~stack[sp]; break; // NOT
                    case 20: // SIGN
                        stack[sp] = (stack[sp] >> 31) | ((-stack[sp]) >>> 31);
                        break;
                    case 21: // CLAMP
                        stack[sp - 2] = Math.min(Math.max(stack[sp - 2], stack[sp]), stack[sp - 1]);
                        sp -= 2; break;
                    case 22: // IFELSE
                        stack[sp - 2] = stack[sp] > 0 ? stack[sp - 1] : stack[sp - 2];
                        sp -= 2; break;
                    case 23: // MAD
                        stack[sp - 2] = (stack[sp] + Math.imul(stack[sp - 1], stack[sp - 2])) | 0;
                        sp -= 2; break;
                    case 24: stack[++sp] = this.mVar[0]; break; // VAR1
                    case 25: stack[++sp] = this.mVar[1]; break; // VAR2
                    case 26: stack[++sp] = this.mVar[2]; break; // VAR3
                    default: break;
                }
            } else {
                stack[++sp] = v;
            }
        }
        return sp >= 0 ? stack[sp] : 0;
    }

    deepToString(indent: string): string { return `${indent}IntegerExpression(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const mask = buffer.readInt();
        const len = buffer.readInt();
        const values = new Int32Array(len);
        for (let i = 0; i < len; i++) {
            values[i] = buffer.readInt();
        }
        operations.push(new IntegerExpression(id, mask, values));
    }
}
