// CoreDocument: the state machine and orchestrator for RemoteCompose playback.
// Manages deserialization, component hierarchy, layout, input, and painting.

import type { Operation } from './Operation';
import { RemoteContext, ContextMode } from './RemoteContext';
import type { RemoteClock } from './RemoteClock';
import { SYSTEM_CLOCK } from './RemoteClock';
import { RemoteComposeState } from './RemoteComposeState';
import { RemoteComposeBuffer } from './RemoteComposeBuffer';
import type { IntMap } from './operations/utilities/IntMap';
import { Header } from './operations/Header';
import { ContainerEnd } from './operations/layout/ContainerEnd';
import { RootLayoutComponent } from './operations/layout/RootLayoutComponent';
import type { Component } from './operations/layout/Component';
import type { Container } from './operations/layout/Container';
import { TimeVariables } from './TimeVariables';
import { BackgroundModifier } from './operations/layout/modifiers/ModifierOperations';
import { LoopOperation } from './operations/layout/LoopOperation';
import { Theme, TextData, BitmapData, FloatConstant, RootContentBehavior } from './operations/DataOperations';
import { LoomManager } from './operations/loom/LoomManager';
import { RemapContext } from './operations/loom/RemapContext';
import { ExpansionContext, type ExpansionDocument, type ArrayAccess } from './operations/loom/ExpansionContext';
import { nestContainers, captureLoomBodies as captureLoomBodiesShared } from './operations/loom/nestContainers';
import { DataListIds } from './operations/DataListIds';
import { PatternDefine, PatternForEach, ReferencedOperations } from './operations/loom/PatternOperations';
import type { ByteSpan } from './RemoteComposeBuffer';
import { ColorTheme } from './operations/ColorTheme';
import { IntegerConstant } from './operations/IntegerConstant';
import { LongConstant } from './operations/LongConstant';
import { DataListFloat } from './operations/DataListFloat';
import type { TouchListener } from './TouchListener';

export class Version {
    mMajorVersion: number;
    mMinorVersion: number;
    mPatchVersion: number;

    constructor(major: number, minor: number, patch: number) {
        this.mMajorVersion = major;
        this.mMinorVersion = minor;
        this.mPatchVersion = patch;
    }

    supportsVersion(major: number, minor: number, patch: number): boolean {
        if (this.mMajorVersion !== major) return this.mMajorVersion > major;
        if (this.mMinorVersion !== minor) return this.mMinorVersion > minor;
        return this.mPatchVersion >= patch;
    }
}

export interface ClickAreaRepresentation {
    id: number;
    contentDescription: string;
    left: number;
    top: number;
    right: number;
    bottom: number;
    metadata: string;
}

export interface IdActionCallback {
    onAction(id: number, metadata: string): void;
}

export interface ActionCallback {
    onAction(name: string, value: any): void;
}

export class CoreDocument implements ExpansionDocument {
    static readonly MAJOR_VERSION = 1;
    static readonly MINOR_VERSION = 1;
    static readonly PATCH_VERSION = 0;
    static readonly DOCUMENT_API_LEVEL = 8;

    mOperations: Operation[] = [];
    mRootLayoutComponent: RootLayoutComponent | null = null;
    mRemoteComposeState = new RemoteComposeState();
    mVersion: Version | null = null;
    mWidth = 256;
    mHeight = 256;
    private mCapabilities = 0;
    private mProperties: IntMap<any> | null = null;
    private mContentDescription = '';

    mContentScroll = 0;
    mContentAlignment = 0;
    mContentSizing = 0;
    mContentMode = 0;

    private mBuffer: RemoteComposeBuffer | null = null;
    private mClock: RemoteClock;
    private mTimeVariables: TimeVariables;
    private mHeader: Header | null = null;

    private mClickAreas = new Set<ClickAreaRepresentation>();
    private mIdActionListeners = new Set<IdActionCallback>();
    private mActionCallbacks: ActionCallback[] = [];

    private mThemeColors: ColorTheme[] | null = null;
    private mTouchListeners = new Set<TouchListener>();
    private mAppliedTouchOperations = new Set<any>();
    private mNeedsRepaintFlag = -1;
    private mLastOpCount = 0;
    private mIsUpdateDoc = false;

