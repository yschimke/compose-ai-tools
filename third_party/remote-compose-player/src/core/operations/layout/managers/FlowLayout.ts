// FlowLayout: positions components horizontally, wrapping to next line when space is exhausted.
// Port of Java FlowLayout.java — extends RowLayout with row segmentation.

import { RowLayout } from './RowLayout';
import { LayoutComponent } from '../LayoutComponent';
import { Component } from '../Component';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';
import { Size } from '../measure/Size';

export class FlowLayout extends RowLayout {
    static override readonly OP_CODE = 240;

    mMaxItemsInEachRow = 0;
    mMaxLines = 0;

    constructor(componentId: number, animationId: number,
                horizontalPositioning: number, verticalPositioning: number,
                spacedBy: number) {
        super(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy);
    }

    private hasWeight(c: Component): boolean {
        return c instanceof LayoutComponent && c.hasWidthWeight();
    }

    /** Divide children into rows based on available width. */
    private segmentComponents(context: PaintContext, maxWidth: number, maxHeight: number,
                              measure: MeasurePass): Component[][] {
        const rows: Component[][] = [];
        let currentRow: Component[] = [];
        rows.push(currentRow);
        let currentWidth = 0;

        for (const c of this.mChildrenComponents) {
            let componentWidth = 0;
            if (measure.get(c).isGone()) {
                componentWidth = 0;
            } else if (this.hasWeight(c)) {
                // Check minimum width constraint
                const wIn = (c as LayoutComponent).getWidthInModifier();
                if (wIn) {
                    const min = wIn.getMin();
                    if (min !== -1) {
                        componentWidth = min;
                    }
                }
            } else {
                // Measure to get width
                c.measure(context, 0, maxWidth, 0, maxHeight, measure);
                const m = measure.get(c);
                componentWidth = m.getW();
            }

            if (componentWidth + currentWidth > maxWidth) {
                // Start new row
                currentRow = [];
                rows.push(currentRow);
                currentWidth = 0;
            }
            currentRow.push(c);
            currentWidth += componentWidth;
        }
        return rows;
    }

    override computeWrapSize(context: PaintContext, minWidth: number, maxWidth: number,
                             minHeight: number, maxHeight: number,
                             horizontalWrap: boolean, verticalWrap: boolean,
                             measure: MeasurePass, size: Size): void {
        // Pre-measure all children
        for (const c of this.mChildrenComponents) {
            if (c.needsMeasure()) {
                c.measure(context, 0, maxWidth, 0, maxHeight, measure);
            }
        }

        const rows = this.segmentComponents(context, maxWidth, maxHeight, measure);
        const rowSize = new Size();
        let width = minWidth;
        let height = 0;

        for (const row of rows) {
            this.computeWrapSizeForComponents(context, minWidth, maxWidth, minHeight, maxHeight,
                horizontalWrap, verticalWrap, measure, rowSize, row);
            width = Math.max(width, rowSize.getWidth());
            height += rowSize.getHeight();
        }

        width = Math.min(Math.max(minWidth, width), maxWidth);
        height = Math.min(Math.max(minHeight, height), maxHeight);
        size.setWidth(width);
        size.setHeight(height);
    }

    override computeSize(context: PaintContext, minWidth: number, maxWidth: number,
                         minHeight: number, maxHeight: number, measure: MeasurePass): void {
        const rows = this.segmentComponents(context, maxWidth, maxHeight, measure);
        for (const row of rows) {
            const mw = maxWidth;
            for (const child of row) {
                child.measure(context, minWidth, mw, minHeight, maxHeight, measure);
            }
        }
    }

    override internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        if (this.mChildrenComponents.length === 0) return;

        const selfMeasure = measure.get(this);
        const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
        const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;

        const rows = this.segmentComponents(context, selfWidth, selfHeight, measure);

        let positionX = 0;
        let positionY = 0;

        // Compute total rows height for vertical positioning
        let rowsHeight = 0;
        for (const row of rows) {
            rowsHeight += this.minIntrinsicHeightForComponents(row);
        }

        switch (this.mVerticalPositioning) {
            case RowLayout.CENTER:
                positionY = (selfHeight - rowsHeight) / 2;
                break;
            case RowLayout.BOTTOM:
                positionY = selfHeight - rowsHeight;
                break;
        }

        const rowSize = new Size();
        const rowWidth = selfWidth;

        for (const row of rows) {
            const rowHeight = this.minIntrinsicHeightForComponents(row);
            this.internalLayoutMeasureForComponents(context, measure, row,
                rowWidth, rowHeight, positionX, positionY, rowSize);
            positionY += rowSize.getHeight();
        }
    }

    override write(buffer: WireBuffer): void {
        buffer.start(FlowLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(0); // animationId
        buffer.writeInt(this.mHorizontalPositioning);
        buffer.writeInt(this.mVerticalPositioning);
        buffer.writeFloat(this.mSpacedBy);
        buffer.writeInt(this.mMaxItemsInEachRow);
        buffer.writeInt(this.mMaxLines);
    }

    override apply(context: RemoteContext): void { super.apply(context); }

    override deepToString(indent: string): string {
        return `${indent}FlowLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }

    static override read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.declareId();
        const animationId = buffer.declareId();
        const horizontalPositioning = buffer.readInt();
        const verticalPositioning = buffer.readInt();
        const spacedBy = buffer.readFloat();
        const maxItemsInEachRow = buffer.readInt();
        const maxLines = buffer.readInt();
        const op = new FlowLayout(componentId, animationId,
            horizontalPositioning, verticalPositioning, spacedBy);
        op.mMaxItemsInEachRow = maxItemsInEachRow;
        op.mMaxLines = maxLines;
        operations.push(op);
    }
}
