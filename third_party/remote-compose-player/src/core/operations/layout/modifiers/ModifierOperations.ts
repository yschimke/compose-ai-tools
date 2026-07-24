// ModifierOperations: layout modifier operations for the layout system.

import { Operation } from '../../../Operation';
import type { VariableSupport } from '../../../VariableSupport';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import { ContextMode } from '../../../RemoteContext';
import { PaintBundle } from '../../paint/PaintBundle';
import { isNaNBits, idFromBits, intBitsToFloat, isVariableBits } from '../../Utils';
import { Visibility } from '../Component';

// ── MODIFIER_WIDTH (16): INT type, FLOAT value ───────────────────────
export class WidthModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 16;
    static readonly EXACT = 0;
    static readonly FILL = 1;
    static readonly WRAP = 2;
    static readonly WEIGHT = 3;
    static readonly INTRINSIC_MIN = 4;
    static readonly INTRINSIC_MAX = 5;
    static readonly EXACT_DP = 6;
    static readonly FILL_PARENT_MAX_WIDTH = 7;
    static readonly FILL_PARENT_MAX_HEIGHT = 8;
    private mType: number;
    private mValueBits: number;
    private mOutValue: number;
    constructor(type: number, valueBits: number) {
        super(); this.mType = type; this.mValueBits = valueBits;
        this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getType(): number { return this.mType; }
    getValue(): number { return this.mOutValue; }
    registerListening(context: RemoteContext): void {
        if ((this.mType === WidthModifier.EXACT || this.mType === WidthModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
            context.listensTo(idFromBits(this.mValueBits), this);
        }
    }
    updateVariables(context: RemoteContext): void {
        if ((this.mType === WidthModifier.EXACT || this.mType === WidthModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
            this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
            if (this.mType === WidthModifier.EXACT_DP) {
                this.mOutValue *= context.getDensity();
            }
        }
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}WidthModifier(${this.mType}, ${this.mOutValue})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new WidthModifier(buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_HEIGHT (67): INT type, FLOAT value ──────────────────────
export class HeightModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 67;
    static readonly EXACT = 0;
    static readonly FILL = 1;
    static readonly WRAP = 2;
    static readonly WEIGHT = 3;
    static readonly INTRINSIC_MIN = 4;
    static readonly INTRINSIC_MAX = 5;
    static readonly EXACT_DP = 6;
    static readonly FILL_PARENT_MAX_WIDTH = 7;
    static readonly FILL_PARENT_MAX_HEIGHT = 8;
    private mType: number;
    private mValueBits: number;
    private mOutValue: number;
    constructor(type: number, valueBits: number) {
        super(); this.mType = type; this.mValueBits = valueBits;
        this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getType(): number { return this.mType; }
    getValue(): number { return this.mOutValue; }
    registerListening(context: RemoteContext): void {
        if ((this.mType === HeightModifier.EXACT || this.mType === HeightModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
            context.listensTo(idFromBits(this.mValueBits), this);
        }
    }
    updateVariables(context: RemoteContext): void {
        if ((this.mType === HeightModifier.EXACT || this.mType === HeightModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
            this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
            if (this.mType === HeightModifier.EXACT_DP) {
                this.mOutValue *= context.getDensity();
            }
        }
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}HeightModifier(${this.mType}, ${this.mOutValue})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new HeightModifier(buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_WIDTH_IN (231): FLOAT min, FLOAT max ────────────────────
export class WidthInModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 231;
    private mMinBits: number; private mMaxBits: number;
    private mOutMin: number; private mOutMax: number;
    constructor(minBits: number, maxBits: number) {
        super();
        this.mMinBits = minBits; this.mMaxBits = maxBits;
        this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
        this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
    }
    getMin(): number { return this.mOutMin; }
    getMax(): number { return this.mOutMax; }
    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
        if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
    }
    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mMinBits)) this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
        if (isNaNBits(this.mMaxBits)) this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}WidthInModifier(${this.mOutMin}, ${this.mOutMax})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new WidthInModifier(buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_HEIGHT_IN (232): FLOAT min, FLOAT max ───────────────────
export class HeightInModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 232;
    private mMinBits: number; private mMaxBits: number;
    private mOutMin: number; private mOutMax: number;
    constructor(minBits: number, maxBits: number) {
        super();
        this.mMinBits = minBits; this.mMaxBits = maxBits;
        this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
        this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
    }
    getMin(): number { return this.mOutMin; }
    getMax(): number { return this.mOutMax; }
    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
        if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
    }
    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mMinBits)) this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
        if (isNaNBits(this.mMaxBits)) this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}HeightInModifier(${this.mOutMin}, ${this.mOutMax})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new HeightInModifier(buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_COLLAPSIBLE_PRIORITY (235): INT orientation, FLOAT priority
export class CollapsiblePriorityModifier extends Operation {
    static readonly OP_CODE = 235;
    private mOrientation: number; private mPriority: number;
    constructor(orientation: number, priority: number) { super(); this.mOrientation = orientation; this.mPriority = priority; }
    getOrientation(): number { return this.mOrientation; }
    getPriority(): number { return this.mPriority; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}CollapsiblePriorityModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new CollapsiblePriorityModifier(buffer.readInt(), buffer.readFloat()));
    }
}

