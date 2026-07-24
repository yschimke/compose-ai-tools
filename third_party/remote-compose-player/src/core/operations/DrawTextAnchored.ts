// DrawTextAnchored: anchored text drawing with variable-driven position.
// Matches Java DrawTextAnchored.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export class DrawTextAnchored extends PaintOperation implements VariableSupport {
    static readonly OP_CODE = 133;

    static readonly ANCHOR_TEXT_RTL = 1;
    static readonly ANCHOR_MONOSPACE_MEASURE = 2;
    static readonly MEASURE_EVERY_TIME = 4;
    static readonly BASELINE_RELATIVE = 8;

    // Original wire values as raw float32 int bits (may be NaN-encoded refs).
    mTextID: number;
    private mXBits: number;
    private mYBits: number;
    private mPanXBits: number;
    private mPanYBits: number;
    mFlags: number;

    // Resolved output values (updated by updateVariables)
    mOutX: number;
    mOutY: number;
    mOutPanX: number;
    mOutPanY: number;

    // Cached text measurement
    mBounds = new Float32Array(4);
    mLastString: string | null = null;

    constructor(textId: number, xBits: number, yBits: number, panXBits: number, panYBits: number, flags: number) {
        super();
        this.mTextID = textId;
        this.mXBits = xBits;
        this.mYBits = yBits;
        this.mPanXBits = panXBits;
        this.mPanYBits = panYBits;
        this.mFlags = flags;
        this.mOutX = isNaNBits(xBits) ? 0 : intBitsToFloat(xBits);
        this.mOutY = isNaNBits(yBits) ? 0 : intBitsToFloat(yBits);
        this.mOutPanX = isNaNBits(panXBits) ? 0 : intBitsToFloat(panXBits);
        // panY may be a literal NaN sentinel ("no vertical pan"); keep it NaN.
        this.mOutPanY = intBitsToFloat(panYBits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        context.listensTo(this.mTextID, this);
        if (isNaNBits(this.mXBits)) {
            context.listensTo(idFromBits(this.mXBits), this);
        }
        if (isNaNBits(this.mYBits)) {
            context.listensTo(idFromBits(this.mYBits), this);
        }
        if (isNaNBits(this.mPanXBits)) {
            context.listensTo(idFromBits(this.mPanXBits), this);
        }
        if (isNaNBits(this.mPanYBits) && idFromBits(this.mPanYBits) > 0) {
            context.listensTo(idFromBits(this.mPanYBits), this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mOutX = isNaNBits(this.mXBits) ? context.getFloat(idFromBits(this.mXBits)) : intBitsToFloat(this.mXBits);
        this.mOutY = isNaNBits(this.mYBits) ? context.getFloat(idFromBits(this.mYBits)) : intBitsToFloat(this.mYBits);
        this.mOutPanX = isNaNBits(this.mPanXBits) ? context.getFloat(idFromBits(this.mPanXBits)) : intBitsToFloat(this.mPanXBits);
        // A panY of NaN with id 0 is a "no pan" sentinel — keep it NaN so paint()
        // detects it; otherwise resolve the variable reference.
        this.mOutPanY = (isNaNBits(this.mPanYBits) && idFromBits(this.mPanYBits) > 0)
            ? context.getFloat(idFromBits(this.mPanYBits))
            : intBitsToFloat(this.mPanYBits);
    }

    private getHorizontalOffset(): number {
        // TODO scale TextSize / BaseTextSize
        const scale = 1.0;
        const textWidth = scale * (this.mBounds[2] - this.mBounds[0]);
        const boxWidth = 0;
        return (boxWidth - textWidth) * (1 + this.mOutPanX) / 2 - (scale * this.mBounds[0]);
    }

    private getVerticalOffset(baseline: boolean): number {
        // TODO scale TextSize / BaseTextSize
        const scale = 1.0;
        const boxHeight = 0;
        const textHeight = scale * (this.mBounds[3] - this.mBounds[1]);
        return (boxHeight - textHeight) * (1 - this.mOutPanY) / 2
            + (baseline ? textHeight / 2 : (-scale * this.mBounds[1]));
    }

    paint(context: PaintContext): void {
        const flags = ((this.mFlags & DrawTextAnchored.ANCHOR_MONOSPACE_MEASURE) !== 0)
            ? PaintContext.TEXT_MEASURE_MONOSPACE_WIDTH
            : 0;

        const str = context.getText(this.mTextID);
        // Only re-measure when text changes or MEASURE_EVERY_TIME flag is set
        if (str !== this.mLastString || (this.mFlags & DrawTextAnchored.MEASURE_EVERY_TIME) !== 0) {
            this.mLastString = str;
            context.getTextBounds(this.mTextID, 0, -1, flags, this.mBounds);
        }

        const baseline = (this.mFlags & DrawTextAnchored.BASELINE_RELATIVE) !== 0;
        const x = this.mOutX + this.getHorizontalOffset();
        const y = Number.isNaN(this.mOutPanY) ? this.mOutY : this.mOutY + this.getVerticalOffset(baseline);

        context.drawTextRun(this.mTextID, 0, -1, 0, 1, x, y,
            (this.mFlags & DrawTextAnchored.ANCHOR_TEXT_RTL) === 1);
    }

    deepToString(indent: string): string { return `${indent}DrawTextAnchored(${this.mTextID})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawTextAnchored(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
