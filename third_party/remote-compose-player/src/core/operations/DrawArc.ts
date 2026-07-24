// DrawArc: draw an arc with variable-driven bounds, start angle and sweep.
// Matches Java DrawArc.java — extends DrawBase6.

import { DrawBase6 } from './DrawBase6';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawArc extends DrawBase6 {
    static readonly OP_CODE = 152;

    paintBase6(context: PaintContext, v1: number, v2: number, v3: number, v4: number, v5: number, v6: number): void {
        context.drawArc(v1, v2, v3, v4, v5, v6);
    }

    deepToString(indent: string): string {
        return `${indent}DrawArc(${this.mV1}, ${this.mV2}, ${this.mV3}, ${this.mV4}, start=${this.mV5}, sweep=${this.mV6})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawArc(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