// ── MODIFIER_BACKGROUND (55): INT flags, INT colorId, INT res1, INT res2,
//    FLOAT r, FLOAT g, FLOAT b, FLOAT a, INT shapeType ────────────────
export class BackgroundModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 55;
    private static readonly COLOR_REF = 2;
    private mFlags: number; private mColorId: number;
    // r/g/b/a as raw float32 int bits (may be NaN-encoded color-var refs).
    private mR: number; private mG: number; private mB: number; private mA: number;
    private mOutR: number; private mOutG: number; private mOutB: number; private mOutA: number;
    private mShapeType: number;
    private mComponent: any = null;
    private mLayoutW = 0; private mLayoutH = 0;
    constructor(flags: number, colorId: number, r: number, g: number, b: number, a: number, shapeType: number) {
        super(); this.mFlags = flags; this.mColorId = colorId;
        this.mR = r; this.mG = g; this.mB = b; this.mA = a;
        this.mOutR = isNaNBits(r) ? 0 : intBitsToFloat(r);
        this.mOutG = isNaNBits(g) ? 0 : intBitsToFloat(g);
        this.mOutB = isNaNBits(b) ? 0 : intBitsToFloat(b);
        this.mOutA = isNaNBits(a) ? 0 : intBitsToFloat(a);
        this.mShapeType = shapeType;
    }
    setComponent(c: any): void { this.mComponent = c; }
    layoutDecorator(w: number, h: number): void { this.mLayoutW = w; this.mLayoutH = h; }
    registerListening(context: RemoteContext): void {
        if (this.mFlags === BackgroundModifier.COLOR_REF) {
            context.listensTo(this.mColorId, this);
        } else {
            if (isNaNBits(this.mR)) context.listensTo(idFromBits(this.mR), this);
            if (isNaNBits(this.mG)) context.listensTo(idFromBits(this.mG), this);
            if (isNaNBits(this.mB)) context.listensTo(idFromBits(this.mB), this);
            if (isNaNBits(this.mA)) context.listensTo(idFromBits(this.mA), this);
        }
    }
    updateVariables(context: RemoteContext): void {
        if (this.mFlags !== BackgroundModifier.COLOR_REF) {
            if (isNaNBits(this.mR)) this.mOutR = context.getFloat(idFromBits(this.mR));
            if (isNaNBits(this.mG)) this.mOutG = context.getFloat(idFromBits(this.mG));
            if (isNaNBits(this.mB)) this.mOutB = context.getFloat(idFromBits(this.mB));
            if (isNaNBits(this.mA)) this.mOutA = context.getFloat(idFromBits(this.mA));
        }
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;

        const w = this.mLayoutW;
        const h = this.mLayoutH;
        if (w <= 0 || h <= 0) return;

        pc.savePaint();

        // Build a minimal PaintBundle: STYLE=FILL + COLOR=argb
        let argb: number;
        if (this.mFlags === BackgroundModifier.COLOR_REF) {
            argb = context.mRemoteComposeState.getColor(this.mColorId);
        } else {
            const a = Math.trunc(this.mOutA * 255 + 0.5);
            const r = Math.trunc(this.mOutR * 255 + 0.5);
            const g = Math.trunc(this.mOutG * 255 + 0.5);
            const b = Math.trunc(this.mOutB * 255 + 0.5);
            argb = ((a << 24) | (r << 16) | (g << 8) | b) | 0;
        }
        const pb = new PaintBundle();
        pb.mArray = [PaintBundle.STYLE, PaintBundle.COLOR, argb];
        pb.mPos = 3;
        pc.replacePaint(pb);

        if (this.mShapeType === 1) { // CIRCLE
            pc.drawCircle(w / 2, h / 2, Math.min(w, h) / 2);
        } else { // RECTANGLE (0)
            pc.drawRect(0, 0, w, h);
        }

        pc.restorePaint();
    }
    deepToString(indent: string): string { return `${indent}BackgroundModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        // Java BackgroundModifierOperation.read: colorId remapped (readId),
        // r/g/b/a are readNanId (NaN-encoded color-var refs remap in macros).
        const flags = buffer.readInt();
        const colorId = buffer.readId();
        buffer.readInt(); // reserve1
        buffer.readInt(); // reserve2
        const r = buffer.readInt();
        const g = buffer.readInt();
        const b = buffer.readInt();
        const a = buffer.readInt();
        const shapeType = buffer.readInt();
        operations.push(new BackgroundModifier(flags, colorId, r, g, b, a, shapeType));
    }
}

// ── MODIFIER_BORDER (107): INT flags, INT colorId, INT res1, INT res2,
//    FLOAT borderWidth, FLOAT roundedCorner, FLOAT r, FLOAT g, FLOAT b, FLOAT a, INT shapeType
export class BorderModifier extends Operation {
    static readonly OP_CODE = 107;
    private static readonly COLOR_REF = 2;
    private mFlags: number;
    private mColorId: number;
    private mBorderWidth: number;
    private mRoundedCorner: number;
    private mR: number; private mG: number; private mB: number; private mA: number;
    private mShapeType: number;
    private mComponent: any = null;
    private mLayoutW = 0; private mLayoutH = 0;
    constructor(flags: number, colorId: number, borderWidth: number, roundedCorner: number,
                r: number, g: number, b: number, a: number, shapeType: number) {
        super();
        this.mFlags = flags; this.mColorId = colorId;
        this.mBorderWidth = borderWidth; this.mRoundedCorner = roundedCorner;
        this.mR = r; this.mG = g; this.mB = b; this.mA = a; this.mShapeType = shapeType;
    }
    setComponent(c: any): void { this.mComponent = c; }
    layoutDecorator(w: number, h: number): void { this.mLayoutW = w; this.mLayoutH = h; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;
        const w = this.mLayoutW;
        const h = this.mLayoutH;
        if (w <= 0 || h <= 0) return;

        pc.savePaint();
        const pb = new PaintBundle();
        let argb: number;
        if (this.mFlags === BorderModifier.COLOR_REF) {
            argb = context.mRemoteComposeState.getColor(this.mColorId);
        } else {
            const a = Math.trunc(this.mA * 255 + 0.5);
            const r = Math.trunc(this.mR * 255 + 0.5);
            const g = Math.trunc(this.mG * 255 + 0.5);
            const b = Math.trunc(this.mB * 255 + 0.5);
            argb = ((a << 24) | (r << 16) | (g << 8) | b) | 0;
        }
        pb.reset();
        pb.setStyle(PaintBundle.STROKE);
        pb.setColor(argb);
        pb.setStrokeWidth(this.mBorderWidth);
        pc.replacePaint(pb);

        if (this.mRoundedCorner > 0) {
            pc.drawRoundRect(0, 0, w, h, this.mRoundedCorner, this.mRoundedCorner);
        } else {
            pc.drawRect(0, 0, w, h);
        }
        pc.restorePaint();
    }
    deepToString(indent: string): string { return `${indent}BorderModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        // Java BorderModifierOperation.read: colorId remapped (readId); float
        // fields are readNanId (NaN-encoded var refs remap in macros).
        const flags = buffer.readInt();
        const colorId = buffer.readId();
        buffer.readInt(); // reserve1
        buffer.readInt(); // reserve2
        const borderWidth = buffer.readNanId();
        const roundedCorner = buffer.readNanId();
        const r = buffer.readNanId();
        const g = buffer.readNanId();
        const b = buffer.readNanId();
        const a = buffer.readNanId();
        const shapeType = buffer.readInt();
        operations.push(new BorderModifier(flags, colorId, borderWidth, roundedCorner, r, g, b, a, shapeType));
    }
}

