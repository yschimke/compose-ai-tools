// CoreText (239): Text component using CommandParameters-based binary format.
// Port of Java CoreText.java with full measure/layout/paint implementation.

import { LayoutManager } from './LayoutManager';
import { PaintBundle } from '../../paint/PaintBundle';
import { isNaNBits, idFromBits, intBitsToFloat, isVariableBits, floatToRawIntBits } from '../../Utils';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';
import type { VariableSupport } from '../../../VariableSupport';

// CommandParameters type constants (from CommandParameters.java)
const P_INT = 1;
const P_FLOAT = 2;
const P_SHORT = 3;
const P_BYTE = 4;
const P_BOOLEAN = 5;
const PA_INT = 6;
const PA_FLOAT = 7;
const PA_STRING = 8;

// Parameter IDs
const P_COMPONENT_ID = 1;
const P_ANIMATION_ID = 2;
const P_COLOR = 3;
const P_COLOR_ID = 4;
const P_FONT_SIZE = 5;
const P_FONT_STYLE = 6;
const P_FONT_WEIGHT = 7;
const P_FONT_FAMILY = 8;
const P_TEXT_ALIGN = 9;
const P_OVERFLOW = 10;
const P_MAX_LINES = 11;
const P_LETTER_SPACING = 12;
const P_LINE_HEIGHT_ADD = 13;
const P_LINE_HEIGHT_MULTIPLIER = 14;
const P_BREAK_STRATEGY = 15;
const P_HYPHENATION_FREQUENCY = 16;
const P_JUSTIFICATION_MODE = 17;
const P_UNDERLINE = 18;
const P_STRIKETHROUGH = 19;
const P_FONT_AXIS = 20;
const P_FONT_AXIS_VALUES = 21;
const P_AUTOSIZE = 22;
const P_FLAGS = 23;
const P_TEXT_STYLE_ID = 24;
const P_MIN_FONT_SIZE = 25;
const P_MAX_FONT_SIZE = 26;

// Parameter type lookup table: paramId -> type
const PARAM_TYPES: Record<number, number> = {
    [P_COMPONENT_ID]: P_INT,
    [P_ANIMATION_ID]: P_INT,
    [P_COLOR]: P_INT,
    [P_COLOR_ID]: P_INT,
    [P_FONT_SIZE]: P_FLOAT,
    [P_FONT_STYLE]: P_INT,
    [P_FONT_WEIGHT]: P_FLOAT,
    [P_FONT_FAMILY]: P_INT,
    [P_TEXT_ALIGN]: P_INT,
    [P_OVERFLOW]: P_INT,
    [P_MAX_LINES]: P_INT,
    [P_LETTER_SPACING]: P_FLOAT,
    [P_LINE_HEIGHT_ADD]: P_FLOAT,
    [P_LINE_HEIGHT_MULTIPLIER]: P_FLOAT,
    [P_BREAK_STRATEGY]: P_INT,
    [P_HYPHENATION_FREQUENCY]: P_INT,
    [P_JUSTIFICATION_MODE]: P_INT,
    [P_UNDERLINE]: P_BOOLEAN,
    [P_STRIKETHROUGH]: P_BOOLEAN,
    [P_FONT_AXIS]: PA_INT,
    [P_FONT_AXIS_VALUES]: PA_FLOAT,
    [P_AUTOSIZE]: P_BOOLEAN,
    [P_FLAGS]: P_INT,
    [P_TEXT_STYLE_ID]: P_INT,
    [P_MIN_FONT_SIZE]: P_FLOAT,
    [P_MAX_FONT_SIZE]: P_FLOAT,
};

// Alignment values
const TEXT_ALIGN_LEFT = 1;
const TEXT_ALIGN_RIGHT = 2;
const TEXT_ALIGN_CENTER = 3;
const TEXT_ALIGN_START = 5;
const TEXT_ALIGN_END = 6;

