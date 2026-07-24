// FloatAnimation: orchestrates float animation timing with easing curves.

import { Easing } from './Easing';
import { CubicEasing } from './CubicEasing';
import { BounceCurve } from './BounceCurve';
import { ElasticOutCurve } from './ElasticOutCurve';
import { StepCurve } from './StepCurve';

// Helper to reinterpret float bits as int
const _dv = new DataView(new ArrayBuffer(4));
function floatToRawIntBits(v: number): number {
    _dv.setFloat32(0, v, false);
    return _dv.getInt32(0, false);
}

export class FloatAnimation extends Easing {
    private mSpec: Float32Array | number[];
    mEasingCurve!: Easing;

    private mDuration = 1;
    private mWrap = NaN;
    private mInitialValue = NaN;
    private mTargetValue = NaN;
    private mDirectionalSnap = 0;
    mOffset = 0;
    private mPropagate = false;

    constructor(description: Float32Array | number[]) {
        super();
        this.mType = Easing.CUBIC_STANDARD;
        this.mSpec = description;
        this.setAnimationDescription(description);
    }

    setAnimationDescription(description: Float32Array | number[]): void {
        this.mSpec = description;
        this.mDuration = this.mSpec.length === 0 ? 1 : this.mSpec[0];
        let len = 0;
        if (this.mSpec.length > 1) {
            const numType = floatToRawIntBits(this.mSpec[1]);
            this.mType = numType & 0xFF;
            const wrap = ((numType >> 8) & 0x1) > 0;
            const init = ((numType >> 8) & 0x2) > 0;
            this.mDirectionalSnap = (numType >> 10) & 0x3;
            this.mPropagate = ((numType >> 12) & 0x1) > 0;
            len = (numType >> 16) & 0xFFFF;
            let off = 2 + len;
            if (init) {
                this.mInitialValue = this.mSpec[off++];
            }
            if (wrap) {
                this.mWrap = this.mSpec[off];
            }
        }
        this.create(this.mType, description, 2, len);
    }

    private create(type: number, params: Float32Array | number[] | null,
                   offset: number, len: number): void {
        switch (type) {
            case Easing.CUBIC_STANDARD:
            case Easing.CUBIC_ACCELERATE:
            case Easing.CUBIC_DECELERATE:
            case Easing.CUBIC_LINEAR:
            case Easing.CUBIC_ANTICIPATE:
            case Easing.CUBIC_OVERSHOOT:
                this.mEasingCurve = new CubicEasing(type);
                break;
            case Easing.CUBIC_CUSTOM:
                if (params) {
                    this.mEasingCurve = new CubicEasing(
                        params[offset], params[offset + 1], params[offset + 2], params[offset + 3]);
                } else {
                    this.mEasingCurve = new CubicEasing();
                }
                break;
            case Easing.EASE_OUT_BOUNCE:
                this.mEasingCurve = new BounceCurve(type);
                break;
            case Easing.EASE_OUT_ELASTIC:
                this.mEasingCurve = new ElasticOutCurve();
                break;
            case Easing.SPLINE_CUSTOM:
                if (params) {
                    this.mEasingCurve = new StepCurve(params, offset, len);
                } else {
                    this.mEasingCurve = new CubicEasing();
                }
                break;
            default:
                this.mEasingCurve = new CubicEasing();
                break;
        }
    }

    getDuration(): number { return this.mDuration; }

    setInitialValue(value: number): void {
        if (Number.isNaN(this.mWrap)) {
            this.mInitialValue = value;
        } else {
            this.mInitialValue = value % this.mWrap;
        }
        this.setScaleOffset();
    }

    private static wrap(wrapVal: number, value: number): number {
        value = value % wrapVal;
        if (value < 0) value += wrapVal;
        return value;
    }

    private wrapDistance(wrapVal: number, from: number, to: number): number {
        let delta = (to - from) % 360;
        if (delta < -wrapVal / 2) delta += wrapVal;
        else if (delta > wrapVal / 2) delta -= wrapVal;
        return delta;
    }

    setTargetValue(value: number): void {
        this.mTargetValue = value;
        if (!Number.isNaN(this.mWrap)) {
            this.mInitialValue = FloatAnimation.wrap(this.mWrap, this.mInitialValue);
            this.mTargetValue = FloatAnimation.wrap(this.mWrap, this.mTargetValue);
            if (Number.isNaN(this.mInitialValue)) {
                this.mInitialValue = this.mTargetValue;
            }
            const dist = this.wrapDistance(this.mWrap, this.mInitialValue, this.mTargetValue);
            if (dist > 0 && this.mTargetValue < this.mInitialValue) {
                this.mTargetValue += this.mWrap;
            } else if (dist < 0 && this.mDirectionalSnap !== 0) {
                if (this.mDirectionalSnap === 1 && this.mTargetValue > this.mInitialValue) {
                    this.mInitialValue = this.mTargetValue;
                }
                if (this.mDirectionalSnap === 2 && this.mTargetValue < this.mInitialValue) {
                    this.mInitialValue = this.mTargetValue;
                }
                this.mTargetValue -= this.mWrap;
            }
        }
        this.setScaleOffset();
    }

    getTargetValue(): number { return this.mTargetValue; }
    getInitialValue(): number { return this.mInitialValue; }
    isPropagate(): boolean { return this.mPropagate; }

    private setScaleOffset(): void {
        if (!Number.isNaN(this.mInitialValue) && !Number.isNaN(this.mTargetValue)) {
            this.mOffset = this.mInitialValue;
        } else {
            this.mOffset = 0;
        }
    }

    get(t: number): number {
        if (this.mDirectionalSnap === 1 && this.mTargetValue < this.mInitialValue) {
            this.mInitialValue = this.mTargetValue;
            return this.mTargetValue;
        }
        if (this.mDirectionalSnap === 2 && this.mTargetValue > this.mInitialValue) {
            this.mInitialValue = this.mTargetValue;
            return this.mTargetValue;
        }
        return this.mEasingCurve.get(t / this.mDuration)
            * (this.mTargetValue - this.mInitialValue) + this.mInitialValue;
    }

    getDiff(t: number): number {
        return this.mEasingCurve.getDiff(t / this.mDuration)
            * (this.mTargetValue - this.mInitialValue);
    }
}
