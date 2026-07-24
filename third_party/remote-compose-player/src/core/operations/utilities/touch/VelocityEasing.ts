// VelocityEasing: computes easing curves constrained by velocity.
// Port of Java VelocityEasing.java.

export interface Easing {
    get(t: number): number;
    getDiff(t: number): number;
    clone(): Easing;
}

class Stage {
    mStartV = 0;
    mStartPos = 0;
    mStartTime = 0;
    mEndV = 0;
    mEndPos = 0;
    mEndTime = 0;
    mDeltaV = 0;
    mDeltaT = 0;
    readonly mStage: number;

    constructor(n: number) {
        this.mStage = n;
    }

    setUp(startV: number, startPos: number, startTime: number,
          endV: number, endPos: number, endTime: number): void {
        this.mStartV = startV;
        this.mStartPos = startPos;
        this.mStartTime = startTime;
        this.mEndV = endV;
        this.mEndTime = endTime;
        this.mEndPos = endPos;
        this.mDeltaV = this.mEndV - this.mStartV;
        this.mDeltaT = this.mEndTime - this.mStartTime;
    }

    getPos(t: number): number {
        const dt = t - this.mStartTime;
        const pt = dt / this.mDeltaT;
        const v = this.mStartV + this.mDeltaV * pt;
        return dt * (this.mStartV + v) / 2 + this.mStartPos;
    }

    getVel(t: number): number {
        const dt = t - this.mStartTime;
        const pt = dt / (this.mEndTime - this.mStartTime);
        return this.mStartV + this.mDeltaV * pt;
    }
}

export class VelocityEasing {
    private mStartPos = 0;
    private mStartV = 0;
    private mEndPos = 0;
    private mDuration = 0;

    private mStage: Stage[] = [new Stage(1), new Stage(2), new Stage(3)];
    private mNumberOfStages = 0;
    private mEasing: Easing | null = null;
    private mEasingAdapterDistance = 0;
    private mEasingAdapterA = 0;
    private mEasingAdapterB = 0;
    private mOneDimension = true;
    private mTotalEasingDuration = 0;

    getDuration(): number {
        if (this.mEasing !== null) {
            return this.mTotalEasingDuration;
        }
        return this.mDuration;
    }

    getV(t: number): number {
        if (this.mEasing === null) {
            for (let i = 0; i < this.mNumberOfStages; i++) {
                if (this.mStage[i].mEndTime > t) {
                    return this.mStage[i].getVel(t);
                }
            }
            return 0;
        }
        const lastStages = this.mNumberOfStages - 1;
        for (let i = 0; i < lastStages; i++) {
            if (this.mStage[i].mEndTime > t) {
                return this.mStage[i].getVel(t);
            }
        }
        return this.getEasingDiff(t - this.mStage[lastStages].mStartTime);
    }

    getPos(t: number): number {
        if (this.mEasing === null) {
            for (let i = 0; i < this.mNumberOfStages; i++) {
                if (this.mStage[i].mEndTime > t) {
                    return this.mStage[i].getPos(t);
                }
            }
            return this.mEndPos;
        }
        const lastStages = this.mNumberOfStages - 1;
        for (let i = 0; i < lastStages; i++) {
            if (this.mStage[i].mEndTime > t) {
                return this.mStage[i].getPos(t);
            }
        }
        let ret = this.getEasing(t - this.mStage[lastStages].mStartTime);
        ret += this.mStage[lastStages].mStartPos;
        return ret;
    }

    config(currentPos: number, destination: number, currentVelocity: number,
           maxTime: number, maxAcceleration: number, maxVelocity: number,
           easing: Easing | null): void {
        let pos = currentPos;
        let velocity = currentVelocity;
        if (pos === destination) {
            pos += 1;
        }
        this.mStartPos = pos;
        this.mEndPos = destination;
        if (easing !== null) {
            this.mEasing = easing.clone();
        } else {
            this.mEasing = null;
        }
        const dir = Math.sign(destination - pos);
        const maxV = maxVelocity * dir;
        const maxA = maxAcceleration * dir;
        if (velocity === 0) {
            velocity = 0.0001 * dir;
        }
        this.mStartV = velocity;
        if (!this.rampDown(pos, destination, velocity, maxTime)) {
            if (!(this.mOneDimension
                && this.cruseThenRampDown(pos, destination, velocity, maxTime, maxA, maxV))) {
                if (!this.rampUpRampDown(pos, destination, velocity, maxA, maxV, maxTime)) {
                    this.rampUpCruseRampDown(pos, destination, velocity, maxA, maxV, maxTime);
                }
            }
        }
        if (this.mOneDimension) {
            this.configureEasingAdapter();
        }
    }

    private rampDown(currentPos: number, destination: number,
                     currentVelocity: number, maxTime: number): boolean {
        const timeToDestination = 2 * ((destination - currentPos) / currentVelocity);
        if (timeToDestination > 0 && timeToDestination <= maxTime) {
            this.mNumberOfStages = 1;
            this.mStage[0].setUp(currentVelocity, currentPos, 0, 0, destination, timeToDestination);
            this.mDuration = timeToDestination;
            return true;
        }
        return false;
    }

