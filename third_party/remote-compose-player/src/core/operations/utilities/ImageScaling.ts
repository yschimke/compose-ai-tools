// ImageScaling: implements scaling logic for DrawBitmapScaled.
// Matches Java ImageScaling.java.

export class ImageScaling {
    static readonly SCALE_NONE = 0;
    static readonly SCALE_INSIDE = 1;
    static readonly SCALE_FILL_WIDTH = 2;
    static readonly SCALE_FILL_HEIGHT = 3;
    static readonly SCALE_FIT = 4;
    static readonly SCALE_CROP = 5;
    static readonly SCALE_FILL_BOUNDS = 6;
    static readonly SCALE_FIXED_SCALE = 7;

    mFinalDstLeft = 0;
    mFinalDstTop = 0;
    mFinalDstRight = 0;
    mFinalDstBottom = 0;

    setup(
        srcLeft: number, srcTop: number, srcRight: number, srcBottom: number,
        dstLeft: number, dstTop: number, dstRight: number, dstBottom: number,
        scaleType: number, scaleFactor: number
    ): void {
        const sw = (srcRight - srcLeft) | 0;
        const sh = (srcBottom - srcTop) | 0;
        const width = dstRight - dstLeft;
        const height = dstBottom - dstTop;
        let dw = width | 0;
        let dh = height | 0;
        let dLeft = 0;
        let dRight = dw;
        let dTop = 0;
        let dBottom = dh;

        if (sh === 0 || sw === 0) {
            this.mFinalDstLeft = dstLeft;
            this.mFinalDstTop = dstTop;
            this.mFinalDstRight = dstRight;
            this.mFinalDstBottom = dstBottom;
            return;
        }

        switch (scaleType) {
            case ImageScaling.SCALE_NONE:
                dh = sh;
                dw = sw;
                dTop = ((height | 0) - dh) / 2 | 0;
                dBottom = dh + dTop;
                dLeft = ((width | 0) - dw) / 2 | 0;
                dRight = dw + dLeft;
                break;
            case ImageScaling.SCALE_INSIDE:
                if (dh > sh && dw > sw) {
                    dh = sh;
                    dw = sw;
                } else if (sw * height > width * sh) {
                    dh = (dw * sh / sw) | 0;
                } else {
                    dw = (dh * sw / sh) | 0;
                }
                dTop = ((height | 0) - dh) / 2 | 0;
                dBottom = dh + dTop;
                dLeft = ((width | 0) - dw) / 2 | 0;
                dRight = dw + dLeft;
                break;
            case ImageScaling.SCALE_FILL_WIDTH:
                dh = (dw * sh / sw) | 0;
                dTop = ((height | 0) - dh) / 2 | 0;
                dBottom = dh + dTop;
                dLeft = ((width | 0) - dw) / 2 | 0;
                dRight = dw + dLeft;
                break;
            case ImageScaling.SCALE_FILL_HEIGHT:
                dw = (dh * sw / sh) | 0;
                dTop = ((height | 0) - dh) / 2 | 0;
                dBottom = dh + dTop;
                dLeft = ((width | 0) - dw) / 2 | 0;
                dRight = dw + dLeft;
                break;
            case ImageScaling.SCALE_FIT:
                if (sw * height > width * sh) {
                    dh = (dw * sh / sw) | 0;
                    dTop = ((height | 0) - dh) / 2 | 0;
                    dBottom = dh + dTop;
                } else {
                    dw = (dh * sw / sh) | 0;
                    dLeft = ((width | 0) - dw) / 2 | 0;
                    dRight = dw + dLeft;
                }
                break;
            case ImageScaling.SCALE_CROP:
                if (sw * height < width * sh) {
                    dh = (dw * sh / sw) | 0;
                    dTop = ((height | 0) - dh) / 2 | 0;
                    dBottom = dh + dTop;
                } else {
                    dw = (dh * sw / sh) | 0;
                    dLeft = ((width | 0) - dw) / 2 | 0;
                    dRight = dw + dLeft;
                }
                break;
            case ImageScaling.SCALE_FILL_BOUNDS:
                // do nothing
                break;
            case ImageScaling.SCALE_FIXED_SCALE:
                dh = (sh * scaleFactor) | 0;
                dw = (sw * scaleFactor) | 0;
                dTop = ((height | 0) - dh) / 2 | 0;
                dBottom = dh + dTop;
                dLeft = ((width | 0) - dw) / 2 | 0;
                dRight = dw + dLeft;
                break;
        }

        this.mFinalDstRight = dRight + dstLeft;
        this.mFinalDstLeft = dLeft + dstLeft;
        this.mFinalDstBottom = dBottom + dstTop;
        this.mFinalDstTop = dTop + dstTop;
    }
}
