// ComponentMeasure: result of a measure pass for a single component.
// Matches Java ComponentMeasure.java.

export class ComponentMeasure {
    mId: number;
    mX: number;
    mY: number;
    mW: number;
    mH: number;
    mVisibility: number;
    private mAllowsAnimation = true;

    // Visibility constants — matches Java Component.Visibility encoding
    static readonly GONE = 0;
    static readonly VISIBLE = 1;
    static readonly INVISIBLE = 2;

    constructor(id: number, x: number, y: number, w: number, h: number,
                visibility = ComponentMeasure.VISIBLE) {
        this.mId = id;
        this.mX = x;
        this.mY = y;
        this.mW = w;
        this.mH = h;
        this.mVisibility = visibility;
    }

    getX(): number { return this.mX; }
    setX(value: number): void { this.mX = value; }
    getY(): number { return this.mY; }
    setY(value: number): void { this.mY = value; }
    getW(): number { return this.mW; }
    setW(value: number): void { this.mW = value; }
    getH(): number { return this.mH; }
    setH(value: number): void { this.mH = value; }

    getVisibility(): number { return this.mVisibility; }
    setVisibility(v: number): void { this.mVisibility = v; }

    getAllowsAnimation(): boolean { return this.mAllowsAnimation; }
    setAllowsAnimation(v: boolean): void { this.mAllowsAnimation = v; }

    copyFrom(m: ComponentMeasure): void {
        this.mX = m.mX; this.mY = m.mY;
        this.mW = m.mW; this.mH = m.mH;
        this.mVisibility = m.mVisibility;
    }

    same(m: ComponentMeasure): boolean {
        return this.mX === m.mX && this.mY === m.mY
            && this.mW === m.mW && this.mH === m.mH
            && this.mVisibility === m.mVisibility;
    }

    isGone(): boolean {
        if ((this.mVisibility >> 4) > 0) {
            return (this.mVisibility & 16) === 16;  // OVERRIDE_GONE
        }
        return this.mVisibility === ComponentMeasure.GONE;
    }

    isVisible(): boolean {
        if ((this.mVisibility >> 4) > 0) {
            return (this.mVisibility & 32) === 32;  // OVERRIDE_VISIBLE
        }
        return this.mVisibility === ComponentMeasure.VISIBLE;
    }

    isInvisible(): boolean {
        if ((this.mVisibility >> 4) > 0) {
            return (this.mVisibility & 64) === 64;  // OVERRIDE_INVISIBLE
        }
        return this.mVisibility === ComponentMeasure.INVISIBLE;
    }

    clearVisibilityOverride(): void {
        this.mVisibility = this.mVisibility & 15;
    }

    addVisibilityOverride(value: number): void {
        // Match Java: clear override first, then add
        let v = this.mVisibility & 15;
        v += value;
        if ((v & 128) === 128) {  // CLEAR_OVERRIDE
            v = v & 15;
        }
        this.mVisibility = v;
    }
}
