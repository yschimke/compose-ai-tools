// PaintContext: abstract drawing interface for RemoteCompose operations.

import type { RemoteContext } from './RemoteContext';
import type { RemoteClock } from './RemoteClock';
import type { PaintBundle } from './operations/paint/PaintBundle';

export abstract class PaintContext {
    static readonly TEXT_MEASURE_MONOSPACE_WIDTH = 0x01;
    static readonly TEXT_MEASURE_FONT_HEIGHT = 0x02;
    static readonly TEXT_MEASURE_SPACES = 0x04;
    static readonly TEXT_COMPLEX = 0x08;
    static readonly TEXT_MEASURE_AUTOSIZE = 0x10;

    protected mContext: RemoteContext;
    private mNeedsRepaint = false;
    private mMeasureVersion = 0;

    constructor(context: RemoteContext) {
        this.mContext = context;
    }

    getContext(): RemoteContext { return this.mContext; }
    setContext(context: RemoteContext): void { this.mContext = context; }

    doesNeedsRepaint(): boolean { return this.mNeedsRepaint; }
    clearNeedsRepaint(): void { this.mNeedsRepaint = false; }
    needsRepaint(): void { this.mNeedsRepaint = true; }

    setMeasureVersion(v: number): void { this.mMeasureVersion = v; }
    getMeasureVersion(): number { return this.mMeasureVersion; }

    save(): void { this.matrixSave(); }
    restore(): void { this.matrixRestore(); }
    saveLayer(_x: number, _y: number, _w: number, _h: number): void { this.matrixSave(); }

    getClock(): RemoteClock { return this.mContext.getClock(); }

    isDebug(): boolean { return this.mContext.isBasicDebug(); }
    isAnimationEnabled(): boolean { return this.mContext.isAnimationEnabled(); }
    isVisualDebug(): boolean { return this.mContext.isVisualDebug(); }

    supportsVersion(major: number, minor: number, patch: number): boolean {
        return this.mContext.supportsVersion(major, minor, patch);
    }

    log(content: string): void { console.log(`[LOG] ${content}`); }

    wakeIn(seconds: number): void {
        this.mContext.mRemoteComposeState.wakeIn(seconds);
    }

    // ---- Abstract drawing methods ----

    abstract drawBitmap(imageId: number, srcLeft: number, srcTop: number, srcRight: number, srcBottom: number,
                        dstLeft: number, dstTop: number, dstRight: number, dstBottom: number, cdId: number): void;
    abstract drawBitmapSimple(id: number, left: number, top: number, right: number, bottom: number): void;
    abstract scale(scaleX: number, scaleY: number): void;
    abstract translate(translateX: number, translateY: number): void;
    abstract drawArc(left: number, top: number, right: number, bottom: number, startAngle: number, sweepAngle: number): void;
    abstract drawSector(left: number, top: number, right: number, bottom: number, startAngle: number, sweepAngle: number): void;
    abstract drawCircle(centerX: number, centerY: number, radius: number): void;
    abstract drawLine(x1: number, y1: number, x2: number, y2: number): void;
    abstract drawOval(left: number, top: number, right: number, bottom: number): void;
    abstract drawPath(id: number, start: number, end: number): void;
    abstract drawRect(left: number, top: number, right: number, bottom: number): void;
    abstract drawRoundRect(left: number, top: number, right: number, bottom: number, radiusX: number, radiusY: number): void;
    abstract drawTextOnPath(textId: number, pathId: number, hOffset: number, vOffset: number): void;
    abstract getTextBounds(textId: number, start: number, end: number, flags: number, bounds: Float32Array): void;
    abstract layoutComplexText(textId: number, start: number, end: number, alignment: number, overflow: number,
                               maxLines: number, maxWidth: number, maxHeight: number, letterSpacing: number,
                               lineHeightAdd: number, lineHeightMultiplier: number, lineBreakStrategy: number,
                               hyphenationFrequency: number, justificationMode: number,
                               useUnderline: boolean, strikethrough: boolean, flags: number): any;
    abstract drawTextRun(textId: number, start: number, end: number, contextStart: number, contextEnd: number,
                         x: number, y: number, rtl: boolean): void;
    abstract drawComplexText(computedTextLayout: any): void;
    abstract drawTweenPath(path1Id: number, path2Id: number, tween: number, start: number, end: number): void;
    abstract tweenPath(out: number, path1: number, path2: number, tween: number): void;
    abstract combinePath(out: number, path1: number, path2: number, operation: number): void;
    abstract applyPaint(paintData: PaintBundle): void;
    abstract replacePaint(paintBundle: PaintBundle): void;
    abstract savePaint(): void;
    abstract restorePaint(): void;
    abstract matrixScale(scaleX: number, scaleY: number, centerX: number, centerY: number): void;
    abstract matrixTranslate(translateX: number, translateY: number): void;
    abstract matrixSkew(skewX: number, skewY: number): void;
    abstract matrixRotate(rotate: number, pivotX: number, pivotY: number): void;
    abstract matrixSave(): void;
    abstract matrixRestore(): void;
    abstract clipRect(left: number, top: number, right: number, bottom: number): void;
    abstract clipPath(pathId: number, regionOp: number): void;
    abstract roundedClipRect(width: number, height: number, topStart: number, topEnd: number, bottomStart: number, bottomEnd: number): void;
    abstract reset(): void;
    abstract startGraphicsLayer(w: number, h: number): void;
    abstract setGraphicsLayer(attributes: Map<number, any>): void;
    abstract endGraphicsLayer(): void;
    abstract getText(id: number): string | null;
    abstract matrixFromPath(pathId: number, fraction: number, vOffset: number, flags: number): void;
    abstract drawToBitmap(bitmapId: number, mode: number, color: number): void;
}
