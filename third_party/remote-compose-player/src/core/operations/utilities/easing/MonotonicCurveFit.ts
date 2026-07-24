// MonotonicCurveFit: monotonic cubic Hermite spline interpolation.
// Matches Java MonotonicCurveFit.java.

export class MonotonicCurveFit {
    private mT: Float64Array;
    private mY: Float64Array[]; // mY[i] is the values at time point i (array of dim values)
    private mTangent: Float64Array[]; // same shape as mY
    private mExtrapolate = true;
    private mDim: number;
    private mSlopeTemp: Float64Array;

    constructor(time: Float64Array | number[], y: number[][]) {
        const n = time.length;
        const dim = y[0].length;
        this.mDim = dim;
        this.mSlopeTemp = new Float64Array(dim);
        this.mT = time instanceof Float64Array ? time : Float64Array.from(time);

        // Store y values
        this.mY = new Array(n);
        for (let i = 0; i < n; i++) {
            this.mY[i] = Float64Array.from(y[i]);
        }

        // Compute slopes and tangents
        const slope: Float64Array[] = new Array(n - 1);
        for (let i = 0; i < n - 1; i++) slope[i] = new Float64Array(dim);
        const tangent: Float64Array[] = new Array(n);
        for (let i = 0; i < n; i++) tangent[i] = new Float64Array(dim);

        for (let j = 0; j < dim; j++) {
            for (let i = 0; i < n - 1; i++) {
                const dt = this.mT[i + 1] - this.mT[i];
                slope[i][j] = (this.mY[i + 1][j] - this.mY[i][j]) / dt;
                if (i === 0) {
                    tangent[i][j] = slope[i][j];
                } else {
                    tangent[i][j] = (slope[i - 1][j] + slope[i][j]) * 0.5;
                }
            }
            tangent[n - 1][j] = slope[n - 2][j];
        }

        // Monotonicity correction
        for (let i = 0; i < n - 1; i++) {
            for (let j = 0; j < dim; j++) {
                if (slope[i][j] === 0) {
                    tangent[i][j] = 0;
                    tangent[i + 1][j] = 0;
                } else {
                    const a = tangent[i][j] / slope[i][j];
                    const b = tangent[i + 1][j] / slope[i][j];
                    const h = Math.hypot(a, b);
                    if (h > 9.0) {
                        const t = 3.0 / h;
                        tangent[i][j] = t * a * slope[i][j];
                        tangent[i + 1][j] = t * b * slope[i][j];
                    }
                }
            }
        }

        this.mTangent = tangent;
    }

    /** Get position of curve j at time t */
    getPos(t: number, j: number): number {
        const n = this.mT.length;
        if (this.mExtrapolate) {
            if (t <= this.mT[0]) {
                return this.mY[0][j] + (t - this.mT[0]) * this.getSlope(this.mT[0], j);
            }
            if (t >= this.mT[n - 1]) {
                return this.mY[n - 1][j] + (t - this.mT[n - 1]) * this.getSlope(this.mT[n - 1], j);
            }
        } else {
            if (t <= this.mT[0]) return this.mY[0][j];
            if (t >= this.mT[n - 1]) return this.mY[n - 1][j];
        }

        for (let i = 0; i < n - 1; i++) {
            if (t === this.mT[i]) return this.mY[i][j];
            if (t < this.mT[i + 1]) {
                const h = this.mT[i + 1] - this.mT[i];
                const x = (t - this.mT[i]) / h;
                return MonotonicCurveFit.interpolate(
                    h, x, this.mY[i][j], this.mY[i + 1][j],
                    this.mTangent[i][j], this.mTangent[i + 1][j]
                );
            }
        }
        return 0;
    }

    /** Get slope of curve j at time t */
    getSlope(t: number, j: number): number {
        const n = this.mT.length;
        if (t < this.mT[0]) t = this.mT[0];
        else if (t >= this.mT[n - 1]) t = this.mT[n - 1];

        for (let i = 0; i < n - 1; i++) {
            if (t <= this.mT[i + 1]) {
                const h = this.mT[i + 1] - this.mT[i];
                const x = (t - this.mT[i]) / h;
                return MonotonicCurveFit.diff(
                    h, x, this.mY[i][j], this.mY[i + 1][j],
                    this.mTangent[i][j], this.mTangent[i + 1][j]
                ) / h;
            }
        }
        return 0;
    }

    /** Cubic Hermite spline interpolation */
    private static interpolate(h: number, x: number, y1: number, y2: number, t1: number, t2: number): number {
        const x2 = x * x;
        const x3 = x2 * x;
        return -2 * x3 * y2 + 3 * x2 * y2 + 2 * x3 * y1 - 3 * x2 * y1 + y1
            + h * t2 * x3 + h * t1 * x3 - h * t2 * x2 - 2 * h * t1 * x2 + h * t1 * x;
    }

    /** Cubic Hermite spline derivative */
    private static diff(h: number, x: number, y1: number, y2: number, t1: number, t2: number): number {
        const x2 = x * x;
        return -6 * x2 * y2 + 6 * x * y2 + 6 * x2 * y1 - 6 * x * y1
            + 3 * h * t2 * x2 + 3 * h * t1 * x2 - 2 * h * t2 * x - 4 * h * t1 * x + h * t1;
    }
}
