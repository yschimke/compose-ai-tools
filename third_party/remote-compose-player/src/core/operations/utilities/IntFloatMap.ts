// IntFloatMap: primitive int→float hash map using open addressing with linear probing.

const NOT_PRESENT = -2147483648;
const DEFAULT_CAPACITY = 16;
const LOAD_FACTOR = 0.75;

export class IntFloatMap {
    private mKeys: Int32Array;
    private mValues: Float32Array;
    private mSize = 0;

    constructor(capacity = DEFAULT_CAPACITY) {
        this.mKeys = new Int32Array(capacity).fill(NOT_PRESENT);
        this.mValues = new Float32Array(capacity);
    }

    put(key: number, value: number): number {
        if (this.mSize > this.mKeys.length * LOAD_FACTOR) {
            this.resize();
        }
        return this.insert(key, value);
    }

    get(key: number): number {
        const idx = this.findKey(key);
        if (idx < 0) return 0;
        return this.mValues[idx];
    }

    contains(key: number): boolean {
        return this.findKey(key) >= 0;
    }

    forEach(callback: (key: number, value: number) => void): void {
        for (let i = 0; i < this.mKeys.length; i++) {
            if (this.mKeys[i] !== NOT_PRESENT) {
                callback(this.mKeys[i], this.mValues[i]);
            }
        }
    }

    clear(): void {
        this.mKeys.fill(NOT_PRESENT);
        this.mValues.fill(0);
        this.mSize = 0;
    }

    size(): number {
        return this.mSize;
    }

    private insert(key: number, value: number): number {
        let index = ((key % this.mKeys.length) + this.mKeys.length) % this.mKeys.length;
        while (true) {
            if (this.mKeys[index] === NOT_PRESENT) {
                this.mKeys[index] = key;
                this.mValues[index] = value;
                this.mSize++;
                return 0;
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
        this.mValues = new Float32Array(newCap);
        this.mSize = 0;
        for (let i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] !== NOT_PRESENT) {
                this.insert(oldKeys[i], oldValues[i]);
            }
        }
    }
}
