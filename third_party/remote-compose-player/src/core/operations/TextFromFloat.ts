// TextFromFloat: convert a float value to text with formatting.
// Port of Java TextFromFloat.java — extends Operation, implements VariableSupport.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';
import { floatToStringLegacy, floatToStringFull } from './utilities/StringUtils';

// Flag constants (matching Java TextFromFloat)
const PAD_AFTER_SPACE = 0;
const PAD_AFTER_NONE = 1;
const PAD_AFTER_ZERO = 3;
const PAD_PRE_SPACE = 0;
const PAD_PRE_NONE = 4;
const PAD_PRE_ZERO = 12;
const GROUPING_NONE = 0;
const GROUPING_BY3 = 1 << 4;
const GROUPING_BY4 = 2 << 4;
const GROUPING_BY32 = 3 << 4;
const SEPARATOR_COMMA_PERIOD = 0;
const SEPARATOR_PERIOD_COMMA = 1 << 6;
const SEPARATOR_SPACE_COMMA = 2 << 6;
const SEPARATOR_UNDER_PERIOD = 3 << 6;
const OPTIONS_NEGATIVE_PARENTHESES = 1 << 8;
const OPTIONS_ROUNDING = 2 << 8;
const LEGACY_MODE = 1 << 10;

export class TextFromFloat extends Operation implements VariableSupport {
    static readonly OP_CODE = 135;

    private mTextId: number;
    // Raw float32 int bits of the value (NaN-with-payload => variable ref).
    private mValueBits: number;
    private mOutValue: number;
    private mDigitsBefore: number;
    private mDigitsAfter: number;
    private mFlags: number;
    private mLegacy: boolean;
    private mPre: number;    // char code, 0 = no pad
    private mAfter: number;  // char code, 0 = no pad
    private mGroup: number;
    private mSeparator: number;
    private mOptions: number;

    constructor(textId: number, valueBits: number, digitsBefore: number, digitsAfter: number, flags: number) {
        super();
        this.mTextId = textId;
        this.mValueBits = valueBits;
        this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
        this.mDigitsBefore = digitsBefore;
        this.mDigitsAfter = digitsAfter;
        this.mFlags = flags;

        // Decode pad-after (bits 0-1)
        switch (flags & 3) {
            case PAD_AFTER_NONE:  this.mAfter = 0; break;
            case PAD_AFTER_ZERO:  this.mAfter = 0x30; break; // '0'
            default:              this.mAfter = 0x20; break;  // ' ' (PAD_AFTER_SPACE)
        }

        // Decode pad-before (bits 2-3)
        switch (flags & (3 << 2)) {
            case PAD_PRE_NONE:  this.mPre = 0; break;
            case PAD_PRE_ZERO:  this.mPre = 0x30; break; // '0'
            default:            this.mPre = 0x20; break;  // ' ' (PAD_PRE_SPACE)
        }

        // Decode grouping (bits 4-5)
        this.mGroup = 0;
        switch (flags & (3 << 4)) {
            case GROUPING_BY3:  this.mGroup = 1; break;
            case GROUPING_BY4:  this.mGroup = 2; break;
            case GROUPING_BY32: this.mGroup = 3; break;
        }

        // Decode separator (bits 6-7)
        this.mSeparator = 0;
        switch (flags & (3 << 6)) {
            case SEPARATOR_PERIOD_COMMA: this.mSeparator = 1; break;
            case SEPARATOR_SPACE_COMMA:  this.mSeparator = 2; break;
            case SEPARATOR_UNDER_PERIOD: this.mSeparator = 3; break;
            // default SEPARATOR_COMMA_PERIOD = 0
        }

        // Decode options
        this.mOptions = 0;
        if ((flags & OPTIONS_ROUNDING) !== 0) this.mOptions |= 2;
        if ((flags & OPTIONS_NEGATIVE_PARENTHESES) !== 0) this.mOptions |= 1;

        // Legacy mode
        this.mLegacy = (flags & LEGACY_MODE) !== 0;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mValueBits)) context.listensTo(idFromBits(this.mValueBits), this);
    }

    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mValueBits)) {
            this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
        }
    }

    apply(context: RemoteContext): void {
        const v = this.mOutValue;
        let s: string;
        if (this.mLegacy) {
            s = floatToStringLegacy(v, this.mDigitsBefore, this.mDigitsAfter, this.mPre, this.mAfter);
        } else {
            s = floatToStringFull(
                v, this.mDigitsBefore, this.mDigitsAfter,
                this.mPre, this.mAfter,
                this.mSeparator, this.mGroup, this.mOptions
            );
        }
        context.loadText(this.mTextId, s);
    }

    deepToString(indent: string): string {
        return `${indent}TextFromFloat[${this.mTextId}] = ${isNaNBits(this.mValueBits) ? 'ref(' + idFromBits(this.mValueBits) + ')' : intBitsToFloat(this.mValueBits)} ${this.mDigitsBefore}.${this.mDigitsAfter} ${this.mFlags}`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readInt();
        const valueBits = buffer.readInt();  // raw bits; NaN-with-payload => variable ref
        const tmp = buffer.readInt();
        const digitsAfter = tmp & 0xFFFF;
        const digitsBefore = (tmp >> 16) & 0xFFFF;
        const flags = buffer.readInt();
        operations.push(new TextFromFloat(textId, valueBits, digitsBefore, digitsAfter, flags));
    }
}
