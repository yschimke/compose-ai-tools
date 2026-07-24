// CubicEasing: cubic bezier easing with preset curves.

import { Easing } from './Easing';

const STANDARD = [0.4, 0.0, 0.2, 1.0];
const ACCELERATE = [0.4, 0.05, 0.8, 0.7];
const DECELERATE = [0.0, 0.0, 0.2, 0.95];
const LINEAR = [1.0, 1.0, 0.0, 0.0];
const ANTICIPATE = [0.36, 0.0, 0.66, -0.56];
const OVERSHOOT = [0.34, 1.56, 0.64, 1.0];

const ERROR = 0.01;
const D_ERROR = 0.0001;

export class CubicEasing extends Easing {
    mX1 = 0;
    mY1 = 0;
    mX2 = 0;
    mY2 = 0;

    constructor(typeOrX1?: number, y1?: number, x2?: number, y2?: number) {
        super();
        if (y1 !== undefined && x2 !== undefined && y2 !== undefined) {
            // CubicEasing(x1, y1, x2, y2)
            this.setup(typeOrX1!, y1, x2, y2);
        } else if (typeOrX1 !== undefined) {
            // CubicEasing(type)
            this.mType = typeOrX1;
            this.config(typeOrX1);
        } else {
            // CubicEasing() — standard
            this.setup(STANDARD[0], STANDARD[1], STANDARD[2], STANDARD[3]);
        }
    }

    config(type: number): void {
        switch (type) {
            case Easing.CUBIC_STANDARD: this.setupArr(STANDARD); break;
            case Easing.CUBIC_ACCELERATE: this.setupArr(ACCELERATE); break;
            case Easing.CUBIC_DECELERATE: this.setupArr(DECELERATE); break;
            case Easing.CUBIC_LINEAR: this.setupArr(LINEAR); break;
            case Easing.CUBIC_ANTICIPATE: this.setupArr(ANTICIPATE); break;
            case Easing.CUBIC_OVERSHOOT: this.setupArr(OVERSHOOT); break;
        }
        this.mType = type;
    }

    private setupArr(v: number[]): void {
        this.setup(v[0], v[1], v[2], v[3]);
    }

    setup(x1: number, y1: number, x2: number, y2: number): void {
        this.mX1 = x1; this.mY1 = y1; this.mX2 = x2; this.mY2 = y2;
    }

    private getX(t: number): number {
        const t1 = 1 - t;
        return this.mX1 * 3 * t1 * t1 * t + this.mX2 * 3 * t1 * t * t + t * t * t;
    }

    private getY(t: number): number {
        const t1 = 1 - t;
        return this.mY1 * 3 * t1 * t1 * t + this.mY2 * 3 * t1 * t * t + t * t * t;
    }

    get(x: number): number {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        let t = 0.5, range = 0.5;
        while (range > ERROR) {
            const tx = this.getX(t);
            range *= 0.5;
            if (tx < x) t += range; else t -= range;
        }
        const x1 = this.getX(t - range), x2 = this.getX(t + range);
        const y1 = this.getY(t - range), y2 = this.getY(t + range);
        return (y2 - y1) * (x - x1) / (x2 - x1) + y1;
    }

    getDiff(x: number): number {
        let t = 0.5, range = 0.5;
        while (range > D_ERROR) {
            const tx = this.getX(t);
            range *= 0.5;
            if (tx < x) t += range; else t -= range;
        }
        const x1 = this.getX(t - range), x2 = this.getX(t + range);
        const y1 = this.getY(t - range), y2 = this.getY(t + range);
        return (y2 - y1) / (x2 - x1);
    }
}
