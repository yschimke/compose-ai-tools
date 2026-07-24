// DrawPath: draw a path by ID.
// Matches Java DrawPath.java — extends PaintOperation.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawPath extends PaintOperation {
    static readonly OP_CODE = 124;
    private mPathId: number;
    private mStart: number;
    private mEnd: number;

    constructor(pathId: number, start: number, end: number) {
        super();
        this.mPathId = pathId;
        this.mStart = start;
        this.mEnd = end;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        context.drawPath(this.getId(this.mPathId, context), this.mStart, this.mEnd);
    }

    deepToString(indent: string): string {
        return `${indent}DrawPath(${this.mPathId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        operations.push(new DrawPath(id, 0, 1));
    }
}
