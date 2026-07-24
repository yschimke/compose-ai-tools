// DrawBitmapScaled: draw a bitmap with float source/dest bounds and scale type.
// Matches Java DrawBitmapScaled.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';
import { ImageScaling } from './utilities/ImageScaling';

export class DrawBitmapScaled extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 149;
    mImageId: number;
    // Wire values stored as raw float32 int bits (may be NaN-encoded refs).
    private mSrcLeft: number; private mOutSrcLeft: number;
    private mSrcTop: number; private mOutSrcTop: number;
    private mSrcRight: number; private mOutSrcRight: number;
    private mSrcBottom: number; private mOutSrcBottom: number;
    private mDstLeft: number; private mOutDstLeft: number;
    private mDstTop: number; private mOutDstTop: number;
    private mDstRight: number; private mOutDstRight: number;
    private mDstBottom: number; private mOutDstBottom: number;
    private mScaleType: number;
    private mScaleFactor: number; private mOutScaleFactor: number;
    private mCdId: number;
    private mScaling = new ImageScaling();

    // Args are raw int32 bits for the float fields (see read()).
    constructor(imageId: number, srcL: number, srcT: number, srcR: number, srcB: number,
                dstL: number, dstT: number, dstR: number, dstB: number,
                scaleType: number, scaleFactor: number, cdId: number) {
        super();
        const lit = (b: number) => isNaNBits(b) ? 0 : intBitsToFloat(b);
        this.mImageId = imageId;
        this.mSrcLeft = srcL; this.mOutSrcLeft = lit(srcL);
        this.mSrcTop = srcT; this.mOutSrcTop = lit(srcT);
        this.mSrcRight = srcR; this.mOutSrcRight = lit(srcR);
        this.mSrcBottom = srcB; this.mOutSrcBottom = lit(srcB);
        this.mDstLeft = dstL; this.mOutDstLeft = lit(dstL);
        this.mDstTop = dstT; this.mOutDstTop = lit(dstT);
        this.mDstRight = dstR; this.mOutDstRight = lit(dstR);
        this.mDstBottom = dstB; this.mOutDstBottom = lit(dstB);
        this.mScaleType = scaleType & 0xFF;
        if (((scaleType >> 8) & 0x1) !== 0) {
            this.mImageId |= PaintOperation.PTR_DEREFERENCE;
        }
        this.mScaleFactor = scaleFactor; this.mOutScaleFactor = lit(scaleFactor);
        this.mCdId = cdId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        const register = (b: number) => {
            if (isNaNBits(b)) context.listensTo(idFromBits(b), this);
        };
        register(this.mSrcLeft); register(this.mSrcTop);
        register(this.mSrcRight); register(this.mSrcBottom);
        register(this.mDstLeft); register(this.mDstTop);
        register(this.mDstRight); register(this.mDstBottom);
        register(this.mScaleFactor);
    }

    updateVariables(context: RemoteContext): void {
        const resolve = (b: number) =>
            isNaNBits(b) ? context.getFloat(idFromBits(b)) : intBitsToFloat(b);
        this.mOutSrcLeft = resolve(this.mSrcLeft);
        this.mOutSrcTop = resolve(this.mSrcTop);
        this.mOutSrcRight = resolve(this.mSrcRight);
        this.mOutSrcBottom = resolve(this.mSrcBottom);
        this.mOutDstLeft = resolve(this.mDstLeft);
        this.mOutDstTop = resolve(this.mDstTop);
        this.mOutDstRight = resolve(this.mDstRight);
        this.mOutDstBottom = resolve(this.mDstBottom);
        this.mOutScaleFactor = resolve(this.mScaleFactor);
    }

    paint(context: PaintContext): void {
        this.mScaling.setup(
            this.mOutSrcLeft, this.mOutSrcTop, this.mOutSrcRight, this.mOutSrcBottom,
            this.mOutDstLeft, this.mOutDstTop, this.mOutDstRight, this.mOutDstBottom,
            this.mScaleType, this.mOutScaleFactor
        );
        context.save();
        context.clipRect(this.mOutDstLeft, this.mOutDstTop, this.mOutDstRight, this.mOutDstBottom);
        context.drawBitmap(
            this.getId(this.mImageId, context),
            this.mOutSrcLeft | 0, this.mOutSrcTop | 0,
            this.mOutSrcRight | 0, this.mOutSrcBottom | 0,
            this.mScaling.mFinalDstLeft | 0, this.mScaling.mFinalDstTop | 0,
            this.mScaling.mFinalDstRight | 0, this.mScaling.mFinalDstBottom | 0,
            this.mCdId
        );
        context.restore();
    }

    deepToString(indent: string): string { return `${indent}DrawBitmapScaled(${this.mImageId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const srcL = buffer.readInt(); const srcT = buffer.readInt();
        const srcR = buffer.readInt(); const srcB = buffer.readInt();
        const dstL = buffer.readInt(); const dstT = buffer.readInt();
        const dstR = buffer.readInt(); const dstB = buffer.readInt();
        const scaleType = buffer.readInt();
        const scaleFactor = buffer.readInt();
        const cdId = buffer.readInt();
        operations.push(new DrawBitmapScaled(id, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB, scaleType, scaleFactor, cdId));
    }
}
