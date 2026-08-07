// StubOperations: parse-only operation stubs that read fields but have no apply() logic.

import { Operation } from '../Operation';
import { PaintOperation } from '../PaintOperation';
import { idFromBits, floatToRawIntBits } from './Utils';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { createSnapshot } from '../RemoteClock';
import type { PaintContext } from '../PaintContext';
import { ContextMode } from '../RemoteContext';

// ── ImpulseOperation (164): container, time-gated ────────────────────
/**
 * Runs its body **once** when its time window opens, then hands every later frame to
 * the trailing {@link ImpulseProcess}.
 *
 * This is how a document separates setup from per-frame work: everything directly in
 * the impulse body is initialisation, and the repeating part lives in the nested
 * `impulseProcess`. Treating the operation as a no-op — which this was — leaves its
 * children as ordinary siblings that run on every frame, so a one-time assignment like
 * `current = flow` is re-executed forever and any value derived from it is pinned.
 */
export class ImpulseOperation extends PaintOperation {
    static readonly OP_CODE = 164;
    mList: Operation[] = [];
    private mDuration: number;
    private mStartAt: number;
    private mOutDuration: number;
    private mOutStartAt: number;
    private mProcess: ImpulseProcess | null = null;
    private mInitialPass = true;

    constructor(duration: number, startAt: number) {
        super();
        this.mDuration = duration;
        this.mStartAt = startAt;
        this.mOutDuration = duration;
        this.mOutStartAt = startAt;
    }

    getList(): Operation[] { return this.mList; }

    updateVariables(context: RemoteContext): void {
        this.mOutDuration = Number.isNaN(this.mDuration)
            ? context.getFloat(idFromBits(floatToRawIntBits(this.mDuration))) : this.mDuration;
        this.mOutStartAt = Number.isNaN(this.mStartAt)
            ? context.getFloat(idFromBits(floatToRawIntBits(this.mStartAt))) : this.mStartAt;
    }

    /** The trailing ImpulseProcess is the repeating part; it is not run as setup. */
    private takeProcess(): void {
        if (this.mProcess || !this.mList.length) return;
        const last = this.mList[this.mList.length - 1];
        if (last instanceof ImpulseProcess) {
            this.mProcess = last;
            this.mList.pop();
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        this.takeProcess();
        const remote = context.getContext();
        const now = remote.getAnimationTime();
        if (now < this.mOutStartAt) {
            context.wakeIn(this.mOutStartAt - now);
            return;
        }
        if (now <= this.mOutStartAt + this.mOutDuration) {
            if (this.mInitialPass) {
                for (const op of this.mList) {
                    if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                        (op as any).updateVariables(remote);
                    }
                    remote.incrementOpCount();
                    op.apply(remote);
                }
                this.mInitialPass = false;
            } else {
                remote.incrementOpCount();
                if (this.mProcess) this.mProcess.paint(context);
            }
        } else {
            // Past the window: arm again so a later window replays the setup.
            this.mInitialPass = true;
        }
    }

    deepToString(indent: string): string {
        return `${indent}ImpulseOperation(${this.mList.length} setup ops)`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const duration = buffer.readFloat();
        const startAt = buffer.readFloat();
        operations.push(new ImpulseOperation(duration, startAt));
    }
}

// ── ImpulseProcess (165): the per-frame body of an impulse ────────────
export class ImpulseProcess extends PaintOperation {
    static readonly OP_CODE = 165;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }
    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        const remote = context.getContext();
        for (const op of this.mList) {
            if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                (op as any).updateVariables(remote);
            }
            remote.incrementOpCount();
            op.apply(remote);
        }
    }

    deepToString(indent: string): string {
        return `${indent}ImpulseProcess(${this.mList.length})`;
    }

    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new ImpulseProcess());
    }
}

// CanvasOperations (173) is a real paint container, not a stub — see
// operations/layout/CanvasOperations.ts.

// ── DebugMessage (179) ──────────────────────────────────────────────
export class DebugMessage extends Operation {
    static readonly OP_CODE = 179;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}DebugMessage`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // textId
        buffer.readFloat(); // floatValue
        buffer.readInt(); // flags
        operations.push(new DebugMessage());
    }
}

// ── HostActionMetadataOperation (216) ───────────────────────────────
export class HostActionMetadataOperation extends Operation {
    static readonly OP_CODE = 216;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}HostActionMetadataOperation`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // actionId
        buffer.readInt(); // metadataId
        operations.push(new HostActionMetadataOperation());
    }
}

