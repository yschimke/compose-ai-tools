// LayoutManager: abstract base class for layout managers (Box, Row, Column, Canvas).
// Port of Java LayoutManager.java — handles measurement with fill/wrap/exact sizing.

import { LayoutComponent } from '../LayoutComponent';
import type { PaintContext } from '../../../PaintContext';
import type { RemoteContext } from '../../../RemoteContext';
import { DENSITY_BEHAVIOR_DP } from '../../../RemoteContext';
import type { MeasurePass } from '../measure/MeasurePass';
import { Size } from '../measure/Size';
import { WidthModifier, HeightModifier, ScrollModifier } from '../modifiers/ModifierOperations';
import { isNaNBits, idFromBits } from '../../Utils';

/**
 * The space left for content inside [size] once [padding] is taken off, never negative.
 *
 * Padding can exceed the component's own size — `.width(20).padding(30)` is legal, and the size
 * modifier is not widened to fit the padding — so the subtraction can go below zero. Compose gives
 * such a component a zero-sized content area; letting a negative reach child measurement instead
 * puts it into text layout and wrap-size accumulation, where it produces negative measured widths
 * rather than an empty one.
 */
function contentExtent(size: number, padding: number): number {
    return Math.max(0, size - padding);
}

export abstract class LayoutManager extends LayoutComponent {
    protected mCachedWrapSize = new Size();