// Overflow behavior
const OVERFLOW_VISIBLE = 2;
const OVERFLOW_ELLIPSIS = 3;
const OVERFLOW_START_ELLIPSIS = 4;
const OVERFLOW_MIDDLE_ELLIPSIS = 5;

interface ReadCallback {
    intValue(id: number, value: number): void;
    floatValue(id: number, value: number): void;
    boolValue(id: number, value: boolean): void;
    intArrayValue(id: number, values: number[]): void;
    floatArrayValue(id: number, values: number[]): void;
}

function readCommandParams(buffer: WireBuffer, count: number, cb: ReadCallback): void {
    for (let i = 0; i < count; i++) {
        const paramId = buffer.readByte();
        const type = PARAM_TYPES[paramId];
        if (type === undefined) {
            throw new Error(`CoreText: unknown param id ${paramId}`);
        }
        switch (type) {
            case P_INT:
                cb.intValue(paramId, buffer.readInt());
                break;
            case P_FLOAT:
                // Read raw int32 bits; ref-capable params (font size/weight) keep
                // the bits, others decode to float in the callback.
                cb.floatValue(paramId, buffer.readInt());
                break;
            case P_SHORT:
                buffer.readShort();
                break;
            case P_BYTE:
                buffer.readByte();
                break;
            case P_BOOLEAN:
                cb.boolValue(paramId, buffer.readBoolean());
                break;
            case PA_INT: {
                const len = buffer.readShort();
                const arr: number[] = [];
                for (let j = 0; j < len; j++) arr.push(buffer.readInt());
                cb.intArrayValue(paramId, arr);
                break;
            }
            case PA_FLOAT: {
                // Read raw int32 bits so NaN-encoded variable refs survive.
                const len = buffer.readShort();
                const arr: number[] = [];
                for (let j = 0; j < len; j++) arr.push(buffer.readInt());
                cb.floatArrayValue(paramId, arr);
                break;
            }
            case PA_STRING:
                buffer.readUTF8();
                break;
        }
    }
}

export class CoreText extends LayoutManager implements VariableSupport {
    static readonly OP_CODE = 239;

    private mTextId: number;
    private mColor: number;
    private mColorId: number;
    private mColorValue: number;
    private mIsDynamicColorEnabled: boolean;
    private mFontSize: number;
    private mMinFontSize: number;
    private mMaxFontSize: number;
    private mFontSizeValue: number;
    private mMeasureFontSize: number;
    private mFontStyle: number;
    private mFontWeight: number;
    private mFontWeightValue: number;
    private mFontFamilyId: number;
    private mTextAlign: number;
    private mTextAlignValue: number;
    private mOverflow: number;
    private mMaxLines: number;
    private mLetterSpacing: number;
    private mLineHeightAdd: number;
    private mLineHeightMultiplier: number;
    private mLineBreakStrategy: number;
    private mHyphenationFrequency: number;
    private mJustificationMode: number;
    private mUnderline: boolean;
    private mStrikethrough: boolean;
    private mAutosize: boolean;
    private mFontAxis: number[] | null;
    private mFontAxisValues: number[] | null;
    private mFlags: number;

    private mType = -1;
    private mTextX = 0;
    private mTextY = 0;
    private mTextW = -1;
    private mTextH = -1;
    private mBaseline = 0;

    private mCachedString: string | null = null;
    private mNewString: string | null = null;
    private mComputedTextLayout: any = null;

    private mPaint = new PaintBundle();

