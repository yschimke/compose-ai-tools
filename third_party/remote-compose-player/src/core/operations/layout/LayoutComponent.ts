// LayoutComponent: base class for components that participate in layout.
// Port of Java LayoutComponent.java — handles modifiers, padding, children, paint.

import { Component, Visibility } from './Component';
import type { Operation } from '../../Operation';
import type { PaintContext } from '../../PaintContext';
import type { RemoteContext } from '../../RemoteContext';
import type { MeasurePass } from './measure/MeasurePass';
import { WidthModifier, HeightModifier, PaddingModifier,
         WidthInModifier, HeightInModifier, ZIndexModifier,
         GraphicsLayerModifier, BackgroundModifier, BorderModifier,
         RoundedClipRectModifier, ClipRectModifier, OffsetModifier,
         DrawContentModifier, VisibilityModifier, ClickModifier,
         TouchDownModifier, TouchUpModifier, TouchCancelModifier,
         ScrollModifier, MarqueeModifier } from './modifiers/ModifierOperations';
import { LayoutComputeOperation } from './modifiers/LayoutComputeOperation';
import { LayoutComponentContent } from './LayoutComponentContent';
import { CanvasContent } from './CanvasContent';
import { ComponentValue } from '../../operations/ComponentValue';
import { TouchExpression } from '../../operations/TouchExpression';
import type { ComponentMeasure } from './measure/ComponentMeasure';

export class LayoutComponent extends Component {
    // Extracted modifiers
    private mWidthMod: WidthModifier | null = null;
    private mHeightMod: HeightModifier | null = null;
    private mWidthInMod: WidthInModifier | null = null;
    private mHeightInMod: HeightInModifier | null = null;
    private mZIndexMod: ZIndexModifier | null = null;
    private mGraphicsLayerMod: GraphicsLayerModifier | null = null;

    // Padding
    mPaddingLeft = 0;
    mPaddingRight = 0;
    mPaddingTop = 0;
    mPaddingBottom = 0;

    // Children components extracted during inflate
    mChildrenComponents: Component[] = [];

    // Draw content operations (DrawContentModifier pattern)
    private mDrawContentOperations: Operation[] | null = null;

    // Component modifier operations (non-structural modifiers that need paint)
    private mComponentModifiers: Operation[] = [];

    // Content operations (paint ops from LayoutComponentContent/CanvasContent)
    private mContentOps: Operation[] = [];

    // Has CanvasLayout-style content
    mHasCanvasLayoutContent = false;

    // Computed layout modifiers (LayoutComputeOperation)
    private mComputedLayoutModifiers: LayoutComputeOperation[] | null = null;

    // ComponentValue bindings (Java ComponentData pattern)
    // Collected during inflate; updated during measure to expose dimensions as float variables
    private mComponentValues: ComponentValue[] | null = null;

    // Scroll measurement state
    private mScrollModifier: ScrollModifier | null = null;
    mScrollContentDimension = 0;
    mScrollHostDimension = 0;

    getWidthModifier(): WidthModifier | null { return this.mWidthMod; }
    getHeightModifier(): HeightModifier | null { return this.mHeightMod; }
    getWidthInModifier(): WidthInModifier | null { return this.mWidthInMod; }
    getHeightInModifier(): HeightInModifier | null { return this.mHeightInMod; }
    getScrollModifier(): ScrollModifier | null { return this.mScrollModifier; }