// ── MODIFIER_PADDING (58): FLOAT left, FLOAT top, FLOAT right, FLOAT bottom
export class PaddingModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 58;
    // l/t/r/b as raw float32 int bits (may be NaN-encoded variable refs).
    private mLeft: number; private mTop: number; private mRight: number; private mBottom: number;
    mLeftValue: number; mTopValue: number; mRightValue: number; mBottomValue: number;
    constructor(l: number, t: number, r: number, b: number) {
        super();
        this.mLeft = l; this.mTop = t; this.mRight = r; this.mBottom = b;
        this.mLeftValue = isNaNBits(l) ? 0 : intBitsToFloat(l);
        this.mTopValue = isNaNBits(t) ? 0 : intBitsToFloat(t);
        this.mRightValue = isNaNBits(r) ? 0 : intBitsToFloat(r);
        this.mBottomValue = isNaNBits(b) ? 0 : intBitsToFloat(b);
    }
    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mLeft)) context.listensTo(idFromBits(this.mLeft), this);
        if (isNaNBits(this.mTop)) context.listensTo(idFromBits(this.mTop), this);
        if (isNaNBits(this.mRight)) context.listensTo(idFromBits(this.mRight), this);
        if (isNaNBits(this.mBottom)) context.listensTo(idFromBits(this.mBottom), this);
    }
    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mLeft)) this.mLeftValue = context.getFloat(idFromBits(this.mLeft));
        if (isNaNBits(this.mTop)) this.mTopValue = context.getFloat(idFromBits(this.mTop));
        if (isNaNBits(this.mRight)) this.mRightValue = context.getFloat(idFromBits(this.mRight));
        if (isNaNBits(this.mBottom)) this.mBottomValue = context.getFloat(idFromBits(this.mBottom));
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}PaddingModifier(${this.mLeftValue}, ${this.mTopValue}, ${this.mRightValue}, ${this.mBottomValue})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new PaddingModifier(
            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_ROUNDED_CLIP_RECT (54): FLOAT topStart, FLOAT topEnd, FLOAT bottomStart, FLOAT bottomEnd
export class RoundedClipRectModifier extends Operation {
    static readonly OP_CODE = 54;
    private mTopStart: number; private mTopEnd: number;
    private mBottomStart: number; private mBottomEnd: number;
    private mLayoutW = 0; private mLayoutH = 0;
    private mComponent: any = null;
    constructor(topStart: number, topEnd: number, bottomStart: number, bottomEnd: number) {
        super();
        this.mTopStart = topStart; this.mTopEnd = topEnd;
        this.mBottomStart = bottomStart; this.mBottomEnd = bottomEnd;
    }
    setComponent(c: any): void { this.mComponent = c; }
    layoutDecorator(w: number, h: number): void { this.mLayoutW = w; this.mLayoutH = h; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;
        const w = this.mLayoutW;
        const h = this.mLayoutH;
        if (w > 0 && h > 0) {
            pc.roundedClipRect(w, h, this.mTopStart, this.mTopEnd, this.mBottomStart, this.mBottomEnd);
        }
    }
    deepToString(indent: string): string { return `${indent}RoundedClipRectModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RoundedClipRectModifier(
            buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
    }
}

// ── MODIFIER_CLIP_RECT (108): no payload ──────────────────────────────
export class ClipRectModifier extends Operation {
    static readonly OP_CODE = 108;
    private mComponent: any = null;
    private mLayoutW = 0; private mLayoutH = 0;
    constructor() { super(); }
    setComponent(c: any): void { this.mComponent = c; }
    layoutDecorator(w: number, h: number): void { this.mLayoutW = w; this.mLayoutH = h; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;
        const w = this.mLayoutW;
        const h = this.mLayoutH;
        if (w > 0 && h > 0) {
            pc.clipRect(0, 0, w, h);
        }
    }
    deepToString(indent: string): string { return `${indent}ClipRectModifier`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ClipRectModifier());
    }
}

// ── MODIFIER_CLICK (59): no payload (container — children follow) ─────
export class ClickModifier extends Operation {
    static readonly OP_CODE = 59;
    mList: Operation[] = [];
    private mComponent: any = null;
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }
    setComponent(c: any): void { this.mComponent = c; }

    onClick(context: RemoteContext, _doc: any, _x: number, _y: number): boolean {
        // Execute action operations
        for (const op of this.mList) {
            op.apply(context);
        }
        context.needsRepaint();
        return true;
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}ClickModifier(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ClickModifier());
    }
}

// ── MODIFIER_MULTI_CLICK (83): INT clickType (container — children follow) ─
// Java source: operations/layout/MultiClickModifier.java
// clickType: 0 = single, 1 = long press, 2 = double click
export class MultiClickModifier extends Operation {
    static readonly OP_CODE = 83;
    static readonly CLICK_TYPE_SINGLE = 0;
    static readonly CLICK_TYPE_LONG = 1;
    static readonly CLICK_TYPE_DOUBLE = 2;

    mClickType: number = 0;
    mList: Operation[] = [];
    private mComponent: any = null;

    constructor(clickType: number = 0) {
        super();
        this.mClickType = clickType;
    }
    getList(): Operation[] { return this.mList; }
    getClickType(): number { return this.mClickType; }
    setComponent(c: any): void { this.mComponent = c; }

    onClick(context: RemoteContext, _doc: any, _x: number, _y: number): boolean {
        if (this.mClickType !== MultiClickModifier.CLICK_TYPE_SINGLE) return false;
        for (const op of this.mList) op.apply(context);
        context.needsRepaint();
        return true;
    }

    onLongPress(context: RemoteContext, _doc: any, _x: number, _y: number): boolean {
        if (this.mClickType !== MultiClickModifier.CLICK_TYPE_LONG) return false;
        for (const op of this.mList) op.apply(context);
        context.needsRepaint();
        return true;
    }

    onDoubleClick(context: RemoteContext, _doc: any, _x: number, _y: number): boolean {
        if (this.mClickType !== MultiClickModifier.CLICK_TYPE_DOUBLE) return false;
        for (const op of this.mList) op.apply(context);
        context.needsRepaint();
        return true;
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string {
        return `${indent}MultiClickModifier(type=${this.mClickType}, ${this.mList.length} actions)`;
    }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const clickType = buffer.readInt();
        operations.push(new MultiClickModifier(clickType));
    }
}

// ── MODIFIER_DIMENSION_CONSTRAINTS (243): BYTE type, FLOAT min, FLOAT max ───
// Java source: operations/layout/modifiers/DimensionConstraintsModifierOperation.java
// type: 0 = horizontal, 1 = vertical, 2 = required horizontal, 3 = required vertical.
// "Required" variants override parent constraints; soft variants are clamped.
export class DimensionConstraintsModifier extends Operation {
    static readonly OP_CODE = 243;
    static readonly HORIZONTAL = 0;
    static readonly VERTICAL = 1;
    static readonly REQUIRED_HORIZONTAL = 2;
    static readonly REQUIRED_VERTICAL = 3;

    mType: number;
    mMin: number;
    mMax: number;

    constructor(type: number, min: number, max: number) {
        super();
        this.mType = type;
        this.mMin = min;
        this.mMax = max;
    }

    getType(): number { return this.mType; }
    getMin(): number { return this.mMin; }
    getMax(): number { return this.mMax; }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string {
        return `${indent}DimensionConstraintsModifier(type=${this.mType}, min=${this.mMin}, max=${this.mMax})`;
    }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const type = buffer.readByte();
        const min = buffer.readFloat();
        const max = buffer.readFloat();
        operations.push(new DimensionConstraintsModifier(type, min, max));
    }
}

// ── MODIFIER_TOUCH_DOWN (219): no payload (container) ─────────────────
export class TouchDownModifier extends Operation {
    static readonly OP_CODE = 219;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }

    onTouchDown(context: RemoteContext): void {
        for (const op of this.mList) {
            op.apply(context);
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}TouchDownModifier(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new TouchDownModifier());
    }
}

// ── MODIFIER_TOUCH_UP (220): no payload (container) ───────────────────
export class TouchUpModifier extends Operation {
    static readonly OP_CODE = 220;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }

    onTouchUp(context: RemoteContext): void {
        for (const op of this.mList) {
            op.apply(context);
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}TouchUpModifier(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new TouchUpModifier());
    }
}

// ── MODIFIER_TOUCH_CANCEL (225): no payload (container) ───────────────
export class TouchCancelModifier extends Operation {
    static readonly OP_CODE = 225;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }

    onTouchCancel(context: RemoteContext): void {
        for (const op of this.mList) {
            op.apply(context);
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}TouchCancelModifier(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new TouchCancelModifier());
    }
}

// ── MODIFIER_VISIBILITY (211): INT visibilityId ──────────────────────
export class VisibilityModifier extends Operation {
    static readonly OP_CODE = 211;
    private mVisibilityId: number;
    private mVisibility = Visibility.VISIBLE;
    private mParent: any = null;
    constructor(visibilityId: number) { super(); this.mVisibilityId = visibilityId; }
    setParent(parent: any): void { this.mParent = parent; }
    registerListening(context: RemoteContext): void {
        context.listensTo(this.mVisibilityId, this);
    }
    updateVariables(context: RemoteContext): void {
        const v = context.getInteger(this.mVisibilityId);
        // Match Java ComponentVisibilityOperation.updateVariables:
        // interpret the protocol value using Visibility helpers
        if (Visibility.isVisible(v)) {
            this.mVisibility = Visibility.VISIBLE;
        } else if (Visibility.isGone(v)) {
            this.mVisibility = Visibility.GONE;
        } else if (Visibility.isInvisible(v)) {
            this.mVisibility = Visibility.INVISIBLE;
        } else {
            this.mVisibility = Visibility.GONE;
        }
        if (this.mParent && typeof this.mParent.setVisibility === 'function') {
            this.mParent.setVisibility(this.mVisibility);
        }
        this.markNotDirty();
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (this.isDirty()) {
            this.updateVariables(context);
        }
    }
    deepToString(indent: string): string { return `${indent}VisibilityModifier(${this.mVisibilityId})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new VisibilityModifier(buffer.readInt()));
    }
}

