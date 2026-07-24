// TextMeasure: measure text dimensions and store result as a float.
// Matches Java TextMeasure.java.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

const MEASURE_WIDTH = 0;
const MEASURE_HEIGHT = 1;
const MEASURE_LEFT = 2;
const MEASURE_RIGHT = 3;
const MEASURE_TOP = 4;
const MEASURE_BOTTOM = 5;

export class TextMeasure extends PaintOperation {
    static readonly OP_CODE = 155;
    private mId: number;
    private mTextId: number;
    private mType: number;
    private mBounds = new Float32Array(4);

    constructor(id: number, textId: number, type: number) {
        super();
        this.mId = id;
        this.mTextId = textId;
        this.mType = type;
    }

    write(_buffer: WireBuffer): void { /* not needed */ }

    paint(context: PaintContext): void {
        const val = this.mType & 0xFF;
        const flags = this.mType >> 8;
        context.getTextBounds(this.mTextId, 0, -1, flags, this.mBounds);

        let result = 0;
        switch (val) {
            case MEASURE_WIDTH:
                result = this.mBounds[2] - this.mBounds[0];
                break;
            case MEASURE_HEIGHT:
                result = this.mBounds[3] - this.mBounds[1];
                break;
            case MEASURE_LEFT:
                result = this.mBounds[0];
                break;
            case MEASURE_RIGHT:
                result = this.mBounds[2];
                break;
            case MEASURE_TOP:
                result = this.mBounds[1];
                break;
            case MEASURE_BOTTOM:
                result = this.mBounds[3];
                break;
        }
        context.getContext().loadFloat(this.mId, result);
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const textId = buffer.readInt();
        const type = buffer.readInt();
        operations.push(new TextMeasure(id, textId, type));
    }
}