    inflate(): void {
        // Separate children ops into structure: modifiers, content, child components
        this.mChildrenComponents = [];
        this.mComponentModifiers = [];
        this.mContentOps = [];
        this.mPaddingLeft = 0;
        this.mPaddingRight = 0;
        this.mPaddingTop = 0;
        this.mPaddingBottom = 0;
        this.mComputedLayoutModifiers = null;

        for (const op of this.getList()) {
            if (op instanceof PaddingModifier) {
                // Accumulate padding (use resolved values for dynamic padding)
                this.mPaddingLeft += op.mLeftValue;
                this.mPaddingTop += op.mTopValue;
                this.mPaddingRight += op.mRightValue;
                this.mPaddingBottom += op.mBottomValue;
                // Also keep in modifier list for paint-time translation
                // (matches Java ComponentModifiers pattern)
                this.mComponentModifiers.push(op);
            } else if (op instanceof WidthModifier) {
                this.mWidthMod = op;
            } else if (op instanceof HeightModifier) {
                this.mHeightMod = op;
            } else if (op instanceof WidthInModifier) {
                this.mWidthInMod = op;
            } else if (op instanceof HeightInModifier) {
                this.mHeightInMod = op;
            } else if (op instanceof ZIndexModifier) {
                this.mZIndexMod = op;
                this.mZIndex = (op as any).mValue ?? 0;
            } else if (op instanceof GraphicsLayerModifier) {
                this.mGraphicsLayerMod = op;
            } else if (op instanceof VisibilityModifier) {
                (op as VisibilityModifier).setParent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof BackgroundModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof BorderModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof RoundedClipRectModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof ClipRectModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof OffsetModifier) {
                this.mComponentModifiers.push(op);
            } else if (op instanceof ClickModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof TouchDownModifier || op instanceof TouchUpModifier || op instanceof TouchCancelModifier) {
                this.mComponentModifiers.push(op);
            } else if (op instanceof ScrollModifier) {
                this.mScrollModifier = op;
                this.mComponentModifiers.push(op);
            } else if (op instanceof MarqueeModifier) {
                op.setComponent(this);
                this.mComponentModifiers.push(op);
            } else if (op instanceof LayoutComputeOperation) {
                if (this.mComputedLayoutModifiers === null) {
                    this.mComputedLayoutModifiers = [];
                }
                op.setParent(this);
                this.mComputedLayoutModifiers.push(op);
            } else if (op instanceof DrawContentModifier) {
                // The operations before this modifier are drawn before content,
                // operations after are drawn after content
                this.mDrawContentOperations = [];
            } else if (op instanceof LayoutComponentContent || op instanceof CanvasContent) {
                // Content container — extract its children.
                // IMPORTANT: this check must come BEFORE `instanceof Component`
                // because LCC and CC extend Component.
                for (const contentOp of (op as any).getList()) {
                    if (contentOp instanceof Component) {
                        contentOp.setParent(this);
                        this.mChildrenComponents.push(contentOp);
                        if (contentOp instanceof LayoutComponent) {
                            contentOp.inflate();
                        }
                        // Detect CanvasContent nested inside LayoutComponentContent
                        if (contentOp instanceof CanvasContent) {
                            this.mHasCanvasLayoutContent = true;
                        }
                    } else if (contentOp instanceof ComponentValue) {
                        // Java ComponentData pattern: collect on parent component
                        // so we can update float vars with our dimensions during measure
                        if (this.mComponentValues === null) {
                            this.mComponentValues = [];
                        }
                        this.mComponentValues.push(contentOp);
                    } else {
                        if (typeof (contentOp as any).setComponent === 'function') {
                            (contentOp as any).setComponent(this);
                        }
                        this.mContentOps.push(contentOp);
                    }
                }
                if (op instanceof CanvasContent) {
                    this.mHasCanvasLayoutContent = true;
                }
            } else if (op instanceof Component) {
                op.setParent(this);
                this.mChildrenComponents.push(op);
                if (op instanceof LayoutComponent) {
                    op.inflate();
                }
            } else {
                // Other operations go into component modifiers or content
                if (this.mDrawContentOperations !== null) {
                    this.mDrawContentOperations.push(op);
                } else {
                    this.mContentOps.push(op);
                }
            }
        }

        // Default width/height to WRAP if not specified
        if (!this.mWidthMod) {
            this.mWidthMod = new WidthModifier(WidthModifier.WRAP, 0);
        }
        if (!this.mHeightMod) {
            this.mHeightMod = new HeightModifier(HeightModifier.WRAP, 0);
        }

        // Initialize position and padding
        this.mX = 0;
        this.mY = 0;
    }

    // --- Layout helpers ---

    isWidthFill(): boolean {
        return this.mWidthMod?.getType() === WidthModifier.FILL;
    }

    isHeightFill(): boolean {
        return this.mHeightMod?.getType() === HeightModifier.FILL;
    }

    isWidthWrap(): boolean {
        return this.mWidthMod?.getType() === WidthModifier.WRAP;
    }