// ── MODIFIER_OFFSET (221): FLOAT x, FLOAT y ──────────────────────────
export class OffsetModifier extends Operation implements VariableSupport {
    static readonly OP_CODE = 221;
    // x/y as raw float32 int bits (may be NaN-encoded variable refs).
    private mOffX: number; private mOffY: number;
    private mOutX: number; private mOutY: number;
    constructor(x: number, y: number) {
        super();
        this.mOffX = x; this.mOffY = y;
        this.mOutX = isNaNBits(x) ? 0 : intBitsToFloat(x);
        this.mOutY = isNaNBits(y) ? 0 : intBitsToFloat(y);
    }
    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mOffX)) context.listensTo(idFromBits(this.mOffX), this);
        if (isNaNBits(this.mOffY)) context.listensTo(idFromBits(this.mOffY), this);
    }
    updateVariables(context: RemoteContext): void {
        if (isNaNBits(this.mOffX)) this.mOutX = context.getFloat(idFromBits(this.mOffX));
        if (isNaNBits(this.mOffY)) this.mOutY = context.getFloat(idFromBits(this.mOffY));
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;
        pc.translate(this.mOutX, this.mOutY);
    }
    deepToString(indent: string): string { return `${indent}OffsetModifier(${this.mOutX}, ${this.mOutY})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new OffsetModifier(buffer.readInt(), buffer.readInt()));
    }
}

// ── MODIFIER_ZINDEX (223): FLOAT value ────────────────────────────────
export class ZIndexModifier extends Operation {
    static readonly OP_CODE = 223;
    private mValueBits: number;
    private mCurrentValue: number;
    constructor(valueBits: number) {
        super(); this.mValueBits = valueBits;
        this.mCurrentValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getValue(): number { return this.mCurrentValue; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.PAINT && isVariableBits(this.mValueBits)) {
            this.mCurrentValue = context.getFloat(idFromBits(this.mValueBits));
        }
    }
    deepToString(indent: string): string { return `${indent}ZIndexModifier(${this.mCurrentValue})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ZIndexModifier(buffer.readInt()));
    }
}

