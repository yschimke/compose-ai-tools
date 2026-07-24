// DrawRoundRect: draw a rounded rectangle with variable-driven bounds and radii.
// Matches Java DrawRoundRect.java — extends DrawBase6.

import { DrawBase6 } from './DrawBase6';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawRoundRect extends DrawBase6 {
    static readonly OP_CODE = 51;

    paintBase6(context: PaintContext, v1: number, v2: number, v3: number, v4: number, v5: number, v6: number): void {
        context.drawRoundRect(v1, v2, v3, v4, v5, v6);
    }

    deepToString(indent: string): string {
        return `${indent}DrawRoundRect(${this.mV1}, ${this.mV2}, ${this.mV3}, ${this.mV4}, rx=${this.mV5}, ry=${this.mV6})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawRoundRect(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
