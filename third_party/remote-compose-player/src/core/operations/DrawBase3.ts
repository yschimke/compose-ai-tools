// DrawBase3: abstract base for draw operations with 3 float parameters.
// Matches Java DrawBase3.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export abstract class DrawBase3 extends PaintOperation implements VariableSupport {
    // Wire values as raw float32 int bits (NaN-with-payload => variable ref).
    private mV1Bits: number;
    private mV2Bits: number;
    private mV3Bits: number;

    // Resolved output values (updated by updateVariables)
    mV1: number;
    mV2: number;
    mV3: number;

    constructor(v1Bits: number, v2Bits: number, v3Bits: number) {
        super();
        this.mV1Bits = v1Bits;
        this.mV2Bits = v2Bits;
        this.mV3Bits = v3Bits;
        this.mV1 = isNaNBits(v1Bits) ? 0 : intBitsToFloat(v1Bits);
        this.mV2 = isNaNBits(v2Bits) ? 0 : intBitsToFloat(v2Bits);
        this.mV3 = isNaNBits(v3Bits) ? 0 : intBitsToFloat(v3Bits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mV1Bits)) context.listensTo(idFromBits(this.mV1Bits), this);
        if (isNaNBits(this.mV2Bits)) context.listensTo(idFromBits(this.mV2Bits), this);
        if (isNaNBits(this.mV3Bits)) context.listensTo(idFromBits(this.mV3Bits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mV1 = isNaNBits(this.mV1Bits) ? context.getFloat(idFromBits(this.mV1Bits)) : intBitsToFloat(this.mV1Bits);
        this.mV2 = isNaNBits(this.mV2Bits) ? context.getFloat(idFromBits(this.mV2Bits)) : intBitsToFloat(this.mV2Bits);
        this.mV3 = isNaNBits(this.mV3Bits) ? context.getFloat(idFromBits(this.mV3Bits)) : intBitsToFloat(this.mV3Bits);
    }

    abstract paintBase3(context: PaintContext, v1: number, v2: number, v3: number): void;

    paint(context: PaintContext): void {
        this.paintBase3(context, this.mV1, this.mV2, this.mV3);
    }
}
