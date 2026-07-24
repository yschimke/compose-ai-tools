// RowLayout: children laid out horizontally with spacing, alignment, and weight support.
// Port of Java RowLayout.java with full measure/layout implementation.

import { LayoutManager } from './LayoutManager';
import { LayoutComponent } from '../LayoutComponent';
import { Component } from '../Component';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class RowLayout extends LayoutManager {
    static readonly OP_CODE = 203;

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

    // --- Intrinsic size (matches Java RowLayout) ---

    override minIntrinsicWidth(): number {
        return this.minIntrinsicWidthForComponents(this.mChildrenComponents);
    }

    override minIntrinsicHeight(): number {
        return this.minIntrinsicHeightForComponents(this.mChildrenComponents);
    }

    protected minIntrinsicWidthForComponents(components: Component[]): number {
        const width = this.computeModifierDefinedWidth();
        let componentWidths = 0;
        for (const c of components) {
            componentWidths += c.minIntrinsicWidth();
        }
        return Math.max(width, componentWidths);
    }

    protected minIntrinsicHeightForComponents(components: Component[]): number {
        const height = this.computeModifierDefinedHeight();
        let componentHeights = 0;
        for (const c of components) {
            componentHeights = Math.max(componentHeights, c.minIntrinsicHeight());
        }
        return Math.max(height, componentHeights);
    }

    // --- Wrap size ---

    computeWrapSize(context: PaintContext, minWidth: number, maxWidth: number,
                    minHeight: number, maxHeight: number,
                    horizontalWrap: boolean, verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        this.computeWrapSizeForComponents(context, minWidth, maxWidth, minHeight, maxHeight,
            horizontalWrap, verticalWrap, measure, size, this.mChildrenComponents);
    }

    protected computeWrapSizeForComponents(
        context: PaintContext, _minWidth: number, maxWidth: number,
        _minHeight: number, maxHeight: number,
        _horizontalWrap: boolean, _verticalWrap: boolean,
        measure: MeasurePass, size: Size, components: Component[]): void {

        size.clear();
        let visibleChildren = 0;
        let currentMaxWidth = maxWidth;

        // Check for weights
        let totalWeights = 0;
        let hasWeights = false;
        for (const child of components) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            if (child instanceof LayoutComponent && child.hasWidthWeight()) {
                hasWeights = true;
                totalWeights += child.getWidthModValue();
            }
        }

        if (hasWeights) {
            // First pass: measure non-weighted children
            for (const child of components) {
                if (child instanceof LayoutComponent && child.hasWidthWeight()) continue;
                child.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(size.getWidth() + m.getW());
                    size.setHeight(Math.max(size.getHeight(), m.getH()));
                    visibleChildren++;
                    currentMaxWidth -= m.getW();
                }
            }
            // Second pass: distribute remaining space to weighted children
            for (const child of components) {
                if (!(child instanceof LayoutComponent && child.hasWidthWeight())) continue;
                const weight = child.getWidthModValue();
                const childWidth = (weight * currentMaxWidth) / totalWeights;
                child.measure(context, childWidth, childWidth, 0, maxHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(size.getWidth() + m.getW());
                    size.setHeight(Math.max(size.getHeight(), m.getH()));
                    visibleChildren++;
                }
            }
        } else {
            for (const child of components) {
                child.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
                const m = measure.get(child);
                if (!m.isGone()) {
                    size.setWidth(size.getWidth() + m.getW());
                    size.setHeight(Math.max(size.getHeight(), m.getH()));
                    visibleChildren++;
                    currentMaxWidth -= m.getW();
                }
            }
        }

        if (visibleChildren > 0) {
            size.setWidth(size.getWidth() + this.mSpacedBy * (visibleChildren - 1));
        }
    }

    // --- Compute size ---

    computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                minHeight: number, maxHeight: number, measure: MeasurePass): void {
        this.computeSizeForComponents(context, minWidth, maxWidth, minHeight, maxHeight,
            measure, this.mChildrenComponents);
    }

    protected computeSizeForComponents(
        context: PaintContext, _minWidth: number, maxWidth: number,
        _minHeight: number, maxHeight: number, measure: MeasurePass,
        components: Component[]): void {

        let mw = maxWidth;
        for (const child of components) {
            child.measure(context, 0, mw, 0, maxHeight, measure);
            const m = measure.get(child);
            if (!m.isGone()) mw -= m.getW();
        }
    }

    // --- Internal layout measure ---

    internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        const selfMeasure = measure.get(this);
        const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
        const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;

        this.internalLayoutMeasureForComponents(context, measure, this.mChildrenComponents,
            selfWidth, selfHeight, 0, 0, null);
    }

    protected internalLayoutMeasureForComponents(
        context: PaintContext, measure: MeasurePass,
        components: Component[], selfWidth: number, selfHeight: number,
        positionX: number, positionY: number, size: Size | null): void {

        if (components.length === 0) return;

        // Handle weights
        let hasWeights = false;
        let totalWeights = 0;
        let nonWeightWidth = 0;

        for (const child of components) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            if (child instanceof LayoutComponent && child.hasWidthWeight()) {
                hasWeights = true;
                totalWeights += child.getWidthModValue();
            } else {
                nonWeightWidth += cm.getW();
            }
        }

        if (hasWeights) {
            const availableSpace = selfWidth - nonWeightWidth;
            for (const child of components) {
                if (!(child instanceof LayoutComponent && child.hasWidthWeight())) continue;
                const cm = measure.get(child);
                if (cm.isGone()) continue;
                const weight = child.getWidthModValue();
                let childWidth = (weight * availableSpace) / totalWeights;
                const wIn = child.getWidthInModifier();
                if (wIn) {
                    if (wIn.getMin() >= 0) childWidth = Math.max(wIn.getMin(), childWidth);
                    if (wIn.getMax() >= 0) childWidth = Math.min(wIn.getMax(), childWidth);
                }
                cm.setW(childWidth);
                child.measure(context, childWidth, childWidth, cm.getH(), cm.getH(), measure);
            }
        }

        // Compute children total width
        let childrenWidth = 0;
        let childrenHeight = 0;
        let visibleChildren = 0;

        for (const child of components) {
            const cm = measure.get(child);
            if (cm.isGone()) continue;
            childrenWidth += cm.getW();
            childrenHeight = Math.max(childrenHeight, cm.getH());
            visibleChildren++;
        }
        childrenWidth += this.mSpacedBy * Math.max(0, visibleChildren - 1);

        // Compute horizontal starting position
        let tx = 0;
        let horizontalGap = 0;

        switch (this.mHorizontalPositioning) {
            case RowLayout.START: tx = 0; break;
            case RowLayout.END: tx = selfWidth - childrenWidth; break;
            case RowLayout.CENTER: tx = (selfWidth - childrenWidth) / 2; break;
            case RowLayout.SPACE_BETWEEN: {
                let total = 0;
                for (const c of components) { const m = measure.get(c); if (!m.isGone()) total += m.getW(); }
                if (visibleChildren > 1) horizontalGap = (selfWidth - total) / (visibleChildren - 1);
                else tx = (selfWidth - childrenWidth) / 2;
                break;
            }
            case RowLayout.SPACE_EVENLY: {
                let total = 0;
                for (const c of components) { const m = measure.get(c); if (!m.isGone()) total += m.getW(); }
                horizontalGap = (selfWidth - total) / (visibleChildren + 1);
                tx = horizontalGap;
                break;
            }
            case RowLayout.SPACE_AROUND: {
                let total = 0;
                for (const c of components) { const m = measure.get(c); if (!m.isGone()) total += m.getW(); }
                horizontalGap = (selfWidth - total) / visibleChildren;
                tx = horizontalGap / 2;
                break;
            }
        }

        // Position each child
        for (const child of components) {
            const cm = measure.get(child);
            let ty = 0;

            switch (this.mVerticalPositioning) {
                case RowLayout.TOP: ty = 0; break;
                case RowLayout.CENTER: ty = (selfHeight - cm.getH()) / 2; break;
                case RowLayout.BOTTOM: ty = selfHeight - cm.getH(); break;
            }

            cm.setX(tx + positionX);
            cm.setY(ty + positionY);

            if (cm.isGone()) continue;
            tx += cm.getW();
            if (this.mHorizontalPositioning === RowLayout.SPACE_BETWEEN
                || this.mHorizontalPositioning === RowLayout.SPACE_AROUND
                || this.mHorizontalPositioning === RowLayout.SPACE_EVENLY) {
                tx += horizontalGap;
            }
            tx += this.mSpacedBy;
        }

        if (size !== null) {
            size.setWidth(childrenWidth);
            size.setHeight(childrenHeight);
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(RowLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.mAnimationId);
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
        buffer.writeFloat(this.mSpacedBy);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}RowLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        const spacedBy = buffer.readFloat();
        operations.push(new RowLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning, spacedBy));
    }
}
