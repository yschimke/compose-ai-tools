// TouchExpression: handles touch interactions on canvas.
// Port of Java TouchExpression.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';
import { RemoteContext as RC } from '../RemoteContext';
import type { TouchListener } from '../TouchListener';
import { isNaNBits, idFromBits, intBitsToFloat, floatToRawIntBits } from './Utils';
import { AnimatedFloatExpression } from './utilities/AnimatedFloatExpression';
import { VelocityEasing } from './utilities/touch/VelocityEasing';

export class TouchExpression extends Operation implements TouchListener {
    static readonly OP_CODE = 157;

    static readonly STOP_GENTLY = 0;
    static readonly STOP_INSTANTLY = 1;
    static readonly STOP_ENDS = 2;
    static readonly STOP_NOTCHES_EVEN = 3;
    static readonly STOP_NOTCHES_PERCENTS = 4;
    static readonly STOP_NOTCHES_ABSOLUTE = 5;
    static readonly STOP_ABSOLUTE_POS = 6;
    static readonly STOP_NOTCHES_SINGLE_EVEN = 7;

    private mId: number;
    // Expression / spec arrays as raw float32 int bits (operators/vars/array ids
    // are NaN-with-payload). Bits survive engines that canonicalize NaN payloads.
    private mSrcExp: Int32Array;
    private mDefValueBits: number;
    private mOutDefValue: number;
    private mMinBits: number;
    private mMaxBits: number;
    private mOutMin: number;
    private mOutMax: number;
    private mMode: number;
    private mStopMode: number;
    private mStopSpec: Int32Array;
    private mOutStopSpec: Float32Array;
    private mTouchEffects: number;
    private mVelocityId: number;
    private mMaxTime = 1;
    private mMaxAcceleration = 5;
    private mMaxVelocity = 7;
    private mWrapMode = false;

    // Runtime state
    private mTouchDown = false;
    private mEasingToStop = false;
    private mValue = 0;
    private mValueAtDown = 0;
    private mDownTouchValue = 0;
    private mCurrentValue = NaN;
    private mUnmodified = true;
    private mLastValue = 0;
    private mMaxAtDown = NaN;
    private mMinAtDown = NaN;
    private mTouchUpTime = 0;
    private mLastChange = NaN;
    private mLastCalculatedValue = NaN;

    // Resolved expression token bits fed to AnimatedFloatExpression.eval.
    private mPreCalcValue: Int32Array | null = null;
    private mExp = new AnimatedFloatExpression();
    private mEasyTouch = new VelocityEasing();

    // Component bounds
    private mScrLeft = 0;
    private mScrRight = 0;
    private mScrTop = 0;
    private mScrBottom = 0;
    private mComponent: any = null;

    constructor(
        id: number, exp: Int32Array, defValueBits: number,
        minBits: number, maxBits: number, touchEffects: number,
        velocityId: number, stopMode: number,
        stopSpec: Int32Array, easingSpec: Float32Array | null
    ) {
        super();
        this.mId = id;
        this.mSrcExp = exp;
        this.mDefValueBits = defValueBits;
        this.mOutDefValue = isNaNBits(defValueBits) ? 0 : intBitsToFloat(defValueBits);
        this.mMode = TouchExpression.STOP_ABSOLUTE_POS === stopMode ? 1 : 0;
        this.mMaxBits = maxBits;
        this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
        this.mTouchEffects = touchEffects;
        this.mVelocityId = velocityId;
        this.mStopSpec = stopSpec;
        this.mOutStopSpec = new Float32Array(stopSpec.length);
        for (let i = 0; i < stopSpec.length; i++) {
            this.mOutStopSpec[i] = isNaNBits(stopSpec[i]) ? 0 : intBitsToFloat(stopSpec[i]);
        }

        if (isNaNBits(minBits) && idFromBits(minBits) === 0) {
            this.mWrapMode = true;
            this.mMinBits = floatToRawIntBits(0);
            this.mOutMin = 0;
        } else {
            this.mMinBits = minBits;
            this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
        }

        this.mStopMode = stopMode;

        if (easingSpec !== null && easingSpec.length >= 4) {
            // Check if first value has raw bits == 0 (i.e., +0.0)
            if (easingSpec[0] === 0) {
                this.mMaxTime = easingSpec[1];
                this.mMaxAcceleration = easingSpec[2];
                this.mMaxVelocity = easingSpec[3];
            }
        }
    }