    isHeightWrap(): boolean {
        return this.mHeightMod?.getType() === HeightModifier.WRAP;
    }

    isWidthExact(): boolean {
        const t = this.mWidthMod?.getType();
        return t === WidthModifier.EXACT || t === WidthModifier.EXACT_DP;
    }

    isHeightExact(): boolean {
        const t = this.mHeightMod?.getType();
        return t === HeightModifier.EXACT || t === HeightModifier.EXACT_DP;
    }

    hasWidthWeight(): boolean {
        return this.mWidthMod?.getType() === WidthModifier.WEIGHT;
    }

    hasHeightWeight(): boolean {
        return this.mHeightMod?.getType() === HeightModifier.WEIGHT;
    }

    getWidthModValue(): number { return this.mWidthMod?.getValue() ?? 0; }
    getHeightModValue(): number { return this.mHeightMod?.getValue() ?? 0; }

    override hasComputedLayout(): boolean {
        return this.mComputedLayoutModifiers !== null;
    }

    override applyComputedLayout(type: number, context: PaintContext,
                                 m: ComponentMeasure, parent: ComponentMeasure): boolean {
        if (this.mComputedLayoutModifiers !== null) {
            let needsMeasure = false;
            for (const modifier of this.mComputedLayoutModifiers) {
                if (modifier.getType() === type) {
                    needsMeasure = modifier.applyToMeasure(context, m, parent) || needsMeasure;
                }
            }
            return needsMeasure;
        }
        return false;
    }

    // --- Intrinsic size (matches Java LayoutComponent) ---

    override minIntrinsicHeight(): number {
        const height = this.computeModifierDefinedHeight();
        let childrenHeight = 0;
        for (const c of this.mChildrenComponents) {
            childrenHeight = Math.max(childrenHeight, c.minIntrinsicHeight());
        }
        return Math.max(height, childrenHeight);
    }

    override minIntrinsicWidth(): number {
        const width = this.computeModifierDefinedWidth();
        let childrenWidth = 0;
        for (const c of this.mChildrenComponents) {
            childrenWidth = Math.max(childrenWidth, c.minIntrinsicWidth());
        }
        return Math.max(width, childrenWidth);
    }

    computeModifierDefinedWidth(): number {
        if (this.isWidthExact()) return this.getWidthModValue() + this.mPaddingLeft + this.mPaddingRight;
        return -1;
    }

    computeModifierDefinedHeight(): number {
        if (this.isHeightExact()) return this.getHeightModValue() + this.mPaddingTop + this.mPaddingBottom;
        return -1;
    }

    // --- ComponentValue (Java ComponentData pattern) ---

    /** Update bound float variables with this component's dimensions/position.
     *  Matches Java Component.updateComponentValues — called during both measure
     *  (for WIDTH/HEIGHT) and layout (for all types including POS_X/POS_Y). */
    updateComponentValues(context: RemoteContext, measuredW: number, measuredH: number): void {
        if (this.mComponentValues === null) return;
        for (const cv of this.mComponentValues) {
            switch (cv.getType()) {
                case ComponentValue.WIDTH:
                    context.loadFloat(cv.getValueId(), measuredW);
                    break;
                case ComponentValue.HEIGHT:
                    context.loadFloat(cv.getValueId(), measuredH);
                    break;
                case ComponentValue.POS_X:
                    context.loadFloat(cv.getValueId(), this.mX);
                    break;
                case ComponentValue.POS_Y:
                    context.loadFloat(cv.getValueId(), this.mY);
                    break;
                case ComponentValue.POS_ROOT_X: {
                    const loc = this.getLocationInWindow();
                    context.loadFloat(cv.getValueId(), loc[0]);
                    break;
                }
                case ComponentValue.POS_ROOT_Y: {
                    const loc = this.getLocationInWindow();
                    context.loadFloat(cv.getValueId(), loc[1]);
                    break;
                }
                case ComponentValue.CONTENT_WIDTH:
                    context.loadFloat(cv.getValueId(), measuredW - this.mPaddingLeft - this.mPaddingRight);
                    break;
                case ComponentValue.CONTENT_HEIGHT:
                    context.loadFloat(cv.getValueId(), measuredH - this.mPaddingTop - this.mPaddingBottom);
                    break;
            }
        }
    }