// ── MODIFIER_GRAPHICS_LAYER (224): INT length, then [INT tag, FLOAT/INT value]* ──
export class GraphicsLayerModifier extends Operation {
    static readonly OP_CODE = 224;
    // Attribute tag IDs (lower 10 bits of the tag word)
    static readonly SCALE_X = 0;
    static readonly SCALE_Y = 1;
    static readonly ROTATION_X = 2;
    static readonly ROTATION_Y = 3;
    static readonly ROTATION_Z = 4;
    static readonly TRANSLATION_X = 5;
    static readonly TRANSLATION_Y = 6;
    static readonly TRANSLATION_Z = 7;
    static readonly ALPHA = 8;
    static readonly SHADOW_ELEVATION = 9;
    static readonly CAMERA_DISTANCE = 10;
    static readonly BLUR_RADIUS_X = 15;
    static readonly BLUR_RADIUS_Y = 16;

    private mAttributes: Map<number, { value: number; isFloat: boolean }> = new Map();

    constructor(attributes: Map<number, { value: number; isFloat: boolean }>) {
        super();
        this.mAttributes = attributes;
    }

    getAlpha(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.ALPHA);
        return attr ? attr.value : 1.0;
    }

    getScaleX(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.SCALE_X);
        return attr ? attr.value : 1.0;
    }

    getScaleY(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.SCALE_Y);
        return attr ? attr.value : 1.0;
    }

    getRotationZ(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.ROTATION_Z);
        return attr ? attr.value : 0.0;
    }

    getTranslationX(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.TRANSLATION_X);
        return attr ? attr.value : 0.0;
    }

    getTranslationY(): number {
        const attr = this.mAttributes.get(GraphicsLayerModifier.TRANSLATION_Y);
        return attr ? attr.value : 0.0;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;

        // Evaluate variable-referenced attributes
        for (const [_key, attr] of this.mAttributes) {
            if (attr.isFloat && Number.isNaN(attr.value)) {
                // NaN-encoded variable — resolve
                // Note: the attribute values are static for now; animation support would go here
            }
        }
    }

    deepToString(indent: string): string { return `${indent}GraphicsLayerModifier(${this.mAttributes.size} attrs)`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const length = buffer.readInt();
        const attributes = new Map<number, { value: number; isFloat: boolean }>();
        for (let i = 0; i < length; i++) {
            const tag = buffer.readInt();
            const attrId = tag & 0x3FF;
            const dataType = (tag >> 10) & 0x3;
            if (dataType === 1) {
                attributes.set(attrId, { value: buffer.readFloat(), isFloat: true });
            } else {
                attributes.set(attrId, { value: buffer.readInt(), isFloat: false });
            }
        }
        operations.push(new GraphicsLayerModifier(attributes));
    }
}

