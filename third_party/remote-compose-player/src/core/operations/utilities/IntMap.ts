// IntMap: open-addressing hash map with int keys and generic values.
// Uses linear probing with identity hash function.

const NOT_PRESENT = -2147483648; // Integer.MIN_VALUE
const DEFAULT_CAPACITY = 16;
const LOAD_FACTOR = 0.75;

export class IntMap<T> {
    private mKeys: Int32Array;
    private mValues: (T | null)[];
    private mSize = 0;

    constructor(capacity = DEFAULT_CAPACITY) {
        this.mKeys = new Int32Array(capacity).fill(NOT_PRESENT);
        this.mValues = new Array<T | null>(capacity).fill(null);
    }

    put(key: number, value: T): T | null {
        if (this.mSize > this.mKeys.length * LOAD_FACTOR) {
            this.resize();
        }
        return this.insert(key, value);
    }

    get(key: number): T | null {
        const idx = this.findKey(key);
        if (idx < 0) return null;
        return this.mValues[idx];
    }

    remove(key: number): T | null {
        const idx = this.findKey(key);
        if (idx < 0) return null;
        const old = this.mValues[idx];
        this.mKeys[idx] = NOT_PRESENT;
        this.mValues[idx] = null;
        this.mSize--;
        this.rehashFrom(idx);
        return old;
    }

    clear(): void {
        this.mKeys.fill(NOT_PRESENT);
        this.mValues.fill(null);
        this.mSize = 0;
    }

    size(): number {
        return this.mSize;
    }

    keySet(): Set<number> {
        const keys = new Set<number>();
        for (let i = 0; i < this.mKeys.length; i++) {
            if (this.mKeys[i] !== NOT_PRESENT) {
                keys.add(this.mKeys[i]);
            }
        }
        return keys;
    }

    putAll(other: IntMap<T>): void {
        for (let i = 0; i < other.mKeys.length; i++) {
            if (other.mKeys[i] !== NOT_PRESENT) {
                this.put(other.mKeys[i], other.mValues[i]!);
            }
        }
    }

    private insert(key: number, value: T): T | null {
        let index = ((key % this.mKeys.length) + this.mKeys.length) % this.mKeys.length;
        while (true) {
            if (this.mKeys[index] === NOT_PRESENT) {
                this.mKeys[index] = key;
                this.mValues[index] = value;
                this.mSize++;
                return null;
            }
            if (this.mKeys[index] === key) {
                const old = this.mValues[index];
                this.mValues[index] = value;
                return old;
            }
            index = (index + 1) % this.mKeys.length;
        }
    }

    private findKey(key: number): number {
        let index = ((key % this.mKeys.length) + this.mKeys.length) % this.mKeys.length;
        while (true) {
            if (this.mKeys[index] === NOT_PRESENT) return -1;
            if (this.mKeys[index] === key) return index;
            index = (index + 1) % this.mKeys.length;
        }
    }

    private resize(): void {
        const oldKeys = this.mKeys;
        const oldValues = this.mValues;
        const newCap = oldKeys.length * 2;
        this.mKeys = new Int32Array(newCap).fill(NOT_PRESENT);
        this.mValues = new Array<T | null>(newCap).fill(null);
        this.mSize = 0;
        for (let i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] !== NOT_PRESENT) {
                this.insert(oldKeys[i], oldValues[i]!);
            }
        }
    }

    private rehashFrom(startIndex: number): void {
        let index = (startIndex + 1) % this.mKeys.length;
        while (this.mKeys[index] !== NOT_PRESENT) {
            const k = this.mKeys[index];
            const v = this.mValues[index]!;
            this.mKeys[index] = NOT_PRESENT;
            this.mValues[index] = null;
            this.mSize--;
            this.insert(k, v);
            index = (index + 1) % this.mKeys.length;
        }
    }
}