    // --- Layout ---

    layout(context: RemoteContext, measure: MeasurePass): void {
        super.layout(context, measure);
        // Update ComponentValue bindings now that positions are set
        // (matches Java Component.layout() calling updateComponentValues after setLayoutPosition)
        this.updateComponentValues(context, this.mWidth, this.mHeight);
        // Layout children
        for (const child of this.mChildrenComponents) {
            child.layout(context, measure);
        }
        this.mNeedsMeasure = false;
    }

    /** Walk modifiers reducing dimensions by padding and passing to decorators.
     *  Matches Java ComponentModifiers.layout(). */
    layoutModifiers(w: number, h: number): void {
        let currentW = w;
        let currentH = h;
        for (const mod of this.mComponentModifiers) {
            if (mod instanceof PaddingModifier) {
                currentW -= mod.mLeftValue + mod.mRightValue;
                currentH -= mod.mTopValue + mod.mBottomValue;
            }
            if (typeof (mod as any).layoutDecorator === 'function') {
                (mod as any).layoutDecorator(currentW, currentH);
            }
        }
    }

    // --- Paint ---

    drawContent(paintContext: PaintContext): void {
        // Used by DrawContent operation
        paintContext.matrixSave();
        paintContext.matrixTranslate(-this.mX, -this.mY);
        this.paintingComponent(paintContext);
        paintContext.matrixRestore();
    }