// ── MODIFIER_SCROLL (226): INT direction, FLOAT position, FLOAT max, FLOAT notchMax
export class ScrollModifier extends Operation {
    static readonly OP_CODE = 226;
    static readonly VERTICAL = 0;
    static readonly HORIZONTAL = 1;
    mList: Operation[] = [];
    private mDirection: number;
    // position/max/notchMax as raw float32 int bits (NaN-encoded variable refs;
    // getMaxNan/getNotchMaxNan return the bits which LayoutManager decodes).
    private mPositionId: number;
    private mMax: number;
    private mNotchMax: number;
    constructor(direction: number, positionId: number, max: number, notchMax: number) {
        super();
        this.mDirection = direction;
        this.mPositionId = positionId;
        this.mMax = max;
        this.mNotchMax = notchMax;
    }
    getList(): Operation[] { return this.mList; }
    getDirection(): number { return this.mDirection; }
    getMaxNan(): number { return this.mMax; }
    getNotchMaxNan(): number { return this.mNotchMax; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc) return;
        // Read current scroll position from context
        const pos = isNaNBits(this.mPositionId)
            ? context.getFloat(idFromBits(this.mPositionId))
            : 0;
        if (this.mDirection === ScrollModifier.HORIZONTAL) {
            pc.translate(-pos, 0);
        } else {
            pc.translate(0, -pos);
        }
    }
    deepToString(indent: string): string { return `${indent}ScrollModifier(dir=${this.mDirection})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const direction = buffer.readInt();
        const position = buffer.readInt();
        const max = buffer.readInt();
        const notchMax = buffer.readInt();
        operations.push(new ScrollModifier(direction, position, max, notchMax));
    }
}

// ── MODIFIER_MARQUEE (228): INT iterations, INT animationMode,
//    FLOAT repeatDelay, FLOAT initialDelay, FLOAT spacing, FLOAT velocity
export class MarqueeModifier extends Operation {
    static readonly OP_CODE = 228;
    private mIterations: number;
    private mAnimationMode: number;
    private mRepeatDelayMillis: number;
    private mInitialDelayMillis: number;
    private mSpacing: number;
    private mVelocity: number;
    private mComponent: any = null;
    private mScrollX = 0;
    private mStartTime = 0;
    private mLastTime = 0;
    private mComponentWidth = 0;
    private mContentWidth = 0;

    constructor(iterations: number, animationMode: number,
                repeatDelay: number, initialDelay: number,
                spacing: number, velocity: number) {
        super();
        this.mIterations = iterations;
        this.mAnimationMode = animationMode;
        this.mRepeatDelayMillis = repeatDelay;
        this.mInitialDelayMillis = initialDelay;
        this.mSpacing = spacing;
        this.mVelocity = velocity;
    }
    setComponent(c: any): void { this.mComponent = c; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode !== ContextMode.PAINT) return;
        const pc = context.getPaintContext();
        if (!pc || !this.mComponent) return;

        // Capture component and content dimensions on first call
        this.mComponentWidth = this.mComponent.getWidth?.() ?? 0;
        this.mContentWidth = this.mComponent.getContentWidth?.() ?? this.mComponentWidth;

        const delta = this.mContentWidth - this.mComponentWidth;
        if (delta <= 0) return; // No overflow, no scrolling needed

        const now = context.getAnimationTime();
        if (this.mStartTime === 0) {
            this.mStartTime = now;
        }

        const elapsed = now - this.mStartTime - this.mInitialDelayMillis / 1000;
        if (elapsed < 0) {
            context.needsRepaint();
            return;
        }

        // Compute animation duration from velocity (pixels per second)
        const duration = delta / (this.mVelocity > 0 ? this.mVelocity : 30);
        const totalCycle = duration + this.mRepeatDelayMillis / 1000;
        const cyclePos = elapsed % totalCycle;

        if (cyclePos < duration) {
            // Sine wave animation: offset = (1 + sin(t * 2π - π/2)) / 2 * -delta
            const t = cyclePos / duration;
            const offset = (1 + Math.sin(t * 2 * Math.PI - Math.PI / 2)) / 2 * -delta;
            this.mScrollX = offset;
        } else {
            this.mScrollX = 0; // In repeat delay period
        }

        pc.translate(this.mScrollX, 0);
        context.needsRepaint();
    }
    deepToString(indent: string): string { return `${indent}MarqueeModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const iterations = buffer.readInt();
        const animationMode = buffer.readInt();
        const repeatDelay = buffer.readFloat();
        const initialDelay = buffer.readFloat();
        const spacing = buffer.readFloat();
        const velocity = buffer.readFloat();
        operations.push(new MarqueeModifier(iterations, animationMode, repeatDelay, initialDelay, spacing, velocity));
    }
}

// ── MODIFIER_RIPPLE (229): no payload ─────────────────────────────────
export class RippleModifier extends Operation {
    static readonly OP_CODE = 229;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}RippleModifier`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RippleModifier());
    }
}

// ── MODIFIER_DRAW_CONTENT (174): no payload ───────────────────────────
export class DrawContentModifier extends Operation {
    static readonly OP_CODE = 174;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}DrawContentModifier`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawContentModifier());
    }
}

// ── MODIFIER_ALIGN_BY (237): FLOAT line, INT flags ────────────────────
export class AlignByModifier extends Operation {
    static readonly OP_CODE = 237;
    private mLine: number; private mFlags: number;
    constructor(line: number, flags: number) { super(); this.mLine = line; this.mFlags = flags; }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* handled by layout */ }
    deepToString(indent: string): string { return `${indent}AlignByModifier`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new AlignByModifier(buffer.readFloat(), buffer.readInt()));
    }
}
