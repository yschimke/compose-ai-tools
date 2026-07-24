// DrawCircle: draw a circle with variable-driven center and radius.
// Matches Java DrawCircle.java — extends DrawBase3.

import { DrawBase3 } from './DrawBase3';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawCircle extends DrawBase3 {
    static readonly OP_CODE = 46;

    paintBase3(context: PaintContext, v1: number, v2: number, v3: number): void {
        context.drawCircle(v1, v2, v3);
    }

    deepToString(indent: string): string {
        return `${indent}DrawCircle(${this.mV1}, ${this.mV2}, ${this.mV3})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawCircle(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}
