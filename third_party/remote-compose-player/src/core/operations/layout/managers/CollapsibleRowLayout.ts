// CollapsibleRowLayout: a row that hides children that don't fit horizontally.
// Port of Java CollapsibleRowLayout.java.

import { RowLayout } from './RowLayout';
import { LayoutComponent } from '../LayoutComponent';
import { Component, Visibility } from '../Component';
import { CollapsiblePriority } from './CollapsiblePriority';
import { CollapsiblePriorityModifier } from '../modifiers/ModifierOperations';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import type { Size } from '../measure/Size';

export class CollapsibleRowLayout extends RowLayout {
    static override readonly OP_CODE = 230;

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
                                   horizontalWrap: boolean, measure: MeasurePass,
                                   size: Size | null): void {
        let visibleChildren = 0;
        const self = measure.get(this);
        self.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
        let currentMaxWidth = maxWidth;
        let hasPriorities = false;

        for (const c of this.mChildrenComponents) {
            if (!measure.contains(c.getComponentId())) {
                if (c instanceof CollapsibleRowLayout) {
                    c.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
                } else {
                    c.measure(context, 0, Number.MAX_VALUE, 0, maxHeight, measure);
                }
            }
            const m = measure.get(c);
            if (!m.isGone()) {
                if (size !== null) {
                    size.setHeight(Math.max(size.getHeight(), m.getH()));
                    size.setWidth(size.getWidth() + m.getW());
                }
                visibleChildren++;
                currentMaxWidth -= m.getW();
            }
            if (c instanceof LayoutComponent) {
                const priority = c.selfOrModifier(CollapsiblePriorityModifier);
                if (priority) hasPriorities = true;
            }
        }
        if (this.mChildrenComponents.length > 0 && size !== null) {
            size.setWidth(size.getWidth() + (this.mSpacedBy * (visibleChildren - 1)));
        }

        let childrenWidth = 0;
        let childrenHeight = 0;
        let overflow = false;

        let children: Component[] = this.mChildrenComponents;
        if (hasPriorities) {
            children = CollapsiblePriority.sortWithPriorities(
                this.mChildrenComponents, CollapsiblePriority.HORIZONTAL);
        }

        for (const child of children) {
            const childMeasure = measure.get(child);
            if (overflow || childMeasure.isGone()) {
                childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
                continue;
            }
            const childWidth = childMeasure.getW();
            if (childrenWidth + childWidth > maxWidth) {
                childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
                overflow = true;
            } else {
                childrenWidth += childWidth;
                childrenHeight = Math.max(childrenHeight, childMeasure.getH());
                visibleChildren++;
            }
        }

        if (horizontalWrap && size !== null) {
            size.setWidth(Math.min(maxWidth, childrenWidth));
        }
        if (visibleChildren === 0 || (size !== null && size.getWidth() <= 0)) {
            self.addVisibilityOverride(Visibility.OVERRIDE_GONE);
        }
    }

    override write(buffer: WireBuffer): void {
        buffer.start(CollapsibleRowLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.getAnimationId());
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
        buffer.writeFloat(this.mSpacedBy);
    }

    override deepToString(indent: string): string {
        return `${indent}CollapsibleRowLayout(${this.getComponentId()})`;
    }

    static override read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        const spacedBy = buffer.readFloat();
        operations.push(new CollapsibleRowLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning, spacedBy));
    }
}
