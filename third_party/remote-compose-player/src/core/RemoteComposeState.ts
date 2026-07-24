// RemoteComposeState: runtime state for a RemoteCompose document.
// Contains variable values, caches, listener management.

import { IntMap } from './operations/utilities/IntMap';
import { IntFloatMap } from './operations/utilities/IntFloatMap';
import { IntIntMap } from './operations/utilities/IntIntMap';
import type { VariableSupport } from './VariableSupport';
import type { RemoteContext } from './RemoteContext';

export class RemoteComposeState {
    static readonly START_ID = 42;
    static readonly BITMAP_TEXTURE_ID_OFFSET = 2000;
    private static readonly MAX_DATA = 10000;

    private mIntDataMap = new IntMap<any>();
    private mIntWrittenMap = new IntMap<boolean>();
    private mDataIntMap = new Map<any, number>();
    private mFloatMap = new IntFloatMap();
    private mIntegerMap = new IntIntMap();
    private mColorMap = new IntIntMap();
    private mDataMapMap = new IntMap<any>();
    private mObjectMap = new IntMap<any>();
    private mPathMap = new IntMap<any>();
    private mPathData = new IntMap<Int32Array>();
    private mPathWinding = new IntIntMap();
    private mColorOverride = new IntIntMap();
    private mCollectionMap = new IntMap<any>();

    private mDataOverride = new Array<boolean>(RemoteComposeState.MAX_DATA).fill(false);
    private mIntegerOverride = new Array<boolean>(RemoteComposeState.MAX_DATA).fill(false);
    private mFloatOverride = new Array<boolean>(RemoteComposeState.MAX_DATA).fill(false);

    private mNextId = RemoteComposeState.START_ID;
    private mRemoteContext: RemoteContext | null = null;

    mVarListeners = new IntMap<VariableSupport[]>();
    mAllVarListeners: VariableSupport[] = [];

    mLastRepaint = NaN;
    mRepaintSeconds = NaN;

    getFromId(id: number): any { return this.mIntDataMap.get(id); }
    containsId(id: number): boolean { return this.mIntDataMap.get(id) != null; }

    dataGetId(data: any): number {
        const res = this.mDataIntMap.get(data);
        return res ?? -1;
    }

    cacheData(item: any, idOrType?: number): number {
        if (idOrType !== undefined && typeof item !== 'number') {
            // cacheData(item, type) - type-based ID
            // Simplified: just use next ID
            const id = this.createNextAvailableId();
            this.mDataIntMap.set(item, id);
            this.mIntDataMap.put(id, item);
            return id;
        }
        const id = this.createNextAvailableId();
        this.mDataIntMap.set(item, id);
        this.mIntDataMap.put(id, item);
        return id;
    }

    cacheDataWithId(id: number, item: any): void {
        this.mDataIntMap.set(item, id);
        this.mIntDataMap.put(id, item);
    }

    updateData(id: number, item: any): void {
        if (id < RemoteComposeState.MAX_DATA && this.mDataOverride[id]) return;
        const previous = this.mIntDataMap.get(id);
        if (previous !== item) {
            this.mDataIntMap.delete(previous);
            this.mDataIntMap.set(item, id);
            this.mIntDataMap.put(id, item);
            this.updateListeners(id);
        }
    }

    getPath(id: number): any { return this.mPathMap.get(id); }
    putPath(id: number, path: any): void { this.mPathMap.put(id, path); }

    putPathData(id: number, data: Int32Array): void {
        this.mPathData.put(id, data);
        this.mPathMap.remove(id);
    }

    getPathData(id: number): Int32Array | null { return this.mPathData.get(id); }
    getPathWinding(id: number): number { return this.mPathWinding.get(id); }
    putPathWinding(id: number, winding: number): void { this.mPathWinding.put(id, winding); }

    cacheFloat(id: number | undefined, item: number): number {
        if (id === undefined) {
            id = this.createNextAvailableId();
        }
        this.mFloatMap.put(id, item);
        return id;
    }

