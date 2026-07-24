// MatrixOperations: matrix transform and clip operations.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { ContextMode } from '../RemoteContext';
import { isNaNBits, idFromBits, intBitsToFloat } from './Utils';

// All matrix value fields are stored as raw float32 int bits (a coordinate is
// either a NaN-encoded variable reference or a literal float). This keeps the
// encoded ids alive on engines that canonicalize NaN payloads (Safari/Firefox).
function listenFloat(bits: number, context: RemoteContext, op: any): void {
    if (isNaNBits(bits)) context.listensTo(idFromBits(bits), op);
}
function resolveFloat(bits: number, context: RemoteContext): number {
    return isNaNBits(bits) ? context.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
}

export class MatrixSave extends Operation {
    static readonly OP_CODE = 130;
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        const pc = context.getPaintContext();
        if (pc) { pc.matrixSave(); pc.savePaint(); }
    }
    deepToString(indent: string): string { return `${indent}MatrixSave`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixSave());
    }
}

export class MatrixRestore extends Operation {
    static readonly OP_CODE = 131;
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        const pc = context.getPaintContext();
        if (pc) { pc.restorePaint(); pc.matrixRestore(); }
    }
    deepToString(indent: string): string { return `${indent}MatrixRestore`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixRestore());
    }
}

export class MatrixTranslate extends Operation {
    static readonly OP_CODE = 127;
    mTranslateX: number; mTranslateY: number;
    constructor(tx: number, ty: number) { super(); this.mTranslateX = tx; this.mTranslateY = ty; }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mTranslateX, context, this);
        listenFloat(this.mTranslateY, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.matrixTranslate(
            resolveFloat(this.mTranslateX, context),
            resolveFloat(this.mTranslateY, context));
    }
    deepToString(indent: string): string { return `${indent}MatrixTranslate(${this.mTranslateX}, ${this.mTranslateY})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixTranslate(buffer.readInt(), buffer.readInt()));
    }
}

export class MatrixScale extends Operation {
    static readonly OP_CODE = 126;
    mScaleX: number; mScaleY: number; mCenterX: number; mCenterY: number;
    constructor(sx: number, sy: number, cx: number, cy: number) {
        super(); this.mScaleX = sx; this.mScaleY = sy; this.mCenterX = cx; this.mCenterY = cy;
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mScaleX, context, this);
        listenFloat(this.mScaleY, context, this);
        listenFloat(this.mCenterX, context, this);
        listenFloat(this.mCenterY, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.matrixScale(
            resolveFloat(this.mScaleX, context),
            resolveFloat(this.mScaleY, context),
            resolveFloat(this.mCenterX, context),
            resolveFloat(this.mCenterY, context));
    }
    deepToString(indent: string): string { return `${indent}MatrixScale(${this.mScaleX}, ${this.mScaleY})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixScale(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}

export class MatrixRotate extends Operation {
    static readonly OP_CODE = 129;
    mAngle: number; mPivotX: number; mPivotY: number;
    constructor(angle: number, px: number, py: number) {
        super(); this.mAngle = angle; this.mPivotX = px; this.mPivotY = py;
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mAngle, context, this);
        listenFloat(this.mPivotX, context, this);
        listenFloat(this.mPivotY, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.matrixRotate(
            resolveFloat(this.mAngle, context),
            resolveFloat(this.mPivotX, context),
            resolveFloat(this.mPivotY, context));
    }
    deepToString(indent: string): string { return `${indent}MatrixRotate(${this.mAngle})`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixRotate(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}

export class MatrixSkew extends Operation {
    static readonly OP_CODE = 128;
    mSkewX: number; mSkewY: number;
    constructor(sx: number, sy: number) { super(); this.mSkewX = sx; this.mSkewY = sy; }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mSkewX, context, this);
        listenFloat(this.mSkewY, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.matrixSkew(
            resolveFloat(this.mSkewX, context),
            resolveFloat(this.mSkewY, context));
    }
    deepToString(indent: string): string { return `${indent}MatrixSkew`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixSkew(buffer.readInt(), buffer.readInt()));
    }
}

export class MatrixFromPath extends Operation {
    static readonly OP_CODE = 181;
    mPathId: number; mFraction: number; mVOffset: number; mFlags: number;
    constructor(pathId: number, fraction: number, vOffset: number, flags: number) {
        super(); this.mPathId = pathId; this.mFraction = fraction; this.mVOffset = vOffset; this.mFlags = flags;
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mFraction, context, this);
        listenFloat(this.mVOffset, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.matrixFromPath(
            this.mPathId,
            resolveFloat(this.mFraction, context),
            resolveFloat(this.mVOffset, context),
            this.mFlags);
    }
    deepToString(indent: string): string { return `${indent}MatrixFromPath`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new MatrixFromPath(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}

export class ClipRect extends Operation {
    static readonly OP_CODE = 39;
    mLeft: number; mTop: number; mRight: number; mBottom: number;
    constructor(l: number, t: number, r: number, b: number) {
        super(); this.mLeft = l; this.mTop = t; this.mRight = r; this.mBottom = b;
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    registerListening(context: RemoteContext): void {
        listenFloat(this.mLeft, context, this);
        listenFloat(this.mTop, context, this);
        listenFloat(this.mRight, context, this);
        listenFloat(this.mBottom, context, this);
    }

    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.clipRect(
            resolveFloat(this.mLeft, context),
            resolveFloat(this.mTop, context),
            resolveFloat(this.mRight, context),
            resolveFloat(this.mBottom, context));
    }
    deepToString(indent: string): string { return `${indent}ClipRect`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ClipRect(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
}

export class ClipPath extends Operation {
    static readonly OP_CODE = 38;
    mPathId: number; mRegionOp: number;
    constructor(pathId: number, regionOp: number) {
        super(); this.mPathId = pathId; this.mRegionOp = regionOp;
    }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(context: RemoteContext): void {
        if (context.mMode === ContextMode.DATA) return;
        context.getPaintContext()?.clipPath(this.mPathId, this.mRegionOp);
    }
    deepToString(indent: string): string { return `${indent}ClipPath`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        const pack = buffer.readInt();
        const id = pack & 0xFFFFF;
        const regionOp = pack >> 24;
        operations.push(new ClipPath(id, regionOp));
    }
}
