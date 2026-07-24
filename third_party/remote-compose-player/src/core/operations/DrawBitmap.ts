// DrawBitmap: draw a bitmap with float destination bounds.
// Matches Java DrawBitmap.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DrawBitmap extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 44;

    mImageId: number;
    mCdId: number;

    // Wire values as raw float32 int bits (may be NaN-encoded variable refs).
    private mLeftBits: number;
    private mTopBits: number;
    private mRightBits: number;
    private mBottomBits: number;

    // Resolved output values
    private mOutLeft: number;
    private mOutTop: number;
    private mOutRight: number;
    private mOutBottom: number;

    constructor(imageId: number, leftBits: number, topBits: number, rightBits: number, bottomBits: number, cdId: number) {
        super();
        this.mImageId = imageId;
        this.mLeftBits = leftBits;
        this.mTopBits = topBits;
        this.mRightBits = rightBits;
        this.mBottomBits = bottomBits;
        this.mCdId = cdId;
        this.mOutLeft = isNaNBits(leftBits) ? 0 : intBitsToFloat(leftBits);
        this.mOutTop = isNaNBits(topBits) ? 0 : intBitsToFloat(topBits);
        this.mOutRight = isNaNBits(rightBits) ? 0 : intBitsToFloat(rightBits);
        this.mOutBottom = isNaNBits(bottomBits) ? 0 : intBitsToFloat(bottomBits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mLeftBits)) context.listensTo(idFromBits(this.mLeftBits), this);
        if (isNaNBits(this.mTopBits)) context.listensTo(idFromBits(this.mTopBits), this);
        if (isNaNBits(this.mRightBits)) context.listensTo(idFromBits(this.mRightBits), this);
        if (isNaNBits(this.mBottomBits)) context.listensTo(idFromBits(this.mBottomBits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mOutLeft = isNaNBits(this.mLeftBits) ? context.getFloat(idFromBits(this.mLeftBits)) : intBitsToFloat(this.mLeftBits);
        this.mOutTop = isNaNBits(this.mTopBits) ? context.getFloat(idFromBits(this.mTopBits)) : intBitsToFloat(this.mTopBits);
        this.mOutRight = isNaNBits(this.mRightBits) ? context.getFloat(idFromBits(this.mRightBits)) : intBitsToFloat(this.mRightBits);
        this.mOutBottom = isNaNBits(this.mBottomBits) ? context.getFloat(idFromBits(this.mBottomBits)) : intBitsToFloat(this.mBottomBits);
    }

    paint(context: PaintContext): void {
        context.drawBitmapSimple(
            this.getId(this.mImageId, context),
            this.mOutLeft, this.mOutTop, this.mOutRight, this.mOutBottom
        );
    }

    deepToString(indent: string): string { return `${indent}DrawBitmap(${this.mImageId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const left = buffer.readInt();
        const top = buffer.readInt();
        const right = buffer.readInt();
        const bottom = buffer.readInt();
        const cdId = buffer.readInt();
        operations.push(new DrawBitmap(id, left, top, right, bottom, cdId));
    }
}