    updateFloat(id: number, value: number): void {
        if (id < RemoteComposeState.MAX_DATA && this.mFloatOverride[id]) return;
        const previous = this.mFloatMap.get(id);
        if (previous !== value) {
            this.mFloatMap.put(id, value);
            this.mIntegerMap.put(id, Math.trunc(value));
            this.updateListeners(id);
        }
    }

    overrideFloat(id: number, value: number): void {
        const previous = this.mFloatMap.get(id);
        if (previous !== value) {
            this.mFloatMap.put(id, value);
            this.mIntegerMap.put(id, Math.trunc(value));
            if (id < RemoteComposeState.MAX_DATA) this.mFloatOverride[id] = true;
            this.updateListeners(id);
        }
    }

    updateInteger(id: number, value: number): void {
        if (id < RemoteComposeState.MAX_DATA && this.mIntegerOverride[id]) return;
        const previous = this.mIntegerMap.get(id);
        if (previous !== value) {
            this.mFloatMap.put(id, value);
            this.mIntegerMap.put(id, value);
            this.updateListeners(id);
        }
    }

    overrideInteger(id: number, value: number): void {
        const previous = this.mIntegerMap.get(id);
        if (previous !== value) {
            this.mIntegerMap.put(id, value);
            this.mFloatMap.put(id, value);
            if (id < RemoteComposeState.MAX_DATA) this.mIntegerOverride[id] = true;
            this.updateListeners(id);
        }
    }

    getFloatEntries(): Array<[number, number]> {
        const entries: Array<[number, number]> = [];
        this.mFloatMap.forEach((key, value) => entries.push([key, value]));
        return entries;
    }

    getFloat(id: number): number { return this.mFloatMap.get(id); }
    getInteger(id: number): number { return this.mIntegerMap.get(id); }
    getColor(id: number): number { return this.mColorMap.get(id); }

    updateColor(id: number, color: number): void {
        if (this.mColorOverride.contains(id)) return;
        this.mColorMap.put(id, color);
        this.updateListeners(id);
    }

    overrideColor(id: number, color: number): void {
        this.mColorOverride.put(id, 1);
        this.mColorMap.put(id, color);
        this.updateListeners(id);
    }

    clearColorOverride(): void { this.mColorOverride.clear(); }

    private updateListeners(id: number): void {
        const v = this.mVarListeners.get(id);
        if (v && this.mRemoteContext) {
            for (const c of v) {
                c.markDirty();
            }
        }
    }

    listenToVar(id: number, variableSupport: VariableSupport): void {
        let v = this.mVarListeners.get(id);
        if (!v) {
            v = [];
            this.mVarListeners.put(id, v);
        }
        if (!v.includes(variableSupport)) {
            v.push(variableSupport);
            this.mAllVarListeners.push(variableSupport);
        }
    }

    getListeners(id: number): VariableSupport[] | null {
        return this.mVarListeners.get(id);
    }

    hasListener(id: number): boolean {
        return this.mVarListeners.get(id) != null;
    }

    getOpsToUpdate(context: RemoteContext, currentTime: number): number {
        if (this.mVarListeners.get(1) != null) return 1; // ID_CONTINUOUS_SEC
        if (this.mVarListeners.get(30) != null) return 1; // ID_ANIMATION_TIME
        if (this.mVarListeners.get(31) != null) return 1; // ID_ANIMATION_DELTA_TIME
        let repaintMs = Number.MAX_SAFE_INTEGER;
        if (!Number.isNaN(this.mRepaintSeconds)) {
            repaintMs = Math.trunc(this.mRepaintSeconds * 1000);
            this.mLastRepaint = this.mRepaintSeconds;
        }
        if (this.mVarListeners.get(2) != null) { // ID_TIME_IN_SEC
            const sub = Math.trunc(currentTime % 1000);
            return Math.min(repaintMs, 2 + 1000 - sub);
        }
        if (this.mVarListeners.get(3) != null) { // ID_TIME_IN_MIN
            const sub = Math.trunc(currentTime % 60000);
            return Math.min(repaintMs, 2 + 60000 - sub);
        }
        return -1;
    }

