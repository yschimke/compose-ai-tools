// DataOperations: data-loading operations (text, bitmap, path, paint, constants, etc.)

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { ContextMode } from '../RemoteContext';
import type { VariableSupport } from '../VariableSupport';
import { PaintBundle } from './paint/PaintBundle';
import { isNaNBits, idFromBits, intBitsToFloat, floatToRawIntBits } from './Utils';

// Path-command marker ids. A NaN path token in one of these ranges is a path
// COMMAND, not a variable reference. Two encodings exist in the wire:
//   • short ids 10..17 (MOVE=10, LINE=11, QUAD=12, CONIC=13, CUBIC=14,
//     CLOSE=15, DONE=16, RESET=17) — emitted by PathData/PathCreate/PathAppend,
//     and the canonical scheme the native (C++) player uses ("10-17 stay as-is").
//   • NanMap ids 0x300000..0x300006 (MOVE..DONE) — an alternate path encoding.
// Coordinate variable refs always use higher ids, so both ranges are safe to
// treat as markers. Missing the 10..17 range made markers resolve as variables
// (getFloat(10/11/15)) and corrupted every short-encoded path (e.g. cube3d).
const NANMAP_PATH_BASE = 0x300000;
function isPathMarkerBits(b: number): boolean {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return (id >= 10 && id <= 17) ||
        (id >= NANMAP_PATH_BASE && id <= NANMAP_PATH_BASE + 6);
}

export class TextData extends Operation {
    static readonly OP_CODE = 102;
    mTextId: number;
    mText: string;

    constructor(textId: number, text: string) {
        super(); this.mTextId = textId; this.mText = text;
    }

    update(other: TextData): void { this.mText = other.mText; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.loadText(this.mTextId, this.mText);
    }

    deepToString(indent: string): string { return `${indent}TextData(${this.mTextId}, "${this.mText}")`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.declareId();
        const text = buffer.readUTF8();
        operations.push(new TextData(id, text));
    }
}

export class BitmapData extends Operation {
    static readonly OP_CODE = 101;
    mImageId: number;
    private mEncoding: number;
    private mType: number;
    private mWidth: number;
    private mHeight: number;
    private mBitmap: Uint8Array;

    constructor(imageId: number, encoding: number, type: number, width: number, height: number, bitmap: Uint8Array) {
        super();
        this.mImageId = imageId; this.mEncoding = encoding; this.mType = type;
        this.mWidth = width; this.mHeight = height; this.mBitmap = bitmap;
    }

    getWidth(): number { return this.mWidth; }
    getHeight(): number { return this.mHeight; }

    update(other: BitmapData): void {
        this.mEncoding = other.mEncoding; this.mType = other.mType;
        this.mWidth = other.mWidth; this.mHeight = other.mHeight;
        this.mBitmap = other.mBitmap;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.putObject(this.mImageId, this);
        context.loadBitmap(this.mImageId, this.mEncoding, this.mType, this.mWidth, this.mHeight, this.mBitmap);
    }

