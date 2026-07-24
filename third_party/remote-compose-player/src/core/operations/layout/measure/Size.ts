// Size: basic data class for width/height during layout computations.
// Matches Java Size.java.

export class Size {
    mWidth: number;
    mHeight: number;

    constructor(width = 0, height = 0) {
        this.mWidth = width;
        this.mHeight = height;
    }

    getWidth(): number { return this.mWidth; }
    setWidth(value: number): void { this.mWidth = value; }
    getHeight(): number { return this.mHeight; }
    setHeight(value: number): void { this.mHeight = value; }
    clear(): void { this.mWidth = 0; this.mHeight = 0; }
}
