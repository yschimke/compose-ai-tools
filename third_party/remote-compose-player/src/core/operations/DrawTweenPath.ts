// DrawTweenPath: draw an interpolated path between two paths.
// Matches Java DrawTweenPath.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DrawTweenPath extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 125;
    private mPath1Id: number;
    private mPath2Id: number;

    // Wire values as raw float32 int bits (may be NaN-encoded variable refs).
    private mTweenBits: number;
    private mStartBits: number;
    private mEndBits: number;

    // Resolved output values
    private mOutTween: number;
    private mOutStart: number;
    private mOutEnd: number;

    constructor(path1Id: number, path2Id: number, tweenBits: number, startBits: number, endBits: number) {
        super();
        this.mPath1Id = path1Id;
        this.mPath2Id = path2Id;
        this.mTweenBits = tweenBits;
        this.mStartBits = startBits;
        this.mEndBits = endBits;
        this.mOutTween = isNaNBits(tweenBits) ? 0 : intBitsToFloat(tweenBits);
        this.mOutStart = isNaNBits(startBits) ? 0 : intBitsToFloat(startBits);
        this.mOutEnd = isNaNBits(endBits) ? 0 : intBitsToFloat(endBits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mTweenBits)) context.listensTo(idFromBits(this.mTweenBits), this);
        if (isNaNBits(this.mStartBits)) context.listensTo(idFromBits(this.mStartBits), this);
        if (isNaNBits(this.mEndBits)) context.listensTo(idFromBits(this.mEndBits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mOutTween = isNaNBits(this.mTweenBits) ? context.getFloat(idFromBits(this.mTweenBits)) : intBitsToFloat(this.mTweenBits);
        this.mOutStart = isNaNBits(this.mStartBits) ? context.getFloat(idFromBits(this.mStartBits)) : intBitsToFloat(this.mStartBits);
        this.mOutEnd = isNaNBits(this.mEndBits) ? context.getFloat(idFromBits(this.mEndBits)) : intBitsToFloat(this.mEndBits);
    }

    paint(context: PaintContext): void {
        context.drawTweenPath(this.getId(this.mPath1Id, context), this.getId(this.mPath2Id, context), this.mOutTween, this.mOutStart, this.mOutEnd);
    }

    deepToString(indent: string): string {
        return `${indent}DrawTweenPath`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawTweenPath(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt()
        ));
    }
}