    // --- Loom / macro expansion state ---
    private mLoomManager = new LoomManager();
    private mCurrentId = 0;
    private mProfileMask = 0;
    private mTextData = new Map<number, string>();
    private mReferencedOperations = new Map<number, ReferencedOperations>();
    private mArrays = new Map<number, ArrayAccess>();

    constructor(clock: RemoteClock = SYSTEM_CLOCK) {
        this.mClock = clock;
        this.mTimeVariables = new TimeVariables(clock);
    }

    /** Lazily collect all ColorTheme operations from the operation tree. */
    getThemedColors(): ColorTheme[] {
        if (this.mThemeColors === null) {
            const colors: ColorTheme[] = [];
            this.collectColorThemes(this.mOperations, colors);
            this.mThemeColors = colors;
        }
        return this.mThemeColors;
    }

    private collectColorThemes(ops: Operation[], out: ColorTheme[]): void {
        for (const op of ops) {
            if (op instanceof ColorTheme) {
                out.push(op);
            }
            // Recurse into containers (LoopOperation, ConditionalOperations, etc.)
            if (typeof (op as any).getList === 'function') {
                const children = (op as any).getList();
                if (Array.isArray(children)) {
                    this.collectColorThemes(children, out);
                }
            }
        }
    }

    // --- Version & metadata ---

    static getDocumentApiLevel(): number { return CoreDocument.DOCUMENT_API_LEVEL; }

    getContentDescription(): string { return this.mContentDescription; }
    setContentDescription(cd: string): void { this.mContentDescription = cd; }

    getRequiredCapabilities(): number { return this.mCapabilities; }
    setRequiredCapabilities(cap: number): void { this.mCapabilities = cap; }

    setVersion(major: number, minor: number, patch: number): void {
        this.mVersion = new Version(major, minor, patch);
    }

    getWidth(): number {
        if (this.mRootLayoutComponent) {
            const w = this.mRootLayoutComponent.getWidth();
            if (w > 0) return w;
        }
        return this.mWidth;
    }

    setWidth(w: number): void { this.mWidth = w; }

    getHeight(): number {
        if (this.mRootLayoutComponent) {
            const h = this.mRootLayoutComponent.getHeight();
            if (h > 0) return h;
        }
        return this.mHeight;
    }

    setHeight(h: number): void { this.mHeight = h; }

    setProperties(properties: IntMap<any> | null): void { this.mProperties = properties; }
    getProperty(key: number): any { return this.mProperties?.get(key) ?? null; }

    useFeature(feature: number, defaultValue = 0): boolean {
        if (!this.mHeader) return defaultValue !== 0;
        return this.mHeader.getInt(feature, defaultValue) !== 0;
    }

    // --- Buffer & state ---

    getBuffer(): RemoteComposeBuffer | null { return this.mBuffer; }
    setBuffer(buffer: RemoteComposeBuffer): void { this.mBuffer = buffer; }
    getRemoteComposeState(): RemoteComposeState { return this.mRemoteComposeState; }
    getClock(): RemoteClock { return this.mClock; }

    getRootLayoutComponent(): RootLayoutComponent | null { return this.mRootLayoutComponent; }

    getComponent(id: number): Component | null {
        if (!this.mRootLayoutComponent) return null;
        return this.mRootLayoutComponent.getComponent(id);
    }

    invalidateMeasure(): void {
        if (this.mRootLayoutComponent) this.mRootLayoutComponent.invalidateMeasure();
    }

    getOperations(): Operation[] { return this.mOperations; }
    getOpsPerFrame(): number { return this.mLastOpCount; }

    // --- Content behavior ---

    setRootContentBehavior(scroll: number, alignment: number, sizing: number, mode: number): void {
        this.mContentScroll = scroll;
        this.mContentAlignment = alignment;
        this.mContentSizing = sizing;
        this.mContentMode = mode;
    }

    // --- Loading ---

    // --- ExpansionDocument surface (used by the loom expansion engine) ---

    getNextId(): number { return ++this.mCurrentId; }
    getProfileMask(): number { return this.mProfileMask; }

