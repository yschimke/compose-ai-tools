// CanvasLayout: container for both drawing operations and child components.
// Port of Java CanvasLayout.java — extends BoxLayout, overrides layout for canvas content.

import { BoxLayout } from './BoxLayout';
import { Component } from '../Component';
import type { Operation } from '../../../Operation';
import type { WireBuffer } from '../../../WireBuffer';
import type { RemoteContext } from '../../../RemoteContext';
import type { PaintContext } from '../../../PaintContext';
import type { MeasurePass } from '../measure/MeasurePass';

export class CanvasLayout extends BoxLayout {
    static readonly OP_CODE = 205;

    constructor(componentId: number, animationId: number) {
        super(componentId, animationId, 0, 0);
    }

    internalLayoutMeasure(context: PaintContext, measure: MeasurePass): void {
        if (this.mHasCanvasLayoutContent) {
            // Canvas content: size all children to our component's size
            const selfMeasure = measure.get(this);
            const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
            const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
            for (const child of this.mChildrenComponents) {
                const m = measure.get(child);
                m.setX(0);
                m.setY(0);
                m.setW(selfWidth);
                m.setH(selfHeight);
            }
        } else {
            super.internalLayoutMeasure(context, measure);
        }
    }

    write(buffer: WireBuffer): void {
        buffer.start(CanvasLayout.OP_CODE);
        buffer.writeInt(this.getComponentId());
        buffer.writeInt(this.mAnimationId);
    }

    apply(context: RemoteContext): void { super.apply(context); }

    deepToString(indent: string): string {
        return `${indent}CanvasLayout(${this.getComponentId()})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const animationId = buffer.readInt();
        operations.push(new CanvasLayout(componentId, animationId));
    }
}
