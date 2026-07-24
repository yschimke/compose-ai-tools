// ImageLayout: image layout manager.
// Port of Java ImageLayout.java — displays a bitmap with scaling and alpha.

import { LayoutManager } from './LayoutManager';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';
import type { VariableSupport } from '../../../VariableSupport';
import { ImageScaling } from '../../utilities/ImageScaling';
import { PaintBundle } from '../../paint/PaintBundle';
import { isNaNBits, idFromBits, intBitsToFloat } from '../../Utils';
import { BitmapData } from '../../DataOperations';

export class ImageLayout extends LayoutManager implements VariableSupport {
    static readonly OP_CODE = 234;

    private mBitmapId: number;
    private mScaleType: number;
    private mAlphaBits: number;
    private mOutAlpha: number;
    private mScaling = new ImageScaling();
    private mPaint = new PaintBundle();

    constructor(componentId: number, animationId: number,
                bitmapId: number, scaleType: number, alphaBits: number) {
        super(componentId, animationId);
        this.mBitmapId = bitmapId;
        this.mScaleType = scaleType & 0xFF;
        this.mAlphaBits = alphaBits;
        this.mOutAlpha = isNaNBits(alphaBits) ? 1 : intBitsToFloat(alphaBits);
    }

    registerListening(context: RemoteContext): void {
        if (this.mBitmapId !== -1) {
            context.listensTo(this.mBitmapId, this);
        }
        if (isNaNBits(this.mAlphaBits)) {
            context.listensTo(idFromBits(this.mAlphaBits), this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mOutAlpha = isNaNBits(this.mAlphaBits)
            ? context.getFloat(idFromBits(this.mAlphaBits))
            : intBitsToFloat(this.mAlphaBits);
    }

    computeWrapSize(context: PaintContext, _minWidth: number, _maxWidth: number,
                    _minHeight: number, _maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    _measure: MeasurePass, size: Size): void {
        const bitmapData = context.getContext().getObject(this.mBitmapId);
        if (bitmapData && bitmapData instanceof BitmapData) {
            size.setWidth(bitmapData.getWidth());
            size.setHeight(bitmapData.getHeight());
        }
    }

    computeSize(context: PaintContext, _minWidth: number, _maxWidth: number,
                _minHeight: number, _maxHeight: number, measure: MeasurePass): void {
        const modW = this.computeModifierDefinedWidth();
        const modH = this.computeModifierDefinedHeight();
        const m = measure.get(this);
        if (modW >= 0) m.setW(modW);
        if (modH >= 0) m.setH(modH);
    }

    paintingComponent(paintContext: PaintContext): void {
        paintContext.save();
        paintContext.translate(this.mX, this.mY);

        // Paint modifiers (background, border, clip, etc.)
        const context = paintContext.getContext();
        const tx = this.mPaddingLeft;
        const ty = this.mPaddingTop;
        paintContext.translate(tx, ty);
        const w = this.mWidth - this.mPaddingLeft - this.mPaddingRight;
        const h = this.mHeight - this.mPaddingTop - this.mPaddingBottom;
        paintContext.clipRect(0, 0, w, h);

        const bitmapData = context.getObject(this.mBitmapId);
        if (bitmapData && bitmapData instanceof BitmapData) {
            this.mScaling.setup(
                0, 0, bitmapData.getWidth(), bitmapData.getHeight(),
                0, 0, w, h,
                this.mScaleType, 1
            );

            paintContext.savePaint();
            if (this.mOutAlpha !== 1) {
                this.mPaint.reset();
                this.mPaint.setColor(0, 0, 0, this.mOutAlpha);
                paintContext.applyPaint(this.mPaint);
            }
            paintContext.drawBitmap(
                this.mBitmapId,
                0, 0,
                bitmapData.getWidth() | 0,
                bitmapData.getHeight() | 0,
                this.mScaling.mFinalDstLeft | 0,
                this.mScaling.mFinalDstTop | 0,
                this.mScaling.mFinalDstRight | 0,
                this.mScaling.mFinalDstBottom | 0,
                -1
            );
            paintContext.restorePaint();
        }

        paintContext.translate(-tx, -ty);
        paintContext.restore();
    }

    write(buffer: WireBuffer): void {
        buffer.start(ImageLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(0); // animationId
        buffer.writeInt(this.mBitmapId);
        buffer.writeInt(this.mScaleType);
        buffer.writeInt(this.mAlphaBits);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}ImageLayout(${this.getComponentId()}, bitmap=${this.mBitmapId}, scale=${this.mScaleType})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        const bitmapId = buffer.readInt();
        const scaleType = buffer.readInt();
        const alpha = buffer.readInt();
        operations.push(new ImageLayout(componentId, animationId, bitmapId, scaleType, alpha));
    }
}
