// RemoteContext: abstract context used to playback RemoteCompose documents.

import { asNan, idFromNan } from './operations/Utils';
import { RemoteComposeState } from './RemoteComposeState';
import type { RemoteClock } from './RemoteClock';
import { SYSTEM_CLOCK } from './RemoteClock';
import type { PaintContext } from './PaintContext';
import type { CoreDocument } from './CoreDocument';
import type { VariableSupport } from './VariableSupport';
import type { IntMap } from './operations/utilities/IntMap';

export enum ContextMode {
    UNSET = 'UNSET',
    DATA = 'DATA',
    PAINT = 'PAINT'
}

export abstract class RemoteContext {
    private static readonly MAX_OP_COUNT = 20_000;

    private mClock: RemoteClock;
    mDocument!: CoreDocument;
    mRemoteComposeState = new RemoteComposeState();
    private mDocLoadTime: number;
    protected mPaintContext: PaintContext | null = null;
    protected mDensity = NaN;
    private mPaintTheme = -3;
    mMode = ContextMode.UNSET;
    private mDebug = 0;
    private mOpCount = 0;
    private mTheme = -1; // Theme.UNSPECIFIED
    mWidth = 0;
    mHeight = 0;
    mViewportWidth = 0;
    mViewportHeight = 0;
    private mAnimationTime = 0;
    private mAnimate = true;
    mLastComponent: any = null;
    currentTime = 0;
    private mTouchVersion = 0;

    constructor(clock: RemoteClock = SYSTEM_CLOCK) {
        this.mClock = clock;
        this.mDocLoadTime = clock.millis();
    }

    supportsVersion(major: number, minor: number, patch: number): boolean {
        return this.mDocument?.mVersion?.supportsVersion(major, minor, patch) ?? false;
    }

    getDensity(): number { return this.mDensity; }
    setDensity(density: number): void {
        if (!Number.isNaN(density) && density > 0) this.mDensity = density;
    }

    getDocLoadTime(): number { return this.mDocLoadTime; }
    setDocLoadTime(): void { this.mDocLoadTime = this.mClock.millis(); }

    isAnimationEnabled(): boolean { return this.mAnimate; }
    setAnimationEnabled(value: boolean): void { this.mAnimate = value; }

    setAnimationTime(time: number): void { this.mAnimationTime = time; }
    getAnimationTime(): number { return this.mAnimationTime; }

    getClock(): RemoteClock { return this.mClock; }
    setClock(clock: RemoteClock): void { this.mClock = clock; }

    setPaintTheme(theme: number): void { this.mPaintTheme = theme; }
    getPaintTheme(): number { return this.mPaintTheme; }
    setTouchVersion(v: number): void { this.mTouchVersion = v; }
    getTouchVersion(): number { return this.mTouchVersion; }

    getTheme(): number { return this.mTheme; }
    setTheme(theme: number): void { this.mTheme = theme; }
    getMode(): ContextMode { return this.mMode; }
    setMode(mode: ContextMode): void { this.mMode = mode; }

    getPaintContext(): PaintContext | null { return this.mPaintContext; }
    setPaintContext(paintContext: PaintContext): void { this.mPaintContext = paintContext; }

    getDocument(): CoreDocument | null { return this.mDocument; }
    setDocument(document: CoreDocument): void {
        this.mDocument = document;
        this.mClock = document.getClock();
    }

    isBasicDebug(): boolean { return this.mDebug === 1; }
    isVisualDebug(): boolean { return this.mDebug === 2; }
    isLayoutDebug(): boolean { return this.mDebug === 3; }
    setDebug(debug: number): void { this.mDebug = debug; }

    needsRepaint(): void {
        if (this.mPaintContext) this.mPaintContext.needsRepaint();
    }

    incrementOpCount(): void {
        this.mOpCount++;
        if (this.mOpCount > RemoteContext.MAX_OP_COUNT) {
            throw new Error('Too many operations executed');
        }
    }

    getLastOpCount(): number {
        const count = this.mOpCount;
        this.mOpCount = 0;
        return count;
    }

    clearLastOpCount(): void { this.mOpCount = 0; }

    // Utility: load font
    loadFont(fontId: number, fontData: Uint8Array): void {
        const info = this.getObject(fontId);
        if (info && (info as any).mFontData === fontData) return;
        this.putObject(fontId, { mFontId: fontId, mFontData: fontData, fontBuilder: null });
    }

