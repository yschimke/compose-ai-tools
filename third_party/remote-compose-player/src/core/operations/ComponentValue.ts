// ComponentValue: read layout component dimensions/position into float variables.
// Matches Java ComponentValue.java.

import { Operation } from '../Operation';
import type { WireBuffer } from '../WireBuffer';
import type { RemoteContext } from '../RemoteContext';

export class ComponentValue extends Operation {
    static readonly OP_CODE = 150;
    static readonly WIDTH = 0;
    static readonly HEIGHT = 1;
    static readonly POS_X = 2;
    static readonly POS_Y = 3;
    static readonly POS_ROOT_X = 4;
    static readonly POS_ROOT_Y = 5;
    static readonly CONTENT_WIDTH = 6;
    static readonly CONTENT_HEIGHT = 7;

    private mType: number;
    private mComponentId: number;
    private mValueId: number;

    constructor(type: number, componentId: number, valueId: number) {
        super();
        this.mType = type; this.mComponentId = componentId; this.mValueId = valueId;
    }

    getType(): number { return this.mType; }
    getComponentId2(): number { return this.mComponentId; }
    getValueId(): number { return this.mValueId; }

    write(_buffer: WireBuffer): void { /* stub */ }

    apply(context: RemoteContext): void {
        const doc = context.getDocument();
        if (!doc) return;
        const component = doc.getComponent(this.mComponentId);
        if (!component) {
            // Fallback: use document dimensions
            if (this.mType === ComponentValue.WIDTH) {
                context.loadFloat(this.mValueId, doc.getWidth());
            } else if (this.mType === ComponentValue.HEIGHT) {
                context.loadFloat(this.mValueId, doc.getHeight());
            }
            return;
        }
        const lc = component as any;
        switch (this.mType) {
            case ComponentValue.WIDTH:
                context.loadFloat(this.mValueId, lc.getWidth ? lc.getWidth() : doc.getWidth());
                break;
            case ComponentValue.HEIGHT:
                context.loadFloat(this.mValueId, lc.getHeight ? lc.getHeight() : doc.getHeight());
                break;
            case ComponentValue.POS_X:
                context.loadFloat(this.mValueId, lc.getX ? lc.getX() : 0);
                break;
            case ComponentValue.POS_Y:
                context.loadFloat(this.mValueId, lc.getY ? lc.getY() : 0);
                break;
            case ComponentValue.POS_ROOT_X: {
                const loc = lc.getLocationInWindow ? lc.getLocationInWindow() : [0, 0];
                context.loadFloat(this.mValueId, loc[0]);
                break;
            }
            case ComponentValue.POS_ROOT_Y: {
                const loc = lc.getLocationInWindow ? lc.getLocationInWindow() : [0, 0];
                context.loadFloat(this.mValueId, loc[1]);
                break;
            }
            case ComponentValue.CONTENT_WIDTH:
                context.loadFloat(this.mValueId, lc.getContentWidth ? lc.getContentWidth() : (lc.getWidth ? lc.getWidth() : 0));
                break;
            case ComponentValue.CONTENT_HEIGHT:
                context.loadFloat(this.mValueId, lc.getContentHeight ? lc.getContentHeight() : (lc.getHeight ? lc.getHeight() : 0));
                break;
            default:
                context.loadFloat(this.mValueId, 0);
                break;
        }
    }

    deepToString(indent: string): string { return `${indent}ComponentValue(${this.mType}, ${this.mComponentId}, ${this.mValueId})`; }

    static read(buffer: WireBuffer, operations: Operation[]): void {
        const type = buffer.readInt();
        const componentId = buffer.readInt();
        const valueId = buffer.readInt();
        operations.push(new ComponentValue(type, componentId, valueId));
    }
}
