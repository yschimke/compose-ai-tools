// TextMerge: concatenate two text values into a new text ID.
// Matches Java TextMerge.java.

import { Operation } from '../Operation';
import type { VariableSupport } from '../VariableSupport';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class TextMerge extends Operation implements VariableSupport {
    static readonly OP_CODE = 136;
    private mTextId: number;
    private mSrcId1: number;
    private mSrcId2: number;

    constructor(textId: number, srcId1: number, srcId2: number) {
        super();
        this.mTextId = textId; this.mSrcId1 = srcId1; this.mSrcId2 = srcId2;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mSrcId1, this);
        context.listensTo(this.mSrcId2, this);
    }

    updateVariables(context: RemoteContext): void {
        this.apply(context);
    }

    apply(context: RemoteContext): void {
        const s1 = context.getText(this.mSrcId1) ?? '';
        const s2 = context.getText(this.mSrcId2) ?? '';
        context.loadText(this.mTextId, s1 + s2);
    }

    deepToString(indent: string): string { return `${indent}TextMerge(${this.mTextId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new TextMerge(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}
