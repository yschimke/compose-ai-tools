// DrawSector: draw a sector (pie slice) with variable-driven bounds, start angle and sweep.
// Matches Java DrawSector.java — extends DrawBase6.

import { DrawBase6 } from './DrawBase6';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawSector extends DrawBase6 {
    static readonly OP_CODE = 52;

    paintBase6(context: PaintContext, v1: number, v2: number, v3: number, v4: number, v5: number, v6: number): void {
        context.drawSector(v1, v2, v3, v4, v5, v6);
    }

    deepToString(indent: string): string {
        return `${indent}DrawSector`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawSector(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
