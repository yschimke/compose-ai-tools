// TextLength: stores the length of a text string as a float.
// Matches Java TextLength.java (opcode 156).

import { Operation } from '../Operation';
import type { RemoteContext } from '../RemoteContext';
import type { WireBuffer } from '../WireBuffer';
import type { VariableSupport } from '../VariableSupport';

export class TextLength extends Operation implements VariableSupport {
    static readonly OP_CODE = 156;
    private mLengthId: number;
    private mTextId: number;

    constructor(lengthId: number, textId: number) {
        super();
        this.mLengthId = lengthId;
        this.mTextId = textId;
    }

    write(_buffer: WireBuffer): void { /* not needed */ }

    apply(context: RemoteContext): void {
        const text = context.getText(this.mTextId);
        context.loadFloat(this.mLengthId, text ? text.length : 0);
    }

    deepToString(indent: string): string {
        return `${indent}TextLength(${this.mLengthId}, ${this.mTextId})`;
    }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mTextId, this);
    }

    updateVariables(_context: RemoteContext): void { /* nothing to update */ }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const lengthId = buffer.readInt();
        const textId = buffer.readInt();
        operations.push(new TextLength(lengthId, textId));
    }
}
