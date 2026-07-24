// CollapsibleColumnLayout: a column that hides children that don't fit vertically.
// Port of Java CollapsibleColumnLayout.java.

import { ColumnLayout } from './ColumnLayout';
import { LayoutComponent } from '../LayoutComponent';
import { Component, Visibility } from '../Component';
import { CollapsiblePriority } from './CollapsiblePriority';
import { CollapsiblePriorityModifier } from '../modifiers/ModifierOperations';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class CollapsibleColumnLayout extends ColumnLayout {
    static override readonly OP_CODE = 233;

    constructor(componentId: number, animationId: number,
                horizontalPositioning: number, verticalPositioning: number,
                spacedBy: number) {
        super(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy);
    }

    override computeWrapSize(context: PaintContext, _minWidth: number, maxWidth: number,
                    _minHeight: number, maxHeight: number,
                    _horizontalWrap: boolean, _verticalWrap: boolean,
                    measure: MeasurePass, size: Size): void {
        this.computeVisibleChildren(context, maxWidth, maxHeight, true, measure, size);
    }

    override computeSize(context: PaintContext, _minWidth: number, maxWidth: number,
                _minHeight: number, maxHeight: number, measure: MeasurePass): void {
        this.computeVisibleChildren(context, maxWidth, maxHeight, false, measure, null);
    }

    override internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        super.internalLayoutMeasure(context, measure);
        const m = measure.get(this);
        this.computeVisibleChildren(context, m.getW(), m.getH(), false, measure, null);
    }

    private computeVisibleChildren(context: PaintContext, maxWidth: number, maxHeight: number,
                                   verticalWrap: boolean, measure: MeasurePass,
                                   size: Size | null): void {
        let visibleChildren = 0;
        const self = measure.get(this);
        self.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
        let currentMaxHeight = maxHeight;
        let hasPriorities = false;

        for (const c of this.mChildrenComponents) {
            if (!measure.contains(c.getComponentId())) {
                if (c instanceof CollapsibleColumnLayout) {
                    c.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
                } else {
                    c.measure(context, 0, maxWidth, 0, Number.MAX_VALUE, measure);
                }
            }
            const m = measure.get(c);
            if (!m.isGone()) {
                if (size !== null) {
                    size.setWidth(Math.max(size.getWidth(), m.getW()));
                    size.setHeight(size.getHeight() + m.getH());
                }
                visibleChildren++;
                currentMaxHeight -= m.getH();
            }
            if (c instanceof LayoutComponent) {
                const priority = c.selfOrModifier(CollapsiblePriorityModifier);
                if (priority) hasPriorities = true;
            }
        }
        if (this.mChildrenComponents.length > 0 && size !== null) {
            size.setHeight(size.getHeight() + (this.mSpacedBy * (visibleChildren - 1)));
        }

        let childrenWidth = 0;
        let childrenHeight = 0;
        let overflow = false;

        let children: Component[] = this.mChildrenComponents;
        if (hasPriorities) {
            children = CollapsiblePriority.sortWithPriorities(
                this.mChildrenComponents, CollapsiblePriority.VERTICAL);
        }

        for (const child of children) {
            const childMeasure = measure.get(child);
            if (overflow || childMeasure.isGone()) {
                childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
                continue;
            }
            const childHeight = childMeasure.getH();
            if (childrenHeight + childHeight > maxHeight) {
                childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
                overflow = true;
            } else {
                childrenHeight += childHeight;
                childrenWidth = Math.max(childrenWidth, childMeasure.getW());
                visibleChildren++;
            }
        }

        if (verticalWrap && size !== null) {
            size.setHeight(Math.min(maxHeight, childrenHeight));
        }
        if (visibleChildren === 0 || (size !== null && size.getHeight() <= 0)) {
            self.addVisibilityOverride(Visibility.OVERRIDE_GONE);
        }
    }

    override write(buffer: WireBuffer): void {
        buffer.start(CollapsibleColumnLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.getAnimationId());
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
        buffer.writeFloat(this.mSpacedBy);
    }

    override deepToString(indent: string): string {
        return `${indent}CollapsibleColumnLayout(${this.getComponentId()})`;
    }

    static override read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        const spacedBy = buffer.readFloat();
        operations.push(new CollapsibleColumnLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning, spacedBy));
    }
}
