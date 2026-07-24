// TextSubtext: extracts a substring from a text variable.
// Matches Java TextSubtext.java (opcode 182).

import { Operation } from '../Operation';
import type { RemoteContext } from '../RemoteContext';
import type { WireBuffer } from '../WireBuffer';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class TextSubtext extends Operation implements VariableSupport {
    static readonly OP_CODE = 182;
    private mTextId: number;
    private mSrcId: number;
    // start/len as raw float32 int bits (may be NaN-encoded variable refs).
    private mStartBits: number;
    private mLenBits: number;
    private mOutStart: number;
    private mOutLen: number;

    constructor(textId: number, srcId: number, startBits: number, lenBits: number) {
        super();
        this.mTextId = textId;
        this.mSrcId = srcId;
        this.mStartBits = startBits;
        this.mLenBits = lenBits;
        this.mOutStart = isNaNBits(startBits) ? 0 : intBitsToFloat(startBits);
        this.mOutLen = isNaNBits(lenBits) ? 0 : intBitsToFloat(lenBits);
    }

    write(_buffer: WireBuffer): void { /* not needed */ }

    apply(context: RemoteContext): void {
        const str = context.getText(this.mSrcId);
        if (!str) {
            context.loadText(this.mTextId, '');
            return;
        }
        const s = Math.max(0, Math.min(Math.floor(this.mOutStart), str.length));
        let out: string;
        if (this.mOutLen === -1) {
            out = str.substring(s);
        } else {
            const e = Math.min(s + Math.floor(this.mOutLen), str.length);
            out = str.substring(s, e);
        }
        context.loadText(this.mTextId, out);
    }

    deepToString(indent: string): string {
        return `${indent}TextSubtext(${this.mTextId}, ${this.mSrcId}, ${this.mOutStart}, ${this.mOutLen})`;
    }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mSrcId, this);
        if (isNaNBits(this.mStartBits)) {
            context.listensTo(idFromBits(this.mStartBits), this);
        }
        if (isNaNBits(this.mLenBits)) {
            context.listensTo(idFromBits(this.mLenBits), this);
        }
    }

    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mStartBits)) {
            this.mOutStart = context.getFloat(idFromBits(this.mStartBits));
        }
        if (isNaNBits(this.mLenBits)) {
            this.mOutLen = context.getFloat(idFromBits(this.mLenBits));
        }
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const srcId = buffer.readInt();
        const start = buffer.readInt();
        const len = buffer.readInt();
        operations.push(new TextSubtext(textId, srcId, start, len));
    }
}