// ── RunActionOperation (236): container — children are action operations ──
/**
 * Runs its child actions **as part of painting**, once per frame.
 *
 * This is a `PaintOperation` in the reference, not a load-time one, and the difference
 * is the whole point: a document drives per-frame state — a score, a physics step —
 * by putting a run-action in the draw stream. Executing the children from `apply`
 * instead runs them exactly once, at load, and the document then sits frozen while
 * still drawing perfectly, which reads as "the actions do nothing".
 */
export class RunActionOperation extends PaintOperation {
    static readonly OP_CODE = 236;
    mList: Operation[] = [];
    constructor() { super(); }
    getList(): Operation[] { return this.mList; }
    write(_buffer: WireBuffer): void { /* stub */ }
    // `apply` comes from PaintOperation: it routes to paint() in PAINT mode, and
    // outside it walks children without running them, which is what we want — the
    // actions must fire per frame, not once at load.
    paint(context: PaintContext): void {
        const remote = context.getContext();
        for (const op of this.mList) {
            op.apply(remote);
        }
    }
    deepToString(indent: string): string { return `${indent}RunActionOperation(${this.mList.length} actions)`; }
    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new RunActionOperation());
    }
}

// ── ValueFloatExpressionChangeAction (227) ──────────────────────────
/**
 * Evaluate an expression and write the result into a float variable.
 *
 * This is how a document mutates its own state: `score = score + 1`,
 * `y = y + dy`. It had been reading both ids off the wire and discarding them, so
 * every such assignment was silently dropped — the document still drew, it just never
 * changed.
 */
export class ValueFloatExpressionChangeAction extends Operation {
    static readonly OP_CODE = 227;
    private mTargetValueId: number;
    private mValueExpressionId: number;

    constructor(targetValueId: number, valueExpressionId: number) {
        super();
        this.mTargetValueId = targetValueId;
        this.mValueExpressionId = valueExpressionId;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        // Only while painting. The reference keeps `apply` empty and does the work in a
        // separate `runAction`, which only the action-runners call — so the action fires
        // exactly once per frame. Here the effect lives in `apply`, and a PaintOperation
        // container also walks its children outside PAINT mode, so the DATA pass ran the
        // action an extra time and every counter sat one increment ahead of the reference.
        if (context.mMode !== ContextMode.PAINT) return;
        const document = context.getDocument();
        if (!document) return;
        document.evaluateFloatExpression(this.mValueExpressionId, this.mTargetValueId, context);
    }

    deepToString(indent: string): string {
        return `${indent}ValueFloatExpressionChangeAction(${this.mTargetValueId} <- ${this.mValueExpressionId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const valueId = buffer.readInt();
        const expressionId = buffer.readInt();
        operations.push(new ValueFloatExpressionChangeAction(valueId, expressionId));
    }
}

// ── PathTween (158) ──────────────────────────────────────────────────
export class PathTween extends Operation {
    static readonly OP_CODE = 158;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}PathTween`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt();   // outId
        buffer.readInt();   // pathId1
        buffer.readInt();   // pathId2
        buffer.readFloat(); // tween
        operations.push(new PathTween());
    }
}

