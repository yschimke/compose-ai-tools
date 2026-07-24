// LayoutManager: abstract base class for layout managers (Box, Row, Column, Canvas).
// Port of Java LayoutManager.java — handles measurement with fill/wrap/exact sizing.

import { LayoutComponent } from '../LayoutComponent';
import type { PaintContext } from '../../../PaintContext';
import type { RemoteContext } from '../../../RemoteContext';
import type { MeasurePass } from '../measure/MeasurePass';
import { Size } from '../measure/Size';
import { WidthModifier, HeightModifier, ScrollModifier } from '../modifiers/ModifierOperations';
import { isNaNBits, idFromBits } from '../../Utils';

export abstract class LayoutManager extends LayoutComponent {
    protected mCachedWrapSize = new Size();

    measure(context: PaintContext, minWidth: number, maxWidth: number,
            minHeight: number, maxHeight: number, measure: MeasurePass): void {
        const selfMeasure = measure.get(this);
        const padding_w = this.mPaddingLeft + this.mPaddingRight;
        const padding_h = this.mPaddingTop + this.mPaddingBottom;

        const wMod = this.getWidthModifier();
        const hMod = this.getHeightModifier();

        // Determine width
        let w: number;
        if (wMod && (wMod.getType() === WidthModifier.EXACT || wMod.getType() === WidthModifier.EXACT_DP)) {
            w = wMod.getValue() + padding_w;
        } else if (wMod && wMod.getType() === WidthModifier.FILL) {
            w = maxWidth;
        } else {
            // WRAP or other — compute from children
            w = maxWidth; // temporary, will be adjusted by computeWrapSize
        }

        // Determine height
        let h: number;
        if (hMod && (hMod.getType() === HeightModifier.EXACT || hMod.getType() === HeightModifier.EXACT_DP)) {
            h = hMod.getValue() + padding_h;
        } else if (hMod && hMod.getType() === HeightModifier.FILL) {
            h = maxHeight;
        } else {
            h = maxHeight;
        }

        selfMeasure.setW(w);
        selfMeasure.setH(h);

        const horizontalWrap = wMod?.getType() === WidthModifier.WRAP;
        const verticalWrap = hMod?.getType() === HeightModifier.WRAP;

        if (horizontalWrap || verticalWrap) {
            this.mCachedWrapSize.clear();
            this.computeWrapSize(context, minWidth, maxWidth - padding_w, minHeight,
                maxHeight - padding_h, horizontalWrap, verticalWrap, measure, this.mCachedWrapSize);

            if (horizontalWrap) {
                w = this.mCachedWrapSize.getWidth() + padding_w;
                // Apply WidthIn constraints
                const wIn = this.getWidthInModifier();
                if (wIn) {
                    if (wIn.getMin() >= 0) w = Math.max(w, wIn.getMin());
                    if (wIn.getMax() >= 0) w = Math.min(w, wIn.getMax());
                }
                w = Math.min(w, maxWidth);
            }
            if (verticalWrap) {
                h = this.mCachedWrapSize.getHeight() + padding_h;
                const hIn = this.getHeightInModifier();
                if (hIn) {
                    if (hIn.getMin() >= 0) h = Math.max(h, hIn.getMin());
                    if (hIn.getMax() >= 0) h = Math.min(h, hIn.getMax());
                }
                h = Math.min(h, maxHeight);
            }

            selfMeasure.setW(w);
            selfMeasure.setH(h);
        }

        // Scroll-aware measurement (matching Java LayoutManager.measure_v1_1_0):
        // Re-measure children with unbounded dimension on the scroll axis to discover
        // full content size, then store scroll dimensions for variable writing.
        const scrollMod = this.getScrollModifier();
        if (scrollMod) {
            const isVertical = (scrollMod.getDirection() === ScrollModifier.VERTICAL);
            const hostW = Math.min(w, maxWidth) - padding_w;
            const hostH = Math.min(h, maxHeight) - padding_h;
            const unboundW = isVertical ? hostW : 1e9;
            const unboundH = isVertical ? 1e9 : hostH;

            this.mCachedWrapSize.clear();
            this.computeWrapSize(context, 0, unboundW, 0, unboundH,
                true, true, measure, this.mCachedWrapSize);

            if (isVertical) {
                this.mScrollHostDimension = hostH;
                this.mScrollContentDimension = this.mCachedWrapSize.getHeight();
            } else {
                this.mScrollHostDimension = hostW;
                this.mScrollContentDimension = this.mCachedWrapSize.getWidth();
            }

            // Re-measure children with unbounded content dimension
            const childMaxW = isVertical ? (w - padding_w) : Math.max(w - padding_w, this.mScrollContentDimension);
            const childMaxH = isVertical ? Math.max(h - padding_h, this.mScrollContentDimension) : (h - padding_h);
            this.computeSize(context, 0, childMaxW, 0, childMaxH, measure);
        }

        // Update ComponentValue float bindings with our final dimensions
        // so LAYOUT_COMPUTE expressions can reference parent width/height.
        // Java does this via ComponentData.updateComponentData in LayoutComponent.
        this.updateComponentValues(context.getContext(), w, h);

        // Measure children with fill sizing (skip if already done in scroll path)
        if (!scrollMod) {
            this.computeSize(context, minWidth, w - padding_w, minHeight, h - padding_h, measure);
        }

        // Re-assign final dimensions after computeSize() (matching Java lines 558-563).
        // Subclass computeSize() overrides (e.g. CoreText) may overwrite selfMeasure
        // with content-only dimensions; restore the container's computed w/h here.
        w = Math.max(w, minWidth);
        h = Math.max(h, minHeight);
        selfMeasure.setW(w);
        selfMeasure.setH(h);

        // Run internal layout measure (positioning children)
        this.internalLayoutMeasure(context, measure);
    }

    layout(context: RemoteContext, measure: MeasurePass): void {
        // super.layout() already recurses into children, so we only add
        // layoutModifiers here (passes dimensions to Border/Background decorators).
        super.layout(context, measure);
        const self = measure.get(this);
        this.layoutModifiers(self.getW(), self.getH());

        // Write scroll max/notch variables to context
        // (matching Java ScrollModifierOperation.layout())
        const scrollMod = this.getScrollModifier();
        if (scrollMod) {
            const maxScroll = Math.max(0, this.mScrollContentDimension - this.mScrollHostDimension);
            const maxNan = scrollMod.getMaxNan();
            const notchNan = scrollMod.getNotchMaxNan();
            if (isNaNBits(maxNan)) {
                context.loadFloat(idFromBits(maxNan), maxScroll);
            }
            if (isNaNBits(notchNan)) {
                context.loadFloat(idFromBits(notchNan), this.mScrollContentDimension);
            }
        }
    }

    // Override in subclasses to compute wrap-content size
    computeWrapSize(_context: PaintContext, _minWidth: number, _maxWidth: number,
                    _minHeight: number, _maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    _measure: MeasurePass, _size: Size): void { /* override */ }

    // Override in subclasses to measure non-wrap children
    computeSize(_context: PaintContext, _minWidth: number, _maxWidth: number,
                _minHeight: number, _maxHeight: number, _measure: MeasurePass): void { /* override */ }

    // Override in subclasses to position children
    internalLayoutMeasure(_context: PaintContext, _measure: MeasurePass): void { /* override */ }
}
