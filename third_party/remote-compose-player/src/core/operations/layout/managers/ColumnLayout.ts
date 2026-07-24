// ColumnLayout: children laid out vertically with spacing, alignment, and weight support.
// Port of Java ColumnLayout.java with full measure/layout implementation.

import { LayoutManager } from './LayoutManager';
import { LayoutComponent } from '../LayoutComponent';
import { Component } from '../Component';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class ColumnLayout extends LayoutManager {
    static readonly OP_CODE = 204;

    static readonly START = 1;
    static readonly CENTER = 2;
    static readonly END = 3;
    static readonly TOP = 4;
    static readonly BOTTOM = 5;
    static readonly SPACE_BETWEEN = 6;
    static readonly SPACE_EVENLY = 7;
    static readonly SPACE_AROUND = 8;

    private mAnimationId: number;
    protected mHorizontalPositioning: number;
    protected mVerticalPositioning: number;
    protected mSpacedBy: number;

    constructor(componentId: number, animationId: number,
                horizontalPositioning: number, verticalPositioning: number,
                spacedBy: number) {
        super(componentId, animationId);
        this.mAnimationId = animationId;
        this.mHorizontalPositioning = horizontalPositioning;
        this.mVerticalPositioning = verticalPositioning;
        this.mSpacedBy = spacedBy;
    }

    computeWrapSize(context: PaintContext, _minWidth: number, maxWidth: number,
                    _minHeight: number, maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        size.clear();
        let visibleChildren = 0;
        let currentMaxHeight = maxHeight;

        // Check for weights
        let totalWeights = 0;
        let hasWeights = false;
        for (const child of this.mChildrenComponents) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            if (child instanceof LayoutComponent && child.hasHeightWeight()) {
                hasWeights = true;
                totalWeights += child.getHeightModValue();
            }
        }

        if (hasWeights) {
            // First pass: measure non-weighted children
            for (const child of this.mChildrenComponents) {
                if (child instanceof LayoutComponent && child.hasHeightWeight()) continue;
                child.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(Math.max(size.getWidth(), m.getW()));
                    size.setHeight(size.getHeight() + m.getH());
                    visibleChildren++;
                    currentMaxHeight -= m.getH();
                }
            }
            // Second pass: distribute remaining space to weighted children
            for (const child of this.mChildrenComponents) {
                if (!(child instanceof LayoutComponent && child.hasHeightWeight())) continue;
                const weight = child.getHeightModValue();
                const childHeight = (weight * currentMaxHeight) / totalWeights;
                child.measure(context, 0, maxWidth, childHeight, childHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(Math.max(size.getWidth(), m.getW()));
                    size.setHeight(size.getHeight() + m.getH());
                    visibleChildren++;
                }
            }
        } else {
            for (const child of this.mChildrenComponents) {
                child.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(Math.max(size.getWidth(), m.getW()));
                    size.setHeight(size.getHeight() + m.getH());
                    visibleChildren++;
                    currentMaxHeight -= m.getH();
                }
            }
        }

        if (visibleChildren > 0) {
            size.setHeight(size.getHeight() + this.mSpacedBy * (visibleChildren - 1));
        }
    }

    computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                minHeight: number, maxHeight: number, measure: MeasurePass): void {
        // Check for weights
        let totalHeightsNoWeights = 0;
        let totalWeights = 0;
        let hasWeights = false;
        let maxh = maxHeight;

        for (const child of this.mChildrenComponents) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            if (child instanceof LayoutComponent && child.hasHeightWeight()) {
                hasWeights = true;
                totalWeights += child.getHeightModValue();
            } else {
                child.measure(context, minWidth, maxWidth, minHeight, maxh, measure);
                const m = measure.get(child);
                maxh -= m.getH();
                totalHeightsNoWeights += m.getH();
            }
        }

        if (hasWeights) {
            maxh = maxHeight;
            for (const child of this.mChildrenComponents) {
                if (child instanceof LayoutComponent && child.hasHeightWeight() && !child.isGone()) {
                    const weight = child.getHeightModValue();
                    const childHeight = (maxHeight - totalHeightsNoWeights) * weight / totalWeights;
                    child.measure(context, minWidth, maxWidth, childHeight, childHeight, measure);
                } else {
                    child.measure(context, minWidth, maxWidth, minHeight, maxh, measure);
                }
                const m = measure.get(child);
                if (!m.isGone()) {
                    maxh -= m.getH();
                }
            }
        } else {
            let mh = maxHeight;
            for (const child of this.mChildrenComponents) {
                child.measure(context, minWidth, maxWidth, minHeight, mh, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    mh -= m.getH();
                }
            }
        }
    }

    internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        const selfMeasure = measure.get(this);
        const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
        const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
        const children = this.mChildrenComponents;

        if (children.length === 0) return;

        // Handle weights
        let hasWeights = false;
        let totalWeights = 0;
        let nonWeightHeight = 0;

        for (const child of children) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            if (child instanceof LayoutComponent && child.hasHeightWeight()) {
                hasWeights = true;
                totalWeights += child.getHeightModValue();
            } else {
                nonWeightHeight += cm.getH();
            }
        }

        if (hasWeights) {
            const availableSpace = selfHeight - nonWeightHeight;
            for (const child of children) {
                if (!(child instanceof LayoutComponent && child.hasHeightWeight())) continue;
                const cm = measure.get(child);
                if (cm.isGone()) continue;
                const weight = child.getHeightModValue();
                let childHeight = (weight * availableSpace) / totalWeights;
                const hIn = child.getHeightInModifier();
                if (hIn) {
                    if (hIn.getMin() >= 0) childHeight = Math.max(hIn.getMin(), childHeight);
                    if (hIn.getMax() >= 0) childHeight = Math.min(hIn.getMax(), childHeight);
                }
                cm.setH(childHeight);
                child.measure(context, cm.getW(), cm.getW(), cm.getH(), cm.getH(), measure);
            }
        }

        // Compute children total height
        let childrenWidth = 0;
        let childrenHeight = 0;
        let visibleChildren = 0;

        for (const child of children) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            childrenWidth = Math.max(childrenWidth, cm.getW());
            childrenHeight += cm.getH();
            visibleChildren++;
        }
        childrenHeight += this.mSpacedBy * Math.max(0, visibleChildren - 1);

        // Compute vertical starting position
        let ty = 0;
        let verticalGap = 0;

        switch (this.mVerticalPositioning) {
            case ColumnLayout.TOP: ty = 0; break;
            case ColumnLayout.BOTTOM: ty = selfHeight - childrenHeight; break;
            case ColumnLayout.CENTER: ty = (selfHeight - childrenHeight) / 2; break;
            case ColumnLayout.SPACE_BETWEEN: {
                let total = 0;
                for (const c of children) { const m = measure.get(c); if (!m.isGone()) total += m.getH(); }
                if (visibleChildren > 1) verticalGap = (selfHeight - total) / (visibleChildren - 1);
                else ty = (selfHeight - childrenHeight) / 2;
                break;
            }
            case ColumnLayout.SPACE_EVENLY: {
                let total = 0;
                for (const c of children) { const m = measure.get(c); if (!m.isGone()) total += m.getH(); }
                verticalGap = (selfHeight - total) / (visibleChildren + 1);
                ty = verticalGap;
                break;
            }
            case ColumnLayout.SPACE_AROUND: {
                let total = 0;
                for (const c of children) { const m = measure.get(c); if (!m.isGone()) total += m.getH(); }
                verticalGap = (selfHeight - total) / visibleChildren;
                ty = verticalGap / 2;
                break;
            }
        }

        // Position each child
        for (const child of children) {
            const cm = measure.get(child);
            let tx = 0;

            switch (this.mHorizontalPositioning) {
                case ColumnLayout.START: tx = 0; break;
                case ColumnLayout.CENTER: tx = (selfWidth - cm.getW()) / 2; break;
                case ColumnLayout.END: tx = selfWidth - cm.getW(); break;
            }

            cm.setX(tx);
            cm.setY(ty);

            if (cm.isGone()) continue;
            ty += cm.getH();
            if (this.mVerticalPositioning === ColumnLayout.SPACE_BETWEEN
                || this.mVerticalPositioning === ColumnLayout.SPACE_AROUND
                || this.mVerticalPositioning === ColumnLayout.SPACE_EVENLY) {
                ty += verticalGap;
            }
            ty += this.mSpacedBy;
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(ColumnLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.mAnimationId);
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
        buffer.writeFloat(this.mSpacedBy);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}ColumnLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        const spacedBy = buffer.readFloat();
        operations.push(new ColumnLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning, spacedBy));
    }
}