    // --- Header ---
    header(majorVersion: number, minorVersion: number, patchVersion: number,
           width: number, height: number, capabilities: number,
           properties: IntMap<any> | null): void {
        this.mRemoteComposeState.setWindowWidth(width);
        this.mRemoteComposeState.setWindowHeight(height);
        if (this.mDocument) {
            this.mDocument.setVersion(majorVersion, minorVersion, patchVersion);
            this.mDocument.setWidth(width);
            this.mDocument.setHeight(height);
            this.mDocument.setRequiredCapabilities(capabilities);
            this.mDocument.setProperties(properties);
        }
    }

    setRootContentBehavior(scroll: number, alignment: number, sizing: number, mode: number): void {
        if (this.mDocument) this.mDocument.setRootContentBehavior(scroll, alignment, sizing, mode);
    }

    setDocumentContentDescription(contentDescriptionId: number): void {
        const cd = this.mRemoteComposeState.getFromId(contentDescriptionId) as string;
        if (this.mDocument) this.mDocument.setContentDescription(cd);
    }

    markVariableDirty(_id: number): void { /* empty */ }

    addTouchListener(touchExpression: any): void {
        if (this.mDocument) this.mDocument.addTouchListener(touchExpression);
    }
    createEdgeEffect(_direction: number): any { return null; }
    getListeners(_id: number): VariableSupport[] | null { return null; }
    getCollectionsAccess(): RemoteComposeState { return this.mRemoteComposeState; }

    // --- Abstract methods ---
    abstract loadPathData(instanceId: number, winding: number, path: Int32Array): void;
    abstract getPathData(instanceId: number): Int32Array | null;
    abstract loadVariableName(varName: string, varId: number, varType: number): void;
    abstract loadColor(id: number, color: number): void;
    abstract setNamedColorOverride(colorName: string, color: number): void;
    abstract setNamedStringOverride(stringName: string, value: string): void;
    abstract clearNamedStringOverride(stringName: string): void;
    abstract setNamedBooleanOverride(booleanName: string, value: boolean): void;
    abstract clearNamedBooleanOverride(booleanName: string): void;
    abstract setNamedIntegerOverride(integerName: string, value: number): void;
    abstract clearNamedIntegerOverride(integerName: string): void;
    abstract setNamedFloatOverride(floatName: string, value: number): void;
    abstract clearNamedFloatOverride(floatName: string): void;
    abstract setNamedLong(name: string, value: number): void;
    abstract setNamedDataOverride(dataName: string, value: any): void;
    abstract clearNamedDataOverride(dataName: string): void;
    abstract addCollection(id: number, collection: any): void;
    abstract putDataMap(id: number, map: any): void;
    abstract getDataMap(id: number): any;
    abstract runAction(id: number, metadata: string): void;
    abstract runNamedAction(id: number, value: any): void;
    abstract putObject(id: number, value: any): void;
    abstract getObject(id: number): any;
    abstract hapticEffect(type: number): void;
    abstract loadBitmap(imageId: number, encoding: number, type: number, width: number, height: number, bitmap: Uint8Array): void;
    abstract loadText(id: number, text: string): void;
    abstract getText(id: number): string | null;
    abstract loadFloat(id: number, value: number): void;
    abstract overrideFloat(id: number, value: number): void;
    abstract loadInteger(id: number, value: number): void;
    abstract overrideInteger(id: number, value: number): void;
    abstract overrideText(id: number, valueId: number): void;
    abstract loadAnimatedFloat(id: number, animatedFloat: any): void;
    abstract loadShader(id: number, value: any): void;
    abstract loadSound(id: number, data: Uint8Array): void;
    abstract playSound(id: number): void;
    abstract getFloat(id: number): number;
    abstract getInteger(id: number): number;
    abstract getLong(id: number): number;
    abstract getColor(id: number): number;
    abstract listensTo(id: number, variableSupport: VariableSupport): void;
    abstract updateOps(): number;
    abstract getShader(id: number): any;
    abstract addClickArea(id: number, contentDescriptionId: number, left: number, top: number, right: number, bottom: number, metadataId: number): void;

