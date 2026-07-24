// RootLayoutComponent: the root of the component hierarchy.
// Port of Java RootLayoutComponent.java — uses MeasurePass for proper layout.

import { Component } from './Component';
import { PaintOperation } from '../../PaintOperation';
import { MeasurePass } from './measure/MeasurePass';
import type { Operation } from '../../Operation';
import type { WireBuffer } from '../../WireBuffer';
import type { RemoteContext } from '../../RemoteContext';
import type { PaintContext } from '../../PaintContext';

export class RootLayoutComponent extends Component {
    static readonly OP_CODE = 200;

    private mCurrentId = -1;
    private mHasTouchListeners = false;

    constructor(componentId: number = -1) {
        super(componentId);
    }

    getHasTouchListeners(): boolean { return this.mHasTouchListeners; }
    setHasTouchListeners(v: boolean): void { this.mHasTouchListeners = v; }

    assignIds(lastId: number): void {
        this.mCurrentId = lastId;
        this.assignId(this);
    }

    private assignId(component: Component): void {
        if (component.getComponentId() === -1) {
            this.mCurrentId--;
            (component as any).mComponentId = this.mCurrentId;
        }
        for (const op of component.getList()) {
            if (op instanceof Component) {
                this.assignId(op);
            }
        }
    }

    /** Measure then layout the tree of components */
    layoutTree(context: RemoteContext): void {
        if (!this.mNeedsMeasure) return;
        this.mNeedsMeasure = false;
        this.setWidth(context.mWidth);
        this.setHeight(context.mHeight);
        context.mViewportWidth = context.mWidth;
        context.mViewportHeight = context.mHeight;

        const measurePass = new MeasurePass();
        for (const op of this.getList()) {
            if (typeof (op as any).measure === 'function') {
                const paintContext = context.getPaintContext();
                if (paintContext) {
                    (op as any).measure(paintContext, 0, this.mWidth, 0, this.mHeight, measurePass);
                    if (typeof (op as any).layout === 'function') {
                        (op as any).layout(context, measurePass);
                    }
                }
            }
        }

    }

    /** Measure the document and layout components, returning first child size */
    measureDoc(context: RemoteContext, minWidth: number, maxWidth: number,
               minHeight: number, maxHeight: number): void {
        this.mNeedsMeasure = false;
        this.setWidth(context.mWidth);
        this.setHeight(context.mHeight);
        context.mViewportWidth = context.mWidth;
        context.mViewportHeight = context.mHeight;

        const measurePass = new MeasurePass();
        let firstComponent: Component | null = null;
        for (const op of this.getList()) {
            if (typeof (op as any).measure === 'function') {
                if (firstComponent === null && op instanceof Component) {
                    firstComponent = op;
                }
                const paintContext = context.getPaintContext();
                if (paintContext) {
                    (op as any).measure(paintContext, minWidth, maxWidth, minHeight, maxHeight, measurePass);
                    if (typeof (op as any).layout === 'function') {
                        (op as any).layout(context, measurePass);
                    }
                }
            }
        }
        if (firstComponent) {
            this.setWidth(firstComponent.getWidth());
            this.setHeight(firstComponent.getHeight());
        }
    }

    paint(paintContext: PaintContext): void {
        this.mNeedsRepaint = false;
        const remoteContext = paintContext.getContext();

        paintContext.save();
        if (!this.getParent()) {
            paintContext.clipRect(0, 0, this.mWidth, this.mHeight);
        }

        for (const op of this.getList()) {
            if (op instanceof PaintOperation) {
                op.paint(paintContext);
                remoteContext.incrementOpCount();
            }
        }

        paintContext.restore();
    }

    getComponent(id: number): Component | null {
        const queue: Component[] = [this];
        while (queue.length > 0) {
            const c = queue.shift()!;
            if (c.getComponentId() === id) return c;
            for (const child of c.getList()) {
                if (child instanceof Component) {
                    queue.push(child);
                }
            }
        }
        return null;
    }

    displayHierarchy(): string {
        return `RootLayout(${this.getWidth()}x${this.getHeight()})`;
    }

    onClick(context: RemoteContext, doc: any, x: number, y: number): boolean {
        for (const child of this.getList()) {
            if (typeof (child as any).onClick === 'function') {
                if ((child as any).onClick(context, doc, x, y)) return true;
            }
        }
        return false;
    }

    onTouchDown(context: RemoteContext, doc: any, x: number, y: number): boolean {
        for (const child of this.getList()) {
            if (typeof (child as any).onTouchDown === 'function') {
                if ((child as any).onTouchDown(context, doc, x, y)) return true;
            }
        }
        return false;
    }

    write(buffer: WireBuffer): void {
        buffer.start(RootLayoutComponent.OP_CODE);
        buffer.writeInt(this.getComponentId());
    }

    deepToString(indent: string): string {
        return `${indent}RootLayoutComponent(${this.getComponentId()})`;
    }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const componentId = buffer.readInt();
        const component = new RootLayoutComponent(componentId);
        operations.push(component);
    }
}