    measure(context: PaintContext, minWidth: number, maxWidth: number,
            minHeight: number, maxHeight: number, measure: MeasurePass): void {
        const selfMeasure = measure.get(this);
        // Refresh cached padding from the (now variable-resolved, density-scaled)
        // padding modifiers before it feeds the size computation below.
        this.refreshPadding();
        const padding_w = this.mPaddingLeft + this.mPaddingRight;
        const padding_h = this.mPaddingTop + this.mPaddingBottom;

        const wMod = this.getWidthModifier();
        const hMod = this.getHeightModifier();

        // Document dp→px scale (DOC_DENSITY_AT_GENERATION; 1 for density-1 docs).
        // Applied to dp-typed dimensions (EXACT_DP, width/heightIn) which the wire
        // stores as raw dp; EXACT (px) and padding are left untouched.
        const dp = this.getDpScale(context);

        // Determine width
        let w: number;
        if (wMod && (wMod.getType() === WidthModifier.EXACT || wMod.getType() === WidthModifier.EXACT_DP)) {
            // EXACT is already in px; EXACT_DP is raw dp and needs the document's
            // generation density applied before it can be compared with anything else.
            const exactW = wMod.getType() === WidthModifier.EXACT_DP
                ? wMod.getValue() * dp
                : wMod.getValue();
            // Clamp to the incoming constraint. Without this a child larger than its
            // parent keeps its requested size and overflows; the reference and the C++
            // player both clamp (BaseModernMeasurePolicy: min(measuredWidth, maxWidth)).
            w = Math.min(exactW + this.mPadBeforeWidth, maxWidth);
        } else if (wMod && wMod.getType() === WidthModifier.FILL) {
            // A fill may carry a fraction of the parent; a bare fill carries NaN.
            w = wMod.hasFraction() ? maxWidth * wMod.getValue() : maxWidth;
        } else if (wMod && wMod.getType() === WidthModifier.WEIGHT) {
            // A weighted child gets its share from the parent's distribution pass. Until
            // then its own size is just its modifier-defined size, as in the reference
            // (max(measured, computeModifierDefinedWidth)). Defaulting to maxWidth here
            // leaks the full width whenever no distribution happens — a weight on the
            // cross axis, or in a parent that wraps and so has no slack to share.
            //
            // But the distribution pass communicates the share it decided on *as the
            // incoming constraint*: RowLayout re-measures each weighted child with
            // `minWidth == maxWidth == childWidth`. Taking the modifier-defined size
            // unconditionally throws that away and re-measures the child at ~0, which is
            // silent — the child is then laid out and painted at that width, so a text
            // inside it wraps one word per line and centres itself around a zero-width
            // box, i.e. off its own component. Honour the constraint the parent handed
            // down and fall back to the modifier-defined size only when it is loose.
            w = Math.min(Math.max(this.mPadBeforeWidth, minWidth), maxWidth);
        } else {
            // WRAP or other — compute from children
            w = maxWidth; // temporary, will be adjusted by computeWrapSize
        }

        // Determine height
        let h: number;
        if (hMod && (hMod.getType() === HeightModifier.EXACT || hMod.getType() === HeightModifier.EXACT_DP)) {
            const exactH = hMod.getType() === HeightModifier.EXACT_DP
                ? hMod.getValue() * dp
                : hMod.getValue();
            h = Math.min(exactH + this.mPadBeforeHeight, maxHeight);
        } else if (hMod && hMod.getType() === HeightModifier.FILL) {
            h = hMod.hasFraction() ? maxHeight * hMod.getValue() : maxHeight;
        } else if (hMod && hMod.getType() === HeightModifier.WEIGHT) {
            // See the width branch: a tight incoming constraint is the parent's
            // distributed share and wins over the modifier-defined size.
            h = Math.min(Math.max(this.mPadBeforeHeight, minHeight), maxHeight);
        } else {
            h = maxHeight;
        }

        selfMeasure.setW(w);
        selfMeasure.setH(h);

        const horizontalWrap = wMod?.getType() === WidthModifier.WRAP;
        const verticalWrap = hMod?.getType() === HeightModifier.WRAP;

        if (horizontalWrap || verticalWrap) {
            this.mCachedWrapSize.clear();
            // Children must be measured against *this* component's resolved size, not
            // against the space it was offered. The reference tightens the inset to the
            // measured size for any non-wrapping axis (BaseModernMeasurePolicy: "non-WRAP
            // gets exact inset"), and C++ does the same. Without it a box with an explicit
            // width measures its children at the parent's full width first, and a
            // wrapping height then locks in from that wrong measurement — which is why
            // text in a narrow fixed-width box was sized to one line and never re-grew.
            const childMaxW = contentExtent(horizontalWrap ? maxWidth : w, padding_w);
            const childMaxH = contentExtent(verticalWrap ? maxHeight : h, padding_h);
            this.computeWrapSize(context, minWidth, childMaxW, minHeight,
                childMaxH, horizontalWrap, verticalWrap, measure, this.mCachedWrapSize);

            // width/heightIn bounds are authored in dp (androidx `widthIn(min: Dp,
            // max: Dp)`), but the wire stores the raw dp number — scale to px by the
            // document's generation density (`dp`, 1 for density-1 docs).
            if (horizontalWrap) {
                w = this.mCachedWrapSize.getWidth() + padding_w;
                // Apply WidthIn constraints
                const wIn = this.getWidthInModifier();
                if (wIn) {
                    if (wIn.getMin() >= 0) w = Math.max(w, wIn.getMin() * dp);
                    if (wIn.getMax() >= 0) w = Math.min(w, wIn.getMax() * dp);
                }
                w = Math.min(w, maxWidth);
            }
            if (verticalWrap) {
                h = this.mCachedWrapSize.getHeight() + padding_h;
                const hIn = this.getHeightInModifier();
                if (hIn) {
                    if (hIn.getMin() >= 0) h = Math.max(h, hIn.getMin() * dp);
                    if (hIn.getMax() >= 0) h = Math.min(h, hIn.getMax() * dp);
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
            const hostW = contentExtent(Math.min(w, maxWidth), padding_w);
            const hostH = contentExtent(Math.min(h, maxHeight), padding_h);
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
            const childMaxW = isVertical ? contentExtent(w, padding_w)
                : Math.max(contentExtent(w, padding_w), this.mScrollContentDimension);
            const childMaxH = isVertical ? Math.max(contentExtent(h, padding_h), this.mScrollContentDimension)
                : contentExtent(h, padding_h);
            this.computeSize(context, 0, childMaxW, 0, childMaxH, measure);
        }

        // Update ComponentValue float bindings with our final dimensions
        // so LAYOUT_COMPUTE expressions can reference parent width/height.
        // Java does this via ComponentData.updateComponentData in LayoutComponent.
        this.updateComponentValues(context.getContext(), w, h);

        // Measure children with fill sizing (skip if already done in scroll path)
        if (!scrollMod) {
            this.computeSize(context, minWidth, contentExtent(w, padding_w),
                minHeight, contentExtent(h, padding_h), measure);
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

    /** The document's dp→px scale (DOC_DENSITY_AT_GENERATION). Used to convert
     *  dp-typed dimension bounds to generation pixels. Defaults to 1 (unset/
     *  density-1 documents), so it is a no-op for everything authored today. */
    protected getDpScale(context: PaintContext): number {
        const d = context.getContext().getDensity();
        return (Number.isNaN(d) || d <= 0) ? 1 : d;
    }

    /** dp→px factor for values AndroidX scales *only* under DP density behavior —
     *  layout `spacedBy` spacing here, matching Row/ColumnLayout which multiply the
     *  gap by the density when `getDensityBehavior() == DP`. Returns 1 for LEGACY /
     *  PIXELS behavior so authored-in-px documents are untouched. */
    protected getDpBehaviorScale(context: PaintContext): number {
        const ctx = context.getContext();
        if (ctx.getDensityBehavior() !== DENSITY_BEHAVIOR_DP) return 1;
        const d = ctx.getDensity();
        return (Number.isNaN(d) || d <= 0) ? 1 : d;
    }
}