    // --- System variable IDs ---
    static readonly ID_CONTINUOUS_SEC = 1;
    static readonly ID_TIME_IN_SEC = 2;
    static readonly ID_TIME_IN_MIN = 3;
    static readonly ID_TIME_IN_HR = 4;
    static readonly ID_WINDOW_WIDTH = 5;
    static readonly ID_WINDOW_HEIGHT = 6;
    static readonly ID_COMPONENT_WIDTH = 7;
    static readonly ID_COMPONENT_HEIGHT = 8;
    static readonly ID_CALENDAR_MONTH = 9;
    static readonly ID_OFFSET_TO_UTC = 10;
    static readonly ID_WEEK_DAY = 11;
    static readonly ID_DAY_OF_MONTH = 12;
    static readonly ID_TOUCH_POS_X = 13;
    static readonly ID_TOUCH_POS_Y = 14;
    static readonly ID_TOUCH_VEL_X = 15;
    static readonly ID_TOUCH_VEL_Y = 16;
    static readonly ID_ACCELERATION_X = 17;
    static readonly ID_ACCELERATION_Y = 18;
    static readonly ID_ACCELERATION_Z = 19;
    static readonly ID_GYRO_ROT_X = 20;
    static readonly ID_GYRO_ROT_Y = 21;
    static readonly ID_GYRO_ROT_Z = 22;
    static readonly ID_MAGNETIC_X = 23;
    static readonly ID_MAGNETIC_Y = 24;
    static readonly ID_MAGNETIC_Z = 25;
    static readonly ID_LIGHT = 26;
    static readonly ID_DENSITY = 27;
    static readonly ID_API_LEVEL = 28;
    static readonly ID_TOUCH_EVENT_TIME = 29;
    static readonly ID_ANIMATION_TIME = 30;
    static readonly ID_ANIMATION_DELTA_TIME = 31;
    static readonly ID_EPOCH_SECOND = 32;
    static readonly ID_FONT_SIZE = 33;
    static readonly ID_DAY_OF_YEAR = 34;
    static readonly ID_YEAR = 35;
    static readonly ID_FIRST_BASELINE = 36;
    static readonly ID_LAST_BASELINE = 37;

    // NaN-encoded float versions of system variables
    static readonly FLOAT_DENSITY = asNan(27);
    static readonly FLOAT_CONTINUOUS_SEC = asNan(1);
    static readonly FLOAT_TIME_IN_SEC = asNan(2);
    static readonly FLOAT_TIME_IN_MIN = asNan(3);
    static readonly FLOAT_TIME_IN_HR = asNan(4);
    static readonly FLOAT_WINDOW_WIDTH = asNan(5);
    static readonly FLOAT_WINDOW_HEIGHT = asNan(6);
    static readonly FLOAT_COMPONENT_WIDTH = asNan(7);
    static readonly FLOAT_COMPONENT_HEIGHT = asNan(8);
    static readonly FLOAT_CALENDAR_MONTH = asNan(9);
    static readonly FLOAT_OFFSET_TO_UTC = asNan(10);
    static readonly FLOAT_WEEK_DAY = asNan(11);
    static readonly FLOAT_DAY_OF_MONTH = asNan(12);
    static readonly FLOAT_TOUCH_POS_X = asNan(13);
    static readonly FLOAT_TOUCH_POS_Y = asNan(14);
    static readonly FLOAT_TOUCH_VEL_X = asNan(15);
    static readonly FLOAT_TOUCH_VEL_Y = asNan(16);
    static readonly FLOAT_TOUCH_EVENT_TIME = asNan(29);
    static readonly FLOAT_ANIMATION_TIME = asNan(30);
    static readonly FLOAT_ANIMATION_DELTA_TIME = asNan(31);
    static readonly FLOAT_DAY_OF_YEAR = asNan(34);
    static readonly FLOAT_YEAR = asNan(35);
    static readonly FLOAT_API_LEVEL = asNan(28);
    static readonly FLOAT_FONT_SIZE = asNan(33);
    static readonly FIRST_BASELINE = asNan(36);
    static readonly LAST_BASELINE = asNan(37);

    static isTime(fl: number): boolean {
        const value = idFromNan(fl);
        return value >= RemoteContext.ID_CONTINUOUS_SEC && value <= RemoteContext.ID_DAY_OF_MONTH;
    }
}
