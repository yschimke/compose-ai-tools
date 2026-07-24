// BoxLayout: children stacked on top of each other with positioning.
// Port of Java BoxLayout.java with measure/layout implementation.

import { LayoutManager } from './LayoutManager';
import { Component } from '../Component';
import { LayoutComputeOperation } from '../modifiers/LayoutComputeOperation';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class BoxLayout extends LayoutManager {
    static readonly OP_CODE = 202;

    static readonly START = 1;
    static readonly CENTER = 2;
    static readonly END = 3;
    static readonly TOP = 4;
    static readonly BOTTOM = 5;

    protected mAnimationId: number;
    protected mHorizontalPositioning: number;
    protected mVerticalPositioning: number;

    constructor(componentId: number, animationId: number,
                horizontalPositioning: number, verticalPositioning: number) {
        super(componentId, animationId);
        this.mAnimationId = animationId;
        this.mHorizontalPositioning = horizontalPositioning;
        this.mVerticalPositioning = verticalPositioning;
    }

    computeWrapSize(context: PaintContext, minWidth: number, maxWidth: number,
                    minHeight: number, maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        const parent = measure.get(this);
        for (const child of this.mChildrenComponents) {
            child.measure(context, 0, maxWidth, 0, maxHeight, measure);
            const m = measure.get(child);
            if (child.hasComputedLayout()) {
                if (child.applyComputedLayout(LayoutComputeOperation.TYPE_MEASURE, context, m, parent)) {
                    child.measure(context, m.getW(), m.getW(), m.getH(), m.getH(), measure);
                }
            }
            if (!m.isGone()) {
                size.setWidth(Math.max(size.getWidth(), m.getW()));
                size.setHeight(Math.max(size.getHeight(), m.getH()));
            }
        }
    }

    computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                minHeight: number, maxHeight: number, measure: MeasurePass): void {
        const parent = measure.get(this);
        for (const child of this.mChildrenComponents) {
            child.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
            if (child.hasComputedLayout()) {
                const m = measure.get(child);
                if (child.applyComputedLayout(LayoutComputeOperation.TYPE_MEASURE, context, m, parent)) {
                    child.measure(context, m.getW(), m.getW(), m.getH(), m.getH(), measure);
                }
            }
        }
    }

    internalLayoutMeasure(_context: PaintContext, measure: MeasurePass): void {
        const selfMeasure = measure.get(this);
        const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
        const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;

        for (const child of this.mChildrenComponents) {
            const childMeasure = measure.get(child);
            if (childMeasure.isGone()) continue;

            let tx = 0;
            let ty = 0;

            switch (this.mHorizontalPositioning) {
                case BoxLayout.START: tx = 0; break;
                case BoxLayout.CENTER: tx = (selfWidth - childMeasure.getW()) / 2; break;
                case BoxLayout.END: tx = selfWidth - childMeasure.getW(); break;
            }
            switch (this.mVerticalPositioning) {
                case BoxLayout.TOP: ty = 0; break;
                case BoxLayout.CENTER: ty = (selfHeight - childMeasure.getH()) / 2; break;
                case BoxLayout.BOTTOM: ty = selfHeight - childMeasure.getH(); break;
            }

            childMeasure.setX(tx);
            childMeasure.setY(ty);

            if (child.hasComputedLayout()) {
                child.applyComputedLayout(LayoutComputeOperation.TYPE_POSITION, _context, childMeasure, selfMeasure);
            }
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(BoxLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.mAnimationId);
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}BoxLayout(${this.getComponentId()}, h=${this.mHorizontalPositioning}, v=${this.mVerticalPositioning})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        operations.push(new BoxLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning));
    }
}
