// ColorExpression: color interpolation and computation expression.
// Matches Java ColorExpression.java — extends Operation, implements VariableSupport.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat, floatToRawIntBits, hsvToRgb, toARGB, interpolateColor } from './Utils';

export class ColorExpression extends Operation implements VariableSupport {
    static readonly OP_CODE = 134;

    static readonly COLOR_COLOR_INTERPOLATE = 0;
    static readonly ID_COLOR_INTERPOLATE = 1;
    static readonly COLOR_ID_INTERPOLATE = 2;
    static readonly ID_ID_INTERPOLATE = 3;
    static readonly HSV_MODE = 4;
    static readonly ARGB_MODE = 5;
    static readonly IDARGB_MODE = 6;

    mId: number;
    private mMode: number;
    private mAlpha: number;

    // Interpolation mode fields. Color ids are plain integers (used directly
    // with getColor); the float-ref fields are stored as raw float32 int bits
    // so NaN-encoded variable ids survive engines that canonicalize payloads.
    private mColor1: number;
    private mColor2: number;
    private mTweenBits: number;
    private mOutColor1: number;
    private mOutColor2: number;
    private mOutTween: number;

    // HSV mode fields (raw bits)
    private mHueBits: number;
    private mSatBits: number;
    private mValueBits: number;
    private mOutHue: number;
    private mOutSat: number;
    private mOutValue: number;

    // ARGB mode fields (raw bits)
    private mArgbAlphaBits: number;
    private mArgbRedBits: number;
    private mArgbGreenBits: number;
    private mArgbBlueBits: number;
    private mOutArgbAlpha: number;
    private mOutArgbRed: number;
    private mOutArgbGreen: number;
    private mOutArgbBlue: number;

