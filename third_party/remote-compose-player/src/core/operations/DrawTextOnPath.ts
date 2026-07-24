// DrawTextOnPath: draw text along a path with variable-driven offsets.
// Matches Java DrawTextOnPath.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DrawTextOnPath extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 53;
    mTextId: number;
    mPathId: number;

    // Wire values as raw float32 int bits (may be NaN-encoded variable refs).
    private mHOffsetBits: number;
    private mVOffsetBits: number;

    // Resolved output values
    private mOutHOffset: number;
    private mOutVOffset: number;

    constructor(textId: number, pathId: number, hOffsetBits: number, vOffsetBits: number) {
        super();
        this.mTextId = textId;
        this.mPathId = pathId;
        this.mHOffsetBits = hOffsetBits;
        this.mVOffsetBits = vOffsetBits;
        this.mOutHOffset = isNaNBits(hOffsetBits) ? 0 : intBitsToFloat(hOffsetBits);
        this.mOutVOffset = isNaNBits(vOffsetBits) ? 0 : intBitsToFloat(vOffsetBits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mTextId, this);
        if (isNaNBits(this.mHOffsetBits)) context.listensTo(idFromBits(this.mHOffsetBits), this);
        if (isNaNBits(this.mVOffsetBits)) context.listensTo(idFromBits(this.mVOffsetBits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mOutHOffset = isNaNBits(this.mHOffsetBits) ? context.getFloat(idFromBits(this.mHOffsetBits)) : intBitsToFloat(this.mHOffsetBits);
        this.mOutVOffset = isNaNBits(this.mVOffsetBits) ? context.getFloat(idFromBits(this.mVOffsetBits)) : intBitsToFloat(this.mVOffsetBits);
    }

    paint(context: PaintContext): void {
        context.drawTextOnPath(this.mTextId, this.getId(this.mPathId, context), this.mOutHOffset, this.mOutVOffset);
    }

    deepToString(indent: string): string { return `${indent}DrawTextOnPath`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const pathId = buffer.readInt();
        const vOffsetBits = buffer.readInt();
        const hOffsetBits = buffer.readInt();
        operations.push(new DrawTextOnPath(textId, pathId, hOffsetBits, vOffsetBits));
    }
}
