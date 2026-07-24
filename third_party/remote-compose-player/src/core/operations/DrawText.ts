// DrawText: draw a text run with variable-driven position.
// Matches Java DrawText.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DrawText extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 43;
    mTextId: number; mStart: number; mEnd: number;
    mContextStart: number; mContextEnd: number;
    mRtl: boolean;

    // Wire values as raw float32 int bits (may be NaN-encoded variable refs).
    private mXBits: number;
    private mYBits: number;

    // Resolved output values
    mX: number;
    mY: number;

    constructor(textId: number, start: number, end: number, contextStart: number, contextEnd: number,
                xBits: number, yBits: number, rtl: boolean) {
        super();
        this.mTextId = textId; this.mStart = start; this.mEnd = end;
        this.mContextStart = contextStart; this.mContextEnd = contextEnd;
        this.mXBits = xBits; this.mYBits = yBits;
        this.mX = isNaNBits(xBits) ? 0 : intBitsToFloat(xBits);
        this.mY = isNaNBits(yBits) ? 0 : intBitsToFloat(yBits);
        this.mRtl = rtl;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mTextId, this);
        if (isNaNBits(this.mXBits)) context.listensTo(idFromBits(this.mXBits), this);
        if (isNaNBits(this.mYBits)) context.listensTo(idFromBits(this.mYBits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mX = isNaNBits(this.mXBits) ? context.getFloat(idFromBits(this.mXBits)) : intBitsToFloat(this.mXBits);
        this.mY = isNaNBits(this.mYBits) ? context.getFloat(idFromBits(this.mYBits)) : intBitsToFloat(this.mYBits);
    }

    paint(context: PaintContext): void {
        context.drawTextRun(
            this.mTextId, this.mStart, this.mEnd,
            this.mContextStart, this.mContextEnd,
            this.mX, this.mY, this.mRtl
        );
    }

    deepToString(indent: string): string { return `${indent}DrawText(${this.mTextId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawText(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readBoolean()
        ));
    }
}
