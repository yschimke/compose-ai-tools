// ElasticOutCurve: elastic easing using exponential decay + sinusoidal oscillation.

import { Easing } from './Easing';

const C4 = 2 * Math.PI / 3;
const TWENTY_PI = 20 * Math.PI;
const LOG_8 = Math.log(8.0);

export class ElasticOutCurve extends Easing {
    get(x: number): number {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * C4) + 1;
    }

    getDiff(x: number): number {
        if (x < 0 || x > 1) return 0;
        return 5 * Math.pow(2, 1 - 10 * x)
            * (LOG_8 * Math.cos(TWENTY_PI * x / 3) + 2 * Math.PI * Math.sin(TWENTY_PI * x / 3))
            / 3;
    }
}
