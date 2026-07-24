// DrawToBitmap: redirect drawing output to a bitmap.
// Matches Java DrawToBitmap.java — extends PaintOperation.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawToBitmap extends PaintOperation {
    static readonly OP_CODE = 190;
    mBitmapId: number;
    mMode: number;
    mColor: number;

    constructor(bitmapId: number, mode: number, color: number) {
        super();
        this.mBitmapId = bitmapId;
        this.mMode = mode;
        this.mColor = color;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        context.drawToBitmap(this.getId(this.mBitmapId, context), this.mMode, this.mColor);
    }

    deepToString(indent: string): string { return `${indent}DrawToBitmap(${this.mBitmapId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawToBitmap(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}
