// BounceCurve: bouncing easing function.

import { Easing } from './Easing';

const N1 = 7.5625;
const D1 = 2.75;

export class BounceCurve extends Easing {
    constructor(type: number) {
        super();
        this.mType = type;
    }

    get(x: number): number {
        let t = x;
        if (t < 0) return 0;
        if (t < 1 / D1) {
            return 1 / (1 + 1 / D1) * (N1 * t * t + t);
        } else if (t < 2 / D1) {
            t -= 1.5 / D1;
            return N1 * t * t + 0.75;
        } else if (t < 2.5 / D1) {
            t -= 2.25 / D1;
            return N1 * t * t + 0.9375;
        } else if (t <= 1) {
            t -= 2.625 / D1;
            return N1 * t * t + 0.984375;
        }
        return 1;
    }

    getDiff(x: number): number {
        if (x < 0) return 0;
        if (x < 1 / D1) {
            return 2 * N1 * x / (1 + 1 / D1) + 1 / (1 + 1 / D1);
        } else if (x < 2 / D1) {
            return 2 * N1 * (x - 1.5 / D1);
        } else if (x < 2.5 / D1) {
            return 2 * N1 * (x - 2.25 / D1);
        } else if (x <= 1) {
            return 2 * N1 * (x - 2.625 / D1);
        }
        return 0;
    }
}