    getText(id: number): string | null {
        const t = this.mTextData.get(id);
        return t === undefined ? null : t;
    }

    getArray(id: number): ArrayAccess | null {
        const collected = this.mArrays.get(id);
        if (collected) return collected;
        const arr = (this.mRemoteComposeState as unknown as { getArray?: (id: number) => any }).getArray?.(id);
        if (arr && typeof arr.getLength === 'function' && typeof arr.getId === 'function') {
            return arr as ArrayAccess;
        }
        return null;
    }

    getReferencedOperationsObject(id: number): Operation | null {
        return this.mReferencedOperations.get(id) ?? null;
    }

    initFromBuffer(buffer: RemoteComposeBuffer): void {
        this.mBuffer = buffer;
        this.mOperations = [];
        // Inflate into a flat list (ops tagged with byte spans for body capture).
        buffer.inflateFromBuffer(this.mOperations);

        // Compute the starting id counter above the max existing variable id,
        // mirroring Java initFromBuffer's maxId computation (START_VARIABLE_ID-ish).
        let maxId = 42; // above the system-global tier
        this.mTextData.clear();
        this.mReferencedOperations.clear();
        this.mProfileMask = 0;
        for (const op of this.mOperations) {
            const vid = (op as unknown as { getId?: () => number }).getId;
            if (typeof vid === 'function') {
                const id = vid.call(op);
                // ID_REGION_ARRAY (Java) ~ 0x40000000; ignore array-region ids
                if (typeof id === 'number' && id < 0x40000000 && id > maxId) {
                    maxId = id;
                }
            }
            if (op instanceof Header) {
                this.mHeader = op;
                op.setVersion(this);
                this.mProfileMask = (op as unknown as { getProfiles?: () => number }).getProfiles?.() ?? 0;
            }
            if (op instanceof TextData) {
                this.mTextData.set(op.mTextId, op.mText);
            }
            if (op instanceof ReferencedOperations) {
                this.mReferencedOperations.set(op.getId(), op);
            }
            if (op instanceof DataListIds) {
                this.mArrays.set(op.getCollectionId(), op);
            }
        }
        maxId += 10;
        this.mCurrentId = maxId;

        // Capture macro/loom body bytes from the original buffer (flat list).
        this.captureLoomBodies(this.mOperations, buffer);

        // Nest the flat list into a component tree.
        this.inflateComponents(this.mOperations);

        // Expand macros: flatten -> expand -> re-nest, then replace.
        this.expandMacros();
    }

    /**
     * Capture the raw body bytes for body-bearing loom containers from the
     * original source buffer. For each PatternDefine (container form),
     * PatternForEach, and ReferencedOperations in the FLAT list, the body bytes
     * are the concatenated bytes of all child ops: from the container's _byteEnd
     * up to the matching ContainerEnd's _byteStart, located via depth counting.
     */
    private captureLoomBodies(flat: Operation[], buffer: RemoteComposeBuffer): void {
        captureLoomBodiesShared(flat, buffer.getBuffer().getBuffer());
    }

    private expandMacros(): void {
        const remap = new RemapContext(this);
        const expansionContext = new ExpansionContext(
            this.mLoomManager, this, remap, new Map(), false, 0);
        const expanded = expansionContext.expandRecursiveTop(this.mOperations, this.mLoomManager);
        const nested = nestContainers(expanded);
        this.mOperations.length = 0;
        for (const op of nested) this.mOperations.push(op);

        // Re-resolve the root after expansion.
        this.mRootLayoutComponent = null;
        for (const op of this.mOperations) {
            if (op instanceof RootLayoutComponent) {
                this.mRootLayoutComponent = op;
                break;
            }
        }
    }