    private cruseThenRampDown(currentPos: number, destination: number,
                              currentVelocity: number, maxTime: number,
                              maxA: number, _maxV: number): boolean {
        const timeToBreak = currentVelocity / maxA;
        const brakeDist = currentVelocity * timeToBreak / 2;
        const cruseDist = destination - currentPos - brakeDist;
        const cruseTime = cruseDist / currentVelocity;
        const totalTime = cruseTime + timeToBreak;
        if (totalTime > 0 && totalTime < maxTime) {
            this.mNumberOfStages = 2;
            this.mStage[0].setUp(currentVelocity, currentPos, 0, currentVelocity, cruseDist, cruseTime);
            this.mStage[1].setUp(currentVelocity, currentPos + cruseDist, cruseTime, 0, destination, cruseTime + timeToBreak);
            this.mDuration = cruseTime + timeToBreak;
            return true;
        }
        return false;
    }

    private rampUpRampDown(currentPos: number, destination: number,
                           currentVelocity: number, maxA: number,
                           maxVelocity: number, maxTime: number): boolean {
        let peak_v = Math.sign(maxA) *
            Math.sqrt(maxA * (destination - currentPos) + currentVelocity * currentVelocity / 2);
        if (maxVelocity / peak_v > 1) {
            let t1 = (peak_v - currentVelocity) / maxA;
            let d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
            let t2 = peak_v / maxA;
            this.mNumberOfStages = 2;
            this.mStage[0].setUp(currentVelocity, currentPos, 0, peak_v, d1, t1);
            this.mStage[1].setUp(peak_v, d1, t1, 0, destination, t2 + t1);
            this.mDuration = t2 + t1;
            if (this.mDuration > maxTime) {
                return false;
            }
            if (this.mDuration < maxTime / 2) {
                t1 = this.mDuration / 2;
                t2 = t1;
                peak_v = (2 * (destination - currentPos) / t1 - currentVelocity) / 2;
                d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
                this.mNumberOfStages = 2;
                this.mStage[0].setUp(currentVelocity, currentPos, 0, peak_v, d1, t1);
                this.mStage[1].setUp(peak_v, d1, t1, 0, destination, t2 + t1);
                this.mDuration = t2 + t1;
                if (this.mDuration > maxTime) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private rampUpCruseRampDown(currentPos: number, destination: number,
                                currentVelocity: number, _maxA: number,
                                _maxV: number, maxTime: number): void {
        const t1 = maxTime / 3;
        const t2 = t1 * 2;
        const distance = destination - currentPos;
        const dt2 = t2 - t1;
        const dt3 = maxTime - t2;
        const v1 = (2 * distance - currentVelocity * t1) / (t1 + 2 * dt2 + dt3);
        this.mDuration = maxTime;
        const d1 = (currentVelocity + v1) * t1 / 2;
        const d2 = (v1 + v1) * (t2 - t1) / 2;
        this.mNumberOfStages = 3;
        this.mStage[0].setUp(currentVelocity, currentPos, 0, v1, currentPos + d1, t1);
        this.mStage[1].setUp(v1, currentPos + d1, t1, v1, currentPos + d1 + d2, t2);
        this.mStage[2].setUp(v1, currentPos + d1 + d2, t2, 0, destination, maxTime);
        this.mDuration = maxTime;
    }

    private getEasing(t: number): number {
        const gx = t * t * this.mEasingAdapterA + t * this.mEasingAdapterB;
        if (gx > 1) {
            return this.mEasingAdapterDistance;
        } else {
            return this.mEasing!.get(gx) * this.mEasingAdapterDistance;
        }
    }

    private getEasingDiff(t: number): number {
        const gx = t * t * this.mEasingAdapterA + t * this.mEasingAdapterB;
        if (gx > 1) {
            return 0;
        } else {
            return this.mEasing!.getDiff(gx)
                * this.mEasingAdapterDistance
                * (t * this.mEasingAdapterA + this.mEasingAdapterB);
        }
    }

    private configureEasingAdapter(): void {
        if (this.mEasing === null) return;
        const last = this.mNumberOfStages - 1;
        const initialVelocity = this.mStage[last].mStartV;
        const distance = this.mStage[last].mEndPos - this.mStage[last].mStartPos;
        const baseVel = this.mEasing.getDiff(0.0);
        this.mEasingAdapterB = initialVelocity / (baseVel * distance);
        this.mEasingAdapterA = 1 - this.mEasingAdapterB;
        this.mEasingAdapterDistance = distance;
        const easingDuration =
            (Math.sqrt(4 * this.mEasingAdapterA + this.mEasingAdapterB * this.mEasingAdapterB)
                - this.mEasingAdapterB)
            / (2 * this.mEasingAdapterA);
        this.mTotalEasingDuration = easingDuration + this.mStage[last].mStartTime;
    }
}
