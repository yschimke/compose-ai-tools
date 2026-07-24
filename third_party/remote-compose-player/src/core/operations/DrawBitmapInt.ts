// DrawBitmapInt: draw a bitmap with integer source and destination rects.
// Matches Java DrawBitmapInt.java — extends PaintOperation.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawBitmapInt extends PaintOperation {
    static readonly OP_CODE = 66;
    mImageId: number;
    mSrcL: number; mSrcT: number; mSrcR: number; mSrcB: number;
    mDstL: number; mDstT: number; mDstR: number; mDstB: number;
    mCdId: number;

    constructor(imageId: number, srcL: number, srcT: number, srcR: number, srcB: number,
                dstL: number, dstT: number, dstR: number, dstB: number, cdId: number) {
        super();
        this.mImageId = imageId;
        this.mSrcL = srcL; this.mSrcT = srcT; this.mSrcR = srcR; this.mSrcB = srcB;
        this.mDstL = dstL; this.mDstT = dstT; this.mDstR = dstR; this.mDstB = dstB;
        this.mCdId = cdId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        context.drawBitmap(
            this.getId(this.mImageId, context), this.mSrcL, this.mSrcT, this.mSrcR, this.mSrcB,
            this.mDstL, this.mDstT, this.mDstR, this.mDstB, this.mCdId
        );
    }

    deepToString(indent: string): string { return `${indent}DrawBitmapInt(${this.mImageId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawBitmapInt(
            buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt()
        ));
    }
}