    private inflateComponents(operations: Operation[]): void {
        // Find header and extract its properties immediately
        for (const op of operations) {
            if (op instanceof Header) {
                this.mHeader = op;
                op.setVersion(this);
                break;
            }
        }

        // Build component hierarchy from flat list using Container/ContainerEnd.
        // Matches Java CoreDocument.inflateComponents():
        //   - `finalOps` is the top-level output list
        //   - `ops` points to the current target list (top-level or a container's child list)
        //   - When a Container is found, push it and redirect `ops` into its child list
        //   - When ContainerEnd is found, pop the container, inflate it, and add it to the parent
        //   - Non-container ops are added to the current `ops`
        const finalOps: Operation[] = [];
        let ops: Operation[] = finalOps;
        const containers: Container[] = [];

        for (const op of operations) {
            if (op instanceof RootLayoutComponent) {
                this.mRootLayoutComponent = op;
            }

            if (this.isContainer(op)) {
                const container = op as unknown as Container;
                containers.push(container);
                ops = container.getList();
            } else if (op instanceof ContainerEnd) {
                let container: Container | null = null;
                if (containers.length > 0) {
                    container = containers.pop()!;
                }
                // Reset ops to parent container's list, or top-level
                if (containers.length > 0) {
                    ops = containers[containers.length - 1].getList();
                } else {
                    ops = finalOps;
                }
                if (container) {
                    // Inflate Component containers (extracts layout children etc.)
                    if (typeof (container as any).inflate === 'function') {
                        (container as any).inflate();
                    }
                    // Add the container itself to its parent level
                    ops.push(container as unknown as Operation);
                }
                // Link BackgroundModifier to its parent component
                if (op instanceof BackgroundModifier && containers.length > 0) {
                    op.setComponent(containers[containers.length - 1]);
                }
            } else {
                // Non-container op: add to current level
                ops.push(op);
                if (op instanceof BackgroundModifier && containers.length > 0) {
                    op.setComponent(containers[containers.length - 1]);
                }
            }
        }

        // Replace the operations array contents with the properly nested list
        operations.length = 0;
        for (const op of finalOps) {
            operations.push(op);
        }
    }

    private isContainer(op: Operation): boolean {
        return typeof (op as any).getList === 'function' && !(op instanceof ContainerEnd);
    }

    // --- Context initialization ---

    initializeContext(context: RemoteContext): void {
        context.setDocument(this);
        // Share the document's state with the context (matches Java line 1136)
        context.mRemoteComposeState = this.mRemoteComposeState;
        this.mRemoteComposeState.setContext(context);
    }

    applyDataOperations(context: RemoteContext): void {
        context.setMode(ContextMode.DATA);
        this.mTimeVariables.updateTime(context);
        this.registerVariables(context, this.mOperations);
        this.applyOperations(context, this.mOperations);
        context.setMode(ContextMode.UNSET);
    }

    /** Recursively apply operations, matching Java CoreDocument.applyOperations. */
    private applyOperations(context: RemoteContext, ops: Operation[]): void {
        for (const op of ops) {
            if (typeof (op as any).updateVariables === 'function') {
                (op as any).updateVariables(context);
            }
            op.markNotDirty();
            context.incrementOpCount();
            if (this.isContainer(op)) {
                this.applyOperations(context, (op as any).getList());
            } else {
                op.apply(context);
            }
        }
    }

    private registerVariables(context: RemoteContext, ops: Operation[]): void {
        for (const op of ops) {
            if (typeof (op as any).registerListening === 'function') {
                (op as any).registerListening(context);
            }
            // Recurse into containers
            if (typeof (op as any).getList === 'function' && !(op instanceof ContainerEnd)) {
                this.registerVariables(context, (op as any).getList());
            }
        }
    }

    // --- Painting ---

