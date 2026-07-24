// StateLayout: state-based layout manager that switches between child layouts.
// Simplified port of Java StateLayout.java — shows current child, no transition animation.

import { LayoutManager } from './LayoutManager';
import { LayoutComponent } from '../LayoutComponent';
import { Component, Visibility } from '../Component';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class StateLayout extends LayoutManager {
    static readonly OP_CODE = 217;

    private mIndexId: number;
    currentLayoutIndex = 0;

    constructor(componentId: number, animationId: number, indexId: number) {
        super(componentId, animationId);
        this.mIndexId = indexId;
    }

    inflate(): void {
        super.inflate();
        this.hideLayoutsOtherThan(this.currentLayoutIndex);
    }

    computeWrapSize(context: PaintContext, minWidth: number, maxWidth: number,
                    minHeight: number, maxHeight: number,
                    horizontalWrap: boolean, verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            layout.computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight,
                horizontalWrap, verticalWrap, measure, size);
        }
    }

    computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                minHeight: number, maxHeight: number, measure: MeasurePass): void {
        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            layout.computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        }
    }

    internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            layout.internalLayoutMeasure(context, measure);
        }
    }

    measure(context: PaintContext, minWidth: number, maxWidth: number,
            minHeight: number, maxHeight: number, measure: MeasurePass): void {
        // Read current index from context
        if (this.mIndexId !== 0) {
            const newValue = context.getContext().mRemoteComposeState.getInteger(this.mIndexId);
            if (newValue !== this.currentLayoutIndex) {
                this.currentLayoutIndex = newValue;
                this.hideLayoutsOtherThan(this.currentLayoutIndex);
            }
        }

        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            layout.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
            // Copy layout's measure to our own
            const layoutM = measure.get(layout);
            const selfM = measure.get(this);
            selfM.copyFrom(layoutM);
        } else {
            super.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        }
    }

    layout(context: RemoteContext, measure: MeasurePass): void {
        const self = measure.get(this);
        // Set own position from measure (like Component.layout)
        this.mVisibility = self.getVisibility();
        if (self.isGone()) return;
        this.mX = self.getX();
        this.mY = self.getY();
        this.mWidth = self.getW();
        this.mHeight = self.getH();
        this.layoutModifiers(self.getW(), self.getH());
        this.mNeedsMeasure = false;

        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            const layoutMeasure = measure.get(layout);
            layoutMeasure.copyFrom(self);
            layout.layout(context, measure);
        }
    }

    paint(paintContext: PaintContext): void {
        // Check if index variable changed
        if (this.mIndexId !== 0) {
            const newValue = paintContext.getContext().mRemoteComposeState.getInteger(this.mIndexId);
            if (newValue !== this.currentLayoutIndex) {
                this.currentLayoutIndex = newValue;
                this.hideLayoutsOtherThan(this.currentLayoutIndex);
                this.invalidateMeasure();
            }
        }

        // Ensure visibility is correct
        let index = 0;
        for (const pane of this.mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                if (index === this.currentLayoutIndex) {
                    if (!pane.isVisible()) {
                        pane.mVisibility = Visibility.VISIBLE;
                    }
                } else {
                    pane.mVisibility = Visibility.GONE;
                }
                index++;
            }
        }

        const layout = this.getLayout(this.currentLayoutIndex);
        if (layout) {
            paintContext.save();
            paintContext.translate(layout.getX(), layout.getY());

            // Paint the current layout's operations
            const context = paintContext.getContext();
            for (const op of layout.getList()) {
                context.incrementOpCount();
                if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                    op.markNotDirty();
                    (op as any).updateVariables(context);
                }
                if (op instanceof Component) {
                    if (!Visibility.isGone(op.mVisibility)) {
                        op.paint(paintContext);
                    }
                } else {
                    op.apply(context);
                }
            }

            paintContext.restore();
        }
    }

    /** Returns the idx-th LayoutComponent child */
    getLayout(idx: number): LayoutManager | null {
        let index = 0;
        for (const pane of this.mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                if (index === idx) {
                    return pane as LayoutManager;
                }
                index++;
            }
        }
        // Fallback to first child
        if (this.mChildrenComponents.length > 0 && this.mChildrenComponents[0] instanceof LayoutComponent) {
            return this.mChildrenComponents[0] as LayoutManager;
        }
        return null;
    }

    /** Hides all layout children except the one at idx */
    hideLayoutsOtherThan(idx: number): void {
        let index = 0;
        for (const pane of this.mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                pane.mVisibility = (index === idx) ? Visibility.VISIBLE : Visibility.GONE;
                index++;
            }
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(StateLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(0); // animationId
        buffer.writeInt(0); // horizontalPositioning
        buffer.writeInt(0); // verticalPositioning
        buffer.writeInt(this.mIndexId);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}StateLayout(${this.getComponentId()}, index=${this.mIndexId})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        buffer.readInt(); // horizontalPositioning
        buffer.readInt(); // verticalPositioning
        const indexId = buffer.readInt();
        operations.push(new StateLayout(componentId, animationId, indexId));
    }
}
