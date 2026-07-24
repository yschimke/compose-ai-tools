// StepCurve: monotonic spline-based custom easing.
// Matches Java StepCurve.java — uses MonotonicCurveFit.

import { Easing } from './Easing';
import { MonotonicCurveFit } from './MonotonicCurveFit';

export class StepCurve extends Easing {
    private mCurveFit: MonotonicCurveFit;

    constructor(params: number[] | Float32Array, offset: number, len: number) {
        super();
        this.mCurveFit = StepCurve.genSpline(params, offset, len);
    }

    private static genSpline(values: number[] | Float32Array, off: number, arrayLen: number): MonotonicCurveFit {
        const length = arrayLen * 3 - 2;
        const nLen = arrayLen - 1;
        const gap = 1.0 / nLen;
        const points: number[][] = new Array(length);
        for (let i = 0; i < length; i++) points[i] = [0];
        const time = new Float64Array(length);

        for (let i = 0; i < arrayLen; i++) {
            const v = values[i + off];
            points[i + nLen][0] = v;
            time[i + nLen] = i * gap;
            if (i > 0) {
                points[i + nLen * 2][0] = v + 1;
                time[i + nLen * 2] = i * gap + 1;

                points[i - 1][0] = v - 1 - gap;
                time[i - 1] = i * gap + -1 - gap;
            }
        }

        return new MonotonicCurveFit(time, points);
    }

    get(x: number): number {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return this.mCurveFit.getPos(x, 0);
    }

    getDiff(x: number): number {
        if (x < 0 || x > 1) return 0;
        return this.mCurveFit.getSlope(x, 0);
    }
}
