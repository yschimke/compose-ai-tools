// Easing: abstract base class for easing functions.

export abstract class Easing {
    mType = 0;

    abstract get(x: number): number;
    abstract getDiff(x: number): number;
    getType(): number { return this.mType; }

    static readonly CUBIC_STANDARD = 1;
    static readonly CUBIC_ACCELERATE = 2;
    static readonly CUBIC_DECELERATE = 3;
    static readonly CUBIC_LINEAR = 4;
    static readonly CUBIC_ANTICIPATE = 5;
    static readonly CUBIC_OVERSHOOT = 6;
    static readonly CUBIC_CUSTOM = 11;
    static readonly SPLINE_CUSTOM = 12;
    static readonly EASE_OUT_BOUNCE = 13;
    static readonly EASE_OUT_ELASTIC = 14;
}