    paint(context: RemoteContext, theme: number): void {
        // Ensure context uses document's state (matches Java lines 1620-1621)
        context.mRemoteComposeState = this.mRemoteComposeState;
        this.mRemoteComposeState.setContext(context);

        this.mClickAreas.clear();
        this.mTimeVariables.updateTime(context);

        // Run layout pass if needed
        if (this.mRootLayoutComponent && this.mRootLayoutComponent.needsMeasure()) {
            this.mRootLayoutComponent.layoutTree(context);
        }

        context.setMode(ContextMode.PAINT);
        context.clearLastOpCount();

        // Pre-load theme colors before painting (matches Java CoreDocument)
        const themeColors = this.getThemedColors();
        if (themeColors.length > 0 && theme !== context.getPaintTheme()) {
            for (const tc of themeColors) {
                tc.setTheme(context, theme);
            }
        }
        context.setPaintTheme(theme);
        context.setTheme(Theme.UNSPECIFIED);

        // Load density into float variable so expressions can reference it.
        // If density hasn't been set on the context, read from document properties.
        let density = context.getDensity();
        if (Number.isNaN(density) || density <= 0) {
            density = (this.getProperty(7 /* DOC_DENSITY_AT_GENERATION */) as number) || 1;
            context.setDensity(density);
        }
        context.loadFloat(27 /* ID_DENSITY */, density);

        // Save canvas state so content scaling transforms don't accumulate across frames
        const pc = context.getPaintContext();
        if (pc) pc.save();

        // Content scaling: if SIZING_SCALE, apply translate + scale before painting
        if (this.mContentSizing === RootContentBehavior.SIZING_SCALE) {
            const scaleOutput = [1, 1];
            this.computeScale(context.mWidth, context.mHeight, scaleOutput);
            const sw = scaleOutput[0];
            const sh = scaleOutput[1];
            const translateOutput = [0, 0];
            this.computeTranslate(context.mWidth, context.mHeight, sw, sh, translateOutput);
            if (pc) {
                pc.translate(translateOutput[0], translateOutput[1]);
                pc.scale(sw, sh);
            }
        } else {
            this.setWidth(context.mWidth);
            this.setHeight(context.mHeight);
        }

        for (const op of this.mOperations) {
            // Theme gating: skip ops that don't match the requested theme
            let apply = true;
            if (theme !== Theme.UNSPECIFIED) {
                const currentTheme = context.getTheme();
                apply = currentTheme === theme
                    || currentTheme === Theme.UNSPECIFIED
                    || op instanceof Theme;
            }
            if (apply) {
                const opIsDirty = op.isDirty();
                if (opIsDirty && typeof (op as any).updateVariables === 'function') {
                    op.markNotDirty();
                    (op as any).updateVariables(context);
                }
                context.incrementOpCount();
                op.apply(context);
            }
        }

        this.mLastOpCount = context.getLastOpCount();

        // Restore canvas state (matches save before content scaling)
        if (pc) pc.restore();

        context.setMode(ContextMode.UNSET);

        // Check if we need repaint
        const pc2 = context.getPaintContext();
        if (pc && pc.doesNeedsRepaint()) {
            this.mNeedsRepaintFlag = 1;
        } else {
            this.mNeedsRepaintFlag = this.mRemoteComposeState.getOpsToUpdate(context, this.mClock.millis());
        }
    }

    needsRepaint(): number { return this.mNeedsRepaintFlag; }

    needsMeasure(): boolean {
        return this.mRootLayoutComponent?.needsMeasure() ?? false;
    }

    measure(context: RemoteContext, minW: number, maxW: number, minH: number, maxH: number): void {
        if (this.mRootLayoutComponent) {
            this.mRootLayoutComponent.measureDoc(context, minW, maxW, minH, maxH);
        }
    }

    // --- Click areas ---

    addClickArea(id: number, contentDescription: string, left: number, top: number,
                 right: number, bottom: number, metadata: string): void {
        this.mClickAreas.add({ id, contentDescription, left, top, right, bottom, metadata });
    }

    getClickAreas(): Set<ClickAreaRepresentation> { return this.mClickAreas; }

    onClick(context: RemoteContext, x: number, y: number): boolean {
        for (const area of this.mClickAreas) {
            if (x >= area.left && x <= area.right && y >= area.top && y <= area.bottom) {
                this.runAction(area.id, area.metadata);
                return true;
            }
        }
        if (this.mRootLayoutComponent) {
            return this.mRootLayoutComponent.onClick(context, this, x, y);
        }
        return false;
    }

    // --- Actions ---

    addIdActionListener(listener: IdActionCallback): void { this.mIdActionListeners.add(listener); }
    getIdActionListeners(): Set<IdActionCallback> { return this.mIdActionListeners; }

    addActionCallback(callback: ActionCallback): void { this.mActionCallbacks.push(callback); }
    clearActionCallbacks(): void { this.mActionCallbacks = []; }

    runAction(id: number, metadata: string): void {
        for (const listener of this.mIdActionListeners) {
            listener.onAction(id, metadata);
        }
    }