    wakeIn(seconds: number): void {
        if (Number.isNaN(seconds) || Number.isNaN(this.mLastRepaint) || this.mRepaintSeconds > seconds) {
            this.mRepaintSeconds = seconds;
        }
    }

    setWindowWidth(width: number): void { this.updateFloat(5, width); } // ID_WINDOW_WIDTH
    setWindowHeight(height: number): void { this.updateFloat(6, height); } // ID_WINDOW_HEIGHT

    addCollection(id: number, collection: any): void {
        this.mCollectionMap.put(id & 0xFFFFF, collection);
    }

    getFloatValue(id: number, index: number): number {
        const array = this.mCollectionMap.get(id & 0xFFFFF);
        if (array && typeof array.getFloatValue === 'function') {
            return array.getFloatValue(index);
        }
        return 0;
    }

    getFloats(id: number): Float32Array | null {
        const array = this.mCollectionMap.get(id & 0xFFFFF);
        if (array && typeof array.getFloats === 'function') {
            return array.getFloats();
        }
        return null;
    }

    getDynamicFloats(id: number): Float32Array | null {
        const array = this.mCollectionMap.get(id & 0xFFFFF);
        if (array && typeof array.isDynamic === 'boolean' && array.isDynamic) {
            return array.getFloats?.() ?? null;
        }
        return null;
    }

    getArray(id: number): any {
        return this.mCollectionMap.get(id & 0xFFFFF) ?? null;
    }

    getId(listId: number, index: number): number {
        const array = this.mCollectionMap.get(listId & 0xFFFFF);
        if (array && typeof array.getId === 'function') {
            return array.getId(index);
        }
        return -1;
    }

    getListLength(id: number): number {
        const array = this.mCollectionMap.get(id & 0xFFFFF);
        if (array && typeof array.getLength === 'function') {
            return array.getLength();
        }
        return 0;
    }

    putDataMap(id: number, map: any): void { this.mDataMapMap.put(id, map); }
    getDataMap(id: number): any { return this.mDataMapMap.get(id); }

    updateObject(id: number, value: any): void { this.mObjectMap.put(id, value); }
    getObject(id: number): any { return this.mObjectMap.get(id); }

    reset(): void {
        this.mIntWrittenMap.clear();
        this.mDataIntMap.clear();
    }

    wasNotWritten(id: number): boolean {
        return !this.mIntWrittenMap.get(id);
    }

    markWritten(id: number): void {
        this.mIntWrittenMap.put(id, true);
    }

    createNextAvailableId(): number { return this.mNextId++; }
    setNextId(id: number): void { this.mNextId = id; }

    setContext(context: RemoteContext): void {
        this.mRemoteContext = context;
        context.clearLastOpCount();
    }

    markVariableDirty(id: number): void { this.updateListeners(id); }

    clearDataOverride(id: number): void {
        if (id < RemoteComposeState.MAX_DATA) this.mDataOverride[id] = false;
        this.updateListeners(id);
    }
    clearIntegerOverride(id: number): void {
        if (id < RemoteComposeState.MAX_DATA) this.mIntegerOverride[id] = false;
        this.updateListeners(id);
    }
    clearFloatOverride(id: number): void {
        if (id < RemoteComposeState.MAX_DATA) this.mFloatOverride[id] = false;
        this.updateListeners(id);
    }

    overrideData(id: number, item: any): void {
        const previous = this.mIntDataMap.get(id);
        if (previous !== item) {
            this.mDataIntMap.delete(previous);
            this.mDataIntMap.set(item, id);
            this.mIntDataMap.put(id, item);
            if (id < RemoteComposeState.MAX_DATA) this.mDataOverride[id] = true;
            this.updateListeners(id);
        }
    }
}
