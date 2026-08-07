// MeasurePass: stores measurement results for an entire component hierarchy.
// Matches Java MeasurePass.java.

import { ComponentMeasure } from './ComponentMeasure';
import type { Component } from '../Component';

export class MeasurePass {
    private mList = new Map<number, ComponentMeasure>();

    clear(): void { this.mList.clear(); }

    add(measure: ComponentMeasure): void {
        if (measure.mId === -1) throw new Error('Component has no id!');
        this.mList.set(measure.mId, measure);
    }

    contains(id: number): boolean { return this.mList.has(id); }

    get(c: Component): ComponentMeasure;
    get(id: number): ComponentMeasure;
    get(arg: Component | number): ComponentMeasure {
        if (typeof arg === 'number') {
            let m = this.mList.get(arg);
            if (!m) {
                m = new ComponentMeasure(arg, 0, 0, 0, 0, ComponentMeasure.GONE);
                this.mList.set(arg, m);
            }
            return m;
        }
        const c = arg as Component;
        const id = c.getComponentId();
        let m = this.mList.get(id);
        if (!m) {
            // Seed the entry with the component's own visibility. Without it the entry
            // defaults to VISIBLE, and Component.layout() then copies that back over
            // whatever a VisibilityModifier had set — so a GONE component silently
            // became visible again on every layout pass.
            m = new ComponentMeasure(id, c.getX(), c.getY(), c.getWidth(), c.getHeight(),
                                     c.getVisibility());
            this.mList.set(id, m);
        }
        return m;
    }
}