// ── HapticFeedback (177) ─────────────────────────────────────────────
export class HapticFeedback extends Operation {
    static readonly OP_CODE = 177;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}HapticFeedback`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readInt(); // type
        operations.push(new HapticFeedback());
    }
}

// ── WakeIn (191) ─────────────────────────────────────────────────────
export class WakeIn extends Operation {
    static readonly OP_CODE = 191;
    constructor() { super(); }
    write(_buffer: WireBuffer): void { /* stub */ }
    apply(_context: RemoteContext): void { /* stub */ }
    deepToString(indent: string): string { return `${indent}WakeIn`; }
    static read(buffer: WireBuffer, operations: Operation[]): void {
        buffer.readFloat(); // wake
        operations.push(new WakeIn());
    }
}

// ── TimeAttribute (172) ──────────────────────────────────────────────
/**
 * ATTRIBUTE_TIME (172) — write a clock-derived value into a float slot.
 *
 * Ported from the reference `TimeAttribute.paint`. This was a parse-only stub, which is
 * the most expensive kind of gap: the byte stream stayed in sync so nothing ever failed
 * loudly, while every value it should have produced silently stayed 0. A world-clock
 * document is built almost entirely out of these — the hour and minute it displays, and
 * the integer expressions that drive component visibility — so the document rendered
 * with the wrong branch visible and no text in it.
 *
 * The instant measured is `mTimeId`'s LongConstant when there is one, otherwise now.
 */
export class TimeAttribute extends PaintOperation {
    static readonly OP_CODE = 172;

    static readonly TIME_FROM_NOW_SEC = 0;
    static readonly TIME_FROM_NOW_MIN = 1;
    static readonly TIME_FROM_NOW_HR = 2;
    static readonly TIME_FROM_ARG_SEC = 3;
    static readonly TIME_FROM_ARG_MIN = 4;
    static readonly TIME_FROM_ARG_HR = 5;
    static readonly TIME_IN_SEC = 6;
    static readonly TIME_IN_MIN = 7;
    static readonly TIME_IN_HR = 8;
    static readonly TIME_DAY_OF_MONTH = 9;
    static readonly TIME_MONTH_VALUE = 10;
    static readonly TIME_DAY_OF_WEEK = 11;
    static readonly TIME_YEAR = 12;
    static readonly TIME_FROM_LOAD_SEC = 14;
    static readonly TIME_DAY_OF_YEAR = 15;

    private mId: number;
    private mTimeId: number;
    private mType: number;
    private mArgs: number[];

    constructor(id: number, timeId: number, type: number, args: number[]) {
        super();
        this.mId = id; this.mTimeId = timeId; this.mType = type; this.mArgs = args;
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        const val = this.mType & 255;
        const ctx = context.getContext();
        const loadTime = ctx.getDocLoadTime();
        const longConstant: any = ctx.getObject(this.mTimeId);
        const now = context.getClock().snapshot();
        const value = (longConstant && typeof longConstant.getValue === 'function')
            ? createSnapshot(longConstant.getValue())
            : now;

        let delta = 0;
        switch (val) {
            case TimeAttribute.TIME_FROM_NOW_SEC:
            case TimeAttribute.TIME_FROM_NOW_MIN:
            case TimeAttribute.TIME_FROM_NOW_HR:
                delta = value.getMillis() - now.getMillis();
                break;
            case TimeAttribute.TIME_FROM_ARG_SEC:
            case TimeAttribute.TIME_FROM_ARG_MIN:
            case TimeAttribute.TIME_FROM_ARG_HR: {
                const lc2: any = ctx.getObject(this.mArgs[0]);
                delta = value.getMillis() - (lc2 && typeof lc2.getValue === 'function'
                    ? lc2.getValue() : 0);
                break;
            }
            default: break;
        }

        switch (val) {
            case TimeAttribute.TIME_FROM_NOW_SEC:
            case TimeAttribute.TIME_FROM_ARG_SEC:
                ctx.loadFloat(this.mId, delta * 1e-3);
                ctx.needsRepaint?.();
                break;
            case TimeAttribute.TIME_FROM_NOW_MIN:
            case TimeAttribute.TIME_FROM_ARG_MIN:
                ctx.loadFloat(this.mId, delta * 1e-3 / 60);
                ctx.needsRepaint?.();
                break;
            case TimeAttribute.TIME_FROM_NOW_HR:
            case TimeAttribute.TIME_FROM_ARG_HR:
                ctx.loadFloat(this.mId, delta * 1e-3 / 3600);
                break;
            case TimeAttribute.TIME_IN_SEC:
                ctx.loadFloat(this.mId, value.getSecond());
                break;
            case TimeAttribute.TIME_IN_MIN:
                ctx.loadFloat(this.mId, value.getMinute());
                break;
            case TimeAttribute.TIME_IN_HR:
                ctx.loadFloat(this.mId, value.getHour());
                break;
            case TimeAttribute.TIME_DAY_OF_MONTH:
                ctx.loadFloat(this.mId, value.getDayOfMonth());
                break;
            case TimeAttribute.TIME_DAY_OF_YEAR:
                ctx.loadFloat(this.mId, value.getDayOfYear());
                break;
            // month and day-of-week are zero-based here, matching the reference
            case TimeAttribute.TIME_MONTH_VALUE:
                ctx.loadFloat(this.mId, value.getMonth() - 1);
                break;
            case TimeAttribute.TIME_DAY_OF_WEEK:
                ctx.loadFloat(this.mId, value.getDayOfWeek() - 1);
                break;
            case TimeAttribute.TIME_YEAR:
                ctx.loadFloat(this.mId, value.getYear());
                break;
            case TimeAttribute.TIME_FROM_LOAD_SEC:
                ctx.loadFloat(this.mId, (value.getMillis() - loadTime) * 1e-3);
                ctx.needsRepaint?.();
                break;
            default: break;
        }
    }

    deepToString(indent: string): string {
        return `${indent}TimeAttribute[${this.mId}] = ${this.mTimeId} ${this.mType}`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const timeId = buffer.readInt();
        const type = buffer.readShort();
        const len = buffer.readShort();
        const args: number[] = [];
        for (let i = 0; i < len; i++) args.push(buffer.readInt());
        operations.push(new TimeAttribute(id, timeId, type, args));
    }
}