    write(_buffer: WireBuffer): void { /* stub */ }

    registerListening(context: RemoteContext): void {
        if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
        if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
        if (isNaNBits(this.mDefValueBits)) context.listensTo(idFromBits(this.mDefValueBits), this);
        if (this.mComponent === null) {
            context.addTouchListener(this);
        }
        for (const b of this.mSrcExp) {
            if (isNaNBits(b)
                && !AnimatedFloatExpression.isMathOperatorBits(b)
                && !this.isDataVariableBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
        for (const b of this.mStopSpec) {
            if (isNaNBits(b)) {
                context.listensTo(idFromBits(b), this);
            }
        }
    }

    private isDataVariableBits(b: number): boolean {
        if (!isNaNBits(b)) return false;
        const id = idFromBits(b);
        return (id & 0x700000) === 0x200000;
    }

    updateVariables(context: RemoteContext): void {
        if (this.mPreCalcValue === null || this.mPreCalcValue.length !== this.mSrcExp.length) {
            this.mPreCalcValue = new Int32Array(this.mSrcExp.length);
        }
        if (this.mOutStopSpec.length !== this.mStopSpec.length) {
            this.mOutStopSpec = new Float32Array(this.mStopSpec.length);
        }
        if (isNaNBits(this.mMaxBits)) {
            this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
        }
        if (isNaNBits(this.mMinBits)) {
            this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
        }
        if (isNaNBits(this.mDefValueBits)) {
            this.mOutDefValue = context.getFloat(idFromBits(this.mDefValueBits));
        }

        for (let i = 0; i < this.mSrcExp.length; i++) {
            const b = this.mSrcExp[i];
            if (isNaNBits(b)
                && !AnimatedFloatExpression.isMathOperatorBits(b)
                && !this.isDataVariableBits(b)) {
                // Resolve variable to a literal float and store its bits.
                this.mPreCalcValue[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
            } else {
                this.mPreCalcValue[i] = b;
            }
        }
        for (let i = 0; i < this.mStopSpec.length; i++) {
            const b = this.mStopSpec[i];
            if (isNaNBits(b)) {
                this.mOutStopSpec[i] = context.getFloat(idFromBits(b));
            } else {
                this.mOutStopSpec[i] = intBitsToFloat(b);
            }
        }
    }

    private wrap(pos: number): number {
        if (!this.mWrapMode) return pos;
        pos = pos % this.mOutMax;
        if (pos < 0) pos += this.mOutMax;
        return pos;
    }

    private getStopPosition(pos: number, slope: number): number {
        let target = pos + slope / 2;
        if (this.mWrapMode) {
            pos = this.wrap(pos);
            target = pos + slope / 2;
        } else {
            target = Math.max(Math.min(target, this.mOutMax), this.mOutMin);
        }
        const min = this.mWrapMode ? 0 : this.mOutMin;

        switch (this.mStopMode) {
            case TouchExpression.STOP_ENDS:
                return ((pos + slope) > (this.mOutMax + min) / 2) ? this.mOutMax : min;
            case TouchExpression.STOP_INSTANTLY:
                return pos;
            case TouchExpression.STOP_NOTCHES_EVEN:
            case TouchExpression.STOP_NOTCHES_SINGLE_EVEN: {
                const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
                const notchMax = this.mOutStopSpec.length > 1 ? this.mOutStopSpec[1] : this.mOutMax;
                const step = (notchMax - min) / evenSpacing;
                let notch = min + step * Math.trunc(0.5 + (target - this.mOutMin) / step);
                if (this.mStopMode === TouchExpression.STOP_NOTCHES_SINGLE_EVEN) {
                    notch = Math.min(this.mMaxAtDown, notch);
                    notch = Math.max(this.mMinAtDown, notch);
                }
                if (!this.mWrapMode) {
                    notch = Math.max(Math.min(notch, this.mOutMax), min);
                }
                return notch;
            }
            case TouchExpression.STOP_NOTCHES_PERCENTS: {
                let minPos = min;
                let minPosDist = Math.abs(this.mOutMin - target);
                for (let i = 0; i < this.mStopSpec.length; i++) {
                    const p = this.mOutMin + this.mOutStopSpec[i] * (this.mOutMax - this.mOutMin);
                    const dist = Math.abs(p - target);
                    if (minPosDist > dist) {
                        minPosDist = dist;
                        minPos = p;
                    }
                }
                return minPos;
            }
            case TouchExpression.STOP_NOTCHES_ABSOLUTE: {
                let minPos = this.mOutMin;
                let minPosDist = Math.abs(this.mOutMin - target);
                for (let i = 0; i < this.mStopSpec.length; i++) {
                    const dist = Math.abs(this.mOutStopSpec[i] - target);
                    if (minPosDist > dist) {
                        minPosDist = dist;
                        minPos = this.mOutStopSpec[i];
                    }
                }
                return minPos;
            }
            case TouchExpression.STOP_GENTLY:
            default:
                return target;
        }
    }

    private haptic(context: RemoteContext): void {
        let touch = this.mTouchEffects & 0xFF;
        if ((this.mTouchEffects & (1 << 15)) !== 0) {
            touch = context.getInteger(this.mTouchEffects & 0x7FFF);
        }
        context.hapticEffect(touch);
    }

    private crossNotchCheck(context: RemoteContext): void {
        const prev = this.mLastValue;
        const next = this.mCurrentValue;
        this.mLastValue = next;
        const min = this.mWrapMode ? 0 : this.mOutMin;
        const max = this.mOutMax;

        switch (this.mStopMode) {
            case TouchExpression.STOP_ENDS:
                if (((min - prev) * (max - prev) < 0) !== ((min - next) * (max - next) < 0)) {
                    this.haptic(context);
                }
                break;
            case TouchExpression.STOP_INSTANTLY:
                this.haptic(context);
                break;
            case TouchExpression.STOP_NOTCHES_EVEN:
            case TouchExpression.STOP_NOTCHES_SINGLE_EVEN: {
                const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
                const step = (max - min) / evenSpacing;
                if (Math.trunc((prev - min) / step) !== Math.trunc((next - min) / step)) {
                    this.haptic(context);
                }
                break;
            }
            case TouchExpression.STOP_NOTCHES_PERCENTS:
                for (let i = 0; i < this.mStopSpec.length; i++) {
                    const p = this.mOutMin + this.mOutStopSpec[i] * (this.mOutMax - this.mOutMin);
                    if ((prev - p) * (next - p) < 0) this.haptic(context);
                }
                break;
            case TouchExpression.STOP_NOTCHES_ABSOLUTE:
                for (let i = 0; i < this.mStopSpec.length; i++) {
                    const p = this.mOutStopSpec[i];
                    if ((prev - p) * (next - p) < 0) this.haptic(context);
                }
                break;
        }
    }

    private updateBounds(context: RemoteContext): void {
        const comp = this.mComponent;
        if (comp !== null) {
            if (context.getTouchVersion() === 1) { // FIX_TOUCH_EVENT
                this.mScrLeft = 0;
                this.mScrTop = 0;
                this.mScrRight = comp.getWidth();
                this.mScrBottom = comp.getHeight();
            } else {
                let x = comp.getX();
                let y = comp.getY();
                const w = comp.getWidth();
                const h = comp.getHeight();
                let parent = comp.getParent();
                while (parent !== null) {
                    x += parent.getX();
                    y += parent.getY();
                    parent = parent.getParent();
                }
                this.mScrLeft = x;
                this.mScrTop = y;
                this.mScrRight = w + x;
                this.mScrBottom = h + y;
            }
        }
    }

    apply(context: RemoteContext): void {
        this.updateBounds(context);
        if (this.mUnmodified) {
            this.mCurrentValue = this.mOutDefValue;
            context.loadFloat(this.mId, this.wrap(this.mCurrentValue));
            return;
        }
        if (this.mEasingToStop) {
            const time = context.getAnimationTime() - this.mTouchUpTime;
            let value = this.mEasyTouch.getPos(time);
            this.mCurrentValue = value;
            if (this.mWrapMode) {
                value = this.wrap(value);
            } else {
                value = Math.min(Math.max(value, this.mOutMin), this.mOutMax);
            }
            context.loadFloat(this.mId, value);
            if (this.mEasyTouch.getDuration() < time) {
                this.mEasingToStop = false;
            }
            this.crossNotchCheck(context);
            context.needsRepaint();
            return;
        }
        if (this.mTouchDown && this.mPreCalcValue) {
            let value = this.mExp.eval(
                context.getCollectionsAccess(), this.mPreCalcValue, this.mPreCalcValue.length);
            if (this.mMode === 0) {
                value = this.mValueAtDown + (value - this.mDownTouchValue);
            }
            if (this.mWrapMode) {
                value = this.wrap(value);
            } else {
                value = Math.min(Math.max(value, this.mOutMin), this.mOutMax);
            }
            this.mCurrentValue = value;
        }
        this.crossNotchCheck(context);
        if (this.mStopMode === TouchExpression.STOP_NOTCHES_SINGLE_EVEN) {
            this.mCurrentValue = Math.min(this.mMaxAtDown, this.mCurrentValue);
            this.mCurrentValue = Math.max(this.mMinAtDown, this.mCurrentValue);
        }
        if (!this.mWrapMode) {
            if (!Number.isNaN(this.mOutMin)) this.mCurrentValue = Math.max(this.mCurrentValue, this.mOutMin);
            if (!Number.isNaN(this.mOutMax)) this.mCurrentValue = Math.min(this.mCurrentValue, this.mOutMax);
        }
        context.loadFloat(this.mId, this.wrap(this.mCurrentValue));
    }

    touchDown(context: RemoteContext, x: number, y: number): void {
        // Only check bounds when inside a component (bounds are set by updateBounds).
        // For canvas-level touch expressions (mComponent === null), accept all events.
        if (this.mComponent !== null &&
            !(x >= this.mScrLeft && x <= this.mScrRight && y >= this.mScrTop && y <= this.mScrBottom)) {
            return;
        }
        this.mEasingToStop = false;
        this.mTouchDown = true;
        this.mUnmodified = false;
        if (this.mMode === 0 && this.mPreCalcValue) {
            this.mValueAtDown = context.getFloat(this.mId);
            if (TouchExpression.STOP_NOTCHES_SINGLE_EVEN === this.mStopMode) {
                const min = this.mWrapMode ? 0 : this.mOutMin;
                const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
                const notchMax = this.mOutStopSpec.length > 1 ? this.mOutStopSpec[1] : this.mOutMax;
                const step = (notchMax - min) / evenSpacing;
                const notch = this.mValueAtDown;
                this.mMaxAtDown = notch + step;
                this.mMinAtDown = notch - step;
            }
            this.mDownTouchValue = this.mExp.eval(
                context.getCollectionsAccess(), this.mPreCalcValue, this.mPreCalcValue.length);
        }
        context.needsRepaint();
    }

    touchDrag(context: RemoteContext, _x: number, _y: number): void {
        if (!this.mTouchDown) return;
        this.apply(context);
        context.needsRepaint();
    }

    touchUp(context: RemoteContext, x: number, y: number, dx: number, dy: number): void {
        if (!this.mTouchDown) return;
        this.mTouchDown = false;
        const dt = 0.0001;
        if (this.mStopMode === TouchExpression.STOP_INSTANTLY) return;
        if (!this.mPreCalcValue) return;

        const v = this.mExp.eval(
            context.getCollectionsAccess(), this.mPreCalcValue, this.mPreCalcValue.length);

        // Compute slope by perturbing touch position. mPreCalcValue holds float
        // bits; mutate the float value then re-encode back to bits.
        if (context.getTouchVersion() === 1) { // FIX_TOUCH_EVENT
            for (let i = 0; i < this.mSrcExp.length; i++) {
                if (isNaNBits(this.mSrcExp[i])) {
                    const id = idFromBits(this.mSrcExp[i]);
                    if (id === RC.ID_TOUCH_POS_X) {
                        this.mPreCalcValue[i] = floatToRawIntBits(intBitsToFloat(this.mPreCalcValue[i]) + dx * dt);
                    } else if (id === RC.ID_TOUCH_POS_Y) {
                        this.mPreCalcValue[i] = floatToRawIntBits(intBitsToFloat(this.mPreCalcValue[i]) + dy * dt);
                    }
                }
            }
        } else {
            for (let i = 0; i < this.mSrcExp.length; i++) {
                if (isNaNBits(this.mSrcExp[i])) {
                    const id = idFromBits(this.mSrcExp[i]);
                    if (id === RC.ID_TOUCH_POS_X) {
                        this.mPreCalcValue[i] = floatToRawIntBits(x + dx * dt);
                    } else if (id === RC.ID_TOUCH_POS_Y) {
                        this.mPreCalcValue[i] = floatToRawIntBits(y + dy * dt);
                    }
                }
            }
        }

        const vdt = this.mExp.eval(
            context.getCollectionsAccess(), this.mPreCalcValue, this.mPreCalcValue.length);
        const slope = (vdt - v) / dt;
        const value = context.getFloat(this.mId);

        this.mTouchUpTime = context.getAnimationTime();
        const dest = this.getStopPosition(value, slope);
        const time = Math.min(2, this.mMaxTime * Math.abs(dest - value) / (2 * this.mMaxVelocity));
        this.mEasyTouch.config(value, dest, slope, time, this.mMaxAcceleration, this.mMaxVelocity, null);
        this.mEasingToStop = true;
        context.needsRepaint();
    }

    setComponent(component: any): void {
        this.mComponent = component;
        if (this.mComponent !== null) {
            try {
                const root = this.mComponent.getRoot();
                root.setHasTouchListeners(true);
            } catch (_e) {
                // ignore
            }
        }
    }

    deepToString(indent: string): string { return `${indent}TouchExpression(${this.mId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const id = buffer.readInt();
        const startValue = buffer.readInt();
        const min = buffer.readInt();
        const max = buffer.readInt();
        const velocityId = buffer.readInt();
        const touchEffects = buffer.readInt();
        const len = buffer.readInt();
        const valueLen = len & 0xFFFF;
        const exp = new Int32Array(valueLen);
        for (let i = 0; i < valueLen; i++) exp[i] = buffer.readInt();
        const stopLogic = buffer.readInt();
        const stopLen = stopLogic & 0xFFFF;
        const stopMode = stopLogic >> 16;
        const stopsData = new Int32Array(stopLen);
        for (let i = 0; i < stopLen; i++) stopsData[i] = buffer.readInt();
        const easingLen = buffer.readInt();
        const easingData = easingLen > 0 ? new Float32Array(easingLen) : null;
        for (let i = 0; i < easingLen; i++) easingData![i] = buffer.readFloat();
        operations.push(new TouchExpression(
            id, exp, startValue, min, max, touchEffects,
            velocityId, stopMode, stopsData, easingData));
    }
}
