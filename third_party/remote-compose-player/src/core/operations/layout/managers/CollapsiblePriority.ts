// CollapsiblePriority: utility for sorting components by collapsible priority.
// Port of Java CollapsiblePriority.java.

import { Component } from '../Component';
import { LayoutComponent } from '../LayoutComponent';
import { CollapsiblePriorityModifier } from '../modifiers/ModifierOperations';

export class CollapsiblePriority {
    static readonly HORIZONTAL = 0;
    static readonly VERTICAL = 1;

    static getPriority(c: Component, orientation: number): number {
        if (c instanceof LayoutComponent) {
            const priority = c.selfOrModifier(CollapsiblePriorityModifier);
            if (priority && priority.getOrientation() === orientation) {
                return priority.getPriority();
            }
        }
        return Number.MAX_VALUE;
    }

    static sortWithPriorities(components: Component[], orientation: number): Component[] {
        const sorted = [...components];
        sorted.sort((a, b) => {
            const p1 = CollapsiblePriority.getPriority(a, orientation);
            const p2 = CollapsiblePriority.getPriority(b, orientation);
            return p2 - p1;
        });
        return sorted;
    }
}
