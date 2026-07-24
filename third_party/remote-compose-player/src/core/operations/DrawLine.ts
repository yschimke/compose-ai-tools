// DrawLine: draw a line segment with variable-driven endpoints.
// Matches Java DrawLine.java — extends DrawBase4.

import { DrawBase4 } from './DrawBase4';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawLine extends DrawBase4 {
    static readonly OP_CODE = 47;

    paintBase4(context: PaintContext, x1: number, y1: number, x2: number, y2: number): void {
        context.drawLine(x1, y1, x2, y2);
    }

    deepToString(indent: string): string {
        return `${indent}DrawLine(${this.mX1}, ${this.mY1}, ${this.mX2}, ${this.mY2})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawLine(
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
