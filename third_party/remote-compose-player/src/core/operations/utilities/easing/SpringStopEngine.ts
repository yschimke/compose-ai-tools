// SpringStopEngine: physics-based spring animation using damped harmonic motion.

import { intBitsToFloat } from '../../Utils';

export class SpringStopEngine {
    private mDamping = 0.5;
    private mStiffness = 0;
    private mTargetPos = 0;
    private mLastTime = 0;
    private mPos = 0;
    private mV = 0;
    private mMass = 1;
    private mStopThreshold = 0;
    private mBoundaryMode = 0;

    constructor(parameters?: number[] | Float32Array) {
        if (parameters) {
            if (parameters[0] !== 0) {
                throw new Error('parameter[0] should be 0');
            }
            const dv = new DataView(new ArrayBuffer(4));
            dv.setFloat32(0, parameters[4], false);
            const boundaryMode = dv.getInt32(0, false);
            this.springParameters(1, parameters[1], parameters[2], parameters[3], boundaryMode);
        }
    }

    getTargetValue(): number { return this.mTargetPos; }
    setInitialValue(v: number): void { this.mPos = v; }
    setTargetValue(v: number): void { this.mTargetPos = v; }

    springStart(currentPos: number, target: number, currentVelocity: number): void {
        this.mTargetPos = target;
        this.mPos = currentPos;
        this.mLastTime = 0;
    }

    springParameters(mass: number, stiffness: number, damping: number,
                     stopThreshold: number, boundaryMode: number): void {
        this.mDamping = damping;
        this.mStiffness = stiffness;
        this.mMass = mass;
        this.mStopThreshold = stopThreshold;
        this.mBoundaryMode = boundaryMode;
        this.mLastTime = 0;
    }

    getVelocity(_time?: number): number { return this.mV; }

    get(time: number): number {
        this.compute(time - this.mLastTime);
        this.mLastTime = time;
        if (this.isStopped()) {
            this.mPos = this.mTargetPos;
        }
        return this.mPos;
    }

    isStopped(): boolean {
        const x = this.mPos - this.mTargetPos;
        const energy = this.mV * this.mV * this.mMass + this.mStiffness * x * x;
        const maxDef = Math.sqrt(energy / this.mStiffness);
        return maxDef <= this.mStopThreshold;
    }

    private compute(dt: number): void {
        if (dt <= 0) return;
        const k = this.mStiffness;
        const c = this.mDamping;
        const overSample = Math.trunc(1 + 9 / (Math.sqrt(this.mStiffness / this.mMass) * dt * 4));
        dt /= overSample;

        for (let i = 0; i < overSample; i++) {
            const x = this.mPos - this.mTargetPos;
            const a = (-k * x - c * this.mV) / this.mMass;
            const avgV = this.mV + a * dt / 2;
            const avgX = this.mPos + dt * avgV / 2 - this.mTargetPos;
            const a2 = (-avgX * k - avgV * c) / this.mMass;
            const dv = a2 * dt;
            const avgV2 = this.mV + dv / 2;
            this.mV += dv;
            this.mPos += avgV2 * dt;
            if (this.mBoundaryMode > 0) {
                if (this.mPos < 0 && (this.mBoundaryMode & 1) === 1) {
                    this.mPos = -this.mPos;
                    this.mV = -this.mV;
                }
                if (this.mPos > 1 && (this.mBoundaryMode & 2) === 2) {
                    this.mPos = 2 - this.mPos;
                    this.mV = -this.mV;
                }
            }
        }
    }
}