    constructor(id: number, param1: number, param2: number, param3: number, param4: number) {
        super();
        this.mId = id;
        this.mMode = param1 & 0xFF;
        this.mAlpha = (param1 >> 16) & 0xFF;
        const lit = (b: number) => isNaNBits(b) ? 0 : intBitsToFloat(b);

        // Interpolation defaults
        this.mColor1 = param2;
        this.mColor2 = param3;
        this.mTweenBits = param4;
        this.mOutColor1 = param2;
        this.mOutColor2 = param3;
        this.mOutTween = lit(param4);

        // HSV defaults
        this.mHueBits = 0; this.mSatBits = 0; this.mValueBits = 0;
        this.mOutHue = 0; this.mOutSat = 0; this.mOutValue = 0;

        // ARGB defaults
        this.mArgbAlphaBits = 0; this.mArgbRedBits = 0; this.mArgbGreenBits = 0; this.mArgbBlueBits = 0;
        this.mOutArgbAlpha = 0; this.mOutArgbRed = 0; this.mOutArgbGreen = 0; this.mOutArgbBlue = 0;

        if (this.mMode === ColorExpression.HSV_MODE) {
            this.mHueBits = param2; this.mOutHue = lit(param2);
            this.mSatBits = param3; this.mOutSat = lit(param3);
            this.mValueBits = param4; this.mOutValue = lit(param4);
        } else if (this.mMode === ColorExpression.ARGB_MODE) {
            // Alpha is a literal float here; store its bits for a uniform code path.
            this.mArgbAlphaBits = floatToRawIntBits((param1 >> 16) / 1024.0);
            this.mOutArgbAlpha = (param1 >> 16) / 1024.0;
            this.mArgbRedBits = param2; this.mOutArgbRed = lit(param2);
            this.mArgbGreenBits = param3; this.mOutArgbGreen = lit(param3);
            this.mArgbBlueBits = param4; this.mOutArgbBlue = lit(param4);
        } else if (this.mMode === ColorExpression.IDARGB_MODE) {
            this.mArgbAlphaBits = ((param1 >> 16) | 0xFF800000) | 0; // NaN-encoded ID bits
            this.mOutArgbAlpha = lit(this.mArgbAlphaBits);
            this.mArgbRedBits = param2; this.mOutArgbRed = lit(param2);
            this.mArgbGreenBits = param3; this.mOutArgbGreen = lit(param3);
            this.mArgbBlueBits = param4; this.mOutArgbBlue = lit(param4);
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mTweenBits)) context.listensTo(idFromBits(this.mTweenBits), this);
        if (this.mMode === ColorExpression.HSV_MODE) {
            if (isNaNBits(this.mHueBits)) context.listensTo(idFromBits(this.mHueBits), this);
            if (isNaNBits(this.mSatBits)) context.listensTo(idFromBits(this.mSatBits), this);
            if (isNaNBits(this.mValueBits)) context.listensTo(idFromBits(this.mValueBits), this);
        }
        if (this.mMode === ColorExpression.ARGB_MODE || this.mMode === ColorExpression.IDARGB_MODE) {
            if (isNaNBits(this.mArgbAlphaBits)) context.listensTo(idFromBits(this.mArgbAlphaBits), this);
            if (isNaNBits(this.mArgbRedBits)) context.listensTo(idFromBits(this.mArgbRedBits), this);
            if (isNaNBits(this.mArgbGreenBits)) context.listensTo(idFromBits(this.mArgbGreenBits), this);
            if (isNaNBits(this.mArgbBlueBits)) context.listensTo(idFromBits(this.mArgbBlueBits), this);
        }
    }


    apply(context: RemoteContext): void {
        // Resolve NaN-encoded variable references
        this.updateVariables(context);

        if (this.mMode === ColorExpression.HSV_MODE) {
            context.loadColor(this.mId,
                ((this.mAlpha << 24) | (0xFFFFFF & hsvToRgb(this.mOutHue, this.mOutSat, this.mOutValue))) | 0);
            return;
        }
        if (this.mMode === ColorExpression.ARGB_MODE || this.mMode === ColorExpression.IDARGB_MODE) {
            context.loadColor(this.mId,
                toARGB(this.mOutArgbAlpha, this.mOutArgbRed, this.mOutArgbGreen, this.mOutArgbBlue));
            return;
        }
        // Interpolation modes (0-3)
        if (this.mOutTween === 0.0) {
            if ((this.mMode & 1) === 1) {
                this.mOutColor1 = context.getColor(this.mColor1);
            }
            context.loadColor(this.mId, this.mOutColor1);
        } else {
            if ((this.mMode & 1) === 1) {
                this.mOutColor1 = context.getColor(this.mColor1);
            }
            if ((this.mMode & 2) === 2) {
                this.mOutColor2 = context.getColor(this.mColor2);
            }
            context.loadColor(this.mId, interpolateColor(this.mOutColor1, this.mOutColor2, this.mOutTween));
        }
    }

    updateVariables(context: RemoteContext): void {
        if (this.mMode === ColorExpression.HSV_MODE) {
            if (isNaNBits(this.mHueBits)) {
                this.mOutHue = context.getFloat(idFromBits(this.mHueBits));
            }
            if (isNaNBits(this.mSatBits)) {
                this.mOutSat = context.getFloat(idFromBits(this.mSatBits));
            }
            if (isNaNBits(this.mValueBits)) {
                this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
            }
        }
        if (this.mMode === ColorExpression.ARGB_MODE || this.mMode === ColorExpression.IDARGB_MODE) {
            if (isNaNBits(this.mArgbAlphaBits)) {
                this.mOutArgbAlpha = context.getFloat(idFromBits(this.mArgbAlphaBits));
            }
            if (isNaNBits(this.mArgbRedBits)) {
                this.mOutArgbRed = context.getFloat(idFromBits(this.mArgbRedBits));
            }
            if (isNaNBits(this.mArgbGreenBits)) {
                this.mOutArgbGreen = context.getFloat(idFromBits(this.mArgbGreenBits));
            }
            if (isNaNBits(this.mArgbBlueBits)) {
                this.mOutArgbBlue = context.getFloat(idFromBits(this.mArgbBlueBits));
            }
        }
        if (isNaNBits(this.mTweenBits)) {
            this.mOutTween = context.getFloat(idFromBits(this.mTweenBits));
        }
        if ((this.mMode & 1) === 1) {
            this.mOutColor1 = context.getColor(this.mColor1);
        }
        if ((this.mMode & 2) === 2) {
            this.mOutColor2 = context.getColor(this.mColor2);
        }
    }

    deepToString(indent: string): string { return `${indent}ColorExpression(${this.mId}, mode=${this.mMode})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ColorExpression(
            buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt()
        ));
    }
}