    runNamedAction(name: string, value: any): void {
        for (const callback of this.mActionCallbacks) {
            callback.onAction(name, value);
        }
    }

    // --- Touch handling ---

    addTouchListener(listener: TouchListener): void {
        this.mTouchListeners.add(listener);
    }

    appliedTouchOperation(component: any): void {
        this.mAppliedTouchOperations.add(component);
    }

    hasTouchListener(): boolean {
        if (this.mTouchListeners.size > 0) return true;
        return this.mRootLayoutComponent?.getHasTouchListeners() ?? false;
    }

    touchDown(context: RemoteContext, x: number, y: number): boolean {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (const listener of this.mTouchListeners) {
            listener.touchDown(context, x, y);
        }
        let handled = this.mTouchListeners.size > 0;
        if (this.mRootLayoutComponent) {
            handled = this.mRootLayoutComponent.onTouchDown(context, this, x, y) || handled;
        }
        this.mNeedsRepaintFlag = 1;
        return handled;
    }

    touchDrag(context: RemoteContext, x: number, y: number): boolean {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (const listener of this.mTouchListeners) {
            listener.touchDrag(context, x, y);
        }
        let handled = this.mTouchListeners.size > 0;
        if (this.mRootLayoutComponent) {
            for (const component of this.mAppliedTouchOperations) {
                if (component.onTouchDrag(context, this, x, y, true)) {
                    handled = true;
                }
            }
        }
        return handled;
    }

    touchUp(context: RemoteContext, x: number, y: number, dx: number, dy: number): boolean {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (const listener of this.mTouchListeners) {
            listener.touchUp(context, x, y, dx, dy);
        }
        let handled = this.mTouchListeners.size > 0;
        if (this.mRootLayoutComponent) {
            for (const component of this.mAppliedTouchOperations) {
                if (component.onTouchUp(context, this, x, y, dx, dy, true)) {
                    handled = true;
                }
            }
            this.mAppliedTouchOperations.clear();
        }
        this.mNeedsRepaintFlag = 1;
        return handled;
    }

    touchCancel(context: RemoteContext, x: number, y: number, dx: number, dy: number): boolean {
        if (this.mRootLayoutComponent) {
            let handled = false;
            for (const component of this.mAppliedTouchOperations) {
                if (component.onTouchCancel(context, this, x, y, true)) {
                    handled = true;
                }
            }
            this.mAppliedTouchOperations.clear();
            this.mNeedsRepaintFlag = 1;
            return handled;
        }
        this.mNeedsRepaintFlag = 1;
        return false;
    }

    performClick(context: RemoteContext, id: number, metadata: string): boolean {
        for (const area of this.mClickAreas) {
            if (area.id === id) {
                this.runAction(area.id, area.metadata);
                return true;
            }
        }
        this.runAction(id, metadata);
        const component = this.getComponent(id);
        if (component) {
            return (component as any).onClick?.(context, this, -1, -1) ?? false;
        }
        return false;
    }

    // --- Content scaling and translation ---