    deepToString(indent: string): string { return `${indent}BitmapData(${this.mImageId}, ${this.mWidth}x${this.mHeight})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        let rawWidth = buffer.readInt();
        let rawHeight = buffer.readInt();
        let type = 0; // TYPE_PNG_8888
        let encoding = 0; // ENCODING_INLINE
        if (rawWidth > 0xFFFF) {
            type = rawWidth >> 16;
            rawWidth = rawWidth & 0xFFFF;
        }
        if (rawHeight > 0xFFFF) {
            encoding = rawHeight >> 16;
            rawHeight = rawHeight & 0xFFFF;
        }
        const bitmap = buffer.readBuffer();
        operations.push(new BitmapData(id, encoding, type, rawWidth, rawHeight, bitmap));
    }
}

export class PaintData extends Operation {
    static readonly OP_CODE = 40;
    mPaintBundle: PaintBundle;

    constructor(paintBundle: PaintBundle) {
        super(); this.mPaintBundle = paintBundle;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        this.mPaintBundle.registerListening(context, this);
    }

    updateVariables(context: RemoteContext): void {
        this.mPaintBundle.updateVariables(context);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.applyPaint(this.mPaintBundle);
    }

    deepToString(indent: string): string { return `${indent}PaintData`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const bundle = new PaintBundle();
        bundle.read(buffer);
        operations.push(new PaintData(bundle));
    }
}

export class PathData extends Operation implements VariableSupport {
    static readonly OP_CODE = 123;
    private mPathId: number;
    private mWinding: number;
    // Raw float32 int bits of each path token (command markers / variable refs
    // are NaN-with-payload). Bits survive NaN-payload canonicalizing engines.
    private mPathBits: Int32Array;
    // Resolved path tokens as raw float32 int bits: command markers kept as-is,
    // variable refs resolved to the bits of their current float, literals as-is.
    private mOutputPath: Int32Array;
    private mPathChanged = false;

    constructor(pathId: number, winding: number, pathBits: Int32Array) {
        super(); this.mPathId = pathId; this.mWinding = winding;
        this.mPathBits = pathBits;
        // Copy bits directly — markers/literals are already correct; variable
        // refs will be resolved in updateVariables before first paint.
        this.mOutputPath = Int32Array.from(pathBits);
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        // Listen only to variable references — NOT path-command markers (whose
        // ids fall in the NANMAP_PATH_BASE range and are not real variables).
        for (const b of this.mPathBits) {
            if (isNaNBits(b) && !isPathMarkerBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }

    updateVariables(context: RemoteContext): void {
        for (let i = 0; i < this.mPathBits.length; i++) {
            const b = this.mPathBits[i];
            if (isPathMarkerBits(b)) {
                this.mOutputPath[i] = b;
            } else if (isNaNBits(b)) {
                const prev = this.mOutputPath[i];
                this.mOutputPath[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
                if (prev !== this.mOutputPath[i]) this.mPathChanged = true;
            } else {
                this.mOutputPath[i] = b;
            }
        }
    }

    apply(context: RemoteContext): void {
        context.loadPathData(this.mPathId, this.mWinding, this.mOutputPath);
    }

    deepToString(indent: string): string { return `${indent}PathData(${this.mPathId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const rawId = buffer.readInt();
        const winding = rawId >> 24;
        const id = rawId & 0xFFFFFF;
        const count = buffer.readInt();
        if (count < 0 || count > 20000) {
            throw new Error(`PathData: invalid path length ${count}`);
        }
        const data = new Int32Array(count);
        for (let i = 0; i < count; i++) {
            data[i] = buffer.readInt();
        }
        operations.push(new PathData(id, winding, data));
    }
}

export class FloatConstant extends Operation {
    static readonly OP_CODE = 80;
    // RAND operator ID: OFFSET(0x310000) + 39 = 0x310027
    private static readonly RAND_ID = 0x310027;
    mId: number;
    mValue: number;

    constructor(id: number, valueBits: number) {
        super(); this.mId = id;
        // If the value encodes RAND, generate a random number; else decode the literal.
        this.mValue = (isNaNBits(valueBits) && idFromBits(valueBits) === FloatConstant.RAND_ID)
            ? Math.random() : intBitsToFloat(valueBits);
    }

    update(other: FloatConstant): void { this.mValue = other.mValue; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.loadFloat(this.mId, this.mValue);
    }

    deepToString(indent: string): string { return `${indent}FloatConstant(${this.mId}, ${this.mValue})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const value = buffer.readInt();
        operations.push(new FloatConstant(id, value));
    }
}

export class ColorConstant extends Operation {
    static readonly OP_CODE = 138;
    private mId: number;
    private mColor: number;

    constructor(id: number, color: number) {
        super(); this.mId = id; this.mColor = color;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.loadColor(this.mId, this.mColor);
    }

    deepToString(indent: string): string { return `${indent}ColorConstant(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ColorConstant(buffer.readInt(), buffer.readInt()));
    }
}

export class Theme extends Operation {
    static readonly OP_CODE = 63;
    static readonly UNSPECIFIED = -1;
    static readonly DARK = -2;
    static readonly LIGHT = -3;
    static readonly SYSTEM = 0;

    private mTheme: number;

    constructor(theme: number) { super(); this.mTheme = theme; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.setTheme(this.mTheme);
    }

    deepToString(indent: string): string { return `${indent}Theme(${this.mTheme})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new Theme(buffer.readInt()));
    }
}

export class ClickArea extends Operation implements VariableSupport {
    static readonly OP_CODE = 64;
    private mId: number; private mContentDescriptionId: number;
    // Bounds as raw float32 int bits (may be NaN-encoded variable refs).
    private mLeft: number; private mTop: number; private mRight: number; private mBottom: number;
    private mOutLeft: number; private mOutTop: number; private mOutRight: number; private mOutBottom: number;
    private mMetadataId: number;

    constructor(id: number, cdId: number, left: number, top: number, right: number, bottom: number, metaId: number) {
        super();
        this.mId = id; this.mContentDescriptionId = cdId;
        this.mLeft = left; this.mTop = top; this.mRight = right; this.mBottom = bottom;
        this.mOutLeft = isNaNBits(left) ? 0 : intBitsToFloat(left);
        this.mOutTop = isNaNBits(top) ? 0 : intBitsToFloat(top);
        this.mOutRight = isNaNBits(right) ? 0 : intBitsToFloat(right);
        this.mOutBottom = isNaNBits(bottom) ? 0 : intBitsToFloat(bottom);
        this.mMetadataId = metaId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mLeft)) context.listensTo(idFromBits(this.mLeft), this);
        if (isNaNBits(this.mTop)) context.listensTo(idFromBits(this.mTop), this);
        if (isNaNBits(this.mRight)) context.listensTo(idFromBits(this.mRight), this);
        if (isNaNBits(this.mBottom)) context.listensTo(idFromBits(this.mBottom), this);
    }

    updateVariables(context: RemoteContext): void {
        this.mOutLeft = isNaNBits(this.mLeft) ? context.getFloat(idFromBits(this.mLeft)) : intBitsToFloat(this.mLeft);
        this.mOutTop = isNaNBits(this.mTop) ? context.getFloat(idFromBits(this.mTop)) : intBitsToFloat(this.mTop);
        this.mOutRight = isNaNBits(this.mRight) ? context.getFloat(idFromBits(this.mRight)) : intBitsToFloat(this.mRight);
        this.mOutBottom = isNaNBits(this.mBottom) ? context.getFloat(idFromBits(this.mBottom)) : intBitsToFloat(this.mBottom);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.addClickArea(this.mId, this.mContentDescriptionId,
            this.mOutLeft, this.mOutTop, this.mOutRight, this.mOutBottom, this.mMetadataId);
    }

    deepToString(indent: string): string { return `${indent}ClickArea(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ClickArea(
            buffer.readInt(), buffer.readInt(),
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
            buffer.readInt()
        ));
    }
}

export class NamedVariable extends Operation {
    static readonly OP_CODE = 137;
    static readonly STRING_TYPE = 0;
    static readonly FLOAT_TYPE = 1;
    static readonly COLOR_TYPE = 2;
    static readonly IMAGE_TYPE = 3;
    static readonly INT_TYPE = 4;
    static readonly LONG_TYPE = 5;
    static readonly FLOAT_ARRAY_TYPE = 6;

    mVarName: string; mVarId: number; mVarType: number;

    constructor(varName: string, varId: number, varType: number) {
        super(); this.mVarName = varName; this.mVarId = varId; this.mVarType = varType;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.loadVariableName(this.mVarName, this.mVarId, this.mVarType);
    }

    deepToString(indent: string): string { return `${indent}NamedVariable("${this.mVarName}", ${this.mVarId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const type = buffer.readInt();
        const name = buffer.readUTF8();
        operations.push(new NamedVariable(name, id, type));
    }
}

export class RootContentDescription extends Operation {
    static readonly OP_CODE = 103;
    private mContentDescription: number;

    constructor(contentDescription: number) {
        super(); this.mContentDescription = contentDescription;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.setDocumentContentDescription(this.mContentDescription);
    }

    deepToString(indent: string): string { return `${indent}RootContentDescription(${this.mContentDescription})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RootContentDescription(buffer.readInt()));
    }
}

export class RootContentBehavior extends Operation {
    static readonly OP_CODE = 65;
    static readonly NONE = 0;
    static readonly SIZING_LAYOUT = 1;
    static readonly SIZING_SCALE = 2;
    static readonly ALIGNMENT_TOP = 1;
    static readonly ALIGNMENT_VERTICAL_CENTER = 2;
    static readonly ALIGNMENT_BOTTOM = 4;
    static readonly ALIGNMENT_START = 16;
    static readonly ALIGNMENT_HORIZONTAL_CENTER = 32;
    static readonly ALIGNMENT_END = 64;
    static readonly ALIGNMENT_CENTER = 34; // H_CENTER + V_CENTER
    static readonly SCALE_INSIDE = 1;
    static readonly SCALE_FIT = 2;
    static readonly SCALE_FILL_WIDTH = 3;
    static readonly SCALE_FILL_HEIGHT = 4;
    static readonly SCALE_CROP = 5;
    static readonly SCALE_FILL_BOUNDS = 6;
    static readonly LAYOUT_MATCH_PARENT = 0;
    static readonly LAYOUT_WRAP_CONTENT = 1;

    private mScroll: number; private mAlignment: number;
    private mSizing: number; private mMode: number;

    constructor(scroll: number, alignment: number, sizing: number, mode: number) {
        super();
        this.mScroll = scroll; this.mAlignment = alignment;
        this.mSizing = sizing; this.mMode = mode;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        context.setRootContentBehavior(this.mScroll, this.mAlignment, this.mSizing, this.mMode);
    }

    deepToString(indent: string): string { return `${indent}RootContentBehavior`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RootContentBehavior(
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()
        ));
    }
}
