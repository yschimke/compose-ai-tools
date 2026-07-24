// DrawContent: placeholder for layout-system content drawing.
// Matches Java DrawContent.java — extends PaintOperation.

import { PaintOperation } from '../PaintOperation';
import type { PaintContext } from '../PaintContext';
import type { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';

export class DrawContent extends PaintOperation {
    static readonly OP_CODE = 139;
    private mLayoutComponent: any = null;
    private mInProcessing = false;

    setComponent(component: any): void { this.mLayoutComponent = component; }

    write(_buffer: WireBuffer): void { /* stub */ }

    paint(context: PaintContext): void {
        if (this.mLayoutComponent != null) {
            if (!this.mInProcessing) {
                this.mInProcessing = true;
                this.mLayoutComponent.drawContent(context);
                this.mInProcessing = false;
            }
        }
    }

    deepToString(indent: string): string { return `${indent}DrawContent`; }

    static read(_buffer: WireBuffer, operations: Operation[]): void {
        operations.push(new DrawContent());
    }
}