    constructor(
        componentId: number, animationId: number, textId: number,
        color: number, colorId: number, fontSize: number,
        minFontSize: number, maxFontSize: number,
        fontStyle: number, fontWeight: number, fontFamilyId: number,
        textAlign: number, overflow: number, maxLines: number,
        letterSpacing: number, lineHeightAdd: number, lineHeightMultiplier: number,
        lineBreakStrategy: number, hyphenationFrequency: number, justificationMode: number,
        underline: boolean, strikethrough: boolean,
        fontAxis: number[] | null, fontAxisValues: number[] | null,
        autosize: boolean, flags: number
    ) {
        super(componentId, animationId);
        this.mTextId = textId;
        this.mColor = color;
        this.mColorId = colorId;
        this.mIsDynamicColorEnabled = colorId !== -1;
        this.mColorValue = this.mIsDynamicColorEnabled ? -1 : color;
        this.mFontSize = fontSize;
        this.mMinFontSize = minFontSize;
        this.mMaxFontSize = maxFontSize;
        this.mFontSizeValue = isNaNBits(fontSize) ? 16 : intBitsToFloat(fontSize);
        this.mMeasureFontSize = this.mFontSizeValue;
        this.mFontStyle = fontStyle;
        this.mFontWeight = fontWeight;
        this.mFontWeightValue = isNaNBits(fontWeight) ? 0 : intBitsToFloat(fontWeight);
        this.mFontFamilyId = fontFamilyId;
        this.mTextAlign = textAlign;
        this.mTextAlignValue = textAlign & 0xFFFF;
        this.mOverflow = overflow;
        this.mMaxLines = maxLines;
        this.mLetterSpacing = letterSpacing;
        this.mLineHeightAdd = lineHeightAdd;
        this.mLineHeightMultiplier = lineHeightMultiplier;
        this.mLineBreakStrategy = lineBreakStrategy;
        this.mHyphenationFrequency = hyphenationFrequency;
        this.mJustificationMode = justificationMode;
        this.mUnderline = underline;
        this.mStrikethrough = strikethrough;
        this.mFontAxis = fontAxis;
        this.mFontAxisValues = fontAxisValues;
        this.mAutosize = autosize;
        this.mFlags = flags;
    }

    registerListening(context: RemoteContext): void {
        if (this.mTextId !== -1) {
            context.listensTo(this.mTextId, this);
        }
        if (isNaNBits(this.mFontSize)) {
            context.listensTo(idFromBits(this.mFontSize), this);
        }
        if (isNaNBits(this.mFontWeight)) {
            context.listensTo(idFromBits(this.mFontWeight), this);
        }
        if (this.mIsDynamicColorEnabled) {
            context.listensTo(this.mColorId, this);
        }
    }

    updateVariables(context: RemoteContext): void {
        this.mFontSizeValue = isNaNBits(this.mFontSize)
            ? context.getFloat(idFromBits(this.mFontSize))
            : intBitsToFloat(this.mFontSize);
        this.mFontWeightValue = isNaNBits(this.mFontWeight)
            ? context.getFloat(idFromBits(this.mFontWeight))
            : intBitsToFloat(this.mFontWeight);
        this.mTextAlignValue = this.mTextAlign & 0xFFFF;
        this.mColorValue = this.mIsDynamicColorEnabled
            ? context.getColor(this.mColorId)
            : this.mColor;

        const cachedString = context.getText(this.mTextId);
        if (cachedString !== null && cachedString === this.mCachedString && this.mType !== -1) {
            if (this.mMeasureFontSize !== this.mFontSizeValue) {
                this.invalidateMeasure();
            }
            return;
        }
        this.mNewString = cachedString;
        if (this.mType === -1) {
            if (this.mFontFamilyId !== -1) {
                const fontFamily = context.getText(this.mFontFamilyId);
                if (fontFamily !== null) {
                    if (fontFamily.toLowerCase() === 'default') this.mType = 0;
                    else if (fontFamily.toLowerCase() === 'sans-serif') this.mType = 1;
                    else if (fontFamily.toLowerCase() === 'serif') this.mType = 2;
                    else if (fontFamily.toLowerCase() === 'monospace') this.mType = 3;
                    else this.mType = this.mFontFamilyId;
                }
            } else {
                this.mType = 0;
            }
        }
        this.invalidateMeasure();
    }