    computeScale(w: number, h: number, scaleOutput: number[]): void {
        let contentScaleX = 1;
        let contentScaleY = 1;
        if (this.mContentSizing === RootContentBehavior.SIZING_SCALE) {
            let scaleX: number, scaleY: number, scale: number;
            switch (this.mContentMode) {
                case RootContentBehavior.SCALE_INSIDE:
                    scaleX = w / this.mWidth;
                    scaleY = h / this.mHeight;
                    scale = Math.min(1, Math.min(scaleX, scaleY));
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FIT:
                    scaleX = w / this.mWidth;
                    scaleY = h / this.mHeight;
                    scale = Math.min(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_WIDTH:
                    scale = w / this.mWidth;
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_HEIGHT:
                    scale = h / this.mHeight;
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_CROP:
                    scaleX = w / this.mWidth;
                    scaleY = h / this.mHeight;
                    scale = Math.max(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_BOUNDS:
                    contentScaleX = w / this.mWidth;
                    contentScaleY = h / this.mHeight;
                    break;
            }
        }
        scaleOutput[0] = contentScaleX;
        scaleOutput[1] = contentScaleY;
    }

    computeTranslate(w: number, h: number, contentScaleX: number, contentScaleY: number,
                     translateOutput: number[]): void {
        const horizontalContentAlignment = this.mContentAlignment & 0xF0;
        const verticalContentAlignment = this.mContentAlignment & 0xF;
        let translateX = 0;
        let translateY = 0;
        const contentWidth = this.mWidth * contentScaleX;
        const contentHeight = this.mHeight * contentScaleY;

        switch (horizontalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_START:
                break;
            case RootContentBehavior.ALIGNMENT_HORIZONTAL_CENTER:
                translateX = (w - contentWidth) / 2;
                break;
            case RootContentBehavior.ALIGNMENT_END:
                translateX = w - contentWidth;
                break;
        }
        switch (verticalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_TOP:
                break;
            case RootContentBehavior.ALIGNMENT_VERTICAL_CENTER:
                translateY = (h - contentHeight) / 2;
                break;
            case RootContentBehavior.ALIGNMENT_BOTTOM:
                translateY = h - contentHeight;
                break;
        }

        translateOutput[0] = translateX;
        translateOutput[1] = translateY;
    }

    // --- Update documents ---

    setUpdateDoc(v: boolean): void { this.mIsUpdateDoc = v; }
    isUpdateDoc(): boolean { return this.mIsUpdateDoc; }

    applyUpdate(delta: CoreDocument): void {
        const txtData = new Map<number, TextData>();
        const imgData = new Map<number, BitmapData>();
        const fltData = new Map<number, FloatConstant>();
        const intData = new Map<number, IntegerConstant>();
        const longData = new Map<number, LongConstant>();
        const floatListData = new Map<number, DataListFloat>();

        this.recursiveTraverse(this.mOperations, (op: Operation) => {
            if (op instanceof TextData) {
                txtData.set(op.mTextId, op);
            } else if (op instanceof BitmapData) {
                imgData.set(op.mImageId, op);
            } else if (op instanceof FloatConstant) {
                fltData.set(op.mId, op);
            } else if (op instanceof IntegerConstant) {
                intData.set(op.mId, op);
            } else if (op instanceof LongConstant) {
                longData.set(op.mId, op);
            } else if (op instanceof DataListFloat) {
                floatListData.set(op.mId, op);
            }
        });

        this.recursiveTraverse(delta.mOperations, (op: Operation) => {
            if (op instanceof TextData) {
                const existing = txtData.get(op.mTextId);
                if (existing) { existing.update(op); existing.markDirty(); }
            } else if (op instanceof BitmapData) {
                const existing = imgData.get(op.mImageId);
                if (existing) { existing.update(op); existing.markDirty(); }
            } else if (op instanceof FloatConstant) {
                const existing = fltData.get(op.mId);
                if (existing) { existing.update(op); existing.markDirty(); }
            } else if (op instanceof IntegerConstant) {
                const existing = intData.get(op.mId);
                if (existing) { existing.update(op); existing.markDirty(); }
            } else if (op instanceof LongConstant) {
                const existing = longData.get(op.mId);
                if (existing) { existing.update(op); existing.markDirty(); }
            } else if (op instanceof DataListFloat) {
                const existing = floatListData.get(op.mId);
                if (existing) { existing.update(op); existing.markDirty(); }
            }
        });
    }

    private recursiveTraverse(operations: Operation[], visitor: (op: Operation) => void): void {
        for (const op of operations) {
            if (typeof (op as any).getList === 'function') {
                this.recursiveTraverse((op as any).getList(), visitor);
            }
            visitor(op);
        }
    }

    // --- Diagnostics ---

    getNumberOfOps(): number { return this.mOperations.length; }

    toNestedString(): string {
        return this.mOperations.map(op => op.deepToString('  ')).join('\n');
    }

    toString(): string {
        return `CoreDocument(${this.mOperations.length} ops, ${this.mWidth}x${this.mHeight})`;
    }

    displayHierarchy(): string {
        if (this.mRootLayoutComponent) {
            return this.mRootLayoutComponent.displayHierarchy();
        }
        return this.toString();
    }
}