    paint(paintContext: PaintContext): void {
        if (this.mDrawContentOperations !== null && this.mDrawContentOperations.length > 0) {
            // Draw content operations handle their own painting
            paintContext.matrixSave();
            paintContext.matrixTranslate(this.mX, this.mY);
            const context = paintContext.getContext();
            for (const op of this.mDrawContentOperations) {
                context.incrementOpCount();
                if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                    op.markNotDirty();
                    (op as any).updateVariables(context);
                }
                op.apply(context);
            }
            paintContext.matrixRestore();
        } else {
            super.paint(paintContext);
        }
    }

    paintingComponent(paintContext: PaintContext): void {
        if (Visibility.isGone(this.mVisibility)) return;
        const context = paintContext.getContext();

        paintContext.matrixSave();
        paintContext.matrixTranslate(this.mX, this.mY);

        // Paint modifiers with padding translation (matches Java ComponentModifiers.paint())
        let tx = 0;
        let ty = 0;
        for (const mod of this.mComponentModifiers) {
            context.incrementOpCount();
            if (mod.isDirty() && typeof (mod as any).updateVariables === 'function') {
                mod.markNotDirty();
                (mod as any).updateVariables(context);
            }
            if (mod instanceof PaddingModifier) {
                paintContext.matrixTranslate(mod.mLeftValue, mod.mTopValue);
                tx += mod.mLeftValue;
                ty += mod.mTopValue;
            } else {
                mod.apply(context);
            }
        }
        // Back out modifier translations
        paintContext.matrixTranslate(-tx, -ty);

        // Translate by total padding for content
        paintContext.matrixTranslate(this.mPaddingLeft, this.mPaddingTop);

        // Paint content operations (non-layout draw ops)
        for (const op of this.mContentOps) {
            context.incrementOpCount();
            if (op.isDirty() && typeof (op as any).updateVariables === 'function') {
                op.markNotDirty();
                (op as any).updateVariables(context);
            }
            op.apply(context);
        }

        // Paint children sorted by z-index
        const children = this.mChildrenComponents;
        if (children.length > 1) {
            // Check if z-index sorting is needed
            let needsSort = false;
            for (const c of children) {
                if (c.mZIndex !== 0) { needsSort = true; break; }
            }
            if (needsSort) {
                const sorted = [...children].sort((a, b) => a.mZIndex - b.mZIndex);
                for (const child of sorted) {
                    if (!Visibility.isGone(child.mVisibility)) {
                        child.paint(paintContext);
                    }
                }
            } else {
                for (const child of children) {
                    if (!Visibility.isGone(child.mVisibility)) {
                        child.paint(paintContext);
                    }
                }
            }
        } else {
            for (const child of children) {
                if (!Visibility.isGone(child.mVisibility)) {
                    child.paint(paintContext);
                }
            }
        }

        paintContext.matrixRestore();
    }

    /** Matches Java Component.updateVariables — re-push dimension/position values
     *  when a component is marked dirty (e.g. by animation or expression change). */
    override updateVariables(context: RemoteContext): void {
        if (this.mComponentValues !== null && this.mComponentValues.length > 0) {
            this.updateComponentValues(context, this.mWidth, this.mHeight);
        }
    }

    // Inherits apply() from Component → PaintOperation chain.
    // No override needed — matches Java LayoutComponent which does not override apply().

    // --- Scroll ---

    getScrollX(): number { return 0; }
    getScrollY(): number { return 0; }

    onClick(context: RemoteContext, doc: any, x: number, y: number): boolean {
        // Check children first
        for (let i = this.mChildrenComponents.length - 1; i >= 0; i--) {
            const child = this.mChildrenComponents[i];
            if (child instanceof Component) {
                if (child.onClick(context, doc, x - this.mX - this.mPaddingLeft,
                                   y - this.mY - this.mPaddingTop)) return true;
            }
        }
        // Check if click is within our bounds
        if (x >= this.mX && x <= this.mX + this.mWidth &&
            y >= this.mY && y <= this.mY + this.mHeight) {
            // Check for ClickModifier in our component modifiers
            for (const mod of this.mComponentModifiers) {
                if (mod instanceof ClickModifier) {
                    return mod.onClick(context, doc, x, y);
                }
            }
        }
        return false;
    }

    onTouchDown(context: RemoteContext, doc: any, x: number, y: number): boolean {
        let handled = false;
        // Coordinates relative to this component's content area (for child dispatch)
        const cx = x - this.mX - this.mPaddingLeft;
        const cy = y - this.mY - this.mPaddingTop;
        // Dispatch to child LayoutComponents
        for (const child of this.mChildrenComponents) {
            if (child instanceof LayoutComponent) {
                handled = child.onTouchDown(context, doc, cx, cy) || handled;
            }
        }
        if (x >= this.mX && x <= this.mX + this.mWidth &&
            y >= this.mY && y <= this.mY + this.mHeight) {
            // Coordinates relative to this component origin (for TouchExpression bounds)
            const lx = x - this.mX;
            const ly = y - this.mY;
            // Check mContentOps for TouchExpression (direct content)
            for (const op of this.mContentOps) {
                if (op instanceof TouchExpression) {
                    op.updateVariables(context);
                    op.touchDown(context, lx, ly);
                    doc.appliedTouchOperation(this);
                    handled = true;
                }
            }
            // Check non-LayoutComponent children (e.g. CanvasContent) for nested TouchExpression
            for (const child of this.mChildrenComponents) {
                if (!(child instanceof LayoutComponent)) {
                    handled = this.dispatchTouchDownToOps(child.getList(), context, doc, lx, ly) || handled;
                }
            }
            for (const mod of this.mComponentModifiers) {
                if (mod instanceof TouchDownModifier) {
                    mod.onTouchDown(context);
                    handled = true;
                }
            }
        }
        return handled;
    }

    /** Recursively search operation list for TouchExpression and dispatch touchDown */
    private dispatchTouchDownToOps(ops: Operation[], context: RemoteContext, doc: any,
                                    lx: number, ly: number): boolean {
        let handled = false;
        for (const op of ops) {
            if (op instanceof TouchExpression) {
                op.updateVariables(context);
                op.touchDown(context, lx, ly);
                doc.appliedTouchOperation(this);
                handled = true;
            } else if (typeof (op as any).getList === 'function') {
                handled = this.dispatchTouchDownToOps((op as any).getList(), context, doc, lx, ly) || handled;
            }
        }
        return handled;
    }

    getLocationInWindow(): [number, number] {
        let x = this.mX + this.mPaddingLeft;
        let y = this.mY + this.mPaddingTop;
        let parent = this.getParent();
        while (parent) {
            x += parent.mX;
            y += parent.mY;
            if (parent instanceof LayoutComponent) {
                x += parent.mPaddingLeft;
                y += parent.mPaddingTop;
            }
            parent = parent.getParent();
        }
        return [x, y];
    }
}