    computeWrapSize(context: PaintContext, _minWidth: number, maxWidth: number,
                    _minHeight: number, maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        this.mMeasureFontSize = this.mFontSizeValue;
        context.savePaint();
        this.mPaint.reset();
        this.mPaint.setTextSize(this.mMeasureFontSize);
        this.mPaint.setTextStyle(this.mType === -1 ? 0 : this.mType,
            Math.round(this.mFontWeightValue), this.mFontStyle === 1);
        if (this.mFontAxis && this.mFontAxis.length > 0) {
            // mFontAxisValues holds raw int bits; resolve vars, decode literals.
            const values = this.mFontAxisValues!.map(b =>
                isVariableBits(b) ? context.getContext().getFloat(idFromBits(b)) : intBitsToFloat(b));
            this.mPaint.setTextAxis(this.mFontAxis, values);
        }
        this.mPaint.setColor(this.mColorValue);
        context.replacePaint(this.mPaint);

        const bounds = new Float32Array(4);
        if (this.mNewString !== null && this.mNewString !== this.mCachedString) {
            this.mCachedString = this.mNewString;
        }
        if (this.mCachedString === null) {
            context.restorePaint();
            return;
        }

        this.textLayout(context, maxWidth, maxHeight, bounds);

        context.restorePaint();
        const w = bounds[2] - bounds[0];
        const h = bounds[3] - bounds[1];
        size.setWidth(Math.min(maxWidth, w));
        this.mTextX = -bounds[0];
        size.setHeight(Math.min(maxHeight, h));
        this.mTextY = -bounds[1];
        this.mTextW = w;
        this.mTextH = h;
    }

    computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                minHeight: number, maxHeight: number, measure: MeasurePass): void {
        super.computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        const tempSize = this.mCachedWrapSize;
        tempSize.clear();
        this.computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight,
            true, true, measure, tempSize);
        const m = measure.get(this);
        m.setW(tempSize.getWidth());
        m.setH(tempSize.getHeight());
    }

    private textLayout(context: PaintContext, maxWidth: number, maxHeight: number,
                       bounds: Float32Array): void {
        if (maxWidth < 0 || maxHeight < 0) return;

        let flags = PaintContext.TEXT_MEASURE_FONT_HEIGHT | PaintContext.TEXT_MEASURE_SPACES;
        let forceComplex = false;

        if (this.mOverflow === OVERFLOW_START_ELLIPSIS
            || this.mOverflow === OVERFLOW_MIDDLE_ELLIPSIS
            || this.mOverflow === OVERFLOW_ELLIPSIS) {
            flags |= PaintContext.TEXT_COMPLEX;
            forceComplex = true;
        }
        if (this.mLetterSpacing !== 0 || this.mLineHeightMultiplier !== 1
            || this.mLineHeightAdd > 0 || this.mUnderline || this.mStrikethrough
            || this.mJustificationMode > 0 || this.mLineBreakStrategy > 0
            || this.mHyphenationFrequency > 0) {
            flags |= PaintContext.TEXT_COMPLEX;
            forceComplex = true;
        }
        if (!(flags & PaintContext.TEXT_COMPLEX) && this.mCachedString) {
            for (let i = 0; i < this.mCachedString.length; i++) {
                const c = this.mCachedString.charAt(i);
                if (c === '\n' || c === '\t') {
                    flags |= PaintContext.TEXT_COMPLEX;
                    forceComplex = true;
                    break;
                }
            }
        }

        if (!forceComplex) {
            context.getTextBounds(this.mTextId, 0, this.mCachedString!.length, flags, bounds);
            this.mBaseline = -bounds[1];
        }

        if (forceComplex || (bounds[2] - bounds[0] > maxWidth && this.mMaxLines > 1 && maxWidth > 0)) {
            this.mComputedTextLayout = context.layoutComplexText(
                this.mTextId, 0, this.mCachedString!.length,
                this.mTextAlign, this.mOverflow, this.mMaxLines,
                maxWidth, maxHeight,
                this.mLetterSpacing, this.mLineHeightAdd, this.mLineHeightMultiplier,
                this.mLineBreakStrategy, this.mHyphenationFrequency, this.mJustificationMode,
                this.mUnderline, this.mStrikethrough, flags
            );
            if (this.mComputedTextLayout) {
                bounds[0] = 0;
                bounds[1] = 0;
                bounds[2] = this.mComputedTextLayout.width;
                bounds[3] = this.mComputedTextLayout.height;
            }
        } else {
            this.mComputedTextLayout = null;
        }
    }

    paintingComponent(paintContext: PaintContext): void {
        const remoteContext = paintContext.getContext();

        paintContext.save();
        paintContext.translate(this.mX, this.mY);

        // Apply padding
        const tx = this.mPaddingLeft;
        const ty = this.mPaddingTop;
        paintContext.translate(tx, ty);

        // Set up paint for text
        paintContext.savePaint();
        this.mPaint.reset();
        this.mPaint.setStyle(PaintBundle.FILL);
        this.mPaint.setColor(this.mColorValue);
        this.mPaint.setTextSize(this.mMeasureFontSize);
        this.mPaint.setTextStyle(this.mType === -1 ? 0 : this.mType,
            Math.round(this.mFontWeightValue), this.mFontStyle === 1);
        if (this.mFontAxis && this.mFontAxis.length > 0) {
            // mFontAxisValues holds raw int bits; resolve vars, decode literals.
            const values = this.mFontAxisValues!.map(b =>
                isVariableBits(b) ? remoteContext.getFloat(idFromBits(b)) : intBitsToFloat(b));
            this.mPaint.setTextAxis(this.mFontAxis, values);
        }
        paintContext.replacePaint(this.mPaint);

        if (this.mCachedString === null) {
            paintContext.restorePaint();
            paintContext.restore();
            return;
        }

        const length = this.mCachedString.length;
        if (this.mComputedTextLayout) {
            if (this.mOverflow !== OVERFLOW_VISIBLE) {
                paintContext.save();
                paintContext.clipRect(0, 0,
                    this.mWidth - this.mPaddingLeft - this.mPaddingRight,
                    this.mHeight - this.mPaddingTop - this.mPaddingBottom);
                paintContext.drawComplexText(this.mComputedTextLayout);
                paintContext.restore();
            } else {
                paintContext.drawComplexText(this.mComputedTextLayout);
            }
        } else {
            let px = this.mTextX;
            switch (this.mTextAlignValue) {
                case TEXT_ALIGN_CENTER:
                    px = (this.mWidth - this.mPaddingLeft - this.mPaddingRight - this.mTextW) / 2;
                    break;
                case TEXT_ALIGN_RIGHT:
                case TEXT_ALIGN_END:
                    px = this.mWidth - this.mPaddingLeft - this.mPaddingRight - this.mTextW;
                    break;
                case TEXT_ALIGN_LEFT:
                case TEXT_ALIGN_START:
                default:
                    break;
            }

            const contentW = this.mWidth - this.mPaddingLeft - this.mPaddingRight;
            if (this.mOverflow !== OVERFLOW_VISIBLE || this.mTextW > contentW) {
                paintContext.save();
                paintContext.clipRect(0, 0, contentW,
                    this.mHeight - this.mPaddingTop - this.mPaddingBottom);
                paintContext.drawTextRun(this.mTextId, 0, length, 0, 0, px, this.mTextY, false);
                paintContext.restore();
            } else {
                paintContext.drawTextRun(this.mTextId, 0, length, 0, 0, px, this.mTextY, false);
            }
        }

        paintContext.restorePaint();
        paintContext.restore();
    }

    write(buffer: WireBuffer): void {
        buffer.start(CoreText.OP_CODE);
        buffer.writeInt(this.mTextId);
        // Write minimal params - stub for now
        buffer.writeShort(0);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}CoreText(${this.getComponentId()}, textId=${this.mTextId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const textId = buffer.readId();  // Java CoreText.read: readId (remapped in macros)
        const paramsLength = buffer.readShort();

        // Default values matching Java
        let componentId = -1, animationId = -1, color = 0xFF000000 | 0, colorId = -1;
        let fontStyle = 0, fontFamilyId = -1, textAlign = 1, overflow = 1;
        let maxLines = 0x7FFFFFFF, lineBreakStrategy = 0, hyphenationFrequency = 0;
        let justificationMode = 0, flags = 0;
        // fontSize / fontWeight are kept as raw float32 int bits (default 16.0 / 400.0).
        let fontSize = floatToRawIntBits(16), minFontSize = -1, maxFontSize = -1;
        let fontWeight = floatToRawIntBits(400), letterSpacing = 0, lineHeightAdd = 0, lineHeightMultiplier = 1;
        let underline = false, strikethrough = false, autosize = false;
        let fontAxis: number[] | null = null;
        let fontAxisValues: number[] | null = null;

        readCommandParams(buffer, paramsLength, {
            intValue(id: number, value: number) {
                switch (id) {
                    case P_COMPONENT_ID: componentId = value; break;
                    case P_ANIMATION_ID: animationId = value; break;
                    case P_COLOR: color = value; break;
                    case P_COLOR_ID: colorId = value; break;
                    case P_FONT_STYLE: fontStyle = value; break;
                    case P_FONT_FAMILY: fontFamilyId = value; break;
                    case P_TEXT_ALIGN: textAlign = value; break;
                    case P_OVERFLOW: overflow = value; break;
                    case P_MAX_LINES: maxLines = value; break;
                    case P_BREAK_STRATEGY: lineBreakStrategy = value; break;
                    case P_HYPHENATION_FREQUENCY: hyphenationFrequency = value; break;
                    case P_JUSTIFICATION_MODE: justificationMode = value; break;
                    case P_FLAGS: flags = value; break;
                    case P_TEXT_STYLE_ID: break; // TODO
                }
            },
            floatValue(id: number, bits: number) {
                switch (id) {
                    // Ref-capable: keep raw bits (resolved later).
                    case P_FONT_SIZE: fontSize = bits; break;
                    case P_FONT_WEIGHT: fontWeight = bits; break;
                    // Non-ref: decode literal float now.
                    case P_MIN_FONT_SIZE: minFontSize = intBitsToFloat(bits); break;
                    case P_MAX_FONT_SIZE: maxFontSize = intBitsToFloat(bits); break;
                    case P_LETTER_SPACING: letterSpacing = intBitsToFloat(bits); break;
                    case P_LINE_HEIGHT_ADD: lineHeightAdd = intBitsToFloat(bits); break;
                    case P_LINE_HEIGHT_MULTIPLIER: lineHeightMultiplier = intBitsToFloat(bits); break;
                }
            },
            boolValue(id: number, value: boolean) {
                switch (id) {
                    case P_UNDERLINE: underline = value; break;
                    case P_STRIKETHROUGH: strikethrough = value; break;
                    case P_AUTOSIZE: autosize = value; break;
                }
            },
            intArrayValue(id: number, values: number[]) {
                if (id === P_FONT_AXIS) fontAxis = values;
            },
            floatArrayValue(id: number, values: number[]) {
                if (id === P_FONT_AXIS_VALUES) fontAxisValues = values;
            }
        });

        operations.push(new CoreText(
            componentId, animationId, textId,
            color, colorId, fontSize, minFontSize, maxFontSize,
            fontStyle, fontWeight, fontFamilyId,
            textAlign, overflow, maxLines,
            letterSpacing, lineHeightAdd, lineHeightMultiplier,
            lineBreakStrategy, hyphenationFrequency, justificationMode,
            underline, strikethrough, fontAxis, fontAxisValues,
            autosize, flags
        ));
    }
}
