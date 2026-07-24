// DrawBase4: abstract base for draw operations with 4 float parameters.
// Matches Java DrawBase4.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export abstract class DrawBase4 extends PaintOperation implements VariableSupport {
    // Wire values as raw float32 int bits. A coordinate is either a NaN-encoded
    // variable reference or a literal float; keeping the bits lets the encoded
    // ids survive engines that canonicalize NaN payloads (Safari/Firefox).
    private mX1Bits: number;
    private mY1Bits: number;
    private mX2Bits: number;
    private mY2Bits: number;

    // Resolved output values (updated by updateVariables)
    mX1: number;
    mY1: number;
    mX2: number;
    mY2: number;

    // Constructed from the raw int32 bits of each coordinate (see read()).
    constructor(x1Bits: number, y1Bits: number, x2Bits: number, y2Bits: number) {
        super();
        this.mX1Bits = x1Bits;
        this.mY1Bits = y1Bits;
        this.mX2Bits = x2Bits;
        this.mY2Bits = y2Bits;
        this.mX1 = isNaNBits(x1Bits) ? 0 : intBitsToFloat(x1Bits);
        this.mY1 = isNaNBits(y1Bits) ? 0 : intBitsToFloat(y1Bits);
        this.mX2 = isNaNBits(x2Bits) ? 0 : intBitsToFloat(x2Bits);
        this.mY2 = isNaNBits(y2Bits) ? 0 : intBitsToFloat(y2Bits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mX1Bits)) context.listensTo(idFromBits(this.mX1Bits), this);
        if (isNaNBits(this.mY1Bits)) context.listensTo(idFromBits(this.mY1Bits), this);
        if (isNaNBits(this.mX2Bits)) context.listensTo(idFromBits(this.mX2Bits), this);
        if (isNaNBits(this.mY2Bits)) context.listensTo(idFromBits(this.mY2Bits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mX1 = isNaNBits(this.mX1Bits) ? context.getFloat(idFromBits(this.mX1Bits)) : intBitsToFloat(this.mX1Bits);
        this.mY1 = isNaNBits(this.mY1Bits) ? context.getFloat(idFromBits(this.mY1Bits)) : intBitsToFloat(this.mY1Bits);
        this.mX2 = isNaNBits(this.mX2Bits) ? context.getFloat(idFromBits(this.mX2Bits)) : intBitsToFloat(this.mX2Bits);
        this.mY2 = isNaNBits(this.mY2Bits) ? context.getFloat(idFromBits(this.mY2Bits)) : intBitsToFloat(this.mY2Bits);
    }

    abstract paintBase4(context: PaintContext, x1: number, y1: number, x2: number, y2: number): void;

    paint(context: PaintContext): void {
        this.paintBase4(context, this.mX1, this.mY1, this.mX2, this.mY2);
    }
}
