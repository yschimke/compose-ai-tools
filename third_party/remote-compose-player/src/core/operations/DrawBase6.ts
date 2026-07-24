// DrawBase6: abstract base for draw operations with 6 float parameters.
// Matches Java DrawBase6.java — extends PaintOperation, implements VariableSupport.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

export abstract class DrawBase6 extends PaintOperation implements VariableSupport {
    // Wire values as raw float32 int bits (NaN-with-payload => variable ref).
    private mV1Bits: number;
    private mV2Bits: number;
    private mV3Bits: number;
    private mV4Bits: number;
    private mV5Bits: number;
    private mV6Bits: number;

    // Resolved output values (updated by updateVariables)
    mV1: number;
    mV2: number;
    mV3: number;
    mV4: number;
    mV5: number;
    mV6: number;

    constructor(v1Bits: number, v2Bits: number, v3Bits: number, v4Bits: number, v5Bits: number, v6Bits: number) {
        super();
        this.mV1Bits = v1Bits;
        this.mV2Bits = v2Bits;
        this.mV3Bits = v3Bits;
        this.mV4Bits = v4Bits;
        this.mV5Bits = v5Bits;
        this.mV6Bits = v6Bits;
        this.mV1 = isNaNBits(v1Bits) ? 0 : intBitsToFloat(v1Bits);
        this.mV2 = isNaNBits(v2Bits) ? 0 : intBitsToFloat(v2Bits);
        this.mV3 = isNaNBits(v3Bits) ? 0 : intBitsToFloat(v3Bits);
        this.mV4 = isNaNBits(v4Bits) ? 0 : intBitsToFloat(v4Bits);
        this.mV5 = isNaNBits(v5Bits) ? 0 : intBitsToFloat(v5Bits);
        this.mV6 = isNaNBits(v6Bits) ? 0 : intBitsToFloat(v6Bits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mV1Bits)) context.listensTo(idFromBits(this.mV1Bits), this);
        if (isNaNBits(this.mV2Bits)) context.listensTo(idFromBits(this.mV2Bits), this);
        if (isNaNBits(this.mV3Bits)) context.listensTo(idFromBits(this.mV3Bits), this);
        if (isNaNBits(this.mV4Bits)) context.listensTo(idFromBits(this.mV4Bits), this);
        if (isNaNBits(this.mV5Bits)) context.listensTo(idFromBits(this.mV5Bits), this);
        if (isNaNBits(this.mV6Bits)) context.listensTo(idFromBits(this.mV6Bits), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mV1 = isNaNBits(this.mV1Bits) ? context.getFloat(idFromBits(this.mV1Bits)) : intBitsToFloat(this.mV1Bits);
        this.mV2 = isNaNBits(this.mV2Bits) ? context.getFloat(idFromBits(this.mV2Bits)) : intBitsToFloat(this.mV2Bits);
        this.mV3 = isNaNBits(this.mV3Bits) ? context.getFloat(idFromBits(this.mV3Bits)) : intBitsToFloat(this.mV3Bits);
        this.mV4 = isNaNBits(this.mV4Bits) ? context.getFloat(idFromBits(this.mV4Bits)) : intBitsToFloat(this.mV4Bits);
        this.mV5 = isNaNBits(this.mV5Bits) ? context.getFloat(idFromBits(this.mV5Bits)) : intBitsToFloat(this.mV5Bits);
        this.mV6 = isNaNBits(this.mV6Bits) ? context.getFloat(idFromBits(this.mV6Bits)) : intBitsToFloat(this.mV6Bits);
    }

    abstract paintBase6(context: PaintContext, v1: number, v2: number, v3: number, v4: number, v5: number, v6: number): void;

    paint(context: PaintContext): void {
        this.paintBase6(context, this.mV1, this.mV2, this.mV3, this.mV4, this.mV5, this.mV6);
    }
}
