"use strict";
var RC = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // third_party/remote-compose-player/src/web/main.ts
  var main_exports = {};
  __export(main_exports, {
    GOOGLE_PREFIX: () => GOOGLE_PREFIX,
    RcPlayerElement: () => RcPlayerElement,
    RcdPlayer: () => RcdPlayer,
    base64ToArrayBuffer: () => base64ToArrayBuffer,
    configureWebFonts: () => configureWebFonts,
    createPlayer: () => createPlayer,
    cssFontStackFor: () => cssFontStackFor,
    cssQuoted: () => cssQuoted,
    embeddedFontDescriptors: () => embeddedFontDescriptors,
    ensureWebFont: () => ensureWebFont,
    googleFontsAxisUrl: () => googleFontsAxisUrl,
    googleFontsUrl: () => googleFontsUrl,
    namedFontStack: () => namedFontStack,
    parseFamily: () => parseFamily,
    registerEmbeddedFont: () => registerEmbeddedFont,
    releaseEmbeddedFont: () => releaseEmbeddedFont,
    resetWebFonts: () => resetWebFonts,
    webFontsReady: () => webFontsReady
  });

  // third_party/remote-compose-player/src/core/operations/Utils.ts
  var _dv = new DataView(new ArrayBuffer(4));
  function intBitsToFloat(bits) {
    _dv.setInt32(0, bits, false);
    return _dv.getFloat32(0, false);
  }
  function floatToRawIntBits(value) {
    _dv.setFloat32(0, value, false);
    return _dv.getInt32(0, false);
  }
  function asNan(v) {
    return intBitsToFloat(v | 0 | -8388608);
  }
  function idFromNan(value) {
    return floatToRawIntBits(value) & 4194303;
  }
  function isNaNBits(bits) {
    return (bits & 2139095040) === 2139095040 && (bits & 8388607) !== 0;
  }
  function idFromBits(bits) {
    return bits & 4194303;
  }
  function idFromLong(v) {
    return v - 4294967296;
  }
  function isSystemGlobal(id) {
    return id >= 0 && id <= 41;
  }
  function isMacroLocal(id) {
    return id >= 16384 && id <= 20479;
  }
  function isVariableBits(bits) {
    if (isNaNBits(bits)) {
      const id = idFromBits(bits);
      if (id === 0) return false;
      return id > 40 || id < 10;
    }
    return false;
  }
  function clamp(c) {
    let n = 255;
    c &= ~(c >> 31);
    c -= n;
    c &= c >> 31;
    c += n;
    return c;
  }
  function interpolateColor(c1, c2, t) {
    if (Number.isNaN(t) || t === 0) return c1;
    if (t === 1) return c2;
    let a = c1 >> 24 & 255;
    let r = c1 >> 16 & 255;
    let g = c1 >> 8 & 255;
    let b = c1 & 255;
    const c1fr = Math.pow(r / 255, 2.2);
    const c1fg = Math.pow(g / 255, 2.2);
    const c1fb = Math.pow(b / 255, 2.2);
    const c1fa = a / 255;
    a = c2 >> 24 & 255;
    r = c2 >> 16 & 255;
    g = c2 >> 8 & 255;
    b = c2 & 255;
    const c2fr = Math.pow(r / 255, 2.2);
    const c2fg = Math.pow(g / 255, 2.2);
    const c2fb = Math.pow(b / 255, 2.2);
    const c2fa = a / 255;
    const f_r = c1fr + t * (c2fr - c1fr);
    const f_g = c1fg + t * (c2fg - c1fg);
    const f_b = c1fb + t * (c2fb - c1fb);
    const f_a = c1fa + t * (c2fa - c1fa);
    const outr = clamp(Math.trunc(Math.pow(f_r, 1 / 2.2) * 255));
    const outg = clamp(Math.trunc(Math.pow(f_g, 1 / 2.2) * 255));
    const outb = clamp(Math.trunc(Math.pow(f_b, 1 / 2.2) * 255));
    const outa = clamp(Math.trunc(f_a * 255));
    return outa << 24 | outr << 16 | outg << 8 | outb | 0;
  }
  function hsvToRgb(hue, saturation, value) {
    const h = Math.trunc(hue * 6);
    const f = hue * 6 - h;
    const p = Math.trunc(0.5 + 255 * value * (1 - saturation));
    const q = Math.trunc(0.5 + 255 * value * (1 - f * saturation));
    const t = Math.trunc(0.5 + 255 * value * (1 - (1 - f) * saturation));
    const v = Math.trunc(0.5 + 255 * value);
    switch (h) {
      case 0:
        return 4278190080 | v << 16 | t << 8 | p | 0;
      case 1:
        return 4278190080 | q << 16 | v << 8 | p | 0;
      case 2:
        return 4278190080 | p << 16 | v << 8 | t | 0;
      case 3:
        return 4278190080 | p << 16 | q << 8 | v | 0;
      case 4:
        return 4278190080 | t << 16 | p << 8 | v | 0;
      case 5:
        return 4278190080 | v << 16 | p << 8 | q | 0;
    }
    return 0;
  }
  function toARGB(alpha, red, green, blue) {
    const a = Math.trunc(alpha * 255 + 0.5);
    const r = Math.trunc(red * 255 + 0.5);
    const g = Math.trunc(green * 255 + 0.5);
    const b = Math.trunc(blue * 255 + 0.5);
    return a << 24 | r << 16 | g << 8 | b | 0;
  }

  // third_party/remote-compose-player/src/core/operations/utilities/IntMap.ts
  var NOT_PRESENT = -2147483648;
  var DEFAULT_CAPACITY = 16;
  var LOAD_FACTOR = 0.75;
  var IntMap = class {
    constructor(capacity = DEFAULT_CAPACITY) {
      this.mSize = 0;
      this.mKeys = new Int32Array(capacity).fill(NOT_PRESENT);
      this.mValues = new Array(capacity).fill(null);
    }
    put(key, value) {
      if (this.mSize > this.mKeys.length * LOAD_FACTOR) {
        this.resize();
      }
      return this.insert(key, value);
    }
    get(key) {
      const idx = this.findKey(key);
      if (idx < 0) return null;
      return this.mValues[idx];
    }
    remove(key) {
      const idx = this.findKey(key);
      if (idx < 0) return null;
      const old = this.mValues[idx];
      this.mKeys[idx] = NOT_PRESENT;
      this.mValues[idx] = null;
      this.mSize--;
      this.rehashFrom(idx);
      return old;
    }
    clear() {
      this.mKeys.fill(NOT_PRESENT);
      this.mValues.fill(null);
      this.mSize = 0;
    }
    size() {
      return this.mSize;
    }
    keySet() {
      const keys = /* @__PURE__ */ new Set();
      for (let i = 0; i < this.mKeys.length; i++) {
        if (this.mKeys[i] !== NOT_PRESENT) {
          keys.add(this.mKeys[i]);
        }
      }
      return keys;
    }
    putAll(other) {
      for (let i = 0; i < other.mKeys.length; i++) {
        if (other.mKeys[i] !== NOT_PRESENT) {
          this.put(other.mKeys[i], other.mValues[i]);
        }
      }
    }
    insert(key, value) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
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
    findKey(key) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
      while (true) {
        if (this.mKeys[index] === NOT_PRESENT) return -1;
        if (this.mKeys[index] === key) return index;
        index = (index + 1) % this.mKeys.length;
      }
    }
    resize() {
      const oldKeys = this.mKeys;
      const oldValues = this.mValues;
      const newCap = oldKeys.length * 2;
      this.mKeys = new Int32Array(newCap).fill(NOT_PRESENT);
      this.mValues = new Array(newCap).fill(null);
      this.mSize = 0;
      for (let i = 0; i < oldKeys.length; i++) {
        if (oldKeys[i] !== NOT_PRESENT) {
          this.insert(oldKeys[i], oldValues[i]);
        }
      }
    }
    rehashFrom(startIndex) {
      let index = (startIndex + 1) % this.mKeys.length;
      while (this.mKeys[index] !== NOT_PRESENT) {
        const k = this.mKeys[index];
        const v = this.mValues[index];
        this.mKeys[index] = NOT_PRESENT;
        this.mValues[index] = null;
        this.mSize--;
        this.insert(k, v);
        index = (index + 1) % this.mKeys.length;
      }
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/IntFloatMap.ts
  var NOT_PRESENT2 = -2147483648;
  var DEFAULT_CAPACITY2 = 16;
  var LOAD_FACTOR2 = 0.75;
  var IntFloatMap = class {
    constructor(capacity = DEFAULT_CAPACITY2) {
      this.mSize = 0;
      this.mKeys = new Int32Array(capacity).fill(NOT_PRESENT2);
      this.mValues = new Float32Array(capacity);
    }
    put(key, value) {
      if (this.mSize > this.mKeys.length * LOAD_FACTOR2) {
        this.resize();
      }
      return this.insert(key, value);
    }
    get(key) {
      const idx = this.findKey(key);
      if (idx < 0) return 0;
      return this.mValues[idx];
    }
    contains(key) {
      return this.findKey(key) >= 0;
    }
    forEach(callback) {
      for (let i = 0; i < this.mKeys.length; i++) {
        if (this.mKeys[i] !== NOT_PRESENT2) {
          callback(this.mKeys[i], this.mValues[i]);
        }
      }
    }
    clear() {
      this.mKeys.fill(NOT_PRESENT2);
      this.mValues.fill(0);
      this.mSize = 0;
    }
    size() {
      return this.mSize;
    }
    insert(key, value) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
      while (true) {
        if (this.mKeys[index] === NOT_PRESENT2) {
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
    findKey(key) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
      while (true) {
        if (this.mKeys[index] === NOT_PRESENT2) return -1;
        if (this.mKeys[index] === key) return index;
        index = (index + 1) % this.mKeys.length;
      }
    }
    resize() {
      const oldKeys = this.mKeys;
      const oldValues = this.mValues;
      const newCap = oldKeys.length * 2;
      this.mKeys = new Int32Array(newCap).fill(NOT_PRESENT2);
      this.mValues = new Float32Array(newCap);
      this.mSize = 0;
      for (let i = 0; i < oldKeys.length; i++) {
        if (oldKeys[i] !== NOT_PRESENT2) {
          this.insert(oldKeys[i], oldValues[i]);
        }
      }
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/IntIntMap.ts
  var NOT_PRESENT3 = -2147483648;
  var DEFAULT_CAPACITY3 = 16;
  var LOAD_FACTOR3 = 0.75;
  var IntIntMap = class {
    constructor(capacity = DEFAULT_CAPACITY3) {
      this.mSize = 0;
      this.mKeys = new Int32Array(capacity).fill(NOT_PRESENT3);
      this.mValues = new Int32Array(capacity);
    }
    put(key, value) {
      if (this.mSize > this.mKeys.length * LOAD_FACTOR3) {
        this.resize();
      }
      return this.insert(key, value);
    }
    get(key) {
      const idx = this.findKey(key);
      if (idx < 0) return 0;
      return this.mValues[idx];
    }
    contains(key) {
      return this.findKey(key) >= 0;
    }
    clear() {
      this.mKeys.fill(NOT_PRESENT3);
      this.mValues.fill(0);
      this.mSize = 0;
    }
    size() {
      return this.mSize;
    }
    insert(key, value) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
      while (true) {
        if (this.mKeys[index] === NOT_PRESENT3) {
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
    findKey(key) {
      let index = (key % this.mKeys.length + this.mKeys.length) % this.mKeys.length;
      while (true) {
        if (this.mKeys[index] === NOT_PRESENT3) return -1;
        if (this.mKeys[index] === key) return index;
        index = (index + 1) % this.mKeys.length;
      }
    }
    resize() {
      const oldKeys = this.mKeys;
      const oldValues = this.mValues;
      const newCap = oldKeys.length * 2;
      this.mKeys = new Int32Array(newCap).fill(NOT_PRESENT3);
      this.mValues = new Int32Array(newCap);
      this.mSize = 0;
      for (let i = 0; i < oldKeys.length; i++) {
        if (oldKeys[i] !== NOT_PRESENT3) {
          this.insert(oldKeys[i], oldValues[i]);
        }
      }
    }
  };

  // third_party/remote-compose-player/src/core/RemoteComposeState.ts
  var _RemoteComposeState = class _RemoteComposeState {
    constructor() {
      this.mIntDataMap = new IntMap();
      this.mIntWrittenMap = new IntMap();
      this.mDataIntMap = /* @__PURE__ */ new Map();
      this.mFloatMap = new IntFloatMap();
      this.mIntegerMap = new IntIntMap();
      this.mColorMap = new IntIntMap();
      this.mDataMapMap = new IntMap();
      this.mObjectMap = new IntMap();
      this.mPathMap = new IntMap();
      this.mPathData = new IntMap();
      this.mPathWinding = new IntIntMap();
      this.mColorOverride = new IntIntMap();
      this.mCollectionMap = new IntMap();
      this.mDataOverride = new Array(_RemoteComposeState.MAX_DATA).fill(false);
      this.mIntegerOverride = new Array(_RemoteComposeState.MAX_DATA).fill(false);
      this.mFloatOverride = new Array(_RemoteComposeState.MAX_DATA).fill(false);
      this.mNextId = _RemoteComposeState.START_ID;
      this.mRemoteContext = null;
      this.mVarListeners = new IntMap();
      this.mAllVarListeners = [];
      this.mLastRepaint = NaN;
      this.mRepaintSeconds = NaN;
    }
    getFromId(id) {
      return this.mIntDataMap.get(id);
    }
    containsId(id) {
      return this.mIntDataMap.get(id) != null;
    }
    dataGetId(data) {
      const res = this.mDataIntMap.get(data);
      return res ?? -1;
    }
    cacheData(item, idOrType) {
      if (idOrType !== void 0 && typeof item !== "number") {
        const id2 = this.createNextAvailableId();
        this.mDataIntMap.set(item, id2);
        this.mIntDataMap.put(id2, item);
        return id2;
      }
      const id = this.createNextAvailableId();
      this.mDataIntMap.set(item, id);
      this.mIntDataMap.put(id, item);
      return id;
    }
    cacheDataWithId(id, item) {
      this.mDataIntMap.set(item, id);
      this.mIntDataMap.put(id, item);
    }
    updateData(id, item) {
      if (id < _RemoteComposeState.MAX_DATA && this.mDataOverride[id]) return;
      const previous = this.mIntDataMap.get(id);
      if (previous !== item) {
        this.mDataIntMap.delete(previous);
        this.mDataIntMap.set(item, id);
        this.mIntDataMap.put(id, item);
        this.updateListeners(id);
      }
    }
    getPath(id) {
      return this.mPathMap.get(id);
    }
    putPath(id, path) {
      this.mPathMap.put(id, path);
    }
    putPathData(id, data) {
      this.mPathData.put(id, data);
      this.mPathMap.remove(id);
    }
    getPathData(id) {
      return this.mPathData.get(id);
    }
    getPathWinding(id) {
      return this.mPathWinding.get(id);
    }
    putPathWinding(id, winding) {
      this.mPathWinding.put(id, winding);
    }
    cacheFloat(id, item) {
      if (id === void 0) {
        id = this.createNextAvailableId();
      }
      this.mFloatMap.put(id, item);
      return id;
    }
    updateFloat(id, value) {
      if (id < _RemoteComposeState.MAX_DATA && this.mFloatOverride[id]) return;
      const previous = this.mFloatMap.get(id);
      if (previous !== value) {
        this.mFloatMap.put(id, value);
        this.mIntegerMap.put(id, Math.trunc(value));
        this.updateListeners(id);
      }
    }
    overrideFloat(id, value) {
      const previous = this.mFloatMap.get(id);
      if (previous !== value) {
        this.mFloatMap.put(id, value);
        this.mIntegerMap.put(id, Math.trunc(value));
        if (id < _RemoteComposeState.MAX_DATA) this.mFloatOverride[id] = true;
        this.updateListeners(id);
      }
    }
    updateInteger(id, value) {
      if (id < _RemoteComposeState.MAX_DATA && this.mIntegerOverride[id]) return;
      const previous = this.mIntegerMap.get(id);
      if (previous !== value) {
        this.mFloatMap.put(id, value);
        this.mIntegerMap.put(id, value);
        this.updateListeners(id);
      }
    }
    overrideInteger(id, value) {
      const previous = this.mIntegerMap.get(id);
      if (previous !== value) {
        this.mIntegerMap.put(id, value);
        this.mFloatMap.put(id, value);
        if (id < _RemoteComposeState.MAX_DATA) this.mIntegerOverride[id] = true;
        this.updateListeners(id);
      }
    }
    getFloatEntries() {
      const entries = [];
      this.mFloatMap.forEach((key, value) => entries.push([key, value]));
      return entries;
    }
    getFloat(id) {
      return this.mFloatMap.get(id);
    }
    getInteger(id) {
      return this.mIntegerMap.get(id);
    }
    getColor(id) {
      return this.mColorMap.get(id);
    }
    updateColor(id, color) {
      if (this.mColorOverride.contains(id)) return;
      this.mColorMap.put(id, color);
      this.updateListeners(id);
    }
    overrideColor(id, color) {
      this.mColorOverride.put(id, 1);
      this.mColorMap.put(id, color);
      this.updateListeners(id);
    }
    clearColorOverride() {
      this.mColorOverride.clear();
    }
    updateListeners(id) {
      const v = this.mVarListeners.get(id);
      if (v && this.mRemoteContext) {
        for (const c of v) {
          c.markDirty();
        }
      }
    }
    listenToVar(id, variableSupport) {
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
    getListeners(id) {
      return this.mVarListeners.get(id);
    }
    hasListener(id) {
      return this.mVarListeners.get(id) != null;
    }
    getOpsToUpdate(context, currentTime) {
      if (this.mVarListeners.get(1) != null) return 1;
      if (this.mVarListeners.get(30) != null) return 1;
      if (this.mVarListeners.get(31) != null) return 1;
      let repaintMs = Number.MAX_SAFE_INTEGER;
      if (!Number.isNaN(this.mRepaintSeconds)) {
        repaintMs = Math.trunc(this.mRepaintSeconds * 1e3);
        this.mLastRepaint = this.mRepaintSeconds;
      }
      if (this.mVarListeners.get(2) != null) {
        const sub = Math.trunc(currentTime % 1e3);
        return Math.min(repaintMs, 2 + 1e3 - sub);
      }
      if (this.mVarListeners.get(3) != null) {
        const sub = Math.trunc(currentTime % 6e4);
        return Math.min(repaintMs, 2 + 6e4 - sub);
      }
      return -1;
    }
    wakeIn(seconds) {
      if (Number.isNaN(seconds) || Number.isNaN(this.mLastRepaint) || this.mRepaintSeconds > seconds) {
        this.mRepaintSeconds = seconds;
      }
    }
    setWindowWidth(width) {
      this.updateFloat(5, width);
    }
    // ID_WINDOW_WIDTH
    setWindowHeight(height) {
      this.updateFloat(6, height);
    }
    // ID_WINDOW_HEIGHT
    addCollection(id, collection) {
      this.mCollectionMap.put(id & 1048575, collection);
    }
    getFloatValue(id, index) {
      const array = this.mCollectionMap.get(id & 1048575);
      if (array && typeof array.getFloatValue === "function") {
        return array.getFloatValue(index);
      }
      return 0;
    }
    getFloats(id) {
      const array = this.mCollectionMap.get(id & 1048575);
      if (array && typeof array.getFloats === "function") {
        return array.getFloats();
      }
      return null;
    }
    getDynamicFloats(id) {
      const array = this.mCollectionMap.get(id & 1048575);
      if (array && typeof array.isDynamic === "boolean" && array.isDynamic) {
        return array.getFloats?.() ?? null;
      }
      return null;
    }
    getArray(id) {
      return this.mCollectionMap.get(id & 1048575) ?? null;
    }
    getId(listId, index) {
      const array = this.mCollectionMap.get(listId & 1048575);
      if (array && typeof array.getId === "function") {
        return array.getId(index);
      }
      return -1;
    }
    getListLength(id) {
      const array = this.mCollectionMap.get(id & 1048575);
      if (array && typeof array.getLength === "function") {
        return array.getLength();
      }
      return 0;
    }
    putDataMap(id, map) {
      this.mDataMapMap.put(id, map);
    }
    getDataMap(id) {
      return this.mDataMapMap.get(id);
    }
    updateObject(id, value) {
      this.mObjectMap.put(id, value);
    }
    getObject(id) {
      return this.mObjectMap.get(id);
    }
    reset() {
      this.mIntWrittenMap.clear();
      this.mDataIntMap.clear();
    }
    wasNotWritten(id) {
      return !this.mIntWrittenMap.get(id);
    }
    markWritten(id) {
      this.mIntWrittenMap.put(id, true);
    }
    createNextAvailableId() {
      return this.mNextId++;
    }
    setNextId(id) {
      this.mNextId = id;
    }
    setContext(context) {
      this.mRemoteContext = context;
      context.clearLastOpCount();
    }
    markVariableDirty(id) {
      this.updateListeners(id);
    }
    clearDataOverride(id) {
      if (id < _RemoteComposeState.MAX_DATA) this.mDataOverride[id] = false;
      this.updateListeners(id);
    }
    clearIntegerOverride(id) {
      if (id < _RemoteComposeState.MAX_DATA) this.mIntegerOverride[id] = false;
      this.updateListeners(id);
    }
    clearFloatOverride(id) {
      if (id < _RemoteComposeState.MAX_DATA) this.mFloatOverride[id] = false;
      this.updateListeners(id);
    }
    overrideData(id, item) {
      const previous = this.mIntDataMap.get(id);
      if (previous !== item) {
        this.mDataIntMap.delete(previous);
        this.mDataIntMap.set(item, id);
        this.mIntDataMap.put(id, item);
        if (id < _RemoteComposeState.MAX_DATA) this.mDataOverride[id] = true;
        this.updateListeners(id);
      }
    }
  };
  _RemoteComposeState.START_ID = 42;
  _RemoteComposeState.BITMAP_TEXTURE_ID_OFFSET = 2e3;
  _RemoteComposeState.MAX_DATA = 1e4;
  var RemoteComposeState = _RemoteComposeState;

  // third_party/remote-compose-player/src/core/RemoteClock.ts
  function createSnapshot(millis) {
    const d = new Date(millis);
    const year = d.getFullYear();
    const month = d.getMonth() + 1;
    const dayOfMonth = d.getDate();
    const hour = d.getHours();
    const minute = d.getMinutes();
    const second = d.getSeconds();
    const millisOfSecond = d.getMilliseconds();
    const jsDay = d.getDay();
    const dayOfWeek = jsDay === 0 ? 7 : jsDay;
    const startOfYear = new Date(year, 0, 1);
    const dayOfYear = Math.floor((millis - startOfYear.getTime()) / 864e5) + 1;
    const offsetSeconds = -d.getTimezoneOffset() * 60;
    return {
      getMillis: () => millis,
      getYear: () => year,
      getMonth: () => month,
      getDayOfMonth: () => dayOfMonth,
      getDayOfYear: () => dayOfYear,
      getHour: () => hour,
      getMinute: () => minute,
      getSecond: () => second,
      getMillisOfSecond: () => millisOfSecond,
      getDayOfWeek: () => dayOfWeek,
      getOffsetSeconds: () => offsetSeconds,
      getContinuousSeconds: () => minute * 60 + second + millisOfSecond * 1e-3,
      getEpochSeconds: () => Math.floor(millis / 1e3),
      getTimeInSec: () => minute * 60 + second,
      getTimeInMin: () => hour * 60 + minute
    };
  }
  var SystemClock = {
    millis() {
      return Date.now();
    },
    snapshot() {
      return createSnapshot(Date.now());
    }
  };
  var SYSTEM_CLOCK = SystemClock;

  // third_party/remote-compose-player/src/core/RemoteContext.ts
  var DENSITY_BEHAVIOR_LEGACY = 0;
  var DENSITY_BEHAVIOR_DP = 2;
  var _RemoteContext = class _RemoteContext {
    constructor(clock = SYSTEM_CLOCK) {
      this.mRemoteComposeState = new RemoteComposeState();
      this.mPaintContext = null;
      this.mDensity = NaN;
      this.mPaintTheme = -3;
      this.mMode = "UNSET" /* UNSET */;
      this.mDebug = 0;
      this.mOpCount = 0;
      this.mTheme = -1;
      // Theme.UNSPECIFIED
      this.mWidth = 0;
      this.mHeight = 0;
      this.mViewportWidth = 0;
      this.mViewportHeight = 0;
      this.mAnimationTime = 0;
      this.mAnimate = true;
      this.mLastComponent = null;
      this.currentTime = 0;
      this.mTouchVersion = 0;
      this.mClock = clock;
      this.mDocLoadTime = clock.millis();
    }
    supportsVersion(major, minor, patch) {
      return this.mDocument?.mVersion?.supportsVersion(major, minor, patch) ?? false;
    }
    getDensity() {
      return this.mDensity;
    }
    setDensity(density) {
      if (!Number.isNaN(density) && density > 0) this.mDensity = density;
    }
    /** The document's density behavior (DOC_DENSITY_BEHAVIOR, key 27). Mirrors
     *  AndroidX RemoteContext.getDensityBehavior() → CoreDocument.mDensityBehavior.
     *  Defaults to LEGACY (no scaling) when the header omits the property. */
    getDensityBehavior() {
      return this.mDocument?.getDensityBehavior() ?? DENSITY_BEHAVIOR_LEGACY;
    }
    getDocLoadTime() {
      return this.mDocLoadTime;
    }
    setDocLoadTime() {
      this.mDocLoadTime = this.mClock.millis();
    }
    isAnimationEnabled() {
      return this.mAnimate;
    }
    setAnimationEnabled(value) {
      this.mAnimate = value;
    }
    setAnimationTime(time) {
      this.mAnimationTime = time;
    }
    getAnimationTime() {
      return this.mAnimationTime;
    }
    getClock() {
      return this.mClock;
    }
    setClock(clock) {
      this.mClock = clock;
    }
    setPaintTheme(theme) {
      this.mPaintTheme = theme;
    }
    getPaintTheme() {
      return this.mPaintTheme;
    }
    setTouchVersion(v) {
      this.mTouchVersion = v;
    }
    getTouchVersion() {
      return this.mTouchVersion;
    }
    getTheme() {
      return this.mTheme;
    }
    setTheme(theme) {
      this.mTheme = theme;
    }
    getMode() {
      return this.mMode;
    }
    setMode(mode) {
      this.mMode = mode;
    }
    getPaintContext() {
      return this.mPaintContext;
    }
    setPaintContext(paintContext) {
      this.mPaintContext = paintContext;
    }
    getDocument() {
      return this.mDocument;
    }
    setDocument(document2) {
      this.mDocument = document2;
      this.mClock = document2.getClock();
    }
    isBasicDebug() {
      return this.mDebug === 1;
    }
    isVisualDebug() {
      return this.mDebug === 2;
    }
    isLayoutDebug() {
      return this.mDebug === 3;
    }
    setDebug(debug) {
      this.mDebug = debug;
    }
    needsRepaint() {
      if (this.mPaintContext) this.mPaintContext.needsRepaint();
    }
    incrementOpCount() {
      this.mOpCount++;
      if (this.mOpCount > _RemoteContext.MAX_OP_COUNT) {
        throw new Error("Too many operations executed");
      }
    }
    getLastOpCount() {
      const count = this.mOpCount;
      this.mOpCount = 0;
      return count;
    }
    clearLastOpCount() {
      this.mOpCount = 0;
    }
    // Utility: load font
    loadFont(fontId, fontData) {
      const info = this.getObject(fontId);
      if (info && info.mFontData === fontData) return;
      this.putObject(fontId, { mFontId: fontId, mFontData: fontData, fontBuilder: null });
    }
    // --- Header ---
    header(majorVersion, minorVersion, patchVersion, width, height, capabilities, properties) {
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
    setRootContentBehavior(scroll, alignment, sizing, mode) {
      if (this.mDocument) this.mDocument.setRootContentBehavior(scroll, alignment, sizing, mode);
    }
    setDocumentContentDescription(contentDescriptionId) {
      const cd = this.mRemoteComposeState.getFromId(contentDescriptionId);
      if (this.mDocument) this.mDocument.setContentDescription(cd);
    }
    markVariableDirty(_id) {
    }
    addTouchListener(touchExpression) {
      if (this.mDocument) this.mDocument.addTouchListener(touchExpression);
    }
    createEdgeEffect(_direction) {
      return null;
    }
    getListeners(_id) {
      return null;
    }
    getCollectionsAccess() {
      return this.mRemoteComposeState;
    }
    static isTime(fl) {
      const value = idFromNan(fl);
      return value >= _RemoteContext.ID_CONTINUOUS_SEC && value <= _RemoteContext.ID_DAY_OF_MONTH;
    }
  };
  _RemoteContext.MAX_OP_COUNT = 2e4;
  // --- System variable IDs ---
  _RemoteContext.ID_CONTINUOUS_SEC = 1;
  _RemoteContext.ID_TIME_IN_SEC = 2;
  _RemoteContext.ID_TIME_IN_MIN = 3;
  _RemoteContext.ID_TIME_IN_HR = 4;
  _RemoteContext.ID_WINDOW_WIDTH = 5;
  _RemoteContext.ID_WINDOW_HEIGHT = 6;
  _RemoteContext.ID_COMPONENT_WIDTH = 7;
  _RemoteContext.ID_COMPONENT_HEIGHT = 8;
  _RemoteContext.ID_CALENDAR_MONTH = 9;
  _RemoteContext.ID_OFFSET_TO_UTC = 10;
  _RemoteContext.ID_WEEK_DAY = 11;
  _RemoteContext.ID_DAY_OF_MONTH = 12;
  _RemoteContext.ID_TOUCH_POS_X = 13;
  _RemoteContext.ID_TOUCH_POS_Y = 14;
  _RemoteContext.ID_TOUCH_VEL_X = 15;
  _RemoteContext.ID_TOUCH_VEL_Y = 16;
  _RemoteContext.ID_ACCELERATION_X = 17;
  _RemoteContext.ID_ACCELERATION_Y = 18;
  _RemoteContext.ID_ACCELERATION_Z = 19;
  _RemoteContext.ID_GYRO_ROT_X = 20;
  _RemoteContext.ID_GYRO_ROT_Y = 21;
  _RemoteContext.ID_GYRO_ROT_Z = 22;
  _RemoteContext.ID_MAGNETIC_X = 23;
  _RemoteContext.ID_MAGNETIC_Y = 24;
  _RemoteContext.ID_MAGNETIC_Z = 25;
  _RemoteContext.ID_LIGHT = 26;
  _RemoteContext.ID_DENSITY = 27;
  _RemoteContext.ID_API_LEVEL = 28;
  _RemoteContext.ID_TOUCH_EVENT_TIME = 29;
  _RemoteContext.ID_ANIMATION_TIME = 30;
  _RemoteContext.ID_ANIMATION_DELTA_TIME = 31;
  _RemoteContext.ID_EPOCH_SECOND = 32;
  _RemoteContext.ID_FONT_SIZE = 33;
  _RemoteContext.ID_DAY_OF_YEAR = 34;
  _RemoteContext.ID_YEAR = 35;
  _RemoteContext.ID_FIRST_BASELINE = 36;
  _RemoteContext.ID_LAST_BASELINE = 37;
  // NaN-encoded float versions of system variables
  _RemoteContext.FLOAT_DENSITY = asNan(27);
  _RemoteContext.FLOAT_CONTINUOUS_SEC = asNan(1);
  _RemoteContext.FLOAT_TIME_IN_SEC = asNan(2);
  _RemoteContext.FLOAT_TIME_IN_MIN = asNan(3);
  _RemoteContext.FLOAT_TIME_IN_HR = asNan(4);
  _RemoteContext.FLOAT_WINDOW_WIDTH = asNan(5);
  _RemoteContext.FLOAT_WINDOW_HEIGHT = asNan(6);
  _RemoteContext.FLOAT_COMPONENT_WIDTH = asNan(7);
  _RemoteContext.FLOAT_COMPONENT_HEIGHT = asNan(8);
  _RemoteContext.FLOAT_CALENDAR_MONTH = asNan(9);
  _RemoteContext.FLOAT_OFFSET_TO_UTC = asNan(10);
  _RemoteContext.FLOAT_WEEK_DAY = asNan(11);
  _RemoteContext.FLOAT_DAY_OF_MONTH = asNan(12);
  _RemoteContext.FLOAT_TOUCH_POS_X = asNan(13);
  _RemoteContext.FLOAT_TOUCH_POS_Y = asNan(14);
  _RemoteContext.FLOAT_TOUCH_VEL_X = asNan(15);
  _RemoteContext.FLOAT_TOUCH_VEL_Y = asNan(16);
  _RemoteContext.FLOAT_TOUCH_EVENT_TIME = asNan(29);
  _RemoteContext.FLOAT_ANIMATION_TIME = asNan(30);
  _RemoteContext.FLOAT_ANIMATION_DELTA_TIME = asNan(31);
  _RemoteContext.FLOAT_DAY_OF_YEAR = asNan(34);
  _RemoteContext.FLOAT_YEAR = asNan(35);
  _RemoteContext.FLOAT_API_LEVEL = asNan(28);
  _RemoteContext.FLOAT_FONT_SIZE = asNan(33);
  _RemoteContext.FIRST_BASELINE = asNan(36);
  _RemoteContext.LAST_BASELINE = asNan(37);
  var RemoteContext = _RemoteContext;

  // third_party/remote-compose-player/src/core/Operation.ts
  var _Operation = class _Operation {
    constructor() {
      this.mDirty = true;
    }
    /**
     * Materialize this operation during macro (loom) expansion. Default behaviour
     * mirrors Operation.materialize in Java: emit self, and if this is a container,
     * recursively expand its children and emit a ContainerEnd marker.
     */
    materialize(context, result, loomManager) {
      result.push(this);
      const list = this.getList;
      if (typeof list === "function") {
        const children = list.call(this);
        context.expandRecursive(children, result, loomManager);
        children.length = 0;
        result.push(context.makeContainerEnd());
      }
    }
    markDirty() {
      this.mDirty = true;
    }
    markNotDirty() {
      if (_Operation.ENABLE_DIRTY_FLAG_OPTIMIZATION) {
        this.mDirty = false;
      }
    }
    isDirty() {
      if (_Operation.ENABLE_DIRTY_FLAG_OPTIMIZATION) {
        return this.mDirty;
      }
      return true;
    }
  };
  _Operation.ENABLE_DIRTY_FLAG_OPTIMIZATION = true;
  var Operation = _Operation;

  // third_party/remote-compose-player/src/core/operations/Header.ts
  var _Header = class _Header extends Operation {
    constructor(majorVersion, minorVersion, patchVersion, properties = null, width = 256, height = 256, capabilities = 0) {
      super();
      this.mMajorVersion = majorVersion;
      this.mMinorVersion = minorVersion;
      this.mPatchVersion = patchVersion;
      this.mWidth = width;
      this.mHeight = height;
      this.mCapabilities = capabilities;
      this.mProperties = properties;
      this.mProfiles = 0;
      if (properties) {
        const profileVal = properties.get(_Header.DOC_PROFILES);
        if (profileVal != null) this.mProfiles = profileVal;
        const widthVal = properties.get(_Header.DOC_WIDTH);
        if (widthVal != null) this.mWidth = widthVal;
        const heightVal = properties.get(_Header.DOC_HEIGHT);
        if (heightVal != null) this.mHeight = heightVal;
      }
    }
    get(property) {
      return this.mProperties?.get(property) ?? null;
    }
    getInt(key, defaultValue) {
      const v = this.mProperties?.get(key);
      if (v != null && typeof v === "number") return v;
      return defaultValue;
    }
    getProfiles() {
      return this.mProfiles;
    }
    setVersion(doc) {
      doc.setVersion(this.mMajorVersion, this.mMinorVersion, this.mPatchVersion);
      doc.setWidth(this.mWidth);
      doc.setHeight(this.mHeight);
      doc.setRequiredCapabilities(this.mCapabilities);
      doc.setProperties(this.mProperties);
    }
    write(buffer) {
      buffer.start(_Header.OP_CODE);
    }
    apply(context) {
      context.header(
        this.mMajorVersion,
        this.mMinorVersion,
        this.mPatchVersion,
        this.mWidth,
        this.mHeight,
        this.mCapabilities,
        this.mProperties
      );
    }
    deepToString(indent) {
      return `${indent}Header v${this.mMajorVersion}.${this.mMinorVersion}.${this.mPatchVersion} ${this.mWidth}x${this.mHeight}`;
    }
    /** Peek the API level from the buffer without consuming bytes */
    static peekApiLevel(buffer) {
      const savedIndex = buffer.getIndex();
      buffer.readByte();
      const versionOrMagic = buffer.readInt();
      buffer.setIndex(savedIndex);
      if ((versionOrMagic & 4294901760) === _Header.MAGIC_NUMBER) {
        const majorVersion = versionOrMagic & 65535;
        const minorVersion = buffer.peekInt();
        if (majorVersion >= 1) {
          buffer.setIndex(savedIndex);
          buffer.readByte();
          buffer.readInt();
          const minor = buffer.readInt();
          buffer.setIndex(savedIndex);
          if (minor >= 2) return 8;
          if (minor >= 1) return 7;
          return 6;
        }
        return 6;
      }
      if (versionOrMagic === 0) {
        return 6;
      }
      return 6;
    }
    /** Read a Header from the buffer (modern format with property map) */
    static readDirect(buffer) {
      buffer.readByte();
      const versionOrMagic = buffer.readInt();
      if ((versionOrMagic & 4294901760) === _Header.MAGIC_NUMBER) {
        const majorVersion2 = versionOrMagic & 65535;
        const minorVersion2 = buffer.readInt();
        const patchVersion2 = buffer.readInt();
        const numProperties = buffer.readInt();
        const properties = new IntMap();
        if (numProperties > _Header.MAX_TABLE_SIZE) {
          console.warn(`Header: property table size ${numProperties} exceeds limit ${_Header.MAX_TABLE_SIZE}, truncating`);
        }
        for (let i = 0; i < numProperties; i++) {
          const tag = buffer.readShort();
          const dataType = tag >> 10;
          const key = tag & 1023;
          const itemLen = buffer.readShort();
          if (i >= _Header.MAX_TABLE_SIZE) {
            for (let j = 0; j < itemLen; j++) buffer.readByte();
            continue;
          }
          switch (dataType) {
            case _Header.DATA_TYPE_INT:
              properties.put(key, buffer.readInt());
              break;
            case _Header.DATA_TYPE_FLOAT:
              properties.put(key, buffer.readFloat());
              break;
            case _Header.DATA_TYPE_LONG:
              properties.put(key, buffer.readLong());
              break;
            case _Header.DATA_TYPE_STRING:
              properties.put(key, buffer.readUTF8());
              break;
            default:
              for (let j = 0; j < itemLen; j++) buffer.readByte();
              break;
          }
        }
        return new _Header(majorVersion2, minorVersion2, patchVersion2, properties);
      }
      const majorVersion = versionOrMagic;
      const minorVersion = buffer.readInt();
      const patchVersion = buffer.readInt();
      const width = buffer.readInt();
      const height = buffer.readInt();
      const capabilities = buffer.readLong();
      return new _Header(majorVersion, minorVersion, patchVersion, null, width, height, capabilities);
    }
    /** CompanionOperation.read implementation */
    static read(buffer, operations) {
      const savedIndex = buffer.getIndex() - 1;
      buffer.setIndex(savedIndex);
      const header = _Header.readDirect(buffer);
      operations.push(header);
    }
  };
  _Header.OP_CODE = 0;
  _Header.MAGIC_NUMBER = 76283904;
  _Header.MAX_TABLE_SIZE = 1e3;
  // Property keys
  _Header.DOC_WIDTH = 5;
  _Header.DOC_HEIGHT = 6;
  _Header.DOC_DENSITY_AT_GENERATION = 7;
  _Header.DOC_DESIRED_FPS = 8;
  _Header.DOC_CONTENT_DESCRIPTION = 9;
  _Header.DOC_SOURCE = 11;
  _Header.DOC_DATA_UPDATE = 12;
  _Header.HOST_EXCEPTION_HANDLER = 13;
  _Header.DOC_PROFILES = 14;
  _Header.FEATURE_PAINT_MEASURE = 15;
  _Header.DEBUG = 16;
  _Header.FEATURE_MEASURE_VERSION = 17;
  _Header.FEATURE_TOUCH_VERSION = 18;
  _Header.DOC_DENSITY_BEHAVIOR = 27;
  // Data types
  _Header.DATA_TYPE_INT = 0;
  _Header.DATA_TYPE_FLOAT = 1;
  _Header.DATA_TYPE_LONG = 2;
  _Header.DATA_TYPE_STRING = 3;
  var Header = _Header;

  // third_party/remote-compose-player/src/core/operations/layout/ContainerEnd.ts
  var _ContainerEnd = class _ContainerEnd extends Operation {
    write(buffer) {
      buffer.start(_ContainerEnd.OP_CODE);
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}ContainerEnd`;
    }
    static read(_buffer, operations) {
      operations.push(new _ContainerEnd());
    }
  };
  _ContainerEnd.OP_CODE = 214;
  var ContainerEnd = _ContainerEnd;

  // third_party/remote-compose-player/src/core/PaintOperation.ts
  var PTR_DEREFERENCE = 1 << 30;
  var VALUE_MASK = 65535;
  function isContainer(op) {
    return typeof op.getList === "function";
  }
  function isVariableSupport(op) {
    return typeof op.updateVariables === "function";
  }
  var PaintOperation = class extends Operation {
    apply(context) {
      if (context.mMode === "PAINT" /* PAINT */) {
        const paintContext = context.getPaintContext();
        if (paintContext) {
          this.paint(paintContext);
        }
      } else {
        if (isContainer(this)) {
          for (const op of this.getList()) {
            if (op.isDirty()) {
              if (isVariableSupport(op)) {
                op.markNotDirty();
                op.updateVariables(context);
              }
              op.apply(context);
            }
          }
        }
      }
    }
    deepToString(indent) {
      return indent + this.constructor.name;
    }
    getId(id, context) {
      let returnId = id & VALUE_MASK;
      if ((id & PTR_DEREFERENCE) !== 0) {
        returnId = context.getContext().getInteger(returnId);
      }
      return returnId;
    }
  };
  PaintOperation.PTR_DEREFERENCE = PTR_DEREFERENCE;
  PaintOperation.VALUE_MASK = VALUE_MASK;

  // third_party/remote-compose-player/src/core/operations/utilities/easing/Easing.ts
  var Easing = class {
    constructor() {
      this.mType = 0;
    }
    getType() {
      return this.mType;
    }
  };
  Easing.CUBIC_STANDARD = 1;
  Easing.CUBIC_ACCELERATE = 2;
  Easing.CUBIC_DECELERATE = 3;
  Easing.CUBIC_LINEAR = 4;
  Easing.CUBIC_ANTICIPATE = 5;
  Easing.CUBIC_OVERSHOOT = 6;
  Easing.CUBIC_CUSTOM = 11;
  Easing.SPLINE_CUSTOM = 12;
  Easing.EASE_OUT_BOUNCE = 13;
  Easing.EASE_OUT_ELASTIC = 14;

  // third_party/remote-compose-player/src/core/operations/utilities/easing/CubicEasing.ts
  var STANDARD = [0.4, 0, 0.2, 1];
  var ACCELERATE = [0.4, 0.05, 0.8, 0.7];
  var DECELERATE = [0, 0, 0.2, 0.95];
  var LINEAR = [1, 1, 0, 0];
  var ANTICIPATE = [0.36, 0, 0.66, -0.56];
  var OVERSHOOT = [0.34, 1.56, 0.64, 1];
  var ERROR = 0.01;
  var D_ERROR = 1e-4;
  var CubicEasing = class extends Easing {
    constructor(typeOrX1, y1, x2, y2) {
      super();
      this.mX1 = 0;
      this.mY1 = 0;
      this.mX2 = 0;
      this.mY2 = 0;
      if (y1 !== void 0 && x2 !== void 0 && y2 !== void 0) {
        this.setup(typeOrX1, y1, x2, y2);
      } else if (typeOrX1 !== void 0) {
        this.mType = typeOrX1;
        this.config(typeOrX1);
      } else {
        this.setup(STANDARD[0], STANDARD[1], STANDARD[2], STANDARD[3]);
      }
    }
    config(type) {
      switch (type) {
        case Easing.CUBIC_STANDARD:
          this.setupArr(STANDARD);
          break;
        case Easing.CUBIC_ACCELERATE:
          this.setupArr(ACCELERATE);
          break;
        case Easing.CUBIC_DECELERATE:
          this.setupArr(DECELERATE);
          break;
        case Easing.CUBIC_LINEAR:
          this.setupArr(LINEAR);
          break;
        case Easing.CUBIC_ANTICIPATE:
          this.setupArr(ANTICIPATE);
          break;
        case Easing.CUBIC_OVERSHOOT:
          this.setupArr(OVERSHOOT);
          break;
      }
      this.mType = type;
    }
    setupArr(v) {
      this.setup(v[0], v[1], v[2], v[3]);
    }
    setup(x1, y1, x2, y2) {
      this.mX1 = x1;
      this.mY1 = y1;
      this.mX2 = x2;
      this.mY2 = y2;
    }
    getX(t) {
      const t1 = 1 - t;
      return this.mX1 * 3 * t1 * t1 * t + this.mX2 * 3 * t1 * t * t + t * t * t;
    }
    getY(t) {
      const t1 = 1 - t;
      return this.mY1 * 3 * t1 * t1 * t + this.mY2 * 3 * t1 * t * t + t * t * t;
    }
    get(x) {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      let t = 0.5, range = 0.5;
      while (range > ERROR) {
        const tx = this.getX(t);
        range *= 0.5;
        if (tx < x) t += range;
        else t -= range;
      }
      const x1 = this.getX(t - range), x2 = this.getX(t + range);
      const y1 = this.getY(t - range), y2 = this.getY(t + range);
      return (y2 - y1) * (x - x1) / (x2 - x1) + y1;
    }
    getDiff(x) {
      let t = 0.5, range = 0.5;
      while (range > D_ERROR) {
        const tx = this.getX(t);
        range *= 0.5;
        if (tx < x) t += range;
        else t -= range;
      }
      const x1 = this.getX(t - range), x2 = this.getX(t + range);
      const y1 = this.getY(t - range), y2 = this.getY(t + range);
      return (y2 - y1) / (x2 - x1);
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/BounceCurve.ts
  var N1 = 7.5625;
  var D1 = 2.75;
  var BounceCurve = class extends Easing {
    constructor(type) {
      super();
      this.mType = type;
    }
    get(x) {
      let t = x;
      if (t < 0) return 0;
      if (t < 1 / D1) {
        return 1 / (1 + 1 / D1) * (N1 * t * t + t);
      } else if (t < 2 / D1) {
        t -= 1.5 / D1;
        return N1 * t * t + 0.75;
      } else if (t < 2.5 / D1) {
        t -= 2.25 / D1;
        return N1 * t * t + 0.9375;
      } else if (t <= 1) {
        t -= 2.625 / D1;
        return N1 * t * t + 0.984375;
      }
      return 1;
    }
    getDiff(x) {
      if (x < 0) return 0;
      if (x < 1 / D1) {
        return 2 * N1 * x / (1 + 1 / D1) + 1 / (1 + 1 / D1);
      } else if (x < 2 / D1) {
        return 2 * N1 * (x - 1.5 / D1);
      } else if (x < 2.5 / D1) {
        return 2 * N1 * (x - 2.25 / D1);
      } else if (x <= 1) {
        return 2 * N1 * (x - 2.625 / D1);
      }
      return 0;
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/ElasticOutCurve.ts
  var C4 = 2 * Math.PI / 3;
  var TWENTY_PI = 20 * Math.PI;
  var LOG_8 = Math.log(8);
  var ElasticOutCurve = class extends Easing {
    get(x) {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      return Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * C4) + 1;
    }
    getDiff(x) {
      if (x < 0 || x > 1) return 0;
      return 5 * Math.pow(2, 1 - 10 * x) * (LOG_8 * Math.cos(TWENTY_PI * x / 3) + 2 * Math.PI * Math.sin(TWENTY_PI * x / 3)) / 3;
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/MonotonicCurveFit.ts
  var MonotonicCurveFit = class _MonotonicCurveFit {
    constructor(time, y) {
      // same shape as mY
      this.mExtrapolate = true;
      const n = time.length;
      const dim = y[0].length;
      this.mDim = dim;
      this.mSlopeTemp = new Float64Array(dim);
      this.mT = time instanceof Float64Array ? time : Float64Array.from(time);
      this.mY = new Array(n);
      for (let i = 0; i < n; i++) {
        this.mY[i] = Float64Array.from(y[i]);
      }
      const slope = new Array(n - 1);
      for (let i = 0; i < n - 1; i++) slope[i] = new Float64Array(dim);
      const tangent = new Array(n);
      for (let i = 0; i < n; i++) tangent[i] = new Float64Array(dim);
      for (let j = 0; j < dim; j++) {
        for (let i = 0; i < n - 1; i++) {
          const dt = this.mT[i + 1] - this.mT[i];
          slope[i][j] = (this.mY[i + 1][j] - this.mY[i][j]) / dt;
          if (i === 0) {
            tangent[i][j] = slope[i][j];
          } else {
            tangent[i][j] = (slope[i - 1][j] + slope[i][j]) * 0.5;
          }
        }
        tangent[n - 1][j] = slope[n - 2][j];
      }
      for (let i = 0; i < n - 1; i++) {
        for (let j = 0; j < dim; j++) {
          if (slope[i][j] === 0) {
            tangent[i][j] = 0;
            tangent[i + 1][j] = 0;
          } else {
            const a = tangent[i][j] / slope[i][j];
            const b = tangent[i + 1][j] / slope[i][j];
            const h = Math.hypot(a, b);
            if (h > 9) {
              const t = 3 / h;
              tangent[i][j] = t * a * slope[i][j];
              tangent[i + 1][j] = t * b * slope[i][j];
            }
          }
        }
      }
      this.mTangent = tangent;
    }
    /** Get position of curve j at time t */
    getPos(t, j) {
      const n = this.mT.length;
      if (this.mExtrapolate) {
        if (t <= this.mT[0]) {
          return this.mY[0][j] + (t - this.mT[0]) * this.getSlope(this.mT[0], j);
        }
        if (t >= this.mT[n - 1]) {
          return this.mY[n - 1][j] + (t - this.mT[n - 1]) * this.getSlope(this.mT[n - 1], j);
        }
      } else {
        if (t <= this.mT[0]) return this.mY[0][j];
        if (t >= this.mT[n - 1]) return this.mY[n - 1][j];
      }
      for (let i = 0; i < n - 1; i++) {
        if (t === this.mT[i]) return this.mY[i][j];
        if (t < this.mT[i + 1]) {
          const h = this.mT[i + 1] - this.mT[i];
          const x = (t - this.mT[i]) / h;
          return _MonotonicCurveFit.interpolate(
            h,
            x,
            this.mY[i][j],
            this.mY[i + 1][j],
            this.mTangent[i][j],
            this.mTangent[i + 1][j]
          );
        }
      }
      return 0;
    }
    /** Get slope of curve j at time t */
    getSlope(t, j) {
      const n = this.mT.length;
      if (t < this.mT[0]) t = this.mT[0];
      else if (t >= this.mT[n - 1]) t = this.mT[n - 1];
      for (let i = 0; i < n - 1; i++) {
        if (t <= this.mT[i + 1]) {
          const h = this.mT[i + 1] - this.mT[i];
          const x = (t - this.mT[i]) / h;
          return _MonotonicCurveFit.diff(
            h,
            x,
            this.mY[i][j],
            this.mY[i + 1][j],
            this.mTangent[i][j],
            this.mTangent[i + 1][j]
          ) / h;
        }
      }
      return 0;
    }
    /** Cubic Hermite spline interpolation */
    static interpolate(h, x, y1, y2, t1, t2) {
      const x2 = x * x;
      const x3 = x2 * x;
      return -2 * x3 * y2 + 3 * x2 * y2 + 2 * x3 * y1 - 3 * x2 * y1 + y1 + h * t2 * x3 + h * t1 * x3 - h * t2 * x2 - 2 * h * t1 * x2 + h * t1 * x;
    }
    /** Cubic Hermite spline derivative */
    static diff(h, x, y1, y2, t1, t2) {
      const x2 = x * x;
      return -6 * x2 * y2 + 6 * x * y2 + 6 * x2 * y1 - 6 * x * y1 + 3 * h * t2 * x2 + 3 * h * t1 * x2 - 2 * h * t2 * x - 4 * h * t1 * x + h * t1;
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/StepCurve.ts
  var StepCurve = class _StepCurve extends Easing {
    constructor(params, offset, len) {
      super();
      this.mCurveFit = _StepCurve.genSpline(params, offset, len);
    }
    static genSpline(values, off, arrayLen) {
      const length = arrayLen * 3 - 2;
      const nLen = arrayLen - 1;
      const gap = 1 / nLen;
      const points = new Array(length);
      for (let i = 0; i < length; i++) points[i] = [0];
      const time = new Float64Array(length);
      for (let i = 0; i < arrayLen; i++) {
        const v = values[i + off];
        points[i + nLen][0] = v;
        time[i + nLen] = i * gap;
        if (i > 0) {
          points[i + nLen * 2][0] = v + 1;
          time[i + nLen * 2] = i * gap + 1;
          points[i - 1][0] = v - 1 - gap;
          time[i - 1] = i * gap + -1 - gap;
        }
      }
      return new MonotonicCurveFit(time, points);
    }
    get(x) {
      if (x < 0) return 0;
      if (x > 1) return 1;
      return this.mCurveFit.getPos(x, 0);
    }
    getDiff(x) {
      if (x < 0 || x > 1) return 0;
      return this.mCurveFit.getSlope(x, 0);
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/FloatAnimation.ts
  var _dv2 = new DataView(new ArrayBuffer(4));
  function floatToRawIntBits2(v) {
    _dv2.setFloat32(0, v, false);
    return _dv2.getInt32(0, false);
  }
  var FloatAnimation = class _FloatAnimation extends Easing {
    constructor(description) {
      super();
      this.mDuration = 1;
      this.mWrap = NaN;
      this.mInitialValue = NaN;
      this.mTargetValue = NaN;
      this.mDirectionalSnap = 0;
      this.mOffset = 0;
      this.mPropagate = false;
      this.mType = Easing.CUBIC_STANDARD;
      this.mSpec = description;
      this.setAnimationDescription(description);
    }
    setAnimationDescription(description) {
      this.mSpec = description;
      this.mDuration = this.mSpec.length === 0 ? 1 : this.mSpec[0];
      let len = 0;
      if (this.mSpec.length > 1) {
        const numType = floatToRawIntBits2(this.mSpec[1]);
        this.mType = numType & 255;
        const wrap = (numType >> 8 & 1) > 0;
        const init = (numType >> 8 & 2) > 0;
        this.mDirectionalSnap = numType >> 10 & 3;
        this.mPropagate = (numType >> 12 & 1) > 0;
        len = numType >> 16 & 65535;
        let off = 2 + len;
        if (init) {
          this.mInitialValue = this.mSpec[off++];
        }
        if (wrap) {
          this.mWrap = this.mSpec[off];
        }
      }
      this.create(this.mType, description, 2, len);
    }
    create(type, params, offset, len) {
      switch (type) {
        case Easing.CUBIC_STANDARD:
        case Easing.CUBIC_ACCELERATE:
        case Easing.CUBIC_DECELERATE:
        case Easing.CUBIC_LINEAR:
        case Easing.CUBIC_ANTICIPATE:
        case Easing.CUBIC_OVERSHOOT:
          this.mEasingCurve = new CubicEasing(type);
          break;
        case Easing.CUBIC_CUSTOM:
          if (params) {
            this.mEasingCurve = new CubicEasing(
              params[offset],
              params[offset + 1],
              params[offset + 2],
              params[offset + 3]
            );
          } else {
            this.mEasingCurve = new CubicEasing();
          }
          break;
        case Easing.EASE_OUT_BOUNCE:
          this.mEasingCurve = new BounceCurve(type);
          break;
        case Easing.EASE_OUT_ELASTIC:
          this.mEasingCurve = new ElasticOutCurve();
          break;
        case Easing.SPLINE_CUSTOM:
          if (params) {
            this.mEasingCurve = new StepCurve(params, offset, len);
          } else {
            this.mEasingCurve = new CubicEasing();
          }
          break;
        default:
          this.mEasingCurve = new CubicEasing();
          break;
      }
    }
    getDuration() {
      return this.mDuration;
    }
    setInitialValue(value) {
      if (Number.isNaN(this.mWrap)) {
        this.mInitialValue = value;
      } else {
        this.mInitialValue = value % this.mWrap;
      }
      this.setScaleOffset();
    }
    static wrap(wrapVal, value) {
      value = value % wrapVal;
      if (value < 0) value += wrapVal;
      return value;
    }
    wrapDistance(wrapVal, from, to) {
      let delta = (to - from) % 360;
      if (delta < -wrapVal / 2) delta += wrapVal;
      else if (delta > wrapVal / 2) delta -= wrapVal;
      return delta;
    }
    setTargetValue(value) {
      this.mTargetValue = value;
      if (!Number.isNaN(this.mWrap)) {
        this.mInitialValue = _FloatAnimation.wrap(this.mWrap, this.mInitialValue);
        this.mTargetValue = _FloatAnimation.wrap(this.mWrap, this.mTargetValue);
        if (Number.isNaN(this.mInitialValue)) {
          this.mInitialValue = this.mTargetValue;
        }
        const dist = this.wrapDistance(this.mWrap, this.mInitialValue, this.mTargetValue);
        if (dist > 0 && this.mTargetValue < this.mInitialValue) {
          this.mTargetValue += this.mWrap;
        } else if (dist < 0 && this.mDirectionalSnap !== 0) {
          if (this.mDirectionalSnap === 1 && this.mTargetValue > this.mInitialValue) {
            this.mInitialValue = this.mTargetValue;
          }
          if (this.mDirectionalSnap === 2 && this.mTargetValue < this.mInitialValue) {
            this.mInitialValue = this.mTargetValue;
          }
          this.mTargetValue -= this.mWrap;
        }
      }
      this.setScaleOffset();
    }
    getTargetValue() {
      return this.mTargetValue;
    }
    getInitialValue() {
      return this.mInitialValue;
    }
    isPropagate() {
      return this.mPropagate;
    }
    setScaleOffset() {
      if (!Number.isNaN(this.mInitialValue) && !Number.isNaN(this.mTargetValue)) {
        this.mOffset = this.mInitialValue;
      } else {
        this.mOffset = 0;
      }
    }
    get(t) {
      if (this.mDirectionalSnap === 1 && this.mTargetValue < this.mInitialValue) {
        this.mInitialValue = this.mTargetValue;
        return this.mTargetValue;
      }
      if (this.mDirectionalSnap === 2 && this.mTargetValue > this.mInitialValue) {
        this.mInitialValue = this.mTargetValue;
        return this.mTargetValue;
      }
      return this.mEasingCurve.get(t / this.mDuration) * (this.mTargetValue - this.mInitialValue) + this.mInitialValue;
    }
    getDiff(t) {
      return this.mEasingCurve.getDiff(t / this.mDuration) * (this.mTargetValue - this.mInitialValue);
    }
  };

  // third_party/remote-compose-player/src/core/operations/utilities/easing/SpringStopEngine.ts
  var SpringStopEngine = class {
    constructor(parameters) {
      this.mDamping = 0.5;
      this.mStiffness = 0;
      this.mTargetPos = 0;
      this.mLastTime = 0;
      this.mPos = 0;
      this.mV = 0;
      this.mMass = 1;
      this.mStopThreshold = 0;
      this.mBoundaryMode = 0;
      if (parameters) {
        if (parameters[0] !== 0) {
          throw new Error("parameter[0] should be 0");
        }
        const dv = new DataView(new ArrayBuffer(4));
        dv.setFloat32(0, parameters[4], false);
        const boundaryMode = dv.getInt32(0, false);
        this.springParameters(1, parameters[1], parameters[2], parameters[3], boundaryMode);
      }
    }
    getTargetValue() {
      return this.mTargetPos;
    }
    setInitialValue(v) {
      this.mPos = v;
    }
    setTargetValue(v) {
      this.mTargetPos = v;
    }
    springStart(currentPos, target, currentVelocity) {
      this.mTargetPos = target;
      this.mPos = currentPos;
      this.mLastTime = 0;
    }
    springParameters(mass, stiffness, damping, stopThreshold, boundaryMode) {
      this.mDamping = damping;
      this.mStiffness = stiffness;
      this.mMass = mass;
      this.mStopThreshold = stopThreshold;
      this.mBoundaryMode = boundaryMode;
      this.mLastTime = 0;
    }
    getVelocity(_time) {
      return this.mV;
    }
    get(time) {
      this.compute(time - this.mLastTime);
      this.mLastTime = time;
      if (this.isStopped()) {
        this.mPos = this.mTargetPos;
      }
      return this.mPos;
    }
    isStopped() {
      const x = this.mPos - this.mTargetPos;
      const energy = this.mV * this.mV * this.mMass + this.mStiffness * x * x;
      const maxDef = Math.sqrt(energy / this.mStiffness);
      return maxDef <= this.mStopThreshold;
    }
    compute(dt) {
      if (dt <= 0) return;
      const k = this.mStiffness;
      const c = this.mDamping;
      const overSample = Math.trunc(1 + 9 / (Math.sqrt(this.mStiffness / this.mMass) * dt * 4));
      dt /= overSample;
      for (let i = 0; i < overSample; i++) {
        const x = this.mPos - this.mTargetPos;
        const a = (-k * x - c * this.mV) / this.mMass;
        const avgV = this.mV + a * dt / 2;
        const avgX = this.mPos + dt * avgV / 2 - this.mTargetPos;
        const a2 = (-avgX * k - avgV * c) / this.mMass;
        const dv = a2 * dt;
        const avgV2 = this.mV + dv / 2;
        this.mV += dv;
        this.mPos += avgV2 * dt;
        if (this.mBoundaryMode > 0) {
          if (this.mPos < 0 && (this.mBoundaryMode & 1) === 1) {
            this.mPos = -this.mPos;
            this.mV = -this.mV;
          }
          if (this.mPos > 1 && (this.mBoundaryMode & 2) === 2) {
            this.mPos = 2 - this.mPos;
            this.mV = -this.mV;
          }
        }
      }
    }
  };

  // third_party/remote-compose-player/src/core/operations/FloatExpression.ts
  var ARR_SENTINEL = 2 ** 42;
  function arrIdFromStack(x) {
    return x >= ARR_SENTINEL ? x - ARR_SENTINEL | 0 : idFromNan(x);
  }
  var _FloatExpression = class _FloatExpression extends Operation {
    constructor(id, bits, animation) {
      super();
      // Animation support
      this.mFloatAnimation = null;
      this.mSpring = null;
      this.mLastChange = NaN;
      this.mLastCalculatedValue = NaN;
      this.mLastAnimatedValue = NaN;
      // Registers for STORE/LOAD ops
      this.mR0 = 0;
      this.mR1 = 0;
      this.mR2 = 0;
      this.mR3 = 0;
      // Variables for animation callbacks
      this.mVar = [0, 0, 0];
      this.mId = id;
      this.mBits = bits;
      this.mAnimation = animation;
      if (animation) {
        if (animation.length > 4 && animation[0] === 0) {
          this.mSpring = new SpringStopEngine(animation);
        } else {
          this.mFloatAnimation = new FloatAnimation(animation);
        }
      }
    }
    write(_buffer) {
    }
    registerListening(context) {
      for (let i = 0; i < this.mBits.length; i++) {
        const b = this.mBits[i];
        if (isNaNBits(b) && !_FloatExpression.isMathOperatorBits(b) && !_FloatExpression.isDataVariableBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(context) {
      let v = _FloatExpression.evalRPN(context, this.mBits, this.mVar);
      let valueChanged = v !== this.mLastCalculatedValue;
      if (valueChanged) {
        if (v !== this.mLastCalculatedValue) {
          this.mLastChange = context.getAnimationTime();
          this.mLastCalculatedValue = v;
        } else {
          valueChanged = false;
        }
      }
      if (valueChanged && this.mFloatAnimation) {
        if (Number.isNaN(this.mFloatAnimation.getTargetValue())) {
          this.mFloatAnimation.setInitialValue(v);
        } else {
          this.mFloatAnimation.setInitialValue(this.mFloatAnimation.getTargetValue());
        }
        this.mFloatAnimation.setTargetValue(v);
      } else if (valueChanged && this.mSpring) {
        this.mSpring.setTargetValue(v);
      }
    }
    static isMathOperatorBits(b) {
      if (!isNaNBits(b)) return false;
      const id = idFromBits(b);
      return id > _FloatExpression.OFFSET && id <= _FloatExpression.OFFSET + 79;
    }
    static isDataVariableBits(b) {
      if (!isNaNBits(b)) return false;
      const id = idFromBits(b);
      return (id & _FloatExpression.ID_REGION_MASK) === _FloatExpression.ID_REGION_ARRAY;
    }
    apply(context) {
      const t = context.getAnimationTime();
      if (Number.isNaN(this.mLastChange)) {
        this.mLastChange = t;
      }
      if (this.mFloatAnimation) {
        if (Number.isNaN(this.mLastCalculatedValue)) {
          this.mLastCalculatedValue = this.evaluate(context);
          this.mFloatAnimation.setTargetValue(this.mLastCalculatedValue);
          if (Number.isNaN(this.mFloatAnimation.getInitialValue())) {
            this.mFloatAnimation.setInitialValue(this.mLastCalculatedValue);
          }
        }
        const lastComputedValue = this.mFloatAnimation.get(t - this.mLastChange);
        if (lastComputedValue !== this.mLastAnimatedValue || t - this.mLastChange <= this.mFloatAnimation.getDuration()) {
          this.mLastAnimatedValue = lastComputedValue;
          context.loadFloat(this.mId, lastComputedValue);
          context.needsRepaint();
        }
      } else if (this.mSpring) {
        const lastComputedValue = this.mSpring.get(t);
        if (lastComputedValue !== this.mLastAnimatedValue || Math.abs(this.mSpring.getTargetValue() - lastComputedValue) > 0.01) {
          this.mLastAnimatedValue = lastComputedValue;
          context.loadFloat(this.mId, lastComputedValue);
          context.needsRepaint();
        }
      } else {
        const result = this.evaluate(context);
        context.loadFloat(this.mId, result);
      }
    }
    /**
     * Evaluate now, without waiting for this operation's turn in the frame.
     *
     * `ValueFloatExpressionChangeAction` needs the value at the moment the action
     * runs; reading the last loaded value instead would be a frame stale, which on a
     * per-frame action compounds into visible drift.
     */
    evaluate(context) {
      return _FloatExpression.evalRPN(context, this.mBits, this.mVar);
    }
    /**
     * Static RPN expression evaluator, callable from particle operations.
     * @param context - RemoteContext for variable resolution and collections access
     * @param values - the RPN expression tokens (Float32Array or number[])
     * @param vars - VAR1/VAR2/VAR3 values, defaults to [0, 0, 0]
     * @returns the evaluated result
     */
    static evalRPN(context, values, vars = [0, 0, 0]) {
      const stack = [];
      const OFFSET3 = _FloatExpression.OFFSET;
      const collectionsAccess = context.getCollectionsAccess();
      const useBits = values instanceof Int32Array;
      let r0 = 0, r1 = 0, r2 = 0, r3 = 0;
      for (let i = 0; i < values.length; i++) {
        const raw = values[i];
        const bits = useBits ? raw | 0 : floatToRawIntBits(raw);
        const v = useBits ? intBitsToFloat(bits) : raw;
        if (isNaNBits(bits)) {
          const id = idFromBits(bits);
          if ((id & _FloatExpression.ID_REGION_MASK) === _FloatExpression.ID_REGION_ARRAY) {
            stack.push(ARR_SENTINEL + id);
          } else if (id > OFFSET3 && id <= OFFSET3 + 79) {
            const op = id - OFFSET3;
            switch (op) {
              case 1: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a + b);
                break;
              }
              case 2: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a - b);
                break;
              }
              case 3: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a * b);
                break;
              }
              case 4: {
                const b = stack.pop(), a = stack.pop();
                stack.push(b !== 0 ? a / b : 0);
                break;
              }
              case 5: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a % b);
                break;
              }
              case 6: {
                const b = stack.pop(), a = stack.pop();
                stack.push(Math.min(a, b));
                break;
              }
              case 7: {
                const b = stack.pop(), a = stack.pop();
                stack.push(Math.max(a, b));
                break;
              }
              case 8: {
                const b = stack.pop(), a = stack.pop();
                stack.push(Math.pow(a, b));
                break;
              }
              case 9:
                stack.push(Math.sqrt(stack.pop()));
                break;
              case 10:
                stack.push(Math.abs(stack.pop()));
                break;
              case 11:
                stack.push(Math.sign(stack.pop()));
                break;
              case 12: {
                const b = stack.pop(), a = stack.pop();
                stack.push(1 / b < 0 || b < 0 ? -Math.abs(a) : Math.abs(a));
                break;
              }
              case 13:
                stack.push(Math.exp(stack.pop()));
                break;
              case 14:
                stack.push(Math.floor(stack.pop()));
                break;
              case 15:
                stack.push(Math.log10(stack.pop()));
                break;
              case 16:
                stack.push(Math.log(stack.pop()));
                break;
              case 17:
                stack.push(Math.round(stack.pop()));
                break;
              case 18:
                stack.push(Math.sin(stack.pop()));
                break;
              case 19:
                stack.push(Math.cos(stack.pop()));
                break;
              case 20:
                stack.push(Math.tan(stack.pop()));
                break;
              case 21:
                stack.push(Math.asin(stack.pop()));
                break;
              case 22:
                stack.push(Math.acos(stack.pop()));
                break;
              case 23:
                stack.push(Math.atan(stack.pop()));
                break;
              case 24: {
                const b = stack.pop(), a = stack.pop();
                stack.push(Math.atan2(a, b));
                break;
              }
              case 25: {
                const c = stack.pop(), b = stack.pop(), a = stack.pop();
                stack.push(a * b + c);
                break;
              }
              case 26: {
                const cond = stack.pop(), trueVal = stack.pop(), falseVal = stack.pop();
                stack.push(cond > 0 ? trueVal : falseVal);
                break;
              }
              case 27: {
                const top = stack.pop(), second = stack.pop(), val = stack.pop();
                stack.push(Math.min(Math.max(val, top), second));
                break;
              }
              case 28:
                stack.push(Math.pow(stack.pop(), 1 / 3));
                break;
              case 29:
                stack.push(stack.pop() * (180 / Math.PI));
                break;
              case 30:
                stack.push(stack.pop() * (Math.PI / 180));
                break;
              case 31:
                stack.push(Math.ceil(stack.pop()));
                break;
              case 32: {
                const index = stack.pop();
                const arrayId = arrIdFromStack(stack.pop());
                stack.push(collectionsAccess.getFloatValue(arrayId, Math.trunc(index)));
                break;
              }
              case 33: {
                const aId = arrIdFromStack(stack.pop());
                const floats = collectionsAccess.getFloats(aId);
                if (floats && floats.length > 0) {
                  let mx = floats[0];
                  for (let j = 1; j < floats.length; j++) mx = Math.max(mx, floats[j]);
                  stack.push(mx);
                } else {
                  stack.push(0);
                }
                break;
              }
              case 34: {
                const aId = arrIdFromStack(stack.pop());
                const floats = collectionsAccess.getFloats(aId);
                if (floats && floats.length > 0) {
                  let mn = floats[0];
                  for (let j = 1; j < floats.length; j++) mn = Math.min(mn, floats[j]);
                  stack.push(mn);
                } else {
                  stack.push(0);
                }
                break;
              }
              case 35: {
                const aId = arrIdFromStack(stack.pop());
                const floats = collectionsAccess.getFloats(aId);
                let sum = 0;
                if (floats) {
                  for (let j = 0; j < floats.length; j++) sum += floats[j];
                }
                stack.push(sum);
                break;
              }
              case 36: {
                const aId = arrIdFromStack(stack.pop());
                const floats = collectionsAccess.getFloats(aId);
                let sum = 0;
                if (floats && floats.length > 0) {
                  for (let j = 0; j < floats.length; j++) sum += floats[j];
                  stack.push(sum / floats.length);
                } else {
                  stack.push(0);
                }
                break;
              }
              case 37: {
                const aId = arrIdFromStack(stack.pop());
                stack.push(collectionsAccess.getListLength(aId));
                break;
              }
              case 38: {
                const pos = stack.pop();
                const aId = arrIdFromStack(stack.pop());
                const floats = collectionsAccess.getFloats(aId);
                stack.push(floats ? _FloatExpression.splineInterp(floats, pos) : 0);
                break;
              }
              case 39:
                stack.push(Math.random());
                break;
              case 40:
                stack.pop();
                break;
              case 41: {
                let x = floatToRawIntBits(stack.pop());
                x = x << 13 ^ x | 0;
                stack.push(
                  1 - (Math.imul(x, Math.imul(x, Math.imul(x, 15731)) + 789221) + 1376312589 & 2147483647) / 1073741800
                );
                break;
              }
              case 42: {
                const max = stack.pop(), min = stack.pop();
                stack.push(min + Math.random() * (max - min));
                break;
              }
              case 43: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a * a + b * b);
                break;
              }
              case 44: {
                const b = stack.pop(), a = stack.pop();
                stack.push(a > b ? 1 : 0);
                break;
              }
              case 45: {
                const x = stack.pop();
                stack.push(x * x);
                break;
              }
              case 46:
                stack.push(stack[stack.length - 1]);
                break;
              case 47: {
                const b = stack.pop(), a = stack.pop();
                stack.push(Math.hypot(a, b));
                break;
              }
              case 48: {
                const b = stack.pop(), a = stack.pop();
                stack.push(b, a);
                break;
              }
              case 49: {
                const t = stack.pop(), y = stack.pop(), x = stack.pop();
                stack.push(x + (y - x) * t);
                break;
              }
              case 50: {
                const mn = stack.pop(), mx = stack.pop(), val = stack.pop();
                if (val < mn) {
                  stack.push(0);
                } else if (val > mx) {
                  stack.push(1);
                } else {
                  const t = (val - mn) / (mx - mn);
                  stack.push(t * t * (3 - 2 * t));
                }
                break;
              }
              case 51:
                stack.push(Math.log(stack.pop()) / Math.log(2));
                break;
              case 52:
                stack.push(1 / stack.pop());
                break;
              case 53: {
                const x = stack.pop();
                stack.push(x - Math.trunc(x));
                break;
              }
              case 54: {
                const pmax = stack.pop();
                const pval = stack.pop();
                const max2 = pmax * 2;
                const tmp = pval % max2;
                stack.push(tmp < pmax ? tmp : max2 - tmp);
                break;
              }
              case 55:
                break;
              case 56:
                r0 = stack.pop();
                break;
              case 57:
                r1 = stack.pop();
                break;
              case 58:
                r2 = stack.pop();
                break;
              case 59:
                r3 = stack.pop();
                break;
              case 60:
                stack.push(r0);
                break;
              case 61:
                stack.push(r1);
                break;
              case 62:
                stack.push(r2);
                break;
              case 63:
                stack.push(r3);
                break;
              // 64-67: CMD1-CMD4 reserved (no-op)
              case 64:
              case 65:
              case 66:
              case 67:
                break;
              case 70:
                stack.push(vars[0]);
                break;
              case 71:
                stack.push(vars[1]);
                break;
              case 72:
                stack.push(vars[2]);
                break;
              case 73:
                stack.push(-stack.pop());
                break;
              case 74: {
                const cpos = stack.pop();
                const cy2 = stack.pop();
                const cx2 = stack.pop();
                const cy1 = stack.pop();
                const cx1 = stack.pop();
                stack.push(_FloatExpression.cubicEasing(cx1, cy1, cx2, cy2, cpos));
                break;
              }
              case 75: {
                const sli = stack.pop();
                const slId = arrIdFromStack(stack.pop());
                const slr = sli - Math.floor(sli);
                const slFloats = collectionsAccess.getFloats(slId);
                stack.push(slFloats ? _FloatExpression.splineInterp(slFloats, slr < 0 ? slr + 1 : slr) : 0);
                break;
              }
              case 76: {
                const stLast = Math.trunc(stack.pop());
                const stId = arrIdFromStack(stack.pop());
                let stSum = 0;
                for (let j = 0; j <= stLast; j++) {
                  stSum += collectionsAccess.getFloatValue(stId, j);
                }
                stack.push(stSum);
                break;
              }
              case 77: {
                const syIdY = arrIdFromStack(stack.pop());
                const syIdX = arrIdFromStack(stack.pop());
                const syArrX = collectionsAccess.getFloats(syIdX);
                const syArrY = collectionsAccess.getFloats(syIdY);
                let sxySum = 0;
                if (syArrX && syArrY) {
                  for (let j = 0; j < syArrX.length; j++) {
                    sxySum += syArrX[j] * syArrY[j];
                  }
                }
                stack.push(sxySum);
                break;
              }
              case 78: {
                const ssId = arrIdFromStack(stack.pop());
                const ssArr = collectionsAccess.getFloats(ssId);
                let ssSum = 0;
                if (ssArr) {
                  for (let j = 0; j < ssArr.length; j++) {
                    ssSum += ssArr[j] * ssArr[j];
                  }
                }
                stack.push(ssSum);
                break;
              }
              case 79: {
                const alPos = stack.pop();
                const alId = arrIdFromStack(stack.pop());
                const alArr = collectionsAccess.getFloats(alId);
                if (alArr && alArr.length > 0) {
                  const alP = alPos * (alArr.length - 1);
                  const alIdx = Math.trunc(alP);
                  if (alIdx < 0) {
                    stack.push(alArr[0]);
                  } else if (alIdx >= alArr.length - 1) {
                    stack.push(alArr[alArr.length - 1]);
                  } else {
                    const alT = alP - alIdx;
                    stack.push(alArr[alIdx] + alT * (alArr[alIdx + 1] - alArr[alIdx]));
                  }
                } else {
                  stack.push(0);
                }
                break;
              }
              default:
                stack.push(0);
                break;
            }
          } else {
            stack.push(context.getFloat(id));
          }
        } else {
          stack.push(v);
        }
      }
      return stack.length > 0 ? stack[stack.length - 1] : 0;
    }
    /**
     * Monotonic cubic Hermite spline interpolation.
     * Time is implicitly spaced from 0 to 1 over the array.
     * Matches Java MonotonicSpline(null, y).getPos(pos).
     */
    static splineInterp(y, pos) {
      const n = y.length;
      if (n === 0) return 0;
      if (n === 1) return y[0];
      const t = new Float64Array(n);
      for (let i = 0; i < n; i++) t[i] = i / (n - 1);
      const slope = new Float64Array(n - 1);
      const tangent = new Float64Array(n);
      for (let i = 0; i < n - 1; i++) {
        const dt = t[i + 1] - t[i];
        slope[i] = (y[i + 1] - y[i]) / dt;
        if (i === 0) {
          tangent[i] = slope[i];
        } else {
          tangent[i] = (slope[i - 1] + slope[i]) * 0.5;
        }
      }
      tangent[n - 1] = slope[n - 2];
      for (let i = 0; i < n - 1; i++) {
        if (slope[i] === 0) {
          tangent[i] = 0;
          tangent[i + 1] = 0;
        } else {
          const a = tangent[i] / slope[i];
          const b = tangent[i + 1] / slope[i];
          const h = Math.hypot(a, b);
          if (h > 9) {
            const s = 3 / h;
            tangent[i] = s * a * slope[i];
            tangent[i + 1] = s * b * slope[i];
          }
        }
      }
      if (pos <= t[0]) {
        const h0 = t[1] - t[0];
        const d0 = (-6 * 0 * y[1] + 6 * 0 * y[1] + 6 * 0 * y[0] - 6 * 0 * y[0] + 3 * h0 * tangent[1] * 0 + 3 * h0 * tangent[0] * 0 - 2 * h0 * tangent[1] * 0 - 4 * h0 * tangent[0] * 0 + h0 * tangent[0]) / h0;
        return y[0] + (pos - t[0]) * d0;
      }
      if (pos >= t[n - 1]) {
        const hn = t[n - 1] - t[n - 2];
        const d1 = (-6 * y[n - 1] + 6 * y[n - 1] + 6 * y[n - 2] - 6 * y[n - 2] + 3 * hn * tangent[n - 1] + 3 * hn * tangent[n - 2] - 2 * hn * tangent[n - 1] - 4 * hn * tangent[n - 2] + hn * tangent[n - 2]) / hn;
        return y[n - 1] + (pos - t[n - 1]) * d1;
      }
      for (let i = 0; i < n - 1; i++) {
        if (pos < t[i + 1]) {
          const h = t[i + 1] - t[i];
          const x = (pos - t[i]) / h;
          const x2 = x * x;
          const x3 = x2 * x;
          const y1 = y[i], y2v = y[i + 1], t1 = tangent[i], t2 = tangent[i + 1];
          return -2 * x3 * y2v + 3 * x2 * y2v + 2 * x3 * y1 - 3 * x2 * y1 + y1 + h * t2 * x3 + h * t1 * x3 - h * t2 * x2 - 2 * h * t1 * x2 + h * t1 * x;
        }
      }
      return y[n - 1];
    }
    /**
     * Cubic bezier easing: given control points (x1,y1) and (x2,y2),
     * find the y value for a given x position using binary search.
     */
    static cubicEasing(x1, y1, x2, y2, pos) {
      if (pos <= 0) return 0;
      if (pos >= 1) return 1;
      let lo = 0, hi = 1;
      for (let i = 0; i < 20; i++) {
        const mid = (lo + hi) / 2;
        const bx = _FloatExpression.bezier(mid, x1, x2);
        if (bx < pos) lo = mid;
        else hi = mid;
      }
      const t = (lo + hi) / 2;
      return _FloatExpression.bezier(t, y1, y2);
    }
    static bezier(t, c1, c2) {
      const t1 = 1 - t;
      return 3 * t1 * t1 * t * c1 + 3 * t1 * t * t * c2 + t * t * t;
    }
    deepToString(indent) {
      return `${indent}FloatExpression(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const len = buffer.readInt();
      const valueLen = len & 65535;
      const animLen = len >> 16 & 65535;
      const bits = new Int32Array(valueLen);
      for (let i = 0; i < valueLen; i++) {
        bits[i] = buffer.readInt();
      }
      let animation = null;
      if (animLen > 0) {
        animation = new Float32Array(animLen);
        for (let i = 0; i < animLen; i++) {
          animation[i] = buffer.readFloat();
        }
      }
      operations.push(new _FloatExpression(id, bits, animation));
    }
  };
  _FloatExpression.OP_CODE = 81;
  // Math operator offset: all operator IDs are OFFSET + index
  _FloatExpression.OFFSET = 3211264;
  // 3211264
  _FloatExpression.ID_REGION_MASK = 7340032;
  _FloatExpression.ID_REGION_ARRAY = 2097152;
  var FloatExpression = _FloatExpression;

  // third_party/remote-compose-player/src/core/operations/utilities/AnimatedFloatExpression.ts
  var ARR_SENTINEL2 = 2 ** 42;
  function arrIdFromStack2(x) {
    return x >= ARR_SENTINEL2 ? x - ARR_SENTINEL2 | 0 : idFromNan(x);
  }
  var _AnimatedFloatExpression = class _AnimatedFloatExpression {
    constructor() {
      this.mStack = new Float32Array(128);
      this.mR0 = 0;
      this.mR1 = 0;
      this.mR2 = 0;
      this.mR3 = 0;
    }
    static isMathOperator(v) {
      if (!Number.isNaN(v)) return false;
      const id = idFromNan(v);
      if ((id & _AnimatedFloatExpression.ID_REGION_MASK) === _AnimatedFloatExpression.ID_REGION_ARRAY) {
        return false;
      }
      return id > _AnimatedFloatExpression.OFFSET && id <= _AnimatedFloatExpression.LAST_OP;
    }
    /** Bit-aware variant: true if raw float32 int bits encode a math operator token. */
    static isMathOperatorBits(b) {
      if (!isNaNBits(b)) return false;
      const id = idFromBits(b);
      if ((id & _AnimatedFloatExpression.ID_REGION_MASK) === _AnimatedFloatExpression.ID_REGION_ARRAY) {
        return false;
      }
      return id > _AnimatedFloatExpression.OFFSET && id <= _AnimatedFloatExpression.LAST_OP;
    }
    eval(...args) {
      let ca = null;
      let exp;
      let len;
      let vars = [];
      if (typeof args[0] === "number" || args[0] instanceof Float32Array || args[0] instanceof Int32Array || Array.isArray(args[0])) {
        exp = args[0];
        len = args[1];
        vars = args.slice(2);
      } else {
        ca = args[0];
        exp = args[1];
        len = args[2];
        vars = args.slice(3);
      }
      const useBits = exp instanceof Int32Array;
      const s = this.mStack;
      let sp = -1;
      for (let i = 0; i < len; i++) {
        const raw = exp[i];
        const bits = useBits ? raw | 0 : floatToRawIntBits(raw);
        if (isNaNBits(bits)) {
          const id = idFromBits(bits);
          if ((id & _AnimatedFloatExpression.ID_REGION_MASK) === _AnimatedFloatExpression.ID_REGION_ARRAY) {
            s[++sp] = ARR_SENTINEL2 + id;
          } else {
            sp = this.opEval(sp, id, s, ca, vars);
          }
        } else {
          s[++sp] = useBits ? intBitsToFloat(bits) : raw;
        }
      }
      return sp >= 0 ? s[sp] : 0;
    }
    opEval(sp, id, s, ca, vars) {
      const OFFSET3 = _AnimatedFloatExpression.OFFSET;
      switch (id) {
        case OFFSET3 + 1:
          s[sp - 1] += s[sp];
          return sp - 1;
        // ADD
        case OFFSET3 + 2:
          s[sp - 1] -= s[sp];
          return sp - 1;
        // SUB
        case OFFSET3 + 3:
          s[sp - 1] *= s[sp];
          return sp - 1;
        // MUL
        case OFFSET3 + 4:
          s[sp - 1] /= s[sp];
          return sp - 1;
        // DIV
        case OFFSET3 + 5:
          s[sp - 1] %= s[sp];
          return sp - 1;
        // MOD
        case OFFSET3 + 6:
          s[sp - 1] = Math.min(s[sp - 1], s[sp]);
          return sp - 1;
        // MIN
        case OFFSET3 + 7:
          s[sp - 1] = Math.max(s[sp - 1], s[sp]);
          return sp - 1;
        // MAX
        case OFFSET3 + 8:
          s[sp - 1] = Math.pow(s[sp - 1], s[sp]);
          return sp - 1;
        // POW
        case OFFSET3 + 9:
          s[sp] = Math.sqrt(s[sp]);
          return sp;
        // SQRT
        case OFFSET3 + 10:
          s[sp] = Math.abs(s[sp]);
          return sp;
        // ABS
        case OFFSET3 + 11:
          s[sp] = Math.sign(s[sp]);
          return sp;
        // SIGN
        case OFFSET3 + 12:
          s[sp - 1] = Math.abs(s[sp - 1]) * Math.sign(s[sp]);
          return sp - 1;
        // COPY_SIGN
        case OFFSET3 + 13:
          s[sp] = Math.exp(s[sp]);
          return sp;
        // EXP
        case OFFSET3 + 14:
          s[sp] = Math.floor(s[sp]);
          return sp;
        // FLOOR
        case OFFSET3 + 15:
          s[sp] = Math.log10(s[sp]);
          return sp;
        // LOG
        case OFFSET3 + 16:
          s[sp] = Math.log(s[sp]);
          return sp;
        // LN
        case OFFSET3 + 17:
          s[sp] = Math.round(s[sp]);
          return sp;
        // ROUND
        case OFFSET3 + 18:
          s[sp] = Math.sin(s[sp]);
          return sp;
        // SIN
        case OFFSET3 + 19:
          s[sp] = Math.cos(s[sp]);
          return sp;
        // COS
        case OFFSET3 + 20:
          s[sp] = Math.tan(s[sp]);
          return sp;
        // TAN
        case OFFSET3 + 21:
          s[sp] = Math.asin(s[sp]);
          return sp;
        // ASIN
        case OFFSET3 + 22:
          s[sp] = Math.acos(s[sp]);
          return sp;
        // ACOS
        case OFFSET3 + 23:
          s[sp] = Math.atan(s[sp]);
          return sp;
        // ATAN
        case OFFSET3 + 24:
          s[sp - 1] = Math.atan2(s[sp - 1], s[sp]);
          return sp - 1;
        // ATAN2
        case OFFSET3 + 25:
          s[sp - 2] = s[sp] + s[sp - 1] * s[sp - 2];
          return sp - 2;
        // MAD
        case OFFSET3 + 26: {
          const c = s[sp];
          s[sp - 2] = c > 0 ? s[sp - 1] : s[sp - 2];
          return sp - 2;
        }
        case OFFSET3 + 27:
          s[sp - 2] = Math.min(Math.max(s[sp - 2], s[sp]), s[sp - 1]);
          return sp - 2;
        // CLAMP
        case OFFSET3 + 28:
          s[sp] = Math.pow(s[sp], 1 / 3);
          return sp;
        // CBRT
        case OFFSET3 + 29:
          s[sp] = s[sp] * 57.29578;
          return sp;
        // DEG
        case OFFSET3 + 30:
          s[sp] = s[sp] * 0.017453292;
          return sp;
        // RAD
        case OFFSET3 + 31:
          s[sp] = Math.ceil(s[sp]);
          return sp;
        // CEIL
        case OFFSET3 + 32: {
          const arrId = arrIdFromStack2(s[sp - 1]);
          if (ca) s[sp - 1] = ca.getFloatValue(arrId, Math.trunc(s[sp]));
          return sp - 1;
        }
        case OFFSET3 + 33: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            let mx = arr[0];
            for (let i = 1; i < arr.length; i++) mx = Math.max(mx, arr[i]);
            s[sp] = mx;
          }
          return sp;
        }
        case OFFSET3 + 34: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            if (arr.length > 0) {
              let mn = arr[0];
              for (let i = 1; i < arr.length; i++) mn = Math.min(mn, arr[i]);
              s[sp] = mn;
            }
          }
          return sp;
        }
        case OFFSET3 + 35: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            let sum = 0;
            for (let i = 0; i < arr.length; i++) sum += arr[i];
            s[sp] = sum;
          }
          return sp;
        }
        case OFFSET3 + 36: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            let sum = 0;
            for (let i = 0; i < arr.length; i++) sum += arr[i];
            s[sp] = arr.length > 0 ? sum / arr.length : 0;
          }
          return sp;
        }
        case OFFSET3 + 37: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) s[sp] = ca.getListLength(arrId);
          return sp;
        }
        case OFFSET3 + 38: {
          const arrId = arrIdFromStack2(s[sp - 1]);
          if (ca) {
            const fl = ca.getFloats(arrId);
            s[sp - 1] = fl ? FloatExpression.splineInterp(fl, s[sp]) : 0;
          }
          return sp - 1;
        }
        case OFFSET3 + 39:
          s[++sp] = Math.random();
          return sp;
        case OFFSET3 + 40:
          return sp - 1;
        case OFFSET3 + 41: {
          let x = Math.trunc(s[sp]);
          x = x << 13 ^ x;
          s[sp] = 1 - (x * (x * x * 15731 + 789221) + 1376312589 & 2147483647) / 1073741800;
          return sp;
        }
        case OFFSET3 + 42:
          s[sp] = Math.random() * (s[sp] - s[sp - 1]) + s[sp - 1];
          return sp;
        case OFFSET3 + 43:
          s[sp - 1] = s[sp - 1] * s[sp - 1] + s[sp] * s[sp];
          return sp - 1;
        case OFFSET3 + 44:
          s[sp - 1] = s[sp - 1] > s[sp] ? 1 : 0;
          return sp - 1;
        case OFFSET3 + 45:
          s[sp] = s[sp] * s[sp];
          return sp;
        case OFFSET3 + 46:
          s[sp + 1] = s[sp];
          return sp + 1;
        case OFFSET3 + 47:
          s[sp - 1] = Math.hypot(s[sp - 1], s[sp]);
          return sp - 1;
        case OFFSET3 + 48: {
          const tmp = s[sp - 1];
          s[sp - 1] = s[sp];
          s[sp] = tmp;
          return sp;
        }
        case OFFSET3 + 49: {
          const t = s[sp];
          s[sp - 2] = s[sp - 2] + (s[sp - 1] - s[sp - 2]) * t;
          return sp - 2;
        }
        case OFFSET3 + 50: {
          const val = s[sp - 2], max2 = s[sp - 1], min1 = s[sp];
          if (val < min1) s[sp - 2] = 0;
          else if (val > max2) s[sp - 2] = 1;
          else {
            const vv = (val - min1) / (max2 - min1);
            s[sp - 2] = vv * vv * (3 - 2 * vv);
          }
          return sp - 2;
        }
        case OFFSET3 + 51:
          s[sp] = Math.log(s[sp]) / Math.log(2);
          return sp;
        case OFFSET3 + 52:
          s[sp] = 1 / s[sp];
          return sp;
        case OFFSET3 + 53:
          s[sp] = s[sp] - Math.trunc(s[sp]);
          return sp;
        case OFFSET3 + 54: {
          const max2 = s[sp] * 2;
          const tmp = s[sp - 1] % max2;
          s[sp - 1] = tmp < s[sp] ? tmp : max2 - tmp;
          return sp - 1;
        }
        case OFFSET3 + 55:
          return sp;
        // NOP
        case OFFSET3 + 56:
          this.mR0 = s[sp];
          return sp - 1;
        // STORE_R0
        case OFFSET3 + 57:
          this.mR1 = s[sp];
          return sp - 1;
        // STORE_R1
        case OFFSET3 + 58:
          this.mR2 = s[sp];
          return sp - 1;
        // STORE_R2
        case OFFSET3 + 59:
          this.mR3 = s[sp];
          return sp - 1;
        // STORE_R3
        case OFFSET3 + 60:
          s[++sp] = this.mR0;
          return sp;
        // LOAD_R0
        case OFFSET3 + 61:
          s[++sp] = this.mR1;
          return sp;
        // LOAD_R1
        case OFFSET3 + 62:
          s[++sp] = this.mR2;
          return sp;
        // LOAD_R2
        case OFFSET3 + 63:
          s[++sp] = this.mR3;
          return sp;
        // LOAD_R3
        case OFFSET3 + 70:
          s[++sp] = vars.length > 0 ? vars[0] : 0;
          return sp;
        case OFFSET3 + 71:
          s[++sp] = vars.length > 1 ? vars[1] : 0;
          return sp;
        case OFFSET3 + 72:
          s[++sp] = vars.length > 2 ? vars[2] : 0;
          return sp;
        case OFFSET3 + 73:
          s[sp] = -s[sp];
          return sp;
        case OFFSET3 + 74: {
          const x1 = s[sp - 4], y1 = s[sp - 3], x2 = s[sp - 2], y2 = s[sp - 1], pos = s[sp];
          s[sp - 4] = FloatExpression.cubicEasing(x1, y1, x2, y2, pos);
          return sp - 4;
        }
        case OFFSET3 + 75: {
          const arrId = arrIdFromStack2(s[sp - 1]);
          const frac = s[sp] - Math.floor(s[sp]);
          const r = frac < 0 ? frac + 1 : frac;
          if (ca) {
            const fl = ca.getFloats(arrId);
            s[sp - 1] = fl ? FloatExpression.splineInterp(fl, r) : 0;
          }
          return sp - 1;
        }
        case OFFSET3 + 76: {
          const arrId = arrIdFromStack2(s[sp - 1]);
          const last = Math.trunc(s[sp]);
          let sum = 0;
          if (ca) {
            for (let j = 0; j <= last; j++) sum += ca.getFloatValue(arrId, j);
          }
          s[sp - 1] = sum;
          return sp - 1;
        }
        case OFFSET3 + 77: {
          const idX = arrIdFromStack2(s[sp - 1]);
          const idY = arrIdFromStack2(s[sp]);
          if (ca) {
            const ax = ca.getFloats(idX);
            const ay = ca.getFloats(idY);
            let sum = 0;
            for (let i = 0; i < ax.length; i++) sum += ax[i] * ay[i];
            s[sp - 1] = sum;
          }
          return sp - 1;
        }
        case OFFSET3 + 78: {
          const arrId = arrIdFromStack2(s[sp]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            let sum = 0;
            for (let i = 0; i < arr.length; i++) sum += arr[i] * arr[i];
            s[sp] = sum;
          }
          return sp;
        }
        case OFFSET3 + 79: {
          const arrId = arrIdFromStack2(s[sp - 1]);
          if (ca) {
            const arr = ca.getFloats(arrId);
            const p = s[sp] * (arr.length - 1);
            const idx = Math.trunc(p);
            if (idx < 0) s[sp - 1] = arr[0];
            else if (idx >= arr.length - 1) s[sp - 1] = arr[arr.length - 1];
            else {
              const t = p - idx;
              s[sp - 1] = arr[idx] + t * (arr[idx + 1] - arr[idx]);
            }
          }
          return sp - 1;
        }
      }
      return sp;
    }
  };
  _AnimatedFloatExpression.OFFSET = 3211264;
  _AnimatedFloatExpression.LAST_OP = _AnimatedFloatExpression.OFFSET + 79;
  _AnimatedFloatExpression.ID_REGION_MASK = 7340032;
  _AnimatedFloatExpression.ID_REGION_ARRAY = 2097152;
  var AnimatedFloatExpression = _AnimatedFloatExpression;

  // third_party/remote-compose-player/src/core/operations/utilities/touch/VelocityEasing.ts
  var Stage = class {
    constructor(n) {
      this.mStartV = 0;
      this.mStartPos = 0;
      this.mStartTime = 0;
      this.mEndV = 0;
      this.mEndPos = 0;
      this.mEndTime = 0;
      this.mDeltaV = 0;
      this.mDeltaT = 0;
      this.mStage = n;
    }
    setUp(startV, startPos, startTime, endV, endPos, endTime) {
      this.mStartV = startV;
      this.mStartPos = startPos;
      this.mStartTime = startTime;
      this.mEndV = endV;
      this.mEndTime = endTime;
      this.mEndPos = endPos;
      this.mDeltaV = this.mEndV - this.mStartV;
      this.mDeltaT = this.mEndTime - this.mStartTime;
    }
    getPos(t) {
      const dt = t - this.mStartTime;
      const pt = dt / this.mDeltaT;
      const v = this.mStartV + this.mDeltaV * pt;
      return dt * (this.mStartV + v) / 2 + this.mStartPos;
    }
    getVel(t) {
      const dt = t - this.mStartTime;
      const pt = dt / (this.mEndTime - this.mStartTime);
      return this.mStartV + this.mDeltaV * pt;
    }
  };
  var VelocityEasing = class {
    constructor() {
      this.mStartPos = 0;
      this.mStartV = 0;
      this.mEndPos = 0;
      this.mDuration = 0;
      this.mStage = [new Stage(1), new Stage(2), new Stage(3)];
      this.mNumberOfStages = 0;
      this.mEasing = null;
      this.mEasingAdapterDistance = 0;
      this.mEasingAdapterA = 0;
      this.mEasingAdapterB = 0;
      this.mOneDimension = true;
      this.mTotalEasingDuration = 0;
    }
    getDuration() {
      if (this.mEasing !== null) {
        return this.mTotalEasingDuration;
      }
      return this.mDuration;
    }
    getV(t) {
      if (this.mEasing === null) {
        for (let i = 0; i < this.mNumberOfStages; i++) {
          if (this.mStage[i].mEndTime > t) {
            return this.mStage[i].getVel(t);
          }
        }
        return 0;
      }
      const lastStages = this.mNumberOfStages - 1;
      for (let i = 0; i < lastStages; i++) {
        if (this.mStage[i].mEndTime > t) {
          return this.mStage[i].getVel(t);
        }
      }
      return this.getEasingDiff(t - this.mStage[lastStages].mStartTime);
    }
    getPos(t) {
      if (this.mEasing === null) {
        for (let i = 0; i < this.mNumberOfStages; i++) {
          if (this.mStage[i].mEndTime > t) {
            return this.mStage[i].getPos(t);
          }
        }
        return this.mEndPos;
      }
      const lastStages = this.mNumberOfStages - 1;
      for (let i = 0; i < lastStages; i++) {
        if (this.mStage[i].mEndTime > t) {
          return this.mStage[i].getPos(t);
        }
      }
      let ret = this.getEasing(t - this.mStage[lastStages].mStartTime);
      ret += this.mStage[lastStages].mStartPos;
      return ret;
    }
    config(currentPos, destination, currentVelocity, maxTime, maxAcceleration, maxVelocity, easing) {
      let pos = currentPos;
      let velocity = currentVelocity;
      if (pos === destination) {
        pos += 1;
      }
      this.mStartPos = pos;
      this.mEndPos = destination;
      if (easing !== null) {
        this.mEasing = easing.clone();
      } else {
        this.mEasing = null;
      }
      const dir = Math.sign(destination - pos);
      const maxV = maxVelocity * dir;
      const maxA = maxAcceleration * dir;
      if (velocity === 0) {
        velocity = 1e-4 * dir;
      }
      this.mStartV = velocity;
      if (!this.rampDown(pos, destination, velocity, maxTime)) {
        if (!(this.mOneDimension && this.cruseThenRampDown(pos, destination, velocity, maxTime, maxA, maxV))) {
          if (!this.rampUpRampDown(pos, destination, velocity, maxA, maxV, maxTime)) {
            this.rampUpCruseRampDown(pos, destination, velocity, maxA, maxV, maxTime);
          }
        }
      }
      if (this.mOneDimension) {
        this.configureEasingAdapter();
      }
    }
    rampDown(currentPos, destination, currentVelocity, maxTime) {
      const timeToDestination = 2 * ((destination - currentPos) / currentVelocity);
      if (timeToDestination > 0 && timeToDestination <= maxTime) {
        this.mNumberOfStages = 1;
        this.mStage[0].setUp(currentVelocity, currentPos, 0, 0, destination, timeToDestination);
        this.mDuration = timeToDestination;
        return true;
      }
      return false;
    }
    cruseThenRampDown(currentPos, destination, currentVelocity, maxTime, maxA, _maxV) {
      const timeToBreak = currentVelocity / maxA;
      const brakeDist = currentVelocity * timeToBreak / 2;
      const cruseDist = destination - currentPos - brakeDist;
      const cruseTime = cruseDist / currentVelocity;
      const totalTime = cruseTime + timeToBreak;
      if (totalTime > 0 && totalTime < maxTime) {
        this.mNumberOfStages = 2;
        this.mStage[0].setUp(currentVelocity, currentPos, 0, currentVelocity, cruseDist, cruseTime);
        this.mStage[1].setUp(currentVelocity, currentPos + cruseDist, cruseTime, 0, destination, cruseTime + timeToBreak);
        this.mDuration = cruseTime + timeToBreak;
        return true;
      }
      return false;
    }
    rampUpRampDown(currentPos, destination, currentVelocity, maxA, maxVelocity, maxTime) {
      let peak_v = Math.sign(maxA) * Math.sqrt(maxA * (destination - currentPos) + currentVelocity * currentVelocity / 2);
      if (maxVelocity / peak_v > 1) {
        let t1 = (peak_v - currentVelocity) / maxA;
        let d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
        let t2 = peak_v / maxA;
        this.mNumberOfStages = 2;
        this.mStage[0].setUp(currentVelocity, currentPos, 0, peak_v, d1, t1);
        this.mStage[1].setUp(peak_v, d1, t1, 0, destination, t2 + t1);
        this.mDuration = t2 + t1;
        if (this.mDuration > maxTime) {
          return false;
        }
        if (this.mDuration < maxTime / 2) {
          t1 = this.mDuration / 2;
          t2 = t1;
          peak_v = (2 * (destination - currentPos) / t1 - currentVelocity) / 2;
          d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
          this.mNumberOfStages = 2;
          this.mStage[0].setUp(currentVelocity, currentPos, 0, peak_v, d1, t1);
          this.mStage[1].setUp(peak_v, d1, t1, 0, destination, t2 + t1);
          this.mDuration = t2 + t1;
          if (this.mDuration > maxTime) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
    rampUpCruseRampDown(currentPos, destination, currentVelocity, _maxA, _maxV, maxTime) {
      const t1 = maxTime / 3;
      const t2 = t1 * 2;
      const distance = destination - currentPos;
      const dt2 = t2 - t1;
      const dt3 = maxTime - t2;
      const v1 = (2 * distance - currentVelocity * t1) / (t1 + 2 * dt2 + dt3);
      this.mDuration = maxTime;
      const d1 = (currentVelocity + v1) * t1 / 2;
      const d2 = (v1 + v1) * (t2 - t1) / 2;
      this.mNumberOfStages = 3;
      this.mStage[0].setUp(currentVelocity, currentPos, 0, v1, currentPos + d1, t1);
      this.mStage[1].setUp(v1, currentPos + d1, t1, v1, currentPos + d1 + d2, t2);
      this.mStage[2].setUp(v1, currentPos + d1 + d2, t2, 0, destination, maxTime);
      this.mDuration = maxTime;
    }
    getEasing(t) {
      const gx = t * t * this.mEasingAdapterA + t * this.mEasingAdapterB;
      if (gx > 1) {
        return this.mEasingAdapterDistance;
      } else {
        return this.mEasing.get(gx) * this.mEasingAdapterDistance;
      }
    }
    getEasingDiff(t) {
      const gx = t * t * this.mEasingAdapterA + t * this.mEasingAdapterB;
      if (gx > 1) {
        return 0;
      } else {
        return this.mEasing.getDiff(gx) * this.mEasingAdapterDistance * (t * this.mEasingAdapterA + this.mEasingAdapterB);
      }
    }
    configureEasingAdapter() {
      if (this.mEasing === null) return;
      const last = this.mNumberOfStages - 1;
      const initialVelocity = this.mStage[last].mStartV;
      const distance = this.mStage[last].mEndPos - this.mStage[last].mStartPos;
      const baseVel = this.mEasing.getDiff(0);
      this.mEasingAdapterB = initialVelocity / (baseVel * distance);
      this.mEasingAdapterA = 1 - this.mEasingAdapterB;
      this.mEasingAdapterDistance = distance;
      const easingDuration = (Math.sqrt(4 * this.mEasingAdapterA + this.mEasingAdapterB * this.mEasingAdapterB) - this.mEasingAdapterB) / (2 * this.mEasingAdapterA);
      this.mTotalEasingDuration = easingDuration + this.mStage[last].mStartTime;
    }
  };

  // third_party/remote-compose-player/src/core/operations/TouchExpression.ts
  var _TouchExpression = class _TouchExpression extends Operation {
    constructor(id, exp, defValueBits, minBits, maxBits, touchEffects, velocityId, stopMode, stopSpec, easingSpec) {
      super();
      this.mMaxTime = 1;
      this.mMaxAcceleration = 5;
      this.mMaxVelocity = 7;
      this.mWrapMode = false;
      // Runtime state
      this.mTouchDown = false;
      this.mEasingToStop = false;
      this.mValue = 0;
      this.mValueAtDown = 0;
      this.mDownTouchValue = 0;
      this.mCurrentValue = NaN;
      this.mUnmodified = true;
      this.mLastValue = 0;
      this.mMaxAtDown = NaN;
      this.mMinAtDown = NaN;
      this.mTouchUpTime = 0;
      this.mLastChange = NaN;
      this.mLastCalculatedValue = NaN;
      // Resolved expression token bits fed to AnimatedFloatExpression.eval.
      this.mPreCalcValue = null;
      this.mExp = new AnimatedFloatExpression();
      this.mEasyTouch = new VelocityEasing();
      // Component bounds
      this.mScrLeft = 0;
      this.mScrRight = 0;
      this.mScrTop = 0;
      this.mScrBottom = 0;
      this.mComponent = null;
      this.mId = id;
      this.mSrcExp = exp;
      this.mDefValueBits = defValueBits;
      this.mOutDefValue = isNaNBits(defValueBits) ? 0 : intBitsToFloat(defValueBits);
      this.mMode = _TouchExpression.STOP_ABSOLUTE_POS === stopMode ? 1 : 0;
      this.mMaxBits = maxBits;
      this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
      this.mTouchEffects = touchEffects;
      this.mVelocityId = velocityId;
      this.mStopSpec = stopSpec;
      this.mOutStopSpec = new Float32Array(stopSpec.length);
      for (let i = 0; i < stopSpec.length; i++) {
        this.mOutStopSpec[i] = isNaNBits(stopSpec[i]) ? 0 : intBitsToFloat(stopSpec[i]);
      }
      if (isNaNBits(minBits) && idFromBits(minBits) === 0) {
        this.mWrapMode = true;
        this.mMinBits = floatToRawIntBits(0);
        this.mOutMin = 0;
      } else {
        this.mMinBits = minBits;
        this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
      }
      this.mStopMode = stopMode;
      if (easingSpec !== null && easingSpec.length >= 4) {
        if (easingSpec[0] === 0) {
          this.mMaxTime = easingSpec[1];
          this.mMaxAcceleration = easingSpec[2];
          this.mMaxVelocity = easingSpec[3];
        }
      }
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
      if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
      if (isNaNBits(this.mDefValueBits)) context.listensTo(idFromBits(this.mDefValueBits), this);
      if (this.mComponent === null) {
        context.addTouchListener(this);
      }
      for (const b of this.mSrcExp) {
        if (isNaNBits(b) && !AnimatedFloatExpression.isMathOperatorBits(b) && !this.isDataVariableBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
      for (const b of this.mStopSpec) {
        if (isNaNBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    isDataVariableBits(b) {
      if (!isNaNBits(b)) return false;
      const id = idFromBits(b);
      return (id & 7340032) === 2097152;
    }
    updateVariables(context) {
      if (this.mPreCalcValue === null || this.mPreCalcValue.length !== this.mSrcExp.length) {
        this.mPreCalcValue = new Int32Array(this.mSrcExp.length);
      }
      if (this.mOutStopSpec.length !== this.mStopSpec.length) {
        this.mOutStopSpec = new Float32Array(this.mStopSpec.length);
      }
      if (isNaNBits(this.mMaxBits)) {
        this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
      }
      if (isNaNBits(this.mMinBits)) {
        this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
      }
      if (isNaNBits(this.mDefValueBits)) {
        this.mOutDefValue = context.getFloat(idFromBits(this.mDefValueBits));
      }
      for (let i = 0; i < this.mSrcExp.length; i++) {
        const b = this.mSrcExp[i];
        if (isNaNBits(b) && !AnimatedFloatExpression.isMathOperatorBits(b) && !this.isDataVariableBits(b)) {
          this.mPreCalcValue[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
        } else {
          this.mPreCalcValue[i] = b;
        }
      }
      for (let i = 0; i < this.mStopSpec.length; i++) {
        const b = this.mStopSpec[i];
        if (isNaNBits(b)) {
          this.mOutStopSpec[i] = context.getFloat(idFromBits(b));
        } else {
          this.mOutStopSpec[i] = intBitsToFloat(b);
        }
      }
    }
    wrap(pos) {
      if (!this.mWrapMode) return pos;
      pos = pos % this.mOutMax;
      if (pos < 0) pos += this.mOutMax;
      return pos;
    }
    getStopPosition(pos, slope) {
      let target = pos + slope / 2;
      if (this.mWrapMode) {
        pos = this.wrap(pos);
        target = pos + slope / 2;
      } else {
        target = Math.max(Math.min(target, this.mOutMax), this.mOutMin);
      }
      const min = this.mWrapMode ? 0 : this.mOutMin;
      switch (this.mStopMode) {
        case _TouchExpression.STOP_ENDS:
          return pos + slope > (this.mOutMax + min) / 2 ? this.mOutMax : min;
        case _TouchExpression.STOP_INSTANTLY:
          return pos;
        case _TouchExpression.STOP_NOTCHES_EVEN:
        case _TouchExpression.STOP_NOTCHES_SINGLE_EVEN: {
          const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
          const notchMax = this.mOutStopSpec.length > 1 ? this.mOutStopSpec[1] : this.mOutMax;
          const step = (notchMax - min) / evenSpacing;
          let notch = min + step * Math.trunc(0.5 + (target - this.mOutMin) / step);
          if (this.mStopMode === _TouchExpression.STOP_NOTCHES_SINGLE_EVEN) {
            notch = Math.min(this.mMaxAtDown, notch);
            notch = Math.max(this.mMinAtDown, notch);
          }
          if (!this.mWrapMode) {
            notch = Math.max(Math.min(notch, this.mOutMax), min);
          }
          return notch;
        }
        case _TouchExpression.STOP_NOTCHES_PERCENTS: {
          let minPos = min;
          let minPosDist = Math.abs(this.mOutMin - target);
          for (let i = 0; i < this.mStopSpec.length; i++) {
            const p = this.mOutMin + this.mOutStopSpec[i] * (this.mOutMax - this.mOutMin);
            const dist = Math.abs(p - target);
            if (minPosDist > dist) {
              minPosDist = dist;
              minPos = p;
            }
          }
          return minPos;
        }
        case _TouchExpression.STOP_NOTCHES_ABSOLUTE: {
          let minPos = this.mOutMin;
          let minPosDist = Math.abs(this.mOutMin - target);
          for (let i = 0; i < this.mStopSpec.length; i++) {
            const dist = Math.abs(this.mOutStopSpec[i] - target);
            if (minPosDist > dist) {
              minPosDist = dist;
              minPos = this.mOutStopSpec[i];
            }
          }
          return minPos;
        }
        case _TouchExpression.STOP_GENTLY:
        default:
          return target;
      }
    }
    haptic(context) {
      let touch = this.mTouchEffects & 255;
      if ((this.mTouchEffects & 1 << 15) !== 0) {
        touch = context.getInteger(this.mTouchEffects & 32767);
      }
      context.hapticEffect(touch);
    }
    crossNotchCheck(context) {
      const prev = this.mLastValue;
      const next = this.mCurrentValue;
      this.mLastValue = next;
      const min = this.mWrapMode ? 0 : this.mOutMin;
      const max = this.mOutMax;
      switch (this.mStopMode) {
        case _TouchExpression.STOP_ENDS:
          if ((min - prev) * (max - prev) < 0 !== (min - next) * (max - next) < 0) {
            this.haptic(context);
          }
          break;
        case _TouchExpression.STOP_INSTANTLY:
          this.haptic(context);
          break;
        case _TouchExpression.STOP_NOTCHES_EVEN:
        case _TouchExpression.STOP_NOTCHES_SINGLE_EVEN: {
          const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
          const step = (max - min) / evenSpacing;
          if (Math.trunc((prev - min) / step) !== Math.trunc((next - min) / step)) {
            this.haptic(context);
          }
          break;
        }
        case _TouchExpression.STOP_NOTCHES_PERCENTS:
          for (let i = 0; i < this.mStopSpec.length; i++) {
            const p = this.mOutMin + this.mOutStopSpec[i] * (this.mOutMax - this.mOutMin);
            if ((prev - p) * (next - p) < 0) this.haptic(context);
          }
          break;
        case _TouchExpression.STOP_NOTCHES_ABSOLUTE:
          for (let i = 0; i < this.mStopSpec.length; i++) {
            const p = this.mOutStopSpec[i];
            if ((prev - p) * (next - p) < 0) this.haptic(context);
          }
          break;
      }
    }
    updateBounds(context) {
      const comp = this.mComponent;
      if (comp !== null) {
        if (context.getTouchVersion() === 1) {
          this.mScrLeft = 0;
          this.mScrTop = 0;
          this.mScrRight = comp.getWidth();
          this.mScrBottom = comp.getHeight();
        } else {
          let x = comp.getX();
          let y = comp.getY();
          const w = comp.getWidth();
          const h = comp.getHeight();
          let parent = comp.getParent();
          while (parent !== null) {
            x += parent.getX();
            y += parent.getY();
            parent = parent.getParent();
          }
          this.mScrLeft = x;
          this.mScrTop = y;
          this.mScrRight = w + x;
          this.mScrBottom = h + y;
        }
      }
    }
    apply(context) {
      this.updateBounds(context);
      if (this.mUnmodified) {
        this.mCurrentValue = this.mOutDefValue;
        context.loadFloat(this.mId, this.wrap(this.mCurrentValue));
        return;
      }
      if (this.mEasingToStop) {
        const time = context.getAnimationTime() - this.mTouchUpTime;
        let value = this.mEasyTouch.getPos(time);
        this.mCurrentValue = value;
        if (this.mWrapMode) {
          value = this.wrap(value);
        } else {
          value = Math.min(Math.max(value, this.mOutMin), this.mOutMax);
        }
        context.loadFloat(this.mId, value);
        if (this.mEasyTouch.getDuration() < time) {
          this.mEasingToStop = false;
        }
        this.crossNotchCheck(context);
        context.needsRepaint();
        return;
      }
      if (this.mTouchDown && this.mPreCalcValue) {
        let value = this.mExp.eval(
          context.getCollectionsAccess(),
          this.mPreCalcValue,
          this.mPreCalcValue.length
        );
        if (this.mMode === 0) {
          value = this.mValueAtDown + (value - this.mDownTouchValue);
        }
        if (this.mWrapMode) {
          value = this.wrap(value);
        } else {
          value = Math.min(Math.max(value, this.mOutMin), this.mOutMax);
        }
        this.mCurrentValue = value;
      }
      this.crossNotchCheck(context);
      if (this.mStopMode === _TouchExpression.STOP_NOTCHES_SINGLE_EVEN) {
        this.mCurrentValue = Math.min(this.mMaxAtDown, this.mCurrentValue);
        this.mCurrentValue = Math.max(this.mMinAtDown, this.mCurrentValue);
      }
      if (!this.mWrapMode) {
        if (!Number.isNaN(this.mOutMin)) this.mCurrentValue = Math.max(this.mCurrentValue, this.mOutMin);
        if (!Number.isNaN(this.mOutMax)) this.mCurrentValue = Math.min(this.mCurrentValue, this.mOutMax);
      }
      context.loadFloat(this.mId, this.wrap(this.mCurrentValue));
    }
    touchDown(context, x, y) {
      if (this.mComponent !== null && !(x >= this.mScrLeft && x <= this.mScrRight && y >= this.mScrTop && y <= this.mScrBottom)) {
        return;
      }
      this.mEasingToStop = false;
      this.mTouchDown = true;
      this.mUnmodified = false;
      if (this.mMode === 0 && this.mPreCalcValue) {
        this.mValueAtDown = context.getFloat(this.mId);
        if (_TouchExpression.STOP_NOTCHES_SINGLE_EVEN === this.mStopMode) {
          const min = this.mWrapMode ? 0 : this.mOutMin;
          const evenSpacing = Math.trunc(this.mOutStopSpec[0]);
          const notchMax = this.mOutStopSpec.length > 1 ? this.mOutStopSpec[1] : this.mOutMax;
          const step = (notchMax - min) / evenSpacing;
          const notch = this.mValueAtDown;
          this.mMaxAtDown = notch + step;
          this.mMinAtDown = notch - step;
        }
        this.mDownTouchValue = this.mExp.eval(
          context.getCollectionsAccess(),
          this.mPreCalcValue,
          this.mPreCalcValue.length
        );
      }
      context.needsRepaint();
    }
    touchDrag(context, _x, _y) {
      if (!this.mTouchDown) return;
      this.apply(context);
      context.needsRepaint();
    }
    touchUp(context, x, y, dx, dy) {
      if (!this.mTouchDown) return;
      this.mTouchDown = false;
      const dt = 1e-4;
      if (this.mStopMode === _TouchExpression.STOP_INSTANTLY) return;
      if (!this.mPreCalcValue) return;
      const v = this.mExp.eval(
        context.getCollectionsAccess(),
        this.mPreCalcValue,
        this.mPreCalcValue.length
      );
      if (context.getTouchVersion() === 1) {
        for (let i = 0; i < this.mSrcExp.length; i++) {
          if (isNaNBits(this.mSrcExp[i])) {
            const id = idFromBits(this.mSrcExp[i]);
            if (id === RemoteContext.ID_TOUCH_POS_X) {
              this.mPreCalcValue[i] = floatToRawIntBits(intBitsToFloat(this.mPreCalcValue[i]) + dx * dt);
            } else if (id === RemoteContext.ID_TOUCH_POS_Y) {
              this.mPreCalcValue[i] = floatToRawIntBits(intBitsToFloat(this.mPreCalcValue[i]) + dy * dt);
            }
          }
        }
      } else {
        for (let i = 0; i < this.mSrcExp.length; i++) {
          if (isNaNBits(this.mSrcExp[i])) {
            const id = idFromBits(this.mSrcExp[i]);
            if (id === RemoteContext.ID_TOUCH_POS_X) {
              this.mPreCalcValue[i] = floatToRawIntBits(x + dx * dt);
            } else if (id === RemoteContext.ID_TOUCH_POS_Y) {
              this.mPreCalcValue[i] = floatToRawIntBits(y + dy * dt);
            }
          }
        }
      }
      const vdt = this.mExp.eval(
        context.getCollectionsAccess(),
        this.mPreCalcValue,
        this.mPreCalcValue.length
      );
      const slope = (vdt - v) / dt;
      const value = context.getFloat(this.mId);
      this.mTouchUpTime = context.getAnimationTime();
      const dest = this.getStopPosition(value, slope);
      const time = Math.min(2, this.mMaxTime * Math.abs(dest - value) / (2 * this.mMaxVelocity));
      this.mEasyTouch.config(value, dest, slope, time, this.mMaxAcceleration, this.mMaxVelocity, null);
      this.mEasingToStop = true;
      context.needsRepaint();
    }
    setComponent(component) {
      this.mComponent = component;
      if (this.mComponent !== null) {
        try {
          const root = this.mComponent.getRoot();
          root.setHasTouchListeners(true);
        } catch (_e) {
        }
      }
    }
    deepToString(indent) {
      return `${indent}TouchExpression(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const startValue = buffer.readInt();
      const min = buffer.readInt();
      const max = buffer.readInt();
      const velocityId = buffer.readInt();
      const touchEffects = buffer.readInt();
      const len = buffer.readInt();
      const valueLen = len & 65535;
      const exp = new Int32Array(valueLen);
      for (let i = 0; i < valueLen; i++) exp[i] = buffer.readInt();
      const stopLogic = buffer.readInt();
      const stopLen = stopLogic & 65535;
      const stopMode = stopLogic >> 16;
      const stopsData = new Int32Array(stopLen);
      for (let i = 0; i < stopLen; i++) stopsData[i] = buffer.readInt();
      const easingLen = buffer.readInt();
      const easingData = easingLen > 0 ? new Float32Array(easingLen) : null;
      for (let i = 0; i < easingLen; i++) easingData[i] = buffer.readFloat();
      operations.push(new _TouchExpression(
        id,
        exp,
        startValue,
        min,
        max,
        touchEffects,
        velocityId,
        stopMode,
        stopsData,
        easingData
      ));
    }
  };
  _TouchExpression.OP_CODE = 157;
  _TouchExpression.STOP_GENTLY = 0;
  _TouchExpression.STOP_INSTANTLY = 1;
  _TouchExpression.STOP_ENDS = 2;
  _TouchExpression.STOP_NOTCHES_EVEN = 3;
  _TouchExpression.STOP_NOTCHES_PERCENTS = 4;
  _TouchExpression.STOP_NOTCHES_ABSOLUTE = 5;
  _TouchExpression.STOP_ABSOLUTE_POS = 6;
  _TouchExpression.STOP_NOTCHES_SINGLE_EVEN = 7;
  var TouchExpression = _TouchExpression;

  // third_party/remote-compose-player/src/core/operations/layout/Component.ts
  var _Visibility = class _Visibility {
    static isGone(v) {
      if (v >> 4 > 0) return (v & _Visibility.OVERRIDE_GONE) === _Visibility.OVERRIDE_GONE;
      return v === _Visibility.GONE;
    }
    static isVisible(v) {
      if (v >> 4 > 0) return (v & _Visibility.OVERRIDE_VISIBLE) === _Visibility.OVERRIDE_VISIBLE;
      return v === _Visibility.VISIBLE;
    }
    static isInvisible(v) {
      if (v >> 4 > 0) return (v & _Visibility.OVERRIDE_INVISIBLE) === _Visibility.OVERRIDE_INVISIBLE;
      return v === _Visibility.INVISIBLE;
    }
    static hasOverride(v) {
      return v >> 4 > 0;
    }
    static clearOverride(v) {
      return v & 15;
    }
    static add(v, override) {
      let result = (v & 15) + override;
      if ((result & _Visibility.CLEAR_OVERRIDE) === _Visibility.CLEAR_OVERRIDE) {
        result = result & 15;
      }
      return result;
    }
  };
  // Matches Java Component.Visibility encoding
  _Visibility.GONE = 0;
  _Visibility.VISIBLE = 1;
  _Visibility.INVISIBLE = 2;
  _Visibility.OVERRIDE_GONE = 16;
  _Visibility.OVERRIDE_VISIBLE = 32;
  _Visibility.OVERRIDE_INVISIBLE = 64;
  _Visibility.CLEAR_OVERRIDE = 128;
  var Visibility = _Visibility;
  var Component = class _Component extends PaintOperation {
    constructor(componentId, animationId = -1, x = 0, y = 0, width = 0, height = 0) {
      super();
      this.mAnimationId = -1;
      this.mParent = null;
      this.mChildren = [];
      // Position & dimensions
      this.mX = 0;
      this.mY = 0;
      this.mWidth = 0;
      this.mHeight = 0;
      this.mZIndex = 0;
      this.mVisibility = Visibility.VISIBLE;
      this.mNeedsMeasure = true;
      this.mNeedsRepaint = true;
      this.mFirstLayout = true;
      this.mComponentId = componentId;
      this.mAnimationId = animationId;
      this.mX = x;
      this.mY = y;
      this.mWidth = width;
      this.mHeight = height;
    }
    getComponentId() {
      return this.mComponentId;
    }
    setComponentId(id) {
      this.mComponentId = id;
    }
    getAnimationId() {
      return this.mAnimationId;
    }
    setAnimationId(id) {
      this.mAnimationId = id;
    }
    getParent() {
      return this.mParent;
    }
    setParent(parent) {
      this.mParent = parent;
    }
    getList() {
      return this.mChildren;
    }
    getX() {
      return this.mX;
    }
    setX(v) {
      this.mX = v;
    }
    getY() {
      return this.mY;
    }
    setY(v) {
      this.mY = v;
    }
    getWidth() {
      return this.mWidth;
    }
    setWidth(v) {
      this.mWidth = v;
    }
    getHeight() {
      return this.mHeight;
    }
    setHeight(v) {
      this.mHeight = v;
    }
    getZIndex() {
      return this.mZIndex;
    }
    getScrollX() {
      return 0;
    }
    getScrollY() {
      return 0;
    }
    needsMeasure() {
      return this.mNeedsMeasure;
    }
    invalidateMeasure() {
      this.mNeedsMeasure = true;
      if (this.mParent) this.mParent.invalidateMeasure();
    }
    clearNeedsMeasure() {
      this.mNeedsMeasure = false;
    }
    isVisible() {
      if (Visibility.isGone(this.mVisibility)) return false;
      if (this.mParent) return this.mParent.isVisible();
      return true;
    }
    isGone() {
      return Visibility.isGone(this.mVisibility);
    }
    getVisibility() {
      return this.mVisibility;
    }
    setVisibility(v) {
      if (v === this.mVisibility) return;
      this.mVisibility = v;
      this.invalidateMeasure();
      if (this.mParent) this.mParent.invalidateMeasure();
    }
    inflate() {
      for (const op of this.mChildren) {
        if (op instanceof _Component) {
          op.setParent(this);
        }
      }
    }
    // --- Intrinsic size (matches Java Component.minIntrinsicHeight/Width) ---
    minIntrinsicHeight() {
      let height = 0;
      for (const op of this.mChildren) {
        if (op instanceof _Component) {
          height = Math.max(height, op.minIntrinsicHeight());
        }
      }
      return height;
    }
    minIntrinsicWidth() {
      let width = 0;
      for (const op of this.mChildren) {
        if (op instanceof _Component) {
          width = Math.max(width, op.minIntrinsicWidth());
        }
      }
      return width;
    }
    // --- Measure/Layout ---
    measure(_context, _minWidth, _maxWidth, _minHeight, _maxHeight, measure) {
      const m = measure.get(this);
      m.setW(this.mWidth);
      m.setH(this.mHeight);
    }
    layout(context, measure) {
      const m = measure.get(this);
      this.mVisibility = m.getVisibility();
      if (m.isGone()) return;
      this.mX = m.getX();
      this.mY = m.getY();
      this.mWidth = m.getW();
      this.mHeight = m.getH();
      this.mFirstLayout = false;
    }
    animatingBounds(_context) {
    }
    // --- Paint ---
    paint(paintContext) {
      if (Visibility.isGone(this.mVisibility)) return;
      if (Visibility.isInvisible(this.mVisibility)) return;
      this.paintingComponent(paintContext);
    }
    paintingComponent(paintContext) {
      const context = paintContext.getContext();
      paintContext.matrixSave();
      paintContext.matrixTranslate(this.mX, this.mY);
      for (const op of this.mChildren) {
        context.incrementOpCount();
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.markNotDirty();
          op.updateVariables(context);
        }
        op.apply(context);
      }
      paintContext.matrixRestore();
    }
    apply(context) {
      for (const op of this.mChildren) {
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.markNotDirty();
          op.updateVariables(context);
        }
      }
      super.apply(context);
    }
    // --- Touch/Input ---
    onClick(context, doc, x, y) {
      for (let i = this.mChildren.length - 1; i >= 0; i--) {
        const child = this.mChildren[i];
        if (child instanceof _Component) {
          if (child.onClick(context, doc, x, y)) return true;
        }
      }
      return false;
    }
    onTouchDown(context, doc, x, y) {
      if (!this.contains(x, y)) return false;
      const loc = this.getLocationInWindow();
      const lx = x - loc[0];
      const ly = y - loc[1];
      let handled = false;
      let componentHandled = false;
      for (let i = this.mChildren.length - 1; i >= 0; i--) {
        const op = this.mChildren[i];
        if (op instanceof _Component) {
          if (!componentHandled && op.onTouchDown(context, doc, x, y)) {
            componentHandled = true;
          }
        } else if (op instanceof TouchExpression) {
          op.updateVariables(context);
          op.touchDown(context, lx, ly);
          doc.appliedTouchOperation(this);
          handled = true;
        }
      }
      return componentHandled || handled;
    }
    onTouchDrag(context, doc, x, y, force) {
      if (!force && !this.contains(x, y)) return false;
      const loc = this.getLocationInWindow();
      const lx = x - loc[0];
      const ly = y - loc[1];
      let handled = false;
      let componentHandled = false;
      for (let i = this.mChildren.length - 1; i >= 0; i--) {
        const op = this.mChildren[i];
        if (op instanceof _Component) {
          if (!componentHandled && op.onTouchDrag(context, doc, x, y, force)) {
            componentHandled = true;
          }
        } else if (op instanceof TouchExpression) {
          op.updateVariables(context);
          op.touchDrag(context, lx, ly);
          handled = true;
        }
      }
      return componentHandled || handled;
    }
    onTouchUp(context, doc, x, y, dx, dy, force) {
      if (!force && !this.contains(x, y)) return false;
      const loc = this.getLocationInWindow();
      const lx = x - loc[0];
      const ly = y - loc[1];
      let handled = false;
      let componentHandled = false;
      for (let i = this.mChildren.length - 1; i >= 0; i--) {
        const op = this.mChildren[i];
        if (op instanceof _Component) {
          if (!componentHandled && op.onTouchUp(context, doc, x, y, dx, dy, force)) {
            componentHandled = true;
          }
        } else if (op instanceof TouchExpression) {
          op.updateVariables(context);
          op.touchUp(context, lx, ly, dx, dy);
          handled = true;
        }
      }
      return componentHandled || handled;
    }
    onTouchCancel(context, doc, x, y, force) {
      if (!force && !this.contains(x, y)) return false;
      const loc = this.getLocationInWindow();
      const lx = x - loc[0];
      const ly = y - loc[1];
      let handled = false;
      let componentHandled = false;
      for (let i = this.mChildren.length - 1; i >= 0; i--) {
        const op = this.mChildren[i];
        if (op instanceof _Component) {
          if (!componentHandled && op.onTouchCancel(context, doc, x, y, force)) {
            componentHandled = true;
          }
        } else if (op instanceof TouchExpression) {
          op.updateVariables(context);
          op.touchUp(context, lx, ly, 0, 0);
          handled = true;
        }
      }
      return componentHandled || handled;
    }
    // --- Utility ---
    contains(x, y) {
      const loc = this.getLocationInWindow();
      return x >= loc[0] && x <= loc[0] + this.mWidth && y >= loc[1] && y <= loc[1] + this.mHeight;
    }
    getLocationInWindow() {
      let x = this.mX;
      let y = this.mY;
      let parent = this.mParent;
      while (parent) {
        x += parent.mX;
        y += parent.mY;
        parent = parent.getParent();
      }
      return [x, y];
    }
    getRoot() {
      let c = this;
      while (c.mParent) c = c.mParent;
      return c;
    }
    getComponent(id) {
      if (this.mComponentId === id) return this;
      for (const child of this.mChildren) {
        if (child instanceof _Component) {
          const found = child.getComponent(id);
          if (found) return found;
        }
      }
      return null;
    }
    addComponentValue(_v) {
    }
    registerVariables(_context) {
    }
    updateVariables(_context) {
    }
    selfOrModifier(cls) {
      for (const op of this.mChildren) {
        if (op instanceof cls) return op;
      }
      return null;
    }
    hasComputedLayout() {
      return false;
    }
    applyComputedLayout(_type, _context, _m, _parent) {
      return false;
    }
    write(_buffer) {
    }
    deepToString(indent) {
      return `${indent}Component(${this.mComponentId})`;
    }
  };

  // third_party/remote-compose-player/src/core/operations/layout/measure/ComponentMeasure.ts
  var _ComponentMeasure = class _ComponentMeasure {
    constructor(id, x, y, w, h, visibility = _ComponentMeasure.VISIBLE) {
      this.mAllowsAnimation = true;
      this.mId = id;
      this.mX = x;
      this.mY = y;
      this.mW = w;
      this.mH = h;
      this.mVisibility = visibility;
    }
    getX() {
      return this.mX;
    }
    setX(value) {
      this.mX = value;
    }
    getY() {
      return this.mY;
    }
    setY(value) {
      this.mY = value;
    }
    getW() {
      return this.mW;
    }
    setW(value) {
      this.mW = value;
    }
    getH() {
      return this.mH;
    }
    setH(value) {
      this.mH = value;
    }
    getVisibility() {
      return this.mVisibility;
    }
    setVisibility(v) {
      this.mVisibility = v;
    }
    getAllowsAnimation() {
      return this.mAllowsAnimation;
    }
    setAllowsAnimation(v) {
      this.mAllowsAnimation = v;
    }
    copyFrom(m) {
      this.mX = m.mX;
      this.mY = m.mY;
      this.mW = m.mW;
      this.mH = m.mH;
      this.mVisibility = m.mVisibility;
    }
    same(m) {
      return this.mX === m.mX && this.mY === m.mY && this.mW === m.mW && this.mH === m.mH && this.mVisibility === m.mVisibility;
    }
    isGone() {
      if (this.mVisibility >> 4 > 0) {
        return (this.mVisibility & 16) === 16;
      }
      return this.mVisibility === _ComponentMeasure.GONE;
    }
    isVisible() {
      if (this.mVisibility >> 4 > 0) {
        return (this.mVisibility & 32) === 32;
      }
      return this.mVisibility === _ComponentMeasure.VISIBLE;
    }
    isInvisible() {
      if (this.mVisibility >> 4 > 0) {
        return (this.mVisibility & 64) === 64;
      }
      return this.mVisibility === _ComponentMeasure.INVISIBLE;
    }
    clearVisibilityOverride() {
      this.mVisibility = this.mVisibility & 15;
    }
    addVisibilityOverride(value) {
      let v = this.mVisibility & 15;
      v += value;
      if ((v & 128) === 128) {
        v = v & 15;
      }
      this.mVisibility = v;
    }
  };
  // Visibility constants — matches Java Component.Visibility encoding
  _ComponentMeasure.GONE = 0;
  _ComponentMeasure.VISIBLE = 1;
  _ComponentMeasure.INVISIBLE = 2;
  var ComponentMeasure = _ComponentMeasure;

  // third_party/remote-compose-player/src/core/operations/layout/measure/MeasurePass.ts
  var MeasurePass = class {
    constructor() {
      this.mList = /* @__PURE__ */ new Map();
    }
    clear() {
      this.mList.clear();
    }
    add(measure) {
      if (measure.mId === -1) throw new Error("Component has no id!");
      this.mList.set(measure.mId, measure);
    }
    contains(id) {
      return this.mList.has(id);
    }
    get(arg) {
      if (typeof arg === "number") {
        let m2 = this.mList.get(arg);
        if (!m2) {
          m2 = new ComponentMeasure(arg, 0, 0, 0, 0, ComponentMeasure.GONE);
          this.mList.set(arg, m2);
        }
        return m2;
      }
      const c = arg;
      const id = c.getComponentId();
      let m = this.mList.get(id);
      if (!m) {
        m = new ComponentMeasure(
          id,
          c.getX(),
          c.getY(),
          c.getWidth(),
          c.getHeight(),
          c.getVisibility()
        );
        this.mList.set(id, m);
      }
      return m;
    }
  };

  // third_party/remote-compose-player/src/core/operations/layout/RootLayoutComponent.ts
  var _RootLayoutComponent = class _RootLayoutComponent extends Component {
    constructor(componentId = -1) {
      super(componentId);
      this.mCurrentId = -1;
      this.mHasTouchListeners = false;
    }
    getHasTouchListeners() {
      return this.mHasTouchListeners;
    }
    setHasTouchListeners(v) {
      this.mHasTouchListeners = v;
    }
    assignIds(lastId) {
      this.mCurrentId = lastId;
      this.assignId(this);
    }
    assignId(component) {
      if (component.getComponentId() === -1) {
        this.mCurrentId--;
        component.mComponentId = this.mCurrentId;
      }
      for (const op of component.getList()) {
        if (op instanceof Component) {
          this.assignId(op);
        }
      }
    }
    /** Measure then layout the tree of components */
    layoutTree(context) {
      if (!this.mNeedsMeasure) return;
      this.mNeedsMeasure = false;
      this.setWidth(context.mWidth);
      this.setHeight(context.mHeight);
      context.mViewportWidth = context.mWidth;
      context.mViewportHeight = context.mHeight;
      const measurePass = new MeasurePass();
      for (const op of this.getList()) {
        if (typeof op.measure === "function") {
          const paintContext = context.getPaintContext();
          if (paintContext) {
            op.measure(paintContext, 0, this.mWidth, 0, this.mHeight, measurePass);
            if (typeof op.layout === "function") {
              op.layout(context, measurePass);
            }
          }
        }
      }
    }
    /** Measure the document and layout components, returning first child size */
    measureDoc(context, minWidth, maxWidth, minHeight, maxHeight) {
      this.mNeedsMeasure = false;
      this.setWidth(context.mWidth);
      this.setHeight(context.mHeight);
      context.mViewportWidth = context.mWidth;
      context.mViewportHeight = context.mHeight;
      const measurePass = new MeasurePass();
      let firstComponent = null;
      for (const op of this.getList()) {
        if (typeof op.measure === "function") {
          if (firstComponent === null && op instanceof Component) {
            firstComponent = op;
          }
          const paintContext = context.getPaintContext();
          if (paintContext) {
            op.measure(paintContext, minWidth, maxWidth, minHeight, maxHeight, measurePass);
            if (typeof op.layout === "function") {
              op.layout(context, measurePass);
            }
          }
        }
      }
      if (firstComponent) {
        this.setWidth(firstComponent.getWidth());
        this.setHeight(firstComponent.getHeight());
      }
    }
    paint(paintContext) {
      this.mNeedsRepaint = false;
      const remoteContext = paintContext.getContext();
      paintContext.save();
      if (!this.getParent()) {
        paintContext.clipRect(0, 0, this.mWidth, this.mHeight);
      }
      for (const op of this.getList()) {
        if (op instanceof PaintOperation) {
          op.paint(paintContext);
          remoteContext.incrementOpCount();
        }
      }
      paintContext.restore();
    }
    getComponent(id) {
      const queue = [this];
      while (queue.length > 0) {
        const c = queue.shift();
        if (c.getComponentId() === id) return c;
        for (const child of c.getList()) {
          if (child instanceof Component) {
            queue.push(child);
          }
        }
      }
      return null;
    }
    displayHierarchy() {
      return `RootLayout(${this.getWidth()}x${this.getHeight()})`;
    }
    onClick(context, doc, x, y) {
      for (const child of this.getList()) {
        if (typeof child.onClick === "function") {
          if (child.onClick(context, doc, x, y)) return true;
        }
      }
      return false;
    }
    onTouchDown(context, doc, x, y) {
      for (const child of this.getList()) {
        if (typeof child.onTouchDown === "function") {
          if (child.onTouchDown(context, doc, x, y)) return true;
        }
      }
      return false;
    }
    write(buffer) {
      buffer.start(_RootLayoutComponent.OP_CODE);
      buffer.writeInt(this.getComponentId());
    }
    deepToString(indent) {
      return `${indent}RootLayoutComponent(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const component = new _RootLayoutComponent(componentId);
      operations.push(component);
    }
  };
  _RootLayoutComponent.OP_CODE = 200;
  var RootLayoutComponent = _RootLayoutComponent;

  // third_party/remote-compose-player/src/core/TimeVariables.ts
  var TimeVariables = class {
    constructor(clock) {
      this.mLastAnimationTime = -1;
      this.mClock = clock;
      this.mStartTime = performance.now() / 1e3;
    }
    getClock() {
      return this.mClock;
    }
    /** Seconds elapsed since this TimeVariables was created. */
    getElapsedSeconds() {
      return performance.now() / 1e3 - this.mStartTime;
    }
    updateTime(context) {
      const snapshot = this.mClock.snapshot();
      context.loadFloat(RemoteContext.ID_OFFSET_TO_UTC, snapshot.getOffsetSeconds());
      context.loadFloat(RemoteContext.ID_CONTINUOUS_SEC, snapshot.getContinuousSeconds());
      context.loadInteger(RemoteContext.ID_EPOCH_SECOND, snapshot.getEpochSeconds());
      context.loadFloat(RemoteContext.ID_TIME_IN_SEC, snapshot.getTimeInSec());
      context.loadFloat(RemoteContext.ID_TIME_IN_MIN, snapshot.getTimeInMin());
      context.loadFloat(RemoteContext.ID_TIME_IN_HR, snapshot.getHour());
      context.loadFloat(RemoteContext.ID_CALENDAR_MONTH, snapshot.getMonth());
      context.loadFloat(RemoteContext.ID_DAY_OF_MONTH, snapshot.getDayOfMonth());
      context.loadFloat(RemoteContext.ID_WEEK_DAY, snapshot.getDayOfWeek());
      context.loadFloat(RemoteContext.ID_DAY_OF_YEAR, snapshot.getDayOfYear());
      context.loadFloat(RemoteContext.ID_YEAR, snapshot.getYear());
      const animTime = this.getElapsedSeconds();
      const deltaTime = this.mLastAnimationTime >= 0 ? animTime - this.mLastAnimationTime : 0;
      this.mLastAnimationTime = animTime;
      context.loadFloat(RemoteContext.ID_ANIMATION_TIME, animTime);
      context.setAnimationTime(animTime);
      context.loadFloat(RemoteContext.ID_ANIMATION_DELTA_TIME, deltaTime);
      context.loadFloat(RemoteContext.ID_API_LEVEL, CoreDocument.DOCUMENT_API_LEVEL);
    }
  };

  // third_party/remote-compose-player/src/core/operations/paint/PaintBundle.ts
  var _f32dv = new DataView(new ArrayBuffer(4));
  function intBitsToFloat2(bits) {
    _f32dv.setInt32(0, bits);
    return _f32dv.getFloat32(0);
  }
  function floatToRawIntBits3(v) {
    _f32dv.setFloat32(0, v);
    return _f32dv.getInt32(0);
  }
  function isNaNBitsLocal(bits) {
    return (bits & 2139095040) === 2139095040 && (bits & 8388607) !== 0;
  }
  function idFromBitsLocal(bits) {
    return bits & 4194303;
  }
  function fixFloatVar(val, context) {
    if (isNaNBitsLocal(val)) {
      return floatToRawIntBits3(context.getFloat(idFromBitsLocal(val)));
    }
    return val;
  }
  function fixColor(val, context) {
    return context.getColor(val);
  }
  function listenFloatVar(val, context, op) {
    if (isNaNBitsLocal(val)) {
      context.listensTo(idFromBitsLocal(val), op);
    }
  }
  var _PaintBundle = class _PaintBundle {
    constructor() {
      // The flat int array and current position
      this.mArray = [];
      this.mOutArray = null;
      this.mPos = 0;
    }
    read(buffer) {
      const len = buffer.readInt();
      if (len <= 0 || len > 1024) {
        throw new Error(`PaintBundle: invalid length ${len}`);
      }
      this.mArray = new Array(len);
      for (let i = 0; i < len; i++) {
        this.mArray[i] = buffer.readInt();
      }
      this.mPos = len;
    }
    /** Returns the resolved array (mOutArray if available, otherwise mArray). */
    getArray() {
      return this.mOutArray ?? this.mArray;
    }
    getLength() {
      return this.mPos;
    }
    // --- Programmatic setters for building paint bundles at runtime ---
    reset() {
      this.mArray = [];
      this.mOutArray = null;
      this.mPos = 0;
    }
    setStyle(style) {
      this.mArray[this.mPos++] = _PaintBundle.STYLE | style << 16;
    }
    setColor(aOrColor, r, g, b) {
      if (r !== void 0 && g !== void 0 && b !== void 0) {
        const ai = Math.round(aOrColor * 255) & 255;
        const ri = Math.round(r * 255) & 255;
        const gi = Math.round(g * 255) & 255;
        const bi = Math.round(b * 255) & 255;
        aOrColor = (ai << 24 | ri << 16 | gi << 8 | bi) >>> 0;
      }
      this.mArray[this.mPos++] = _PaintBundle.COLOR;
      this.mArray[this.mPos++] = aOrColor;
    }
    setTextSize(size) {
      this.mArray[this.mPos++] = _PaintBundle.TEXT_SIZE;
      _f32dv.setFloat32(0, size);
      this.mArray[this.mPos++] = _f32dv.getInt32(0);
    }
    setTextStyle(typeface, fontWeight, italic) {
      const style = fontWeight & 1023 | (italic ? 2048 : 0);
      this.mArray[this.mPos++] = _PaintBundle.TYPEFACE | style << 16;
      this.mArray[this.mPos++] = typeface;
    }
    setTextAxis(axisTags, axisValues) {
      const count = axisTags.length;
      this.mArray[this.mPos++] = _PaintBundle.FONT_AXIS | count << 16;
      for (let i = 0; i < count; i++) {
        this.mArray[this.mPos++] = axisTags[i];
        _f32dv.setFloat32(0, axisValues[i]);
        this.mArray[this.mPos++] = _f32dv.getInt32(0);
      }
    }
    setStrokeWidth(width) {
      this.mArray[this.mPos++] = _PaintBundle.STROKE_WIDTH;
      _f32dv.setFloat32(0, width);
      this.mArray[this.mPos++] = _f32dv.getInt32(0);
    }
    registerListening(context, op) {
      let i = 0;
      const arr = this.mArray;
      while (i < this.mPos) {
        const cmd = arr[i++];
        const tag = cmd & 65535;
        switch (tag) {
          case _PaintBundle.STROKE_MITER:
          case _PaintBundle.STROKE_WIDTH:
          case _PaintBundle.ALPHA:
          case _PaintBundle.TEXT_SIZE:
            listenFloatVar(arr[i], context, op);
            i++;
            break;
          case _PaintBundle.COLOR_FILTER_ID:
          case _PaintBundle.COLOR_ID:
            context.listensTo(arr[i], op);
            i++;
            break;
          case _PaintBundle.COLOR:
          case _PaintBundle.TYPEFACE:
          case _PaintBundle.SHADER:
          case _PaintBundle.COLOR_FILTER:
          case _PaintBundle.FALLBACK_TYPEFACE:
            i++;
            break;
          case _PaintBundle.STROKE_JOIN:
          case _PaintBundle.FILTER_BITMAP:
          case _PaintBundle.STROKE_CAP:
          case _PaintBundle.STYLE:
          case _PaintBundle.IMAGE_FILTER_QUALITY:
          case _PaintBundle.BLEND_MODE:
          case _PaintBundle.ANTI_ALIAS:
          case _PaintBundle.CLEAR_COLOR_FILTER:
            break;
          case _PaintBundle.FONT_AXIS: {
            const count = cmd >> 16 & 65535;
            for (let j = 0; j < count; j++) {
              i++;
              listenFloatVar(arr[i], context, op);
              i++;
            }
            break;
          }
          case _PaintBundle.TEXTURE:
            i += 3;
            break;
          case _PaintBundle.SHADER_MATRIX:
            i++;
            break;
          case _PaintBundle.GRADIENT:
            i = this.registerGradientListening(cmd, arr, i, context, op);
            break;
          case _PaintBundle.PATH_EFFECT: {
            const count = cmd >> 16 & 65535;
            for (let j = 0; j < count; j++) {
              listenFloatVar(arr[i], context, op);
              i++;
            }
            listenFloatVar(arr[i], context, op);
            i++;
            break;
          }
          default:
            break;
        }
      }
    }
    registerGradientListening(cmd, arr, startIdx, context, op) {
      let i = startIdx;
      const meta = arr[i++];
      const numColors = meta & 255;
      const register = meta >> 16 & 65535;
      for (let j = 0; j < numColors; j++) {
        if ((register & 1 << j) !== 0) {
          context.listensTo(arr[i], op);
        }
        i++;
      }
      const numStops = arr[i++];
      for (let j = 0; j < numStops; j++) {
        listenFloatVar(arr[i], context, op);
        i++;
      }
      const gradType = cmd >> 16 & 65535;
      switch (gradType) {
        case _PaintBundle.LINEAR_GRADIENT:
          for (let j = 0; j < 4; j++) {
            listenFloatVar(arr[i], context, op);
            i++;
          }
          i++;
          break;
        case _PaintBundle.RADIAL_GRADIENT:
          for (let j = 0; j < 3; j++) {
            listenFloatVar(arr[i], context, op);
            i++;
          }
          i++;
          break;
        case _PaintBundle.SWEEP_GRADIENT:
          for (let j = 0; j < 2; j++) {
            listenFloatVar(arr[i], context, op);
            i++;
          }
          break;
      }
      return i;
    }
    /**
     * Resolve NaN-encoded float variable IDs and color IDs in paint parameters.
     * Matches Java PaintBundle.updateVariables().
     */
    updateVariables(context) {
      if (this.mOutArray === null) {
        this.mOutArray = this.mArray.slice();
      } else {
        for (let j = 0; j < this.mArray.length; j++) {
          this.mOutArray[j] = this.mArray[j];
        }
      }
      let i = 0;
      const arr = this.mArray;
      const out = this.mOutArray;
      while (i < this.mPos) {
        const cmd = arr[i++];
        const tag = cmd & 65535;
        switch (tag) {
          case _PaintBundle.STROKE_MITER:
          case _PaintBundle.STROKE_WIDTH:
          case _PaintBundle.ALPHA:
          case _PaintBundle.TEXT_SIZE:
            out[i] = fixFloatVar(arr[i], context);
            i++;
            break;
          case _PaintBundle.COLOR_FILTER_ID:
          case _PaintBundle.COLOR_ID:
            out[i] = fixColor(arr[i], context);
            i++;
            break;
          case _PaintBundle.COLOR:
          case _PaintBundle.TYPEFACE:
          case _PaintBundle.SHADER:
          case _PaintBundle.COLOR_FILTER:
          case _PaintBundle.FALLBACK_TYPEFACE:
            i++;
            break;
          case _PaintBundle.STROKE_JOIN:
          case _PaintBundle.FILTER_BITMAP:
          case _PaintBundle.STROKE_CAP:
          case _PaintBundle.STYLE:
          case _PaintBundle.IMAGE_FILTER_QUALITY:
          case _PaintBundle.BLEND_MODE:
          case _PaintBundle.ANTI_ALIAS:
          case _PaintBundle.CLEAR_COLOR_FILTER:
            break;
          case _PaintBundle.FONT_AXIS: {
            const count = cmd >> 16 & 65535;
            for (let j = 0; j < count; j++) {
              i++;
              out[i] = fixFloatVar(arr[i], context);
              i++;
            }
            break;
          }
          case _PaintBundle.TEXTURE:
            i += 3;
            break;
          case _PaintBundle.SHADER_MATRIX:
            i++;
            break;
          case _PaintBundle.GRADIENT:
            i = this.updateGradientVars(cmd, arr, out, i, context);
            break;
          case _PaintBundle.PATH_EFFECT: {
            const count = cmd >> 16 & 65535;
            for (let j = 0; j < count; j++) {
              out[i] = fixFloatVar(arr[i], context);
              i++;
            }
            out[i] = fixFloatVar(arr[i], context);
            i++;
            break;
          }
          default:
            break;
        }
      }
    }
    updateGradientVars(cmd, arr, out, startIdx, context) {
      let i = startIdx;
      const gradType = cmd >> 16 & 65535;
      const meta = arr[i++];
      const numColors = meta & 255;
      const register = meta >> 16 & 65535;
      for (let j = 0; j < numColors; j++) {
        if ((register & 1 << j) !== 0) {
          out[i] = fixColor(arr[i], context);
        }
        i++;
      }
      const numStops = arr[i++];
      for (let j = 0; j < numStops; j++) {
        out[i] = fixFloatVar(arr[i], context);
        i++;
      }
      switch (gradType) {
        case _PaintBundle.LINEAR_GRADIENT:
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          i++;
          break;
        case _PaintBundle.RADIAL_GRADIENT:
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          i++;
          break;
        case _PaintBundle.SWEEP_GRADIENT:
          out[i] = fixFloatVar(arr[i], context);
          i++;
          out[i] = fixFloatVar(arr[i], context);
          i++;
          break;
      }
      return i;
    }
  };
  // Paint property tags (matching Java PaintBundle constants)
  _PaintBundle.TEXT_SIZE = 1;
  _PaintBundle.COLOR = 4;
  _PaintBundle.STROKE_WIDTH = 5;
  _PaintBundle.STROKE_MITER = 6;
  _PaintBundle.STROKE_CAP = 7;
  _PaintBundle.STYLE = 8;
  _PaintBundle.SHADER = 9;
  _PaintBundle.IMAGE_FILTER_QUALITY = 10;
  _PaintBundle.GRADIENT = 11;
  _PaintBundle.ALPHA = 12;
  _PaintBundle.COLOR_FILTER = 13;
  _PaintBundle.ANTI_ALIAS = 14;
  _PaintBundle.STROKE_JOIN = 15;
  _PaintBundle.TYPEFACE = 16;
  _PaintBundle.FILTER_BITMAP = 17;
  _PaintBundle.BLEND_MODE = 18;
  _PaintBundle.COLOR_ID = 19;
  _PaintBundle.COLOR_FILTER_ID = 20;
  _PaintBundle.CLEAR_COLOR_FILTER = 21;
  _PaintBundle.SHADER_MATRIX = 22;
  _PaintBundle.FONT_AXIS = 23;
  _PaintBundle.TEXTURE = 24;
  _PaintBundle.PATH_EFFECT = 25;
  _PaintBundle.FALLBACK_TYPEFACE = 26;
  // Gradient types (wire format values)
  _PaintBundle.LINEAR_GRADIENT = 0;
  _PaintBundle.RADIAL_GRADIENT = 1;
  _PaintBundle.SWEEP_GRADIENT = 2;
  // Style constants
  _PaintBundle.FILL = 0;
  _PaintBundle.STROKE = 1;
  _PaintBundle.FILL_AND_STROKE = 2;
  var PaintBundle = _PaintBundle;

  // third_party/remote-compose-player/src/core/operations/layout/modifiers/ModifierOperations.ts
  var _WidthModifier = class _WidthModifier extends Operation {
    constructor(type, valueBits) {
      super();
      this.mType = type;
      this.mValueBits = valueBits;
      this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getType() {
      return this.mType;
    }
    getValue() {
      return this.mOutValue;
    }
    /** True when a fill fraction was supplied. A bare fill encodes NaN, which
     *  getValue() reports as 0 — so the raw bits are the only way to tell
     *  "fill half" from "fill completely". */
    hasFraction() {
      return !isNaNBits(this.mValueBits);
    }
    registerListening(context) {
      if ((this.mType === _WidthModifier.EXACT || this.mType === _WidthModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
        context.listensTo(idFromBits(this.mValueBits), this);
      }
    }
    updateVariables(context) {
      if ((this.mType === _WidthModifier.EXACT || this.mType === _WidthModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
        this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}WidthModifier(${this.mType}, ${this.mOutValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _WidthModifier(buffer.readInt(), buffer.readInt()));
    }
  };
  _WidthModifier.OP_CODE = 16;
  _WidthModifier.EXACT = 0;
  _WidthModifier.FILL = 1;
  _WidthModifier.WRAP = 2;
  _WidthModifier.WEIGHT = 3;
  _WidthModifier.INTRINSIC_MIN = 4;
  _WidthModifier.INTRINSIC_MAX = 5;
  _WidthModifier.EXACT_DP = 6;
  _WidthModifier.FILL_PARENT_MAX_WIDTH = 7;
  _WidthModifier.FILL_PARENT_MAX_HEIGHT = 8;
  var WidthModifier = _WidthModifier;
  var _HeightModifier = class _HeightModifier extends Operation {
    constructor(type, valueBits) {
      super();
      this.mType = type;
      this.mValueBits = valueBits;
      this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getType() {
      return this.mType;
    }
    getValue() {
      return this.mOutValue;
    }
    /** True when a fill fraction was supplied. A bare fill encodes NaN, which
     *  getValue() reports as 0 — so the raw bits are the only way to tell
     *  "fill half" from "fill completely". */
    hasFraction() {
      return !isNaNBits(this.mValueBits);
    }
    registerListening(context) {
      if ((this.mType === _HeightModifier.EXACT || this.mType === _HeightModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
        context.listensTo(idFromBits(this.mValueBits), this);
      }
    }
    updateVariables(context) {
      if ((this.mType === _HeightModifier.EXACT || this.mType === _HeightModifier.EXACT_DP) && isNaNBits(this.mValueBits)) {
        this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}HeightModifier(${this.mType}, ${this.mOutValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _HeightModifier(buffer.readInt(), buffer.readInt()));
    }
  };
  _HeightModifier.OP_CODE = 67;
  _HeightModifier.EXACT = 0;
  _HeightModifier.FILL = 1;
  _HeightModifier.WRAP = 2;
  _HeightModifier.WEIGHT = 3;
  _HeightModifier.INTRINSIC_MIN = 4;
  _HeightModifier.INTRINSIC_MAX = 5;
  _HeightModifier.EXACT_DP = 6;
  _HeightModifier.FILL_PARENT_MAX_WIDTH = 7;
  _HeightModifier.FILL_PARENT_MAX_HEIGHT = 8;
  var HeightModifier = _HeightModifier;
  var _WidthInModifier = class _WidthInModifier extends Operation {
    constructor(minBits, maxBits) {
      super();
      this.mMinBits = minBits;
      this.mMaxBits = maxBits;
      this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
      this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
    }
    getMin() {
      return this.mOutMin;
    }
    getMax() {
      return this.mOutMax;
    }
    registerListening(context) {
      if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
      if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
    }
    updateVariables(context) {
      if (isNaNBits(this.mMinBits)) this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
      if (isNaNBits(this.mMaxBits)) this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}WidthInModifier(${this.mOutMin}, ${this.mOutMax})`;
    }
    static read(buffer, operations) {
      operations.push(new _WidthInModifier(buffer.readInt(), buffer.readInt()));
    }
  };
  _WidthInModifier.OP_CODE = 231;
  var WidthInModifier = _WidthInModifier;
  var _HeightInModifier = class _HeightInModifier extends Operation {
    constructor(minBits, maxBits) {
      super();
      this.mMinBits = minBits;
      this.mMaxBits = maxBits;
      this.mOutMin = isNaNBits(minBits) ? 0 : intBitsToFloat(minBits);
      this.mOutMax = isNaNBits(maxBits) ? 0 : intBitsToFloat(maxBits);
    }
    getMin() {
      return this.mOutMin;
    }
    getMax() {
      return this.mOutMax;
    }
    registerListening(context) {
      if (isNaNBits(this.mMinBits)) context.listensTo(idFromBits(this.mMinBits), this);
      if (isNaNBits(this.mMaxBits)) context.listensTo(idFromBits(this.mMaxBits), this);
    }
    updateVariables(context) {
      if (isNaNBits(this.mMinBits)) this.mOutMin = context.getFloat(idFromBits(this.mMinBits));
      if (isNaNBits(this.mMaxBits)) this.mOutMax = context.getFloat(idFromBits(this.mMaxBits));
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}HeightInModifier(${this.mOutMin}, ${this.mOutMax})`;
    }
    static read(buffer, operations) {
      operations.push(new _HeightInModifier(buffer.readInt(), buffer.readInt()));
    }
  };
  _HeightInModifier.OP_CODE = 232;
  var HeightInModifier = _HeightInModifier;
  var _CollapsiblePriorityModifier = class _CollapsiblePriorityModifier extends Operation {
    constructor(orientation, priority) {
      super();
      this.mOrientation = orientation;
      this.mPriority = priority;
    }
    getOrientation() {
      return this.mOrientation;
    }
    getPriority() {
      return this.mPriority;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}CollapsiblePriorityModifier`;
    }
    static read(buffer, operations) {
      operations.push(new _CollapsiblePriorityModifier(buffer.readInt(), buffer.readFloat()));
    }
  };
  _CollapsiblePriorityModifier.OP_CODE = 235;
  var CollapsiblePriorityModifier = _CollapsiblePriorityModifier;
  var _BackgroundModifier = class _BackgroundModifier extends Operation {
    constructor(flags, colorId, r, g, b, a, shapeType) {
      super();
      this.mComponent = null;
      this.mLayoutW = 0;
      this.mLayoutH = 0;
      this.mFlags = flags;
      this.mColorId = colorId;
      this.mR = r;
      this.mG = g;
      this.mB = b;
      this.mA = a;
      this.mOutR = isNaNBits(r) ? 0 : intBitsToFloat(r);
      this.mOutG = isNaNBits(g) ? 0 : intBitsToFloat(g);
      this.mOutB = isNaNBits(b) ? 0 : intBitsToFloat(b);
      this.mOutA = isNaNBits(a) ? 0 : intBitsToFloat(a);
      this.mShapeType = shapeType;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    layoutDecorator(w, h) {
      this.mLayoutW = w;
      this.mLayoutH = h;
    }
    registerListening(context) {
      if (this.mFlags === _BackgroundModifier.COLOR_REF) {
        context.listensTo(this.mColorId, this);
      } else {
        if (isNaNBits(this.mR)) context.listensTo(idFromBits(this.mR), this);
        if (isNaNBits(this.mG)) context.listensTo(idFromBits(this.mG), this);
        if (isNaNBits(this.mB)) context.listensTo(idFromBits(this.mB), this);
        if (isNaNBits(this.mA)) context.listensTo(idFromBits(this.mA), this);
      }
    }
    updateVariables(context) {
      if (this.mFlags !== _BackgroundModifier.COLOR_REF) {
        if (isNaNBits(this.mR)) this.mOutR = context.getFloat(idFromBits(this.mR));
        if (isNaNBits(this.mG)) this.mOutG = context.getFloat(idFromBits(this.mG));
        if (isNaNBits(this.mB)) this.mOutB = context.getFloat(idFromBits(this.mB));
        if (isNaNBits(this.mA)) this.mOutA = context.getFloat(idFromBits(this.mA));
      }
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      const w = this.mLayoutW;
      const h = this.mLayoutH;
      if (w <= 0 || h <= 0) return;
      pc.savePaint();
      let argb;
      if (this.mFlags === _BackgroundModifier.COLOR_REF) {
        argb = context.mRemoteComposeState.getColor(this.mColorId);
      } else {
        const a = Math.trunc(this.mOutA * 255 + 0.5);
        const r = Math.trunc(this.mOutR * 255 + 0.5);
        const g = Math.trunc(this.mOutG * 255 + 0.5);
        const b = Math.trunc(this.mOutB * 255 + 0.5);
        argb = a << 24 | r << 16 | g << 8 | b | 0;
      }
      const pb = new PaintBundle();
      pb.mArray = [PaintBundle.STYLE, PaintBundle.COLOR, argb];
      pb.mPos = 3;
      pc.replacePaint(pb);
      if (this.mShapeType === 1) {
        pc.drawCircle(w / 2, h / 2, Math.min(w, h) / 2);
      } else {
        pc.drawRect(0, 0, w, h);
      }
      pc.restorePaint();
    }
    deepToString(indent) {
      return `${indent}BackgroundModifier`;
    }
    static read(buffer, operations) {
      const flags = buffer.readInt();
      const colorId = buffer.readId();
      buffer.readInt();
      buffer.readInt();
      const r = buffer.readInt();
      const g = buffer.readInt();
      const b = buffer.readInt();
      const a = buffer.readInt();
      const shapeType = buffer.readInt();
      operations.push(new _BackgroundModifier(flags, colorId, r, g, b, a, shapeType));
    }
  };
  _BackgroundModifier.OP_CODE = 55;
  _BackgroundModifier.COLOR_REF = 2;
  var BackgroundModifier = _BackgroundModifier;
  var _BorderModifier = class _BorderModifier extends Operation {
    constructor(flags, colorId, borderWidth, roundedCorner, r, g, b, a, shapeType) {
      super();
      this.mComponent = null;
      this.mLayoutW = 0;
      this.mLayoutH = 0;
      this.mFlags = flags;
      this.mColorId = colorId;
      this.mBorderWidth = borderWidth;
      this.mRoundedCorner = roundedCorner;
      this.mR = r;
      this.mG = g;
      this.mB = b;
      this.mA = a;
      this.mShapeType = shapeType;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    layoutDecorator(w, h) {
      this.mLayoutW = w;
      this.mLayoutH = h;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      const w = this.mLayoutW;
      const h = this.mLayoutH;
      if (w <= 0 || h <= 0) return;
      pc.savePaint();
      const pb = new PaintBundle();
      let argb;
      if (this.mFlags === _BorderModifier.COLOR_REF) {
        argb = context.mRemoteComposeState.getColor(this.mColorId);
      } else {
        const a = Math.trunc(this.mA * 255 + 0.5);
        const r = Math.trunc(this.mR * 255 + 0.5);
        const g = Math.trunc(this.mG * 255 + 0.5);
        const b = Math.trunc(this.mB * 255 + 0.5);
        argb = a << 24 | r << 16 | g << 8 | b | 0;
      }
      let borderWidth = this.mBorderWidth;
      let roundedCorner = this.mRoundedCorner;
      if (context.getDensityBehavior() === DENSITY_BEHAVIOR_DP) {
        const d = context.getDensity();
        if (!Number.isNaN(d) && d > 0) {
          borderWidth *= d;
          roundedCorner *= d;
        }
      }
      pb.reset();
      pb.setStyle(PaintBundle.STROKE);
      pb.setColor(argb);
      pb.setStrokeWidth(borderWidth);
      pc.replacePaint(pb);
      if (roundedCorner > 0) {
        pc.drawRoundRect(0, 0, w, h, roundedCorner, roundedCorner);
      } else {
        pc.drawRect(0, 0, w, h);
      }
      pc.restorePaint();
    }
    deepToString(indent) {
      return `${indent}BorderModifier`;
    }
    static read(buffer, operations) {
      const flags = buffer.readInt();
      const colorId = buffer.readId();
      buffer.readInt();
      buffer.readInt();
      const borderWidth = buffer.readNanId();
      const roundedCorner = buffer.readNanId();
      const r = buffer.readNanId();
      const g = buffer.readNanId();
      const b = buffer.readNanId();
      const a = buffer.readNanId();
      const shapeType = buffer.readInt();
      operations.push(new _BorderModifier(flags, colorId, borderWidth, roundedCorner, r, g, b, a, shapeType));
    }
  };
  _BorderModifier.OP_CODE = 107;
  _BorderModifier.COLOR_REF = 2;
  var BorderModifier = _BorderModifier;
  var _PaddingModifier = class _PaddingModifier extends Operation {
    constructor(l, t, r, b) {
      super();
      this.mLeft = l;
      this.mTop = t;
      this.mRight = r;
      this.mBottom = b;
      this.mLeftValue = isNaNBits(l) ? 0 : intBitsToFloat(l);
      this.mTopValue = isNaNBits(t) ? 0 : intBitsToFloat(t);
      this.mRightValue = isNaNBits(r) ? 0 : intBitsToFloat(r);
      this.mBottomValue = isNaNBits(b) ? 0 : intBitsToFloat(b);
    }
    registerListening(context) {
      if (isNaNBits(this.mLeft)) context.listensTo(idFromBits(this.mLeft), this);
      if (isNaNBits(this.mTop)) context.listensTo(idFromBits(this.mTop), this);
      if (isNaNBits(this.mRight)) context.listensTo(idFromBits(this.mRight), this);
      if (isNaNBits(this.mBottom)) context.listensTo(idFromBits(this.mBottom), this);
    }
    updateVariables(context) {
      this.mLeftValue = isNaNBits(this.mLeft) ? context.getFloat(idFromBits(this.mLeft)) : intBitsToFloat(this.mLeft);
      this.mTopValue = isNaNBits(this.mTop) ? context.getFloat(idFromBits(this.mTop)) : intBitsToFloat(this.mTop);
      this.mRightValue = isNaNBits(this.mRight) ? context.getFloat(idFromBits(this.mRight)) : intBitsToFloat(this.mRight);
      this.mBottomValue = isNaNBits(this.mBottom) ? context.getFloat(idFromBits(this.mBottom)) : intBitsToFloat(this.mBottom);
      if (context.getDensityBehavior() === DENSITY_BEHAVIOR_DP) {
        const d = context.getDensity();
        if (!Number.isNaN(d) && d > 0) {
          this.mLeftValue *= d;
          this.mTopValue *= d;
          this.mRightValue *= d;
          this.mBottomValue *= d;
        }
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}PaddingModifier(${this.mLeftValue}, ${this.mTopValue}, ${this.mRightValue}, ${this.mBottomValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _PaddingModifier(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _PaddingModifier.OP_CODE = 58;
  var PaddingModifier = _PaddingModifier;
  var _RoundedClipRectModifier = class _RoundedClipRectModifier extends Operation {
    constructor(topStart, topEnd, bottomStart, bottomEnd) {
      super();
      this.mLayoutW = 0;
      this.mLayoutH = 0;
      this.mComponent = null;
      this.mTopStart = topStart;
      this.mTopEnd = topEnd;
      this.mBottomStart = bottomStart;
      this.mBottomEnd = bottomEnd;
      this.mTopStartValue = isNaNBits(topStart) ? 0 : intBitsToFloat(topStart);
      this.mTopEndValue = isNaNBits(topEnd) ? 0 : intBitsToFloat(topEnd);
      this.mBottomStartValue = isNaNBits(bottomStart) ? 0 : intBitsToFloat(bottomStart);
      this.mBottomEndValue = isNaNBits(bottomEnd) ? 0 : intBitsToFloat(bottomEnd);
    }
    registerListening(context) {
      if (isNaNBits(this.mTopStart)) context.listensTo(idFromBits(this.mTopStart), this);
      if (isNaNBits(this.mTopEnd)) context.listensTo(idFromBits(this.mTopEnd), this);
      if (isNaNBits(this.mBottomStart)) context.listensTo(idFromBits(this.mBottomStart), this);
      if (isNaNBits(this.mBottomEnd)) context.listensTo(idFromBits(this.mBottomEnd), this);
    }
    updateVariables(context) {
      const ts = this.resolve(context, this.mTopStart);
      const te = this.resolve(context, this.mTopEnd);
      const bs = this.resolve(context, this.mBottomStart);
      const be = this.resolve(context, this.mBottomEnd);
      this.mTopStartValue = ts;
      this.mTopEndValue = te;
      this.mBottomStartValue = bs;
      this.mBottomEndValue = be;
    }
    /**
     * A literal corner is authored in dp, so under DP density behavior AndroidX's
     * `RoundedClipRectModifierOperation.paint` scales it by the doc density — replicate
     * that so the clipped corners match the baked render at densities != 1. A *variable*
     * corner is deliberately left alone: it is computed from the component's measured
     * width/height, which the engine already carries in generation pixels, so scaling it
     * would double-apply the density and over-round the shape.
     */
    resolve(context, bits) {
      if (isNaNBits(bits)) return context.getFloat(idFromBits(bits));
      let v = intBitsToFloat(bits);
      if (context.getDensityBehavior() === DENSITY_BEHAVIOR_DP) {
        const d = context.getDensity();
        if (!Number.isNaN(d) && d > 0) v *= d;
      }
      return v;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    layoutDecorator(w, h) {
      this.mLayoutW = w;
      this.mLayoutH = h;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      const w = this.mLayoutW;
      const h = this.mLayoutH;
      if (w > 0 && h > 0) {
        this.updateVariables(context);
        pc.roundedClipRect(
          w,
          h,
          this.mTopStartValue,
          this.mTopEndValue,
          this.mBottomStartValue,
          this.mBottomEndValue
        );
      }
    }
    deepToString(indent) {
      return `${indent}RoundedClipRectModifier(${this.mTopStartValue}, ${this.mTopEndValue}, ${this.mBottomStartValue}, ${this.mBottomEndValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _RoundedClipRectModifier(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _RoundedClipRectModifier.OP_CODE = 54;
  var RoundedClipRectModifier = _RoundedClipRectModifier;
  var _ClipRectModifier = class _ClipRectModifier extends Operation {
    constructor() {
      super();
      this.mComponent = null;
      this.mLayoutW = 0;
      this.mLayoutH = 0;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    layoutDecorator(w, h) {
      this.mLayoutW = w;
      this.mLayoutH = h;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      const w = this.mLayoutW;
      const h = this.mLayoutH;
      if (w > 0 && h > 0) {
        pc.clipRect(0, 0, w, h);
      }
    }
    deepToString(indent) {
      return `${indent}ClipRectModifier`;
    }
    static read(_buffer, operations) {
      operations.push(new _ClipRectModifier());
    }
  };
  _ClipRectModifier.OP_CODE = 108;
  var ClipRectModifier = _ClipRectModifier;
  var _ClickModifier = class _ClickModifier extends Operation {
    constructor() {
      super();
      this.mList = [];
      this.mComponent = null;
    }
    getList() {
      return this.mList;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    onClick(context, _doc, _x, _y) {
      for (const op of this.mList) {
        op.apply(context);
      }
      context.needsRepaint();
      return true;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}ClickModifier(${this.mList.length} actions)`;
    }
    static read(_buffer, operations) {
      operations.push(new _ClickModifier());
    }
  };
  _ClickModifier.OP_CODE = 59;
  var ClickModifier = _ClickModifier;
  var _MultiClickModifier = class _MultiClickModifier extends Operation {
    constructor(clickType = 0) {
      super();
      this.mClickType = 0;
      this.mList = [];
      this.mComponent = null;
      this.mClickType = clickType;
    }
    getList() {
      return this.mList;
    }
    getClickType() {
      return this.mClickType;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    onClick(context, _doc, _x, _y) {
      if (this.mClickType !== _MultiClickModifier.CLICK_TYPE_SINGLE) return false;
      for (const op of this.mList) op.apply(context);
      context.needsRepaint();
      return true;
    }
    onLongPress(context, _doc, _x, _y) {
      if (this.mClickType !== _MultiClickModifier.CLICK_TYPE_LONG) return false;
      for (const op of this.mList) op.apply(context);
      context.needsRepaint();
      return true;
    }
    onDoubleClick(context, _doc, _x, _y) {
      if (this.mClickType !== _MultiClickModifier.CLICK_TYPE_DOUBLE) return false;
      for (const op of this.mList) op.apply(context);
      context.needsRepaint();
      return true;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}MultiClickModifier(type=${this.mClickType}, ${this.mList.length} actions)`;
    }
    static read(buffer, operations) {
      const clickType = buffer.readInt();
      operations.push(new _MultiClickModifier(clickType));
    }
  };
  _MultiClickModifier.OP_CODE = 83;
  _MultiClickModifier.CLICK_TYPE_SINGLE = 0;
  _MultiClickModifier.CLICK_TYPE_LONG = 1;
  _MultiClickModifier.CLICK_TYPE_DOUBLE = 2;
  var MultiClickModifier = _MultiClickModifier;
  var _DimensionConstraintsModifier = class _DimensionConstraintsModifier extends Operation {
    constructor(type, min, max) {
      super();
      this.mType = type;
      this.mMin = min;
      this.mMax = max;
    }
    getType() {
      return this.mType;
    }
    getMin() {
      return this.mMin;
    }
    getMax() {
      return this.mMax;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}DimensionConstraintsModifier(type=${this.mType}, min=${this.mMin}, max=${this.mMax})`;
    }
    static read(buffer, operations) {
      const type = buffer.readByte();
      const min = buffer.readFloat();
      const max = buffer.readFloat();
      operations.push(new _DimensionConstraintsModifier(type, min, max));
    }
  };
  _DimensionConstraintsModifier.OP_CODE = 243;
  _DimensionConstraintsModifier.HORIZONTAL = 0;
  _DimensionConstraintsModifier.VERTICAL = 1;
  _DimensionConstraintsModifier.REQUIRED_HORIZONTAL = 2;
  _DimensionConstraintsModifier.REQUIRED_VERTICAL = 3;
  var DimensionConstraintsModifier = _DimensionConstraintsModifier;
  var _TouchDownModifier = class _TouchDownModifier extends Operation {
    constructor() {
      super();
      this.mList = [];
    }
    getList() {
      return this.mList;
    }
    onTouchDown(context) {
      for (const op of this.mList) {
        op.apply(context);
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}TouchDownModifier(${this.mList.length} actions)`;
    }
    static read(_buffer, operations) {
      operations.push(new _TouchDownModifier());
    }
  };
  _TouchDownModifier.OP_CODE = 219;
  var TouchDownModifier = _TouchDownModifier;
  var _TouchUpModifier = class _TouchUpModifier extends Operation {
    constructor() {
      super();
      this.mList = [];
    }
    getList() {
      return this.mList;
    }
    onTouchUp(context) {
      for (const op of this.mList) {
        op.apply(context);
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}TouchUpModifier(${this.mList.length} actions)`;
    }
    static read(_buffer, operations) {
      operations.push(new _TouchUpModifier());
    }
  };
  _TouchUpModifier.OP_CODE = 220;
  var TouchUpModifier = _TouchUpModifier;
  var _TouchCancelModifier = class _TouchCancelModifier extends Operation {
    constructor() {
      super();
      this.mList = [];
    }
    getList() {
      return this.mList;
    }
    onTouchCancel(context) {
      for (const op of this.mList) {
        op.apply(context);
      }
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}TouchCancelModifier(${this.mList.length} actions)`;
    }
    static read(_buffer, operations) {
      operations.push(new _TouchCancelModifier());
    }
  };
  _TouchCancelModifier.OP_CODE = 225;
  var TouchCancelModifier = _TouchCancelModifier;
  var _VisibilityModifier = class _VisibilityModifier extends Operation {
    constructor(visibilityId) {
      super();
      this.mVisibility = Visibility.VISIBLE;
      this.mParent = null;
      this.mVisibilityId = visibilityId;
    }
    setParent(parent) {
      this.mParent = parent;
    }
    registerListening(context) {
      context.listensTo(this.mVisibilityId, this);
    }
    updateVariables(context) {
      const v = context.getInteger(this.mVisibilityId);
      if (Visibility.isVisible(v)) {
        this.mVisibility = Visibility.VISIBLE;
      } else if (Visibility.isGone(v)) {
        this.mVisibility = Visibility.GONE;
      } else if (Visibility.isInvisible(v)) {
        this.mVisibility = Visibility.INVISIBLE;
      } else {
        this.mVisibility = Visibility.GONE;
      }
      if (this.mParent && typeof this.mParent.setVisibility === "function") {
        this.mParent.setVisibility(this.mVisibility);
      }
      this.markNotDirty();
    }
    write(_buffer) {
    }
    apply(context) {
      if (this.isDirty()) {
        this.updateVariables(context);
      }
    }
    /**
     * Re-resolve the visibility every layout pass, matching
     * ComponentVisibilityOperation.evaluateInLayout in the reference.
     *
     * `apply` only runs when the op is dirty, which never happens for a visibility id
     * that nothing writes — so the component stayed VISIBLE while the reference
     * resolved the same id to GONE. A document using visibility to switch between
     * mutually exclusive branches therefore drew *both* of them.
     */
    evaluateInLayout(context) {
      const before = this.mVisibility;
      this.updateVariables(context);
      return before !== this.mVisibility;
    }
    deepToString(indent) {
      return `${indent}VisibilityModifier(${this.mVisibilityId})`;
    }
    static read(buffer, operations) {
      operations.push(new _VisibilityModifier(buffer.readInt()));
    }
  };
  _VisibilityModifier.OP_CODE = 211;
  var VisibilityModifier = _VisibilityModifier;
  var _OffsetModifier = class _OffsetModifier extends Operation {
    constructor(x, y) {
      super();
      this.mOffX = x;
      this.mOffY = y;
      this.mOutX = isNaNBits(x) ? 0 : intBitsToFloat(x);
      this.mOutY = isNaNBits(y) ? 0 : intBitsToFloat(y);
    }
    registerListening(context) {
      if (isNaNBits(this.mOffX)) context.listensTo(idFromBits(this.mOffX), this);
      if (isNaNBits(this.mOffY)) context.listensTo(idFromBits(this.mOffY), this);
    }
    updateVariables(context) {
      if (isNaNBits(this.mOffX)) this.mOutX = context.getFloat(idFromBits(this.mOffX));
      if (isNaNBits(this.mOffY)) this.mOutY = context.getFloat(idFromBits(this.mOffY));
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      let ox = this.mOutX, oy = this.mOutY;
      if (context.getDensityBehavior() === DENSITY_BEHAVIOR_DP) {
        const d = context.getDensity();
        if (!Number.isNaN(d) && d > 0) {
          ox *= d;
          oy *= d;
        }
      }
      pc.translate(ox, oy);
    }
    deepToString(indent) {
      return `${indent}OffsetModifier(${this.mOutX}, ${this.mOutY})`;
    }
    static read(buffer, operations) {
      operations.push(new _OffsetModifier(buffer.readInt(), buffer.readInt()));
    }
  };
  _OffsetModifier.OP_CODE = 221;
  var OffsetModifier = _OffsetModifier;
  var _ZIndexModifier = class _ZIndexModifier extends Operation {
    constructor(valueBits) {
      super();
      this.mValueBits = valueBits;
      this.mCurrentValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    getValue() {
      return this.mCurrentValue;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode === "PAINT" /* PAINT */ && isVariableBits(this.mValueBits)) {
        this.mCurrentValue = context.getFloat(idFromBits(this.mValueBits));
      }
    }
    deepToString(indent) {
      return `${indent}ZIndexModifier(${this.mCurrentValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _ZIndexModifier(buffer.readInt()));
    }
  };
  _ZIndexModifier.OP_CODE = 223;
  var ZIndexModifier = _ZIndexModifier;
  var _GraphicsLayerModifier = class _GraphicsLayerModifier extends Operation {
    constructor(attributes) {
      super();
      this.mAttributes = /* @__PURE__ */ new Map();
      this.mAttributes = attributes;
    }
    getAlpha() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.ALPHA);
      return attr ? attr.value : 1;
    }
    getScaleX() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.SCALE_X);
      return attr ? attr.value : 1;
    }
    getScaleY() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.SCALE_Y);
      return attr ? attr.value : 1;
    }
    getRotationZ() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.ROTATION_Z);
      return attr ? attr.value : 0;
    }
    getTranslationX() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.TRANSLATION_X);
      return attr ? attr.value : 0;
    }
    getTranslationY() {
      const attr = this.mAttributes.get(_GraphicsLayerModifier.TRANSLATION_Y);
      return attr ? attr.value : 0;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      for (const [_key, attr] of this.mAttributes) {
        if (attr.isFloat && Number.isNaN(attr.value)) {
        }
      }
    }
    deepToString(indent) {
      return `${indent}GraphicsLayerModifier(${this.mAttributes.size} attrs)`;
    }
    static read(buffer, operations) {
      const length = buffer.readInt();
      const attributes = /* @__PURE__ */ new Map();
      for (let i = 0; i < length; i++) {
        const tag = buffer.readInt();
        const attrId = tag & 1023;
        const dataType = tag >> 10 & 3;
        if (dataType === 1) {
          attributes.set(attrId, { value: buffer.readFloat(), isFloat: true });
        } else {
          attributes.set(attrId, { value: buffer.readInt(), isFloat: false });
        }
      }
      operations.push(new _GraphicsLayerModifier(attributes));
    }
  };
  _GraphicsLayerModifier.OP_CODE = 224;
  // Attribute tag IDs (lower 10 bits of the tag word)
  _GraphicsLayerModifier.SCALE_X = 0;
  _GraphicsLayerModifier.SCALE_Y = 1;
  _GraphicsLayerModifier.ROTATION_X = 2;
  _GraphicsLayerModifier.ROTATION_Y = 3;
  _GraphicsLayerModifier.ROTATION_Z = 4;
  _GraphicsLayerModifier.TRANSLATION_X = 5;
  _GraphicsLayerModifier.TRANSLATION_Y = 6;
  _GraphicsLayerModifier.TRANSLATION_Z = 7;
  _GraphicsLayerModifier.ALPHA = 8;
  _GraphicsLayerModifier.SHADOW_ELEVATION = 9;
  _GraphicsLayerModifier.CAMERA_DISTANCE = 10;
  _GraphicsLayerModifier.BLUR_RADIUS_X = 15;
  _GraphicsLayerModifier.BLUR_RADIUS_Y = 16;
  var GraphicsLayerModifier = _GraphicsLayerModifier;
  var _ScrollModifier = class _ScrollModifier extends Operation {
    constructor(direction, positionId, max, notchMax) {
      super();
      this.mList = [];
      this.mDirection = direction;
      this.mPositionId = positionId;
      this.mMax = max;
      this.mNotchMax = notchMax;
    }
    getList() {
      return this.mList;
    }
    getDirection() {
      return this.mDirection;
    }
    getMaxNan() {
      return this.mMax;
    }
    getNotchMaxNan() {
      return this.mNotchMax;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc) return;
      const pos = isNaNBits(this.mPositionId) ? context.getFloat(idFromBits(this.mPositionId)) : 0;
      if (this.mDirection === _ScrollModifier.HORIZONTAL) {
        pc.translate(-pos, 0);
      } else {
        pc.translate(0, -pos);
      }
    }
    deepToString(indent) {
      return `${indent}ScrollModifier(dir=${this.mDirection})`;
    }
    static read(buffer, operations) {
      const direction = buffer.readInt();
      const position = buffer.readInt();
      const max = buffer.readInt();
      const notchMax = buffer.readInt();
      operations.push(new _ScrollModifier(direction, position, max, notchMax));
    }
  };
  _ScrollModifier.OP_CODE = 226;
  _ScrollModifier.VERTICAL = 0;
  _ScrollModifier.HORIZONTAL = 1;
  var ScrollModifier = _ScrollModifier;
  var _MarqueeModifier = class _MarqueeModifier extends Operation {
    constructor(iterations, animationMode, repeatDelay, initialDelay, spacing, velocity) {
      super();
      this.mComponent = null;
      this.mScrollX = 0;
      this.mStartTime = 0;
      this.mLastTime = 0;
      this.mComponentWidth = 0;
      this.mContentWidth = 0;
      this.mIterations = iterations;
      this.mAnimationMode = animationMode;
      this.mRepeatDelayMillis = repeatDelay;
      this.mInitialDelayMillis = initialDelay;
      this.mSpacing = spacing;
      this.mVelocity = velocity;
    }
    setComponent(c) {
      this.mComponent = c;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const pc = context.getPaintContext();
      if (!pc || !this.mComponent) return;
      this.mComponentWidth = this.mComponent.getWidth?.() ?? 0;
      this.mContentWidth = this.mComponent.getContentWidth?.() ?? this.mComponentWidth;
      const delta = this.mContentWidth - this.mComponentWidth;
      if (delta <= 0) return;
      const now = context.getAnimationTime();
      if (this.mStartTime === 0) {
        this.mStartTime = now;
      }
      const elapsed = now - this.mStartTime - this.mInitialDelayMillis / 1e3;
      if (elapsed < 0) {
        context.needsRepaint();
        return;
      }
      const duration = delta / (this.mVelocity > 0 ? this.mVelocity : 30);
      const totalCycle = duration + this.mRepeatDelayMillis / 1e3;
      const cyclePos = elapsed % totalCycle;
      if (cyclePos < duration) {
        const t = cyclePos / duration;
        const offset = (1 + Math.sin(t * 2 * Math.PI - Math.PI / 2)) / 2 * -delta;
        this.mScrollX = offset;
      } else {
        this.mScrollX = 0;
      }
      pc.translate(this.mScrollX, 0);
      context.needsRepaint();
    }
    deepToString(indent) {
      return `${indent}MarqueeModifier`;
    }
    static read(buffer, operations) {
      const iterations = buffer.readInt();
      const animationMode = buffer.readInt();
      const repeatDelay = buffer.readFloat();
      const initialDelay = buffer.readFloat();
      const spacing = buffer.readFloat();
      const velocity = buffer.readFloat();
      operations.push(new _MarqueeModifier(iterations, animationMode, repeatDelay, initialDelay, spacing, velocity));
    }
  };
  _MarqueeModifier.OP_CODE = 228;
  var MarqueeModifier = _MarqueeModifier;
  var _RippleModifier = class _RippleModifier extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}RippleModifier`;
    }
    static read(_buffer, operations) {
      operations.push(new _RippleModifier());
    }
  };
  _RippleModifier.OP_CODE = 229;
  var RippleModifier = _RippleModifier;
  var _DrawContentModifier = class _DrawContentModifier extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}DrawContentModifier`;
    }
    static read(_buffer, operations) {
      operations.push(new _DrawContentModifier());
    }
  };
  _DrawContentModifier.OP_CODE = 174;
  var DrawContentModifier = _DrawContentModifier;
  var _AlignByModifier = class _AlignByModifier extends Operation {
    constructor(line, flags) {
      super();
      this.mLine = line;
      this.mFlags = flags;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}AlignByModifier`;
    }
    static read(buffer, operations) {
      operations.push(new _AlignByModifier(buffer.readFloat(), buffer.readInt()));
    }
  };
  _AlignByModifier.OP_CODE = 237;
  var AlignByModifier = _AlignByModifier;
  var _AccessibilitySemantics = class _AccessibilitySemantics extends Operation {
    constructor(contentDescriptionId, role, textId, stateDescriptionId, mode, enabled, clickable) {
      super();
      this.mContentDescriptionId = contentDescriptionId;
      this.mRole = role;
      this.mTextId = textId;
      this.mStateDescriptionId = stateDescriptionId;
      this.mMode = mode;
      this.mEnabled = enabled;
      this.mClickable = clickable;
    }
    getContentDescriptionId() {
      return this.mContentDescriptionId;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}AccessibilitySemantics(contentDescriptionId=${this.mContentDescriptionId}, role=${this.mRole}, enabled=${this.mEnabled}, clickable=${this.mClickable})`;
    }
    static read(buffer, operations) {
      const contentDescriptionId = buffer.declareId ? buffer.declareId() : buffer.readInt();
      const role = buffer.readByte();
      const textId = buffer.declareId ? buffer.declareId() : buffer.readInt();
      const stateDescriptionId = buffer.declareId ? buffer.declareId() : buffer.readInt();
      const mode = buffer.readByte();
      const enabled = buffer.readBoolean();
      const clickable = buffer.readBoolean();
      operations.push(new _AccessibilitySemantics(
        contentDescriptionId,
        role,
        textId,
        stateDescriptionId,
        mode,
        enabled,
        clickable
      ));
    }
  };
  _AccessibilitySemantics.OP_CODE = 250;
  var AccessibilitySemantics = _AccessibilitySemantics;

  // third_party/remote-compose-player/src/core/operations/DataOperations.ts
  var NANMAP_PATH_BASE = 3145728;
  function isPathMarkerBits(b) {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id >= 10 && id <= 17 || id >= NANMAP_PATH_BASE && id <= NANMAP_PATH_BASE + 6;
  }
  var _TextData = class _TextData extends Operation {
    constructor(textId, text) {
      super();
      this.mTextId = textId;
      this.mText = text;
    }
    update(other) {
      this.mText = other.mText;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadText(this.mTextId, this.mText);
    }
    deepToString(indent) {
      return `${indent}TextData(${this.mTextId}, "${this.mText}")`;
    }
    static read(buffer, operations) {
      const id = buffer.declareId();
      const text = buffer.readUTF8();
      operations.push(new _TextData(id, text));
    }
  };
  _TextData.OP_CODE = 102;
  var TextData = _TextData;
  var _BitmapData = class _BitmapData extends Operation {
    constructor(imageId, encoding, type, width, height, bitmap) {
      super();
      this.mImageId = imageId;
      this.mEncoding = encoding;
      this.mType = type;
      this.mWidth = width;
      this.mHeight = height;
      this.mBitmap = bitmap;
    }
    getWidth() {
      return this.mWidth;
    }
    getHeight() {
      return this.mHeight;
    }
    update(other) {
      this.mEncoding = other.mEncoding;
      this.mType = other.mType;
      this.mWidth = other.mWidth;
      this.mHeight = other.mHeight;
      this.mBitmap = other.mBitmap;
    }
    write(_buffer) {
    }
    apply(context) {
      context.putObject(this.mImageId, this);
      context.loadBitmap(this.mImageId, this.mEncoding, this.mType, this.mWidth, this.mHeight, this.mBitmap);
    }
    deepToString(indent) {
      return `${indent}BitmapData(${this.mImageId}, ${this.mWidth}x${this.mHeight})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      let rawWidth = buffer.readInt();
      let rawHeight = buffer.readInt();
      let type = 0;
      let encoding = 0;
      if (rawWidth > 65535) {
        type = rawWidth >> 16;
        rawWidth = rawWidth & 65535;
      }
      if (rawHeight > 65535) {
        encoding = rawHeight >> 16;
        rawHeight = rawHeight & 65535;
      }
      const bitmap = buffer.readBuffer();
      operations.push(new _BitmapData(id, encoding, type, rawWidth, rawHeight, bitmap));
    }
  };
  _BitmapData.OP_CODE = 101;
  var BitmapData = _BitmapData;
  var _PaintData = class _PaintData extends Operation {
    constructor(paintBundle) {
      super();
      this.mPaintBundle = paintBundle;
    }
    write(_buffer) {
    }
    registerListening(context) {
      this.mPaintBundle.registerListening(context, this);
    }
    updateVariables(context) {
      this.mPaintBundle.updateVariables(context);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.applyPaint(this.mPaintBundle);
    }
    deepToString(indent) {
      return `${indent}PaintData`;
    }
    static read(buffer, operations) {
      const bundle = new PaintBundle();
      bundle.read(buffer);
      operations.push(new _PaintData(bundle));
    }
  };
  _PaintData.OP_CODE = 40;
  var PaintData = _PaintData;
  var _PathData = class _PathData extends Operation {
    constructor(pathId, winding, pathBits) {
      super();
      this.mPathChanged = false;
      this.mPathId = pathId;
      this.mWinding = winding;
      this.mPathBits = pathBits;
      this.mOutputPath = Int32Array.from(pathBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      for (const b of this.mPathBits) {
        if (isNaNBits(b) && !isPathMarkerBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(context) {
      for (let i = 0; i < this.mPathBits.length; i++) {
        const b = this.mPathBits[i];
        if (isPathMarkerBits(b)) {
          this.mOutputPath[i] = b;
        } else if (isNaNBits(b)) {
          const prev = this.mOutputPath[i];
          this.mOutputPath[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
          if (prev !== this.mOutputPath[i]) this.mPathChanged = true;
        } else {
          this.mOutputPath[i] = b;
        }
      }
    }
    apply(context) {
      context.loadPathData(this.mPathId, this.mWinding, this.mOutputPath);
    }
    deepToString(indent) {
      return `${indent}PathData(${this.mPathId})`;
    }
    static read(buffer, operations) {
      const rawId = buffer.readInt();
      const winding = rawId >> 24;
      const id = rawId & 16777215;
      const count = buffer.readInt();
      if (count < 0 || count > 2e4) {
        throw new Error(`PathData: invalid path length ${count}`);
      }
      const data = new Int32Array(count);
      for (let i = 0; i < count; i++) {
        data[i] = buffer.readInt();
      }
      operations.push(new _PathData(id, winding, data));
    }
  };
  _PathData.OP_CODE = 123;
  var PathData = _PathData;
  var _FloatConstant = class _FloatConstant extends Operation {
    constructor(id, valueBits) {
      super();
      this.mId = id;
      this.mValue = isNaNBits(valueBits) && idFromBits(valueBits) === _FloatConstant.RAND_ID ? Math.random() : intBitsToFloat(valueBits);
    }
    update(other) {
      this.mValue = other.mValue;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadFloat(this.mId, this.mValue);
    }
    deepToString(indent) {
      return `${indent}FloatConstant(${this.mId}, ${this.mValue})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const value = buffer.readInt();
      operations.push(new _FloatConstant(id, value));
    }
  };
  _FloatConstant.OP_CODE = 80;
  // RAND operator ID: OFFSET(0x310000) + 39 = 0x310027
  _FloatConstant.RAND_ID = 3211303;
  var FloatConstant = _FloatConstant;
  var _ColorConstant = class _ColorConstant extends Operation {
    constructor(id, color) {
      super();
      this.mId = id;
      this.mColor = color;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadColor(this.mId, this.mColor);
    }
    deepToString(indent) {
      return `${indent}ColorConstant(${this.mId})`;
    }
    static read(buffer, operations) {
      operations.push(new _ColorConstant(buffer.readInt(), buffer.readInt()));
    }
  };
  _ColorConstant.OP_CODE = 138;
  var ColorConstant = _ColorConstant;
  var _Theme = class _Theme extends Operation {
    constructor(theme) {
      super();
      this.mTheme = theme;
    }
    write(_buffer) {
    }
    apply(context) {
      context.setTheme(this.mTheme);
    }
    deepToString(indent) {
      return `${indent}Theme(${this.mTheme})`;
    }
    static read(buffer, operations) {
      operations.push(new _Theme(buffer.readInt()));
    }
  };
  _Theme.OP_CODE = 63;
  _Theme.UNSPECIFIED = -1;
  _Theme.DARK = -2;
  _Theme.LIGHT = -3;
  _Theme.SYSTEM = 0;
  var Theme = _Theme;
  var _ClickArea = class _ClickArea extends Operation {
    constructor(id, cdId, left, top, right, bottom, metaId) {
      super();
      this.mId = id;
      this.mContentDescriptionId = cdId;
      this.mLeft = left;
      this.mTop = top;
      this.mRight = right;
      this.mBottom = bottom;
      this.mOutLeft = isNaNBits(left) ? 0 : intBitsToFloat(left);
      this.mOutTop = isNaNBits(top) ? 0 : intBitsToFloat(top);
      this.mOutRight = isNaNBits(right) ? 0 : intBitsToFloat(right);
      this.mOutBottom = isNaNBits(bottom) ? 0 : intBitsToFloat(bottom);
      this.mMetadataId = metaId;
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mLeft)) context.listensTo(idFromBits(this.mLeft), this);
      if (isNaNBits(this.mTop)) context.listensTo(idFromBits(this.mTop), this);
      if (isNaNBits(this.mRight)) context.listensTo(idFromBits(this.mRight), this);
      if (isNaNBits(this.mBottom)) context.listensTo(idFromBits(this.mBottom), this);
    }
    updateVariables(context) {
      this.mOutLeft = isNaNBits(this.mLeft) ? context.getFloat(idFromBits(this.mLeft)) : intBitsToFloat(this.mLeft);
      this.mOutTop = isNaNBits(this.mTop) ? context.getFloat(idFromBits(this.mTop)) : intBitsToFloat(this.mTop);
      this.mOutRight = isNaNBits(this.mRight) ? context.getFloat(idFromBits(this.mRight)) : intBitsToFloat(this.mRight);
      this.mOutBottom = isNaNBits(this.mBottom) ? context.getFloat(idFromBits(this.mBottom)) : intBitsToFloat(this.mBottom);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.addClickArea(
        this.mId,
        this.mContentDescriptionId,
        this.mOutLeft,
        this.mOutTop,
        this.mOutRight,
        this.mOutBottom,
        this.mMetadataId
      );
    }
    deepToString(indent) {
      return `${indent}ClickArea(${this.mId})`;
    }
    static read(buffer, operations) {
      operations.push(new _ClickArea(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _ClickArea.OP_CODE = 64;
  var ClickArea = _ClickArea;
  var _NamedVariable = class _NamedVariable extends Operation {
    constructor(varName, varId, varType) {
      super();
      this.mVarName = varName;
      this.mVarId = varId;
      this.mVarType = varType;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadVariableName(this.mVarName, this.mVarId, this.mVarType);
    }
    deepToString(indent) {
      return `${indent}NamedVariable("${this.mVarName}", ${this.mVarId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const type = buffer.readInt();
      const name = buffer.readUTF8();
      operations.push(new _NamedVariable(name, id, type));
    }
  };
  _NamedVariable.OP_CODE = 137;
  _NamedVariable.STRING_TYPE = 0;
  _NamedVariable.FLOAT_TYPE = 1;
  _NamedVariable.COLOR_TYPE = 2;
  _NamedVariable.IMAGE_TYPE = 3;
  _NamedVariable.INT_TYPE = 4;
  _NamedVariable.LONG_TYPE = 5;
  _NamedVariable.FLOAT_ARRAY_TYPE = 6;
  var NamedVariable = _NamedVariable;
  var _RootContentDescription = class _RootContentDescription extends Operation {
    constructor(contentDescription) {
      super();
      this.mContentDescription = contentDescription;
    }
    write(_buffer) {
    }
    apply(context) {
      context.setDocumentContentDescription(this.mContentDescription);
    }
    deepToString(indent) {
      return `${indent}RootContentDescription(${this.mContentDescription})`;
    }
    static read(buffer, operations) {
      operations.push(new _RootContentDescription(buffer.readInt()));
    }
  };
  _RootContentDescription.OP_CODE = 103;
  var RootContentDescription = _RootContentDescription;
  var _RootContentBehavior = class _RootContentBehavior extends Operation {
    constructor(scroll, alignment, sizing, mode) {
      super();
      this.mScroll = scroll;
      this.mAlignment = alignment;
      this.mSizing = sizing;
      this.mMode = mode;
    }
    write(_buffer) {
    }
    apply(context) {
      context.setRootContentBehavior(this.mScroll, this.mAlignment, this.mSizing, this.mMode);
    }
    deepToString(indent) {
      return `${indent}RootContentBehavior`;
    }
    static read(buffer, operations) {
      operations.push(new _RootContentBehavior(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _RootContentBehavior.OP_CODE = 65;
  _RootContentBehavior.NONE = 0;
  _RootContentBehavior.SIZING_LAYOUT = 1;
  _RootContentBehavior.SIZING_SCALE = 2;
  _RootContentBehavior.ALIGNMENT_TOP = 1;
  _RootContentBehavior.ALIGNMENT_VERTICAL_CENTER = 2;
  _RootContentBehavior.ALIGNMENT_BOTTOM = 4;
  _RootContentBehavior.ALIGNMENT_START = 16;
  _RootContentBehavior.ALIGNMENT_HORIZONTAL_CENTER = 32;
  _RootContentBehavior.ALIGNMENT_END = 64;
  _RootContentBehavior.ALIGNMENT_CENTER = 34;
  // H_CENTER + V_CENTER
  _RootContentBehavior.SCALE_INSIDE = 1;
  _RootContentBehavior.SCALE_FIT = 2;
  _RootContentBehavior.SCALE_FILL_WIDTH = 3;
  _RootContentBehavior.SCALE_FILL_HEIGHT = 4;
  _RootContentBehavior.SCALE_CROP = 5;
  _RootContentBehavior.SCALE_FILL_BOUNDS = 6;
  _RootContentBehavior.LAYOUT_MATCH_PARENT = 0;
  _RootContentBehavior.LAYOUT_WRAP_CONTENT = 1;
  var RootContentBehavior = _RootContentBehavior;

  // third_party/remote-compose-player/src/core/operations/StubOperations.ts
  var _ImpulseOperation = class _ImpulseOperation extends PaintOperation {
    constructor(duration, startAt) {
      super();
      this.mList = [];
      this.mProcess = null;
      this.mInitialPass = true;
      this.mDuration = duration;
      this.mStartAt = startAt;
      this.mOutDuration = duration;
      this.mOutStartAt = startAt;
    }
    getList() {
      return this.mList;
    }
    updateVariables(context) {
      this.mOutDuration = Number.isNaN(this.mDuration) ? context.getFloat(idFromBits(floatToRawIntBits(this.mDuration))) : this.mDuration;
      this.mOutStartAt = Number.isNaN(this.mStartAt) ? context.getFloat(idFromBits(floatToRawIntBits(this.mStartAt))) : this.mStartAt;
    }
    /** The trailing ImpulseProcess is the repeating part; it is not run as setup. */
    takeProcess() {
      if (this.mProcess || !this.mList.length) return;
      const last = this.mList[this.mList.length - 1];
      if (last instanceof ImpulseProcess) {
        this.mProcess = last;
        this.mList.pop();
      }
    }
    write(_buffer) {
    }
    paint(context) {
      this.takeProcess();
      const remote = context.getContext();
      const now = remote.getAnimationTime();
      if (now < this.mOutStartAt) {
        context.wakeIn(this.mOutStartAt - now);
        return;
      }
      if (now <= this.mOutStartAt + this.mOutDuration) {
        if (this.mInitialPass) {
          for (const op of this.mList) {
            if (op.isDirty() && typeof op.updateVariables === "function") {
              op.updateVariables(remote);
            }
            remote.incrementOpCount();
            op.apply(remote);
          }
          this.mInitialPass = false;
        } else {
          remote.incrementOpCount();
          if (this.mProcess) this.mProcess.paint(context);
        }
      } else {
        this.mInitialPass = true;
      }
    }
    deepToString(indent) {
      return `${indent}ImpulseOperation(${this.mList.length} setup ops)`;
    }
    static read(buffer, operations) {
      const duration = buffer.readFloat();
      const startAt = buffer.readFloat();
      operations.push(new _ImpulseOperation(duration, startAt));
    }
  };
  _ImpulseOperation.OP_CODE = 164;
  var ImpulseOperation = _ImpulseOperation;
  var _ImpulseProcess = class _ImpulseProcess extends PaintOperation {
    constructor() {
      super();
      this.mList = [];
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    paint(context) {
      const remote = context.getContext();
      for (const op of this.mList) {
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.updateVariables(remote);
        }
        remote.incrementOpCount();
        op.apply(remote);
      }
    }
    deepToString(indent) {
      return `${indent}ImpulseProcess(${this.mList.length})`;
    }
    static read(_buffer, operations) {
      operations.push(new _ImpulseProcess());
    }
  };
  _ImpulseProcess.OP_CODE = 165;
  var ImpulseProcess = _ImpulseProcess;
  var _DebugMessage = class _DebugMessage extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}DebugMessage`;
    }
    static read(buffer, operations) {
      buffer.readInt();
      buffer.readFloat();
      buffer.readInt();
      operations.push(new _DebugMessage());
    }
  };
  _DebugMessage.OP_CODE = 179;
  var DebugMessage = _DebugMessage;
  var _HostActionMetadataOperation = class _HostActionMetadataOperation extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}HostActionMetadataOperation`;
    }
    static read(buffer, operations) {
      buffer.readInt();
      buffer.readInt();
      operations.push(new _HostActionMetadataOperation());
    }
  };
  _HostActionMetadataOperation.OP_CODE = 216;
  var HostActionMetadataOperation = _HostActionMetadataOperation;
  var _RunActionOperation = class _RunActionOperation extends PaintOperation {
    constructor() {
      super();
      this.mList = [];
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    // `apply` comes from PaintOperation: it routes to paint() in PAINT mode, and
    // outside it walks children without running them, which is what we want — the
    // actions must fire per frame, not once at load.
    paint(context) {
      const remote = context.getContext();
      for (const op of this.mList) {
        op.apply(remote);
      }
    }
    deepToString(indent) {
      return `${indent}RunActionOperation(${this.mList.length} actions)`;
    }
    static read(_buffer, operations) {
      operations.push(new _RunActionOperation());
    }
  };
  _RunActionOperation.OP_CODE = 236;
  var RunActionOperation = _RunActionOperation;
  var _ValueFloatExpressionChangeAction = class _ValueFloatExpressionChangeAction extends Operation {
    constructor(targetValueId, valueExpressionId) {
      super();
      this.mTargetValueId = targetValueId;
      this.mValueExpressionId = valueExpressionId;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const document2 = context.getDocument();
      if (!document2) return;
      document2.evaluateFloatExpression(this.mValueExpressionId, this.mTargetValueId, context);
    }
    deepToString(indent) {
      return `${indent}ValueFloatExpressionChangeAction(${this.mTargetValueId} <- ${this.mValueExpressionId})`;
    }
    static read(buffer, operations) {
      const valueId = buffer.readInt();
      const expressionId = buffer.readInt();
      operations.push(new _ValueFloatExpressionChangeAction(valueId, expressionId));
    }
  };
  _ValueFloatExpressionChangeAction.OP_CODE = 227;
  var ValueFloatExpressionChangeAction = _ValueFloatExpressionChangeAction;
  var _PathTween = class _PathTween extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}PathTween`;
    }
    static read(buffer, operations) {
      buffer.readInt();
      buffer.readInt();
      buffer.readInt();
      buffer.readFloat();
      operations.push(new _PathTween());
    }
  };
  _PathTween.OP_CODE = 158;
  var PathTween = _PathTween;
  var _HapticFeedback = class _HapticFeedback extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}HapticFeedback`;
    }
    static read(buffer, operations) {
      buffer.readInt();
      operations.push(new _HapticFeedback());
    }
  };
  _HapticFeedback.OP_CODE = 177;
  var HapticFeedback = _HapticFeedback;
  var _WakeIn = class _WakeIn extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}WakeIn`;
    }
    static read(buffer, operations) {
      buffer.readFloat();
      operations.push(new _WakeIn());
    }
  };
  _WakeIn.OP_CODE = 191;
  var WakeIn = _WakeIn;
  var _TimeAttribute = class _TimeAttribute extends PaintOperation {
    constructor(id, timeId, type, args) {
      super();
      this.mId = id;
      this.mTimeId = timeId;
      this.mType = type;
      this.mArgs = args;
    }
    write(_buffer) {
    }
    paint(context) {
      const val = this.mType & 255;
      const ctx = context.getContext();
      const loadTime = ctx.getDocLoadTime();
      const longConstant = ctx.getObject(this.mTimeId);
      const now = context.getClock().snapshot();
      const value = longConstant && typeof longConstant.getValue === "function" ? createSnapshot(longConstant.getValue()) : now;
      let delta = 0;
      switch (val) {
        case _TimeAttribute.TIME_FROM_NOW_SEC:
        case _TimeAttribute.TIME_FROM_NOW_MIN:
        case _TimeAttribute.TIME_FROM_NOW_HR:
          delta = value.getMillis() - now.getMillis();
          break;
        case _TimeAttribute.TIME_FROM_ARG_SEC:
        case _TimeAttribute.TIME_FROM_ARG_MIN:
        case _TimeAttribute.TIME_FROM_ARG_HR: {
          const lc2 = ctx.getObject(this.mArgs[0]);
          delta = value.getMillis() - (lc2 && typeof lc2.getValue === "function" ? lc2.getValue() : 0);
          break;
        }
        default:
          break;
      }
      switch (val) {
        case _TimeAttribute.TIME_FROM_NOW_SEC:
        case _TimeAttribute.TIME_FROM_ARG_SEC:
          ctx.loadFloat(this.mId, delta * 1e-3);
          ctx.needsRepaint?.();
          break;
        case _TimeAttribute.TIME_FROM_NOW_MIN:
        case _TimeAttribute.TIME_FROM_ARG_MIN:
          ctx.loadFloat(this.mId, delta * 1e-3 / 60);
          ctx.needsRepaint?.();
          break;
        case _TimeAttribute.TIME_FROM_NOW_HR:
        case _TimeAttribute.TIME_FROM_ARG_HR:
          ctx.loadFloat(this.mId, delta * 1e-3 / 3600);
          break;
        case _TimeAttribute.TIME_IN_SEC:
          ctx.loadFloat(this.mId, value.getSecond());
          break;
        case _TimeAttribute.TIME_IN_MIN:
          ctx.loadFloat(this.mId, value.getMinute());
          break;
        case _TimeAttribute.TIME_IN_HR:
          ctx.loadFloat(this.mId, value.getHour());
          break;
        case _TimeAttribute.TIME_DAY_OF_MONTH:
          ctx.loadFloat(this.mId, value.getDayOfMonth());
          break;
        case _TimeAttribute.TIME_DAY_OF_YEAR:
          ctx.loadFloat(this.mId, value.getDayOfYear());
          break;
        // month and day-of-week are zero-based here, matching the reference
        case _TimeAttribute.TIME_MONTH_VALUE:
          ctx.loadFloat(this.mId, value.getMonth() - 1);
          break;
        case _TimeAttribute.TIME_DAY_OF_WEEK:
          ctx.loadFloat(this.mId, value.getDayOfWeek() - 1);
          break;
        case _TimeAttribute.TIME_YEAR:
          ctx.loadFloat(this.mId, value.getYear());
          break;
        case _TimeAttribute.TIME_FROM_LOAD_SEC:
          ctx.loadFloat(this.mId, (value.getMillis() - loadTime) * 1e-3);
          ctx.needsRepaint?.();
          break;
        default:
          break;
      }
    }
    deepToString(indent) {
      return `${indent}TimeAttribute[${this.mId}] = ${this.mTimeId} ${this.mType}`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const timeId = buffer.readInt();
      const type = buffer.readShort();
      const len = buffer.readShort();
      const args = [];
      for (let i = 0; i < len; i++) args.push(buffer.readInt());
      operations.push(new _TimeAttribute(id, timeId, type, args));
    }
  };
  _TimeAttribute.OP_CODE = 172;
  _TimeAttribute.TIME_FROM_NOW_SEC = 0;
  _TimeAttribute.TIME_FROM_NOW_MIN = 1;
  _TimeAttribute.TIME_FROM_NOW_HR = 2;
  _TimeAttribute.TIME_FROM_ARG_SEC = 3;
  _TimeAttribute.TIME_FROM_ARG_MIN = 4;
  _TimeAttribute.TIME_FROM_ARG_HR = 5;
  _TimeAttribute.TIME_IN_SEC = 6;
  _TimeAttribute.TIME_IN_MIN = 7;
  _TimeAttribute.TIME_IN_HR = 8;
  _TimeAttribute.TIME_DAY_OF_MONTH = 9;
  _TimeAttribute.TIME_MONTH_VALUE = 10;
  _TimeAttribute.TIME_DAY_OF_WEEK = 11;
  _TimeAttribute.TIME_YEAR = 12;
  _TimeAttribute.TIME_FROM_LOAD_SEC = 14;
  _TimeAttribute.TIME_DAY_OF_YEAR = 15;
  var TimeAttribute = _TimeAttribute;

  // third_party/remote-compose-player/src/core/operations/loom/PatternOperations.ts
  var _ReferencedOperations = class _ReferencedOperations extends Operation {
    constructor(id) {
      super();
      this.mList = [];
      this.mBody = null;
      this.mId = id;
    }
    getId() {
      return this.mId;
    }
    setId(id) {
      this.mId = id;
    }
    getList() {
      return this.mList;
    }
    setBody(bytes) {
      this.mBody = bytes;
    }
    getBody() {
      return this.mBody;
    }
    write(_buffer) {
    }
    apply(context) {
      const c = context;
      if (typeof c.putObject === "function") c.putObject(this.mId, this);
    }
    materialize(context, result, loomManager) {
      super.materialize(context, result, loomManager);
    }
    deepToString(indent) {
      let s = `${indent}ReferencedOperations[${this.mId}]
`;
      for (const op of this.mList) s += op.deepToString(indent + "  ") + "\n";
      return s;
    }
    static read(buffer, operations) {
      operations.push(new _ReferencedOperations(buffer.declareId()));
    }
  };
  _ReferencedOperations.OP_CODE = 142;
  var ReferencedOperations = _ReferencedOperations;
  var _PatternInflation = class _PatternInflation extends Operation {
    constructor(id, argIds) {
      super();
      this.mList = [];
      this.mId = id;
      this.mArgIds = argIds;
    }
    getId() {
      return this.mId;
    }
    getArgIds() {
      return this.mArgIds;
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    materialize(context, result, loomManager) {
      const def = loomManager.resolve(this, context.getDocument());
      if (def === null) {
        result.push(this);
        return;
      }
      const bodyBytes = def.getBody();
      if (bodyBytes === null || bodyBytes.length === 0) {
        return;
      }
      const ctx = context.getRemapContext().fork().withInsideMacro(true);
      const params = def.getParamIds();
      const args = this.mArgIds;
      const n = Math.min(params.length, args.length);
      for (let i = 0; i < n; i++) {
        ctx.addMapping(params[i], args[i]);
      }
      context.recordBlocks(this);
      const templateOps = context.inflateBody(bodyBytes, ctx);
      if (!templateOps || templateOps.length === 0) {
        return;
      }
      const child = makeChild(
        context,
        loomManager,
        ctx,
        context.getBlocksForCall(this),
        context.getDepth() + 1
      );
      child.expandRecursive(templateOps, result, loomManager);
    }
    deepToString(indent) {
      let s = `${indent}MacroCall[${this.mId}]
`;
      for (const op of this.mList) s += op.deepToString(indent + "  ") + "\n";
      return s;
    }
    static read(buffer, operations) {
      const id = buffer.readId();
      const argCount = buffer.readInt();
      const argIds = new Int32Array(argCount);
      for (let i = 0; i < argCount; i++) {
        argIds[i] = buffer.readId();
      }
      operations.push(new _PatternInflation(id, argIds));
    }
  };
  _PatternInflation.OP_CODE = 247;
  var PatternInflation = _PatternInflation;
  var _PatternBlock = class _PatternBlock extends Operation {
    constructor(paramIndex) {
      super();
      this.mList = [];
      this.mParamIndex = paramIndex;
    }
    getParamIndex() {
      return this.mParamIndex;
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    // No materialize override: blocks are consumed by PatternArgument via the
    // ExpansionContext block map; they should not emit themselves. We therefore
    // suppress default emission.
    materialize(_context, _result, _loomManager) {
    }
    deepToString(indent) {
      let s = `${indent}MacroBlock[${this.mParamIndex}]
`;
      for (const op of this.mList) s += op.deepToString(indent + "  ") + "\n";
      return s;
    }
    static read(buffer, operations) {
      operations.push(new _PatternBlock(buffer.readInt()));
    }
  };
  _PatternBlock.OP_CODE = 249;
  var PatternBlock = _PatternBlock;
  var _PatternForEach = class _PatternForEach extends Operation {
    constructor(collectionId, localItemId) {
      super();
      this.mList = [];
      this.mBody = null;
      this.mCollectionId = collectionId;
      this.mLocalItemId = localItemId;
    }
    getCollectionId() {
      return this.mCollectionId;
    }
    getLocalItemId() {
      return this.mLocalItemId;
    }
    getList() {
      return this.mList;
    }
    setBody(bytes) {
      this.mBody = bytes;
    }
    getBody() {
      return this.mBody;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    materialize(context, result, loomManager) {
      const array = context.getDocument().getArray(this.mCollectionId);
      if (array === null) {
        return;
      }
      const bodyBytes = this.mBody;
      if (bodyBytes === null || bodyBytes.length === 0) {
        return;
      }
      const length = array.getLength();
      for (let i = 0; i < length; i++) {
        const ctx = context.getRemapContext().fork().withInsideMacro(true);
        ctx.addMapping(this.mLocalItemId, array.getId(i));
        const nested = context.inflateBody(bodyBytes, ctx);
        const templateContent = nested.filter((op) => !(op instanceof Header));
        const child = makeChild(
          context,
          loomManager,
          ctx,
          /* @__PURE__ */ new Map(),
          context.getDepth()
        );
        child.expandRecursive(templateContent, result, loomManager);
      }
    }
    deepToString(indent) {
      let s = `${indent}MacroForEach[coll=${this.mCollectionId}, item=${this.mLocalItemId}]
`;
      for (const op of this.mList) s += op.deepToString(indent + "  ") + "\n";
      return s;
    }
    static read(buffer, operations) {
      const collectionId = buffer.readId();
      const localItemId = buffer.readId();
      operations.push(new _PatternForEach(collectionId, localItemId));
    }
  };
  _PatternForEach.OP_CODE = 244;
  var PatternForEach = _PatternForEach;
  var _IncludeReferencedOperations = class _IncludeReferencedOperations extends Operation {
    constructor(id) {
      super();
      this.mId = id;
    }
    getReferenceId() {
      return this.mId;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    materialize(context, result, loomManager) {
      const op = context.getDocument().getReferencedOperationsObject(this.mId);
      if (op instanceof ReferencedOperations) {
        const def = op;
        const bodyBytes = def.getBody();
        if (bodyBytes !== null && bodyBytes.length > 0) {
          const ctx = context.getRemapContext().fork().withInsideMacro(true);
          const nested = context.inflateBody(bodyBytes, ctx);
          const templateContent = nested.filter((o) => !(o instanceof Header));
          const child = makeChild(
            context,
            loomManager,
            ctx,
            /* @__PURE__ */ new Map(),
            context.getDepth() + 1
          );
          child.expandRecursive(templateContent, result, loomManager);
          return;
        }
      }
      super.materialize(context, result, loomManager);
    }
    deepToString(indent) {
      return `${indent}IncludeReferencedOperations[${this.mId}]`;
    }
    static read(buffer, operations) {
      operations.push(new _IncludeReferencedOperations(buffer.readId()));
    }
  };
  _IncludeReferencedOperations.OP_CODE = 245;
  var IncludeReferencedOperations = _IncludeReferencedOperations;
  var _PatternArgument = class _PatternArgument extends Operation {
    constructor(paramIndex) {
      super();
      this.mParamIndex = paramIndex;
    }
    getParamIndex() {
      return this.mParamIndex;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    materialize(context, result, loomManager) {
      const block = context.getBlock(this.mParamIndex);
      if (block !== null) {
        context.expandRecursive(block, result, loomManager);
      }
    }
    deepToString(indent) {
      return `${indent}MacroArgument[${this.mParamIndex}]`;
    }
    static read(buffer, operations) {
      operations.push(new _PatternArgument(buffer.readInt()));
    }
  };
  _PatternArgument.OP_CODE = 248;
  var PatternArgument = _PatternArgument;
  var _PatternDefine = class _PatternDefine extends Operation {
    constructor(id, paramIds, body, isContainer3) {
      super();
      this.mList = [];
      this.mId = id;
      this.mParamIds = paramIds;
      this.mBody = body;
      this.mIsContainer = isContainer3;
      if (isContainer3) {
        this.getList = () => this.mList;
      }
    }
    getId() {
      return this.mId;
    }
    getParamIds() {
      return this.mParamIds;
    }
    getBody() {
      return this.mBody;
    }
    setBody(bytes) {
      this.mBody = bytes;
    }
    isContainerForm() {
      return this.mIsContainer;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    materialize(context, result, loomManager) {
      const name = context.getDocument().getText(this.mId) ?? null;
      loomManager.add(this, name);
    }
    deepToString(indent) {
      return `${indent}MacroDefine[${this.mId}]${this.mBody.length > 0 ? ` [body: ${this.mBody.length} bytes]` : ""}`;
    }
    static read(buffer, operations) {
      const id = buffer.readId();
      const paramCount = buffer.readInt();
      if (paramCount < 0) {
        throw new Error(`PatternDefine: invalid paramCount ${paramCount}`);
      }
      const paramIds = new Int32Array(paramCount);
      for (let i = 0; i < paramCount; i++) {
        paramIds[i] = buffer.readId();
      }
      const skipLength = buffer.readInt();
      let body = new Uint8Array(0);
      const isContainer3 = skipLength === 0;
      if (skipLength > 0) {
        const start = buffer.getIndex();
        body = buffer.getBuffer().slice(start, start + skipLength);
        buffer.setIndex(start + skipLength);
      }
      operations.push(new _PatternDefine(id, paramIds, body, isContainer3));
    }
  };
  _PatternDefine.OP_CODE = 246;
  var PatternDefine = _PatternDefine;
  function makeChild(parent, loomManager, ctx, blocks, depth) {
    const Ctor = parent.constructor;
    return new Ctor(loomManager, parent.getDocument(), ctx, blocks, parent.isSafeMode(), depth);
  }

  // third_party/remote-compose-player/src/core/WireBuffer.ts
  var _WireBuffer = class _WireBuffer {
    constructor(size = _WireBuffer.BUFFER_SIZE) {
      this.mIndex = 0;
      this.mStartingIndex = 0;
      this.mSize = 0;
      this.mValidOperations = new Array(256).fill(true);
      this.mMaxSize = size;
      const ab = new ArrayBuffer(size);
      this.mBuffer = new Uint8Array(ab);
      this.mDataView = new DataView(ab);
    }
    static fromArrayBuffer(data) {
      const wb = new _WireBuffer(data.byteLength);
      wb.mBuffer.set(new Uint8Array(data));
      wb.mSize = data.byteLength;
      return wb;
    }
    resize(need) {
      if (this.mSize + need >= this.mMaxSize) {
        this.mMaxSize = Math.max(this.mMaxSize * 2, this.mSize + need);
        const ab = new ArrayBuffer(this.mMaxSize);
        const newBuf = new Uint8Array(ab);
        newBuf.set(this.mBuffer);
        this.mBuffer = newBuf;
        this.mDataView = new DataView(ab);
      }
    }
    getBuffer() {
      return this.mBuffer;
    }
    getMaxSize() {
      return this.mMaxSize;
    }
    getIndex() {
      return this.mIndex;
    }
    setIndex(index) {
      this.mIndex = index;
    }
    getSize() {
      return this.mSize;
    }
    size() {
      return this.mSize;
    }
    available() {
      return this.mSize - this.mIndex > 0;
    }
    start(type) {
      if (!this.mValidOperations[type]) {
        throw new Error(`Operation ${type} is not supported for this version`);
      }
      this.mStartingIndex = this.mIndex;
      this.writeByte(type);
    }
    startWithSize(type) {
      this.mStartingIndex = this.mIndex;
      this.writeByte(type);
      this.mIndex += 4;
    }
    endWithSize() {
      const size = this.mIndex - this.mStartingIndex;
      const currentIndex = this.mIndex;
      this.mIndex = this.mStartingIndex + 1;
      this.writeInt(size);
      this.mIndex = currentIndex;
    }
    reset(expectedSize) {
      this.mIndex = 0;
      this.mStartingIndex = 0;
      this.mSize = 0;
      if (expectedSize >= this.mMaxSize) {
        this.resize(expectedSize);
      }
    }
    // ---- Read values (big-endian) ----
    readOperationType() {
      return this.readByte();
    }
    readBoolean() {
      const value = this.mBuffer[this.mIndex];
      this.mIndex++;
      return value === 1;
    }
    readByte() {
      const value = this.mBuffer[this.mIndex] & 255;
      this.mIndex++;
      return value;
    }
    readShort() {
      const v = this.mDataView.getUint16(this.mIndex, false);
      this.mIndex += 2;
      return v;
    }
    peekInt() {
      return this.mDataView.getInt32(this.mIndex, false);
    }
    // ---- ID-typed reads ----
    // Identity defaults: a plain WireBuffer treats these as ordinary reads.
    // LoomWireBuffer overrides them to apply RemapContext during macro expansion.
    declareId() {
      return this.readInt();
    }
    readId() {
      return this.readInt();
    }
    readNanId() {
      return this.readFloat();
    }
    readLongNanId() {
      return this.readLong();
    }
    /**
     * A NaN-boxed field read as raw float32 *integer bits* rather than a float.
     *
     * Same wire field as [readNanId], for callers that store the bits (the
     * `isNaNBits`/`intBitsToFloat` representation `CoreText` uses) instead of a
     * float. Reading the bits directly never routes the value through a JS
     * number, so an engine that canonicalises NaN cannot drop the id payload —
     * the same reason `isNaNBits` inspects wire bits rather than testing the
     * float. `LoomWireBuffer` overrides this to remap the payload in place.
     */
    readNanIdBits() {
      return this.readInt();
    }
    readInt() {
      const v = this.mDataView.getInt32(this.mIndex, false);
      this.mIndex += 4;
      return v;
    }
    readLong() {
      const hi = this.mDataView.getInt32(this.mIndex, false);
      const lo = this.mDataView.getUint32(this.mIndex + 4, false);
      this.mIndex += 8;
      return hi * 4294967296 + lo;
    }
    readFloat() {
      const v = this.mDataView.getFloat32(this.mIndex, false);
      this.mIndex += 4;
      return v;
    }
    readDouble() {
      const v = this.mDataView.getFloat64(this.mIndex, false);
      this.mIndex += 8;
      return v;
    }
    readBuffer() {
      const count = this.readInt();
      const b = this.mBuffer.slice(this.mIndex, this.mIndex + count);
      this.mIndex += count;
      return b;
    }
    readBufferMax(maxSize) {
      const count = this.readInt();
      if (count < 0 || count > maxSize) {
        throw new Error(`attempt read a buff of invalid size 0 <= ${count} > ${maxSize}`);
      }
      const b = this.mBuffer.slice(this.mIndex, this.mIndex + count);
      this.mIndex += count;
      return b;
    }
    readUTF8() {
      const buf = this.readBuffer();
      return new TextDecoder().decode(buf);
    }
    readUTF8Max(maxSize) {
      const buf = this.readBufferMax(maxSize);
      return new TextDecoder().decode(buf);
    }
    // ---- Write values (big-endian) ----
    writeBoolean(value) {
      this.resize(1);
      this.mBuffer[this.mIndex++] = value ? 1 : 0;
      this.mSize++;
    }
    writeByte(value) {
      this.resize(1);
      this.mBuffer[this.mIndex++] = value & 255;
      this.mSize++;
    }
    writeShort(value) {
      this.resize(2);
      this.mDataView.setUint16(this.mIndex, value, false);
      this.mIndex += 2;
      this.mSize += 2;
    }
    writeInt(value) {
      this.resize(4);
      this.mDataView.setInt32(this.mIndex, value, false);
      this.mIndex += 4;
      this.mSize += 4;
    }
    writeLong(value) {
      this.resize(8);
      const hi = Math.floor(value / 4294967296) | 0;
      const lo = value - hi * 4294967296;
      this.mDataView.setInt32(this.mIndex, hi, false);
      this.mDataView.setUint32(this.mIndex + 4, lo, false);
      this.mIndex += 8;
      this.mSize += 8;
    }
    writeFloat(value) {
      this.resize(4);
      this.mDataView.setFloat32(this.mIndex, value, false);
      this.mIndex += 4;
      this.mSize += 4;
    }
    writeDouble(value) {
      this.resize(8);
      this.mDataView.setFloat64(this.mIndex, value, false);
      this.mIndex += 8;
      this.mSize += 8;
    }
    writeBuffer(b) {
      this.resize(b.length + 4);
      this.writeInt(b.length);
      this.mBuffer.set(b, this.mIndex);
      this.mIndex += b.length;
      this.mSize += b.length;
    }
    writeUTF8(content) {
      const buffer = new TextEncoder().encode(content);
      this.writeBuffer(buffer);
    }
    cloneBytes() {
      return this.mBuffer.slice(0, this.mSize);
    }
    setVersion(documentApiLevel, profiles) {
      for (let i = 0; i < this.mValidOperations.length; i++) {
        this.mValidOperations[i] = true;
      }
    }
    setValidOperationsFromSet(supportedOperations) {
      this.mValidOperations.fill(false);
      for (const o of supportedOperations) {
        this.mValidOperations[o] = true;
      }
    }
    setValidOperationsCallback(validFn, apiLevel, profiles) {
      for (let i = 0; i < this.mValidOperations.length; i++) {
        this.mValidOperations[i] = validFn(i, apiLevel, profiles);
      }
    }
    moveBlock(beyond, insertLocation) {
      if (insertLocation < 0 || beyond > this.mSize || insertLocation >= beyond) {
        return;
      }
      const lengthOfBlockA = beyond - insertLocation;
      const lengthOfBlockB = this.mSize - beyond;
      if (lengthOfBlockB < lengthOfBlockA) {
        const temp = this.mBuffer.slice(beyond, beyond + lengthOfBlockB);
        this.mBuffer.copyWithin(insertLocation + lengthOfBlockB, insertLocation, insertLocation + lengthOfBlockA);
        this.mBuffer.set(temp, insertLocation);
      } else {
        const temp = this.mBuffer.slice(insertLocation, insertLocation + lengthOfBlockA);
        this.mBuffer.copyWithin(insertLocation, beyond, beyond + lengthOfBlockB);
        this.mBuffer.set(temp, insertLocation + lengthOfBlockB);
      }
    }
  };
  _WireBuffer.BUFFER_SIZE = 1024 * 1024;
  var WireBuffer = _WireBuffer;

  // third_party/remote-compose-player/src/core/operations/DrawBase4.ts
  var DrawBase4 = class extends PaintOperation {
    // Constructed from the raw int32 bits of each coordinate (see read()).
    constructor(x1Bits, y1Bits, x2Bits, y2Bits) {
      super();
      this.mX1Bits = x1Bits;
      this.mY1Bits = y1Bits;
      this.mX2Bits = x2Bits;
      this.mY2Bits = y2Bits;
      this.mX1 = isNaNBits(x1Bits) ? 0 : intBitsToFloat(x1Bits);
      this.mY1 = isNaNBits(y1Bits) ? 0 : intBitsToFloat(y1Bits);
      this.mX2 = isNaNBits(x2Bits) ? 0 : intBitsToFloat(x2Bits);
      this.mY2 = isNaNBits(y2Bits) ? 0 : intBitsToFloat(y2Bits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mX1Bits)) context.listensTo(idFromBits(this.mX1Bits), this);
      if (isNaNBits(this.mY1Bits)) context.listensTo(idFromBits(this.mY1Bits), this);
      if (isNaNBits(this.mX2Bits)) context.listensTo(idFromBits(this.mX2Bits), this);
      if (isNaNBits(this.mY2Bits)) context.listensTo(idFromBits(this.mY2Bits), this);
    }
    updateVariables(context) {
      this.mX1 = isNaNBits(this.mX1Bits) ? context.getFloat(idFromBits(this.mX1Bits)) : intBitsToFloat(this.mX1Bits);
      this.mY1 = isNaNBits(this.mY1Bits) ? context.getFloat(idFromBits(this.mY1Bits)) : intBitsToFloat(this.mY1Bits);
      this.mX2 = isNaNBits(this.mX2Bits) ? context.getFloat(idFromBits(this.mX2Bits)) : intBitsToFloat(this.mX2Bits);
      this.mY2 = isNaNBits(this.mY2Bits) ? context.getFloat(idFromBits(this.mY2Bits)) : intBitsToFloat(this.mY2Bits);
    }
    paint(context) {
      this.paintBase4(context, this.mX1, this.mY1, this.mX2, this.mY2);
    }
  };

  // third_party/remote-compose-player/src/core/operations/DrawRect.ts
  var _DrawRect = class _DrawRect extends DrawBase4 {
    paintBase4(context, x1, y1, x2, y2) {
      context.drawRect(x1, y1, x2, y2);
    }
    deepToString(indent) {
      return `${indent}DrawRect(${this.mX1}, ${this.mY1}, ${this.mX2}, ${this.mY2})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawRect(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawRect.OP_CODE = 42;
  var DrawRect = _DrawRect;

  // third_party/remote-compose-player/src/core/operations/DrawBase3.ts
  var DrawBase3 = class extends PaintOperation {
    constructor(v1Bits, v2Bits, v3Bits) {
      super();
      this.mV1Bits = v1Bits;
      this.mV2Bits = v2Bits;
      this.mV3Bits = v3Bits;
      this.mV1 = isNaNBits(v1Bits) ? 0 : intBitsToFloat(v1Bits);
      this.mV2 = isNaNBits(v2Bits) ? 0 : intBitsToFloat(v2Bits);
      this.mV3 = isNaNBits(v3Bits) ? 0 : intBitsToFloat(v3Bits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mV1Bits)) context.listensTo(idFromBits(this.mV1Bits), this);
      if (isNaNBits(this.mV2Bits)) context.listensTo(idFromBits(this.mV2Bits), this);
      if (isNaNBits(this.mV3Bits)) context.listensTo(idFromBits(this.mV3Bits), this);
    }
    updateVariables(context) {
      this.mV1 = isNaNBits(this.mV1Bits) ? context.getFloat(idFromBits(this.mV1Bits)) : intBitsToFloat(this.mV1Bits);
      this.mV2 = isNaNBits(this.mV2Bits) ? context.getFloat(idFromBits(this.mV2Bits)) : intBitsToFloat(this.mV2Bits);
      this.mV3 = isNaNBits(this.mV3Bits) ? context.getFloat(idFromBits(this.mV3Bits)) : intBitsToFloat(this.mV3Bits);
    }
    paint(context) {
      this.paintBase3(context, this.mV1, this.mV2, this.mV3);
    }
  };

  // third_party/remote-compose-player/src/core/operations/DrawCircle.ts
  var _DrawCircle = class _DrawCircle extends DrawBase3 {
    paintBase3(context, v1, v2, v3) {
      context.drawCircle(v1, v2, v3);
    }
    deepToString(indent) {
      return `${indent}DrawCircle(${this.mV1}, ${this.mV2}, ${this.mV3})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawCircle(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _DrawCircle.OP_CODE = 46;
  var DrawCircle = _DrawCircle;

  // third_party/remote-compose-player/src/core/operations/DrawOval.ts
  var _DrawOval = class _DrawOval extends DrawBase4 {
    paintBase4(context, x1, y1, x2, y2) {
      context.drawOval(x1, y1, x2, y2);
    }
    deepToString(indent) {
      return `${indent}DrawOval(${this.mX1}, ${this.mY1}, ${this.mX2}, ${this.mY2})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawOval(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawOval.OP_CODE = 56;
  var DrawOval = _DrawOval;

  // third_party/remote-compose-player/src/core/operations/DrawBase6.ts
  var DrawBase6 = class extends PaintOperation {
    constructor(v1Bits, v2Bits, v3Bits, v4Bits, v5Bits, v6Bits) {
      super();
      this.mV1Bits = v1Bits;
      this.mV2Bits = v2Bits;
      this.mV3Bits = v3Bits;
      this.mV4Bits = v4Bits;
      this.mV5Bits = v5Bits;
      this.mV6Bits = v6Bits;
      this.mV1 = isNaNBits(v1Bits) ? 0 : intBitsToFloat(v1Bits);
      this.mV2 = isNaNBits(v2Bits) ? 0 : intBitsToFloat(v2Bits);
      this.mV3 = isNaNBits(v3Bits) ? 0 : intBitsToFloat(v3Bits);
      this.mV4 = isNaNBits(v4Bits) ? 0 : intBitsToFloat(v4Bits);
      this.mV5 = isNaNBits(v5Bits) ? 0 : intBitsToFloat(v5Bits);
      this.mV6 = isNaNBits(v6Bits) ? 0 : intBitsToFloat(v6Bits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mV1Bits)) context.listensTo(idFromBits(this.mV1Bits), this);
      if (isNaNBits(this.mV2Bits)) context.listensTo(idFromBits(this.mV2Bits), this);
      if (isNaNBits(this.mV3Bits)) context.listensTo(idFromBits(this.mV3Bits), this);
      if (isNaNBits(this.mV4Bits)) context.listensTo(idFromBits(this.mV4Bits), this);
      if (isNaNBits(this.mV5Bits)) context.listensTo(idFromBits(this.mV5Bits), this);
      if (isNaNBits(this.mV6Bits)) context.listensTo(idFromBits(this.mV6Bits), this);
    }
    updateVariables(context) {
      this.mV1 = isNaNBits(this.mV1Bits) ? context.getFloat(idFromBits(this.mV1Bits)) : intBitsToFloat(this.mV1Bits);
      this.mV2 = isNaNBits(this.mV2Bits) ? context.getFloat(idFromBits(this.mV2Bits)) : intBitsToFloat(this.mV2Bits);
      this.mV3 = isNaNBits(this.mV3Bits) ? context.getFloat(idFromBits(this.mV3Bits)) : intBitsToFloat(this.mV3Bits);
      this.mV4 = isNaNBits(this.mV4Bits) ? context.getFloat(idFromBits(this.mV4Bits)) : intBitsToFloat(this.mV4Bits);
      this.mV5 = isNaNBits(this.mV5Bits) ? context.getFloat(idFromBits(this.mV5Bits)) : intBitsToFloat(this.mV5Bits);
      this.mV6 = isNaNBits(this.mV6Bits) ? context.getFloat(idFromBits(this.mV6Bits)) : intBitsToFloat(this.mV6Bits);
    }
    paint(context) {
      this.paintBase6(context, this.mV1, this.mV2, this.mV3, this.mV4, this.mV5, this.mV6);
    }
  };

  // third_party/remote-compose-player/src/core/operations/DrawRoundRect.ts
  var _DrawRoundRect = class _DrawRoundRect extends DrawBase6 {
    paintBase6(context, v1, v2, v3, v4, v5, v6) {
      context.drawRoundRect(v1, v2, v3, v4, v5, v6);
    }
    deepToString(indent) {
      return `${indent}DrawRoundRect(${this.mV1}, ${this.mV2}, ${this.mV3}, ${this.mV4}, rx=${this.mV5}, ry=${this.mV6})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawRoundRect(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawRoundRect.OP_CODE = 51;
  var DrawRoundRect = _DrawRoundRect;

  // third_party/remote-compose-player/src/core/operations/DrawArc.ts
  var _DrawArc = class _DrawArc extends DrawBase6 {
    paintBase6(context, v1, v2, v3, v4, v5, v6) {
      context.drawArc(v1, v2, v3, v4, v5, v6);
    }
    deepToString(indent) {
      return `${indent}DrawArc(${this.mV1}, ${this.mV2}, ${this.mV3}, ${this.mV4}, start=${this.mV5}, sweep=${this.mV6})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawArc(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawArc.OP_CODE = 152;
  var DrawArc = _DrawArc;

  // third_party/remote-compose-player/src/core/operations/DrawSector.ts
  var _DrawSector = class _DrawSector extends DrawBase6 {
    paintBase6(context, v1, v2, v3, v4, v5, v6) {
      context.drawSector(v1, v2, v3, v4, v5, v6);
    }
    deepToString(indent) {
      return `${indent}DrawSector`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawSector(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawSector.OP_CODE = 52;
  var DrawSector = _DrawSector;

  // third_party/remote-compose-player/src/core/operations/DrawPath.ts
  var _DrawPath = class _DrawPath extends PaintOperation {
    constructor(pathId, start, end) {
      super();
      this.mPathId = pathId;
      this.mStart = start;
      this.mEnd = end;
    }
    write(_buffer) {
    }
    paint(context) {
      context.drawPath(this.getId(this.mPathId, context), this.mStart, this.mEnd);
    }
    deepToString(indent) {
      return `${indent}DrawPath(${this.mPathId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      operations.push(new _DrawPath(id, 0, 1));
    }
  };
  _DrawPath.OP_CODE = 124;
  var DrawPath = _DrawPath;

  // third_party/remote-compose-player/src/core/operations/DrawTweenPath.ts
  var _DrawTweenPath = class _DrawTweenPath extends PaintOperation {
    constructor(path1Id, path2Id, tweenBits, startBits, endBits) {
      super();
      this.mPath1Id = path1Id;
      this.mPath2Id = path2Id;
      this.mTweenBits = tweenBits;
      this.mStartBits = startBits;
      this.mEndBits = endBits;
      this.mOutTween = isNaNBits(tweenBits) ? 0 : intBitsToFloat(tweenBits);
      this.mOutStart = isNaNBits(startBits) ? 0 : intBitsToFloat(startBits);
      this.mOutEnd = isNaNBits(endBits) ? 0 : intBitsToFloat(endBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mTweenBits)) context.listensTo(idFromBits(this.mTweenBits), this);
      if (isNaNBits(this.mStartBits)) context.listensTo(idFromBits(this.mStartBits), this);
      if (isNaNBits(this.mEndBits)) context.listensTo(idFromBits(this.mEndBits), this);
    }
    updateVariables(context) {
      this.mOutTween = isNaNBits(this.mTweenBits) ? context.getFloat(idFromBits(this.mTweenBits)) : intBitsToFloat(this.mTweenBits);
      this.mOutStart = isNaNBits(this.mStartBits) ? context.getFloat(idFromBits(this.mStartBits)) : intBitsToFloat(this.mStartBits);
      this.mOutEnd = isNaNBits(this.mEndBits) ? context.getFloat(idFromBits(this.mEndBits)) : intBitsToFloat(this.mEndBits);
    }
    paint(context) {
      context.drawTweenPath(this.getId(this.mPath1Id, context), this.getId(this.mPath2Id, context), this.mOutTween, this.mOutStart, this.mOutEnd);
    }
    deepToString(indent) {
      return `${indent}DrawTweenPath`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawTweenPath(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawTweenPath.OP_CODE = 125;
  var DrawTweenPath = _DrawTweenPath;

  // third_party/remote-compose-player/src/core/operations/DrawContent.ts
  var _DrawContent = class _DrawContent extends PaintOperation {
    constructor() {
      super(...arguments);
      this.mLayoutComponent = null;
      this.mInProcessing = false;
    }
    setComponent(component) {
      this.mLayoutComponent = component;
    }
    write(_buffer) {
    }
    paint(context) {
      if (this.mLayoutComponent != null) {
        if (!this.mInProcessing) {
          this.mInProcessing = true;
          this.mLayoutComponent.drawContent(context);
          this.mInProcessing = false;
        }
      }
    }
    deepToString(indent) {
      return `${indent}DrawContent`;
    }
    static read(_buffer, operations) {
      operations.push(new _DrawContent());
    }
  };
  _DrawContent.OP_CODE = 139;
  var DrawContent = _DrawContent;

  // third_party/remote-compose-player/src/core/operations/DrawBitmap.ts
  var _DrawBitmap = class _DrawBitmap extends PaintOperation {
    constructor(imageId, leftBits, topBits, rightBits, bottomBits, cdId) {
      super();
      this.mImageId = imageId;
      this.mLeftBits = leftBits;
      this.mTopBits = topBits;
      this.mRightBits = rightBits;
      this.mBottomBits = bottomBits;
      this.mCdId = cdId;
      this.mOutLeft = isNaNBits(leftBits) ? 0 : intBitsToFloat(leftBits);
      this.mOutTop = isNaNBits(topBits) ? 0 : intBitsToFloat(topBits);
      this.mOutRight = isNaNBits(rightBits) ? 0 : intBitsToFloat(rightBits);
      this.mOutBottom = isNaNBits(bottomBits) ? 0 : intBitsToFloat(bottomBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mLeftBits)) context.listensTo(idFromBits(this.mLeftBits), this);
      if (isNaNBits(this.mTopBits)) context.listensTo(idFromBits(this.mTopBits), this);
      if (isNaNBits(this.mRightBits)) context.listensTo(idFromBits(this.mRightBits), this);
      if (isNaNBits(this.mBottomBits)) context.listensTo(idFromBits(this.mBottomBits), this);
    }
    updateVariables(context) {
      this.mOutLeft = isNaNBits(this.mLeftBits) ? context.getFloat(idFromBits(this.mLeftBits)) : intBitsToFloat(this.mLeftBits);
      this.mOutTop = isNaNBits(this.mTopBits) ? context.getFloat(idFromBits(this.mTopBits)) : intBitsToFloat(this.mTopBits);
      this.mOutRight = isNaNBits(this.mRightBits) ? context.getFloat(idFromBits(this.mRightBits)) : intBitsToFloat(this.mRightBits);
      this.mOutBottom = isNaNBits(this.mBottomBits) ? context.getFloat(idFromBits(this.mBottomBits)) : intBitsToFloat(this.mBottomBits);
    }
    paint(context) {
      context.drawBitmapSimple(
        this.getId(this.mImageId, context),
        this.mOutLeft,
        this.mOutTop,
        this.mOutRight,
        this.mOutBottom
      );
    }
    deepToString(indent) {
      return `${indent}DrawBitmap(${this.mImageId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const left = buffer.readInt();
      const top = buffer.readInt();
      const right = buffer.readInt();
      const bottom = buffer.readInt();
      const cdId = buffer.readInt();
      operations.push(new _DrawBitmap(id, left, top, right, bottom, cdId));
    }
  };
  _DrawBitmap.OP_CODE = 44;
  var DrawBitmap = _DrawBitmap;

  // third_party/remote-compose-player/src/core/operations/DrawBitmapInt.ts
  var _DrawBitmapInt = class _DrawBitmapInt extends PaintOperation {
    constructor(imageId, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB, cdId) {
      super();
      this.mImageId = imageId;
      this.mSrcL = srcL;
      this.mSrcT = srcT;
      this.mSrcR = srcR;
      this.mSrcB = srcB;
      this.mDstL = dstL;
      this.mDstT = dstT;
      this.mDstR = dstR;
      this.mDstB = dstB;
      this.mCdId = cdId;
    }
    write(_buffer) {
    }
    paint(context) {
      context.drawBitmap(
        this.getId(this.mImageId, context),
        this.mSrcL,
        this.mSrcT,
        this.mSrcR,
        this.mSrcB,
        this.mDstL,
        this.mDstT,
        this.mDstR,
        this.mDstB,
        this.mCdId
      );
    }
    deepToString(indent) {
      return `${indent}DrawBitmapInt(${this.mImageId})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawBitmapInt(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawBitmapInt.OP_CODE = 66;
  var DrawBitmapInt = _DrawBitmapInt;

  // third_party/remote-compose-player/src/core/operations/utilities/ImageScaling.ts
  var _ImageScaling = class _ImageScaling {
    constructor() {
      this.mFinalDstLeft = 0;
      this.mFinalDstTop = 0;
      this.mFinalDstRight = 0;
      this.mFinalDstBottom = 0;
    }
    setup(srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom, scaleType, scaleFactor) {
      const sw = srcRight - srcLeft | 0;
      const sh = srcBottom - srcTop | 0;
      const width = dstRight - dstLeft;
      const height = dstBottom - dstTop;
      let dw = width | 0;
      let dh = height | 0;
      let dLeft = 0;
      let dRight = dw;
      let dTop = 0;
      let dBottom = dh;
      if (sh === 0 || sw === 0) {
        this.mFinalDstLeft = dstLeft;
        this.mFinalDstTop = dstTop;
        this.mFinalDstRight = dstRight;
        this.mFinalDstBottom = dstBottom;
        return;
      }
      switch (scaleType) {
        case _ImageScaling.SCALE_NONE:
          dh = sh;
          dw = sw;
          dTop = ((height | 0) - dh) / 2 | 0;
          dBottom = dh + dTop;
          dLeft = ((width | 0) - dw) / 2 | 0;
          dRight = dw + dLeft;
          break;
        case _ImageScaling.SCALE_INSIDE:
          if (dh > sh && dw > sw) {
            dh = sh;
            dw = sw;
          } else if (sw * height > width * sh) {
            dh = dw * sh / sw | 0;
          } else {
            dw = dh * sw / sh | 0;
          }
          dTop = ((height | 0) - dh) / 2 | 0;
          dBottom = dh + dTop;
          dLeft = ((width | 0) - dw) / 2 | 0;
          dRight = dw + dLeft;
          break;
        case _ImageScaling.SCALE_FILL_WIDTH:
          dh = dw * sh / sw | 0;
          dTop = ((height | 0) - dh) / 2 | 0;
          dBottom = dh + dTop;
          dLeft = ((width | 0) - dw) / 2 | 0;
          dRight = dw + dLeft;
          break;
        case _ImageScaling.SCALE_FILL_HEIGHT:
          dw = dh * sw / sh | 0;
          dTop = ((height | 0) - dh) / 2 | 0;
          dBottom = dh + dTop;
          dLeft = ((width | 0) - dw) / 2 | 0;
          dRight = dw + dLeft;
          break;
        case _ImageScaling.SCALE_FIT:
          if (sw * height > width * sh) {
            dh = dw * sh / sw | 0;
            dTop = ((height | 0) - dh) / 2 | 0;
            dBottom = dh + dTop;
          } else {
            dw = dh * sw / sh | 0;
            dLeft = ((width | 0) - dw) / 2 | 0;
            dRight = dw + dLeft;
          }
          break;
        case _ImageScaling.SCALE_CROP:
          if (sw * height < width * sh) {
            dh = dw * sh / sw | 0;
            dTop = ((height | 0) - dh) / 2 | 0;
            dBottom = dh + dTop;
          } else {
            dw = dh * sw / sh | 0;
            dLeft = ((width | 0) - dw) / 2 | 0;
            dRight = dw + dLeft;
          }
          break;
        case _ImageScaling.SCALE_FILL_BOUNDS:
          break;
        case _ImageScaling.SCALE_FIXED_SCALE:
          dh = sh * scaleFactor | 0;
          dw = sw * scaleFactor | 0;
          dTop = ((height | 0) - dh) / 2 | 0;
          dBottom = dh + dTop;
          dLeft = ((width | 0) - dw) / 2 | 0;
          dRight = dw + dLeft;
          break;
      }
      this.mFinalDstRight = dRight + dstLeft;
      this.mFinalDstLeft = dLeft + dstLeft;
      this.mFinalDstBottom = dBottom + dstTop;
      this.mFinalDstTop = dTop + dstTop;
    }
  };
  _ImageScaling.SCALE_NONE = 0;
  _ImageScaling.SCALE_INSIDE = 1;
  _ImageScaling.SCALE_FILL_WIDTH = 2;
  _ImageScaling.SCALE_FILL_HEIGHT = 3;
  _ImageScaling.SCALE_FIT = 4;
  _ImageScaling.SCALE_CROP = 5;
  _ImageScaling.SCALE_FILL_BOUNDS = 6;
  _ImageScaling.SCALE_FIXED_SCALE = 7;
  var ImageScaling = _ImageScaling;

  // third_party/remote-compose-player/src/core/operations/DrawBitmapScaled.ts
  var _DrawBitmapScaled = class _DrawBitmapScaled extends PaintOperation {
    // Args are raw int32 bits for the float fields (see read()).
    constructor(imageId, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB, scaleType, scaleFactor, cdId) {
      super();
      this.mScaling = new ImageScaling();
      const lit = (b) => isNaNBits(b) ? 0 : intBitsToFloat(b);
      this.mImageId = imageId;
      this.mSrcLeft = srcL;
      this.mOutSrcLeft = lit(srcL);
      this.mSrcTop = srcT;
      this.mOutSrcTop = lit(srcT);
      this.mSrcRight = srcR;
      this.mOutSrcRight = lit(srcR);
      this.mSrcBottom = srcB;
      this.mOutSrcBottom = lit(srcB);
      this.mDstLeft = dstL;
      this.mOutDstLeft = lit(dstL);
      this.mDstTop = dstT;
      this.mOutDstTop = lit(dstT);
      this.mDstRight = dstR;
      this.mOutDstRight = lit(dstR);
      this.mDstBottom = dstB;
      this.mOutDstBottom = lit(dstB);
      this.mScaleType = scaleType & 255;
      if ((scaleType >> 8 & 1) !== 0) {
        this.mImageId |= PaintOperation.PTR_DEREFERENCE;
      }
      this.mScaleFactor = scaleFactor;
      this.mOutScaleFactor = lit(scaleFactor);
      this.mCdId = cdId;
    }
    write(_buffer) {
    }
    registerListening(context) {
      const register = (b) => {
        if (isNaNBits(b)) context.listensTo(idFromBits(b), this);
      };
      register(this.mSrcLeft);
      register(this.mSrcTop);
      register(this.mSrcRight);
      register(this.mSrcBottom);
      register(this.mDstLeft);
      register(this.mDstTop);
      register(this.mDstRight);
      register(this.mDstBottom);
      register(this.mScaleFactor);
    }
    updateVariables(context) {
      const resolve = (b) => isNaNBits(b) ? context.getFloat(idFromBits(b)) : intBitsToFloat(b);
      this.mOutSrcLeft = resolve(this.mSrcLeft);
      this.mOutSrcTop = resolve(this.mSrcTop);
      this.mOutSrcRight = resolve(this.mSrcRight);
      this.mOutSrcBottom = resolve(this.mSrcBottom);
      this.mOutDstLeft = resolve(this.mDstLeft);
      this.mOutDstTop = resolve(this.mDstTop);
      this.mOutDstRight = resolve(this.mDstRight);
      this.mOutDstBottom = resolve(this.mDstBottom);
      this.mOutScaleFactor = resolve(this.mScaleFactor);
    }
    paint(context) {
      this.mScaling.setup(
        this.mOutSrcLeft,
        this.mOutSrcTop,
        this.mOutSrcRight,
        this.mOutSrcBottom,
        this.mOutDstLeft,
        this.mOutDstTop,
        this.mOutDstRight,
        this.mOutDstBottom,
        this.mScaleType,
        this.mOutScaleFactor
      );
      context.save();
      context.clipRect(this.mOutDstLeft, this.mOutDstTop, this.mOutDstRight, this.mOutDstBottom);
      context.drawBitmap(
        this.getId(this.mImageId, context),
        this.mOutSrcLeft | 0,
        this.mOutSrcTop | 0,
        this.mOutSrcRight | 0,
        this.mOutSrcBottom | 0,
        this.mScaling.mFinalDstLeft | 0,
        this.mScaling.mFinalDstTop | 0,
        this.mScaling.mFinalDstRight | 0,
        this.mScaling.mFinalDstBottom | 0,
        this.mCdId
      );
      context.restore();
    }
    deepToString(indent) {
      return `${indent}DrawBitmapScaled(${this.mImageId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const srcL = buffer.readInt();
      const srcT = buffer.readInt();
      const srcR = buffer.readInt();
      const srcB = buffer.readInt();
      const dstL = buffer.readInt();
      const dstT = buffer.readInt();
      const dstR = buffer.readInt();
      const dstB = buffer.readInt();
      const scaleType = buffer.readInt();
      const scaleFactor = buffer.readInt();
      const cdId = buffer.readInt();
      operations.push(new _DrawBitmapScaled(id, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB, scaleType, scaleFactor, cdId));
    }
  };
  _DrawBitmapScaled.OP_CODE = 149;
  var DrawBitmapScaled = _DrawBitmapScaled;

  // third_party/remote-compose-player/src/core/operations/DrawText.ts
  var _DrawText = class _DrawText extends PaintOperation {
    constructor(textId, start, end, contextStart, contextEnd, xBits, yBits, rtl) {
      super();
      this.mTextId = textId;
      this.mStart = start;
      this.mEnd = end;
      this.mContextStart = contextStart;
      this.mContextEnd = contextEnd;
      this.mXBits = xBits;
      this.mYBits = yBits;
      this.mX = isNaNBits(xBits) ? 0 : intBitsToFloat(xBits);
      this.mY = isNaNBits(yBits) ? 0 : intBitsToFloat(yBits);
      this.mRtl = rtl;
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mTextId, this);
      if (isNaNBits(this.mXBits)) context.listensTo(idFromBits(this.mXBits), this);
      if (isNaNBits(this.mYBits)) context.listensTo(idFromBits(this.mYBits), this);
    }
    updateVariables(context) {
      this.mX = isNaNBits(this.mXBits) ? context.getFloat(idFromBits(this.mXBits)) : intBitsToFloat(this.mXBits);
      this.mY = isNaNBits(this.mYBits) ? context.getFloat(idFromBits(this.mYBits)) : intBitsToFloat(this.mYBits);
    }
    paint(context) {
      context.drawTextRun(
        this.mTextId,
        this.mStart,
        this.mEnd,
        this.mContextStart,
        this.mContextEnd,
        this.mX,
        this.mY,
        this.mRtl
      );
    }
    deepToString(indent) {
      return `${indent}DrawText(${this.mTextId})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawText(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readBoolean()
      ));
    }
  };
  _DrawText.OP_CODE = 43;
  var DrawText = _DrawText;

  // third_party/remote-compose-player/src/core/operations/DrawTextOnPath.ts
  var _DrawTextOnPath = class _DrawTextOnPath extends PaintOperation {
    constructor(textId, pathId, hOffsetBits, vOffsetBits) {
      super();
      this.mTextId = textId;
      this.mPathId = pathId;
      this.mHOffsetBits = hOffsetBits;
      this.mVOffsetBits = vOffsetBits;
      this.mOutHOffset = isNaNBits(hOffsetBits) ? 0 : intBitsToFloat(hOffsetBits);
      this.mOutVOffset = isNaNBits(vOffsetBits) ? 0 : intBitsToFloat(vOffsetBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mTextId, this);
      if (isNaNBits(this.mHOffsetBits)) context.listensTo(idFromBits(this.mHOffsetBits), this);
      if (isNaNBits(this.mVOffsetBits)) context.listensTo(idFromBits(this.mVOffsetBits), this);
    }
    updateVariables(context) {
      this.mOutHOffset = isNaNBits(this.mHOffsetBits) ? context.getFloat(idFromBits(this.mHOffsetBits)) : intBitsToFloat(this.mHOffsetBits);
      this.mOutVOffset = isNaNBits(this.mVOffsetBits) ? context.getFloat(idFromBits(this.mVOffsetBits)) : intBitsToFloat(this.mVOffsetBits);
    }
    paint(context) {
      context.drawTextOnPath(this.mTextId, this.getId(this.mPathId, context), this.mOutHOffset, this.mOutVOffset);
    }
    deepToString(indent) {
      return `${indent}DrawTextOnPath`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const pathId = buffer.readInt();
      const vOffsetBits = buffer.readInt();
      const hOffsetBits = buffer.readInt();
      operations.push(new _DrawTextOnPath(textId, pathId, hOffsetBits, vOffsetBits));
    }
  };
  _DrawTextOnPath.OP_CODE = 53;
  var DrawTextOnPath = _DrawTextOnPath;

  // third_party/remote-compose-player/src/core/operations/DrawToBitmap.ts
  var _DrawToBitmap = class _DrawToBitmap extends PaintOperation {
    constructor(bitmapId, mode, color) {
      super();
      this.mBitmapId = bitmapId;
      this.mMode = mode;
      this.mColor = color;
    }
    write(_buffer) {
    }
    paint(context) {
      context.drawToBitmap(this.getId(this.mBitmapId, context), this.mMode, this.mColor);
    }
    deepToString(indent) {
      return `${indent}DrawToBitmap(${this.mBitmapId})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawToBitmap(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _DrawToBitmap.OP_CODE = 190;
  var DrawToBitmap = _DrawToBitmap;

  // third_party/remote-compose-player/src/core/operations/DrawLine.ts
  var _DrawLine = class _DrawLine extends DrawBase4 {
    paintBase4(context, x1, y1, x2, y2) {
      context.drawLine(x1, y1, x2, y2);
    }
    deepToString(indent) {
      return `${indent}DrawLine(${this.mX1}, ${this.mY1}, ${this.mX2}, ${this.mY2})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawLine(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawLine.OP_CODE = 47;
  var DrawLine = _DrawLine;

  // third_party/remote-compose-player/src/core/PaintContext.ts
  var PaintContext = class {
    constructor(context) {
      this.mNeedsRepaint = false;
      this.mMeasureVersion = 0;
      this.mContext = context;
    }
    getContext() {
      return this.mContext;
    }
    setContext(context) {
      this.mContext = context;
    }
    doesNeedsRepaint() {
      return this.mNeedsRepaint;
    }
    clearNeedsRepaint() {
      this.mNeedsRepaint = false;
    }
    needsRepaint() {
      this.mNeedsRepaint = true;
    }
    setMeasureVersion(v) {
      this.mMeasureVersion = v;
    }
    getMeasureVersion() {
      return this.mMeasureVersion;
    }
    save() {
      this.matrixSave();
    }
    restore() {
      this.matrixRestore();
    }
    saveLayer(_x, _y, _w, _h) {
      this.matrixSave();
    }
    getClock() {
      return this.mContext.getClock();
    }
    isDebug() {
      return this.mContext.isBasicDebug();
    }
    isAnimationEnabled() {
      return this.mContext.isAnimationEnabled();
    }
    isVisualDebug() {
      return this.mContext.isVisualDebug();
    }
    supportsVersion(major, minor, patch) {
      return this.mContext.supportsVersion(major, minor, patch);
    }
    log(content) {
      console.log(`[LOG] ${content}`);
    }
    wakeIn(seconds) {
      this.mContext.mRemoteComposeState.wakeIn(seconds);
    }
  };
  PaintContext.TEXT_MEASURE_MONOSPACE_WIDTH = 1;
  PaintContext.TEXT_MEASURE_FONT_HEIGHT = 2;
  PaintContext.TEXT_MEASURE_SPACES = 4;
  PaintContext.TEXT_COMPLEX = 8;
  PaintContext.TEXT_MEASURE_AUTOSIZE = 16;

  // third_party/remote-compose-player/src/core/operations/DrawTextAnchored.ts
  var _DrawTextAnchored = class _DrawTextAnchored extends PaintOperation {
    constructor(textId, xBits, yBits, panXBits, panYBits, flags) {
      super();
      // Cached text measurement
      this.mBounds = new Float32Array(4);
      this.mLastString = null;
      this.mTextID = textId;
      this.mXBits = xBits;
      this.mYBits = yBits;
      this.mPanXBits = panXBits;
      this.mPanYBits = panYBits;
      this.mFlags = flags;
      this.mOutX = isNaNBits(xBits) ? 0 : intBitsToFloat(xBits);
      this.mOutY = isNaNBits(yBits) ? 0 : intBitsToFloat(yBits);
      this.mOutPanX = isNaNBits(panXBits) ? 0 : intBitsToFloat(panXBits);
      this.mOutPanY = intBitsToFloat(panYBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mTextID, this);
      if (isNaNBits(this.mXBits)) {
        context.listensTo(idFromBits(this.mXBits), this);
      }
      if (isNaNBits(this.mYBits)) {
        context.listensTo(idFromBits(this.mYBits), this);
      }
      if (isNaNBits(this.mPanXBits)) {
        context.listensTo(idFromBits(this.mPanXBits), this);
      }
      if (isNaNBits(this.mPanYBits) && idFromBits(this.mPanYBits) > 0) {
        context.listensTo(idFromBits(this.mPanYBits), this);
      }
    }
    updateVariables(context) {
      this.mOutX = isNaNBits(this.mXBits) ? context.getFloat(idFromBits(this.mXBits)) : intBitsToFloat(this.mXBits);
      this.mOutY = isNaNBits(this.mYBits) ? context.getFloat(idFromBits(this.mYBits)) : intBitsToFloat(this.mYBits);
      this.mOutPanX = isNaNBits(this.mPanXBits) ? context.getFloat(idFromBits(this.mPanXBits)) : intBitsToFloat(this.mPanXBits);
      this.mOutPanY = isNaNBits(this.mPanYBits) && idFromBits(this.mPanYBits) > 0 ? context.getFloat(idFromBits(this.mPanYBits)) : intBitsToFloat(this.mPanYBits);
    }
    getHorizontalOffset() {
      const scale = 1;
      const textWidth = scale * (this.mBounds[2] - this.mBounds[0]);
      const boxWidth = 0;
      return (boxWidth - textWidth) * (1 + this.mOutPanX) / 2 - scale * this.mBounds[0];
    }
    getVerticalOffset(baseline) {
      const scale = 1;
      const boxHeight = 0;
      const textHeight = scale * (this.mBounds[3] - this.mBounds[1]);
      return (boxHeight - textHeight) * (1 - this.mOutPanY) / 2 + (baseline ? textHeight / 2 : -scale * this.mBounds[1]);
    }
    paint(context) {
      const flags = (this.mFlags & _DrawTextAnchored.ANCHOR_MONOSPACE_MEASURE) !== 0 ? PaintContext.TEXT_MEASURE_MONOSPACE_WIDTH : 0;
      const str = context.getText(this.mTextID);
      if (str !== this.mLastString || (this.mFlags & _DrawTextAnchored.MEASURE_EVERY_TIME) !== 0) {
        this.mLastString = str;
        context.getTextBounds(this.mTextID, 0, -1, flags, this.mBounds);
      }
      const baseline = (this.mFlags & _DrawTextAnchored.BASELINE_RELATIVE) !== 0;
      const x = this.mOutX + this.getHorizontalOffset();
      const y = Number.isNaN(this.mOutPanY) ? this.mOutY : this.mOutY + this.getVerticalOffset(baseline);
      context.drawTextRun(
        this.mTextID,
        0,
        -1,
        0,
        1,
        x,
        y,
        (this.mFlags & _DrawTextAnchored.ANCHOR_TEXT_RTL) === 1
      );
    }
    deepToString(indent) {
      return `${indent}DrawTextAnchored(${this.mTextID})`;
    }
    static read(buffer, operations) {
      operations.push(new _DrawTextAnchored(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _DrawTextAnchored.OP_CODE = 133;
  _DrawTextAnchored.ANCHOR_TEXT_RTL = 1;
  _DrawTextAnchored.ANCHOR_MONOSPACE_MEASURE = 2;
  _DrawTextAnchored.MEASURE_EVERY_TIME = 4;
  _DrawTextAnchored.BASELINE_RELATIVE = 8;
  var DrawTextAnchored = _DrawTextAnchored;

  // third_party/remote-compose-player/src/core/operations/MatrixOperations.ts
  function listenFloat(bits, context, op) {
    if (isNaNBits(bits)) context.listensTo(idFromBits(bits), op);
  }
  function resolveFloat(bits, context) {
    return isNaNBits(bits) ? context.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
  }
  var _MatrixSave = class _MatrixSave extends Operation {
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      const pc = context.getPaintContext();
      if (pc) {
        pc.matrixSave();
        pc.savePaint();
      }
    }
    deepToString(indent) {
      return `${indent}MatrixSave`;
    }
    static read(_buffer, operations) {
      operations.push(new _MatrixSave());
    }
  };
  _MatrixSave.OP_CODE = 130;
  var MatrixSave = _MatrixSave;
  var _MatrixRestore = class _MatrixRestore extends Operation {
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      const pc = context.getPaintContext();
      if (pc) {
        pc.restorePaint();
        pc.matrixRestore();
      }
    }
    deepToString(indent) {
      return `${indent}MatrixRestore`;
    }
    static read(_buffer, operations) {
      operations.push(new _MatrixRestore());
    }
  };
  _MatrixRestore.OP_CODE = 131;
  var MatrixRestore = _MatrixRestore;
  var _MatrixTranslate = class _MatrixTranslate extends Operation {
    constructor(tx, ty) {
      super();
      this.mTranslateX = tx;
      this.mTranslateY = ty;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mTranslateX, context, this);
      listenFloat(this.mTranslateY, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.matrixTranslate(
        resolveFloat(this.mTranslateX, context),
        resolveFloat(this.mTranslateY, context)
      );
    }
    deepToString(indent) {
      return `${indent}MatrixTranslate(${this.mTranslateX}, ${this.mTranslateY})`;
    }
    static read(buffer, operations) {
      operations.push(new _MatrixTranslate(buffer.readInt(), buffer.readInt()));
    }
  };
  _MatrixTranslate.OP_CODE = 127;
  var MatrixTranslate = _MatrixTranslate;
  var _MatrixScale = class _MatrixScale extends Operation {
    constructor(sx, sy, cx, cy) {
      super();
      this.mScaleX = sx;
      this.mScaleY = sy;
      this.mCenterX = cx;
      this.mCenterY = cy;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mScaleX, context, this);
      listenFloat(this.mScaleY, context, this);
      listenFloat(this.mCenterX, context, this);
      listenFloat(this.mCenterY, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.matrixScale(
        resolveFloat(this.mScaleX, context),
        resolveFloat(this.mScaleY, context),
        resolveFloat(this.mCenterX, context),
        resolveFloat(this.mCenterY, context)
      );
    }
    deepToString(indent) {
      return `${indent}MatrixScale(${this.mScaleX}, ${this.mScaleY})`;
    }
    static read(buffer, operations) {
      operations.push(new _MatrixScale(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _MatrixScale.OP_CODE = 126;
  var MatrixScale = _MatrixScale;
  var _MatrixRotate = class _MatrixRotate extends Operation {
    constructor(angle, px, py) {
      super();
      this.mAngle = angle;
      this.mPivotX = px;
      this.mPivotY = py;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mAngle, context, this);
      listenFloat(this.mPivotX, context, this);
      listenFloat(this.mPivotY, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.matrixRotate(
        resolveFloat(this.mAngle, context),
        resolveFloat(this.mPivotX, context),
        resolveFloat(this.mPivotY, context)
      );
    }
    deepToString(indent) {
      return `${indent}MatrixRotate(${this.mAngle})`;
    }
    static read(buffer, operations) {
      operations.push(new _MatrixRotate(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _MatrixRotate.OP_CODE = 129;
  var MatrixRotate = _MatrixRotate;
  var _MatrixSkew = class _MatrixSkew extends Operation {
    constructor(sx, sy) {
      super();
      this.mSkewX = sx;
      this.mSkewY = sy;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mSkewX, context, this);
      listenFloat(this.mSkewY, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.matrixSkew(
        resolveFloat(this.mSkewX, context),
        resolveFloat(this.mSkewY, context)
      );
    }
    deepToString(indent) {
      return `${indent}MatrixSkew`;
    }
    static read(buffer, operations) {
      operations.push(new _MatrixSkew(buffer.readInt(), buffer.readInt()));
    }
  };
  _MatrixSkew.OP_CODE = 128;
  var MatrixSkew = _MatrixSkew;
  var _MatrixFromPath = class _MatrixFromPath extends Operation {
    constructor(pathId, fraction, vOffset, flags) {
      super();
      this.mPathId = pathId;
      this.mFraction = fraction;
      this.mVOffset = vOffset;
      this.mFlags = flags;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mFraction, context, this);
      listenFloat(this.mVOffset, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.matrixFromPath(
        this.mPathId,
        resolveFloat(this.mFraction, context),
        resolveFloat(this.mVOffset, context),
        this.mFlags
      );
    }
    deepToString(indent) {
      return `${indent}MatrixFromPath`;
    }
    static read(buffer, operations) {
      operations.push(new _MatrixFromPath(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _MatrixFromPath.OP_CODE = 181;
  var MatrixFromPath = _MatrixFromPath;
  var _ClipRect = class _ClipRect extends Operation {
    constructor(l, t, r, b) {
      super();
      this.mLeft = l;
      this.mTop = t;
      this.mRight = r;
      this.mBottom = b;
    }
    write(_buffer) {
    }
    registerListening(context) {
      listenFloat(this.mLeft, context, this);
      listenFloat(this.mTop, context, this);
      listenFloat(this.mRight, context, this);
      listenFloat(this.mBottom, context, this);
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.clipRect(
        resolveFloat(this.mLeft, context),
        resolveFloat(this.mTop, context),
        resolveFloat(this.mRight, context),
        resolveFloat(this.mBottom, context)
      );
    }
    deepToString(indent) {
      return `${indent}ClipRect`;
    }
    static read(buffer, operations) {
      operations.push(new _ClipRect(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _ClipRect.OP_CODE = 39;
  var ClipRect = _ClipRect;
  var _ClipPath = class _ClipPath extends Operation {
    constructor(pathId, regionOp) {
      super();
      this.mPathId = pathId;
      this.mRegionOp = regionOp;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) return;
      context.getPaintContext()?.clipPath(this.mPathId, this.mRegionOp);
    }
    deepToString(indent) {
      return `${indent}ClipPath`;
    }
    static read(buffer, operations) {
      const pack = buffer.readInt();
      const id = pack & 1048575;
      const regionOp = pack >> 24;
      operations.push(new _ClipPath(id, regionOp));
    }
  };
  _ClipPath.OP_CODE = 38;
  var ClipPath = _ClipPath;

  // third_party/remote-compose-player/src/core/operations/ColorExpression.ts
  var _ColorExpression = class _ColorExpression extends Operation {
    constructor(id, param1, param2, param3, param4) {
      super();
      this.mId = id;
      this.mMode = param1 & 255;
      this.mAlpha = param1 >> 16 & 255;
      const lit = (b) => isNaNBits(b) ? 0 : intBitsToFloat(b);
      this.mColor1 = param2;
      this.mColor2 = param3;
      this.mTweenBits = param4;
      this.mOutColor1 = param2;
      this.mOutColor2 = param3;
      this.mOutTween = lit(param4);
      this.mHueBits = 0;
      this.mSatBits = 0;
      this.mValueBits = 0;
      this.mOutHue = 0;
      this.mOutSat = 0;
      this.mOutValue = 0;
      this.mArgbAlphaBits = 0;
      this.mArgbRedBits = 0;
      this.mArgbGreenBits = 0;
      this.mArgbBlueBits = 0;
      this.mOutArgbAlpha = 0;
      this.mOutArgbRed = 0;
      this.mOutArgbGreen = 0;
      this.mOutArgbBlue = 0;
      if (this.mMode === _ColorExpression.HSV_MODE) {
        this.mHueBits = param2;
        this.mOutHue = lit(param2);
        this.mSatBits = param3;
        this.mOutSat = lit(param3);
        this.mValueBits = param4;
        this.mOutValue = lit(param4);
      } else if (this.mMode === _ColorExpression.ARGB_MODE) {
        this.mArgbAlphaBits = floatToRawIntBits((param1 >> 16) / 1024);
        this.mOutArgbAlpha = (param1 >> 16) / 1024;
        this.mArgbRedBits = param2;
        this.mOutArgbRed = lit(param2);
        this.mArgbGreenBits = param3;
        this.mOutArgbGreen = lit(param3);
        this.mArgbBlueBits = param4;
        this.mOutArgbBlue = lit(param4);
      } else if (this.mMode === _ColorExpression.IDARGB_MODE) {
        this.mArgbAlphaBits = param1 >> 16 | 4286578688 | 0;
        this.mOutArgbAlpha = lit(this.mArgbAlphaBits);
        this.mArgbRedBits = param2;
        this.mOutArgbRed = lit(param2);
        this.mArgbGreenBits = param3;
        this.mOutArgbGreen = lit(param3);
        this.mArgbBlueBits = param4;
        this.mOutArgbBlue = lit(param4);
      }
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mTweenBits)) context.listensTo(idFromBits(this.mTweenBits), this);
      if (this.mMode === _ColorExpression.HSV_MODE) {
        if (isNaNBits(this.mHueBits)) context.listensTo(idFromBits(this.mHueBits), this);
        if (isNaNBits(this.mSatBits)) context.listensTo(idFromBits(this.mSatBits), this);
        if (isNaNBits(this.mValueBits)) context.listensTo(idFromBits(this.mValueBits), this);
      }
      if (this.mMode === _ColorExpression.ARGB_MODE || this.mMode === _ColorExpression.IDARGB_MODE) {
        if (isNaNBits(this.mArgbAlphaBits)) context.listensTo(idFromBits(this.mArgbAlphaBits), this);
        if (isNaNBits(this.mArgbRedBits)) context.listensTo(idFromBits(this.mArgbRedBits), this);
        if (isNaNBits(this.mArgbGreenBits)) context.listensTo(idFromBits(this.mArgbGreenBits), this);
        if (isNaNBits(this.mArgbBlueBits)) context.listensTo(idFromBits(this.mArgbBlueBits), this);
      }
    }
    apply(context) {
      this.updateVariables(context);
      if (this.mMode === _ColorExpression.HSV_MODE) {
        context.loadColor(
          this.mId,
          this.mAlpha << 24 | 16777215 & hsvToRgb(this.mOutHue, this.mOutSat, this.mOutValue) | 0
        );
        return;
      }
      if (this.mMode === _ColorExpression.ARGB_MODE || this.mMode === _ColorExpression.IDARGB_MODE) {
        context.loadColor(
          this.mId,
          toARGB(this.mOutArgbAlpha, this.mOutArgbRed, this.mOutArgbGreen, this.mOutArgbBlue)
        );
        return;
      }
      if (this.mOutTween === 0) {
        if ((this.mMode & 1) === 1) {
          this.mOutColor1 = context.getColor(this.mColor1);
        }
        context.loadColor(this.mId, this.mOutColor1);
      } else {
        if ((this.mMode & 1) === 1) {
          this.mOutColor1 = context.getColor(this.mColor1);
        }
        if ((this.mMode & 2) === 2) {
          this.mOutColor2 = context.getColor(this.mColor2);
        }
        context.loadColor(this.mId, interpolateColor(this.mOutColor1, this.mOutColor2, this.mOutTween));
      }
    }
    updateVariables(context) {
      if (this.mMode === _ColorExpression.HSV_MODE) {
        if (isNaNBits(this.mHueBits)) {
          this.mOutHue = context.getFloat(idFromBits(this.mHueBits));
        }
        if (isNaNBits(this.mSatBits)) {
          this.mOutSat = context.getFloat(idFromBits(this.mSatBits));
        }
        if (isNaNBits(this.mValueBits)) {
          this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
        }
      }
      if (this.mMode === _ColorExpression.ARGB_MODE || this.mMode === _ColorExpression.IDARGB_MODE) {
        if (isNaNBits(this.mArgbAlphaBits)) {
          this.mOutArgbAlpha = context.getFloat(idFromBits(this.mArgbAlphaBits));
        }
        if (isNaNBits(this.mArgbRedBits)) {
          this.mOutArgbRed = context.getFloat(idFromBits(this.mArgbRedBits));
        }
        if (isNaNBits(this.mArgbGreenBits)) {
          this.mOutArgbGreen = context.getFloat(idFromBits(this.mArgbGreenBits));
        }
        if (isNaNBits(this.mArgbBlueBits)) {
          this.mOutArgbBlue = context.getFloat(idFromBits(this.mArgbBlueBits));
        }
      }
      if (isNaNBits(this.mTweenBits)) {
        this.mOutTween = context.getFloat(idFromBits(this.mTweenBits));
      }
      if ((this.mMode & 1) === 1) {
        this.mOutColor1 = context.getColor(this.mColor1);
      }
      if ((this.mMode & 2) === 2) {
        this.mOutColor2 = context.getColor(this.mColor2);
      }
    }
    deepToString(indent) {
      return `${indent}ColorExpression(${this.mId}, mode=${this.mMode})`;
    }
    static read(buffer, operations) {
      operations.push(new _ColorExpression(
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt(),
        buffer.readInt()
      ));
    }
  };
  _ColorExpression.OP_CODE = 134;
  _ColorExpression.COLOR_COLOR_INTERPOLATE = 0;
  _ColorExpression.ID_COLOR_INTERPOLATE = 1;
  _ColorExpression.COLOR_ID_INTERPOLATE = 2;
  _ColorExpression.ID_ID_INTERPOLATE = 3;
  _ColorExpression.HSV_MODE = 4;
  _ColorExpression.ARGB_MODE = 5;
  _ColorExpression.IDARGB_MODE = 6;
  var ColorExpression = _ColorExpression;

  // third_party/remote-compose-player/src/core/operations/IntegerExpression.ts
  var _IntegerExpression = class _IntegerExpression extends Operation {
    constructor(id, mask, values) {
      super();
      this.mPreCalcValues = null;
      /** mMask with the id bit cleared for every entry already resolved to a
       *  literal — the reference's mPreMask. Evaluating with the original mask
       *  makes the evaluator treat a resolved value as an id and look it up
       *  again, so `copy variable 1898` returned getInteger(1) = 0 instead of 1. */
      this.mPreMask = 0;
      this.mVar = [0, 0, 0];
      this.mId = id;
      this.mMask = mask;
      this.mValues = values;
    }
    write(_buffer) {
    }
    isId(mask, i, value) {
      return (1 << i & mask) !== 0 && value < _IntegerExpression.OFFSET;
    }
    registerListening(context) {
      for (let i = 0; i < this.mValues.length; i++) {
        if (this.isId(this.mMask, i, this.mValues[i])) {
          context.listensTo(this.mValues[i], this);
        }
      }
    }
    updateVariables(context) {
      if (!this.mPreCalcValues || this.mPreCalcValues.length !== this.mValues.length) {
        this.mPreCalcValues = new Int32Array(this.mValues.length);
      }
      this.mPreMask = this.mMask;
      for (let i = 0; i < this.mValues.length; i++) {
        if (this.isId(this.mMask, i, this.mValues[i])) {
          this.mPreMask &= ~(1 << i);
          this.mPreCalcValues[i] = context.getInteger(this.mValues[i]);
        } else {
          this.mPreCalcValues[i] = this.mValues[i];
        }
      }
    }
    apply(context) {
      this.updateVariables(context);
      const vals = this.mPreCalcValues || this.mValues;
      const result = this.evaluate(this.mPreMask, vals);
      context.loadInteger(this.mId, result);
    }
    /**
     * Evaluates the RPN program. [exp] is the *resolved* array (`mPreCalcValues`), where every id
     * slot already holds its variable's value.
     *
     * A set mask bit does NOT mean "operator" — per {@link isId} it means "not a literal", which is
     * an **id** when the *declared* value is below {@link OFFSET} and an operator otherwise. So the
     * operator test has to read `mValues`, the array as it was written: in `exp` an id slot holds
     * arbitrary variable data that may collide with the operator range, and (worse) a resolved id
     * would be run as whatever opcode its value happened to encode.
     *
     * Reading the mask alone made every variable reference a bogus operator: a two-variable
     * comparison like `IFELSE(a, b, sub(x, y))` popped operands that were never pushed, underflowed
     * the stack, and returned 0 regardless of input — which silently picked element 0 out of every
     * `TEXT_LOOKUP`/`TEXT_LOOKUP_INT` list.
     */
    evaluate(mask, exp) {
      const stack = new Int32Array(128);
      let sp = -1;
      const OFFSET3 = _IntegerExpression.OFFSET;
      for (let i = 0; i < exp.length; i++) {
        const v = exp[i];
        if ((1 << i & mask) !== 0 && this.mValues[i] >= OFFSET3) {
          const op = this.mValues[i] - OFFSET3;
          switch (op) {
            case 1:
              stack[sp - 1] = stack[sp - 1] + stack[sp] | 0;
              sp--;
              break;
            // ADD
            case 2:
              stack[sp - 1] = stack[sp - 1] - stack[sp] | 0;
              sp--;
              break;
            // SUB
            case 3:
              stack[sp - 1] = Math.imul(stack[sp - 1], stack[sp]);
              sp--;
              break;
            // MUL
            case 4:
              stack[sp - 1] = stack[sp] !== 0 ? stack[sp - 1] / stack[sp] | 0 : 0;
              sp--;
              break;
            // DIV
            case 5:
              stack[sp - 1] = stack[sp - 1] % stack[sp] | 0;
              sp--;
              break;
            // MOD
            case 6:
              stack[sp - 1] = stack[sp - 1] << stack[sp];
              sp--;
              break;
            // SHL
            case 7:
              stack[sp - 1] = stack[sp - 1] >> stack[sp];
              sp--;
              break;
            // SHR
            case 8:
              stack[sp - 1] = stack[sp - 1] >>> stack[sp];
              sp--;
              break;
            // USHR
            case 9:
              stack[sp - 1] = stack[sp - 1] | stack[sp];
              sp--;
              break;
            // OR
            case 10:
              stack[sp - 1] = stack[sp - 1] & stack[sp];
              sp--;
              break;
            // AND
            case 11:
              stack[sp - 1] = stack[sp - 1] ^ stack[sp];
              sp--;
              break;
            // XOR
            case 12:
              stack[sp - 1] = (stack[sp - 1] ^ stack[sp] >> 31) - (stack[sp] >> 31);
              sp--;
              break;
            case 13:
              stack[sp - 1] = Math.min(stack[sp - 1], stack[sp]);
              sp--;
              break;
            // MIN
            case 14:
              stack[sp - 1] = Math.max(stack[sp - 1], stack[sp]);
              sp--;
              break;
            // MAX
            case 15:
              stack[sp] = -stack[sp];
              break;
            // NEG
            case 16:
              stack[sp] = Math.abs(stack[sp]);
              break;
            // ABS
            case 17:
              stack[sp] = stack[sp] + 1 | 0;
              break;
            // INCR
            case 18:
              stack[sp] = stack[sp] - 1 | 0;
              break;
            // DECR
            case 19:
              stack[sp] = ~stack[sp];
              break;
            // NOT
            case 20:
              stack[sp] = stack[sp] >> 31 | -stack[sp] >>> 31;
              break;
            case 21:
              stack[sp - 2] = Math.min(Math.max(stack[sp - 2], stack[sp]), stack[sp - 1]);
              sp -= 2;
              break;
            case 22:
              stack[sp - 2] = stack[sp] > 0 ? stack[sp - 1] : stack[sp - 2];
              sp -= 2;
              break;
            case 23:
              stack[sp - 2] = stack[sp] + Math.imul(stack[sp - 1], stack[sp - 2]) | 0;
              sp -= 2;
              break;
            case 24:
              stack[++sp] = this.mVar[0];
              break;
            // VAR1
            case 25:
              stack[++sp] = this.mVar[1];
              break;
            // VAR2
            case 26:
              stack[++sp] = this.mVar[2];
              break;
            // VAR3
            default:
              break;
          }
        } else {
          stack[++sp] = v;
        }
      }
      return sp >= 0 ? stack[sp] : 0;
    }
    deepToString(indent) {
      return `${indent}IntegerExpression(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const mask = buffer.readInt();
      const len = buffer.readInt();
      const values = new Int32Array(len);
      for (let i = 0; i < len; i++) {
        values[i] = buffer.readInt();
      }
      operations.push(new _IntegerExpression(id, mask, values));
    }
  };
  _IntegerExpression.OP_CODE = 144;
  _IntegerExpression.OFFSET = 65536;
  var IntegerExpression = _IntegerExpression;

  // third_party/remote-compose-player/src/core/operations/IntegerConstant.ts
  var _IntegerConstant = class _IntegerConstant extends Operation {
    constructor(id, value) {
      super();
      this.mId = id;
      this.mValue = value;
    }
    update(other) {
      this.mValue = other.mValue;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadInteger(this.mId, this.mValue);
    }
    deepToString(indent) {
      return `${indent}IntegerConstant(${this.mId}, ${this.mValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _IntegerConstant(buffer.readInt(), buffer.readInt()));
    }
  };
  _IntegerConstant.OP_CODE = 140;
  var IntegerConstant = _IntegerConstant;

  // third_party/remote-compose-player/src/core/operations/BooleanConstant.ts
  var _BooleanConstant = class _BooleanConstant extends Operation {
    constructor(id, value) {
      super();
      this.mId = id;
      this.mValue = value;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}BooleanConstant(${this.mId}, ${this.mValue})`;
    }
    static read(buffer, operations) {
      operations.push(new _BooleanConstant(buffer.readInt(), buffer.readBoolean()));
    }
  };
  _BooleanConstant.OP_CODE = 143;
  var BooleanConstant = _BooleanConstant;

  // third_party/remote-compose-player/src/core/operations/LongConstant.ts
  var _LongConstant = class _LongConstant extends Operation {
    constructor(id, value) {
      super();
      this.mId = id;
      this.mValue = value;
    }
    update(other) {
      this.mValue = other.mValue;
    }
    getValue() {
      return this.mValue;
    }
    write(_buffer) {
    }
    apply(context) {
      context.putObject(this.mId, this);
    }
    deepToString(indent) {
      return `${indent}LongConstant(${this.mId})`;
    }
    static read(buffer, operations) {
      operations.push(new _LongConstant(buffer.readInt(), buffer.readLong()));
    }
  };
  _LongConstant.OP_CODE = 148;
  var LongConstant = _LongConstant;

  // third_party/remote-compose-player/src/core/operations/ShaderData.ts
  var _ShaderData = class _ShaderData extends Operation {
    constructor(shaderId, shaderTextId, floatMap, intMap, bitmapMap) {
      super();
      this.mShaderValid = false;
      this.mShaderId = shaderId;
      this.mShaderTextId = shaderTextId;
      this.mIntMap = intMap;
      this.mBitmapMap = bitmapMap;
      if (floatMap) {
        this.mUniformRawFloatMap = /* @__PURE__ */ new Map();
        this.mUniformFloatMap = /* @__PURE__ */ new Map();
        for (const [name, bits] of floatMap) {
          this.mUniformRawFloatMap.set(name, new Int32Array(bits));
          const f = new Float32Array(bits.length);
          for (let i = 0; i < bits.length; i++) f[i] = intBitsToFloat(bits[i]);
          this.mUniformFloatMap.set(name, f);
        }
      } else {
        this.mUniformRawFloatMap = null;
        this.mUniformFloatMap = null;
      }
    }
    getShaderTextId() {
      return this.mShaderTextId;
    }
    getUniformFloatNames() {
      return this.mUniformFloatMap ? Array.from(this.mUniformFloatMap.keys()) : [];
    }
    getUniformFloats(name) {
      return this.mUniformFloatMap?.get(name) ?? new Float32Array(0);
    }
    getUniformIntegerNames() {
      return this.mIntMap ? Array.from(this.mIntMap.keys()) : [];
    }
    getUniformInts(name) {
      return this.mIntMap?.get(name) ?? new Int32Array(0);
    }
    getUniformBitmapNames() {
      return this.mBitmapMap ? Array.from(this.mBitmapMap.keys()) : [];
    }
    getUniformBitmapId(name) {
      return this.mBitmapMap?.get(name) ?? -1;
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (!this.mUniformRawFloatMap) return;
      for (const bits of this.mUniformRawFloatMap.values()) {
        for (let i = 0; i < bits.length; i++) {
          if (isNaNBits(bits[i])) {
            context.listensTo(idFromBits(bits[i]), this);
          }
        }
      }
    }
    updateVariables(context) {
      if (!this.mUniformRawFloatMap || !this.mUniformFloatMap) return;
      for (const [name, rawBits] of this.mUniformRawFloatMap) {
        const out = new Float32Array(rawBits.length);
        for (let i = 0; i < rawBits.length; i++) {
          const b = rawBits[i];
          if (isNaNBits(b)) {
            const collectionsAccess = context.getCollectionsAccess();
            const dynamicValues = collectionsAccess ? collectionsAccess.getDynamicFloats(idFromBits(b)) : null;
            out[i] = dynamicValues ? intBitsToFloat(b) : context.getFloat(idFromBits(b));
          } else {
            out[i] = intBitsToFloat(b);
          }
        }
        this.mUniformFloatMap.set(name, out);
      }
    }
    markDirty() {
    }
    enable(shaderValid) {
      this.mShaderValid = shaderValid;
    }
    apply(context) {
      if (this.mShaderValid) {
        context.loadShader(this.mShaderId, this);
      }
    }
    deepToString(indent) {
      return `${indent}ShaderData(${this.mShaderId})`;
    }
    static read(buffer, operations) {
      const shaderId = buffer.readInt();
      const shaderTextId = buffer.readInt();
      const sizes = buffer.readInt();
      const floatMapSize = sizes & 255;
      const intMapSize = sizes >> 8 & 255;
      const bitmapMapSize = sizes >> 16 & 255;
      let floatMap = null;
      if (floatMapSize > 0) {
        floatMap = /* @__PURE__ */ new Map();
        for (let i = 0; i < floatMapSize; i++) {
          const name = buffer.readUTF8();
          const len = buffer.readInt();
          const val = new Int32Array(len);
          for (let j = 0; j < len; j++) {
            val[j] = buffer.readInt();
          }
          floatMap.set(name, val);
        }
      }
      let intMap = null;
      if (intMapSize > 0) {
        intMap = /* @__PURE__ */ new Map();
        for (let i = 0; i < intMapSize; i++) {
          const name = buffer.readUTF8();
          const len = buffer.readInt();
          const val = new Int32Array(len);
          for (let j = 0; j < len; j++) {
            val[j] = buffer.readInt();
          }
          intMap.set(name, val);
        }
      }
      let bitmapMap = null;
      if (bitmapMapSize > 0) {
        bitmapMap = /* @__PURE__ */ new Map();
        for (let i = 0; i < bitmapMapSize; i++) {
          const name = buffer.readUTF8();
          const val = buffer.readInt();
          bitmapMap.set(name, val);
        }
      }
      const sd = new _ShaderData(shaderId, shaderTextId, floatMap, intMap, bitmapMap);
      sd.enable(true);
      operations.push(sd);
    }
  };
  _ShaderData.OP_CODE = 45;
  var ShaderData = _ShaderData;

  // third_party/remote-compose-player/src/core/operations/utilities/StringUtils.ts
  var GROUPING_NONE = 0;
  var GROUPING_BY3 = 1;
  var GROUPING_BY4 = 2;
  var GROUPING_BY32 = 3;
  var SEPARATOR_PERIOD_COMMA = 1;
  var SEPARATOR_SPACE_COMMA = 2;
  var SEPARATOR_UNDER_PERIOD = 3;
  var NEGATIVE_PARENTHESES = 1;
  var ROUNDING = 2;
  function tochars(value, beforeDecimalPoint, afterDecimalPoint, rounding) {
    let isNegative = false;
    if (value < 0) {
      isNegative = true;
      value = -value;
    }
    let powerOf10 = 1;
    for (let i = 0; i < afterDecimalPoint; i++) {
      powerOf10 *= 10;
    }
    if (rounding) {
      let roundingFactor = 0.5;
      for (let i = 0; i < afterDecimalPoint; i++) {
        roundingFactor /= 10;
      }
      value += roundingFactor;
    }
    const integerPart = Math.trunc(value);
    const fractionalPart = value - integerPart;
    let intLength = 1;
    if (integerPart > 0) {
      let tempInt2 = integerPart;
      intLength = 0;
      while (tempInt2 > 0) {
        tempInt2 = Math.trunc(tempInt2 / 10);
        intLength++;
      }
    }
    const actualBefore = Math.min(beforeDecimalPoint, intLength);
    const integerChars = new Array(actualBefore);
    let tempInt = integerPart;
    for (let i = actualBefore - 1; i >= 0; i--) {
      integerChars[i] = String.fromCharCode(48 + tempInt % 10);
      tempInt = Math.trunc(tempInt / 10);
    }
    let tempFrac = Math.trunc(fractionalPart * powerOf10);
    let fracLength = 0;
    if (afterDecimalPoint > 0) {
      let temp = tempFrac;
      if (temp === 0) {
        fracLength = 1;
      } else {
        while (temp > 0 && temp % 10 === 0) {
          temp = Math.trunc(temp / 10);
        }
        if (temp > 0) {
          let t = temp;
          while (t > 0) {
            t = Math.trunc(t / 10);
            fracLength++;
          }
        } else {
          fracLength = 0;
        }
      }
    }
    const actualAfter = Math.min(afterDecimalPoint, fracLength);
    const fractionalChars = new Array(actualAfter);
    tempFrac = Math.trunc(fractionalPart * powerOf10);
    for (let i = actualAfter - 1; i >= 0; i--) {
      fractionalChars[i] = String.fromCharCode(48 + tempFrac % 10);
      tempFrac = Math.trunc(tempFrac / 10);
    }
    let result = "";
    if (isNegative) result += "-";
    result += integerChars.join("");
    result += ".";
    result += fractionalChars.join("");
    return result;
  }
  function floatToStringLegacy(value, beforeDecimalPoint, afterDecimalPoint, pre, post) {
    let isNeg = value < 0;
    if (isNeg) value = -value;
    const integerPart = Math.trunc(value);
    let fractionalPart = value % 1;
    let integerPartString = String(integerPart);
    const iLen = integerPartString.length;
    if (iLen < beforeDecimalPoint) {
      if (pre !== 0) {
        const pad = String.fromCharCode(pre).repeat(beforeDecimalPoint - iLen);
        integerPartString = pad + integerPartString;
      }
    } else if (iLen > beforeDecimalPoint) {
      integerPartString = integerPartString.substring(iLen - beforeDecimalPoint);
    }
    if (afterDecimalPoint === 0) {
      return (isNeg ? "-" : "") + integerPartString;
    }
    for (let i = 0; i < afterDecimalPoint; i++) {
      fractionalPart *= 10;
    }
    fractionalPart = Math.round(fractionalPart);
    for (let i = 0; i < afterDecimalPoint; i++) {
      fractionalPart *= 0.1;
    }
    let fact = fractionalPart.toString();
    const dotIdx = fact.indexOf(".");
    if (dotIdx >= 0) {
      fact = fact.substring(dotIdx + 1, Math.min(fact.length, dotIdx + 1 + afterDecimalPoint));
    } else {
      fact = "0";
    }
    let trim = fact.length;
    for (let i = fact.length - 1; i >= 0; i--) {
      if (fact[i] !== "0") break;
      trim--;
    }
    if (trim !== fact.length) {
      fact = fact.substring(0, trim);
    }
    if (post !== 0 && fact.length < afterDecimalPoint) {
      fact = fact + String.fromCharCode(post).repeat(afterDecimalPoint - fact.length);
    }
    return (isNeg ? "-" : "") + integerPartString + "." + fact;
  }
  function floatToStringFull(value, beforeDecimalPoint, afterDecimalPoint, pre, post, separator, grouping, options) {
    let groupSep = ",";
    let decSep = ".";
    switch (separator) {
      case SEPARATOR_PERIOD_COMMA:
        groupSep = ".";
        decSep = ",";
        break;
      case SEPARATOR_SPACE_COMMA:
        groupSep = " ";
        decSep = ",";
        break;
      case SEPARATOR_UNDER_PERIOD:
        groupSep = "_";
        decSep = ".";
        break;
    }
    const useParenthesesForNeg = (options & NEGATIVE_PARENTHESES) !== 0;
    const rounding = (options & ROUNDING) !== 0;
    const isNeg = value < 0;
    if (isNeg) value = -value;
    const chars = tochars(value, beforeDecimalPoint, afterDecimalPoint, rounding);
    let fractionalPart = value % 1;
    const dotPos = chars.indexOf(".");
    let integerPartString = chars.substring(chars.startsWith("-") ? 1 : 0, dotPos);
    if (grouping !== GROUPING_NONE) {
      const len = integerPartString.length;
      switch (grouping) {
        case GROUPING_BY3:
          for (let i = len - 3; i > 0; i -= 3) {
            integerPartString = integerPartString.substring(0, i) + groupSep + integerPartString.substring(i);
          }
          break;
        case GROUPING_BY4:
          for (let i = len - 4; i > 0; i -= 4) {
            integerPartString = integerPartString.substring(0, i) + groupSep + integerPartString.substring(i);
          }
          break;
        case GROUPING_BY32:
          for (let i = len - 3; i > 0; i -= 2) {
            integerPartString = integerPartString.substring(0, i) + groupSep + integerPartString.substring(i);
          }
          break;
      }
    }
    const iLen = integerPartString.length;
    if (iLen < beforeDecimalPoint) {
      if (pre !== 0) {
        const pad = String.fromCharCode(pre).repeat(beforeDecimalPoint - iLen);
        integerPartString = pad + integerPartString;
      }
    } else if (iLen > beforeDecimalPoint) {
      integerPartString = integerPartString.substring(iLen - beforeDecimalPoint);
    }
    if (afterDecimalPoint === 0) {
      if (!isNeg) return integerPartString;
      if (useParenthesesForNeg) return "(" + integerPartString + ")";
      return "-" + integerPartString;
    }
    for (let i = 0; i < afterDecimalPoint; i++) {
      fractionalPart *= 10;
    }
    fractionalPart = Math.round(fractionalPart);
    for (let i = 0; i < afterDecimalPoint; i++) {
      fractionalPart *= 0.1;
    }
    let fact = fractionalPart.toString();
    const factDotIdx = fact.indexOf(".");
    if (factDotIdx >= 0) {
      fact = fact.substring(factDotIdx + 1, Math.min(fact.length, factDotIdx + 1 + afterDecimalPoint));
    } else {
      fact = "0";
    }
    let trim = fact.length;
    for (let i = fact.length - 1; i > 0; i--) {
      if (fact[i] !== "0") break;
      trim--;
    }
    if (trim !== fact.length) {
      fact = fact.substring(0, trim);
    }
    if (post !== 0 && fact.length < afterDecimalPoint) {
      fact = fact + String.fromCharCode(post).repeat(afterDecimalPoint - fact.length);
    }
    if (!isNeg) return integerPartString + decSep + fact;
    if (useParenthesesForNeg) return "(" + integerPartString + decSep + fact + ")";
    return "-" + integerPartString + decSep + fact;
  }

  // third_party/remote-compose-player/src/core/operations/TextFromFloat.ts
  var PAD_AFTER_NONE = 1;
  var PAD_AFTER_ZERO = 3;
  var PAD_PRE_NONE = 4;
  var PAD_PRE_ZERO = 12;
  var GROUPING_BY33 = 1 << 4;
  var GROUPING_BY42 = 2 << 4;
  var GROUPING_BY322 = 3 << 4;
  var SEPARATOR_PERIOD_COMMA2 = 1 << 6;
  var SEPARATOR_SPACE_COMMA2 = 2 << 6;
  var SEPARATOR_UNDER_PERIOD2 = 3 << 6;
  var OPTIONS_NEGATIVE_PARENTHESES = 1 << 8;
  var OPTIONS_ROUNDING = 2 << 8;
  var LEGACY_MODE = 1 << 10;
  var _TextFromFloat = class _TextFromFloat extends Operation {
    constructor(textId, valueBits, digitsBefore, digitsAfter, flags) {
      super();
      this.mTextId = textId;
      this.mValueBits = valueBits;
      this.mOutValue = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
      this.mDigitsBefore = digitsBefore;
      this.mDigitsAfter = digitsAfter;
      this.mFlags = flags;
      switch (flags & 3) {
        case PAD_AFTER_NONE:
          this.mAfter = 0;
          break;
        case PAD_AFTER_ZERO:
          this.mAfter = 48;
          break;
        // '0'
        default:
          this.mAfter = 32;
          break;
      }
      switch (flags & 3 << 2) {
        case PAD_PRE_NONE:
          this.mPre = 0;
          break;
        case PAD_PRE_ZERO:
          this.mPre = 48;
          break;
        // '0'
        default:
          this.mPre = 32;
          break;
      }
      this.mGroup = 0;
      switch (flags & 3 << 4) {
        case GROUPING_BY33:
          this.mGroup = 1;
          break;
        case GROUPING_BY42:
          this.mGroup = 2;
          break;
        case GROUPING_BY322:
          this.mGroup = 3;
          break;
      }
      this.mSeparator = 0;
      switch (flags & 3 << 6) {
        case SEPARATOR_PERIOD_COMMA2:
          this.mSeparator = 1;
          break;
        case SEPARATOR_SPACE_COMMA2:
          this.mSeparator = 2;
          break;
        case SEPARATOR_UNDER_PERIOD2:
          this.mSeparator = 3;
          break;
      }
      this.mOptions = 0;
      if ((flags & OPTIONS_ROUNDING) !== 0) this.mOptions |= 2;
      if ((flags & OPTIONS_NEGATIVE_PARENTHESES) !== 0) this.mOptions |= 1;
      this.mLegacy = (flags & LEGACY_MODE) !== 0;
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mValueBits)) context.listensTo(idFromBits(this.mValueBits), this);
    }
    updateVariables(context) {
      if (isNaNBits(this.mValueBits)) {
        this.mOutValue = context.getFloat(idFromBits(this.mValueBits));
      }
    }
    apply(context) {
      this.updateVariables(context);
      const v = this.mOutValue;
      let s;
      if (this.mLegacy) {
        s = floatToStringLegacy(v, this.mDigitsBefore, this.mDigitsAfter, this.mPre, this.mAfter);
      } else {
        s = floatToStringFull(
          v,
          this.mDigitsBefore,
          this.mDigitsAfter,
          this.mPre,
          this.mAfter,
          this.mSeparator,
          this.mGroup,
          this.mOptions
        );
      }
      context.loadText(this.mTextId, s);
    }
    deepToString(indent) {
      return `${indent}TextFromFloat[${this.mTextId}] = ${isNaNBits(this.mValueBits) ? "ref(" + idFromBits(this.mValueBits) + ")" : intBitsToFloat(this.mValueBits)} ${this.mDigitsBefore}.${this.mDigitsAfter} ${this.mFlags}`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const valueBits = buffer.readInt();
      const tmp = buffer.readInt();
      const digitsAfter = tmp & 65535;
      const digitsBefore = tmp >> 16 & 65535;
      const flags = buffer.readInt();
      operations.push(new _TextFromFloat(textId, valueBits, digitsBefore, digitsAfter, flags));
    }
  };
  _TextFromFloat.OP_CODE = 135;
  var TextFromFloat = _TextFromFloat;

  // third_party/remote-compose-player/src/core/operations/TextMerge.ts
  var _TextMerge = class _TextMerge extends Operation {
    constructor(textId, srcId1, srcId2) {
      super();
      this.mTextId = textId;
      this.mSrcId1 = srcId1;
      this.mSrcId2 = srcId2;
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mSrcId1, this);
      context.listensTo(this.mSrcId2, this);
    }
    updateVariables(context) {
      this.apply(context);
    }
    apply(context) {
      const s1 = context.getText(this.mSrcId1) ?? "";
      const s2 = context.getText(this.mSrcId2) ?? "";
      context.loadText(this.mTextId, s1 + s2);
    }
    deepToString(indent) {
      return `${indent}TextMerge(${this.mTextId})`;
    }
    static read(buffer, operations) {
      operations.push(new _TextMerge(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
  };
  _TextMerge.OP_CODE = 136;
  var TextMerge = _TextMerge;

  // third_party/remote-compose-player/src/core/operations/ComponentValue.ts
  var _ComponentValue = class _ComponentValue extends Operation {
    constructor(type, componentId, valueId) {
      super();
      this.mType = type;
      this.mComponentId = componentId;
      this.mValueId = valueId;
    }
    getType() {
      return this.mType;
    }
    getComponentId2() {
      return this.mComponentId;
    }
    getValueId() {
      return this.mValueId;
    }
    write(_buffer) {
    }
    apply(context) {
      const doc = context.getDocument();
      if (!doc) return;
      const component = doc.getComponent(this.mComponentId);
      if (!component) {
        if (this.mType === _ComponentValue.WIDTH) {
          context.loadFloat(this.mValueId, doc.getWidth());
        } else if (this.mType === _ComponentValue.HEIGHT) {
          context.loadFloat(this.mValueId, doc.getHeight());
        }
        return;
      }
      const lc = component;
      switch (this.mType) {
        case _ComponentValue.WIDTH:
          context.loadFloat(this.mValueId, lc.getWidth ? lc.getWidth() : doc.getWidth());
          break;
        case _ComponentValue.HEIGHT:
          context.loadFloat(this.mValueId, lc.getHeight ? lc.getHeight() : doc.getHeight());
          break;
        case _ComponentValue.POS_X:
          context.loadFloat(this.mValueId, lc.getX ? lc.getX() : 0);
          break;
        case _ComponentValue.POS_Y:
          context.loadFloat(this.mValueId, lc.getY ? lc.getY() : 0);
          break;
        case _ComponentValue.POS_ROOT_X: {
          const loc = lc.getLocationInWindow ? lc.getLocationInWindow() : [0, 0];
          context.loadFloat(this.mValueId, loc[0]);
          break;
        }
        case _ComponentValue.POS_ROOT_Y: {
          const loc = lc.getLocationInWindow ? lc.getLocationInWindow() : [0, 0];
          context.loadFloat(this.mValueId, loc[1]);
          break;
        }
        case _ComponentValue.CONTENT_WIDTH:
          context.loadFloat(this.mValueId, lc.getContentWidth ? lc.getContentWidth() : lc.getWidth ? lc.getWidth() : 0);
          break;
        case _ComponentValue.CONTENT_HEIGHT:
          context.loadFloat(this.mValueId, lc.getContentHeight ? lc.getContentHeight() : lc.getHeight ? lc.getHeight() : 0);
          break;
        default:
          context.loadFloat(this.mValueId, 0);
          break;
      }
    }
    deepToString(indent) {
      return `${indent}ComponentValue(${this.mType}, ${this.mComponentId}, ${this.mValueId})`;
    }
    static read(buffer, operations) {
      const type = buffer.readInt();
      const componentId = buffer.readInt();
      const valueId = buffer.readInt();
      operations.push(new _ComponentValue(type, componentId, valueId));
    }
  };
  _ComponentValue.OP_CODE = 150;
  _ComponentValue.WIDTH = 0;
  _ComponentValue.HEIGHT = 1;
  _ComponentValue.POS_X = 2;
  _ComponentValue.POS_Y = 3;
  _ComponentValue.POS_ROOT_X = 4;
  _ComponentValue.POS_ROOT_Y = 5;
  _ComponentValue.CONTENT_WIDTH = 6;
  _ComponentValue.CONTENT_HEIGHT = 7;
  var ComponentValue = _ComponentValue;

  // third_party/remote-compose-player/src/core/operations/DataMapIds.ts
  var DataMap = class {
    constructor(names, types, ids) {
      this.mNames = names;
      this.mTypes = types;
      this.mIds = ids;
    }
    getPos(name) {
      return this.mNames.indexOf(name);
    }
  };
  var _DataMapIds = class _DataMapIds extends Operation {
    constructor(id, dataMap) {
      super();
      this.mId = id;
      this.mDataMap = dataMap;
    }
    write(_buffer) {
    }
    apply(context) {
      context.putDataMap(this.mId, this.mDataMap);
    }
    deepToString(indent) {
      return `${indent}DataMapIds(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const len = buffer.readInt();
      const names = new Array(len);
      const types = new Int8Array(len);
      const ids = new Int32Array(len);
      for (let i = 0; i < len; i++) {
        names[i] = buffer.readUTF8();
        types[i] = buffer.readByte();
        ids[i] = buffer.readInt();
      }
      operations.push(new _DataMapIds(id, new DataMap(names, types, ids)));
    }
  };
  _DataMapIds.OP_CODE = 145;
  var DataMapIds = _DataMapIds;

  // third_party/remote-compose-player/src/core/operations/DataListIds.ts
  var _DataListIds = class _DataListIds extends Operation {
    constructor(id, ids) {
      super();
      this.mId = id;
      this.mIds = ids;
    }
    getCollectionId() {
      return this.mId;
    }
    getLength() {
      return this.mIds.length;
    }
    getId(index) {
      return this.mIds[index];
    }
    write(_buffer) {
    }
    apply(context) {
      context.addCollection(this.mId, { ids: this.mIds, getLength: () => this.mIds.length, getFloatValue: (i) => this.mIds[i], getId: (i) => this.mIds[i] });
    }
    deepToString(indent) {
      return `${indent}DataListIds(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const count = buffer.readInt();
      const ids = new Int32Array(count);
      for (let i = 0; i < count; i++) {
        ids[i] = buffer.readInt();
      }
      operations.push(new _DataListIds(id, ids));
    }
  };
  _DataListIds.OP_CODE = 146;
  var DataListIds = _DataListIds;

  // third_party/remote-compose-player/src/core/operations/DataListFloat.ts
  var _DataListFloat = class _DataListFloat extends Operation {
    constructor(id, bits) {
      super();
      this.mId = id;
      this.mBits = bits;
      this.mValues = new Float32Array(bits.length);
      for (let i = 0; i < bits.length; i++) this.mValues[i] = intBitsToFloat(bits[i]);
    }
    update(other) {
      this.mBits = other.mBits;
      this.mValues = other.mValues;
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.addCollection(this.mId, {
        values: this.mValues,
        getLength: () => this.mValues.length,
        getFloatValue: (i) => this.mValues[i],
        getFloats: () => this.mValues
      });
      for (const b of this.mBits) {
        if (isVariableBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(_context) {
    }
    apply(context) {
      context.addCollection(this.mId, {
        values: this.mValues,
        getLength: () => this.mValues.length,
        getFloatValue: (i) => this.mValues[i],
        getFloats: () => this.mValues
      });
    }
    deepToString(indent) {
      return `${indent}DataListFloat(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const count = buffer.readInt();
      const bits = new Int32Array(count);
      for (let i = 0; i < count; i++) {
        bits[i] = buffer.readInt();
      }
      operations.push(new _DataListFloat(id, bits));
    }
  };
  _DataListFloat.OP_CODE = 147;
  var DataListFloat = _DataListFloat;

  // third_party/remote-compose-player/src/core/operations/layout/LayoutComponentContent.ts
  var _LayoutComponentContent = class _LayoutComponentContent extends Component {
    constructor(componentId) {
      super(componentId);
    }
    write(buffer) {
      buffer.start(_LayoutComponentContent.OP_CODE);
      buffer.writeInt(this.getComponentId());
    }
    // A content wrapper isn't measured on its own — the enclosing LayoutComponent
    // flattens its children up and measures itself. But `ComponentValue`s (e.g. a
    // button/card fill sized from its content region) reference this wrapper by id,
    // so an unmeasured 0×0 wrapper makes those bindings resolve to zero. Report the
    // parent's measured size when this wrapper has none of its own.
    getWidth() {
      const w = super.getWidth();
      if (w === 0 && this.getParent()) return this.getParent().getWidth();
      return w;
    }
    getHeight() {
      const h = super.getHeight();
      if (h === 0 && this.getParent()) return this.getParent().getHeight();
      return h;
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}LayoutComponentContent(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      operations.push(new _LayoutComponentContent(componentId));
    }
  };
  _LayoutComponentContent.OP_CODE = 201;
  var LayoutComponentContent = _LayoutComponentContent;

  // third_party/remote-compose-player/src/core/operations/layout/CanvasContent.ts
  var _CanvasContent = class _CanvasContent extends Component {
    constructor(componentId) {
      super(componentId);
    }
    write(buffer) {
      buffer.start(_CanvasContent.OP_CODE);
      buffer.writeInt(this.getComponentId());
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}CanvasContent(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      operations.push(new _CanvasContent(componentId));
    }
  };
  _CanvasContent.OP_CODE = 207;
  var CanvasContent = _CanvasContent;

  // third_party/remote-compose-player/src/core/operations/DataDynamicListFloat.ts
  var _DataDynamicListFloat = class _DataDynamicListFloat extends Operation {
    constructor(id, nbValuesBits) {
      super();
      this.isDynamic = true;
      this.mId = id;
      const nbValues = isNaNBits(nbValuesBits) ? NaN : intBitsToFloat(nbValuesBits);
      if (nbValues > _DataDynamicListFloat.MAX_FLOAT_ARRAY) {
        throw new Error("Array too large");
      }
      this.mValues = new Float32Array(Math.floor(nbValues) || 0);
      this.mArrayLengthBits = nbValuesBits;
      this.mArrayLengthOut = nbValues;
    }
    updateVariables(context) {
      if (isNaNBits(this.mArrayLengthBits)) {
        this.mArrayLengthOut = context.getFloat(idFromBits(this.mArrayLengthBits));
      }
      if (Math.floor(this.mArrayLengthOut) !== this.mValues.length) {
        this.mValues = new Float32Array(Math.floor(this.mArrayLengthOut) || 0);
      }
    }
    registerListening(context) {
      context.addCollection(this.mId, this);
      if (isNaNBits(this.mArrayLengthBits)) {
        context.listensTo(idFromBits(this.mArrayLengthBits), this);
      }
    }
    apply(context) {
      context.addCollection(this.mId, this);
    }
    getFloatValue(index) {
      return this.mValues[index];
    }
    getFloats() {
      return this.mValues;
    }
    getLength() {
      return this.mValues.length;
    }
    updateValues(values) {
      if (values.length !== this.mValues.length) {
        this.mValues = new Float32Array(values.length);
      }
      this.mValues.set(values);
    }
    write(buffer) {
      buffer.start(_DataDynamicListFloat.OP_CODE);
      buffer.writeInt(this.mId);
      buffer.writeFloat(this.mValues.length);
    }
    deepToString(indent) {
      return `${indent}DataDynamicListFloat(id=${this.mId}, len=${this.mValues.length})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const lenBits = buffer.readInt();
      const len = isNaNBits(lenBits) ? NaN : intBitsToFloat(lenBits);
      if (len > _DataDynamicListFloat.MAX_FLOAT_ARRAY) {
        throw new Error(`${len} entries exceeds max = ${_DataDynamicListFloat.MAX_FLOAT_ARRAY}`);
      }
      operations.push(new _DataDynamicListFloat(id, lenBits));
    }
  };
  _DataDynamicListFloat.OP_CODE = 197;
  _DataDynamicListFloat.MAX_FLOAT_ARRAY = 2e3;
  var DataDynamicListFloat = _DataDynamicListFloat;

  // third_party/remote-compose-player/src/core/operations/layout/modifiers/LayoutComputeOperation.ts
  var _LayoutComputeOperation = class _LayoutComputeOperation extends Operation {
    constructor(type, boundsId, animateChanges) {
      super();
      this.mList = [];
      this.mParent = null;
      this.mBounds = new Float32Array(6);
      this.mType = type;
      this.mBoundsId = boundsId;
      this.mAnimateChanges = animateChanges;
    }
    getList() {
      return this.mList;
    }
    getType() {
      return this.mType;
    }
    setParent(parent) {
      this.mParent = parent;
    }
    registerListening(context) {
      for (const op of this.mList) {
        if (typeof op.registerListening === "function") {
          op.registerListening(context);
        }
      }
    }
    updateVariables(context) {
      let needsInvalidate = false;
      for (const op of this.mList) {
        if (typeof op.updateVariables === "function" && op.isDirty()) {
          op.updateVariables(context);
          op.apply(context);
          op.markNotDirty();
          needsInvalidate = true;
        }
      }
      if (needsInvalidate && this.mParent && typeof this.mParent.invalidateMeasure === "function") {
        this.mParent.invalidateMeasure();
      }
    }
    markDirty() {
    }
    apply(_context) {
    }
    applyToMeasure(context, m, parent) {
      const collectionsAccess = context.getContext().getCollectionsAccess();
      if (!collectionsAccess) return false;
      const array = collectionsAccess.getArray(this.mBoundsId);
      if (!array) return false;
      this.mBounds[0] = m.getX();
      this.mBounds[1] = m.getY();
      this.mBounds[2] = m.getW();
      this.mBounds[3] = m.getH();
      this.mBounds[4] = parent.getW();
      this.mBounds[5] = parent.getH();
      if (array instanceof DataDynamicListFloat) {
        array.updateValues(this.mBounds);
      }
      for (const operation of this.mList) {
        if (typeof operation.updateVariables === "function" && operation.isDirty()) {
          operation.updateVariables(context.getContext());
        }
        operation.apply(context.getContext());
      }
      const bounds = array.getFloats?.() ?? null;
      if (!bounds) return false;
      switch (this.mType) {
        case _LayoutComputeOperation.TYPE_MEASURE:
          m.setW(bounds[2]);
          m.setH(bounds[3]);
          break;
        case _LayoutComputeOperation.TYPE_POSITION:
          m.setX(bounds[0]);
          m.setY(bounds[1]);
          break;
        default:
          m.setX(bounds[0]);
          m.setY(bounds[1]);
          m.setW(bounds[2]);
          m.setH(bounds[3]);
      }
      m.setAllowsAnimation(this.mAnimateChanges);
      const positionChanged = bounds[0] !== this.mBounds[0] || bounds[1] !== this.mBounds[1];
      const dimensionChanged = bounds[2] !== this.mBounds[2] || bounds[3] !== this.mBounds[3];
      if (this.mType === _LayoutComputeOperation.TYPE_MEASURE && dimensionChanged) return true;
      if (this.mType === _LayoutComputeOperation.TYPE_POSITION && positionChanged) return true;
      return dimensionChanged || positionChanged;
    }
    write(buffer) {
      buffer.start(_LayoutComputeOperation.OP_CODE);
      buffer.writeInt(this.mType);
      buffer.writeInt(this.mBoundsId);
      buffer.writeBoolean(this.mAnimateChanges);
    }
    deepToString(indent) {
      return `${indent}LayoutComputeOperation(type=${this.mType}, boundsId=${this.mBoundsId})`;
    }
    static read(buffer, operations) {
      const type = buffer.readInt();
      const boundsId = buffer.readInt();
      const animateChanges = buffer.readBoolean();
      operations.push(new _LayoutComputeOperation(type, boundsId, animateChanges));
    }
  };
  _LayoutComputeOperation.OP_CODE = 238;
  _LayoutComputeOperation.TYPE_MEASURE = 0;
  _LayoutComputeOperation.TYPE_POSITION = 1;
  var LayoutComputeOperation = _LayoutComputeOperation;

  // third_party/remote-compose-player/src/core/operations/layout/CanvasOperations.ts
  var _CanvasOperations = class _CanvasOperations extends PaintOperation {
    constructor() {
      super();
      // Child drawing commands, collected during inflation (see getList()).
      this.mList = [];
    }
    // Exposing getList() marks this as a Container: CoreDocument.inflateComponents
    // redirects the ops between this one and its ContainerEnd into mList, and the
    // DATA pass recurses through it so child data/variables load.
    getList() {
      return this.mList;
    }
    write(buffer) {
      buffer.start(_CanvasOperations.OP_CODE);
    }
    paint(context) {
      const remoteContext = context.getContext();
      for (const op of this.mList) {
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.updateVariables(remoteContext);
        }
        remoteContext.incrementOpCount();
        op.apply(remoteContext);
      }
    }
    deepToString(indent) {
      const inner = this.mList.map((op) => op.deepToString(indent + "  ")).join("\n");
      return `${indent}CanvasOperations
${inner}`;
    }
    static read(_buffer, operations) {
      operations.push(new _CanvasOperations());
    }
  };
  _CanvasOperations.OP_CODE = 173;
  var CanvasOperations = _CanvasOperations;

  // third_party/remote-compose-player/src/core/operations/layout/LayoutComponent.ts
  var LayoutComponent = class _LayoutComponent extends Component {
    constructor() {
      super(...arguments);
      // Extracted modifiers
      this.mWidthMod = null;
      this.mHeightMod = null;
      // Min/max constraints reach a component two ways: the older WidthIn/HeightIn ops
      // (231/232) and DimensionConstraints (243), which is what `requiredWidthIn` and
      // `requiredHeightIn` compile to. Both expose getMin()/getMax(), and the measure
      // pass only needs that, so store either.
      this.mWidthInMod = null;
      this.mHeightInMod = null;
      this.mZIndexMod = null;
      this.mGraphicsLayerMod = null;
      // Padding
      this.mPaddingLeft = 0;
      // Padding declared *before* the size modifier, which is the only padding that adds
      // to an explicit width/height. The reference walks the modifier list and stops at
      // the size modifier (LayoutComponent.computeModifierDefinedWidth), so
      // `.width(200).padding(30)` is 200 wide with 140 of content, while
      // `.padding(30).width(200)` is 260 wide — exactly Compose's modifier-order rule.
      this.mPadBeforeWidth = 0;
      this.mPadBeforeHeight = 0;
      // The modifiers those two totals are summed from. Kept as references rather than as
      // the sums alone because `inflate()` runs before `PaddingModifier.updateVariables`
      // has resolved variables or applied DP density scaling — the same staleness
      // `refreshPadding()` exists to undo for the *total* padding, which has to reach the
      // ordered totals too now that they feed the measured size. Which side of the size
      // modifier each one falls on is fixed by the modifier list, so only the values need
      // re-reading, not the partition.
      this.mPadBeforeWidthMods = [];
      this.mPadBeforeHeightMods = [];
      this.mPaddingRight = 0;
      this.mPaddingTop = 0;
      this.mPaddingBottom = 0;
      // Children components extracted during inflate
      this.mChildrenComponents = [];
      // Draw content operations (DrawContentModifier pattern)
      this.mDrawContentOperations = null;
      // Component modifier operations (non-structural modifiers that need paint)
      this.mComponentModifiers = [];
      // Content operations (paint ops from LayoutComponentContent/CanvasContent)
      this.mContentOps = [];
      // Has CanvasLayout-style content
      this.mHasCanvasLayoutContent = false;
      // Computed layout modifiers (LayoutComputeOperation)
      this.mComputedLayoutModifiers = null;
      // ComponentValue bindings (Java ComponentData pattern)
      // Collected during inflate; updated during measure to expose dimensions as float variables
      this.mComponentValues = null;
      // Scroll measurement state
      this.mScrollModifier = null;
      this.mScrollContentDimension = 0;
      this.mScrollHostDimension = 0;
    }
    getWidthModifier() {
      return this.mWidthMod;
    }
    getHeightModifier() {
      return this.mHeightMod;
    }
    getWidthInModifier() {
      return this.mWidthInMod;
    }
    getHeightInModifier() {
      return this.mHeightInMod;
    }
    getScrollModifier() {
      return this.mScrollModifier;
    }
    /** Choose which of two competing width/height modifiers a component keeps.
     *  An EXACT / EXACT_DP fixed-size constraint takes precedence over a FILL/WRAP
     *  (Compose composes `size().fillMaxSize()` so the fixed size wins); otherwise
     *  the later modifier wins, preserving the previous last-writer behaviour.
     *
     *  When *both* are fixed the earlier one wins, because the modifier chain is emitted
     *  outermost-first and Compose resolves `size(48).size(24)` to 48 — the outer call fixes the
     *  constraints and the inner one is coerced into them. A widget that appends its own default
     *  size behind the caller's is the common case (`RemoteIcon(modifier = size(48.dp))` carries a
     *  24 dp default), so last-wins silently rendered every such icon at the default rather than
     *  the size the document asked for. */
    static preferExactSize(current, next) {
      const isExact = (t) => t === WidthModifier.EXACT || t === WidthModifier.EXACT_DP;
      if (current && isExact(current.getType())) {
        return current;
      }
      return next;
    }
    inflate() {
      this.mChildrenComponents = [];
      this.mComponentModifiers = [];
      this.mContentOps = [];
      this.mPaddingLeft = 0;
      this.mPaddingRight = 0;
      this.mPaddingTop = 0;
      this.mPaddingBottom = 0;
      this.mPadBeforeWidth = 0;
      this.mPadBeforeHeight = 0;
      this.mPadBeforeWidthMods = [];
      this.mPadBeforeHeightMods = [];
      let sawWidth = false;
      let sawHeight = false;
      this.mComputedLayoutModifiers = null;
      for (const op of this.getList()) {
        if (op instanceof PaddingModifier) {
          this.mPaddingLeft += op.mLeftValue;
          this.mPaddingTop += op.mTopValue;
          this.mPaddingRight += op.mRightValue;
          this.mPaddingBottom += op.mBottomValue;
          if (!sawWidth) {
            this.mPadBeforeWidth += op.mLeftValue + op.mRightValue;
            this.mPadBeforeWidthMods.push(op);
          }
          if (!sawHeight) {
            this.mPadBeforeHeight += op.mTopValue + op.mBottomValue;
            this.mPadBeforeHeightMods.push(op);
          }
          this.mComponentModifiers.push(op);
        } else if (op instanceof WidthModifier) {
          this.mWidthMod = _LayoutComponent.preferExactSize(this.mWidthMod, op);
          sawWidth = true;
        } else if (op instanceof HeightModifier) {
          this.mHeightMod = _LayoutComponent.preferExactSize(this.mHeightMod, op);
          sawHeight = true;
        } else if (op instanceof WidthInModifier) {
          this.mWidthInMod = op;
        } else if (op instanceof HeightInModifier) {
          this.mHeightInMod = op;
        } else if (op instanceof DimensionConstraintsModifier) {
          const t = op.getType();
          if (t === DimensionConstraintsModifier.HORIZONTAL || t === DimensionConstraintsModifier.REQUIRED_HORIZONTAL) {
            this.mWidthInMod = op;
          } else if (t === DimensionConstraintsModifier.VERTICAL || t === DimensionConstraintsModifier.REQUIRED_VERTICAL) {
            this.mHeightInMod = op;
          }
        } else if (op instanceof ZIndexModifier) {
          this.mZIndexMod = op;
          this.mZIndex = op.mValue ?? 0;
        } else if (op instanceof GraphicsLayerModifier) {
          this.mGraphicsLayerMod = op;
        } else if (op instanceof VisibilityModifier) {
          op.setParent(this);
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
          this.mDrawContentOperations = [];
        } else if (op instanceof LayoutComponentContent || op instanceof CanvasContent) {
          op.setParent(this);
          for (const contentOp of op.getList()) {
            if (contentOp instanceof Component) {
              contentOp.setParent(this);
              this.mChildrenComponents.push(contentOp);
              if (contentOp instanceof _LayoutComponent) {
                contentOp.inflate();
              }
              if (contentOp instanceof CanvasContent) {
                this.mHasCanvasLayoutContent = true;
              }
            } else if (contentOp instanceof ComponentValue) {
              if (this.mComponentValues === null) {
                this.mComponentValues = [];
              }
              this.mComponentValues.push(contentOp);
            } else {
              if (typeof contentOp.setComponent === "function") {
                contentOp.setComponent(this);
              }
              this.mContentOps.push(contentOp);
              this.hoistNestedComponentValues(contentOp);
            }
          }
          if (op instanceof CanvasContent) {
            this.mHasCanvasLayoutContent = true;
          }
        } else if (op instanceof Component) {
          op.setParent(this);
          this.mChildrenComponents.push(op);
          if (op instanceof _LayoutComponent) {
            op.inflate();
          }
        } else {
          if (this.mDrawContentOperations !== null) {
            this.mDrawContentOperations.push(op);
          } else {
            this.mContentOps.push(op);
          }
          this.hoistNestedComponentValues(op);
        }
      }
      if (!this.mWidthMod) {
        this.mWidthMod = new WidthModifier(WidthModifier.WRAP, 0);
      }
      if (!this.mHeightMod) {
        this.mHeightMod = new HeightModifier(HeightModifier.WRAP, 0);
      }
      this.mX = 0;
      this.mY = 0;
    }
    // --- Layout helpers ---
    isWidthFill() {
      return this.mWidthMod?.getType() === WidthModifier.FILL;
    }
    isHeightFill() {
      return this.mHeightMod?.getType() === HeightModifier.FILL;
    }
    isWidthWrap() {
      return this.mWidthMod?.getType() === WidthModifier.WRAP;
    }
    isHeightWrap() {
      return this.mHeightMod?.getType() === HeightModifier.WRAP;
    }
    isWidthExact() {
      const t = this.mWidthMod?.getType();
      return t === WidthModifier.EXACT || t === WidthModifier.EXACT_DP;
    }
    isHeightExact() {
      const t = this.mHeightMod?.getType();
      return t === HeightModifier.EXACT || t === HeightModifier.EXACT_DP;
    }
    hasWidthWeight() {
      return this.mWidthMod?.getType() === WidthModifier.WEIGHT;
    }
    hasHeightWeight() {
      return this.mHeightMod?.getType() === HeightModifier.WEIGHT;
    }
    getWidthModValue() {
      return this.mWidthMod?.getValue() ?? 0;
    }
    getHeightModValue() {
      return this.mHeightMod?.getValue() ?? 0;
    }
    hasComputedLayout() {
      return this.mComputedLayoutModifiers !== null;
    }
    applyComputedLayout(type, context, m, parent) {
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
    minIntrinsicHeight() {
      const height = this.computeModifierDefinedHeight();
      let childrenHeight = 0;
      for (const c of this.mChildrenComponents) {
        childrenHeight = Math.max(childrenHeight, c.minIntrinsicHeight());
      }
      return Math.max(height, childrenHeight);
    }
    minIntrinsicWidth() {
      const width = this.computeModifierDefinedWidth();
      let childrenWidth = 0;
      for (const c of this.mChildrenComponents) {
        childrenWidth = Math.max(childrenWidth, c.minIntrinsicWidth());
      }
      return Math.max(width, childrenWidth);
    }
    computeModifierDefinedWidth() {
      if (this.isWidthExact()) return this.getWidthModValue() + this.mPaddingLeft + this.mPaddingRight;
      return -1;
    }
    computeModifierDefinedHeight() {
      if (this.isHeightExact()) return this.getHeightModValue() + this.mPaddingTop + this.mPaddingBottom;
      return -1;
    }
    // --- ComponentValue (Java ComponentData pattern) ---
    /**
     * Collect ComponentValue bindings that live *inside* a content container (e.g.
     * a `CanvasOperations` draw block) so this component's measured size reaches
     * them via updateComponentValues. Without this, a CanvasOperations fill whose
     * path geometry is built from `FloatExpression`s reading WIDTH/HEIGHT sees
     * those variables unset (zero) and draws an empty path. Recurses through
     * non-Component containers only — a nested child `Component` owns and collects
     * its own ComponentValues in its own `inflate()`.
     */
    hoistNestedComponentValues(op) {
      if (op instanceof Component) return;
      const getList = op.getList;
      if (typeof getList !== "function") return;
      for (const child of getList.call(op)) {
        if (child instanceof ComponentValue) {
          if (child.getComponentId2() === this.getComponentId()) {
            if (this.mComponentValues === null) this.mComponentValues = [];
            this.mComponentValues.push(child);
          }
        } else {
          this.hoistNestedComponentValues(child);
        }
      }
    }
    /** Update bound float variables with this component's dimensions/position.
     *  Matches Java Component.updateComponentValues — called during both measure
     *  (for WIDTH/HEIGHT) and layout (for all types including POS_X/POS_Y). */
    updateComponentValues(context, measuredW, measuredH) {
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
    layout(context, measure) {
      super.layout(context, measure);
      this.updateComponentValues(context, this.mWidth, this.mHeight);
      for (const child of this.mChildrenComponents) {
        child.layout(context, measure);
      }
      this.mNeedsMeasure = false;
    }
    /** Re-sum the cached padding totals from the padding modifiers' resolved
     *  values. `inflate()` snapshots these at load time from the raw (dp) values,
     *  before `PaddingModifier.updateVariables` has resolved variables or applied
     *  DP density scaling — so the snapshot is stale for dynamic or density-scaled
     *  padding. Called at the start of each measure (after updateVariables has run
     *  in the data pass) so measure and paint both see the final pixel padding. */
    refreshPadding() {
      let l = 0, t = 0, r = 0, b = 0;
      for (const mod of this.mComponentModifiers) {
        if (mod instanceof PaddingModifier) {
          l += mod.mLeftValue;
          t += mod.mTopValue;
          r += mod.mRightValue;
          b += mod.mBottomValue;
        }
      }
      this.mPaddingLeft = l;
      this.mPaddingTop = t;
      this.mPaddingRight = r;
      this.mPaddingBottom = b;
      let pw = 0;
      for (const mod of this.mPadBeforeWidthMods) pw += mod.mLeftValue + mod.mRightValue;
      this.mPadBeforeWidth = pw;
      let ph = 0;
      for (const mod of this.mPadBeforeHeightMods) ph += mod.mTopValue + mod.mBottomValue;
      this.mPadBeforeHeight = ph;
    }
    /** Walk modifiers reducing dimensions by padding and passing to decorators.
     *  Matches Java ComponentModifiers.layout(). */
    layoutModifiers(w, h) {
      let currentW = w;
      let currentH = h;
      for (const mod of this.mComponentModifiers) {
        if (mod instanceof PaddingModifier) {
          currentW -= mod.mLeftValue + mod.mRightValue;
          currentH -= mod.mTopValue + mod.mBottomValue;
        }
        if (typeof mod.layoutDecorator === "function") {
          mod.layoutDecorator(currentW, currentH);
        }
      }
    }
    // --- Paint ---
    drawContent(paintContext) {
      paintContext.matrixSave();
      paintContext.matrixTranslate(-this.mX, -this.mY);
      this.paintingComponent(paintContext);
      paintContext.matrixRestore();
    }
    paint(paintContext) {
      const drawContentOps = this.mDrawContentOperations;
      const hasCustomDraw = drawContentOps !== null && drawContentOps.some((op) => op instanceof PaintOperation);
      if (hasCustomDraw) {
        paintContext.matrixSave();
        paintContext.matrixTranslate(this.mX, this.mY);
        const context = paintContext.getContext();
        for (const op of drawContentOps) {
          context.incrementOpCount();
          if (op.isDirty() && typeof op.updateVariables === "function") {
            op.markNotDirty();
            op.updateVariables(context);
          }
          op.apply(context);
        }
        paintContext.matrixRestore();
      } else {
        super.paint(paintContext);
      }
    }
    paintingComponent(paintContext) {
      if (Visibility.isGone(this.mVisibility)) return;
      const context = paintContext.getContext();
      paintContext.matrixSave();
      paintContext.matrixTranslate(this.mX, this.mY);
      let tx = 0;
      let ty = 0;
      for (const mod of this.mComponentModifiers) {
        context.incrementOpCount();
        if (mod.isDirty() && typeof mod.updateVariables === "function") {
          mod.markNotDirty();
          mod.updateVariables(context);
        }
        if (mod instanceof PaddingModifier) {
          paintContext.matrixTranslate(mod.mLeftValue, mod.mTopValue);
          tx += mod.mLeftValue;
          ty += mod.mTopValue;
        } else {
          mod.apply(context);
        }
      }
      paintContext.matrixTranslate(-tx, -ty);
      paintContext.matrixTranslate(this.mPaddingLeft, this.mPaddingTop);
      for (const op of this.mContentOps) {
        const isDecoration = op instanceof CanvasOperations;
        if (isDecoration) {
          paintContext.matrixTranslate(-this.mPaddingLeft, -this.mPaddingTop);
        }
        context.incrementOpCount();
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.markNotDirty();
          op.updateVariables(context);
        }
        op.apply(context);
        if (isDecoration) {
          paintContext.matrixTranslate(this.mPaddingLeft, this.mPaddingTop);
        }
      }
      const children = this.mChildrenComponents;
      if (children.length > 1) {
        let needsSort = false;
        for (const c of children) {
          if (c.mZIndex !== 0) {
            needsSort = true;
            break;
          }
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
    updateVariables(context) {
      if (this.mComponentValues !== null && this.mComponentValues.length > 0) {
        this.updateComponentValues(context, this.mWidth, this.mHeight);
      }
    }
    // Inherits apply() from Component → PaintOperation chain.
    // No override needed — matches Java LayoutComponent which does not override apply().
    // --- Scroll ---
    getScrollX() {
      return 0;
    }
    getScrollY() {
      return 0;
    }
    onClick(context, doc, x, y) {
      for (let i = this.mChildrenComponents.length - 1; i >= 0; i--) {
        const child = this.mChildrenComponents[i];
        if (child instanceof Component) {
          if (child.onClick(
            context,
            doc,
            x - this.mX - this.mPaddingLeft,
            y - this.mY - this.mPaddingTop
          )) return true;
        }
      }
      if (x >= this.mX && x <= this.mX + this.mWidth && y >= this.mY && y <= this.mY + this.mHeight) {
        for (const mod of this.mComponentModifiers) {
          if (mod instanceof ClickModifier) {
            return mod.onClick(context, doc, x, y);
          }
        }
      }
      return false;
    }
    onTouchDown(context, doc, x, y) {
      let handled = false;
      const cx = x - this.mX - this.mPaddingLeft;
      const cy = y - this.mY - this.mPaddingTop;
      for (const child of this.mChildrenComponents) {
        if (child instanceof _LayoutComponent) {
          handled = child.onTouchDown(context, doc, cx, cy) || handled;
        }
      }
      if (x >= this.mX && x <= this.mX + this.mWidth && y >= this.mY && y <= this.mY + this.mHeight) {
        const lx = x - this.mX;
        const ly = y - this.mY;
        for (const op of this.mContentOps) {
          if (op instanceof TouchExpression) {
            op.updateVariables(context);
            op.touchDown(context, lx, ly);
            doc.appliedTouchOperation(this);
            handled = true;
          }
        }
        for (const child of this.mChildrenComponents) {
          if (!(child instanceof _LayoutComponent)) {
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
    dispatchTouchDownToOps(ops, context, doc, lx, ly) {
      let handled = false;
      for (const op of ops) {
        if (op instanceof TouchExpression) {
          op.updateVariables(context);
          op.touchDown(context, lx, ly);
          doc.appliedTouchOperation(this);
          handled = true;
        } else if (typeof op.getList === "function") {
          handled = this.dispatchTouchDownToOps(op.getList(), context, doc, lx, ly) || handled;
        }
      }
      return handled;
    }
    getLocationInWindow() {
      let x = this.mX + this.mPaddingLeft;
      let y = this.mY + this.mPaddingTop;
      let parent = this.getParent();
      while (parent) {
        x += parent.mX;
        y += parent.mY;
        if (parent instanceof _LayoutComponent) {
          x += parent.mPaddingLeft;
          y += parent.mPaddingTop;
        }
        parent = parent.getParent();
      }
      return [x, y];
    }
  };

  // third_party/remote-compose-player/src/core/operations/layout/measure/Size.ts
  var Size = class {
    constructor(width = 0, height = 0) {
      this.mWidth = width;
      this.mHeight = height;
    }
    getWidth() {
      return this.mWidth;
    }
    setWidth(value) {
      this.mWidth = value;
    }
    getHeight() {
      return this.mHeight;
    }
    setHeight(value) {
      this.mHeight = value;
    }
    clear() {
      this.mWidth = 0;
      this.mHeight = 0;
    }
  };

  // third_party/remote-compose-player/src/core/operations/layout/managers/LayoutManager.ts
  function contentExtent(size, padding) {
    return Math.max(0, size - padding);
  }
  var LayoutManager = class extends LayoutComponent {
    constructor() {
      super(...arguments);
      this.mCachedWrapSize = new Size();
    }
    measure(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      const selfMeasure = measure.get(this);
      this.refreshPadding();
      const padding_w = this.mPaddingLeft + this.mPaddingRight;
      const padding_h = this.mPaddingTop + this.mPaddingBottom;
      const wMod = this.getWidthModifier();
      const hMod = this.getHeightModifier();
      const dp = this.getDpScale(context);
      let w;
      if (wMod && (wMod.getType() === WidthModifier.EXACT || wMod.getType() === WidthModifier.EXACT_DP)) {
        const exactW = wMod.getType() === WidthModifier.EXACT_DP ? wMod.getValue() * dp : wMod.getValue();
        w = Math.min(exactW + this.mPadBeforeWidth, maxWidth);
      } else if (wMod && wMod.getType() === WidthModifier.FILL) {
        w = wMod.hasFraction() ? maxWidth * wMod.getValue() : maxWidth;
      } else if (wMod && wMod.getType() === WidthModifier.WEIGHT) {
        w = Math.min(Math.max(this.mPadBeforeWidth, minWidth), maxWidth);
      } else {
        w = maxWidth;
      }
      let h;
      if (hMod && (hMod.getType() === HeightModifier.EXACT || hMod.getType() === HeightModifier.EXACT_DP)) {
        const exactH = hMod.getType() === HeightModifier.EXACT_DP ? hMod.getValue() * dp : hMod.getValue();
        h = Math.min(exactH + this.mPadBeforeHeight, maxHeight);
      } else if (hMod && hMod.getType() === HeightModifier.FILL) {
        h = hMod.hasFraction() ? maxHeight * hMod.getValue() : maxHeight;
      } else if (hMod && hMod.getType() === HeightModifier.WEIGHT) {
        h = Math.min(Math.max(this.mPadBeforeHeight, minHeight), maxHeight);
      } else {
        h = maxHeight;
      }
      selfMeasure.setW(w);
      selfMeasure.setH(h);
      const horizontalWrap = wMod?.getType() === WidthModifier.WRAP;
      const verticalWrap = hMod?.getType() === HeightModifier.WRAP;
      if (horizontalWrap || verticalWrap) {
        this.mCachedWrapSize.clear();
        const childMaxW = contentExtent(horizontalWrap ? maxWidth : w, padding_w);
        const childMaxH = contentExtent(verticalWrap ? maxHeight : h, padding_h);
        this.computeWrapSize(
          context,
          minWidth,
          childMaxW,
          minHeight,
          childMaxH,
          horizontalWrap,
          verticalWrap,
          measure,
          this.mCachedWrapSize
        );
        if (horizontalWrap) {
          w = this.mCachedWrapSize.getWidth() + padding_w;
          const wIn = this.getWidthInModifier();
          if (wIn) {
            if (wIn.getMin() >= 0) w = Math.max(w, wIn.getMin() * dp);
            if (wIn.getMax() >= 0) w = Math.min(w, wIn.getMax() * dp);
          }
          w = Math.min(w, maxWidth);
        }
        if (verticalWrap) {
          h = this.mCachedWrapSize.getHeight() + padding_h;
          const hIn = this.getHeightInModifier();
          if (hIn) {
            if (hIn.getMin() >= 0) h = Math.max(h, hIn.getMin() * dp);
            if (hIn.getMax() >= 0) h = Math.min(h, hIn.getMax() * dp);
          }
          h = Math.min(h, maxHeight);
        }
        selfMeasure.setW(w);
        selfMeasure.setH(h);
      }
      const scrollMod = this.getScrollModifier();
      if (scrollMod) {
        const isVertical = scrollMod.getDirection() === ScrollModifier.VERTICAL;
        const hostW = contentExtent(Math.min(w, maxWidth), padding_w);
        const hostH = contentExtent(Math.min(h, maxHeight), padding_h);
        const unboundW = isVertical ? hostW : 1e9;
        const unboundH = isVertical ? 1e9 : hostH;
        this.mCachedWrapSize.clear();
        this.computeWrapSize(
          context,
          0,
          unboundW,
          0,
          unboundH,
          true,
          true,
          measure,
          this.mCachedWrapSize
        );
        if (isVertical) {
          this.mScrollHostDimension = hostH;
          this.mScrollContentDimension = this.mCachedWrapSize.getHeight();
        } else {
          this.mScrollHostDimension = hostW;
          this.mScrollContentDimension = this.mCachedWrapSize.getWidth();
        }
        const childMaxW = isVertical ? contentExtent(w, padding_w) : Math.max(contentExtent(w, padding_w), this.mScrollContentDimension);
        const childMaxH = isVertical ? Math.max(contentExtent(h, padding_h), this.mScrollContentDimension) : contentExtent(h, padding_h);
        this.computeSize(context, 0, childMaxW, 0, childMaxH, measure);
      }
      this.updateComponentValues(context.getContext(), w, h);
      if (!scrollMod) {
        this.computeSize(
          context,
          0,
          contentExtent(w, padding_w),
          0,
          contentExtent(h, padding_h),
          measure
        );
      }
      w = Math.max(w, minWidth);
      h = Math.max(h, minHeight);
      selfMeasure.setW(w);
      selfMeasure.setH(h);
      this.internalLayoutMeasure(context, measure);
    }
    layout(context, measure) {
      super.layout(context, measure);
      const self = measure.get(this);
      this.layoutModifiers(self.getW(), self.getH());
      const scrollMod = this.getScrollModifier();
      if (scrollMod) {
        const maxScroll = Math.max(0, this.mScrollContentDimension - this.mScrollHostDimension);
        const maxNan = scrollMod.getMaxNan();
        const notchNan = scrollMod.getNotchMaxNan();
        if (isNaNBits(maxNan)) {
          context.loadFloat(idFromBits(maxNan), maxScroll);
        }
        if (isNaNBits(notchNan)) {
          context.loadFloat(idFromBits(notchNan), this.mScrollContentDimension);
        }
      }
    }
    // Override in subclasses to compute wrap-content size
    computeWrapSize(_context, _minWidth, _maxWidth, _minHeight, _maxHeight, _horizontalWrap, _verticalWrap, _measure, _size) {
    }
    // Override in subclasses to measure non-wrap children
    computeSize(_context, _minWidth, _maxWidth, _minHeight, _maxHeight, _measure) {
    }
    // Override in subclasses to position children
    internalLayoutMeasure(_context, _measure) {
    }
    /** The document's dp→px scale (DOC_DENSITY_AT_GENERATION). Used to convert
     *  dp-typed dimension bounds to generation pixels. Defaults to 1 (unset/
     *  density-1 documents), so it is a no-op for everything authored today. */
    getDpScale(context) {
      const d = context.getContext().getDensity();
      return Number.isNaN(d) || d <= 0 ? 1 : d;
    }
    /** dp→px factor for values AndroidX scales *only* under DP density behavior —
     *  layout `spacedBy` spacing here, matching Row/ColumnLayout which multiply the
     *  gap by the density when `getDensityBehavior() == DP`. Returns 1 for LEGACY /
     *  PIXELS behavior so authored-in-px documents are untouched. */
    getDpBehaviorScale(context) {
      const ctx = context.getContext();
      if (ctx.getDensityBehavior() !== DENSITY_BEHAVIOR_DP) return 1;
      const d = ctx.getDensity();
      return Number.isNaN(d) || d <= 0 ? 1 : d;
    }
  };

  // third_party/remote-compose-player/src/core/operations/layout/managers/BoxLayout.ts
  var _BoxLayout = class _BoxLayout extends LayoutManager {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning) {
      super(componentId, animationId);
      this.mAnimationId = animationId;
      this.mHorizontalPositioning = horizontalPositioning;
      this.mVerticalPositioning = verticalPositioning;
    }
    computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      const parent = measure.get(this);
      for (const child of this.mChildrenComponents) {
        child.measure(context, 0, maxWidth, 0, maxHeight, measure);
        const m = measure.get(child);
        if (child.hasComputedLayout()) {
          if (child.applyComputedLayout(LayoutComputeOperation.TYPE_MEASURE, context, m, parent)) {
            child.measure(context, m.getW(), m.getW(), m.getH(), m.getH(), measure);
          }
        }
        if (!m.isGone()) {
          size.setWidth(Math.max(size.getWidth(), m.getW()));
          size.setHeight(Math.max(size.getHeight(), m.getH()));
        }
      }
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      const parent = measure.get(this);
      for (const child of this.mChildrenComponents) {
        child.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        if (child.hasComputedLayout()) {
          const m = measure.get(child);
          if (child.applyComputedLayout(LayoutComputeOperation.TYPE_MEASURE, context, m, parent)) {
            child.measure(context, m.getW(), m.getW(), m.getH(), m.getH(), measure);
          }
        }
      }
    }
    internalLayoutMeasure(_context, measure) {
      const selfMeasure = measure.get(this);
      const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
      const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
      for (const child of this.mChildrenComponents) {
        const childMeasure = measure.get(child);
        if (childMeasure.isGone()) continue;
        let tx = 0;
        let ty = 0;
        switch (this.mHorizontalPositioning) {
          case _BoxLayout.START:
            tx = 0;
            break;
          case _BoxLayout.CENTER:
            tx = (selfWidth - childMeasure.getW()) / 2;
            break;
          case _BoxLayout.END:
            tx = selfWidth - childMeasure.getW();
            break;
        }
        switch (this.mVerticalPositioning) {
          case _BoxLayout.TOP:
            ty = 0;
            break;
          case _BoxLayout.CENTER:
            ty = (selfHeight - childMeasure.getH()) / 2;
            break;
          case _BoxLayout.BOTTOM:
            ty = selfHeight - childMeasure.getH();
            break;
        }
        childMeasure.setX(tx);
        childMeasure.setY(ty);
        if (child.hasComputedLayout()) {
          child.applyComputedLayout(LayoutComputeOperation.TYPE_POSITION, _context, childMeasure, selfMeasure);
        }
      }
    }
    write(buffer) {
      buffer.start(_BoxLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.mAnimationId);
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}BoxLayout(${this.getComponentId()}, h=${this.mHorizontalPositioning}, v=${this.mVerticalPositioning})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.declareId();
      const animationId = buffer.declareId();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      operations.push(new _BoxLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning
      ));
    }
  };
  _BoxLayout.OP_CODE = 202;
  _BoxLayout.START = 1;
  _BoxLayout.CENTER = 2;
  _BoxLayout.END = 3;
  _BoxLayout.TOP = 4;
  _BoxLayout.BOTTOM = 5;
  var BoxLayout = _BoxLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/RowLayout.ts
  var _RowLayout = class _RowLayout extends LayoutManager {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy) {
      super(componentId, animationId);
      this.mAnimationId = animationId;
      this.mHorizontalPositioning = horizontalPositioning;
      this.mVerticalPositioning = verticalPositioning;
      this.mSpacedBy = spacedBy;
    }
    // --- Intrinsic size (matches Java RowLayout) ---
    minIntrinsicWidth() {
      return this.minIntrinsicWidthForComponents(this.mChildrenComponents);
    }
    minIntrinsicHeight() {
      return this.minIntrinsicHeightForComponents(this.mChildrenComponents);
    }
    minIntrinsicWidthForComponents(components) {
      const width = this.computeModifierDefinedWidth();
      let componentWidths = 0;
      for (const c of components) {
        componentWidths += c.minIntrinsicWidth();
      }
      return Math.max(width, componentWidths);
    }
    minIntrinsicHeightForComponents(components) {
      const height = this.computeModifierDefinedHeight();
      let componentHeights = 0;
      for (const c of components) {
        componentHeights = Math.max(componentHeights, c.minIntrinsicHeight());
      }
      return Math.max(height, componentHeights);
    }
    // --- Wrap size ---
    computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight, horizontalWrap, verticalWrap, measure, size) {
      this.computeWrapSizeForComponents(
        context,
        minWidth,
        maxWidth,
        minHeight,
        maxHeight,
        horizontalWrap,
        verticalWrap,
        measure,
        size,
        this.mChildrenComponents
      );
    }
    computeWrapSizeForComponents(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size, components) {
      size.clear();
      let visibleChildren = 0;
      let currentMaxWidth = maxWidth;
      let totalWeights = 0;
      let hasWeights = false;
      for (const child of components) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        if (child instanceof LayoutComponent && child.hasWidthWeight()) {
          hasWeights = true;
          totalWeights += child.getWidthModValue();
        }
      }
      if (hasWeights) {
        for (const child of components) {
          if (child instanceof LayoutComponent && child.hasWidthWeight()) continue;
          child.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(size.getWidth() + m.getW());
            size.setHeight(Math.max(size.getHeight(), m.getH()));
            visibleChildren++;
            currentMaxWidth -= m.getW();
          }
        }
        for (const child of components) {
          if (!(child instanceof LayoutComponent && child.hasWidthWeight())) continue;
          const weight = child.getWidthModValue();
          const childWidth = weight * currentMaxWidth / totalWeights;
          child.measure(context, childWidth, childWidth, 0, maxHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(size.getWidth() + m.getW());
            size.setHeight(Math.max(size.getHeight(), m.getH()));
            visibleChildren++;
          }
        }
      } else {
        for (const child of components) {
          child.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(size.getWidth() + m.getW());
            size.setHeight(Math.max(size.getHeight(), m.getH()));
            visibleChildren++;
            currentMaxWidth -= m.getW();
          }
        }
      }
      if (visibleChildren > 0) {
        size.setWidth(size.getWidth() + this.mSpacedBy * this.getDpBehaviorScale(context) * (visibleChildren - 1));
      }
    }
    // --- Compute size ---
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      this.computeSizeForComponents(
        context,
        minWidth,
        maxWidth,
        minHeight,
        maxHeight,
        measure,
        this.mChildrenComponents
      );
    }
    computeSizeForComponents(context, _minWidth, maxWidth, _minHeight, maxHeight, measure, components) {
      let mw = maxWidth;
      for (const child of components) {
        child.measure(context, 0, mw, 0, maxHeight, measure);
        const m = measure.get(child);
        if (!m.isGone()) mw -= m.getW();
      }
    }
    // --- Internal layout measure ---
    internalLayoutMeasure(context, measure) {
      const selfMeasure = measure.get(this);
      const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
      const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
      this.internalLayoutMeasureForComponents(
        context,
        measure,
        this.mChildrenComponents,
        selfWidth,
        selfHeight,
        0,
        0,
        null
      );
    }
    internalLayoutMeasureForComponents(context, measure, components, selfWidth, selfHeight, positionX, positionY, size) {
      if (components.length === 0) return;
      let hasWeights = false;
      let totalWeights = 0;
      let nonWeightWidth = 0;
      for (const child of components) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        if (child instanceof LayoutComponent && child.hasWidthWeight()) {
          hasWeights = true;
          totalWeights += child.getWidthModValue();
        } else {
          nonWeightWidth += cm.getW();
        }
      }
      if (hasWeights) {
        const dp = this.getDpScale(context);
        const availableSpace = selfWidth - nonWeightWidth;
        for (const child of components) {
          if (!(child instanceof LayoutComponent && child.hasWidthWeight())) continue;
          const cm = measure.get(child);
          if (cm.isGone()) continue;
          const weight = child.getWidthModValue();
          let childWidth = weight * availableSpace / totalWeights;
          const wIn = child.getWidthInModifier();
          if (wIn) {
            if (wIn.getMin() >= 0) childWidth = Math.max(wIn.getMin() * dp, childWidth);
            if (wIn.getMax() >= 0) childWidth = Math.min(wIn.getMax() * dp, childWidth);
          }
          cm.setW(childWidth);
          child.measure(context, childWidth, childWidth, 0, selfHeight, measure);
        }
      }
      let childrenWidth = 0;
      let childrenHeight = 0;
      let visibleChildren = 0;
      for (const child of components) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        childrenWidth += cm.getW();
        childrenHeight = Math.max(childrenHeight, cm.getH());
        visibleChildren++;
      }
      childrenWidth += this.mSpacedBy * this.getDpBehaviorScale(context) * Math.max(0, visibleChildren - 1);
      let tx = 0;
      let horizontalGap = 0;
      switch (this.mHorizontalPositioning) {
        case _RowLayout.START:
          tx = 0;
          break;
        case _RowLayout.END:
          tx = selfWidth - childrenWidth;
          break;
        case _RowLayout.CENTER:
          tx = (selfWidth - childrenWidth) / 2;
          break;
        case _RowLayout.SPACE_BETWEEN: {
          let total = 0;
          for (const c of components) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getW();
          }
          if (visibleChildren > 1) horizontalGap = (selfWidth - total) / (visibleChildren - 1);
          else tx = (selfWidth - childrenWidth) / 2;
          break;
        }
        case _RowLayout.SPACE_EVENLY: {
          let total = 0;
          for (const c of components) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getW();
          }
          horizontalGap = (selfWidth - total) / (visibleChildren + 1);
          tx = horizontalGap;
          break;
        }
        case _RowLayout.SPACE_AROUND: {
          let total = 0;
          for (const c of components) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getW();
          }
          horizontalGap = (selfWidth - total) / visibleChildren;
          tx = horizontalGap / 2;
          break;
        }
      }
      for (const child of components) {
        const cm = measure.get(child);
        let ty = 0;
        switch (this.mVerticalPositioning) {
          case _RowLayout.TOP:
            ty = 0;
            break;
          case _RowLayout.CENTER:
            ty = (selfHeight - cm.getH()) / 2;
            break;
          case _RowLayout.BOTTOM:
            ty = selfHeight - cm.getH();
            break;
        }
        cm.setX(tx + positionX);
        cm.setY(ty + positionY);
        if (cm.isGone()) continue;
        tx += cm.getW();
        if (this.mHorizontalPositioning === _RowLayout.SPACE_BETWEEN || this.mHorizontalPositioning === _RowLayout.SPACE_AROUND || this.mHorizontalPositioning === _RowLayout.SPACE_EVENLY) {
          tx += horizontalGap;
        }
        tx += this.mSpacedBy * this.getDpBehaviorScale(context);
      }
      if (size !== null) {
        size.setWidth(childrenWidth);
        size.setHeight(childrenHeight);
      }
    }
    write(buffer) {
      buffer.start(_RowLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.mAnimationId);
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
      buffer.writeFloat(this.mSpacedBy);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}RowLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      const spacedBy = buffer.readFloat();
      operations.push(new _RowLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning,
        spacedBy
      ));
    }
  };
  _RowLayout.OP_CODE = 203;
  _RowLayout.START = 1;
  _RowLayout.CENTER = 2;
  _RowLayout.END = 3;
  _RowLayout.TOP = 4;
  _RowLayout.BOTTOM = 5;
  _RowLayout.SPACE_BETWEEN = 6;
  _RowLayout.SPACE_EVENLY = 7;
  _RowLayout.SPACE_AROUND = 8;
  var RowLayout = _RowLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/ColumnLayout.ts
  var _ColumnLayout = class _ColumnLayout extends LayoutManager {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy) {
      super(componentId, animationId);
      this.mAnimationId = animationId;
      this.mHorizontalPositioning = horizontalPositioning;
      this.mVerticalPositioning = verticalPositioning;
      this.mSpacedBy = spacedBy;
    }
    computeWrapSize(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      size.clear();
      let visibleChildren = 0;
      let currentMaxHeight = maxHeight;
      let totalWeights = 0;
      let hasWeights = false;
      for (const child of this.mChildrenComponents) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        if (child instanceof LayoutComponent && child.hasHeightWeight()) {
          hasWeights = true;
          totalWeights += child.getHeightModValue();
        }
      }
      if (hasWeights) {
        for (const child of this.mChildrenComponents) {
          if (child instanceof LayoutComponent && child.hasHeightWeight()) continue;
          child.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(size.getHeight() + m.getH());
            visibleChildren++;
            currentMaxHeight -= m.getH();
          }
        }
        for (const child of this.mChildrenComponents) {
          if (!(child instanceof LayoutComponent && child.hasHeightWeight())) continue;
          const weight = child.getHeightModValue();
          const childHeight = weight * currentMaxHeight / totalWeights;
          child.measure(context, 0, maxWidth, childHeight, childHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(size.getHeight() + m.getH());
            visibleChildren++;
          }
        }
      } else {
        for (const child of this.mChildrenComponents) {
          child.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(size.getHeight() + m.getH());
            visibleChildren++;
            currentMaxHeight -= m.getH();
          }
        }
      }
      if (visibleChildren > 0) {
        size.setHeight(size.getHeight() + this.mSpacedBy * this.getDpBehaviorScale(context) * (visibleChildren - 1));
      }
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      let totalHeightsNoWeights = 0;
      let totalWeights = 0;
      let hasWeights = false;
      let maxh = maxHeight;
      for (const child of this.mChildrenComponents) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        if (child instanceof LayoutComponent && child.hasHeightWeight()) {
          hasWeights = true;
          totalWeights += child.getHeightModValue();
        } else {
          child.measure(context, minWidth, maxWidth, minHeight, maxh, measure);
          const m = measure.get(child);
          maxh -= m.getH();
          totalHeightsNoWeights += m.getH();
        }
      }
      if (hasWeights) {
        maxh = maxHeight;
        for (const child of this.mChildrenComponents) {
          if (child instanceof LayoutComponent && child.hasHeightWeight() && !child.isGone()) {
            const weight = child.getHeightModValue();
            const childHeight = (maxHeight - totalHeightsNoWeights) * weight / totalWeights;
            child.measure(context, minWidth, maxWidth, childHeight, childHeight, measure);
          } else {
            child.measure(context, minWidth, maxWidth, minHeight, maxh, measure);
          }
          const m = measure.get(child);
          if (!m.isGone()) {
            maxh -= m.getH();
          }
        }
      } else {
        let mh = maxHeight;
        for (const child of this.mChildrenComponents) {
          child.measure(context, minWidth, maxWidth, minHeight, mh, measure);
          const m = measure.get(child);
          if (!m.isGone()) {
            mh -= m.getH();
          }
        }
      }
    }
    internalLayoutMeasure(context, measure) {
      const selfMeasure = measure.get(this);
      const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
      const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
      const children = this.mChildrenComponents;
      if (children.length === 0) return;
      let hasWeights = false;
      let totalWeights = 0;
      let nonWeightHeight = 0;
      for (const child of children) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        if (child instanceof LayoutComponent && child.hasHeightWeight()) {
          hasWeights = true;
          totalWeights += child.getHeightModValue();
        } else {
          nonWeightHeight += cm.getH();
        }
      }
      if (hasWeights) {
        const dp = this.getDpScale(context);
        const availableSpace = selfHeight - nonWeightHeight;
        for (const child of children) {
          if (!(child instanceof LayoutComponent && child.hasHeightWeight())) continue;
          const cm = measure.get(child);
          if (cm.isGone()) continue;
          const weight = child.getHeightModValue();
          let childHeight = weight * availableSpace / totalWeights;
          const hIn = child.getHeightInModifier();
          if (hIn) {
            if (hIn.getMin() >= 0) childHeight = Math.max(hIn.getMin() * dp, childHeight);
            if (hIn.getMax() >= 0) childHeight = Math.min(hIn.getMax() * dp, childHeight);
          }
          cm.setH(childHeight);
          child.measure(context, 0, selfWidth, childHeight, childHeight, measure);
        }
      }
      let childrenWidth = 0;
      let childrenHeight = 0;
      let visibleChildren = 0;
      for (const child of children) {
        const cm = measure.get(child);
        if (cm.isGone()) continue;
        childrenWidth = Math.max(childrenWidth, cm.getW());
        childrenHeight += cm.getH();
        visibleChildren++;
      }
      childrenHeight += this.mSpacedBy * this.getDpBehaviorScale(context) * Math.max(0, visibleChildren - 1);
      let ty = 0;
      let verticalGap = 0;
      switch (this.mVerticalPositioning) {
        case _ColumnLayout.TOP:
          ty = 0;
          break;
        case _ColumnLayout.BOTTOM:
          ty = selfHeight - childrenHeight;
          break;
        case _ColumnLayout.CENTER:
          ty = (selfHeight - childrenHeight) / 2;
          break;
        case _ColumnLayout.SPACE_BETWEEN: {
          let total = 0;
          for (const c of children) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getH();
          }
          if (visibleChildren > 1) verticalGap = (selfHeight - total) / (visibleChildren - 1);
          else ty = (selfHeight - childrenHeight) / 2;
          break;
        }
        case _ColumnLayout.SPACE_EVENLY: {
          let total = 0;
          for (const c of children) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getH();
          }
          verticalGap = (selfHeight - total) / (visibleChildren + 1);
          ty = verticalGap;
          break;
        }
        case _ColumnLayout.SPACE_AROUND: {
          let total = 0;
          for (const c of children) {
            const m = measure.get(c);
            if (!m.isGone()) total += m.getH();
          }
          verticalGap = (selfHeight - total) / visibleChildren;
          ty = verticalGap / 2;
          break;
        }
      }
      for (const child of children) {
        const cm = measure.get(child);
        let tx = 0;
        switch (this.mHorizontalPositioning) {
          case _ColumnLayout.START:
            tx = 0;
            break;
          case _ColumnLayout.CENTER:
            tx = (selfWidth - cm.getW()) / 2;
            break;
          case _ColumnLayout.END:
            tx = selfWidth - cm.getW();
            break;
        }
        cm.setX(tx);
        cm.setY(ty);
        if (cm.isGone()) continue;
        ty += cm.getH();
        if (this.mVerticalPositioning === _ColumnLayout.SPACE_BETWEEN || this.mVerticalPositioning === _ColumnLayout.SPACE_AROUND || this.mVerticalPositioning === _ColumnLayout.SPACE_EVENLY) {
          ty += verticalGap;
        }
        ty += this.mSpacedBy * this.getDpBehaviorScale(context);
      }
    }
    write(buffer) {
      buffer.start(_ColumnLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.mAnimationId);
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
      buffer.writeFloat(this.mSpacedBy);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}ColumnLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.declareId();
      const animationId = buffer.declareId();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      const spacedBy = buffer.readFloat();
      operations.push(new _ColumnLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning,
        spacedBy
      ));
    }
  };
  _ColumnLayout.OP_CODE = 204;
  _ColumnLayout.START = 1;
  _ColumnLayout.CENTER = 2;
  _ColumnLayout.END = 3;
  _ColumnLayout.TOP = 4;
  _ColumnLayout.BOTTOM = 5;
  _ColumnLayout.SPACE_BETWEEN = 6;
  _ColumnLayout.SPACE_EVENLY = 7;
  _ColumnLayout.SPACE_AROUND = 8;
  var ColumnLayout = _ColumnLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/CanvasLayout.ts
  var _CanvasLayout = class _CanvasLayout extends BoxLayout {
    constructor(componentId, animationId) {
      super(componentId, animationId, 0, 0);
    }
    internalLayoutMeasure(context, measure) {
      if (this.mHasCanvasLayoutContent) {
        const selfMeasure = measure.get(this);
        const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
        const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
        for (const child of this.mChildrenComponents) {
          const m = measure.get(child);
          m.setX(0);
          m.setY(0);
          m.setW(selfWidth);
          m.setH(selfHeight);
        }
      } else {
        super.internalLayoutMeasure(context, measure);
      }
    }
    write(buffer) {
      buffer.start(_CanvasLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.mAnimationId);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}CanvasLayout(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      operations.push(new _CanvasLayout(componentId, animationId));
    }
  };
  _CanvasLayout.OP_CODE = 205;
  var CanvasLayout = _CanvasLayout;

  // third_party/remote-compose-player/src/core/operations/Skip.ts
  var sLibraryApiLevel = 7;
  var sProfile = 0;
  var _Skip = class _Skip extends Operation {
    constructor() {
      super();
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}Skip`;
    }
    static needsToSkip(conditionType, value) {
      switch (conditionType) {
        case _Skip.SKIP_IF_API_LESS_THAN:
          return sLibraryApiLevel < value;
        case _Skip.SKIP_IF_API_GREATER_THAN:
          return sLibraryApiLevel > value;
        case _Skip.SKIP_IF_API_EQUAL_TO:
          return sLibraryApiLevel === value;
        case _Skip.SKIP_IF_API_NOT_EQUAL_TO:
          return sLibraryApiLevel !== value;
        case _Skip.SKIP_IF_PROFILE_INCLUDES:
          return (sProfile & value) !== 0;
        case _Skip.SKIP_IF_PROFILE_EXCLUDES:
          return (sProfile & value) === 0;
        default:
          return false;
      }
    }
    static read(buffer, _operations) {
      const conditionType = buffer.readInt();
      const value = buffer.readInt();
      const skipLength = buffer.readInt();
      if (_Skip.needsToSkip(conditionType, value)) {
        buffer.setIndex(buffer.getIndex() + skipLength);
      }
    }
  };
  _Skip.OP_CODE = 241;
  _Skip.SKIP_IF_API_LESS_THAN = 1;
  _Skip.SKIP_IF_API_GREATER_THAN = 2;
  _Skip.SKIP_IF_API_EQUAL_TO = 3;
  _Skip.SKIP_IF_API_NOT_EQUAL_TO = 4;
  _Skip.SKIP_IF_PROFILE_INCLUDES = 5;
  _Skip.SKIP_IF_PROFILE_EXCLUDES = 6;
  var Skip = _Skip;

  // third_party/remote-compose-player/src/core/operations/layout/managers/TextStyle.ts
  var P_ID = 1;
  var P_ANIMATION_ID = 2;
  var P_COLOR = 3;
  var P_COLOR_ID = 4;
  var P_FONT_SIZE = 5;
  var P_FONT_STYLE = 6;
  var P_FONT_WEIGHT = 7;
  var P_FONT_FAMILY = 8;
  var P_TEXT_ALIGN = 9;
  var P_OVERFLOW = 10;
  var P_MAX_LINES = 11;
  var P_LETTER_SPACING = 12;
  var P_LINE_HEIGHT_ADD = 13;
  var P_LINE_HEIGHT_MULTIPLIER = 14;
  var P_BREAK_STRATEGY = 15;
  var P_HYPHENATION_FREQUENCY = 16;
  var P_JUSTIFICATION_MODE = 17;
  var P_UNDERLINE = 18;
  var P_STRIKETHROUGH = 19;
  var P_FONT_AXIS = 20;
  var P_FONT_AXIS_VALUES = 21;
  var P_AUTOSIZE = 22;
  var P_FLAGS = 23;
  var P_PARENT_ID = 24;
  var P_MIN_FONT_SIZE = 25;
  var P_MAX_FONT_SIZE = 26;
  var _TextStyle = class _TextStyle extends Operation {
    constructor() {
      super();
      this.id = -1;
      this.color = null;
      this.colorId = null;
      this.fontSize = null;
      this.minFontSize = null;
      this.maxFontSize = null;
      this.fontStyle = null;
      this.fontWeight = null;
      this.fontFamilyId = null;
      this.textAlign = null;
      this.overflow = null;
      this.maxLines = null;
      this.letterSpacing = null;
      this.lineHeightAdd = null;
      this.lineHeightMultiplier = null;
      this.lineBreakStrategy = null;
      this.hyphenationFrequency = null;
      this.justificationMode = null;
      this.underline = null;
      this.strikethrough = null;
      this.fontAxis = null;
      this.fontAxisValues = null;
      this.autosize = null;
      this.parentId = null;
    }
    write(_buffer) {
    }
    apply(context) {
      if (this.id !== -1) {
        context.putObject?.(this.id, this);
      }
    }
    deepToString(indent) {
      return `${indent}TextStyle(id=${this.id})`;
    }
    static read(buffer, operations) {
      const count = buffer.readShort();
      const style = new _TextStyle();
      for (let i = 0; i < count; i++) {
        const tag = buffer.readByte();
        switch (tag) {
          case P_ID:
            style.id = buffer.readInt();
            break;
          case P_ANIMATION_ID:
            buffer.readInt();
            break;
          case P_COLOR:
            style.color = buffer.readInt();
            break;
          case P_COLOR_ID:
            style.colorId = buffer.readInt();
            break;
          case P_FONT_SIZE:
            style.fontSize = buffer.readFloat();
            break;
          case P_FONT_STYLE:
            style.fontStyle = buffer.readInt();
            break;
          case P_FONT_WEIGHT:
            style.fontWeight = buffer.readFloat();
            break;
          case P_FONT_FAMILY:
            style.fontFamilyId = buffer.readInt();
            break;
          case P_TEXT_ALIGN:
            style.textAlign = buffer.readInt();
            break;
          case P_OVERFLOW:
            style.overflow = buffer.readInt();
            break;
          case P_MAX_LINES:
            style.maxLines = buffer.readInt();
            break;
          case P_LETTER_SPACING:
            style.letterSpacing = buffer.readFloat();
            break;
          case P_LINE_HEIGHT_ADD:
            style.lineHeightAdd = buffer.readFloat();
            break;
          case P_LINE_HEIGHT_MULTIPLIER:
            style.lineHeightMultiplier = buffer.readFloat();
            break;
          case P_BREAK_STRATEGY:
            style.lineBreakStrategy = buffer.readInt();
            break;
          case P_HYPHENATION_FREQUENCY:
            style.hyphenationFrequency = buffer.readInt();
            break;
          case P_JUSTIFICATION_MODE:
            style.justificationMode = buffer.readInt();
            break;
          case P_UNDERLINE:
            style.underline = buffer.readBoolean();
            break;
          case P_STRIKETHROUGH:
            style.strikethrough = buffer.readBoolean();
            break;
          case P_FONT_AXIS: {
            const len = buffer.readShort();
            const arr = new Array(len);
            for (let k = 0; k < len; k++) arr[k] = buffer.readInt();
            style.fontAxis = arr;
            break;
          }
          case P_FONT_AXIS_VALUES: {
            const len = buffer.readShort();
            const arr = new Array(len);
            for (let k = 0; k < len; k++) arr[k] = buffer.readFloat();
            style.fontAxisValues = arr;
            break;
          }
          case P_AUTOSIZE:
            style.autosize = buffer.readBoolean();
            break;
          case P_FLAGS:
            buffer.readInt();
            break;
          case P_PARENT_ID:
            style.parentId = buffer.readInt();
            break;
          case P_MIN_FONT_SIZE:
            style.minFontSize = buffer.readFloat();
            break;
          case P_MAX_FONT_SIZE:
            style.maxFontSize = buffer.readFloat();
            break;
          default:
            console.warn(`TextStyle: unknown parameter tag ${tag}`);
            return;
        }
      }
      operations.push(style);
    }
  };
  _TextStyle.OP_CODE = 242;
  var TextStyle = _TextStyle;

  // third_party/remote-compose-player/src/core/operations/ConditionalOperations.ts
  var _ConditionalOperations = class _ConditionalOperations extends PaintOperation {
    constructor(type, aBits, bBits) {
      super();
      this.mList = [];
      this.mType = type;
      this.mVarABits = aBits;
      this.mVarBBits = bBits;
      this.mVarAOut = isNaNBits(aBits) ? 0 : intBitsToFloat(aBits);
      this.mVarBOut = isNaNBits(bBits) ? 0 : intBitsToFloat(bBits);
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mVarABits)) context.listensTo(idFromBits(this.mVarABits), this);
      if (isNaNBits(this.mVarBBits)) context.listensTo(idFromBits(this.mVarBBits), this);
    }
    updateVariables(context) {
      this.markNotDirty();
      this.mVarAOut = isNaNBits(this.mVarABits) ? context.getFloat(idFromBits(this.mVarABits)) : intBitsToFloat(this.mVarABits);
      this.mVarBOut = isNaNBits(this.mVarBBits) ? context.getFloat(idFromBits(this.mVarBBits)) : intBitsToFloat(this.mVarBBits);
      for (const op of this.mList) {
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.updateVariables(context);
        }
      }
    }
    paint(paintContext) {
      const context = paintContext.getContext();
      for (const op of this.mList) {
        if (op.isDirty() && typeof op.updateVariables === "function") {
          op.markNotDirty();
          op.updateVariables(context);
        }
      }
      let run = false;
      switch (this.mType) {
        case _ConditionalOperations.TYPE_EQ:
          run = this.mVarAOut === this.mVarBOut;
          break;
        case _ConditionalOperations.TYPE_NEQ:
          run = this.mVarAOut !== this.mVarBOut;
          break;
        case _ConditionalOperations.TYPE_LT:
          run = this.mVarAOut < this.mVarBOut;
          break;
        case _ConditionalOperations.TYPE_LTE:
          run = this.mVarAOut <= this.mVarBOut;
          break;
        case _ConditionalOperations.TYPE_GT:
          run = this.mVarAOut > this.mVarBOut;
          break;
        case _ConditionalOperations.TYPE_GTE:
          run = this.mVarAOut >= this.mVarBOut;
          break;
      }
      if (run) {
        for (const op of this.mList) {
          context.incrementOpCount();
          if (op instanceof _ConditionalOperations) {
            op.paint(paintContext);
          } else {
            op.apply(context);
          }
        }
      }
    }
    deepToString(indent) {
      return `${indent}ConditionalOperations(type=${this.mType})`;
    }
    static read(buffer, operations) {
      const type = buffer.readByte();
      const a = buffer.readInt();
      const b = buffer.readInt();
      operations.push(new _ConditionalOperations(type, a, b));
    }
  };
  _ConditionalOperations.OP_CODE = 178;
  _ConditionalOperations.TYPE_EQ = 0;
  _ConditionalOperations.TYPE_NEQ = 1;
  _ConditionalOperations.TYPE_LT = 2;
  _ConditionalOperations.TYPE_LTE = 3;
  _ConditionalOperations.TYPE_GT = 4;
  _ConditionalOperations.TYPE_GTE = 5;
  var ConditionalOperations = _ConditionalOperations;

  // third_party/remote-compose-player/src/core/operations/PathCreate.ts
  function isPathMarkerBits2(b) {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id >= 10 && id <= 17;
  }
  var _PathCreate = class _PathCreate extends Operation {
    constructor(id, startXBits, startYBits) {
      super();
      this.mId = id;
      this.mPathBits = new Int32Array([_PathCreate.MOVE_NAN_BITS, startXBits, startYBits]);
      this.mOutputPath = Int32Array.from(this.mPathBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      for (const b of this.mPathBits) {
        if (isNaNBits(b) && !isPathMarkerBits2(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(context) {
      for (let i = 0; i < this.mPathBits.length; i++) {
        const b = this.mPathBits[i];
        if (isPathMarkerBits2(b)) {
          this.mOutputPath[i] = b;
        } else if (isNaNBits(b)) {
          this.mOutputPath[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
        } else {
          this.mOutputPath[i] = b;
        }
      }
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      context.loadPathData(this.mId, 0, this.mOutputPath);
    }
    deepToString(indent) {
      return `${indent}PathCreate(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const startX = buffer.readInt();
      const startY = buffer.readInt();
      operations.push(new _PathCreate(id, startX, startY));
    }
  };
  _PathCreate.OP_CODE = 159;
  _PathCreate.MOVE_NAN_BITS = 10 | -8388608 | 0;
  var PathCreate = _PathCreate;

  // third_party/remote-compose-player/src/core/operations/PathAppend.ts
  function isPathMarkerBits3(b) {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id >= 10 && id <= 17;
  }
  var _PathAppend = class _PathAppend extends Operation {
    constructor(id, bits) {
      super();
      this.mId = id;
      this.mPathBits = bits;
      this.mOutputPath = Int32Array.from(bits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      for (const b of this.mPathBits) {
        if (isNaNBits(b) && !isPathMarkerBits3(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(context) {
      for (let i = 0; i < this.mPathBits.length; i++) {
        const b = this.mPathBits[i];
        if (isPathMarkerBits3(b)) {
          this.mOutputPath[i] = b;
        } else if (isNaNBits(b)) {
          this.mOutputPath[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
        } else {
          this.mOutputPath[i] = b;
        }
      }
    }
    apply(context) {
      if (context.mMode !== "PAINT" /* PAINT */) return;
      const out = this.mOutputPath;
      if (out.length > 0 && _PathAppend.isReset(out[0])) {
        context.loadPathData(this.mId, 0, new Int32Array(0));
        return;
      }
      const existing = context.getPathData(this.mId);
      if (existing && existing.length > 0) {
        const combined = new Int32Array(existing.length + out.length);
        combined.set(existing, 0);
        combined.set(out, existing.length);
        context.loadPathData(this.mId, 0, combined);
      } else {
        context.loadPathData(this.mId, 0, out);
      }
    }
    static isReset(b) {
      if (!isNaNBits(b)) return false;
      return idFromBits(b) === 17;
    }
    deepToString(indent) {
      return `${indent}PathAppend(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const len = buffer.readInt();
      const bits = new Int32Array(len);
      for (let i = 0; i < len; i++) bits[i] = buffer.readInt();
      operations.push(new _PathAppend(id, bits));
    }
  };
  _PathAppend.OP_CODE = 160;
  var PathAppend = _PathAppend;

  // third_party/remote-compose-player/src/core/operations/PathExpression.ts
  var ARR_SENTINEL3 = 2 ** 42;
  var _PathExpression = class _PathExpression extends Operation {
    constructor(id, flags, minBits, maxBits, countBits, exprX, exprY) {
      super();
      this.mId = id;
      this.mFlags = flags;
      this.mMinBits = minBits;
      this.mMaxBits = maxBits;
      this.mCountBits = countBits;
      this.mExprX = exprX;
      this.mExprY = exprY;
      this.mWinding = (flags & _PathExpression.WINDING_MASK) >> 24;
    }
    write(_buffer) {
    }
    registerListening(context) {
      const listen = (b) => {
        if (isNaNBits(b)) context.listensTo(idFromBits(b), this);
      };
      listen(this.mMinBits);
      listen(this.mMaxBits);
      listen(this.mCountBits);
      const OFFSET3 = _PathExpression.OFFSET;
      const isOp = (b) => {
        if (!isNaNBits(b)) return false;
        const id = idFromBits(b);
        return id > OFFSET3 && id <= OFFSET3 + 79 || id === _PathExpression.VAR1_ID || (id & _PathExpression.ID_REGION_MASK) === _PathExpression.ID_REGION_ARRAY;
      };
      for (const b of this.mExprX) {
        if (isNaNBits(b) && !isOp(b)) listen(b);
      }
      for (const b of this.mExprY) {
        if (isNaNBits(b) && !isOp(b)) listen(b);
      }
    }
    apply(context) {
      const count = Math.round(this.rv(this.mCountBits, context));
      if (count < 1) return;
      const min = this.rv(this.mMinBits, context);
      const max = this.rv(this.mMaxBits, context);
      const loop = (this.mFlags & _PathExpression.LOOP) !== 0;
      const polar = (this.mFlags & _PathExpression.POLAR) !== 0;
      const gap = max - min;
      const step = loop ? gap / count : gap / (count - 1);
      const exprX = this.resolveExpr(this.mExprX, context);
      const exprY = this.resolveExpr(this.mExprY, context);
      const xData = new Float32Array(count);
      const yData = new Float32Array(count);
      const ca = context.getCollectionsAccess();
      if (polar) {
        const cx = exprY.length > 0 ? exprY[0] : 0;
        const cy = exprY.length > 1 ? exprY[1] : 0;
        for (let i = 0; i < count; i++) {
          const angle = min + i * step;
          const r = this.evalRPN(exprX, angle, ca);
          xData[i] = cx + r * Math.cos(angle);
          yData[i] = cy + r * Math.sin(angle);
        }
      } else {
        for (let i = 0; i < count; i++) {
          const val = min + i * step;
          xData[i] = this.evalRPN(exprX, val, ca);
          yData[i] = this.evalRPN(exprY, val, ca);
        }
      }
      const pathData = this.splinePath(xData, yData, loop);
      context.loadPathData(this.mId, this.mWinding, pathData);
    }
    rv(bits, ctx) {
      return isNaNBits(bits) ? ctx.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
    }
    // Resolve variable tokens to literal float bits; keep operator/VAR1/array
    // tokens as their NaN bits so evalRPN can decode them robustly.
    resolveExpr(expr, ctx) {
      const out = new Int32Array(expr.length);
      const OFF = _PathExpression.OFFSET;
      for (let i = 0; i < expr.length; i++) {
        const b = expr[i];
        if (isNaNBits(b)) {
          const id = idFromBits(b);
          if (id > OFF && id <= OFF + 79 || id === _PathExpression.VAR1_ID || (id & _PathExpression.ID_REGION_MASK) === _PathExpression.ID_REGION_ARRAY) {
            out[i] = b;
          } else {
            out[i] = floatToRawIntBits(ctx.getFloat(id));
          }
        } else {
          out[i] = b;
        }
      }
      return out;
    }
    evalRPN(expr, x, ca) {
      const s = [];
      let sp = -1;
      const OFF = _PathExpression.OFFSET;
      const arrId = (v) => v - ARR_SENTINEL3 | 0;
      for (let i = 0; i < expr.length; i++) {
        const b = expr[i];
        if (isNaNBits(b)) {
          const id = idFromBits(b);
          if (id === _PathExpression.VAR1_ID) {
            s[++sp] = x;
          } else if ((id & _PathExpression.ID_REGION_MASK) === _PathExpression.ID_REGION_ARRAY) {
            s[++sp] = ARR_SENTINEL3 + id;
          } else if (id > OFF && id <= OFF + 79) {
            const op = id - OFF;
            switch (op) {
              case 1:
                sp--;
                s[sp] += s[sp + 1];
                break;
              case 2:
                sp--;
                s[sp] -= s[sp + 1];
                break;
              case 3:
                sp--;
                s[sp] *= s[sp + 1];
                break;
              case 4:
                sp--;
                s[sp] /= s[sp + 1];
                break;
              case 5:
                sp--;
                s[sp] %= s[sp + 1];
                break;
              case 6:
                sp--;
                s[sp] = Math.min(s[sp], s[sp + 1]);
                break;
              case 7:
                sp--;
                s[sp] = Math.max(s[sp], s[sp + 1]);
                break;
              case 8:
                sp--;
                s[sp] = Math.pow(s[sp], s[sp + 1]);
                break;
              case 9:
                s[sp] = Math.sqrt(s[sp]);
                break;
              case 10:
                s[sp] = Math.abs(s[sp]);
                break;
              case 11:
                s[sp] = Math.sign(s[sp]);
                break;
              case 12:
                sp--;
                s[sp] = Math.abs(s[sp]) * Math.sign(s[sp + 1]);
                break;
              case 13:
                s[sp] = Math.exp(s[sp]);
                break;
              case 14:
                s[sp] = Math.floor(s[sp]);
                break;
              case 15:
                s[sp] = Math.log10(s[sp]);
                break;
              case 16:
                s[sp] = Math.log(s[sp]);
                break;
              case 17:
                s[sp] = Math.round(s[sp]);
                break;
              case 18:
                s[sp] = Math.sin(s[sp]);
                break;
              case 19:
                s[sp] = Math.cos(s[sp]);
                break;
              case 20:
                s[sp] = Math.tan(s[sp]);
                break;
              case 21:
                s[sp] = Math.asin(s[sp]);
                break;
              case 22:
                s[sp] = Math.acos(s[sp]);
                break;
              case 23:
                s[sp] = Math.atan(s[sp]);
                break;
              case 24:
                sp--;
                s[sp] = Math.atan2(s[sp], s[sp + 1]);
                break;
              case 25:
                sp -= 2;
                s[sp] = s[sp + 2] + s[sp + 1] * s[sp];
                break;
              case 26: {
                const c = s[sp];
                sp -= 2;
                s[sp] = c > 0 ? s[sp + 1] : s[sp];
                break;
              }
              case 27:
                sp -= 2;
                s[sp] = Math.min(Math.max(s[sp], s[sp + 2]), s[sp + 1]);
                break;
              case 28:
                s[sp] = Math.pow(s[sp], 1 / 3);
                break;
              case 29:
                s[sp] = s[sp] * (180 / Math.PI);
                break;
              case 30:
                s[sp] = s[sp] * (Math.PI / 180);
                break;
              case 31:
                s[sp] = Math.ceil(s[sp]);
                break;
              case 32: {
                const idx = s[sp];
                const aId = arrId(s[sp - 1]);
                sp--;
                s[sp] = ca.getFloatValue(aId, Math.trunc(idx));
                break;
              }
              case 33: {
                const aId = arrId(s[sp]);
                const fl = ca.getFloats(aId);
                let mx = 0;
                if (fl && fl.length > 0) {
                  mx = fl[0];
                  for (let j = 1; j < fl.length; j++) mx = Math.max(mx, fl[j]);
                }
                s[sp] = mx;
                break;
              }
              case 34: {
                const aId = arrId(s[sp]);
                const fl = ca.getFloats(aId);
                let mn = 0;
                if (fl && fl.length > 0) {
                  mn = fl[0];
                  for (let j = 1; j < fl.length; j++) mn = Math.min(mn, fl[j]);
                }
                s[sp] = mn;
                break;
              }
              case 35: {
                const aId = arrId(s[sp]);
                const fl = ca.getFloats(aId);
                let sm = 0;
                if (fl) for (let j = 0; j < fl.length; j++) sm += fl[j];
                s[sp] = sm;
                break;
              }
              case 36: {
                const aId = arrId(s[sp]);
                const fl = ca.getFloats(aId);
                let sm = 0;
                if (fl && fl.length > 0) {
                  for (let j = 0; j < fl.length; j++) sm += fl[j];
                  s[sp] = sm / fl.length;
                } else s[sp] = 0;
                break;
              }
              case 37: {
                const aId = arrId(s[sp]);
                s[sp] = ca.getListLength(aId);
                break;
              }
              case 38: {
                const pos = s[sp];
                const aId = arrId(s[sp - 1]);
                const fl = ca.getFloats(aId);
                sp--;
                s[sp] = fl ? FloatExpression.splineInterp(fl, pos) : 0;
                break;
              }
              case 44:
                sp--;
                s[sp] = s[sp] > s[sp + 1] ? 1 : 0;
                break;
              case 45:
                s[sp] = s[sp] * s[sp];
                break;
              case 46:
                s[sp + 1] = s[sp];
                sp++;
                break;
              case 47:
                sp--;
                s[sp] = Math.hypot(s[sp], s[sp + 1]);
                break;
              case 48: {
                const tmp = s[sp];
                s[sp] = s[sp - 1];
                s[sp - 1] = tmp;
                break;
              }
              case 49: {
                const t = s[sp];
                sp -= 2;
                s[sp] = s[sp] + (s[sp + 1] - s[sp]) * t;
                break;
              }
              case 52:
                s[sp] = 1 / s[sp];
                break;
              case 53:
                s[sp] = s[sp] - Math.floor(s[sp]);
                break;
              case 73:
                s[sp] = -s[sp];
                break;
              default:
                break;
            }
          } else {
            s[++sp] = intBitsToFloat(b);
          }
        } else {
          s[++sp] = intBitsToFloat(b);
        }
      }
      return sp >= 0 ? s[sp] : 0;
    }
    // Emits resolved path data as raw float32 int bits: command markers as
    // their NaN-marker bits, coordinates via floatToRawIntBits. Internal float
    // math is unchanged; only the final emitted array is bits.
    splinePath(x, y, loop) {
      const n = x.length;
      if (n === 0) return new Int32Array(0);
      const segs = loop ? n : n - 1;
      const out = new Int32Array(3 + segs * 9 + (loop ? 1 : 0));
      let p = 0;
      out[p++] = _PathExpression.MOVE_BITS;
      out[p++] = floatToRawIntBits(x[0]);
      out[p++] = floatToRawIntBits(y[0]);
      if (n <= 1) return out.subarray(0, p);
      const h = new Float32Array(segs);
      const dxS = new Float32Array(segs);
      const dyS = new Float32Array(segs);
      for (let i = 0; i < segs; i++) {
        const i1 = (i + 1) % n;
        const sx = x[i1] - x[i], sy = y[i1] - y[i];
        let d = Math.hypot(sx, sy);
        if (d === 0) d = 1e-12;
        h[i] = d;
        dxS[i] = sx / d;
        dyS[i] = sy / d;
      }
      const tn = loop ? segs : segs + 1;
      const dxT = new Float32Array(tn);
      const dyT = new Float32Array(tn);
      this.smoothTan(dxT, dxS, h, loop);
      this.smoothTan(dyT, dyS, h, loop);
      let cx = x[0], cy = y[0];
      for (let i = 0; i < segs; i++) {
        const i1 = (i + 1) % n;
        const ti1 = loop ? (i + 1) % segs : i + 1;
        const hi = h[i];
        out[p++] = _PathExpression.CUBIC_BITS;
        out[p++] = floatToRawIntBits(cx);
        out[p++] = floatToRawIntBits(cy);
        out[p++] = floatToRawIntBits(x[i] + dxT[i] * hi / 3);
        out[p++] = floatToRawIntBits(y[i] + dyT[i] * hi / 3);
        out[p++] = floatToRawIntBits(x[i1] - dxT[ti1] * hi / 3);
        out[p++] = floatToRawIntBits(y[i1] - dyT[ti1] * hi / 3);
        out[p++] = floatToRawIntBits(x[i1]);
        out[p++] = floatToRawIntBits(y[i1]);
        cx = x[i1];
        cy = y[i1];
      }
      if (loop) out[p++] = _PathExpression.CLOSE_BITS;
      return out.subarray(0, p);
    }
    smoothTan(d, delta, h, loop) {
      const segs = delta.length;
      const n = loop ? segs : segs + 1;
      if (loop) {
        for (let i = 0; i < n; i++) {
          const im = (i - 1 + segs) % segs;
          const ip = i % segs;
          d[i] = (h[im] * delta[ip] + h[ip] * delta[im]) / (h[im] + h[ip]);
        }
      } else {
        d[0] = delta[0];
        d[n - 1] = delta[segs - 1];
        for (let i = 1; i < n - 1; i++) {
          d[i] = (h[i - 1] * delta[i] + h[i] * delta[i - 1]) / (h[i - 1] + h[i]);
        }
      }
    }
    deepToString(indent) {
      return `${indent}PathExpression`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const flags = buffer.readInt();
      const min = buffer.readInt();
      const max = buffer.readInt();
      const count = buffer.readInt();
      const lenX = buffer.readInt();
      const exprX = new Int32Array(lenX);
      for (let i = 0; i < lenX; i++) exprX[i] = buffer.readInt();
      const lenY = buffer.readInt();
      const exprY = new Int32Array(lenY);
      for (let i = 0; i < lenY; i++) exprY[i] = buffer.readInt();
      operations.push(new _PathExpression(id, flags, min, max, count, exprX, exprY));
    }
  };
  _PathExpression.OP_CODE = 193;
  _PathExpression.LOOP = 1;
  _PathExpression.POLAR = 8;
  _PathExpression.WINDING_MASK = 50331648;
  _PathExpression.OFFSET = 3211264;
  _PathExpression.VAR1_ID = 3211264 + 70;
  // Path-command markers as raw float32 int bits (NaN-with-payload). Emitted
  // directly into the Int32Array path data so they survive NaN-payload
  // canonicalizing engines (Safari/Firefox).
  _PathExpression.MOVE_BITS = 10 | -8388608 | 0;
  _PathExpression.CUBIC_BITS = 14 | -8388608 | 0;
  _PathExpression.CLOSE_BITS = 15 | -8388608 | 0;
  _PathExpression.ID_REGION_MASK = 7340032;
  _PathExpression.ID_REGION_ARRAY = 2097152;
  var PathExpression = _PathExpression;

  // third_party/remote-compose-player/src/core/operations/utilities/Matrix.ts
  var _Matrix = class _Matrix {
    constructor(dim0 = 4, dim1 = 4) {
      this.mDim0 = 4;
      this.mDim1 = 4;
      this.mDim0 = dim0;
      this.mDim1 = dim1;
      this.mMatrix = new Float32Array(dim0 * dim1);
      this.setIdentity();
    }
    setDimensions(dim0, dim1) {
      this.mDim0 = dim0;
      this.mDim1 = dim1;
      this.mMatrix = new Float32Array(dim0 * dim1);
    }
    setIdentity() {
      this.mMatrix.fill(0);
      const min = Math.min(this.mDim0, this.mDim1);
      for (let i = 0; i < min; i++) {
        this.mMatrix[i * this.mDim1 + i] = 1;
      }
    }
    copyFromMatrix(src) {
      this.setDimensions(src.mDim0, src.mDim1);
      for (let i = 0; i < this.mMatrix.length; i++) {
        this.mMatrix[i] = src.mMatrix[i];
      }
    }
    copyFrom(values) {
      if (values.length === 16) {
        for (let i = 0; i < 16; i++) {
          this.mMatrix[i] = values[i];
        }
      } else if (values.length === 9) {
        this.mMatrix[0] = values[0];
        this.mMatrix[1] = values[1];
        this.mMatrix[3] = values[2];
        this.mMatrix[4] = values[3];
        this.mMatrix[5] = values[4];
        this.mMatrix[6] = values[5];
        this.mMatrix[8] = values[6];
        this.mMatrix[9] = values[7];
        this.mMatrix[10] = values[8];
        this.mMatrix[11] = 0;
        this.mMatrix[12] = 0;
        this.mMatrix[13] = 0;
        this.mMatrix[14] = 0;
        this.mMatrix[15] = 1;
      }
    }
    get(row, col) {
      return this.mMatrix[row * this.mDim1 + col];
    }
    set(row, col, value) {
      this.mMatrix[row * this.mDim1 + col] = value;
    }
    putValues(dest) {
      for (let i = 0; i < dest.length; i++) {
        dest[i] = this.mMatrix[i];
      }
    }
    static multiply(a, b, dest) {
      dest.setDimensions(a.mDim0, b.mDim1);
      for (let i = 0; i < dest.mDim0; i++) {
        for (let j = 0; j < dest.mDim1; j++) {
          let sum = 0;
          for (let k = 0; k < a.mDim1; k++) {
            sum += a.mMatrix[i * a.mDim1 + k] * b.mMatrix[k * b.mDim1 + j];
          }
          dest.mMatrix[i * dest.mDim1 + j] = sum;
        }
      }
    }
    static copy(src, dest) {
      dest.setDimensions(src.mDim0, src.mDim1);
      for (let i = 0; i < src.mMatrix.length; i++) {
        dest.mMatrix[i] = src.mMatrix[i];
      }
    }
    rotateX(degrees) {
      const rad = degrees * Math.PI / 180;
      const cos = Math.cos(rad);
      const sin = Math.sin(rad);
      const tmp1 = _Matrix.sTmpMatrix1;
      const tmp2 = _Matrix.sTmpMatrix2;
      tmp1.setIdentity();
      tmp1.set(1, 1, cos);
      tmp1.set(1, 2, -sin);
      tmp1.set(2, 1, sin);
      tmp1.set(2, 2, cos);
      _Matrix.multiply(this, tmp1, tmp2);
      this.copyFromMatrix(tmp2);
    }
    rotateY(degrees) {
      const rad = degrees * Math.PI / 180;
      const cos = Math.cos(rad);
      const sin = Math.sin(rad);
      const tmp1 = _Matrix.sTmpMatrix1;
      const tmp2 = _Matrix.sTmpMatrix2;
      tmp1.setIdentity();
      tmp1.set(0, 0, cos);
      tmp1.set(0, 2, sin);
      tmp1.set(2, 0, -sin);
      tmp1.set(2, 2, cos);
      _Matrix.multiply(this, tmp1, tmp2);
      this.copyFromMatrix(tmp2);
    }
    rotateZ(a, b, c) {
      if (b !== void 0 && c !== void 0) {
        this.rotateZWithPivot(a, b, c);
        return;
      }
      const degrees = a;
      const rad = degrees * Math.PI / 180;
      const cos = Math.cos(rad);
      const sin = Math.sin(rad);
      const tmp1 = _Matrix.sTmpMatrix1;
      const tmp2 = _Matrix.sTmpMatrix2;
      tmp1.setIdentity();
      tmp1.set(0, 0, cos);
      tmp1.set(0, 1, -sin);
      tmp1.set(1, 0, sin);
      tmp1.set(1, 1, cos);
      _Matrix.multiply(this, tmp1, tmp2);
      this.copyFromMatrix(tmp2);
    }
    rotateZWithPivot(pivotX, pivotY, degrees) {
      const rad = degrees * Math.PI / 180;
      const cos = Math.cos(rad);
      const sin = Math.sin(rad);
      const oneMinusCos = 1 - cos;
      const tx = pivotX * oneMinusCos + pivotY * sin;
      const ty = pivotY * oneMinusCos - pivotX * sin;
      const result = new Float32Array(16);
      for (let i = 0; i < 4; i++) {
        for (let j = 0; j < 4; j++) {
          let sum = 0;
          for (let k = 0; k < 4; k++) {
            let m_ik;
            if (i === 0) {
              if (k === 0) m_ik = cos;
              else if (k === 1) m_ik = -sin;
              else if (k === 3) m_ik = tx;
              else m_ik = 0;
            } else if (i === 1) {
              if (k === 0) m_ik = sin;
              else if (k === 1) m_ik = cos;
              else if (k === 3) m_ik = ty;
              else m_ik = 0;
            } else if (i === 2) {
              m_ik = k === 2 ? 1 : 0;
            } else {
              m_ik = k === 3 ? 1 : 0;
            }
            sum += m_ik * this.mMatrix[k * 4 + j];
          }
          result[i * 4 + j] = sum;
        }
      }
      this.mMatrix.set(result);
    }
    translate(x, y, z) {
      const tmp1 = _Matrix.sTmpMatrix1;
      const tmp2 = _Matrix.sTmpMatrix2;
      tmp1.setIdentity();
      tmp1.set(0, 3, x);
      tmp1.set(1, 3, y);
      tmp1.set(2, 3, z);
      _Matrix.multiply(this, tmp1, tmp2);
      this.copyFromMatrix(tmp2);
    }
    setScale(x, y, z) {
      this.mMatrix[0 * this.mDim1 + 0] *= x;
      this.mMatrix[1 * this.mDim1 + 1] *= y;
      this.mMatrix[2 * this.mDim1 + 2] *= z;
    }
    projection(fovDegrees, aspectRatio, near, far) {
      const tmp1 = _Matrix.sTmpMatrix1;
      const tmp2 = _Matrix.sTmpMatrix2;
      const matrix = tmp1.mMatrix;
      const fovRadians = fovDegrees * Math.PI / 180;
      const f = 1 / Math.tan(fovRadians / 2);
      const rangeInv = 1 / (near - far);
      matrix[0] = f / aspectRatio;
      matrix[1] = 0;
      matrix[2] = 0;
      matrix[3] = 0;
      matrix[4] = 0;
      matrix[5] = f;
      matrix[6] = 0;
      matrix[7] = 0;
      matrix[8] = 0;
      matrix[9] = 0;
      matrix[10] = (far + near) * rangeInv;
      matrix[11] = -1;
      matrix[12] = 0;
      matrix[13] = 0;
      matrix[14] = 2 * far * near * rangeInv;
      matrix[15] = 0;
      _Matrix.multiply(this, tmp1, tmp2);
      this.copyFromMatrix(tmp2);
    }
    rotateAroundAxis(vx, vy, vz, angleDegrees) {
      const angleRadians = angleDegrees * Math.PI / 180;
      const lenSq = vx * vx + vy * vy + vz * vz;
      if (lenSq === 0) return;
      const len = Math.sqrt(lenSq);
      const ux = vx / len, uy = vy / len, uz = vz / len;
      const cos = Math.cos(angleRadians);
      const sin = Math.sin(angleRadians);
      const omc = 1 - cos;
      const r00 = cos + ux * ux * omc;
      const r01 = ux * uy * omc - uz * sin;
      const r02 = ux * uz * omc + uy * sin;
      const r10 = uy * ux * omc + uz * sin;
      const r11 = cos + uy * uy * omc;
      const r12 = uy * uz * omc - ux * sin;
      const r20 = uz * ux * omc - uy * sin;
      const r21 = uz * uy * omc + ux * sin;
      const r22 = cos + uz * uz * omc;
      const result = new Float32Array(16);
      for (let i = 0; i < 4; i++) {
        for (let j = 0; j < 4; j++) {
          let sum = 0;
          for (let k = 0; k < 4; k++) {
            let r_ik;
            if (i === 0) {
              r_ik = k === 0 ? r00 : k === 1 ? r01 : k === 2 ? r02 : 0;
            } else if (i === 1) {
              r_ik = k === 0 ? r10 : k === 1 ? r11 : k === 2 ? r12 : 0;
            } else if (i === 2) {
              r_ik = k === 0 ? r20 : k === 1 ? r21 : k === 2 ? r22 : 0;
            } else {
              r_ik = k === 3 ? 1 : 0;
            }
            sum += r_ik * this.mMatrix[k * 4 + j];
          }
          result[i * 4 + j] = sum;
        }
      }
      this.mMatrix.set(result);
    }
    /** Matrix × vector multiplication */
    multiplyVec(input, out) {
      for (let j = 0; j < out.length; j++) {
        let tmp = 0;
        for (let i = 0; i < input.length; i++) {
          tmp += this.mMatrix[i + j * 4] * input[i];
        }
        out[j] = tmp + this.mMatrix[3 + j * 4];
      }
    }
    /** Perspective transform: matrix × vector, then divide by w */
    evalPerspective(input, out) {
      if (_Matrix.sTempInVec === null) {
        _Matrix.sTempInVec = new Float32Array(4);
        _Matrix.sTempOutVec = new Float32Array(4);
        _Matrix.sTempInVec[3] = 1;
      }
      const inVec = _Matrix.sTempInVec;
      const outVec = _Matrix.sTempOutVec;
      for (let i = 0; i < input.length; i++) inVec[i] = input[i];
      for (let j = 0; j < outVec.length; j++) {
        let tmp = 0;
        for (let i = 0; i < inVec.length; i++) {
          tmp += this.mMatrix[i + j * 4] * inVec[i];
        }
        outVec[j] = tmp;
      }
      for (let i = 0; i < out.length; i++) {
        outVec[i] /= outVec[3];
      }
      for (let i = 0; i < out.length; i++) out[i] = outVec[i];
    }
  };
  _Matrix.sTmpMatrix1 = new _Matrix();
  _Matrix.sTmpMatrix2 = new _Matrix();
  _Matrix.sTempOutVec = null;
  _Matrix.sTempInVec = null;
  var Matrix = _Matrix;

  // third_party/remote-compose-player/src/core/operations/utilities/MatrixOperations.ts
  var OFFSET = 3276800;
  function asNan2(v) {
    return intBitsToFloat(v | -8388608);
  }
  function fromNaN(v) {
    return floatToRawIntBits(v) & 4194303;
  }
  var ID_REGION_ARRAY = 2097152;
  function isDataVariable(v) {
    const id = fromNaN(v);
    return (id & 7340032) === ID_REGION_ARRAY;
  }
  function isDataVariableBits(b) {
    return (idFromBits(b) & 7340032) === ID_REGION_ARRAY;
  }
  var _MatrixOperations = class _MatrixOperations {
    constructor() {
      this.mMatrices = [];
      this.mTmpMatrix = new Matrix();
      this.mMatrixIndex = -1;
      this.mStack = [];
      for (let i = 0; i < 10; i++) {
        this.mMatrices.push(new Matrix(4, 4));
      }
    }
    static isOperator(v) {
      if (Number.isNaN(v)) {
        if (isDataVariable(v)) return false;
        const pos = fromNaN(v);
        return pos > OFFSET && pos <= _MatrixOperations.LAST_OP;
      }
      return false;
    }
    /** Bit-aware operator test: true if raw float32 int bits encode an operator token. */
    static isOperatorBits(b) {
      if (isNaNBits(b)) {
        if (isDataVariableBits(b)) return false;
        const pos = idFromBits(b);
        return pos > OFFSET && pos <= _MatrixOperations.LAST_OP;
      }
      return false;
    }
    eval(exp) {
      if (!exp) {
        this.mMatrices[0].setIdentity();
        return this.mMatrices[0];
      }
      this.mStack = exp;
      this.mMatrixIndex = 0;
      this.mMatrices[0].setIdentity();
      for (let i = 0; i < exp.length; i++) {
        const v = exp[i];
        if (Number.isNaN(v)) {
          this.opEval(i, fromNaN(v));
        }
      }
      return this.mMatrices[0];
    }
    /**
     * Evaluate from raw float32 int bits. Operator tokens are detected directly
     * from the bits (robust against engines that canonicalize NaN payloads); the
     * stack holds decoded literal floats so opEval reads ordinary numbers.
     */
    evalBits(bits) {
      if (!bits) {
        this.mMatrices[0].setIdentity();
        return this.mMatrices[0];
      }
      const stack = new Float32Array(bits.length);
      for (let i = 0; i < bits.length; i++) stack[i] = intBitsToFloat(bits[i]);
      this.mStack = stack;
      this.mMatrixIndex = 0;
      this.mMatrices[0].setIdentity();
      for (let i = 0; i < bits.length; i++) {
        if (isNaNBits(bits[i])) {
          this.opEval(i, idFromBits(bits[i]));
        }
      }
      return this.mMatrices[0];
    }
    opEval(sp, id) {
      const s = this.mStack;
      const m = this.mMatrices;
      switch (id) {
        case _MatrixOperations.OP_IDENTITY:
          m[++this.mMatrixIndex].setIdentity();
          return;
        case _MatrixOperations.OP_ROT_X:
          m[this.mMatrixIndex].rotateX(s[sp - 1]);
          return;
        case _MatrixOperations.OP_ROT_Y:
          m[this.mMatrixIndex].rotateY(s[sp - 1]);
          return;
        case _MatrixOperations.OP_ROT_Z:
          m[this.mMatrixIndex].rotateZ(s[sp - 1]);
          return;
        case _MatrixOperations.OP_TRANSLATE_X:
          m[this.mMatrixIndex].translate(s[sp - 1], 0, 0);
          return;
        case _MatrixOperations.OP_TRANSLATE_Y:
          m[this.mMatrixIndex].translate(0, s[sp - 1], 0);
          return;
        case _MatrixOperations.OP_TRANSLATE_Z:
          m[this.mMatrixIndex].translate(0, 0, s[sp - 1]);
          return;
        case _MatrixOperations.OP_TRANSLATE2:
          m[this.mMatrixIndex].translate(s[sp - 2], s[sp - 1], 0);
          return;
        case _MatrixOperations.OP_TRANSLATE3:
          m[this.mMatrixIndex].translate(s[sp - 3], s[sp - 2], s[sp - 1]);
          return;
        case _MatrixOperations.OP_SCALE_X:
          m[this.mMatrixIndex].setScale(s[sp - 1], 1, 1);
          return;
        case _MatrixOperations.OP_SCALE_Y:
          m[this.mMatrixIndex].setScale(1, s[sp - 1], 1);
          return;
        case _MatrixOperations.OP_SCALE_Z:
          m[this.mMatrixIndex].setScale(1, 1, s[sp - 1]);
          return;
        case _MatrixOperations.OP_SCALE2:
          m[this.mMatrixIndex].setScale(s[sp - 2], s[sp - 1], 0);
          return;
        case _MatrixOperations.OP_SCALE3:
          m[this.mMatrixIndex].setScale(s[sp - 3], s[sp - 2], s[sp - 1]);
          return;
        case _MatrixOperations.OP_MUL:
          Matrix.multiply(m[this.mMatrixIndex - 1], m[this.mMatrixIndex], this.mTmpMatrix);
          m[this.mMatrixIndex - 1].copyFromMatrix(this.mTmpMatrix);
          this.mMatrixIndex--;
          return;
        case _MatrixOperations.OP_ROT_PZ:
          m[this.mMatrixIndex].rotateZ(s[sp - 2], s[sp - 1], s[sp - 3]);
          return;
        case _MatrixOperations.OP_ROT_AXIS:
          m[this.mMatrixIndex].rotateAroundAxis(s[sp - 3], s[sp - 2], s[sp - 1], s[sp - 4]);
          return;
        case _MatrixOperations.OP_PROJECTION:
          m[this.mMatrixIndex].projection(s[sp - 4], s[sp - 3], s[sp - 2], s[sp - 1]);
          return;
      }
    }
  };
  _MatrixOperations.OFFSET = OFFSET;
  _MatrixOperations.LAST_OP = OFFSET + 54;
  // Operator NaN constants
  _MatrixOperations.IDENTITY = asNan2(OFFSET + 1);
  _MatrixOperations.ROT_X = asNan2(OFFSET + 2);
  _MatrixOperations.ROT_Y = asNan2(OFFSET + 3);
  _MatrixOperations.ROT_Z = asNan2(OFFSET + 4);
  _MatrixOperations.TRANSLATE_X = asNan2(OFFSET + 5);
  _MatrixOperations.TRANSLATE_Y = asNan2(OFFSET + 6);
  _MatrixOperations.TRANSLATE_Z = asNan2(OFFSET + 7);
  _MatrixOperations.TRANSLATE2 = asNan2(OFFSET + 8);
  _MatrixOperations.TRANSLATE3 = asNan2(OFFSET + 9);
  _MatrixOperations.SCALE_X = asNan2(OFFSET + 10);
  _MatrixOperations.SCALE_Y = asNan2(OFFSET + 11);
  _MatrixOperations.SCALE_Z = asNan2(OFFSET + 12);
  _MatrixOperations.SCALE2 = asNan2(OFFSET + 13);
  _MatrixOperations.SCALE3 = asNan2(OFFSET + 14);
  _MatrixOperations.MUL = asNan2(OFFSET + 15);
  _MatrixOperations.ROT_PZ = asNan2(OFFSET + 16);
  _MatrixOperations.ROT_AXIS = asNan2(OFFSET + 17);
  _MatrixOperations.PROJECTION = asNan2(OFFSET + 18);
  // Op codes
  _MatrixOperations.OP_IDENTITY = OFFSET + 1;
  _MatrixOperations.OP_ROT_X = OFFSET + 2;
  _MatrixOperations.OP_ROT_Y = OFFSET + 3;
  _MatrixOperations.OP_ROT_Z = OFFSET + 4;
  _MatrixOperations.OP_TRANSLATE_X = OFFSET + 5;
  _MatrixOperations.OP_TRANSLATE_Y = OFFSET + 6;
  _MatrixOperations.OP_TRANSLATE_Z = OFFSET + 7;
  _MatrixOperations.OP_TRANSLATE2 = OFFSET + 8;
  _MatrixOperations.OP_TRANSLATE3 = OFFSET + 9;
  _MatrixOperations.OP_SCALE_X = OFFSET + 10;
  _MatrixOperations.OP_SCALE_Y = OFFSET + 11;
  _MatrixOperations.OP_SCALE_Z = OFFSET + 12;
  _MatrixOperations.OP_SCALE2 = OFFSET + 13;
  _MatrixOperations.OP_SCALE3 = OFFSET + 14;
  _MatrixOperations.OP_MUL = OFFSET + 15;
  _MatrixOperations.OP_ROT_PZ = OFFSET + 16;
  _MatrixOperations.OP_ROT_AXIS = OFFSET + 17;
  _MatrixOperations.OP_PROJECTION = OFFSET + 18;
  var MatrixOperations = _MatrixOperations;

  // third_party/remote-compose-player/src/core/operations/MatrixExpression.ts
  var _MatrixExpression = class _MatrixExpression extends Operation {
    constructor(matrixId, type, bits) {
      super();
      this.mValues = new Float32Array(16);
      this.mOutBits = null;
      this.mMatrixOperations = new MatrixOperations();
      this.mMatrixId = matrixId;
      this.mType = type;
      this.mBits = bits;
    }
    write(_buffer) {
    }
    registerListening(context) {
      for (const b of this.mBits) {
        if (isNaNBits(b) && !MatrixOperations.isOperatorBits(b)) {
          context.listensTo(idFromBits(b), this);
        }
      }
    }
    updateVariables(context) {
      if (this.mOutBits === null || this.mOutBits.length !== this.mBits.length) {
        this.mOutBits = new Int32Array(this.mBits.length);
      }
      for (let i = 0; i < this.mBits.length; i++) {
        const b = this.mBits[i];
        if (isNaNBits(b) && !MatrixOperations.isOperatorBits(b)) {
          this.mOutBits[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
        } else {
          this.mOutBits[i] = b;
        }
      }
    }
    apply(context) {
      const m = this.mMatrixOperations.evalBits(this.mOutBits);
      m.putValues(this.mValues);
      context.putObject(this.mMatrixId, this);
      context.loadFloat(this.mMatrixId, performance.now() * 1e6);
    }
    getValues() {
      return this.mValues;
    }
    deepToString(indent) {
      return `${indent}MatrixExpression(${this.mMatrixId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const type = buffer.readInt();
      const len = buffer.readInt();
      if (len > 32 || len < 0) throw new Error(`Invalid matrix expression length ${len}`);
      const bits = new Int32Array(len);
      for (let i = 0; i < len; i++) bits[i] = buffer.readInt();
      operations.push(new _MatrixExpression(id, type, bits));
    }
  };
  _MatrixExpression.OP_CODE = 187;
  var MatrixExpression = _MatrixExpression;

  // third_party/remote-compose-player/src/core/operations/MatrixConstant.ts
  var _MatrixConstant = class _MatrixConstant extends Operation {
    constructor(matrixId, type, values) {
      super();
      this.mMatrixId = matrixId;
      this.mType = type;
      this.mValues = values;
    }
    write(_buffer) {
    }
    apply(context) {
      context.putObject(this.mMatrixId, this);
    }
    getValues() {
      return this.mValues;
    }
    deepToString(indent) {
      return `${indent}MatrixConstant(${this.mMatrixId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const type = buffer.readInt();
      const len = buffer.readInt();
      if (len > 16 || len < 0) throw new Error(`Invalid matrix length ${len}`);
      const values = new Float32Array(len);
      for (let i = 0; i < len; i++) values[i] = buffer.readFloat();
      operations.push(new _MatrixConstant(id, type, values));
    }
  };
  _MatrixConstant.OP_CODE = 186;
  var MatrixConstant = _MatrixConstant;

  // third_party/remote-compose-player/src/core/operations/MatrixVectorMath.ts
  var _MatrixVectorMath = class _MatrixVectorMath extends Operation {
    constructor(type, outputs, matrixId, inputBits) {
      super();
      this.mMatrix = new Matrix();
      this.mType = type;
      this.mMatrixId = matrixId;
      this.mOutputs = outputs;
      this.mOutInputs = new Float32Array(inputBits.length);
      this.mInputBits = inputBits;
      this.mTempOut = new Float32Array(outputs.length);
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mMatrixId, this);
      for (const b of this.mInputBits) {
        if (isNaNBits(b)) context.listensTo(idFromBits(b), this);
      }
    }
    updateVariables(context) {
      for (let i = 0; i < this.mInputBits.length; i++) {
        const b = this.mInputBits[i];
        this.mOutInputs[i] = isNaNBits(b) ? context.getFloat(idFromBits(b)) : intBitsToFloat(b);
      }
    }
    apply(context) {
      const obj = context.getObject(this.mMatrixId);
      if (!obj) return;
      const values = obj.getValues ? obj.getValues() : obj.mValues;
      if (!values) return;
      this.mMatrix.copyFrom(values);
      if (this.mType === 0) {
        this.mMatrix.multiplyVec(this.mOutInputs, this.mTempOut);
      } else {
        this.mMatrix.evalPerspective(this.mOutInputs, this.mTempOut);
      }
      for (let i = 0; i < this.mOutputs.length; i++) {
        context.loadFloat(this.mOutputs[i], this.mTempOut[i]);
      }
    }
    deepToString(indent) {
      return `${indent}MatrixVectorMath(matrix=${this.mMatrixId})`;
    }
    static read(buffer, operations) {
      const type = buffer.readShort();
      const matrixId = buffer.readInt();
      const lenOut = buffer.readInt();
      if (lenOut > 4 || lenOut < 1) throw new Error(`Invalid output length ${lenOut}`);
      const out = new Int32Array(lenOut);
      for (let i = 0; i < lenOut; i++) out[i] = buffer.readInt();
      const lenIn = buffer.readInt();
      if (lenIn > 4 || lenIn < 1) throw new Error(`Invalid input length ${lenIn}`);
      const inputBits = new Int32Array(lenIn);
      for (let i = 0; i < lenIn; i++) inputBits[i] = buffer.readInt();
      operations.push(new _MatrixVectorMath(type, out, matrixId, inputBits));
    }
  };
  _MatrixVectorMath.OP_CODE = 188;
  var MatrixVectorMath = _MatrixVectorMath;

  // third_party/remote-compose-player/src/core/operations/TextTransform.ts
  var _TextTransform = class _TextTransform extends Operation {
    constructor(textId, srcId, startBits, lenBits, operation) {
      super();
      this.mTextId = textId;
      this.mSrcId = srcId;
      this.mStartBits = startBits;
      this.mLenBits = lenBits;
      this.mOperation = operation;
    }
    write(_buffer) {
    }
    apply(context) {
      let text = context.getText(this.mSrcId) || "";
      const start = isNaNBits(this.mStartBits) ? 0 : Math.trunc(intBitsToFloat(this.mStartBits));
      const len = isNaNBits(this.mLenBits) ? text.length : Math.trunc(intBitsToFloat(this.mLenBits));
      if (start > 0 || len < text.length) {
        text = text.substring(start, start + len);
      }
      switch (this.mOperation) {
        case _TextTransform.TEXT_TO_LOWERCASE:
          text = text.toLowerCase();
          break;
        case _TextTransform.TEXT_TO_UPPERCASE:
          text = text.toUpperCase();
          break;
        case _TextTransform.TEXT_TRIM:
          text = text.trim();
          break;
        case _TextTransform.TEXT_CAPITALIZE:
          text = text.replace(/\b\w/g, (c) => c.toUpperCase());
          break;
        case _TextTransform.TEXT_UPPERCASE_FIRST_CHAR:
          if (text.length > 0) text = text[0].toUpperCase() + text.substring(1);
          break;
      }
      context.loadText(this.mTextId, text);
    }
    deepToString(indent) {
      return `${indent}TextTransform(${this.mTextId})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const srcId = buffer.readInt();
      const start = buffer.readInt();
      const len = buffer.readInt();
      const operation = buffer.readInt();
      operations.push(new _TextTransform(textId, srcId, start, len, operation));
    }
  };
  _TextTransform.OP_CODE = 199;
  _TextTransform.TEXT_TO_LOWERCASE = 1;
  _TextTransform.TEXT_TO_UPPERCASE = 2;
  _TextTransform.TEXT_TRIM = 3;
  _TextTransform.TEXT_CAPITALIZE = 4;
  _TextTransform.TEXT_UPPERCASE_FIRST_CHAR = 5;
  var TextTransform = _TextTransform;

  // third_party/remote-compose-player/src/core/operations/TextLookup.ts
  var _TextLookup = class _TextLookup extends Operation {
    constructor(textId, dataSetId, indexBits) {
      super();
      this.mTextId = textId;
      this.mDataSetId = dataSetId;
      this.mIndexBits = indexBits;
      this.mOutIndex = isNaNBits(indexBits) ? 0 : intBitsToFloat(indexBits);
    }
    write(_buffer) {
    }
    registerListening(context) {
      if (isNaNBits(this.mIndexBits)) context.listensTo(idFromBits(this.mIndexBits), this);
    }
    updateVariables(context) {
      if (isNaNBits(this.mIndexBits)) {
        this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
      }
    }
    apply(context) {
      if (isNaNBits(this.mIndexBits)) {
        this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
      }
      const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.trunc(this.mOutIndex));
      if (id >= 0) {
        const text = context.getText(id);
        if (text !== null) {
          context.loadText(this.mTextId, text);
        }
      }
    }
    deepToString(indent) {
      return `${indent}TextLookup(textId=${this.mTextId}, dataSet=${this.mDataSetId}, index=${this.mOutIndex})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const dataSetId = buffer.readInt();
      const index = buffer.readInt();
      operations.push(new _TextLookup(textId, dataSetId, index));
    }
  };
  _TextLookup.OP_CODE = 151;
  var TextLookup = _TextLookup;

  // third_party/remote-compose-player/src/core/operations/TextLookupInt.ts
  var _TextLookupInt = class _TextLookupInt extends Operation {
    constructor(textId, dataSetId, indexId) {
      super();
      this.mOutIndex = 0;
      this.mTextId = textId;
      this.mDataSetId = dataSetId;
      this.mIndexId = indexId;
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mIndexId, this);
    }
    updateVariables(context) {
      this.mOutIndex = context.getInteger(this.mIndexId);
    }
    apply(context) {
      this.mOutIndex = context.getInteger(this.mIndexId);
      const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.trunc(this.mOutIndex));
      if (id >= 0) {
        const text = context.getText(id);
        if (text !== null) {
          context.loadText(this.mTextId, text);
        }
      }
    }
    deepToString(indent) {
      return `${indent}TextLookupInt(textId=${this.mTextId}, dataSet=${this.mDataSetId}, indexId=${this.mIndexId})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const dataSetId = buffer.readInt();
      const indexId = buffer.readInt();
      operations.push(new _TextLookupInt(textId, dataSetId, indexId));
    }
  };
  _TextLookupInt.OP_CODE = 153;
  var TextLookupInt = _TextLookupInt;

  // third_party/remote-compose-player/src/core/operations/ColorTheme.ts
  var _ColorTheme = class _ColorTheme extends Operation {
    constructor(id, colorGroupId, lightModeIndex, darkModeIndex, lightModeFallback, darkModeFallback) {
      super();
      this.mCurrentTheme = -1;
      this.mId = id;
      this.mColorGroupId = colorGroupId;
      this.mLightModeIndex = lightModeIndex;
      this.mDarkModeIndex = darkModeIndex;
      this.mLightModeFallback = lightModeFallback;
      this.mDarkModeFallback = darkModeFallback;
      this.mDarkMode = darkModeFallback;
      this.mLightMode = lightModeFallback;
    }
    write(_buffer) {
    }
    setTheme(context, theme) {
      this.mCurrentTheme = theme;
      if (theme === _ColorTheme.THEME_LIGHT) {
        context.loadColor(this.mId, this.mLightMode);
      } else {
        context.loadColor(this.mId, this.mDarkMode);
      }
    }
    apply(context) {
      this.setTheme(context, context.getPaintTheme());
    }
    deepToString(indent) {
      return `${indent}ColorTheme(${this.mId}, light=0x${(this.mLightModeFallback >>> 0).toString(16)}, dark=0x${(this.mDarkModeFallback >>> 0).toString(16)})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const colorGroupId = buffer.readInt();
      const lightModeIndex = buffer.readShort();
      const darkModeIndex = buffer.readShort();
      const lightModeFallback = buffer.readInt();
      const darkModeFallback = buffer.readInt();
      operations.push(new _ColorTheme(
        id,
        colorGroupId,
        lightModeIndex,
        darkModeIndex,
        lightModeFallback,
        darkModeFallback
      ));
    }
  };
  _ColorTheme.OP_CODE = 196;
  _ColorTheme.THEME_LIGHT = -3;
  _ColorTheme.THEME_DARK = -2;
  var ColorTheme = _ColorTheme;

  // third_party/remote-compose-player/src/core/operations/ColorAttribute.ts
  var _ColorAttribute = class _ColorAttribute extends Operation {
    constructor(outputId, colorId, type) {
      super();
      this.mOutputId = outputId;
      this.mColorId = colorId;
      this.mType = type;
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.listensTo(this.mColorId, this);
    }
    apply(context) {
      const color = context.getColor(this.mColorId);
      const a = (color >> 24 & 255) / 255;
      const r = (color >> 16 & 255) / 255;
      const g = (color >> 8 & 255) / 255;
      const b = (color & 255) / 255;
      let result = 0;
      switch (this.mType) {
        case _ColorAttribute.COLOR_RED:
          result = r;
          break;
        case _ColorAttribute.COLOR_GREEN:
          result = g;
          break;
        case _ColorAttribute.COLOR_BLUE:
          result = b;
          break;
        case _ColorAttribute.COLOR_ALPHA:
          result = a;
          break;
        case _ColorAttribute.COLOR_HUE:
        case _ColorAttribute.COLOR_SATURATION:
        case _ColorAttribute.COLOR_BRIGHTNESS: {
          const max = Math.max(r, g, b), min = Math.min(r, g, b);
          const delta = max - min;
          if (this.mType === _ColorAttribute.COLOR_BRIGHTNESS) {
            result = max;
            break;
          }
          if (this.mType === _ColorAttribute.COLOR_SATURATION) {
            result = max === 0 ? 0 : delta / max;
            break;
          }
          if (delta === 0) {
            result = 0;
          } else if (max === r) {
            result = (g - b) / delta % 6 / 6;
          } else if (max === g) {
            result = ((b - r) / delta + 2) / 6;
          } else {
            result = ((r - g) / delta + 4) / 6;
          }
          if (result < 0) result += 1;
          break;
        }
      }
      context.loadFloat(this.mOutputId, result);
    }
    deepToString(indent) {
      return `${indent}ColorAttribute(${this.mOutputId})`;
    }
    static read(buffer, operations) {
      const outputId = buffer.readInt();
      const colorId = buffer.readInt();
      const type = buffer.readShort();
      operations.push(new _ColorAttribute(outputId, colorId, type));
    }
  };
  _ColorAttribute.OP_CODE = 180;
  _ColorAttribute.COLOR_HUE = 0;
  _ColorAttribute.COLOR_SATURATION = 1;
  _ColorAttribute.COLOR_BRIGHTNESS = 2;
  _ColorAttribute.COLOR_RED = 3;
  _ColorAttribute.COLOR_GREEN = 4;
  _ColorAttribute.COLOR_BLUE = 5;
  _ColorAttribute.COLOR_ALPHA = 6;
  var ColorAttribute = _ColorAttribute;

  // third_party/remote-compose-player/src/core/operations/DataMapLookup.ts
  var TYPE_STRING = 0;
  var TYPE_INT = 1;
  var TYPE_FLOAT = 2;
  var TYPE_LONG = 3;
  var TYPE_BOOLEAN = 4;
  var _DataMapLookup = class _DataMapLookup extends Operation {
    constructor(id, mapId, stringId) {
      super();
      this.mId = id;
      this.mMapId = mapId;
      this.mStringId = stringId;
    }
    write(_buffer) {
    }
    apply(context) {
      const str = context.getText(this.mStringId);
      if (!str) return;
      const data = context.getDataMap(this.mMapId);
      if (!data) return;
      const pos = data.getPos(str);
      if (pos < 0) return;
      const type = data.mTypes[pos];
      const dataId = data.mIds[pos];
      switch (type) {
        case TYPE_STRING: {
          const text = context.getText(dataId);
          if (text !== null) context.loadText(this.mId, text);
          break;
        }
        case TYPE_INT:
          context.loadInteger(this.mId, context.getInteger(dataId));
          break;
        case TYPE_FLOAT:
          context.loadFloat(this.mId, context.getFloat(dataId));
          break;
        case TYPE_LONG:
          context.loadInteger(this.mId, context.getLong(dataId) | 0);
          break;
        case TYPE_BOOLEAN:
          context.loadInteger(this.mId, context.getInteger(dataId) ? 1 : 0);
          break;
      }
    }
    deepToString(indent) {
      return `${indent}DataMapLookup(${this.mId}, map=${this.mMapId}, key=${this.mStringId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const mapId = buffer.readInt();
      const stringId = buffer.readInt();
      operations.push(new _DataMapLookup(id, mapId, stringId));
    }
  };
  _DataMapLookup.OP_CODE = 154;
  var DataMapLookup = _DataMapLookup;

  // third_party/remote-compose-player/src/core/operations/TextMeasure.ts
  var MEASURE_WIDTH = 0;
  var MEASURE_HEIGHT = 1;
  var MEASURE_LEFT = 2;
  var MEASURE_RIGHT = 3;
  var MEASURE_TOP = 4;
  var MEASURE_BOTTOM = 5;
  var _TextMeasure = class _TextMeasure extends PaintOperation {
    constructor(id, textId, type) {
      super();
      this.mBounds = new Float32Array(4);
      this.mId = id;
      this.mTextId = textId;
      this.mType = type;
    }
    write(_buffer) {
    }
    paint(context) {
      const val = this.mType & 255;
      const flags = this.mType >> 8;
      context.getTextBounds(this.mTextId, 0, -1, flags, this.mBounds);
      let result;
      switch (val) {
        case MEASURE_WIDTH:
          result = this.mBounds[2] - this.mBounds[0];
          break;
        case MEASURE_HEIGHT:
          result = this.mBounds[3] - this.mBounds[1];
          break;
        case MEASURE_LEFT:
          result = this.mBounds[0];
          break;
        case MEASURE_RIGHT:
          result = this.mBounds[2];
          break;
        case MEASURE_TOP:
          result = this.mBounds[1];
          break;
        case MEASURE_BOTTOM:
          result = this.mBounds[3];
          break;
      }
      if (result !== void 0) context.getContext().loadFloat(this.mId, result);
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const textId = buffer.readInt();
      const type = buffer.readInt();
      operations.push(new _TextMeasure(id, textId, type));
    }
  };
  _TextMeasure.OP_CODE = 155;
  var TextMeasure = _TextMeasure;

  // third_party/remote-compose-player/src/core/operations/TextAttribute.ts
  var MEASURE_WIDTH2 = 0;
  var MEASURE_HEIGHT2 = 1;
  var MEASURE_LEFT2 = 2;
  var MEASURE_RIGHT2 = 3;
  var MEASURE_TOP2 = 4;
  var MEASURE_BOTTOM2 = 5;
  var TEXT_LENGTH = 6;
  var _TextAttribute = class _TextAttribute extends PaintOperation {
    constructor(id, textId, type) {
      super();
      this.mBounds = new Float32Array(4);
      this.mId = id;
      this.mTextId = textId;
      this.mType = type;
    }
    write(_buffer) {
    }
    paint(context) {
      const val = this.mType & 255;
      const flags = this.mType >> 8;
      if (val <= MEASURE_BOTTOM2) {
        context.getTextBounds(this.mTextId, 0, -1, flags, this.mBounds);
      }
      let result = 0;
      switch (val) {
        case MEASURE_WIDTH2:
          result = this.mBounds[2] - this.mBounds[0];
          break;
        case MEASURE_HEIGHT2:
          result = this.mBounds[3] - this.mBounds[1];
          break;
        case MEASURE_LEFT2:
          result = this.mBounds[0];
          break;
        case MEASURE_RIGHT2:
          result = this.mBounds[2];
          break;
        case MEASURE_TOP2:
          result = this.mBounds[1];
          break;
        case MEASURE_BOTTOM2:
          result = this.mBounds[3];
          break;
        case TEXT_LENGTH: {
          const text = context.getText(this.mTextId);
          result = text ? text.length : 0;
          break;
        }
      }
      context.getContext().loadFloat(this.mId, result);
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const textId = buffer.readInt();
      const type = buffer.readShort();
      buffer.readShort();
      operations.push(new _TextAttribute(id, textId, type));
    }
  };
  _TextAttribute.OP_CODE = 170;
  var TextAttribute = _TextAttribute;

  // third_party/remote-compose-player/src/core/operations/TextLength.ts
  var _TextLength = class _TextLength extends Operation {
    constructor(lengthId, textId) {
      super();
      this.mLengthId = lengthId;
      this.mTextId = textId;
    }
    write(_buffer) {
    }
    apply(context) {
      const text = context.getText(this.mTextId);
      context.loadFloat(this.mLengthId, text ? text.length : 0);
    }
    deepToString(indent) {
      return `${indent}TextLength(${this.mLengthId}, ${this.mTextId})`;
    }
    registerListening(context) {
      context.listensTo(this.mTextId, this);
    }
    updateVariables(_context) {
    }
    static read(buffer, operations) {
      const lengthId = buffer.readInt();
      const textId = buffer.readInt();
      operations.push(new _TextLength(lengthId, textId));
    }
  };
  _TextLength.OP_CODE = 156;
  var TextLength = _TextLength;

  // third_party/remote-compose-player/src/core/operations/TextSubtext.ts
  var _TextSubtext = class _TextSubtext extends Operation {
    constructor(textId, srcId, startBits, lenBits) {
      super();
      this.mTextId = textId;
      this.mSrcId = srcId;
      this.mStartBits = startBits;
      this.mLenBits = lenBits;
      this.mOutStart = isNaNBits(startBits) ? 0 : intBitsToFloat(startBits);
      this.mOutLen = isNaNBits(lenBits) ? 0 : intBitsToFloat(lenBits);
    }
    write(_buffer) {
    }
    apply(context) {
      const str = context.getText(this.mSrcId);
      if (!str) {
        context.loadText(this.mTextId, "");
        return;
      }
      const s = Math.max(0, Math.min(Math.floor(this.mOutStart), str.length));
      let out;
      if (this.mOutLen === -1) {
        out = str.substring(s);
      } else {
        const e = Math.min(s + Math.floor(this.mOutLen), str.length);
        out = str.substring(s, e);
      }
      context.loadText(this.mTextId, out);
    }
    deepToString(indent) {
      return `${indent}TextSubtext(${this.mTextId}, ${this.mSrcId}, ${this.mOutStart}, ${this.mOutLen})`;
    }
    registerListening(context) {
      context.listensTo(this.mSrcId, this);
      if (isNaNBits(this.mStartBits)) {
        context.listensTo(idFromBits(this.mStartBits), this);
      }
      if (isNaNBits(this.mLenBits)) {
        context.listensTo(idFromBits(this.mLenBits), this);
      }
    }
    updateVariables(context) {
      if (isNaNBits(this.mStartBits)) {
        this.mOutStart = context.getFloat(idFromBits(this.mStartBits));
      }
      if (isNaNBits(this.mLenBits)) {
        this.mOutLen = context.getFloat(idFromBits(this.mLenBits));
      }
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const srcId = buffer.readInt();
      const start = buffer.readInt();
      const len = buffer.readInt();
      operations.push(new _TextSubtext(textId, srcId, start, len));
    }
  };
  _TextSubtext.OP_CODE = 182;
  var TextSubtext = _TextSubtext;

  // third_party/remote-compose-player/src/core/operations/ParticleOperations.ts
  var OFFSET2 = 3211264;
  var ID_REGION_MASK = 7340032;
  var ID_REGION_ARRAY2 = 2097152;
  function isMathOperatorBits(b) {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return id > OFFSET2 && id <= OFFSET2 + 79;
  }
  function isDataVariableBits2(b) {
    if (!isNaNBits(b)) return false;
    const id = idFromBits(b);
    return (id & ID_REGION_MASK) === ID_REGION_ARRAY2;
  }
  var asNan3 = (v) => (v | 4286578688) >>> 0;
  var CMD1_BITS = asNan3(OFFSET2 + 64) | 0;
  var CMD2_BITS = asNan3(OFFSET2 + 65) | 0;
  var NOP_BITS = asNan3(OFFSET2 + 55) | 0;
  function resolvePairEquation(src, context, varIds, particle1, particle2) {
    const out = new Int32Array(src.length);
    for (let i = 0; i < src.length; i++) {
      const b = src[i];
      out[i] = isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits2(b) ? floatToRawIntBits(context.getFloat(idFromBits(b))) : b;
      if (i + 1 >= src.length) continue;
      for (let k = 0; k < varIds.length; k++) {
        if (!isNaNBits(b) || idFromBits(b) !== varIds[k]) continue;
        if (src[i + 1] === CMD1_BITS) {
          out[i] = floatToRawIntBits(particle1[k]);
          out[i + 1] = NOP_BITS;
          i++;
        } else if (src[i + 1] === CMD2_BITS) {
          out[i] = floatToRawIntBits(particle2[k]);
          out[i + 1] = NOP_BITS;
          i++;
        }
      }
    }
    return out;
  }
  function resolveEquation(src, context) {
    const out = new Int32Array(src.length);
    for (let i = 0; i < src.length; i++) {
      const b = src[i];
      if (isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits2(b)) {
        out[i] = floatToRawIntBits(context.getFloat(idFromBits(b)));
      } else {
        out[i] = b;
      }
    }
    return out;
  }
  function registerEquationListening(eq, context, op) {
    for (let i = 0; i < eq.length; i++) {
      const b = eq[i];
      if (isNaNBits(b) && !isMathOperatorBits(b) && !isDataVariableBits2(b)) {
        context.listensTo(idFromBits(b), op);
      }
    }
  }
  var _ParticlesCreateOp = class _ParticlesCreateOp extends Operation {
    constructor(id, particleCount, varId, equations) {
      super();
      this.mInitialized = false;
      this.mContext = null;
      this.mId = id;
      this.mParticleCount = particleCount;
      this.mVarId = varId;
      this.mEquations = equations;
      this.mOutEquations = equations.map((eq) => new Int32Array(eq));
      this.mParticles = [];
      for (let i = 0; i < particleCount; i++) {
        this.mParticles.push(new Array(varId.length).fill(0));
      }
    }
    write(_buffer) {
    }
    registerListening(context) {
      context.putObject(this.mId, this);
      for (const eq of this.mEquations) {
        registerEquationListening(eq, context, this);
      }
    }
    updateVariables(context) {
      this.mContext = context;
      for (let j = 0; j < this.mEquations.length; j++) {
        this.mOutEquations[j] = resolveEquation(this.mEquations[j], context);
      }
    }
    apply(context) {
      if (context.mMode === "PAINT" /* PAINT */ && !this.mInitialized) {
        this.mContext = context;
        for (let i = 0; i < this.mParticleCount; i++) {
          this.initializeParticle(i, context);
        }
        this.mInitialized = true;
      }
    }
    initializeParticle(i, context) {
      const varCount = this.mVarId.length;
      for (let j = 0; j < varCount; j++) {
        this.mParticles[i][j] = FloatExpression.evalRPN(
          context,
          this.mOutEquations[j],
          [i, 0, 0]
        );
      }
    }
    getParticles() {
      return this.mParticles;
    }
    getVariableIds() {
      return this.mVarId;
    }
    deepToString(indent) {
      return `${indent}ParticlesCreateOp(id=${this.mId}, count=${this.mParticleCount}, vars=${this.mVarId.length})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const particleCount = buffer.readInt();
      const varLen = buffer.readInt();
      const varIds = [];
      const equations = [];
      for (let i = 0; i < varLen; i++) {
        varIds.push(buffer.readInt());
        const equLen = buffer.readInt();
        const eq = new Int32Array(equLen);
        for (let j = 0; j < equLen; j++) eq[j] = buffer.readInt();
        equations.push(eq);
      }
      operations.push(new _ParticlesCreateOp(id, particleCount, varIds, equations));
    }
  };
  _ParticlesCreateOp.OP_CODE = 161;
  var ParticlesCreateOp = _ParticlesCreateOp;
  var _ParticlesLoopOp = class _ParticlesLoopOp extends PaintOperation {
    constructor(id, restart, equations) {
      super();
      this.mList = [];
      this.mSource = null;
      this.mId = id;
      this.mRestart = restart;
      this.mOutRestart = new Int32Array(restart);
      this.mEquations = equations;
      this.mOutEquations = equations.map((eq) => new Int32Array(eq));
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    registerListening(context) {
      registerEquationListening(this.mRestart, context, this);
      for (const eq of this.mEquations) {
        registerEquationListening(eq, context, this);
      }
    }
    updateVariables(context) {
      this.mOutRestart = resolveEquation(this.mRestart, context);
      for (let j = 0; j < this.mEquations.length; j++) {
        this.mOutEquations[j] = resolveEquation(this.mEquations[j], context);
      }
    }
    paint(paintContext) {
      const context = paintContext.getContext();
      if (context.mMode !== "PAINT" /* PAINT */) return;
      if (!this.mSource) {
        const obj = context.getObject(this.mId);
        if (obj instanceof ParticlesCreateOp) {
          this.mSource = obj;
        } else {
          return;
        }
      }
      const source = this.mSource;
      const particles = source.getParticles();
      const varIds = source.getVariableIds();
      const varCount = varIds.length;
      for (let i = 0; i < particles.length; i++) {
        for (let j = 0; j < varCount; j++) {
          context.loadFloat(varIds[j], particles[i][j]);
        }
        this.updateVariables(context);
        for (let j = 0; j < this.mOutEquations.length && j < varCount; j++) {
          particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations[j]);
          context.loadFloat(varIds[j], particles[i][j]);
        }
        const restartVal = FloatExpression.evalRPN(context, this.mOutRestart);
        if (restartVal > 0) {
          source.initializeParticle(i, context);
          for (let j = 0; j < varCount; j++) {
            context.loadFloat(varIds[j], particles[i][j]);
          }
          this.updateVariables(context);
          for (let j = 0; j < this.mOutEquations.length && j < varCount; j++) {
            particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations[j]);
            context.loadFloat(varIds[j], particles[i][j]);
          }
        }
        for (const child of this.mList) {
          if (child.isDirty() && typeof child.updateVariables === "function") {
            child.markNotDirty();
            child.updateVariables(context);
          }
          context.incrementOpCount();
          child.apply(context);
        }
      }
      context.needsRepaint();
    }
    deepToString(indent) {
      return `${indent}ParticlesLoopOp(id=${this.mId}, children=${this.mList.length})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const restartLen = buffer.readInt();
      const restart = new Int32Array(restartLen);
      for (let i = 0; i < restartLen; i++) restart[i] = buffer.readInt();
      const varLen = buffer.readInt();
      const equations = [];
      for (let i = 0; i < varLen; i++) {
        const equLen = buffer.readInt();
        const eq = new Int32Array(equLen);
        for (let j = 0; j < equLen; j++) eq[j] = buffer.readInt();
        equations.push(eq);
      }
      operations.push(new _ParticlesLoopOp(id, restart, equations));
    }
  };
  _ParticlesLoopOp.OP_CODE = 163;
  var ParticlesLoopOp = _ParticlesLoopOp;
  var _ParticlesCompareOp = class _ParticlesCompareOp extends PaintOperation {
    constructor(id, flags, min, max, condition, equations1, equations2) {
      super();
      this.mList = [];
      this.mSource = null;
      this.mId = id;
      this.mFlags = flags;
      this.mMin = min;
      this.mMax = max;
      this.mCondition = condition;
      this.mOutCondition = new Int32Array(condition);
      this.mEquations1 = equations1;
      this.mOutEquations1 = equations1.map((eq) => new Int32Array(eq));
      this.mEquations2 = equations2;
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    registerListening(context) {
      registerEquationListening(this.mCondition, context, this);
      for (const eq of this.mEquations1) {
        registerEquationListening(eq, context, this);
      }
      for (const eq of this.mEquations2) {
        registerEquationListening(eq, context, this);
      }
    }
    updateVariables(context) {
      this.mOutCondition = resolveEquation(this.mCondition, context);
      for (let j = 0; j < this.mEquations1.length; j++) {
        this.mOutEquations1[j] = resolveEquation(this.mEquations1[j], context);
      }
    }
    paint(paintContext) {
      const context = paintContext.getContext();
      if (context.mMode !== "PAINT" /* PAINT */) return;
      if (!this.mSource) {
        const obj = context.getObject(this.mId);
        if (obj instanceof ParticlesCreateOp) {
          this.mSource = obj;
        } else {
          return;
        }
      }
      const source = this.mSource;
      const particles = source.getParticles();
      const varIds = source.getVariableIds();
      const varCount = varIds.length;
      const startIdx = this.mMin < 0 ? 0 : Math.min(this.mMin, particles.length);
      const endIdx = this.mMax < 0 ? particles.length : Math.min(this.mMax, particles.length);
      if (this.mEquations2 && this.mEquations2.length > 0) {
        this.paintPairs(context, particles, varIds, varCount, startIdx, endIdx);
        context.needsRepaint();
        return;
      }
      for (let i = startIdx; i < endIdx; i++) {
        for (let j = 0; j < varCount; j++) {
          context.loadFloat(varIds[j], particles[i][j]);
        }
        this.updateVariables(context);
        const condVal = FloatExpression.evalRPN(context, this.mOutCondition);
        if (condVal > 0) {
          for (let j = 0; j < this.mOutEquations1.length && j < varCount; j++) {
            particles[i][j] = FloatExpression.evalRPN(context, this.mOutEquations1[j]);
            context.loadFloat(varIds[j], particles[i][j]);
          }
          this.runChildren(context);
        }
      }
      context.needsRepaint();
    }
    /** Run the child operations, as `ParticlesCompare.runChildren` does. */
    runChildren(context) {
      for (const child of this.mList) {
        if (typeof child.updateVariables === "function") {
          child.markNotDirty();
          child.updateVariables(context);
        }
        context.incrementOpCount();
        child.apply(context);
      }
    }
    /**
     * Pairwise comparison over distinct particles — the `condition2Body` algorithm.
     *
     * Note the inner loop starts at `k + 1`: only distinct pairs, so a single particle
     * produces nothing at all. Children run twice per matching pair, once after each
     * particle's equation set is applied, which is what the reference does.
     */
    paintPairs(context, particles, varIds, varCount, startIdx, endIdx) {
      for (let k = startIdx; k < endIdx; k++) {
        const particle2 = particles[k];
        for (let i = k + 1; i < endIdx; i++) {
          const particle1 = particles[i];
          for (let j = 0; j < varCount; j++) context.loadFloat(varIds[j], particle1[j]);
          const cond = resolvePairEquation(
            this.mCondition,
            context,
            varIds,
            particle1,
            particle2
          );
          context.incrementOpCount();
          if (!(FloatExpression.evalRPN(context, cond) > 0)) continue;
          const eq1 = this.mEquations1.map((e) => resolvePairEquation(e, context, varIds, particle1, particle2));
          for (let j = 0; j < varCount; j++) context.loadFloat(varIds[j], particle2[j]);
          const eq2 = this.mEquations2.map((e) => resolvePairEquation(e, context, varIds, particle1, particle2));
          for (let j = 0; j < eq1.length && j < varCount; j++) {
            particle1[j] = FloatExpression.evalRPN(context, eq1[j]);
            context.loadFloat(varIds[j], particle1[j]);
          }
          this.runChildren(context);
          for (let j = 0; j < eq2.length && j < varCount; j++) {
            particle2[j] = FloatExpression.evalRPN(context, eq2[j]);
            context.loadFloat(varIds[j], particle2[j]);
          }
          this.runChildren(context);
        }
      }
    }
    deepToString(indent) {
      return `${indent}ParticlesCompareOp(id=${this.mId}, flags=${this.mFlags})`;
    }
    // Read an equation as raw int32 token bits (NaN operator/array ids survive).
    static readEquationBits(buffer) {
      const len = buffer.readInt();
      const arr = new Int32Array(len);
      for (let i = 0; i < len; i++) arr[i] = buffer.readInt();
      return arr;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const flags = buffer.readShort();
      const min = buffer.readFloat();
      const max = buffer.readFloat();
      const condition = _ParticlesCompareOp.readEquationBits(buffer);
      const result1Len = buffer.readInt();
      const equations1 = [];
      for (let i = 0; i < result1Len; i++) {
        equations1.push(_ParticlesCompareOp.readEquationBits(buffer));
      }
      const result2Len = buffer.readInt();
      const equations2 = [];
      for (let i = 0; i < result2Len; i++) {
        equations2.push(_ParticlesCompareOp.readEquationBits(buffer));
      }
      operations.push(new _ParticlesCompareOp(id, flags, min, max, condition, equations1, equations2));
    }
  };
  _ParticlesCompareOp.OP_CODE = 194;
  var ParticlesCompareOp = _ParticlesCompareOp;

  // third_party/remote-compose-player/src/core/operations/layout/managers/FlowLayout.ts
  var _FlowLayout = class _FlowLayout extends RowLayout {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy) {
      super(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy);
      this.mMaxItemsInEachRow = 0;
      this.mMaxLines = 0;
    }
    hasWeight(c) {
      return c instanceof LayoutComponent && c.hasWidthWeight();
    }
    /** Divide children into rows based on available width. */
    segmentComponents(context, maxWidth, maxHeight, measure) {
      const rows = [];
      let currentRow = [];
      rows.push(currentRow);
      let currentWidth = 0;
      for (const c of this.mChildrenComponents) {
        let componentWidth = 0;
        if (measure.get(c).isGone()) {
          componentWidth = 0;
        } else if (this.hasWeight(c)) {
          const wIn = c.getWidthInModifier();
          if (wIn) {
            const min = wIn.getMin();
            if (min !== -1) {
              componentWidth = min * this.getDpScale(context);
            }
          }
        } else {
          c.measure(context, 0, maxWidth, 0, maxHeight, measure);
          const m = measure.get(c);
          componentWidth = m.getW();
        }
        if (componentWidth + currentWidth > maxWidth) {
          currentRow = [];
          rows.push(currentRow);
          currentWidth = 0;
        }
        currentRow.push(c);
        currentWidth += componentWidth;
      }
      return rows;
    }
    computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight, horizontalWrap, verticalWrap, measure, size) {
      for (const c of this.mChildrenComponents) {
        if (c.needsMeasure()) {
          c.measure(context, 0, maxWidth, 0, maxHeight, measure);
        }
      }
      const rows = this.segmentComponents(context, maxWidth, maxHeight, measure);
      const rowSize = new Size();
      let width = minWidth;
      let height = 0;
      for (const row of rows) {
        this.computeWrapSizeForComponents(
          context,
          minWidth,
          maxWidth,
          minHeight,
          maxHeight,
          horizontalWrap,
          verticalWrap,
          measure,
          rowSize,
          row
        );
        width = Math.max(width, rowSize.getWidth());
        height += rowSize.getHeight();
      }
      width = Math.min(Math.max(minWidth, width), maxWidth);
      height = Math.min(Math.max(minHeight, height), maxHeight);
      size.setWidth(width);
      size.setHeight(height);
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      const rows = this.segmentComponents(context, maxWidth, maxHeight, measure);
      for (const row of rows) {
        const mw = maxWidth;
        for (const child of row) {
          child.measure(context, minWidth, mw, minHeight, maxHeight, measure);
        }
      }
    }
    internalLayoutMeasure(context, measure) {
      if (this.mChildrenComponents.length === 0) return;
      const selfMeasure = measure.get(this);
      const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
      const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
      const rows = this.segmentComponents(context, selfWidth, selfHeight, measure);
      let positionX = 0;
      let positionY = 0;
      let rowsHeight = 0;
      for (const row of rows) {
        rowsHeight += this.minIntrinsicHeightForComponents(row);
      }
      switch (this.mVerticalPositioning) {
        case RowLayout.CENTER:
          positionY = (selfHeight - rowsHeight) / 2;
          break;
        case RowLayout.BOTTOM:
          positionY = selfHeight - rowsHeight;
          break;
      }
      const rowSize = new Size();
      const rowWidth = selfWidth;
      for (const row of rows) {
        const rowHeight = this.minIntrinsicHeightForComponents(row);
        this.internalLayoutMeasureForComponents(
          context,
          measure,
          row,
          rowWidth,
          rowHeight,
          positionX,
          positionY,
          rowSize
        );
        positionY += rowSize.getHeight();
      }
    }
    write(buffer) {
      buffer.start(_FlowLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(0);
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
      buffer.writeFloat(this.mSpacedBy);
      buffer.writeInt(this.mMaxItemsInEachRow);
      buffer.writeInt(this.mMaxLines);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}FlowLayout(${this.getComponentId()}, spacing=${this.mSpacedBy})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.declareId();
      const animationId = buffer.declareId();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      const spacedBy = buffer.readFloat();
      const maxItemsInEachRow = buffer.readInt();
      const maxLines = buffer.readInt();
      const op = new _FlowLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning,
        spacedBy
      );
      op.mMaxItemsInEachRow = maxItemsInEachRow;
      op.mMaxLines = maxLines;
      operations.push(op);
    }
  };
  _FlowLayout.OP_CODE = 240;
  var FlowLayout = _FlowLayout;

  // third_party/remote-compose-player/src/core/operations/layout/LoopOperation.ts
  var _LoopOperation = class _LoopOperation extends Operation {
    constructor(indexId, fromBits, stepBits, untilBits) {
      super();
      this.mList = [];
      this.mIndexId = indexId;
      this.mFromBits = fromBits;
      this.mStepBits = stepBits;
      this.mUntilBits = untilBits;
    }
    getList() {
      return this.mList;
    }
    write(_buffer) {
    }
    apply(context) {
      if (context.mMode === "DATA" /* DATA */) {
        for (const op of this.mList) {
          op.apply(context);
        }
        return;
      }
      const from = this.rv(this.mFromBits, context);
      const step = this.rv(this.mStepBits, context);
      const until = this.rv(this.mUntilBits, context);
      if (step <= 0 || !isFinite(from) || !isFinite(until) || !isFinite(step)) return;
      if (this.mIndexId === 0) {
        for (let i = from; i < until; i += step) {
          for (const op of this.mList) {
            context.incrementOpCount();
            op.apply(context);
          }
        }
      } else {
        for (let i = from; i < until; i += step) {
          context.loadFloat(this.mIndexId, i);
          for (const op of this.mList) {
            if (op.isDirty() && typeof op.updateVariables === "function") {
              op.updateVariables(context);
            }
            context.incrementOpCount();
            op.apply(context);
          }
        }
      }
    }
    rv(bits, ctx) {
      return isNaNBits(bits) ? ctx.getFloat(idFromBits(bits)) : intBitsToFloat(bits);
    }
    deepToString(indent) {
      return `${indent}LoopOperation(${intBitsToFloat(this.mFromBits)}..${intBitsToFloat(this.mUntilBits)} step ${intBitsToFloat(this.mStepBits)})`;
    }
    static read(buffer, operations) {
      const indexId = buffer.readInt();
      const from = buffer.readInt();
      const step = buffer.readInt();
      const until = buffer.readInt();
      operations.push(new _LoopOperation(indexId, from, step, until));
    }
  };
  _LoopOperation.OP_CODE = 215;
  var LoopOperation = _LoopOperation;

  // third_party/remote-compose-player/src/core/operations/layout/managers/CoreText.ts
  var P_INT = 1;
  var P_FLOAT = 2;
  var P_SHORT = 3;
  var P_BYTE = 4;
  var P_BOOLEAN = 5;
  var PA_INT = 6;
  var PA_FLOAT = 7;
  var PA_STRING = 8;
  var P_COMPONENT_ID = 1;
  var P_ANIMATION_ID2 = 2;
  var P_COLOR2 = 3;
  var P_COLOR_ID2 = 4;
  var P_FONT_SIZE2 = 5;
  var P_FONT_STYLE2 = 6;
  var P_FONT_WEIGHT2 = 7;
  var P_FONT_FAMILY2 = 8;
  var P_TEXT_ALIGN2 = 9;
  var P_OVERFLOW2 = 10;
  var P_MAX_LINES2 = 11;
  var P_LETTER_SPACING2 = 12;
  var P_LINE_HEIGHT_ADD2 = 13;
  var P_LINE_HEIGHT_MULTIPLIER2 = 14;
  var P_BREAK_STRATEGY2 = 15;
  var P_HYPHENATION_FREQUENCY2 = 16;
  var P_JUSTIFICATION_MODE2 = 17;
  var P_UNDERLINE2 = 18;
  var P_STRIKETHROUGH2 = 19;
  var P_FONT_AXIS2 = 20;
  var P_FONT_AXIS_VALUES2 = 21;
  var P_AUTOSIZE2 = 22;
  var P_FLAGS2 = 23;
  var P_TEXT_STYLE_ID = 24;
  var P_MIN_FONT_SIZE2 = 25;
  var P_MAX_FONT_SIZE2 = 26;
  var PARAM_TYPES = {
    [P_COMPONENT_ID]: P_INT,
    [P_ANIMATION_ID2]: P_INT,
    [P_COLOR2]: P_INT,
    [P_COLOR_ID2]: P_INT,
    [P_FONT_SIZE2]: P_FLOAT,
    [P_FONT_STYLE2]: P_INT,
    [P_FONT_WEIGHT2]: P_FLOAT,
    [P_FONT_FAMILY2]: P_INT,
    [P_TEXT_ALIGN2]: P_INT,
    [P_OVERFLOW2]: P_INT,
    [P_MAX_LINES2]: P_INT,
    [P_LETTER_SPACING2]: P_FLOAT,
    [P_LINE_HEIGHT_ADD2]: P_FLOAT,
    [P_LINE_HEIGHT_MULTIPLIER2]: P_FLOAT,
    [P_BREAK_STRATEGY2]: P_INT,
    [P_HYPHENATION_FREQUENCY2]: P_INT,
    [P_JUSTIFICATION_MODE2]: P_INT,
    [P_UNDERLINE2]: P_BOOLEAN,
    [P_STRIKETHROUGH2]: P_BOOLEAN,
    [P_FONT_AXIS2]: PA_INT,
    [P_FONT_AXIS_VALUES2]: PA_FLOAT,
    [P_AUTOSIZE2]: P_BOOLEAN,
    [P_FLAGS2]: P_INT,
    [P_TEXT_STYLE_ID]: P_INT,
    [P_MIN_FONT_SIZE2]: P_FLOAT,
    [P_MAX_FONT_SIZE2]: P_FLOAT
  };
  var TEXT_ALIGN_LEFT = 1;
  var TEXT_ALIGN_RIGHT = 2;
  var TEXT_ALIGN_CENTER = 3;
  var TEXT_ALIGN_START = 5;
  var TEXT_ALIGN_END = 6;
  var OVERFLOW_VISIBLE = 2;
  var OVERFLOW_ELLIPSIS = 3;
  var OVERFLOW_START_ELLIPSIS = 4;
  var OVERFLOW_MIDDLE_ELLIPSIS = 5;
  function readCommandParams(buffer, count, cb) {
    for (let i = 0; i < count; i++) {
      const paramId = buffer.readByte();
      const type = PARAM_TYPES[paramId];
      if (type === void 0) {
        throw new Error(`CoreText: unknown param id ${paramId}`);
      }
      switch (type) {
        case P_INT:
          cb.intValue(paramId, buffer.readInt());
          break;
        case P_FLOAT:
          cb.floatValue(paramId, buffer.readInt());
          break;
        case P_SHORT:
          buffer.readShort();
          break;
        case P_BYTE:
          buffer.readByte();
          break;
        case P_BOOLEAN:
          cb.boolValue(paramId, buffer.readBoolean());
          break;
        case PA_INT: {
          const len = buffer.readShort();
          const arr = [];
          for (let j = 0; j < len; j++) arr.push(buffer.readInt());
          cb.intArrayValue(paramId, arr);
          break;
        }
        case PA_FLOAT: {
          const len = buffer.readShort();
          const arr = [];
          for (let j = 0; j < len; j++) arr.push(buffer.readInt());
          cb.floatArrayValue(paramId, arr);
          break;
        }
        case PA_STRING:
          buffer.readUTF8();
          break;
      }
    }
  }
  var _CoreText = class _CoreText extends LayoutManager {
    constructor(componentId, animationId, textId, color, colorId, fontSize, minFontSize, maxFontSize, fontStyle, fontWeight, fontFamilyId, textAlign, overflow, maxLines, letterSpacing, lineHeightAdd, lineHeightMultiplier, lineBreakStrategy, hyphenationFrequency, justificationMode, underline, strikethrough, fontAxis, fontAxisValues, autosize, flags) {
      super(componentId, animationId);
      this.mType = -1;
      this.mTextX = 0;
      this.mTextY = 0;
      this.mTextW = -1;
      this.mTextH = -1;
      this.mBaseline = 0;
      this.mCachedString = null;
      this.mNewString = null;
      this.mComputedTextLayout = null;
      this.mPaint = new PaintBundle();
      this.mTextId = textId;
      this.mColor = color;
      this.mColorId = colorId;
      this.mIsDynamicColorEnabled = colorId !== -1;
      this.mColorValue = this.mIsDynamicColorEnabled ? -1 : color;
      this.mFontSize = fontSize;
      this.mMinFontSize = minFontSize;
      this.mMaxFontSize = maxFontSize;
      this.mFontSizeValue = isNaNBits(fontSize) ? 16 : intBitsToFloat(fontSize);
      this.mMeasureFontSize = this.mFontSizeValue;
      this.mFontStyle = fontStyle;
      this.mFontWeight = fontWeight;
      this.mFontWeightValue = isNaNBits(fontWeight) ? 0 : intBitsToFloat(fontWeight);
      this.mFontFamilyId = fontFamilyId;
      this.mTextAlign = textAlign;
      this.mTextAlignValue = textAlign & 65535;
      this.mOverflow = overflow;
      this.mMaxLines = maxLines;
      this.mLetterSpacing = letterSpacing;
      this.mLineHeightAdd = lineHeightAdd;
      this.mLineHeightMultiplier = lineHeightMultiplier;
      this.mLineBreakStrategy = lineBreakStrategy;
      this.mHyphenationFrequency = hyphenationFrequency;
      this.mJustificationMode = justificationMode;
      this.mUnderline = underline;
      this.mStrikethrough = strikethrough;
      this.mFontAxis = fontAxis;
      this.mFontAxisValues = fontAxisValues;
      this.mAutosize = autosize;
      this.mFlags = flags;
    }
    registerListening(context) {
      if (this.mTextId !== -1) {
        context.listensTo(this.mTextId, this);
      }
      if (isNaNBits(this.mFontSize)) {
        context.listensTo(idFromBits(this.mFontSize), this);
      }
      if (isNaNBits(this.mFontWeight)) {
        context.listensTo(idFromBits(this.mFontWeight), this);
      }
      if (this.mIsDynamicColorEnabled) {
        context.listensTo(this.mColorId, this);
      }
    }
    updateVariables(context) {
      this.mFontSizeValue = isNaNBits(this.mFontSize) ? context.getFloat(idFromBits(this.mFontSize)) : intBitsToFloat(this.mFontSize);
      this.mFontWeightValue = isNaNBits(this.mFontWeight) ? context.getFloat(idFromBits(this.mFontWeight)) : intBitsToFloat(this.mFontWeight);
      this.mTextAlignValue = this.mTextAlign & 65535;
      this.mColorValue = this.mIsDynamicColorEnabled ? context.getColor(this.mColorId) : this.mColor;
      const cachedString = context.getText(this.mTextId);
      if (cachedString !== null && cachedString === this.mCachedString && this.mType !== -1) {
        if (this.mMeasureFontSize !== this.mFontSizeValue) {
          this.invalidateMeasure();
        }
        return;
      }
      this.mNewString = cachedString;
      if (this.mType === -1) {
        if (this.mFontFamilyId !== -1) {
          const fontFamily = context.getText(this.mFontFamilyId);
          if (fontFamily !== null) {
            if (fontFamily.toLowerCase() === "default") this.mType = 0;
            else if (fontFamily.toLowerCase() === "sans-serif") this.mType = 1;
            else if (fontFamily.toLowerCase() === "serif") this.mType = 2;
            else if (fontFamily.toLowerCase() === "monospace") this.mType = 3;
            else this.mType = this.mFontFamilyId;
          }
        } else {
          this.mType = 0;
        }
      }
      this.invalidateMeasure();
    }
    computeWrapSize(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      this.mMeasureFontSize = this.mFontSizeValue;
      context.savePaint();
      this.mPaint.reset();
      this.mPaint.setTextSize(this.mMeasureFontSize);
      this.mPaint.setTextStyle(
        this.mType === -1 ? 0 : this.mType,
        Math.round(this.mFontWeightValue),
        this.mFontStyle === 1
      );
      if (this.mFontAxis && this.mFontAxis.length > 0) {
        const values = this.mFontAxisValues.map((b) => isVariableBits(b) ? context.getContext().getFloat(idFromBits(b)) : intBitsToFloat(b));
        this.mPaint.setTextAxis(this.mFontAxis, values);
      }
      this.mPaint.setColor(this.mColorValue);
      context.replacePaint(this.mPaint);
      const bounds = new Float32Array(4);
      if (this.mNewString !== null && this.mNewString !== this.mCachedString) {
        this.mCachedString = this.mNewString;
      }
      if (this.mCachedString === null) {
        context.restorePaint();
        return;
      }
      if (this.mAutosize) {
        const step = 0.5;
        const minSize = this.mMinFontSize > 0 ? this.mMinFontSize : 4;
        const maxSize = this.mMaxFontSize > 0 ? this.mMaxFontSize : 400;
        let low = minSize;
        let high = maxSize;
        let candidate = (low + high) / 2;
        while (high - low >= step) {
          this.mPaint.setTextSize(candidate);
          context.replacePaint(this.mPaint);
          this.textLayout(context, maxWidth, maxHeight, bounds);
          if (bounds[3] - bounds[1] < maxHeight) low = candidate;
          else high = candidate;
          candidate = (low + high) / 2;
        }
        candidate = Math.floor((low - minSize) / step) * step + minSize;
        this.mMeasureFontSize = candidate;
        this.mFontSizeValue = candidate;
        this.mPaint.setTextSize(candidate);
        context.replacePaint(this.mPaint);
      }
      this.textLayout(context, maxWidth, maxHeight, bounds);
      context.restorePaint();
      const w = bounds[2] - bounds[0];
      const h = bounds[3] - bounds[1];
      size.setWidth(Math.min(maxWidth, w));
      this.mTextX = -bounds[0];
      size.setHeight(Math.min(maxHeight, h));
      this.mTextY = -bounds[1];
      this.mTextW = w;
      this.mTextH = h;
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      super.computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure);
      const tempSize = this.mCachedWrapSize;
      tempSize.clear();
      this.computeWrapSize(
        context,
        minWidth,
        maxWidth,
        minHeight,
        maxHeight,
        true,
        true,
        measure,
        tempSize
      );
      const m = measure.get(this);
      m.setW(tempSize.getWidth());
      m.setH(tempSize.getHeight());
    }
    textLayout(context, maxWidth, maxHeight, bounds) {
      if (maxWidth < 0 || maxHeight < 0) return;
      let flags = PaintContext.TEXT_MEASURE_FONT_HEIGHT | PaintContext.TEXT_MEASURE_SPACES;
      let forceComplex = false;
      if (this.mOverflow === OVERFLOW_START_ELLIPSIS || this.mOverflow === OVERFLOW_MIDDLE_ELLIPSIS || this.mOverflow === OVERFLOW_ELLIPSIS) {
        flags |= PaintContext.TEXT_COMPLEX;
        forceComplex = true;
      }
      if (this.mLetterSpacing !== 0 || this.mLineHeightMultiplier !== 1 || this.mLineHeightAdd > 0 || this.mUnderline || this.mStrikethrough || this.mJustificationMode > 0 || this.mLineBreakStrategy > 0 || this.mHyphenationFrequency > 0) {
        flags |= PaintContext.TEXT_COMPLEX;
        forceComplex = true;
      }
      if (!(flags & PaintContext.TEXT_COMPLEX) && this.mCachedString) {
        for (let i = 0; i < this.mCachedString.length; i++) {
          const c = this.mCachedString.charAt(i);
          if (c === "\n" || c === "	") {
            flags |= PaintContext.TEXT_COMPLEX;
            forceComplex = true;
            break;
          }
        }
      }
      if (!forceComplex) {
        context.getTextBounds(this.mTextId, 0, this.mCachedString.length, flags, bounds);
        this.mBaseline = -bounds[1];
      }
      const autosizeNeedsComplex = this.mAutosize && (this.mOverflow === 1 || this.mOverflow === 2) && maxWidth > 0;
      if (forceComplex || autosizeNeedsComplex || bounds[2] - bounds[0] > maxWidth && this.mMaxLines > 1 && maxWidth > 0) {
        this.mComputedTextLayout = context.layoutComplexText(
          this.mTextId,
          0,
          this.mCachedString.length,
          this.mTextAlign,
          this.mOverflow,
          this.mAutosize && (this.mOverflow === 1 || this.mOverflow === 2) ? 2147483647 : this.mMaxLines,
          maxWidth,
          maxHeight,
          this.mLetterSpacing,
          this.mLineHeightAdd,
          this.mLineHeightMultiplier,
          this.mLineBreakStrategy,
          this.mHyphenationFrequency,
          this.mJustificationMode,
          this.mUnderline,
          this.mStrikethrough,
          flags
        );
        if (this.mComputedTextLayout) {
          bounds[0] = 0;
          bounds[1] = 0;
          bounds[2] = this.mComputedTextLayout.width;
          bounds[3] = this.mComputedTextLayout.naturalHeight ?? this.mComputedTextLayout.height;
        }
      } else {
        this.mComputedTextLayout = null;
      }
    }
    paintingComponent(paintContext) {
      const remoteContext = paintContext.getContext();
      paintContext.save();
      paintContext.translate(this.mX, this.mY);
      const tx = this.mPaddingLeft;
      const ty = this.mPaddingTop;
      paintContext.translate(tx, ty);
      paintContext.savePaint();
      this.mPaint.reset();
      this.mPaint.setStyle(PaintBundle.FILL);
      this.mPaint.setColor(this.mColorValue);
      this.mPaint.setTextSize(this.mMeasureFontSize);
      this.mPaint.setTextStyle(
        this.mType === -1 ? 0 : this.mType,
        Math.round(this.mFontWeightValue),
        this.mFontStyle === 1
      );
      if (this.mFontAxis && this.mFontAxis.length > 0) {
        const values = this.mFontAxisValues.map((b) => isVariableBits(b) ? remoteContext.getFloat(idFromBits(b)) : intBitsToFloat(b));
        this.mPaint.setTextAxis(this.mFontAxis, values);
      }
      paintContext.replacePaint(this.mPaint);
      if (this.mCachedString === null) {
        paintContext.restorePaint();
        paintContext.restore();
        return;
      }
      const length = this.mCachedString.length;
      if (this.mComputedTextLayout) {
        if (this.mOverflow !== OVERFLOW_VISIBLE) {
          paintContext.save();
          paintContext.clipRect(
            0,
            0,
            this.mWidth - this.mPaddingLeft - this.mPaddingRight,
            this.mHeight - this.mPaddingTop - this.mPaddingBottom
          );
          paintContext.drawComplexText(this.mComputedTextLayout);
          paintContext.restore();
        } else {
          paintContext.drawComplexText(this.mComputedTextLayout);
        }
      } else {
        let px = this.mTextX;
        switch (this.mTextAlignValue) {
          case TEXT_ALIGN_CENTER:
            px = (this.mWidth - this.mPaddingLeft - this.mPaddingRight - this.mTextW) / 2;
            break;
          case TEXT_ALIGN_RIGHT:
          case TEXT_ALIGN_END:
            px = this.mWidth - this.mPaddingLeft - this.mPaddingRight - this.mTextW;
            break;
          case TEXT_ALIGN_LEFT:
          case TEXT_ALIGN_START:
          default:
            break;
        }
        const contentW = this.mWidth - this.mPaddingLeft - this.mPaddingRight;
        if (this.mOverflow !== OVERFLOW_VISIBLE || this.mTextW > contentW) {
          paintContext.save();
          paintContext.clipRect(
            0,
            0,
            contentW,
            this.mHeight - this.mPaddingTop - this.mPaddingBottom
          );
          paintContext.drawTextRun(this.mTextId, 0, length, 0, 0, px, this.mTextY, false);
          paintContext.restore();
        } else {
          paintContext.drawTextRun(this.mTextId, 0, length, 0, 0, px, this.mTextY, false);
        }
      }
      paintContext.restorePaint();
      paintContext.restore();
    }
    write(buffer) {
      buffer.start(_CoreText.OP_CODE);
      buffer.writeInt(this.mTextId);
      buffer.writeShort(0);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}CoreText(${this.getComponentId()}, textId=${this.mTextId})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readId();
      const paramsLength = buffer.readShort();
      let componentId = -1, animationId = -1, color = 4278190080 | 0, colorId = -1;
      let fontStyle = 0, fontFamilyId = -1, textAlign = 1, overflow = 1;
      let maxLines = 2147483647, lineBreakStrategy = 0, hyphenationFrequency = 0;
      let justificationMode = 0, flags = 0;
      let fontSize = floatToRawIntBits(16), minFontSize = -1, maxFontSize = -1;
      let fontWeight = floatToRawIntBits(400), letterSpacing = 0, lineHeightAdd = 0, lineHeightMultiplier = 1;
      let underline = false, strikethrough = false, autosize = false;
      let fontAxis = null;
      let fontAxisValues = null;
      readCommandParams(buffer, paramsLength, {
        intValue(id, value) {
          switch (id) {
            case P_COMPONENT_ID:
              componentId = value;
              break;
            case P_ANIMATION_ID2:
              animationId = value;
              break;
            case P_COLOR2:
              color = value;
              break;
            case P_COLOR_ID2:
              colorId = value;
              break;
            case P_FONT_STYLE2:
              fontStyle = value;
              break;
            case P_FONT_FAMILY2:
              fontFamilyId = value;
              break;
            case P_TEXT_ALIGN2:
              textAlign = value;
              break;
            case P_OVERFLOW2:
              overflow = value;
              break;
            case P_MAX_LINES2:
              maxLines = value;
              break;
            case P_BREAK_STRATEGY2:
              lineBreakStrategy = value;
              break;
            case P_HYPHENATION_FREQUENCY2:
              hyphenationFrequency = value;
              break;
            case P_JUSTIFICATION_MODE2:
              justificationMode = value;
              break;
            case P_FLAGS2:
              flags = value;
              break;
            case P_TEXT_STYLE_ID:
              break;
          }
        },
        floatValue(id, bits) {
          switch (id) {
            // Ref-capable: keep raw bits (resolved later).
            case P_FONT_SIZE2:
              fontSize = bits;
              break;
            case P_FONT_WEIGHT2:
              fontWeight = bits;
              break;
            // Non-ref: decode literal float now.
            case P_MIN_FONT_SIZE2:
              minFontSize = intBitsToFloat(bits);
              break;
            case P_MAX_FONT_SIZE2:
              maxFontSize = intBitsToFloat(bits);
              break;
            case P_LETTER_SPACING2:
              letterSpacing = intBitsToFloat(bits);
              break;
            case P_LINE_HEIGHT_ADD2:
              lineHeightAdd = intBitsToFloat(bits);
              break;
            case P_LINE_HEIGHT_MULTIPLIER2:
              lineHeightMultiplier = intBitsToFloat(bits);
              break;
          }
        },
        boolValue(id, value) {
          switch (id) {
            case P_UNDERLINE2:
              underline = value;
              break;
            case P_STRIKETHROUGH2:
              strikethrough = value;
              break;
            case P_AUTOSIZE2:
              autosize = value;
              break;
          }
        },
        intArrayValue(id, values) {
          if (id === P_FONT_AXIS2) fontAxis = values;
        },
        floatArrayValue(id, values) {
          if (id === P_FONT_AXIS_VALUES2) fontAxisValues = values;
        }
      });
      operations.push(new _CoreText(
        componentId,
        animationId,
        textId,
        color,
        colorId,
        fontSize,
        minFontSize,
        maxFontSize,
        fontStyle,
        fontWeight,
        fontFamilyId,
        textAlign,
        overflow,
        maxLines,
        letterSpacing,
        lineHeightAdd,
        lineHeightMultiplier,
        lineBreakStrategy,
        hyphenationFrequency,
        justificationMode,
        underline,
        strikethrough,
        fontAxis,
        fontAxisValues,
        autosize,
        flags
      ));
    }
  };
  // Typed as `number` rather than the literal 239 so TextLayout (208) can
  // subclass this without a static-side type clash.
  _CoreText.OP_CODE = 239;
  var CoreText = _CoreText;

  // third_party/remote-compose-player/src/core/operations/layout/managers/TextLayout.ts
  var FLAG_IS_DYNAMIC_COLOR = 1;
  var _TextLayout = class _TextLayout extends CoreText {
    deepToString(indent) {
      return `${indent}TEXT_LAYOUT [${this.getComponentId()}]`;
    }
    static read(buffer, operations) {
      const componentId = buffer.declareId();
      const animationId = buffer.declareId();
      const textId = buffer.readId();
      const color = buffer.readInt();
      const fontSize = buffer.readNanIdBits();
      const fontStyle = buffer.readInt();
      const fontWeight = buffer.readNanIdBits();
      const fontFamilyId = buffer.readId();
      const textAlign = buffer.readInt();
      const overflow = buffer.readInt();
      const maxLines = buffer.readInt();
      const dynamicColor = (textAlign >>> 16 & FLAG_IS_DYNAMIC_COLOR) > 0;
      operations.push(new _TextLayout(
        componentId,
        animationId,
        textId,
        dynamicColor ? 4278190080 | 0 : color,
        dynamicColor ? color : -1,
        fontSize,
        -1,
        // minFontSize — not carried by this op
        -1,
        // maxFontSize
        fontStyle,
        fontWeight,
        fontFamilyId,
        textAlign,
        overflow,
        maxLines,
        0,
        // letterSpacing
        0,
        // lineHeightAdd
        1,
        // lineHeightMultiplier
        0,
        // lineBreakStrategy
        0,
        // hyphenationFrequency
        0,
        // justificationMode
        false,
        // underline
        false,
        // strikethrough
        null,
        // fontAxis
        null,
        // fontAxisValues
        false,
        // autosize
        0
        // flags
      ));
    }
  };
  _TextLayout.OP_CODE = 208;
  var TextLayout = _TextLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/FitBoxLayout.ts
  var _FitBoxLayout = class _FitBoxLayout extends LayoutManager {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning) {
      super(componentId, animationId);
      this.mAnimationId = animationId;
      this.mHorizontalPositioning = horizontalPositioning;
      this.mVerticalPositioning = verticalPositioning;
    }
    computeWrapSize(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      let found = false;
      const self = measure.get(this);
      const dp = this.getDpScale(context);
      for (const c of this.mChildrenComponents) {
        let cw = 0;
        let ch = 0;
        if (c instanceof LayoutComponent) {
          const wIn = c.getWidthInModifier();
          if (wIn) cw = wIn.getMin() * dp;
          const hIn = c.getHeightInModifier();
          if (hIn) ch = hIn.getMin() * dp;
        }
        c.measure(context, 0, maxWidth, 0, maxHeight, measure);
        const m = measure.get(c);
        if (!found && cw <= maxWidth && ch <= maxHeight) {
          found = true;
          m.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
          size.setWidth(m.getW());
          size.setHeight(m.getH());
        } else {
          m.addVisibilityOverride(Visibility.OVERRIDE_GONE);
        }
      }
      if (!found) {
        self.setVisibility(Visibility.GONE);
      } else {
        self.setVisibility(Visibility.VISIBLE);
      }
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      let found = false;
      const dp = this.getDpScale(context);
      for (const c of this.mChildrenComponents) {
        let cw = 0;
        let ch = 0;
        if (c instanceof LayoutComponent) {
          const wIn = c.getWidthInModifier();
          if (wIn) cw = wIn.getMin() * dp;
          const hIn = c.getHeightInModifier();
          if (hIn) ch = hIn.getMin() * dp;
        }
        c.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        const m = measure.get(c);
        m.clearVisibilityOverride();
        if (!found && cw <= maxWidth && ch <= maxHeight) {
          found = true;
          m.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
        } else {
          m.addVisibilityOverride(Visibility.OVERRIDE_GONE);
        }
      }
    }
    internalLayoutMeasure(_context, measure) {
      const selfMeasure = measure.get(this);
      const selfWidth = selfMeasure.getW() - this.mPaddingLeft - this.mPaddingRight;
      const selfHeight = selfMeasure.getH() - this.mPaddingTop - this.mPaddingBottom;
      for (const child of this.mChildrenComponents) {
        const m = measure.get(child);
        let tx = 0;
        let ty = 0;
        switch (this.mVerticalPositioning) {
          case _FitBoxLayout.TOP:
            ty = 0;
            break;
          case _FitBoxLayout.CENTER:
            ty = (selfHeight - m.getH()) / 2;
            break;
          case _FitBoxLayout.BOTTOM:
            ty = selfHeight - m.getH();
            break;
        }
        switch (this.mHorizontalPositioning) {
          case _FitBoxLayout.START:
            tx = 0;
            break;
          case _FitBoxLayout.CENTER:
            tx = (selfWidth - m.getW()) / 2;
            break;
          case _FitBoxLayout.END:
            tx = selfWidth - m.getW();
            break;
        }
        m.setX(tx);
        m.setY(ty);
      }
    }
    write(buffer) {
      buffer.start(_FitBoxLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.mAnimationId);
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
    }
    deepToString(indent) {
      return `${indent}FitBoxLayout(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      operations.push(new _FitBoxLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning
      ));
    }
  };
  _FitBoxLayout.OP_CODE = 176;
  _FitBoxLayout.START = 1;
  _FitBoxLayout.CENTER = 2;
  _FitBoxLayout.END = 3;
  _FitBoxLayout.TOP = 4;
  _FitBoxLayout.BOTTOM = 5;
  var FitBoxLayout = _FitBoxLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/CollapsiblePriority.ts
  var _CollapsiblePriority = class _CollapsiblePriority {
    static getPriority(c, orientation) {
      if (c instanceof LayoutComponent) {
        const priority = c.selfOrModifier(CollapsiblePriorityModifier);
        if (priority && priority.getOrientation() === orientation) {
          return priority.getPriority();
        }
      }
      return Number.MAX_VALUE;
    }
    static sortWithPriorities(components, orientation) {
      const sorted = [...components];
      sorted.sort((a, b) => {
        const p1 = _CollapsiblePriority.getPriority(a, orientation);
        const p2 = _CollapsiblePriority.getPriority(b, orientation);
        return p2 - p1;
      });
      return sorted;
    }
  };
  _CollapsiblePriority.HORIZONTAL = 0;
  _CollapsiblePriority.VERTICAL = 1;
  var CollapsiblePriority = _CollapsiblePriority;

  // third_party/remote-compose-player/src/core/operations/layout/managers/CollapsibleRowLayout.ts
  var _CollapsibleRowLayout = class _CollapsibleRowLayout extends RowLayout {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy) {
      super(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy);
    }
    computeWrapSize(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      this.computeVisibleChildren(context, maxWidth, maxHeight, true, measure, size);
    }
    computeSize(context, _minWidth, maxWidth, _minHeight, maxHeight, measure) {
      this.computeVisibleChildren(context, maxWidth, maxHeight, false, measure, null);
    }
    internalLayoutMeasure(context, measure) {
      super.internalLayoutMeasure(context, measure);
      const m = measure.get(this);
      this.computeVisibleChildren(context, m.getW(), m.getH(), false, measure, null);
    }
    computeVisibleChildren(context, maxWidth, maxHeight, horizontalWrap, measure, size) {
      let visibleChildren = 0;
      const self = measure.get(this);
      self.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
      let currentMaxWidth = maxWidth;
      let hasPriorities = false;
      for (const c of this.mChildrenComponents) {
        if (!measure.contains(c.getComponentId())) {
          if (c instanceof _CollapsibleRowLayout) {
            c.measure(context, 0, currentMaxWidth, 0, maxHeight, measure);
          } else {
            c.measure(context, 0, Number.MAX_VALUE, 0, maxHeight, measure);
          }
        }
        const m = measure.get(c);
        if (!m.isGone()) {
          if (size !== null) {
            size.setHeight(Math.max(size.getHeight(), m.getH()));
            size.setWidth(size.getWidth() + m.getW());
          }
          visibleChildren++;
          currentMaxWidth -= m.getW();
        }
        if (c instanceof LayoutComponent) {
          const priority = c.selfOrModifier(CollapsiblePriorityModifier);
          if (priority) hasPriorities = true;
        }
      }
      if (this.mChildrenComponents.length > 0 && size !== null) {
        size.setWidth(size.getWidth() + this.mSpacedBy * this.getDpBehaviorScale(context) * (visibleChildren - 1));
      }
      let childrenWidth = 0;
      let childrenHeight = 0;
      let overflow = false;
      let children = this.mChildrenComponents;
      if (hasPriorities) {
        children = CollapsiblePriority.sortWithPriorities(
          this.mChildrenComponents,
          CollapsiblePriority.HORIZONTAL
        );
      }
      for (const child of children) {
        const childMeasure = measure.get(child);
        if (overflow || childMeasure.isGone()) {
          childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
          continue;
        }
        const childWidth = childMeasure.getW();
        if (childrenWidth + childWidth > maxWidth) {
          childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
          overflow = true;
        } else {
          childrenWidth += childWidth;
          childrenHeight = Math.max(childrenHeight, childMeasure.getH());
          visibleChildren++;
        }
      }
      if (horizontalWrap && size !== null) {
        size.setWidth(Math.min(maxWidth, childrenWidth));
      }
      if (visibleChildren === 0 || size !== null && size.getWidth() <= 0) {
        self.addVisibilityOverride(Visibility.OVERRIDE_GONE);
      }
    }
    write(buffer) {
      buffer.start(_CollapsibleRowLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.getAnimationId());
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
      buffer.writeFloat(this.mSpacedBy);
    }
    deepToString(indent) {
      return `${indent}CollapsibleRowLayout(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      const spacedBy = buffer.readFloat();
      operations.push(new _CollapsibleRowLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning,
        spacedBy
      ));
    }
  };
  _CollapsibleRowLayout.OP_CODE = 230;
  var CollapsibleRowLayout = _CollapsibleRowLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/CollapsibleColumnLayout.ts
  var _CollapsibleColumnLayout = class _CollapsibleColumnLayout extends ColumnLayout {
    constructor(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy) {
      super(componentId, animationId, horizontalPositioning, verticalPositioning, spacedBy);
    }
    computeWrapSize(context, _minWidth, maxWidth, _minHeight, maxHeight, _horizontalWrap, _verticalWrap, measure, size) {
      this.computeVisibleChildren(context, maxWidth, maxHeight, true, measure, size);
    }
    computeSize(context, _minWidth, maxWidth, _minHeight, maxHeight, measure) {
      this.computeVisibleChildren(context, maxWidth, maxHeight, false, measure, null);
    }
    internalLayoutMeasure(context, measure) {
      super.internalLayoutMeasure(context, measure);
      const m = measure.get(this);
      this.computeVisibleChildren(context, m.getW(), m.getH(), false, measure, null);
    }
    computeVisibleChildren(context, maxWidth, maxHeight, verticalWrap, measure, size) {
      let visibleChildren = 0;
      const self = measure.get(this);
      self.addVisibilityOverride(Visibility.OVERRIDE_VISIBLE);
      let currentMaxHeight = maxHeight;
      let hasPriorities = false;
      for (const c of this.mChildrenComponents) {
        if (!measure.contains(c.getComponentId())) {
          if (c instanceof _CollapsibleColumnLayout) {
            c.measure(context, 0, maxWidth, 0, currentMaxHeight, measure);
          } else {
            c.measure(context, 0, maxWidth, 0, Number.MAX_VALUE, measure);
          }
        }
        const m = measure.get(c);
        if (!m.isGone()) {
          if (size !== null) {
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(size.getHeight() + m.getH());
          }
          visibleChildren++;
          currentMaxHeight -= m.getH();
        }
        if (c instanceof LayoutComponent) {
          const priority = c.selfOrModifier(CollapsiblePriorityModifier);
          if (priority) hasPriorities = true;
        }
      }
      if (this.mChildrenComponents.length > 0 && size !== null) {
        size.setHeight(size.getHeight() + this.mSpacedBy * this.getDpBehaviorScale(context) * (visibleChildren - 1));
      }
      let childrenWidth = 0;
      let childrenHeight = 0;
      let overflow = false;
      let children = this.mChildrenComponents;
      if (hasPriorities) {
        children = CollapsiblePriority.sortWithPriorities(
          this.mChildrenComponents,
          CollapsiblePriority.VERTICAL
        );
      }
      for (const child of children) {
        const childMeasure = measure.get(child);
        if (overflow || childMeasure.isGone()) {
          childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
          continue;
        }
        const childHeight = childMeasure.getH();
        if (childrenHeight + childHeight > maxHeight) {
          childMeasure.addVisibilityOverride(Visibility.OVERRIDE_GONE);
          overflow = true;
        } else {
          childrenHeight += childHeight;
          childrenWidth = Math.max(childrenWidth, childMeasure.getW());
          visibleChildren++;
        }
      }
      if (verticalWrap && size !== null) {
        size.setHeight(Math.min(maxHeight, childrenHeight));
      }
      if (visibleChildren === 0 || size !== null && size.getHeight() <= 0) {
        self.addVisibilityOverride(Visibility.OVERRIDE_GONE);
      }
    }
    write(buffer) {
      buffer.start(_CollapsibleColumnLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.getAnimationId());
      buffer.writeInt(this.mHorizontalPositioning);
      buffer.writeInt(this.mVerticalPositioning);
      buffer.writeFloat(this.mSpacedBy);
    }
    deepToString(indent) {
      return `${indent}CollapsibleColumnLayout(${this.getComponentId()})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      const horizontalPositioning = buffer.readInt();
      const verticalPositioning = buffer.readInt();
      const spacedBy = buffer.readFloat();
      operations.push(new _CollapsibleColumnLayout(
        componentId,
        animationId,
        horizontalPositioning,
        verticalPositioning,
        spacedBy
      ));
    }
  };
  _CollapsibleColumnLayout.OP_CODE = 233;
  var CollapsibleColumnLayout = _CollapsibleColumnLayout;

  // third_party/remote-compose-player/src/core/operations/IdLookup.ts
  var _IdLookup = class _IdLookup extends Operation {
    constructor(textId, dataSetId, indexBits) {
      super();
      this.mTextId = textId;
      this.mDataSetId = dataSetId;
      this.mIndexBits = indexBits;
      this.mOutIndex = isNaNBits(indexBits) ? 0 : intBitsToFloat(indexBits);
    }
    updateVariables(context) {
      if (isNaNBits(this.mIndexBits)) {
        this.mOutIndex = context.getFloat(idFromBits(this.mIndexBits));
      }
    }
    registerListening(context) {
      if (isNaNBits(this.mIndexBits)) {
        context.listensTo(idFromBits(this.mIndexBits), this);
      }
    }
    apply(context) {
      const id = context.getCollectionsAccess().getId(this.mDataSetId, Math.floor(this.mOutIndex));
      context.loadInteger(this.mTextId, id);
    }
    write(buffer) {
      buffer.start(_IdLookup.OP_CODE);
      buffer.writeInt(this.mTextId);
      buffer.writeInt(this.mDataSetId);
      buffer.writeInt(this.mIndexBits);
    }
    deepToString(indent) {
      return `${indent}IdLookup(textId=${this.mTextId}, dataSetId=${this.mDataSetId}, index=${this.mOutIndex})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const dataSetId = buffer.readInt();
      const index = buffer.readInt();
      operations.push(new _IdLookup(textId, dataSetId, index));
    }
  };
  _IdLookup.OP_CODE = 192;
  var IdLookup = _IdLookup;

  // third_party/remote-compose-player/src/core/operations/UpdateDynamicFloatList.ts
  var _UpdateDynamicFloatList = class _UpdateDynamicFloatList extends Operation {
    constructor(arrayId, indexBits, valueBits) {
      super();
      this.mArrayId = arrayId;
      this.mIndexBits = indexBits;
      this.mIndexOut = isNaNBits(indexBits) ? 0 : intBitsToFloat(indexBits);
      this.mValueBits = valueBits;
      this.mValueOut = isNaNBits(valueBits) ? 0 : intBitsToFloat(valueBits);
    }
    updateVariables(context) {
      this.mIndexOut = isNaNBits(this.mIndexBits) ? context.getFloat(idFromBits(this.mIndexBits)) : intBitsToFloat(this.mIndexBits);
      this.mValueOut = isNaNBits(this.mValueBits) ? context.getFloat(idFromBits(this.mValueBits)) : intBitsToFloat(this.mValueBits);
    }
    registerListening(context) {
      if (isNaNBits(this.mIndexBits)) {
        context.listensTo(idFromBits(this.mIndexBits), this);
      }
      if (isNaNBits(this.mValueBits)) {
        context.listensTo(idFromBits(this.mValueBits), this);
      }
    }
    apply(context) {
      const state = context.mRemoteComposeState;
      const values = state.getDynamicFloats(this.mArrayId);
      if (values !== null) {
        const index = Math.floor(this.mIndexOut);
        if (index < values.length) {
          values[index] = this.mValueOut;
        }
        state.markVariableDirty(this.mArrayId);
      }
    }
    write(buffer) {
      buffer.start(_UpdateDynamicFloatList.OP_CODE);
      buffer.writeInt(this.mArrayId);
      buffer.writeFloat(this.mIndexOut);
      buffer.writeFloat(this.mValueOut);
    }
    deepToString(indent) {
      return `${indent}UpdateDynamicFloatList(arrayId=${this.mArrayId}, index=${this.mIndexOut}, value=${this.mValueOut})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const index = buffer.readInt();
      const value = buffer.readInt();
      operations.push(new _UpdateDynamicFloatList(id, index, value));
    }
  };
  _UpdateDynamicFloatList.OP_CODE = 198;
  var UpdateDynamicFloatList = _UpdateDynamicFloatList;

  // third_party/remote-compose-player/src/core/operations/layout/managers/ImageLayout.ts
  var _ImageLayout = class _ImageLayout extends LayoutManager {
    constructor(componentId, animationId, bitmapId, scaleType, alphaBits) {
      super(componentId, animationId);
      this.mScaling = new ImageScaling();
      this.mPaint = new PaintBundle();
      this.mBitmapId = bitmapId;
      this.mScaleType = scaleType & 255;
      this.mAlphaBits = alphaBits;
      this.mOutAlpha = isNaNBits(alphaBits) ? 1 : intBitsToFloat(alphaBits);
    }
    registerListening(context) {
      if (this.mBitmapId !== -1) {
        context.listensTo(this.mBitmapId, this);
      }
      if (isNaNBits(this.mAlphaBits)) {
        context.listensTo(idFromBits(this.mAlphaBits), this);
      }
    }
    updateVariables(context) {
      this.mOutAlpha = isNaNBits(this.mAlphaBits) ? context.getFloat(idFromBits(this.mAlphaBits)) : intBitsToFloat(this.mAlphaBits);
    }
    computeWrapSize(context, _minWidth, _maxWidth, _minHeight, _maxHeight, _horizontalWrap, _verticalWrap, _measure, size) {
      const bitmapData = context.getContext().getObject(this.mBitmapId);
      if (bitmapData && bitmapData instanceof BitmapData) {
        size.setWidth(bitmapData.getWidth());
        size.setHeight(bitmapData.getHeight());
      }
    }
    computeSize(context, _minWidth, _maxWidth, _minHeight, _maxHeight, measure) {
      const modW = this.computeModifierDefinedWidth();
      const modH = this.computeModifierDefinedHeight();
      const m = measure.get(this);
      if (modW >= 0) m.setW(modW);
      if (modH >= 0) m.setH(modH);
    }
    paintingComponent(paintContext) {
      paintContext.save();
      paintContext.translate(this.mX, this.mY);
      const context = paintContext.getContext();
      const tx = this.mPaddingLeft;
      const ty = this.mPaddingTop;
      paintContext.translate(tx, ty);
      const w = this.mWidth - this.mPaddingLeft - this.mPaddingRight;
      const h = this.mHeight - this.mPaddingTop - this.mPaddingBottom;
      paintContext.clipRect(0, 0, w, h);
      const bitmapData = context.getObject(this.mBitmapId);
      if (bitmapData && bitmapData instanceof BitmapData) {
        this.mScaling.setup(
          0,
          0,
          bitmapData.getWidth(),
          bitmapData.getHeight(),
          0,
          0,
          w,
          h,
          this.mScaleType,
          1
        );
        paintContext.savePaint();
        if (this.mOutAlpha !== 1) {
          this.mPaint.reset();
          this.mPaint.setColor(0, 0, 0, this.mOutAlpha);
          paintContext.applyPaint(this.mPaint);
        }
        paintContext.drawBitmap(
          this.mBitmapId,
          0,
          0,
          bitmapData.getWidth() | 0,
          bitmapData.getHeight() | 0,
          this.mScaling.mFinalDstLeft | 0,
          this.mScaling.mFinalDstTop | 0,
          this.mScaling.mFinalDstRight | 0,
          this.mScaling.mFinalDstBottom | 0,
          -1
        );
        paintContext.restorePaint();
      }
      paintContext.translate(-tx, -ty);
      paintContext.restore();
    }
    write(buffer) {
      buffer.start(_ImageLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(0);
      buffer.writeInt(this.mBitmapId);
      buffer.writeInt(this.mScaleType);
      buffer.writeInt(this.mAlphaBits);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}ImageLayout(${this.getComponentId()}, bitmap=${this.mBitmapId}, scale=${this.mScaleType})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      const bitmapId = buffer.readInt();
      const scaleType = buffer.readInt();
      const alpha = buffer.readInt();
      operations.push(new _ImageLayout(componentId, animationId, bitmapId, scaleType, alpha));
    }
  };
  _ImageLayout.OP_CODE = 234;
  var ImageLayout = _ImageLayout;

  // third_party/remote-compose-player/src/core/operations/layout/managers/StateLayout.ts
  var _StateLayout = class _StateLayout extends LayoutManager {
    constructor(componentId, animationId, indexId) {
      super(componentId, animationId);
      this.currentLayoutIndex = 0;
      this.mIndexId = indexId;
    }
    inflate() {
      super.inflate();
      this.hideLayoutsOtherThan(this.currentLayoutIndex);
    }
    computeWrapSize(context, minWidth, maxWidth, minHeight, maxHeight, horizontalWrap, verticalWrap, measure, size) {
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        layout.computeWrapSize(
          context,
          minWidth,
          maxWidth,
          minHeight,
          maxHeight,
          horizontalWrap,
          verticalWrap,
          measure,
          size
        );
      }
    }
    computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        layout.computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure);
      }
    }
    internalLayoutMeasure(context, measure) {
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        layout.internalLayoutMeasure(context, measure);
      }
    }
    measure(context, minWidth, maxWidth, minHeight, maxHeight, measure) {
      if (this.mIndexId !== 0) {
        const newValue = context.getContext().mRemoteComposeState.getInteger(this.mIndexId);
        if (newValue !== this.currentLayoutIndex) {
          this.currentLayoutIndex = newValue;
          this.hideLayoutsOtherThan(this.currentLayoutIndex);
        }
      }
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        layout.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        const layoutM = measure.get(layout);
        const selfM = measure.get(this);
        selfM.copyFrom(layoutM);
      } else {
        super.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
      }
    }
    layout(context, measure) {
      const self = measure.get(this);
      this.mVisibility = self.getVisibility();
      if (self.isGone()) return;
      this.mX = self.getX();
      this.mY = self.getY();
      this.mWidth = self.getW();
      this.mHeight = self.getH();
      this.layoutModifiers(self.getW(), self.getH());
      this.mNeedsMeasure = false;
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        const layoutMeasure = measure.get(layout);
        layoutMeasure.copyFrom(self);
        layout.layout(context, measure);
      }
    }
    paint(paintContext) {
      if (this.mIndexId !== 0) {
        const newValue = paintContext.getContext().mRemoteComposeState.getInteger(this.mIndexId);
        if (newValue !== this.currentLayoutIndex) {
          this.currentLayoutIndex = newValue;
          this.hideLayoutsOtherThan(this.currentLayoutIndex);
          this.invalidateMeasure();
        }
      }
      let index = 0;
      for (const pane of this.mChildrenComponents) {
        if (pane instanceof LayoutComponent) {
          if (index === this.currentLayoutIndex) {
            if (!pane.isVisible()) {
              pane.mVisibility = Visibility.VISIBLE;
            }
          } else {
            pane.mVisibility = Visibility.GONE;
          }
          index++;
        }
      }
      const layout = this.getLayout(this.currentLayoutIndex);
      if (layout) {
        paintContext.save();
        paintContext.translate(layout.getX(), layout.getY());
        const context = paintContext.getContext();
        for (const op of layout.getList()) {
          context.incrementOpCount();
          if (op.isDirty() && typeof op.updateVariables === "function") {
            op.markNotDirty();
            op.updateVariables(context);
          }
          if (op instanceof Component) {
            if (!Visibility.isGone(op.mVisibility)) {
              op.paint(paintContext);
            }
          } else {
            op.apply(context);
          }
        }
        paintContext.restore();
      }
    }
    /** Returns the idx-th LayoutComponent child */
    getLayout(idx) {
      let index = 0;
      for (const pane of this.mChildrenComponents) {
        if (pane instanceof LayoutComponent) {
          if (index === idx) {
            return pane;
          }
          index++;
        }
      }
      if (this.mChildrenComponents.length > 0 && this.mChildrenComponents[0] instanceof LayoutComponent) {
        return this.mChildrenComponents[0];
      }
      return null;
    }
    /** Hides all layout children except the one at idx */
    hideLayoutsOtherThan(idx) {
      let index = 0;
      for (const pane of this.mChildrenComponents) {
        if (pane instanceof LayoutComponent) {
          pane.mVisibility = index === idx ? Visibility.VISIBLE : Visibility.GONE;
          index++;
        }
      }
    }
    write(buffer) {
      buffer.start(_StateLayout.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(0);
      buffer.writeInt(0);
      buffer.writeInt(0);
      buffer.writeInt(this.mIndexId);
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}StateLayout(${this.getComponentId()}, index=${this.mIndexId})`;
    }
    static read(buffer, operations) {
      const componentId = buffer.readInt();
      const animationId = buffer.readInt();
      buffer.readInt();
      buffer.readInt();
      const indexId = buffer.readInt();
      operations.push(new _StateLayout(componentId, animationId, indexId));
    }
  };
  _StateLayout.OP_CODE = 217;
  var StateLayout = _StateLayout;

  // third_party/remote-compose-player/src/core/operations/layout/modifiers/ActionOperations.ts
  var _HostActionOperation = class _HostActionOperation extends Operation {
    constructor(actionId) {
      super();
      this.mActionId = actionId;
    }
    write(_buffer) {
    }
    apply(context) {
      context.runAction(this.mActionId, "");
    }
    deepToString(indent) {
      return `${indent}HostActionOperation(${this.mActionId})`;
    }
    static read(buffer, operations) {
      operations.push(new _HostActionOperation(buffer.readInt()));
    }
  };
  _HostActionOperation.OP_CODE = 209;
  var HostActionOperation = _HostActionOperation;
  var _HostNamedActionOperation = class _HostNamedActionOperation extends Operation {
    constructor(textId, type, valueId) {
      super();
      this.mTextId = textId;
      this.mType = type;
      this.mValueId = valueId;
    }
    write(_buffer) {
    }
    apply(context) {
      let value;
      switch (this.mType) {
        case _HostNamedActionOperation.FLOAT_TYPE:
          value = context.getFloat(this.mValueId);
          break;
        case _HostNamedActionOperation.INT_TYPE:
          value = context.mRemoteComposeState.getInteger(this.mValueId);
          break;
        case _HostNamedActionOperation.STRING_TYPE:
          value = context.mRemoteComposeState.getFromId(this.mValueId);
          break;
        case _HostNamedActionOperation.NONE_TYPE:
        default:
          value = "";
          break;
      }
      context.runNamedAction(this.mTextId, value);
    }
    deepToString(indent) {
      return `${indent}HostNamedActionOperation(text=${this.mTextId}, type=${this.mType}, value=${this.mValueId})`;
    }
    static read(buffer, operations) {
      const textId = buffer.readInt();
      const type = buffer.readInt();
      const valueId = buffer.readInt();
      operations.push(new _HostNamedActionOperation(textId, type, valueId));
    }
  };
  _HostNamedActionOperation.OP_CODE = 210;
  _HostNamedActionOperation.FLOAT_TYPE = 0;
  _HostNamedActionOperation.INT_TYPE = 1;
  _HostNamedActionOperation.STRING_TYPE = 2;
  _HostNamedActionOperation.NONE_TYPE = -1;
  var HostNamedActionOperation = _HostNamedActionOperation;
  var _ValueIntegerChangeAction = class _ValueIntegerChangeAction extends Operation {
    constructor(valueId, value) {
      super();
      this.mTargetValueId = valueId;
      this.mValue = value;
    }
    write(_buffer) {
    }
    apply(context) {
      context.overrideInteger(this.mTargetValueId, this.mValue);
    }
    deepToString(indent) {
      return `${indent}ValueIntegerChangeAction(${this.mTargetValueId}, ${this.mValue})`;
    }
    static read(buffer, operations) {
      const valueId = buffer.readInt();
      const value = buffer.readInt();
      operations.push(new _ValueIntegerChangeAction(valueId, value));
    }
  };
  _ValueIntegerChangeAction.OP_CODE = 212;
  var ValueIntegerChangeAction = _ValueIntegerChangeAction;
  var _ValueStringChangeAction = class _ValueStringChangeAction extends Operation {
    constructor(valueId, stringId) {
      super();
      this.mTargetValueId = valueId;
      this.mValueId = stringId;
    }
    write(_buffer) {
    }
    apply(context) {
      context.overrideText(this.mTargetValueId, this.mValueId);
    }
    deepToString(indent) {
      return `${indent}ValueStringChangeAction(${this.mTargetValueId}, ${this.mValueId})`;
    }
    static read(buffer, operations) {
      const valueId = buffer.readInt();
      const stringId = buffer.readInt();
      operations.push(new _ValueStringChangeAction(valueId, stringId));
    }
  };
  _ValueStringChangeAction.OP_CODE = 213;
  var ValueStringChangeAction = _ValueStringChangeAction;
  var _ValueIntegerExpressionChangeAction = class _ValueIntegerExpressionChangeAction extends Operation {
    constructor(valueId, expressionId) {
      super();
      this.mTargetValueId = valueId;
      this.mValueExpressionId = expressionId;
    }
    write(_buffer) {
    }
    apply(_context) {
    }
    deepToString(indent) {
      return `${indent}ValueIntegerExpressionChangeAction(${this.mTargetValueId}, ${this.mValueExpressionId})`;
    }
    static read(buffer, operations) {
      const valueId = buffer.readLong();
      const expressionId = buffer.readLong();
      operations.push(new _ValueIntegerExpressionChangeAction(valueId, expressionId));
    }
  };
  _ValueIntegerExpressionChangeAction.OP_CODE = 218;
  var ValueIntegerExpressionChangeAction = _ValueIntegerExpressionChangeAction;
  var _ValueFloatChangeAction = class _ValueFloatChangeAction extends Operation {
    constructor(valueId, value) {
      super();
      this.mTargetValueId = valueId;
      this.mValue = value;
    }
    write(_buffer) {
    }
    apply(context) {
      context.overrideFloat(this.mTargetValueId, this.mValue);
    }
    deepToString(indent) {
      return `${indent}ValueFloatChangeAction(${this.mTargetValueId}, ${this.mValue})`;
    }
    static read(buffer, operations) {
      const valueId = buffer.readInt();
      const value = buffer.readFloat();
      operations.push(new _ValueFloatChangeAction(valueId, value));
    }
  };
  _ValueFloatChangeAction.OP_CODE = 222;
  var ValueFloatChangeAction = _ValueFloatChangeAction;

  // third_party/remote-compose-player/src/core/operations/utilities/ToneSynthesizer.ts
  var _ToneSynthesizer = class _ToneSynthesizer {
    /**
     * Synthesize raw 16-bit little-endian mono PCM bytes.
     * waveform: 1=square, 2=sawtooth, 3=triangle, else=sine.
     */
    static synthesizePcm(frequency, durationSec, waveform, sampleRate) {
      const sampleCount = Math.max(1, Math.floor(sampleRate * durationSec));
      const pcm = new Uint8Array(sampleCount * 2);
      const waveKind = Math.floor(waveform);
      for (let i = 0; i < sampleCount; i++) {
        const t = i / sampleRate;
        const phase = 2 * Math.PI * frequency * t;
        let sample;
        switch (waveKind) {
          case 1:
            sample = Math.sin(phase) >= 0 ? 1 : -1;
            break;
          case 2:
            sample = 2 * (frequency * t - Math.floor(frequency * t + 0.5));
            break;
          case 3:
            sample = 2 * Math.abs(2 * (frequency * t - Math.floor(frequency * t + 0.5))) - 1;
            break;
          default:
            sample = Math.sin(phase);
            break;
        }
        let envelope;
        if (i < 100) {
          envelope = i / 100;
        } else if (i > sampleCount - 100) {
          envelope = (sampleCount - i) / 100;
        } else {
          envelope = 1;
        }
        const s = Math.round(sample * envelope * 32767) << 16 >> 16;
        pcm[2 * i] = s & 255;
        pcm[2 * i + 1] = s >> 8 & 255;
      }
      return pcm;
    }
    /**
     * Wrap raw PCM bytes in a standard 44-byte RIFF/WAVE/fmt/data header
     * (little-endian) followed by the PCM data.
     */
    static buildWav(pcm, sampleRate = _ToneSynthesizer.SAMPLE_RATE, bitDepth = 16, channels = 1) {
      const byteRate = sampleRate * channels * bitDepth / 8;
      const blockAlign = channels * bitDepth / 8;
      const dataSize = pcm.length;
      const out = new Uint8Array(44 + dataSize);
      const view = new DataView(out.buffer);
      const le = true;
      const writeStr = (offset, str) => {
        for (let i = 0; i < str.length; i++) {
          out[offset + i] = str.charCodeAt(i);
        }
      };
      writeStr(0, "RIFF");
      view.setInt32(4, 36 + dataSize, le);
      writeStr(8, "WAVE");
      writeStr(12, "fmt ");
      view.setInt32(16, 16, le);
      view.setInt16(20, 1, le);
      view.setInt16(22, channels, le);
      view.setInt32(24, sampleRate, le);
      view.setInt32(28, byteRate, le);
      view.setInt16(32, blockAlign, le);
      view.setInt16(34, bitDepth, le);
      writeStr(36, "data");
      view.setInt32(40, dataSize, le);
      out.set(pcm, 44);
      return out;
    }
    /** Synthesize a complete WAV file for the given tone. */
    static synthesizeWav(frequency, durationSec, waveform) {
      return _ToneSynthesizer.buildWav(
        _ToneSynthesizer.synthesizePcm(
          frequency,
          durationSec,
          waveform,
          _ToneSynthesizer.SAMPLE_RATE
        )
      );
    }
  };
  _ToneSynthesizer.SAMPLE_RATE = 22050;
  var ToneSynthesizer = _ToneSynthesizer;

  // third_party/remote-compose-player/src/core/operations/SoundOperations.ts
  var _SoundData = class _SoundData extends Operation {
    constructor(soundId, data) {
      super();
      this.mSoundId = soundId;
      this.mData = data;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadSound(this.mSoundId, this.mData);
    }
    deepToString(indent) {
      return `${indent}SoundData(${this.mSoundId}, ${this.mData.length} bytes)`;
    }
    static read(buffer, operations) {
      const soundId = buffer.readInt();
      const data = buffer.readBuffer();
      operations.push(new _SoundData(soundId, data));
    }
  };
  _SoundData.OP_CODE = 169;
  var SoundData = _SoundData;
  var _SoundExpression = class _SoundExpression extends Operation {
    constructor(id, leftVolume, rightVolume, rate, params) {
      super();
      this.mId = id;
      this.mLeftVolume = leftVolume;
      this.mRightVolume = rightVolume;
      this.mRate = rate;
      this.mParams = params;
    }
    write(_buffer) {
    }
    apply(context) {
      if (this.mParams.length === 0) return;
      const typeId = idFromNan(this.mParams[0]);
      if (typeId === _SoundExpression.TYPE_TONE && this.mParams.length >= 4) {
        const wav = ToneSynthesizer.synthesizeWav(
          this.mParams[1],
          this.mParams[2],
          this.mParams[3]
        );
        context.loadSound(this.mId, wav);
      }
    }
    deepToString(indent) {
      return `${indent}SoundExpression(${this.mId})`;
    }
    static read(buffer, operations) {
      const id = buffer.readInt();
      const leftVolume = buffer.readFloat();
      const rightVolume = buffer.readFloat();
      const rate = buffer.readFloat();
      const len = buffer.readInt();
      if (len > _SoundExpression.MAX_PARAMS) {
        throw new Error(`SoundExpression: too many params ${len} > ${_SoundExpression.MAX_PARAMS}`);
      }
      const params = new Float32Array(len);
      for (let i = 0; i < len; i++) {
        params[i] = buffer.readFloat();
      }
      operations.push(new _SoundExpression(id, leftVolume, rightVolume, rate, params));
    }
  };
  _SoundExpression.OP_CODE = 206;
  _SoundExpression.MAX_PARAMS = 64;
  _SoundExpression.TYPE_TONE = 10;
  var SoundExpression = _SoundExpression;
  var _PlaySound = class _PlaySound extends Operation {
    constructor(soundExpressionId) {
      super();
      this.mSoundExpressionId = soundExpressionId;
    }
    write(_buffer) {
    }
    apply(context) {
      context.playSound(this.mSoundExpressionId);
    }
    deepToString(indent) {
      return `${indent}PlaySound(${this.mSoundExpressionId})`;
    }
    static read(buffer, operations) {
      operations.push(new _PlaySound(buffer.readInt()));
    }
  };
  _PlaySound.OP_CODE = 141;
  var PlaySound = _PlaySound;

  // third_party/remote-compose-player/src/core/operations/layout/managers/Custom.ts
  var _Custom = class _Custom extends LayoutManager {
    constructor(componentId, animationId, configId, properties) {
      super(componentId, animationId);
      this.mConfigId = configId;
      this.mProperties = properties;
    }
    write(buffer) {
      buffer.start(_Custom.OP_CODE);
      buffer.writeInt(this.getComponentId());
      buffer.writeInt(this.getAnimationId());
      buffer.writeInt(this.mConfigId);
      buffer.writeInt(this.mProperties.length);
      for (const p of this.mProperties) {
        buffer.writeShort(p.type);
        buffer.writeShort(p.dataType);
        if ((p.dataType & 1) === 0) {
          buffer.writeInt(p.value);
        } else {
          buffer.writeFloat(p.value);
        }
      }
    }
    apply(context) {
      super.apply(context);
    }
    deepToString(indent) {
      return `${indent}Custom(${this.getComponentId()}, config=${this.mConfigId}, ${this.mProperties.length} props)`;
    }
    static read(buffer, operations) {
      const componentId = buffer.declareId();
      const animationId = buffer.declareId();
      const configId = buffer.readInt();
      const propCount = buffer.readInt();
      const properties = [];
      for (let i = 0; i < propCount; i++) {
        const type = buffer.readShort();
        const dataType = buffer.readShort();
        const value = (dataType & 1) === 0 ? buffer.readInt() : buffer.readFloat();
        properties.push({ type, dataType, value });
      }
      operations.push(new _Custom(componentId, animationId, configId, properties));
    }
  };
  _Custom.OP_CODE = 93;
  var Custom = _Custom;

  // third_party/remote-compose-player/src/core/operations/FontData.ts
  var _FontData = class _FontData extends Operation {
    constructor(mFontId, mType, mFontData) {
      super();
      this.mFontId = mFontId;
      this.mType = mType;
      this.mFontData = mFontData;
    }
    write(_buffer) {
    }
    apply(context) {
      context.loadFont(this.mFontId, this.mFontData);
    }
    deepToString(indent) {
      return `${indent}FontData(${this.mFontId}, ${this.mFontData.length} bytes)`;
    }
    static read(buffer, operations) {
      const fontId = buffer.readId();
      const type = buffer.readInt();
      const fontData = buffer.readBuffer();
      operations.push(new _FontData(fontId, type, fontData));
    }
  };
  _FontData.OP_CODE = 189;
  var FontData = _FontData;

  // third_party/remote-compose-player/src/core/Operations.ts
  var _Operations = class _Operations {
    static init() {
      if (_Operations.initialized) return;
      _Operations.initialized = true;
      const m = _Operations.sMap;
      m.set(Header.OP_CODE, Header.read);
      m.set(DrawRect.OP_CODE, DrawRect.read);
      m.set(DrawCircle.OP_CODE, DrawCircle.read);
      m.set(DrawLine.OP_CODE, DrawLine.read);
      m.set(DrawOval.OP_CODE, DrawOval.read);
      m.set(DrawRoundRect.OP_CODE, DrawRoundRect.read);
      m.set(DrawArc.OP_CODE, DrawArc.read);
      m.set(DrawSector.OP_CODE, DrawSector.read);
      m.set(DrawPath.OP_CODE, DrawPath.read);
      m.set(DrawTweenPath.OP_CODE, DrawTweenPath.read);
      m.set(DrawContent.OP_CODE, DrawContent.read);
      m.set(DrawBitmap.OP_CODE, DrawBitmap.read);
      m.set(DrawBitmapInt.OP_CODE, DrawBitmapInt.read);
      m.set(DrawBitmapScaled.OP_CODE, DrawBitmapScaled.read);
      m.set(DrawText.OP_CODE, DrawText.read);
      m.set(DrawTextAnchored.OP_CODE, DrawTextAnchored.read);
      m.set(DrawTextOnPath.OP_CODE, DrawTextOnPath.read);
      m.set(DrawToBitmap.OP_CODE, DrawToBitmap.read);
      m.set(MatrixSave.OP_CODE, MatrixSave.read);
      m.set(MatrixRestore.OP_CODE, MatrixRestore.read);
      m.set(MatrixTranslate.OP_CODE, MatrixTranslate.read);
      m.set(MatrixScale.OP_CODE, MatrixScale.read);
      m.set(MatrixRotate.OP_CODE, MatrixRotate.read);
      m.set(MatrixSkew.OP_CODE, MatrixSkew.read);
      m.set(MatrixFromPath.OP_CODE, MatrixFromPath.read);
      m.set(ClipRect.OP_CODE, ClipRect.read);
      m.set(ClipPath.OP_CODE, ClipPath.read);
      m.set(TextData.OP_CODE, TextData.read);
      m.set(BitmapData.OP_CODE, BitmapData.read);
      m.set(FontData.OP_CODE, FontData.read);
      m.set(PaintData.OP_CODE, PaintData.read);
      m.set(PathData.OP_CODE, PathData.read);
      m.set(FloatConstant.OP_CODE, FloatConstant.read);
      m.set(ColorConstant.OP_CODE, ColorConstant.read);
      m.set(Theme.OP_CODE, Theme.read);
      m.set(ClickArea.OP_CODE, ClickArea.read);
      m.set(NamedVariable.OP_CODE, NamedVariable.read);
      m.set(RootContentDescription.OP_CODE, RootContentDescription.read);
      m.set(RootContentBehavior.OP_CODE, RootContentBehavior.read);
      m.set(ShaderData.OP_CODE, ShaderData.read);
      m.set(FloatExpression.OP_CODE, FloatExpression.read);
      m.set(ColorExpression.OP_CODE, ColorExpression.read);
      m.set(IntegerExpression.OP_CODE, IntegerExpression.read);
      m.set(IntegerConstant.OP_CODE, IntegerConstant.read);
      m.set(BooleanConstant.OP_CODE, BooleanConstant.read);
      m.set(LongConstant.OP_CODE, LongConstant.read);
      m.set(TextFromFloat.OP_CODE, TextFromFloat.read);
      m.set(TextMerge.OP_CODE, TextMerge.read);
      m.set(ComponentValue.OP_CODE, ComponentValue.read);
      m.set(DataMapIds.OP_CODE, DataMapIds.read);
      m.set(DataMapLookup.OP_CODE, DataMapLookup.read);
      m.set(TextMeasure.OP_CODE, TextMeasure.read);
      m.set(TextLength.OP_CODE, TextLength.read);
      m.set(TextSubtext.OP_CODE, TextSubtext.read);
      m.set(DataListIds.OP_CODE, DataListIds.read);
      m.set(DataListFloat.OP_CODE, DataListFloat.read);
      m.set(RootLayoutComponent.OP_CODE, RootLayoutComponent.read);
      m.set(LayoutComponentContent.OP_CODE, LayoutComponentContent.read);
      m.set(CanvasContent.OP_CODE, CanvasContent.read);
      m.set(BoxLayout.OP_CODE, BoxLayout.read);
      m.set(RowLayout.OP_CODE, RowLayout.read);
      m.set(ColumnLayout.OP_CODE, ColumnLayout.read);
      m.set(CanvasLayout.OP_CODE, CanvasLayout.read);
      m.set(ContainerEnd.OP_CODE, ContainerEnd.read);
      m.set(WidthModifier.OP_CODE, WidthModifier.read);
      m.set(HeightModifier.OP_CODE, HeightModifier.read);
      m.set(WidthInModifier.OP_CODE, WidthInModifier.read);
      m.set(HeightInModifier.OP_CODE, HeightInModifier.read);
      m.set(CollapsiblePriorityModifier.OP_CODE, CollapsiblePriorityModifier.read);
      m.set(BackgroundModifier.OP_CODE, BackgroundModifier.read);
      m.set(BorderModifier.OP_CODE, BorderModifier.read);
      m.set(PaddingModifier.OP_CODE, PaddingModifier.read);
      m.set(RoundedClipRectModifier.OP_CODE, RoundedClipRectModifier.read);
      m.set(ClipRectModifier.OP_CODE, ClipRectModifier.read);
      m.set(ClickModifier.OP_CODE, ClickModifier.read);
      m.set(MultiClickModifier.OP_CODE, MultiClickModifier.read);
      m.set(DimensionConstraintsModifier.OP_CODE, DimensionConstraintsModifier.read);
      m.set(TouchDownModifier.OP_CODE, TouchDownModifier.read);
      m.set(TouchUpModifier.OP_CODE, TouchUpModifier.read);
      m.set(TouchCancelModifier.OP_CODE, TouchCancelModifier.read);
      m.set(VisibilityModifier.OP_CODE, VisibilityModifier.read);
      m.set(OffsetModifier.OP_CODE, OffsetModifier.read);
      m.set(ZIndexModifier.OP_CODE, ZIndexModifier.read);
      m.set(GraphicsLayerModifier.OP_CODE, GraphicsLayerModifier.read);
      m.set(ScrollModifier.OP_CODE, ScrollModifier.read);
      m.set(MarqueeModifier.OP_CODE, MarqueeModifier.read);
      m.set(RippleModifier.OP_CODE, RippleModifier.read);
      m.set(DrawContentModifier.OP_CODE, DrawContentModifier.read);
      m.set(AlignByModifier.OP_CODE, AlignByModifier.read);
      m.set(AccessibilitySemantics.OP_CODE, AccessibilitySemantics.read);
      m.set(TouchExpression.OP_CODE, TouchExpression.read);
      m.set(ConditionalOperations.OP_CODE, ConditionalOperations.read);
      m.set(PathCreate.OP_CODE, PathCreate.read);
      m.set(TextTransform.OP_CODE, TextTransform.read);
      m.set(MatrixExpression.OP_CODE, MatrixExpression.read);
      m.set(PathExpression.OP_CODE, PathExpression.read);
      m.set(TextLookup.OP_CODE, TextLookup.read);
      m.set(TextLookupInt.OP_CODE, TextLookupInt.read);
      m.set(ColorTheme.OP_CODE, ColorTheme.read);
      m.set(ColorAttribute.OP_CODE, ColorAttribute.read);
      m.set(TextLayout.OP_CODE, TextLayout.read);
      m.set(PathAppend.OP_CODE, PathAppend.read);
      m.set(ImpulseOperation.OP_CODE, ImpulseOperation.read);
      m.set(TextAttribute.OP_CODE, TextAttribute.read);
      m.set(CanvasOperations.OP_CODE, CanvasOperations.read);
      m.set(DebugMessage.OP_CODE, DebugMessage.read);
      m.set(MatrixVectorMath.OP_CODE, MatrixVectorMath.read);
      m.set(MatrixConstant.OP_CODE, MatrixConstant.read);
      m.set(ParticlesCreateOp.OP_CODE, ParticlesCreateOp.read);
      m.set(HostActionMetadataOperation.OP_CODE, HostActionMetadataOperation.read);
      m.set(RunActionOperation.OP_CODE, RunActionOperation.read);
      m.set(ImpulseProcess.OP_CODE, ImpulseProcess.read);
      m.set(FlowLayout.OP_CODE, FlowLayout.read);
      m.set(ValueFloatExpressionChangeAction.OP_CODE, ValueFloatExpressionChangeAction.read);
      m.set(ParticlesCompareOp.OP_CODE, ParticlesCompareOp.read);
      m.set(ParticlesLoopOp.OP_CODE, ParticlesLoopOp.read);
      m.set(LoopOperation.OP_CODE, LoopOperation.read);
      m.set(CoreText.OP_CODE, CoreText.read);
      m.set(FitBoxLayout.OP_CODE, FitBoxLayout.read);
      m.set(CollapsibleRowLayout.OP_CODE, CollapsibleRowLayout.read);
      m.set(CollapsibleColumnLayout.OP_CODE, CollapsibleColumnLayout.read);
      m.set(IdLookup.OP_CODE, IdLookup.read);
      m.set(DataDynamicListFloat.OP_CODE, DataDynamicListFloat.read);
      m.set(LayoutComputeOperation.OP_CODE, LayoutComputeOperation.read);
      m.set(UpdateDynamicFloatList.OP_CODE, UpdateDynamicFloatList.read);
      m.set(ImageLayout.OP_CODE, ImageLayout.read);
      m.set(StateLayout.OP_CODE, StateLayout.read);
      m.set(HostActionOperation.OP_CODE, HostActionOperation.read);
      m.set(HostNamedActionOperation.OP_CODE, HostNamedActionOperation.read);
      m.set(ValueIntegerChangeAction.OP_CODE, ValueIntegerChangeAction.read);
      m.set(ValueStringChangeAction.OP_CODE, ValueStringChangeAction.read);
      m.set(ValueIntegerExpressionChangeAction.OP_CODE, ValueIntegerExpressionChangeAction.read);
      m.set(ValueFloatChangeAction.OP_CODE, ValueFloatChangeAction.read);
      m.set(PathTween.OP_CODE, PathTween.read);
      m.set(HapticFeedback.OP_CODE, HapticFeedback.read);
      m.set(WakeIn.OP_CODE, WakeIn.read);
      m.set(TimeAttribute.OP_CODE, TimeAttribute.read);
      m.set(Skip.OP_CODE, Skip.read);
      m.set(TextStyle.OP_CODE, TextStyle.read);
      m.set(SoundData.OP_CODE, SoundData.read);
      m.set(SoundExpression.OP_CODE, SoundExpression.read);
      m.set(PlaySound.OP_CODE, PlaySound.read);
      m.set(ReferencedOperations.OP_CODE, ReferencedOperations.read);
      m.set(IncludeReferencedOperations.OP_CODE, IncludeReferencedOperations.read);
      m.set(PatternInflation.OP_CODE, PatternInflation.read);
      m.set(PatternBlock.OP_CODE, PatternBlock.read);
      m.set(PatternForEach.OP_CODE, PatternForEach.read);
      m.set(PatternArgument.OP_CODE, PatternArgument.read);
      m.set(PatternDefine.OP_CODE, PatternDefine.read);
      m.set(Custom.OP_CODE, Custom.read);
    }
    static getOperations() {
      _Operations.init();
      return _Operations.sMap;
    }
    static getReader(opCode) {
      _Operations.init();
      return _Operations.sMap.get(opCode);
    }
    static valid(opId) {
      _Operations.init();
      return _Operations.sMap.has(opId);
    }
  };
  _Operations.sMap = /* @__PURE__ */ new Map();
  _Operations.initialized = false;
  var Operations = _Operations;

  // third_party/remote-compose-player/src/core/operations/loom/LoomWireBuffer.ts
  var LoomWireBuffer = class extends WireBuffer {
    constructor(wrapped, context) {
      super(1);
      this.mWrapped = wrapped;
      this.mContext = context;
    }
    getRemapContext() {
      return this.mContext;
    }
    // ---- ID-typed reads (the remapping hooks) ----
    declareId() {
      return this.mContext.declareId(this.mWrapped.readInt());
    }
    readId() {
      return this.mContext.resolveId(this.mWrapped.readInt());
    }
    readNanId() {
      return this.mContext.resolveNanId(this.mWrapped.readFloat());
    }
    readLongNanId() {
      return this.mContext.resolveLongNanId(this.mWrapped.readLong());
    }
    // Bits-domain twin of readNanId: same remap, but the payload is rewritten in
    // the raw float32 int bits, so the value never passes through a JS float.
    // Mirrors RemapContext.resolveNanId, using the bits-typed helpers.
    readNanIdBits() {
      const bits = this.mWrapped.readInt();
      if (!isNaNBits(bits)) return bits;
      const id = idFromBits(bits);
      const mapped = this.mContext.resolveId(id);
      return mapped === id ? bits : mapped | 0 | -8388608;
    }
    // ---- Delegation ----
    getBuffer() {
      return this.mWrapped.getBuffer();
    }
    getMaxSize() {
      return this.mWrapped.getMaxSize();
    }
    getIndex() {
      return this.mWrapped.getIndex();
    }
    setIndex(index) {
      this.mWrapped.setIndex(index);
    }
    getSize() {
      return this.mWrapped.getSize();
    }
    size() {
      return this.mWrapped.size();
    }
    available() {
      return this.mWrapped.available();
    }
    start(type) {
      this.mWrapped.start(type);
    }
    startWithSize(type) {
      this.mWrapped.startWithSize(type);
    }
    endWithSize() {
      this.mWrapped.endWithSize();
    }
    reset(expectedSize) {
      this.mWrapped.reset(expectedSize);
    }
    readOperationType() {
      return this.mWrapped.readOperationType();
    }
    readBoolean() {
      return this.mWrapped.readBoolean();
    }
    readByte() {
      return this.mWrapped.readByte();
    }
    readShort() {
      return this.mWrapped.readShort();
    }
    peekInt() {
      return this.mWrapped.peekInt();
    }
    readInt() {
      return this.mWrapped.readInt();
    }
    readLong() {
      return this.mWrapped.readLong();
    }
    readFloat() {
      return this.mWrapped.readFloat();
    }
    readDouble() {
      return this.mWrapped.readDouble();
    }
    readBuffer() {
      return this.mWrapped.readBuffer();
    }
    readBufferMax(maxSize) {
      return this.mWrapped.readBufferMax(maxSize);
    }
    readUTF8() {
      return this.mWrapped.readUTF8();
    }
    readUTF8Max(maxSize) {
      return this.mWrapped.readUTF8Max(maxSize);
    }
    writeBoolean(value) {
      this.mWrapped.writeBoolean(value);
    }
    writeByte(value) {
      this.mWrapped.writeByte(value);
    }
    writeShort(value) {
      this.mWrapped.writeShort(value);
    }
    writeInt(value) {
      this.mWrapped.writeInt(value);
    }
    writeLong(value) {
      this.mWrapped.writeLong(value);
    }
    writeFloat(value) {
      this.mWrapped.writeFloat(value);
    }
    writeDouble(value) {
      this.mWrapped.writeDouble(value);
    }
    writeBuffer(b) {
      this.mWrapped.writeBuffer(b);
    }
    writeUTF8(content) {
      this.mWrapped.writeUTF8(content);
    }
    cloneBytes() {
      return this.mWrapped.cloneBytes();
    }
    setVersion(documentApiLevel, profiles) {
      this.mWrapped.setVersion(documentApiLevel, profiles);
    }
    setValidOperationsFromSet(s) {
      this.mWrapped.setValidOperationsFromSet(s);
    }
    moveBlock(beyond, insertLocation) {
      this.mWrapped.moveBlock(beyond, insertLocation);
    }
  };

  // third_party/remote-compose-player/src/core/RemoteComposeBuffer.ts
  var RemoteComposeBuffer = class _RemoteComposeBuffer {
    constructor(buffer) {
      this.mBuffer = buffer;
    }
    static fromArrayBuffer(data) {
      return new _RemoteComposeBuffer(WireBuffer.fromArrayBuffer(data));
    }
    getBuffer() {
      return this.mBuffer;
    }
    /**
     * Inflate operations from the buffer into a flat list.
     *
     * @param operations output list
     * @param remapContext if provided, ID reads are routed through this context
     *        (used during macro/pattern body re-inflation for uniqueification).
     */
    inflateFromBuffer(operations, remapContext) {
      this.mBuffer.setIndex(0);
      const buf = remapContext ? new LoomWireBuffer(this.mBuffer, remapContext) : this.mBuffer;
      const map = Operations.getOperations();
      while (this.mBuffer.available()) {
        const startOffset = this.mBuffer.getIndex();
        const opId = this.mBuffer.readByte();
        const reader = map.get(opId);
        if (!reader) {
          console.warn(`Unknown operation opcode: ${opId}, skipping rest of buffer`);
          return;
        }
        const before = operations.length;
        reader(buf, operations);
        const endOffset = this.mBuffer.getIndex();
        for (let i = before; i < operations.length; i++) {
          const span = operations[i];
          span._byteStart = startOffset;
          span._byteEnd = endOffset;
        }
      }
    }
  };

  // third_party/remote-compose-player/src/core/operations/loom/nestContainers.ts
  function isContainer2(op) {
    return typeof op.getList === "function" && !(op instanceof ContainerEnd);
  }
  function captureLoomBodies(flat, raw) {
    for (let i = 0; i < flat.length; i++) {
      const op = flat[i];
      const bodyBearing = op instanceof PatternDefine && op.isContainerForm() || op instanceof PatternForEach || op instanceof ReferencedOperations;
      if (!bodyBearing || !isContainer2(op)) continue;
      let depth = 1;
      let j = i + 1;
      for (; j < flat.length; j++) {
        const o = flat[j];
        if (o instanceof ContainerEnd) {
          if (--depth === 0) break;
        } else if (isContainer2(o)) {
          depth++;
        }
      }
      if (j >= flat.length) continue;
      const from = op._byteEnd;
      const to = flat[j]._byteStart;
      if (from >= 0 && to >= from) {
        op.setBody(raw.slice(from, to));
      }
    }
  }
  function nestContainers(operations) {
    const finalOps = [];
    let ops = finalOps;
    const containers = [];
    for (const op of operations) {
      if (isContainer2(op)) {
        const container = op;
        ops.push(op);
        containers.push(container);
        ops = container.getList();
      } else if (op instanceof ContainerEnd) {
        let container = null;
        if (containers.length > 0) {
          container = containers.pop();
        }
        if (containers.length > 0) {
          ops = containers[containers.length - 1].getList();
        } else {
          ops = finalOps;
        }
        if (container && typeof container.inflate === "function") {
          container.inflate();
        }
      } else {
        ops.push(op);
      }
    }
    return finalOps;
  }

  // third_party/remote-compose-player/src/core/operations/loom/ExpansionContext.ts
  var MAX_EXPANSION_DEPTH = 64;
  var ExpansionContext = class _ExpansionContext {
    constructor(loomManager, document2, remapContext, blocks, safeMode = false, depth = 0) {
      this.mLoomManager = loomManager;
      this.mDocument = document2;
      this.mRemapContext = remapContext;
      this.mBlocks = blocks;
      this.mSafeMode = safeMode;
      this.mDepth = depth;
    }
    getDepth() {
      return this.mDepth;
    }
    isSafeMode() {
      return this.mSafeMode;
    }
    getMacroManager() {
      return this.mLoomManager;
    }
    getDocument() {
      return this.mDocument;
    }
    getRemapContext() {
      return this.mRemapContext;
    }
    getBlock(paramIndex) {
      return this.mBlocks.get(paramIndex) ?? null;
    }
    /** Factory used by Operation.materialize to avoid an import cycle on ContainerEnd. */
    makeContainerEnd() {
      return new ContainerEnd();
    }
    fork() {
      return new _ExpansionContext(
        this.mLoomManager,
        this.mDocument,
        this.mRemapContext.fork(),
        this.mBlocks,
        this.mSafeMode,
        this.mDepth
      );
    }
    /** Top-level entry: expand a list into a fresh result list. */
    expandRecursiveTop(operations, loomManager) {
      const result = [];
      this.expandRecursive(operations, result, loomManager);
      return result;
    }
    expandRecursive(operations, result, loomManager) {
      if (this.mDepth > MAX_EXPANSION_DEPTH) {
        if (this.mSafeMode) {
          result.push(new DebugMessage());
          return;
        }
        throw new Error("Maximum macro expansion depth exceeded");
      }
      for (const op of operations) {
        try {
          op.materialize(this, result, loomManager);
        } catch (t) {
          if (this.mSafeMode) {
            result.push(new DebugMessage());
          } else {
            throw t;
          }
        }
      }
    }
    /** Record PatternBlock children of a call so PatternArgument can find them. */
    recordBlocks(call) {
      for (const callChild of call.getList()) {
        if (callChild instanceof PatternBlock) {
          this.mBlocks.set(callChild.getParamIndex(), callChild.getList());
        }
      }
    }
    /** Return the blocks associated with a macro call as a fresh map. */
    getBlocksForCall(call) {
      const blocks = /* @__PURE__ */ new Map();
      for (const callChild of call.getList()) {
        if (callChild instanceof PatternBlock) {
          blocks.set(callChild.getParamIndex(), callChild.getList());
        }
      }
      return blocks;
    }
    /** Inflate a macro body from its captured bytes, remapping ids via remapContext. */
    inflateBody(bodyBytes, remapContext) {
      const ab = new ArrayBuffer(bodyBytes.byteLength);
      new Uint8Array(ab).set(bodyBytes);
      const wb = WireBuffer.fromArrayBuffer(ab);
      const buffer = new RemoteComposeBuffer(wb);
      const flatBody = [];
      buffer.inflateFromBuffer(flatBody, remapContext);
      captureLoomBodies(flatBody, new Uint8Array(ab));
      return nestContainers(flatBody);
    }
  };

  // third_party/remote-compose-player/src/core/operations/loom/RemapContext.ts
  var RemapContext = class _RemapContext {
    constructor(a, document2, isInsideMacro) {
      if (a instanceof Map) {
        this.mIdMap = a;
        this.mDocument = document2 ?? null;
        this.mIsInsideMacro = isInsideMacro ?? false;
      } else {
        this.mIdMap = /* @__PURE__ */ new Map();
        this.mDocument = a ?? null;
        this.mIsInsideMacro = false;
      }
      this.mIsMapShared = false;
    }
    /** An identity remapper that passes every id through unchanged. */
    static identity() {
      return new IdentityRemapContext();
    }
    isInsideMacro() {
      return this.mIsInsideMacro;
    }
    /** Returns a new RemapContext with the specified insideMacro state (shares the map). */
    withInsideMacro(insideMacro) {
      if (this.mIsInsideMacro === insideMacro) {
        return this;
      }
      this.mIsMapShared = true;
      const next = new _RemapContext(this.mIdMap, this.mDocument, insideMacro);
      next.mIsMapShared = true;
      return next;
    }
    /** A forked context that inherits current mappings (copy-on-write). */
    fork() {
      this.mIsMapShared = true;
      const forked = new _RemapContext(this.mIdMap, this.mDocument, this.mIsInsideMacro);
      forked.mIsMapShared = true;
      return forked;
    }
    ensureMapWritable() {
      if (this.mIsMapShared) {
        this.mIdMap = new Map(this.mIdMap);
        this.mIsMapShared = false;
      }
    }
    addMapping(originalId, newId) {
      this.ensureMapWritable();
      this.mIdMap.set(originalId, newId);
    }
    /** Look up the translated value of an id; returns the original if unmapped. */
    resolveId(id) {
      const remapped = this.mIdMap.get(id);
      return remapped !== void 0 ? remapped : id;
    }
    /** Resolve a NaN-encoded float id. Non-NaN values pass through. */
    resolveNanId(v) {
      if (!Number.isNaN(v)) {
        return v;
      }
      const id = idFromNan(v);
      const mapped = this.resolveId(id);
      return mapped === id ? v : asNan(mapped);
    }
    /** Resolve a NaN-encoded long id (see Utils.longIdFromNan). */
    resolveLongNanId(v) {
      const decoded = idFromLong(v);
      const id = decoded | 0;
      if (id !== decoded) {
        return v;
      }
      const mapped = this.resolveId(id);
      return mapped === id ? v : mapped + 4294967296;
    }
    /** Declare an id read off the wire, uniqueifying when required. */
    declareId(originalId) {
      if (originalId === -1) {
        return -1;
      }
      const mapped = this.mIdMap.get(originalId);
      if (mapped !== void 0) {
        return mapped;
      }
      if (isMacroLocal(originalId)) {
        return this.allocateNewId(originalId);
      }
      if (this.mIsInsideMacro && !isSystemGlobal(originalId)) {
        return this.allocateNewId(originalId);
      }
      return originalId;
    }
    allocateNewId(originalId) {
      if (this.mDocument === null) {
        throw new Error("Cannot allocate ID without a document");
      }
      const newId = this.mDocument.getNextId();
      this.addMapping(originalId, newId);
      return newId;
    }
    getIdMap() {
      return this.mIdMap;
    }
  };
  var IdentityRemapContext = class extends RemapContext {
    constructor() {
      super(/* @__PURE__ */ new Map(), null, false);
    }
    declareId(id) {
      return id;
    }
    resolveId(id) {
      return id;
    }
    resolveNanId(v) {
      return v;
    }
    resolveLongNanId(v) {
      return v;
    }
    withInsideMacro(_insideMacro) {
      return this;
    }
  };

  // third_party/remote-compose-player/src/core/operations/loom/LoomManager.ts
  var LoomManager = class {
    constructor() {
      this.mMacros = /* @__PURE__ */ new Map();
      this.mMacroNames = /* @__PURE__ */ new Map();
      this.mSafeMode = false;
    }
    setSafeMode(safeMode) {
      this.mSafeMode = safeMode;
    }
    isSafeMode() {
      return this.mSafeMode;
    }
    /** Add a macro definition, keyed by id and (optionally) by name. */
    add(macro, name) {
      const existing = this.mMacros.get(macro.getId());
      if (!existing || existing.getBody().length === 0 && macro.getBody().length > 0) {
        this.mMacros.set(macro.getId(), macro);
      }
      if (name !== null) {
        const en = this.mMacroNames.get(name);
        if (!en || en.getBody().length === 0 && macro.getBody().length > 0) {
          this.mMacroNames.set(name, macro);
        }
      }
    }
    getNamedMacros() {
      return this.mMacroNames;
    }
    /** Top-level entry: expand all macros/references in a list of operations. */
    expandAll(operations, document2) {
      const expansionContext = new ExpansionContext(
        this,
        document2,
        new RemapContext(document2),
        /* @__PURE__ */ new Map(),
        this.mSafeMode,
        0
      );
      return expansionContext.expandRecursiveTop(operations, this);
    }
    /** Resolve a PatternDefine for a given call (by id, then by name via document text). */
    resolve(call, document2) {
      let definition = this.mMacros.get(call.getId()) ?? null;
      if (definition === null) {
        const name = document2.getText(call.getId());
        if (name !== null && name !== void 0) {
          definition = this.mMacroNames.get(name) ?? null;
        }
      }
      return definition;
    }
  };

  // third_party/remote-compose-player/src/core/CoreDocument.ts
  var Version = class {
    constructor(major, minor, patch) {
      this.mMajorVersion = major;
      this.mMinorVersion = minor;
      this.mPatchVersion = patch;
    }
    supportsVersion(major, minor, patch) {
      if (this.mMajorVersion !== major) return this.mMajorVersion > major;
      if (this.mMinorVersion !== minor) return this.mMinorVersion > minor;
      return this.mPatchVersion >= patch;
    }
  };
  var FONT_SCALE = 1;
  var _CoreDocument = class _CoreDocument {
    constructor(clock = SYSTEM_CLOCK) {
      this.mOperations = [];
      this.mRootLayoutComponent = null;
      /** Ops re-evaluated before every layout pass — see registerVariables. */
      this.mLayoutComputeOps = /* @__PURE__ */ new Set();
      this.mRemoteComposeState = new RemoteComposeState();
      this.mVersion = null;
      this.mWidth = 256;
      this.mHeight = 256;
      this.mCapabilities = 0;
      this.mProperties = null;
      this.mContentDescription = "";
      this.mContentScroll = 0;
      this.mContentAlignment = 0;
      this.mContentSizing = 0;
      this.mContentMode = 0;
      this.mBuffer = null;
      this.mHeader = null;
      this.mClickAreas = /* @__PURE__ */ new Set();
      this.mIdActionListeners = /* @__PURE__ */ new Set();
      this.mActionCallbacks = [];
      this.mThemeColors = null;
      this.mTouchListeners = /* @__PURE__ */ new Set();
      this.mAppliedTouchOperations = /* @__PURE__ */ new Set();
      this.mNeedsRepaintFlag = -1;
      this.mLastOpCount = 0;
      this.mIsUpdateDoc = false;
      // --- Loom / macro expansion state ---
      this.mLoomManager = new LoomManager();
      this.mCurrentId = 0;
      this.mProfileMask = 0;
      this.mTextData = /* @__PURE__ */ new Map();
      this.mReferencedOperations = /* @__PURE__ */ new Map();
      this.mArrays = /* @__PURE__ */ new Map();
      /**
       * Float expressions by id, so an action can evaluate one on demand.
       * Mirrors `CoreDocument.mFloatExpressions` / `evaluateFloatExpression`.
       */
      this.mFloatExpressions = /* @__PURE__ */ new Map();
      this.mClock = clock;
      this.mTimeVariables = new TimeVariables(clock);
    }
    /** Lazily collect all ColorTheme operations from the operation tree. */
    getThemedColors() {
      if (this.mThemeColors === null) {
        const colors = [];
        this.collectColorThemes(this.mOperations, colors);
        this.mThemeColors = colors;
      }
      return this.mThemeColors;
    }
    collectColorThemes(ops, out) {
      for (const op of ops) {
        if (op instanceof ColorTheme) {
          out.push(op);
        }
        if (typeof op.getList === "function") {
          const children = op.getList();
          if (Array.isArray(children)) {
            this.collectColorThemes(children, out);
          }
        }
      }
    }
    // --- Version & metadata ---
    static getDocumentApiLevel() {
      return _CoreDocument.DOCUMENT_API_LEVEL;
    }
    getContentDescription() {
      return this.mContentDescription;
    }
    setContentDescription(cd) {
      this.mContentDescription = cd;
    }
    getRequiredCapabilities() {
      return this.mCapabilities;
    }
    setRequiredCapabilities(cap) {
      this.mCapabilities = cap;
    }
    setVersion(major, minor, patch) {
      this.mVersion = new Version(major, minor, patch);
    }
    getWidth() {
      if (this.mRootLayoutComponent) {
        const w = this.mRootLayoutComponent.getWidth();
        if (w > 0) return w;
      }
      return this.mWidth;
    }
    /**
     * Set the document width **and** the `windowWidth()` system variable.
     *
     * The reference `CoreDocument.setWidth` updates both. Setting only the field left
     * ids 5/6 at whatever the header declared, so every expression built on
     * `windowWidth()` / `windowHeight()` was computed against the header size rather
     * than the viewport the document is actually being played at. A document that
     * declares no size reports 256x256, so a full-bleed document played at any other
     * size laid itself out for a 256-pixel world while being drawn much larger.
     */
    setWidth(w) {
      this.mWidth = w;
      this.mRemoteComposeState?.setWindowWidth(w);
    }
    getHeight() {
      if (this.mRootLayoutComponent) {
        const h = this.mRootLayoutComponent.getHeight();
        if (h > 0) return h;
      }
      return this.mHeight;
    }
    /** Set the document height and the `windowHeight()` system variable — see setWidth. */
    setHeight(h) {
      this.mHeight = h;
      this.mRemoteComposeState?.setWindowHeight(h);
    }
    setProperties(properties) {
      this.mProperties = properties;
    }
    getProperty(key) {
      return this.mProperties?.get(key) ?? null;
    }
    /** The document's density behavior (DOC_DENSITY_BEHAVIOR header property,
     *  key 27). Mirrors AndroidX CoreDocument.mDensityBehavior, which it reads via
     *  featureIntValue(27) → Header.getInt(27, DEFAULT_DENSITY_BEHAVIOR=LEGACY).
     *  Returns LEGACY (0 — no dp scaling) when the header omits the property. */
    getDensityBehavior() {
      if (this.mHeader) return this.mHeader.getInt(
        27,
        0
        /* LEGACY */
      );
      const v = this.getProperty(27);
      return typeof v === "number" ? v : 0;
    }
    useFeature(feature, defaultValue = 0) {
      if (!this.mHeader) return defaultValue !== 0;
      return this.mHeader.getInt(feature, defaultValue) !== 0;
    }
    // --- Buffer & state ---
    getBuffer() {
      return this.mBuffer;
    }
    setBuffer(buffer) {
      this.mBuffer = buffer;
    }
    getRemoteComposeState() {
      return this.mRemoteComposeState;
    }
    getClock() {
      return this.mClock;
    }
    getRootLayoutComponent() {
      return this.mRootLayoutComponent;
    }
    getComponent(id) {
      if (!this.mRootLayoutComponent) return null;
      return this.mRootLayoutComponent.getComponent(id);
    }
    invalidateMeasure() {
      if (this.mRootLayoutComponent) this.mRootLayoutComponent.invalidateMeasure();
    }
    getOperations() {
      return this.mOperations;
    }
    getOpsPerFrame() {
      return this.mLastOpCount;
    }
    // --- Content behavior ---
    setRootContentBehavior(scroll, alignment, sizing, mode) {
      this.mContentScroll = scroll;
      this.mContentAlignment = alignment;
      this.mContentSizing = sizing;
      this.mContentMode = mode;
    }
    // --- Loading ---
    // --- ExpansionDocument surface (used by the loom expansion engine) ---
    getNextId() {
      return ++this.mCurrentId;
    }
    getProfileMask() {
      return this.mProfileMask;
    }
    getText(id) {
      const t = this.mTextData.get(id);
      return t === void 0 ? null : t;
    }
    getArray(id) {
      const collected = this.mArrays.get(id);
      if (collected) return collected;
      const arr = this.mRemoteComposeState.getArray?.(id);
      if (arr && typeof arr.getLength === "function" && typeof arr.getId === "function") {
        return arr;
      }
      return null;
    }
    getReferencedOperationsObject(id) {
      return this.mReferencedOperations.get(id) ?? null;
    }
    initFromBuffer(buffer) {
      this.mBuffer = buffer;
      this.mOperations = [];
      buffer.inflateFromBuffer(this.mOperations);
      let maxId = 42;
      this.mTextData.clear();
      this.mReferencedOperations.clear();
      this.mProfileMask = 0;
      for (const op of this.mOperations) {
        const vid = op.getId;
        if (typeof vid === "function") {
          const id = vid.call(op);
          if (typeof id === "number" && id < 1073741824 && id > maxId) {
            maxId = id;
          }
        }
        if (op instanceof Header) {
          this.mHeader = op;
          op.setVersion(this);
          this.mProfileMask = op.getProfiles?.() ?? 0;
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
      this.captureLoomBodies(this.mOperations, buffer);
      this.inflateComponents(this.mOperations);
      this.expandMacros();
    }
    /**
     * Capture the raw body bytes for body-bearing loom containers from the
     * original source buffer. For each PatternDefine (container form),
     * PatternForEach, and ReferencedOperations in the FLAT list, the body bytes
     * are the concatenated bytes of all child ops: from the container's _byteEnd
     * up to the matching ContainerEnd's _byteStart, located via depth counting.
     */
    captureLoomBodies(flat, buffer) {
      captureLoomBodies(flat, buffer.getBuffer().getBuffer());
    }
    expandMacros() {
      const remap = new RemapContext(this);
      const expansionContext = new ExpansionContext(
        this.mLoomManager,
        this,
        remap,
        /* @__PURE__ */ new Map(),
        false,
        0
      );
      const expanded = expansionContext.expandRecursiveTop(this.mOperations, this.mLoomManager);
      const nested = nestContainers(expanded);
      this.mOperations.length = 0;
      for (const op of nested) this.mOperations.push(op);
      this.mRootLayoutComponent = null;
      for (const op of this.mOperations) {
        if (op instanceof RootLayoutComponent) {
          this.mRootLayoutComponent = op;
          break;
        }
      }
    }
    inflateComponents(operations) {
      for (const op of operations) {
        if (op instanceof Header) {
          this.mHeader = op;
          op.setVersion(this);
          break;
        }
      }
      const finalOps = [];
      let ops = finalOps;
      const containers = [];
      for (const op of operations) {
        if (op instanceof RootLayoutComponent) {
          this.mRootLayoutComponent = op;
        }
        if (this.isContainer(op)) {
          const container = op;
          containers.push(container);
          ops = container.getList();
        } else if (op instanceof ContainerEnd) {
          let container = null;
          if (containers.length > 0) {
            container = containers.pop();
          }
          if (containers.length > 0) {
            ops = containers[containers.length - 1].getList();
          } else {
            ops = finalOps;
          }
          if (container) {
            if (typeof container.inflate === "function") {
              container.inflate();
            }
            ops.push(container);
          }
          if (op instanceof BackgroundModifier && containers.length > 0) {
            op.setComponent(containers[containers.length - 1]);
          }
        } else {
          ops.push(op);
          if (op instanceof BackgroundModifier && containers.length > 0) {
            op.setComponent(containers[containers.length - 1]);
          }
        }
      }
      operations.length = 0;
      for (const op of finalOps) {
        operations.push(op);
      }
      this.mFloatExpressions.clear();
      this.indexFloatExpressions(operations);
    }
    /** Collect every FloatExpression in the tree, including inside containers. */
    indexFloatExpressions(operations) {
      for (const op of operations) {
        if (op.constructor?.OP_CODE === 81 && typeof op.mId === "number") {
          this.mFloatExpressions.set(op.mId, op);
        }
        if (typeof op.getList === "function") {
          this.indexFloatExpressions(op.getList());
        }
      }
    }
    /** Evaluate `expressionId` and write the result into `targetId`. */
    evaluateFloatExpression(expressionId, targetId, context) {
      const expression = this.mFloatExpressions.get(expressionId);
      if (expression && typeof expression.evaluate === "function") {
        context.overrideFloat(targetId, expression.evaluate(context));
      }
    }
    isContainer(op) {
      return typeof op.getList === "function" && !(op instanceof ContainerEnd);
    }
    // --- Context initialization ---
    initializeContext(context) {
      context.setDocument(this);
      context.mRemoteComposeState = this.mRemoteComposeState;
      this.mRemoteComposeState.setContext(context);
    }
    /**
     * Seed ID_FONT_SIZE, the platform's default text size.
     *
     * The host owns this value: the Android view uses `14 * density * fontScale`
     * (RemoteComposeView.getDefaultTextSize) and the Compose player the same. Without it
     * the variable reads 0, and any font size a document derives from it resolves to 0 as
     * well. A zero font size is a legal float, so nothing errors — the text measures a
     * real width with zero height, takes up no space, and never paints.
     */
    seedPlatformTextSize(context) {
      let density = context.getDensity();
      if (!(density > 0)) {
        density = this.getProperty(
          7
          /* DOC_DENSITY_AT_GENERATION */
        ) || 1;
      }
      context.loadFloat(27, density);
      context.loadFloat(33, 14 * density * FONT_SCALE);
    }
    applyDataOperations(context) {
      context.setMode("DATA" /* DATA */);
      this.seedPlatformTextSize(context);
      this.mTimeVariables.updateTime(context);
      this.registerVariables(context, this.mOperations);
      this.applyOperations(context, this.mOperations);
      context.setMode("UNSET" /* UNSET */);
    }
    /** Recursively apply operations, matching Java CoreDocument.applyOperations. */
    applyOperations(context, ops) {
      for (const op of ops) {
        if (typeof op.updateVariables === "function") {
          op.updateVariables(context);
        }
        op.markNotDirty();
        context.incrementOpCount();
        if (this.isContainer(op)) {
          this.applyOperations(context, op.getList());
        } else {
          op.apply(context);
        }
      }
    }
    registerVariables(context, ops) {
      for (const op of ops) {
        if (typeof op.registerListening === "function") {
          op.registerListening(context);
        }
        if (typeof op.evaluateInLayout === "function") {
          this.mLayoutComputeOps.add(op);
        }
        if (typeof op.getList === "function" && !(op instanceof ContainerEnd)) {
          this.registerVariables(context, op.getList());
        }
      }
    }
    // --- Painting ---
    paint(context, theme) {
      context.mRemoteComposeState = this.mRemoteComposeState;
      this.mRemoteComposeState.setContext(context);
      this.mClickAreas.clear();
      this.mTimeVariables.updateTime(context);
      if (this.mRootLayoutComponent && this.mLayoutComputeOps.size > 0) {
        let needsEvaluate = true;
        for (let round = 0; needsEvaluate && round < 2; round++) {
          needsEvaluate = false;
          for (const op of this.mLayoutComputeOps) {
            if (op.evaluateInLayout(context)) needsEvaluate = true;
          }
        }
      }
      if (this.mRootLayoutComponent && this.mRootLayoutComponent.needsMeasure()) {
        this.mRootLayoutComponent.layoutTree(context);
      }
      context.setMode("PAINT" /* PAINT */);
      context.clearLastOpCount();
      const themeColors = this.getThemedColors();
      if (themeColors.length > 0 && theme !== context.getPaintTheme()) {
        for (const tc of themeColors) {
          tc.setTheme(context, theme);
        }
      }
      context.setPaintTheme(theme);
      context.setTheme(Theme.UNSPECIFIED);
      let density = context.getDensity();
      if (Number.isNaN(density) || density <= 0) {
        density = this.getProperty(
          7
          /* DOC_DENSITY_AT_GENERATION */
        ) || 1;
        context.setDensity(density);
      }
      context.loadFloat(27, density);
      this.seedPlatformTextSize(context);
      const pc = context.getPaintContext();
      if (pc) pc.save();
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
        let apply = true;
        if (theme !== Theme.UNSPECIFIED) {
          const currentTheme = context.getTheme();
          apply = currentTheme === theme || currentTheme === Theme.UNSPECIFIED || op instanceof Theme;
        }
        if (apply) {
          const opIsDirty = op.isDirty();
          if (opIsDirty && typeof op.updateVariables === "function") {
            op.markNotDirty();
            op.updateVariables(context);
          }
          context.incrementOpCount();
          op.apply(context);
        }
      }
      this.mLastOpCount = context.getLastOpCount();
      if (pc) pc.restore();
      context.setMode("UNSET" /* UNSET */);
      const pc2 = context.getPaintContext();
      if (pc && pc.doesNeedsRepaint()) {
        this.mNeedsRepaintFlag = 1;
      } else {
        this.mNeedsRepaintFlag = this.mRemoteComposeState.getOpsToUpdate(context, this.mClock.millis());
      }
    }
    needsRepaint() {
      return this.mNeedsRepaintFlag;
    }
    needsMeasure() {
      return this.mRootLayoutComponent?.needsMeasure() ?? false;
    }
    measure(context, minW, maxW, minH, maxH) {
      if (this.mRootLayoutComponent) {
        this.mRootLayoutComponent.measureDoc(context, minW, maxW, minH, maxH);
      }
    }
    // --- Click areas ---
    addClickArea(id, contentDescription, left, top, right, bottom, metadata) {
      this.mClickAreas.add({ id, contentDescription, left, top, right, bottom, metadata });
    }
    getClickAreas() {
      return this.mClickAreas;
    }
    onClick(context, x, y) {
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
    addIdActionListener(listener) {
      this.mIdActionListeners.add(listener);
    }
    getIdActionListeners() {
      return this.mIdActionListeners;
    }
    addActionCallback(callback) {
      this.mActionCallbacks.push(callback);
    }
    clearActionCallbacks() {
      this.mActionCallbacks = [];
    }
    runAction(id, metadata) {
      for (const listener of this.mIdActionListeners) {
        listener.onAction(id, metadata);
      }
    }
    runNamedAction(name, value) {
      for (const callback of this.mActionCallbacks) {
        callback.onAction(name, value);
      }
    }
    // --- Touch handling ---
    addTouchListener(listener) {
      this.mTouchListeners.add(listener);
    }
    appliedTouchOperation(component) {
      this.mAppliedTouchOperations.add(component);
    }
    hasTouchListener() {
      if (this.mTouchListeners.size > 0) return true;
      return this.mRootLayoutComponent?.getHasTouchListeners() ?? false;
    }
    touchDown(context, x, y) {
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
    touchDrag(context, x, y) {
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
    touchUp(context, x, y, dx, dy) {
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
    touchCancel(context, x, y, dx, dy) {
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
    performClick(context, id, metadata) {
      for (const area of this.mClickAreas) {
        if (area.id === id) {
          this.runAction(area.id, area.metadata);
          return true;
        }
      }
      this.runAction(id, metadata);
      const component = this.getComponent(id);
      if (component) {
        return component.onClick?.(context, this, -1, -1) ?? false;
      }
      return false;
    }
    // --- Content scaling and translation ---
    computeScale(w, h, scaleOutput) {
      let contentScaleX = 1;
      let contentScaleY = 1;
      if (this.mContentSizing === RootContentBehavior.SIZING_SCALE) {
        let scaleX, scaleY, scale;
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
    computeTranslate(w, h, contentScaleX, contentScaleY, translateOutput) {
      const horizontalContentAlignment = this.mContentAlignment & 240;
      const verticalContentAlignment = this.mContentAlignment & 15;
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
    setUpdateDoc(v) {
      this.mIsUpdateDoc = v;
    }
    isUpdateDoc() {
      return this.mIsUpdateDoc;
    }
    applyUpdate(delta) {
      const txtData = /* @__PURE__ */ new Map();
      const imgData = /* @__PURE__ */ new Map();
      const fltData = /* @__PURE__ */ new Map();
      const intData = /* @__PURE__ */ new Map();
      const longData = /* @__PURE__ */ new Map();
      const floatListData = /* @__PURE__ */ new Map();
      this.recursiveTraverse(this.mOperations, (op) => {
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
      this.recursiveTraverse(delta.mOperations, (op) => {
        if (op instanceof TextData) {
          const existing = txtData.get(op.mTextId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        } else if (op instanceof BitmapData) {
          const existing = imgData.get(op.mImageId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        } else if (op instanceof FloatConstant) {
          const existing = fltData.get(op.mId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        } else if (op instanceof IntegerConstant) {
          const existing = intData.get(op.mId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        } else if (op instanceof LongConstant) {
          const existing = longData.get(op.mId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        } else if (op instanceof DataListFloat) {
          const existing = floatListData.get(op.mId);
          if (existing) {
            existing.update(op);
            existing.markDirty();
          }
        }
      });
    }
    recursiveTraverse(operations, visitor) {
      for (const op of operations) {
        if (typeof op.getList === "function") {
          this.recursiveTraverse(op.getList(), visitor);
        }
        visitor(op);
      }
    }
    // --- Diagnostics ---
    getNumberOfOps() {
      return this.mOperations.length;
    }
    toNestedString() {
      return this.mOperations.map((op) => op.deepToString("  ")).join("\n");
    }
    toString() {
      return `CoreDocument(${this.mOperations.length} ops, ${this.mWidth}x${this.mHeight})`;
    }
    displayHierarchy() {
      if (this.mRootLayoutComponent) {
        return this.mRootLayoutComponent.displayHierarchy();
      }
      return this.toString();
    }
  };
  _CoreDocument.MAJOR_VERSION = 1;
  _CoreDocument.MINOR_VERSION = 1;
  _CoreDocument.PATCH_VERSION = 0;
  _CoreDocument.DOCUMENT_API_LEVEL = 8;
  var CoreDocument = _CoreDocument;

  // third_party/remote-compose-player/src/core/shader/AgslTokenizer.ts
  var IDENT_START = /[a-zA-Z_]/;
  var IDENT_CONT = /[a-zA-Z0-9_]/;
  var DIGIT = /[0-9]/;
  var WS = /[ \t\r\n]/;
  var OP_CHARS = new Set("+-*/%=<>!&|^~?:");
  var PUNCT_CHARS = new Set("(){}[];,");
  function tokenize(source) {
    const tokens = [];
    let i = 0;
    const n = source.length;
    while (i < n) {
      const ch = source[i];
      if (WS.test(ch)) {
        const start = i;
        while (i < n && WS.test(source[i])) i++;
        tokens.push({ type: 5 /* Whitespace */, value: source.slice(start, i) });
        continue;
      }
      if (ch === "/" && i + 1 < n && source[i + 1] === "/") {
        const start = i;
        while (i < n && source[i] !== "\n") i++;
        tokens.push({ type: 6 /* LineComment */, value: source.slice(start, i) });
        continue;
      }
      if (ch === "/" && i + 1 < n && source[i + 1] === "*") {
        const start = i;
        i += 2;
        while (i < n && !(source[i - 1] === "*" && source[i] === "/")) i++;
        if (i < n) i++;
        tokens.push({ type: 7 /* BlockComment */, value: source.slice(start, i) });
        continue;
      }
      if (ch === "#") {
        const start = i;
        while (i < n && source[i] !== "\n") i++;
        tokens.push({ type: 8 /* Preprocessor */, value: source.slice(start, i) });
        continue;
      }
      if (DIGIT.test(ch) || ch === "." && i + 1 < n && DIGIT.test(source[i + 1])) {
        const start = i;
        while (i < n && DIGIT.test(source[i])) i++;
        if (i < n && source[i] === ".") {
          i++;
          while (i < n && DIGIT.test(source[i])) i++;
        }
        if (i < n && (source[i] === "e" || source[i] === "E")) {
          i++;
          if (i < n && (source[i] === "+" || source[i] === "-")) i++;
          while (i < n && DIGIT.test(source[i])) i++;
        }
        tokens.push({ type: 1 /* Number */, value: source.slice(start, i) });
        continue;
      }
      if (IDENT_START.test(ch)) {
        const start = i;
        while (i < n && IDENT_CONT.test(source[i])) i++;
        tokens.push({ type: 0 /* Identifier */, value: source.slice(start, i) });
        continue;
      }
      if (ch === ".") {
        tokens.push({ type: 4 /* Dot */, value: "." });
        i++;
        continue;
      }
      if (PUNCT_CHARS.has(ch)) {
        tokens.push({ type: 3 /* Punctuation */, value: ch });
        i++;
        continue;
      }
      if (OP_CHARS.has(ch)) {
        const start = i;
        i++;
        if (i < n && OP_CHARS.has(source[i])) {
          const two = source.slice(start, i + 1);
          if ([
            "<=",
            ">=",
            "==",
            "!=",
            "&&",
            "||",
            "<<",
            ">>",
            "+=",
            "-=",
            "*=",
            "/=",
            "%=",
            "&=",
            "|=",
            "^="
          ].includes(two)) {
            i++;
          }
        }
        tokens.push({ type: 2 /* Operator */, value: source.slice(start, i) });
        continue;
      }
      tokens.push({ type: 0 /* Identifier */, value: ch });
      i++;
    }
    tokens.push({ type: 9 /* EOF */, value: "" });
    return tokens;
  }

  // third_party/remote-compose-player/src/core/shader/AgslTranspiler.ts
  var TYPE_MAP = {
    "half4": "vec4",
    "half3": "vec3",
    "half2": "vec2",
    "half": "float",
    "float2": "vec2",
    "float3": "vec3",
    "float4": "vec4",
    "int2": "ivec2",
    "int3": "ivec3",
    "int4": "ivec4"
  };
  var UNIFORM_TYPE_MAP = {
    ...TYPE_MAP,
    "float": "float",
    "int": "int",
    "vec2": "vec2",
    "vec3": "vec3",
    "vec4": "vec4",
    "ivec2": "ivec2",
    "ivec3": "ivec3",
    "ivec4": "ivec4",
    "sampler2D": "sampler2D"
  };
  function isSkip(t) {
    return t.type === 5 /* Whitespace */ || t.type === 6 /* LineComment */ || t.type === 7 /* BlockComment */;
  }
  function skipWs(tokens, from) {
    while (from < tokens.length && isSkip(tokens[from])) from++;
    return from;
  }
  function transpileAgslToGlsl(agslSource) {
    const tokens = tokenize(agslSource);
    const uniforms = [];
    const childShaders = [];
    const out = [];
    let i = 0;
    const n = tokens.length;
    let inMain = false;
    let mainBraceDepth = 0;
    let mainParamName = "p";
    let hasIResolution = false;
    let needsResolutionUniform = false;
    for (let j = 0; j < n; j++) {
      if (tokens[j].type === 0 /* Identifier */ && tokens[j].value === "iResolution") {
        let k = j - 1;
        while (k >= 0 && isSkip(tokens[k])) k--;
        if (k >= 0) {
          let k2 = k - 1;
          while (k2 >= 0 && isSkip(tokens[k2])) k2--;
          if (k2 >= 0 && tokens[k2].value === "uniform") {
            hasIResolution = true;
          }
        }
      }
    }
    while (i < n) {
      const tok = tokens[i];
      if (tok.type === 0 /* Identifier */ && tok.value === "uniform") {
        const j1 = skipWs(tokens, i + 1);
        if (j1 < n && tokens[j1].type === 0 /* Identifier */ && tokens[j1].value === "shader") {
          const j2 = skipWs(tokens, j1 + 1);
          if (j2 < n && tokens[j2].type === 0 /* Identifier */) {
            const shaderName = tokens[j2].value;
            childShaders.push(shaderName);
            needsResolutionUniform = true;
            out.push("uniform sampler2D");
            for (let k = j1 + 1; k <= j2; k++) {
              if (tokens[k].type === 5 /* Whitespace */) out.push(tokens[k].value);
            }
            out.push(shaderName);
            i = j2 + 1;
            continue;
          }
        }
      }
      if (tok.type === 0 /* Identifier */ && tok.value === "uniform") {
        const j1 = skipWs(tokens, i + 1);
        if (j1 < n && tokens[j1].type === 0 /* Identifier */) {
          const typeName = tokens[j1].value;
          if (typeName !== "shader") {
            const j2 = skipWs(tokens, j1 + 1);
            if (j2 < n && tokens[j2].type === 0 /* Identifier */) {
              const glslType = UNIFORM_TYPE_MAP[typeName] || TYPE_MAP[typeName] || typeName;
              uniforms.push({ name: tokens[j2].value, glslType });
            }
          }
        }
      }
      if (tok.type === 0 /* Identifier */ && tok.value === "main" && !inMain) {
        let retIdx = i - 1;
        while (retIdx >= 0 && isSkip(tokens[retIdx])) retIdx--;
        const parenIdx = skipWs(tokens, i + 1);
        if (parenIdx < n && tokens[parenIdx].type === 3 /* Punctuation */ && tokens[parenIdx].value === "(") {
          const paramTypeIdx = skipWs(tokens, parenIdx + 1);
          const paramNameIdx = skipWs(tokens, paramTypeIdx + 1);
          const closeIdx = skipWs(tokens, paramNameIdx + 1);
          if (paramNameIdx < n && tokens[paramNameIdx].type === 0 /* Identifier */) {
            mainParamName = tokens[paramNameIdx].value;
          }
          let braceIdx = closeIdx;
          if (braceIdx < n && tokens[braceIdx].value === ")") {
            braceIdx = skipWs(tokens, braceIdx + 1);
          }
          let removeFrom = out.length - 1;
          while (removeFrom >= 0 && out[removeFrom].trim() === "") removeFrom--;
          if (removeFrom >= 0) out.splice(removeFrom, 1);
          out.push("void main()");
          i = braceIdx;
          if (i < n && tokens[i].type === 3 /* Punctuation */ && tokens[i].value === "{") {
            out.push(" {\n");
            out.push(`  vec2 ${mainParamName} = vec2(gl_FragCoord.x, u_resolution.y - gl_FragCoord.y) * u_doc_size / u_resolution;
`);
            inMain = true;
            mainBraceDepth = 1;
            i++;
          }
          continue;
        }
      }
      if (inMain && tok.type === 3 /* Punctuation */) {
        if (tok.value === "{") mainBraceDepth++;
        if (tok.value === "}") {
          mainBraceDepth--;
          if (mainBraceDepth === 0) inMain = false;
        }
      }
      if (inMain && tok.type === 0 /* Identifier */ && tok.value === "return") {
        const exprParts = [];
        i++;
        let depth = 0;
        while (i < n) {
          const t = tokens[i];
          if (t.type === 3 /* Punctuation */ && t.value === ";" && depth === 0) {
            break;
          }
          if (t.type === 3 /* Punctuation */ && (t.value === "(" || t.value === "[")) depth++;
          if (t.type === 3 /* Punctuation */ && (t.value === ")" || t.value === "]")) depth--;
          exprParts.push(rewriteToken(tokens, i, childShaders));
          i++;
        }
        const expr = exprParts.join("");
        out.push(`fragColor = ${expr}; return`);
        continue;
      }
      if (tok.type === 0 /* Identifier */ && childShaders.includes(tok.value)) {
        const dotIdx = skipWs(tokens, i + 1);
        if (dotIdx < n && tokens[dotIdx].type === 4 /* Dot */) {
          const evalIdx = skipWs(tokens, dotIdx + 1);
          if (evalIdx < n && tokens[evalIdx].type === 0 /* Identifier */ && tokens[evalIdx].value === "eval") {
            const parenIdx = skipWs(tokens, evalIdx + 1);
            if (parenIdx < n && tokens[parenIdx].value === "(") {
              let depth = 1;
              let argStart = parenIdx + 1;
              let j = argStart;
              while (j < n && depth > 0) {
                if (tokens[j].value === "(") depth++;
                if (tokens[j].value === ")") depth--;
                if (depth > 0) j++;
              }
              const argParts = [];
              for (let k = argStart; k < j; k++) {
                argParts.push(rewriteToken(tokens, k, childShaders));
              }
              const arg = argParts.join("");
              out.push(`texture(${tok.value}, (${arg}) / u_resolution)`);
              i = j + 1;
              continue;
            }
          }
        }
      }
      out.push(rewriteToken(tokens, i, childShaders));
      i++;
    }
    let glsl = "#version 300 es\nprecision highp float;\n";
    glsl += "uniform vec2 u_resolution;\n";
    glsl += "uniform vec2 u_doc_size;\n";
    glsl += "out vec4 fragColor;\n";
    glsl += out.join("");
    return { glsl, uniforms, childShaders };
  }
  var INT_CONTEXT_KEYWORDS = /* @__PURE__ */ new Set([
    "int",
    "ivec2",
    "ivec3",
    "ivec4"
  ]);
  function isIntContext(tokens, idx) {
    let j = idx - 1;
    while (j >= 0 && isSkip(tokens[j])) j--;
    if (j >= 0) {
      if (tokens[j].value === "[") return true;
      if (tokens[j].type === 0 /* Identifier */ && INT_CONTEXT_KEYWORDS.has(tokens[j].value)) return true;
      if (tokens[j].value === "=") {
        let k = j - 1;
        while (k >= 0 && isSkip(tokens[k])) k--;
        if (k >= 0) {
          let k2 = k - 1;
          while (k2 >= 0 && isSkip(tokens[k2])) k2--;
          if (k2 >= 0 && INT_CONTEXT_KEYWORDS.has(tokens[k2].value)) return true;
        }
      }
      if (tokens[j].value === "<" || tokens[j].value === ">" || tokens[j].value === "<=" || tokens[j].value === ">=" || tokens[j].value === "==" || tokens[j].value === "!=") {
        let k = j - 1;
        while (k >= 0 && isSkip(tokens[k])) k--;
        if (k >= 0 && tokens[k].type === 0 /* Identifier */ && tokens[k].value.length <= 2) return true;
      }
      if (tokens[j].value === "++" || tokens[j].value === "--") return true;
      if (tokens[j].value === "(" || tokens[j].value === ",") {
        let k = j - 1;
        while (k >= 0 && isSkip(tokens[k])) k--;
        if (k >= 0 && tokens[k].type === 0 /* Identifier */ && INT_CONTEXT_KEYWORDS.has(tokens[k].value)) return true;
      }
    }
    return false;
  }
  function rewriteToken(tokens, idx, _childShaders) {
    const tok = tokens[idx];
    if (tok.type === 0 /* Identifier */) {
      if (TYPE_MAP[tok.value]) return TYPE_MAP[tok.value];
      if (tok.value === "sk_FragCoord") return "gl_FragCoord.xy";
    }
    if (tok.type === 1 /* Number */) {
      const v = tok.value;
      const isInt = /^\d+$/.test(v);
      if (isInt && !isIntContext(tokens, idx)) {
        return v + ".0";
      }
    }
    return tok.value;
  }

  // third_party/remote-compose-player/src/web/shader/WebGLShaderRenderer.ts
  var VERTEX_SHADER = `#version 300 es
in vec2 a_position;
void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
}
`;
  var QUAD_VERTS = new Float32Array([
    -1,
    -1,
    1,
    -1,
    -1,
    1,
    -1,
    1,
    1,
    -1,
    1,
    1
  ]);
  var WebGLShaderRenderer = class {
    constructor() {
      this.gl = null;
      this.vao = null;
      this.programCache = /* @__PURE__ */ new Map();
      this.canvas = document.createElement("canvas");
    }
    /** The offscreen canvas — use as drawImage source after render(). */
    getCanvas() {
      return this.canvas;
    }
    /** Returns true if WebGL2 is available. */
    isAvailable() {
      return this.initGL() !== null;
    }
    /**
     * Render a GLSL fragment shader to the offscreen canvas.
     *
     * @param glsl       - GLSL ES 3.0 source (from AgslTranspiler)
     * @param width      - render target width
     * @param height     - render target height
     * @param floats     - float uniform values: name → Float32Array
     * @param ints       - int uniform values: name → Int32Array
     * @param textures   - texture images: name → source element
     * @param cacheKey   - optional cache key for the compiled program
     *                     (use shader text ID for stable caching)
     */
    render(glsl, width, height, floats, ints, textures, cacheKey, docWidth, docHeight) {
      const gl = this.initGL();
      if (!gl) return false;
      if (this.canvas.width !== width || this.canvas.height !== height) {
        this.canvas.width = width;
        this.canvas.height = height;
      }
      const key = cacheKey ?? glsl;
      let cached = this.programCache.get(key);
      if (!cached) {
        const program = this.compileProgram(gl, glsl);
        if (!program) return false;
        cached = { program, uniformLocations: /* @__PURE__ */ new Map() };
        this.programCache.set(key, cached);
      }
      gl.useProgram(cached.program);
      gl.viewport(0, 0, width, height);
      const resLoc = this.getUniformLoc(gl, cached, "u_resolution");
      if (resLoc) gl.uniform2f(resLoc, width, height);
      const docLoc = this.getUniformLoc(gl, cached, "u_doc_size");
      if (docLoc) gl.uniform2f(docLoc, docWidth ?? width, docHeight ?? height);
      if (floats) {
        for (const [name, values] of floats) {
          const loc = this.getUniformLoc(gl, cached, name);
          if (!loc) continue;
          switch (values.length) {
            case 1:
              gl.uniform1f(loc, values[0]);
              break;
            case 2:
              gl.uniform2f(loc, values[0], values[1]);
              break;
            case 3:
              gl.uniform3f(loc, values[0], values[1], values[2]);
              break;
            case 4:
              gl.uniform4f(loc, values[0], values[1], values[2], values[3]);
              break;
            default:
              gl.uniform1fv(loc, values);
              break;
          }
        }
      }
      if (ints) {
        for (const [name, values] of ints) {
          const loc = this.getUniformLoc(gl, cached, name);
          if (!loc) continue;
          switch (values.length) {
            case 1:
              gl.uniform1i(loc, values[0]);
              break;
            case 2:
              gl.uniform2i(loc, values[0], values[1]);
              break;
            case 3:
              gl.uniform3i(loc, values[0], values[1], values[2]);
              break;
            case 4:
              gl.uniform4i(loc, values[0], values[1], values[2], values[3]);
              break;
            default:
              gl.uniform1iv(loc, values);
              break;
          }
        }
      }
      let texUnit = 0;
      if (textures) {
        for (const [name, img] of textures) {
          const loc = this.getUniformLoc(gl, cached, name);
          if (!loc) {
            texUnit++;
            continue;
          }
          const unit = texUnit++;
          gl.activeTexture(gl.TEXTURE0 + unit);
          const tex = gl.createTexture();
          gl.bindTexture(gl.TEXTURE_2D, tex);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
          gl.uniform1i(loc, unit);
        }
      }
      gl.bindVertexArray(this.vao);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      for (let u = 0; u < texUnit; u++) {
        gl.activeTexture(gl.TEXTURE0 + u);
        gl.bindTexture(gl.TEXTURE_2D, null);
      }
      return true;
    }
    /** Destroy WebGL resources. */
    destroy() {
      const gl = this.gl;
      if (!gl) return;
      for (const cached of this.programCache.values()) {
        gl.deleteProgram(cached.program);
      }
      this.programCache.clear();
      if (this.vao) gl.deleteVertexArray(this.vao);
      this.vao = null;
      const lose = gl.getExtension("WEBGL_lose_context");
      if (lose) lose.loseContext();
      this.gl = null;
    }
    // ── Internal ──────────────────────────────────────────────────────
    initGL() {
      if (this.gl) return this.gl;
      const gl = this.canvas.getContext("webgl2", {
        alpha: true,
        premultipliedAlpha: false,
        preserveDrawingBuffer: true
      });
      if (!gl) return null;
      this.gl = gl;
      this.vao = gl.createVertexArray();
      gl.bindVertexArray(this.vao);
      const buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, QUAD_VERTS, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      return gl;
    }
    compileProgram(gl, fragSource) {
      const vs = this.compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER);
      const fs = this.compileShader(gl, gl.FRAGMENT_SHADER, fragSource);
      if (!vs || !fs) return null;
      const prog = gl.createProgram();
      gl.attachShader(prog, vs);
      gl.attachShader(prog, fs);
      gl.bindAttribLocation(prog, 0, "a_position");
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
        console.error("Shader link error:", gl.getProgramInfoLog(prog));
        gl.deleteProgram(prog);
        return null;
      }
      gl.deleteShader(vs);
      gl.deleteShader(fs);
      return prog;
    }
    compileShader(gl, type, source) {
      const shader = gl.createShader(type);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
        console.error(
          `Shader compile error (${type === gl.VERTEX_SHADER ? "vert" : "frag"}):`,
          gl.getShaderInfoLog(shader)
        );
        console.error("Source:\n" + source.split("\n").map((l, i) => `${i + 1}: ${l}`).join("\n"));
        gl.deleteShader(shader);
        return null;
      }
      return shader;
    }
    getUniformLoc(gl, cached, name) {
      let loc = cached.uniformLocations.get(name);
      if (loc === void 0) {
        loc = gl.getUniformLocation(cached.program, name);
        cached.uniformLocations.set(name, loc);
      }
      return loc;
    }
  };

  // third_party/remote-compose-player/src/web/WebFonts.ts
  var DEFAULT_BASE_URL = "https://fonts.googleapis.com/css2";
  var config = { enabled: true, baseUrl: DEFAULT_BASE_URL };
  function configureWebFonts(patch) {
    config = { ...config, ...patch };
  }
  var WEIGHTS = [100, 200, 300, 400, 500, 600, 700, 800, 900];
  function googleFontsUrl(family, baseUrl = config.baseUrl) {
    const axis = WEIGHTS.map((w) => `0,${w}`).concat(WEIGHTS.map((w) => `1,${w}`)).join(";");
    const name = encodeURIComponent(family.trim()).replace(/%20/g, "+");
    return `${baseUrl}?family=${name}:ital,wght@${axis}&display=block`;
  }
  function googleFontsAxisUrl(family, axes, baseUrl = config.baseUrl) {
    const varying = axes.filter((a) => a.max > a.min).sort((a, b) => a.tag < b.tag ? -1 : 1);
    if (varying.length === 0) return null;
    const tags = varying.map((a) => a.tag).join(",");
    const ranges = varying.map((a) => `${trimNumber(a.min)}..${trimNumber(a.max)}`).join(",");
    const name = encodeURIComponent(family.trim()).replace(/%20/g, "+");
    return `${baseUrl}?family=${name}:${tags}@${ranges}&display=block`;
  }
  function trimNumber(value) {
    return Number.isInteger(value) ? `${value}` : `${value}`.replace(/0+$/, "");
  }
  var GOOGLE_PREFIX = "google:";
  function parseFamily(family) {
    const trimmed = family.trim();
    if (trimmed.toLowerCase().startsWith(GOOGLE_PREFIX)) {
      return { source: "google", name: trimmed.slice(GOOGLE_PREFIX.length).trim() };
    }
    return { source: "local", name: trimmed };
  }
  var stylesheets = /* @__PURE__ */ new Map();
  var variants = /* @__PURE__ */ new Map();
  var embeddedFaces = /* @__PURE__ */ new Map();
  var done = /* @__PURE__ */ new Set();
  var waiting = /* @__PURE__ */ new Map();
  function variantKey(family, weight, italic, axes = []) {
    const axisPart = axes.map(({ tag, value }) => `${tag}=${value}`).join(",");
    return `${family.toLowerCase()}|${weight}|${italic ? "i" : "n"}|${axisPart}`;
  }
  function notify(key) {
    done.add(key);
    const listeners = waiting.get(key);
    waiting.delete(key);
    listeners?.forEach((fn) => fn());
  }
  var failed = /* @__PURE__ */ new Set();
  function unquote(family) {
    return family.replace(/^["']|["']$/g, "");
  }
  function cssQuoted(family) {
    return family.replace(/[\\"]/g, "\\$&");
  }
  function hasLocalFace(family) {
    const want = family.toLowerCase();
    let found = false;
    document.fonts.forEach((face) => {
      if (unquote(face.family).toLowerCase() === want) found = true;
    });
    return found;
  }
  var ownVariableFamilies = /* @__PURE__ */ new Set();
  function hasLocalVariableFace(family) {
    const want = family.toLowerCase();
    let found = false;
    document.fonts.forEach((face) => {
      if (unquote(face.family).toLowerCase() !== want) return;
      if (face.weight.includes(" ") || face.stretch.includes(" ")) found = true;
    });
    return found;
  }
  function loadStylesheet(url) {
    return new Promise((resolve, reject) => {
      const link = document.createElement("link");
      link.rel = "stylesheet";
      link.href = url;
      link.addEventListener("load", () => resolve());
      link.addEventListener("error", () => reject(new Error(`stylesheet did not load: ${url}`)));
      document.head.appendChild(link);
    });
  }
  var axisSpans = /* @__PURE__ */ new Map();
  function recordAxes(family, axes) {
    const key = family.toLowerCase();
    const spans = axisSpans.get(key) ?? /* @__PURE__ */ new Map();
    for (const { tag, value } of axes) {
      const span = spans.get(tag);
      if (span) {
        span.min = Math.min(span.min, value);
        span.max = Math.max(span.max, value);
      } else {
        spans.set(tag, { min: value, max: value });
      }
    }
    axisSpans.set(key, spans);
    return [...spans].map(([tag, { min, max }]) => ({ tag, min, max }));
  }
  function registerStylesheet(family, url = googleFontsUrl(family), variable = false) {
    const key = `${family.toLowerCase()}|${url}`;
    const existing = stylesheets.get(key);
    if (existing) return existing;
    let p;
    if (!config.enabled) {
      p = Promise.resolve();
    } else if (typeof document === "undefined" || !document.fonts) {
      p = Promise.resolve();
    } else if (variable ? !ownVariableFamilies.has(family.toLowerCase()) && hasLocalVariableFace(family) : hasLocalFace(family)) {
      p = Promise.resolve();
    } else {
      if (variable) ownVariableFamilies.add(family.toLowerCase());
      p = loadStylesheet(url);
    }
    stylesheets.set(key, p);
    return p;
  }
  async function loadVariant(family, weight, italic, axes) {
    await registerStylesheet(family);
    if (axes.length > 0) {
      const url = googleFontsAxisUrl(family, recordAxes(family, axes));
      if (url) await registerStylesheet(family, url, true).catch(() => {
      });
    }
    if (typeof document === "undefined" || !document.fonts) return;
    await document.fonts.load(`${italic ? "italic " : ""}${weight} 16px "${cssQuoted(family)}"`);
  }
  function ensureWebFont(family, weight = 400, italic = false, onLoaded, axes = []) {
    const key = variantKey(family, weight, italic, axes);
    if (onLoaded && !done.has(key)) {
      const set = waiting.get(key) ?? /* @__PURE__ */ new Set();
      set.add(onLoaded);
      waiting.set(key, set);
    }
    const existing = variants.get(key);
    if (existing) return existing;
    const p = loadVariant(family, weight, italic, axes).catch((e) => {
      const famKey = family.toLowerCase();
      if (!failed.has(famKey)) {
        failed.add(famKey);
        console.warn(`WebFonts: no web font for "${family}", using the fallback stack`, e);
      }
    }).then(() => notify(key));
    variants.set(key, p);
    return p;
  }
  function embeddedFamily(fontId, data) {
    let hash = 2166136261;
    for (const byte of data) {
      hash ^= byte;
      hash = Math.imul(hash, 16777619);
    }
    return `__rc_font_${fontId}_${data.length}_${(hash >>> 0).toString(16)}`;
  }
  function uint16(data, offset) {
    return data[offset] << 8 | data[offset + 1];
  }
  function uint32(data, offset) {
    return (data[offset] << 24 | data[offset + 1] << 16 | data[offset + 2] << 8 | data[offset + 3]) >>> 0;
  }
  function fixed1616(data, offset) {
    return (uint32(data, offset) | 0) / 65536;
  }
  function asciiTag(data, offset) {
    return String.fromCharCode(data[offset], data[offset + 1], data[offset + 2], data[offset + 3]);
  }
  function embeddedFontDescriptors(data) {
    try {
      let sfnt = 0;
      if (data.length >= 16 && asciiTag(data, 0) === "ttcf") {
        if (uint32(data, 8) < 1) return {};
        sfnt = uint32(data, 12);
      }
      if (sfnt + 12 > data.length) return {};
      const tables = uint16(data, sfnt + 4);
      let fvar = -1;
      for (let i = 0; i < tables; i++) {
        const record = sfnt + 12 + i * 16;
        if (record + 16 > data.length) return {};
        if (asciiTag(data, record) === "fvar") {
          fvar = uint32(data, record + 8);
          break;
        }
      }
      if (fvar < 0 || fvar + 16 > data.length) return {};
      const axesOffset = uint16(data, fvar + 4);
      const axisCount = uint16(data, fvar + 8);
      const axisSize = uint16(data, fvar + 10);
      if (axisSize < 20) return {};
      const descriptors = {};
      for (let i = 0; i < axisCount; i++) {
        const axis = fvar + axesOffset + i * axisSize;
        if (axis + 20 > data.length) return {};
        const tag = asciiTag(data, axis);
        const min = fixed1616(data, axis + 4);
        const max = fixed1616(data, axis + 12);
        if (!Number.isFinite(min) || !Number.isFinite(max) || max < min) continue;
        if (tag === "wght") descriptors.weight = `${trimNumber(min)} ${trimNumber(max)}`;
        if (tag === "wdth") descriptors.stretch = `${trimNumber(min)}% ${trimNumber(max)}%`;
      }
      return descriptors;
    } catch (_) {
      return {};
    }
  }
  function registerEmbeddedFont(fontId, data, onLoaded) {
    const family = embeddedFamily(fontId, data);
    const key = `embedded|${family}`;
    if (onLoaded && !done.has(key)) {
      const set = waiting.get(key) ?? /* @__PURE__ */ new Set();
      set.add(onLoaded);
      waiting.set(key, set);
    }
    const retained = embeddedFaces.get(key);
    if (retained) {
      retained.references += 1;
      return family;
    }
    let load;
    if (typeof document === "undefined" || !document.fonts || typeof FontFace === "undefined") {
      embeddedFaces.set(key, { references: 1 });
      load = Promise.resolve();
    } else {
      const bytes = data.slice().buffer;
      const face = new FontFace(family, bytes, embeddedFontDescriptors(data));
      embeddedFaces.set(key, { face, references: 1 });
      document.fonts.add(face);
      load = face.load().then(() => void 0).catch((e) => {
        console.warn(`WebFonts: embedded font ${fontId} could not be loaded`, e);
      });
    }
    const promise = load.then(() => {
      if (embeddedFaces.has(key)) notify(key);
    });
    variants.set(key, promise);
    return family;
  }
  function releaseEmbeddedFont(fontId, data, onLoaded) {
    const key = `embedded|${embeddedFamily(fontId, data)}`;
    const listeners = waiting.get(key);
    if (onLoaded) listeners?.delete(onLoaded);
    if (listeners?.size === 0) waiting.delete(key);
    const retained = embeddedFaces.get(key);
    if (!retained) return;
    retained.references -= 1;
    if (retained.references > 0) return;
    if (retained.face && typeof document !== "undefined" && document.fonts) {
      document.fonts.delete(retained.face);
    }
    embeddedFaces.delete(key);
    variants.delete(key);
    done.delete(key);
    waiting.delete(key);
  }
  async function webFontsReady() {
    let pending = [...variants.values()];
    while (pending.length > 0) {
      await Promise.all(pending);
      const next = [...variants.values()];
      if (next.length === pending.length) break;
      pending = next;
    }
  }
  function resetWebFonts() {
    if (typeof document !== "undefined" && document.fonts) {
      embeddedFaces.forEach(({ face }) => {
        if (face) document.fonts.delete(face);
      });
    }
    embeddedFaces.clear();
    stylesheets.clear();
    axisSpans.clear();
    ownVariableFamilies.clear();
    variants.clear();
    done.clear();
    waiting.clear();
    failed.clear();
    config = { enabled: true, baseUrl: DEFAULT_BASE_URL };
  }

  // third_party/remote-compose-player/src/web/CanvasPaintContext.ts
  var FONT_STRETCH_STEPS = [
    [50, "ultra-condensed"],
    [62.5, "extra-condensed"],
    [75, "condensed"],
    [87.5, "semi-condensed"],
    [100, "normal"],
    [112.5, "semi-expanded"],
    [125, "expanded"],
    [150, "extra-expanded"],
    [200, "ultra-expanded"]
  ];
  function nearestFontStretch(percent) {
    let best = FONT_STRETCH_STEPS[0];
    for (const step of FONT_STRETCH_STEPS) {
      if (Math.abs(step[0] - percent) < Math.abs(best[0] - percent)) best = step;
    }
    return best[1];
  }
  function argbToRgba(argb) {
    const a = (argb >>> 24 & 255) / 255;
    const r = argb >>> 16 & 255;
    const g = argb >>> 8 & 255;
    const b = argb & 255;
    return `rgba(${r},${g},${b},${a.toFixed(3)})`;
  }
  function cssFontStackFor(fontType) {
    switch (fontType) {
      case 2:
        return '"Noto Serif", serif';
      case 3:
        return '"Droid Sans Mono", monospace';
      // 1 = SANS_SERIF is Android's own name for the Roboto stack, so it and
      // DEFAULT (0, and anything unrecognised) resolve to the same face.
      default:
        return "Roboto, sans-serif";
    }
  }
  function namedFontStack(family) {
    return `"${cssQuoted(family)}", ${cssFontStackFor(0)}`;
  }
  var DEFAULT_TEXT_SIZE = 14;
  var ASCENT_RATIO = 0.92;
  var DESCENT_RATIO = 0.24;
  var AVG_ADVANCE = 0.528;
  var _CanvasPaintContext = class _CanvasPaintContext extends PaintContext {
    constructor(context, canvas) {
      super(context);
      // Current paint state
      this.color = "rgba(0,0,0,1)";
      this.style = 0;
      // 0=FILL, 1=STROKE, 2=FILL_AND_STROKE
      this.strokeWidth = 1;
      this.textSize = 14;
      this.alpha = 1;
      this.lineCap = "butt";
      this.lineJoin = "miter";
      this.miterLimit = 10;
      this.blendMode = "source-over";
      this.antiAlias = true;
      this.filterBitmap = true;
      this.letterSpacing = 0;
      this.gradientStyle = null;
      this.fontFamily = cssFontStackFor(0);
      this.fontWeight = 400;
      this.fontItalic = false;
      /** The document's font-variation axes for the current paint, resolved to their tag names. */
      this.fontAxes = [];
      /**
       * The bare `google:` family of the current paint, or null when it names none.
       *
       * Kept because the axes arrive *after* the family does: a paint bundle serialises `setTextStyle`
       * (TYPEFACE) before `setTextAxis` (FONT_AXIS), so the request made while resolving the typeface
       * necessarily has no axes yet. Re-requesting once they are decoded is what actually asks the API
       * for the variable face; without it a networked render keeps the enumerated static stylesheet and
       * paints a `wdth` ramp as identical lines. (It looks fine on a page that vendored the variable
       * face itself, which is exactly how this hid.)
       */
      this.googleFamily = null;
      this.colorFilterColor = null;
      this.colorFilterArgb = 0;
      this.colorFilterMode = 3;
      // SRC_OVER default
      // Active shader (set via PaintBundle.SHADER)
      this.activeShaderData = null;
      this.shaderRenderer = null;
      this.glslCache = /* @__PURE__ */ new Map();
      // shaderTextId -> transpiled GLSL
      // Paint stack for save/restore
      this.paintStack = [];
      // Path cache: id -> Int32Array (raw float32 int bits; markers/variable refs
      // are NaN-with-payload — decoded from bits, never via Number.isNaN, so the
      // ids survive on engines that canonicalize NaN payloads (Safari/Firefox)).
      this.pathDataCache = /* @__PURE__ */ new Map();
      this.pathWindingCache = /* @__PURE__ */ new Map();
      // Bitmap cache: id -> ImageBitmap or HTMLImageElement
      this.bitmapCache = /* @__PURE__ */ new Map();
      this.bitmapPromises = /* @__PURE__ */ new Map();
      // Text cache: id -> string
      this.textCache = /* @__PURE__ */ new Map();
      // Graphics layer stack for offscreen compositing
      this.layerStack = [];
      // Factory for creating offscreen canvases - override for node-canvas
      this.createLayerCanvas = (w, h) => {
        if (typeof OffscreenCanvas !== "undefined") {
          const c = new OffscreenCanvas(w, h);
          return c.getContext("2d");
        }
        if (typeof document !== "undefined") {
          const c = document.createElement("canvas");
          c.width = w;
          c.height = h;
          return c.getContext("2d");
        }
        throw new Error("No canvas factory available for graphics layers");
      };
      /** Embedded FontData families, keyed by the same document id used by TYPEFACE/CoreText. */
      this.embeddedFontFamilies = /* @__PURE__ */ new Map();
      // --- Typeface resolution ---
      /**
       * Called when a named family finishes loading, so a player that already painted a frame in the
       * fallback face can repaint it in the real one. Null for single-shot renderers, which instead
       * await `webFontsReady()` before painting the frame they keep.
       */
      this.onFontLoaded = null;
      this.mainCanvas = null;
      this.bitmapCanvasCache = /* @__PURE__ */ new Map();
      if (!canvas) {
        throw new Error("CanvasPaintContext: canvas rendering context is null or undefined");
      }
      this.ctx = canvas;
    }
    /** Release the WebGL context this paint context lazily created, if any. */
    destroy() {
      if (this.shaderRenderer) {
        this.shaderRenderer.destroy();
        this.shaderRenderer = null;
      }
      this.embeddedFontFamilies.forEach(({ data }, fontId) => {
        releaseEmbeddedFont(fontId, data, this.onFontLoaded ?? void 0);
      });
      this.embeddedFontFamilies.clear();
    }
    getCanvas() {
      return this.ctx;
    }
    // --- Text cache ---
    loadText(id, text) {
      this.textCache.set(id, text);
    }
    getText(id) {
      return this.textCache.get(id) ?? null;
    }
    loadFont(fontId, data) {
      const current = this.embeddedFontFamilies.get(fontId);
      if (current?.data === data) return;
      if (current) releaseEmbeddedFont(fontId, current.data, this.onFontLoaded ?? void 0);
      this.embeddedFontFamilies.set(fontId, {
        data,
        family: registerEmbeddedFont(fontId, data, this.onFontLoaded ?? void 0)
      });
    }
    /**
     * CSS stack for a `PaintBundle.TYPEFACE` operand.
     *
     * The operand is overloaded: below `START_ID` it is one of the four generic typeface constants,
     * at or above it the document's *text id* for a named family (`CoreText.updateVariables` ends
     * `else this.mType = this.mFontFamilyId`). The two ranges cannot collide — ids are handed out
     * from `START_ID` upward, so no text id is ever 0..3 — which is what makes this single integer
     * safe to disambiguate by magnitude.
     *
     * A `google:`-namespaced family is fetched; an unprefixed one is only *named*, leaving it to
     * whatever the host already has. Requesting the face is fire-and-forget: resolution has to be
     * synchronous because it happens mid-paint, so the stack names the family now and the face
     * arrives later (repainted via `onFontLoaded`, or awaited by a single-shot renderer). Until then
     * the fallback paints.
     */
    fontStackForTypeface(fontType) {
      if (fontType < RemoteComposeState.START_ID) return cssFontStackFor(fontType);
      const embedded = this.embeddedFontFamilies.get(fontType);
      if (embedded) {
        this.googleFamily = null;
        return namedFontStack(embedded.family);
      }
      const family = this.getText(fontType);
      if (!family) return cssFontStackFor(0);
      const { source, name } = parseFamily(family);
      if (!name) return cssFontStackFor(0);
      this.googleFamily = source === "google" ? name : null;
      if (source === "google") {
        ensureWebFont(
          name,
          this.fontWeight,
          this.fontItalic,
          this.onFontLoaded ?? void 0,
          this.fontAxes
        );
      }
      return namedFontStack(name);
    }
    // --- Bitmap cache ---
    loadBitmap(imageId, encoding, type, width, height, bitmap) {
      const blob = new Blob([bitmap.buffer], { type: "image/png" });
      const url = URL.createObjectURL(blob);
      const img = new Image();
      const promise = new Promise((resolve) => {
        img.onload = () => {
          URL.revokeObjectURL(url);
          this.bitmapCache.set(imageId, img);
          resolve();
        };
        img.onerror = (e) => {
          URL.revokeObjectURL(url);
          console.warn(`CanvasPaintContext: failed to load bitmap ${imageId}`, e);
          resolve();
        };
        img.src = url;
      });
      this.bitmapPromises.set(imageId, promise);
    }
    // --- Path cache ---
    loadPathData(id, winding, data) {
      this.pathDataCache.set(id, data);
      this.pathWindingCache.set(id, winding);
    }
    // A path operand is either a literal float or a NaN-encoded variable id (a
    // dynamic coordinate, e.g. a button/card fill sized from its component's
    // measured dimensions). Dereference the id to its current float value, the way
    // the command word and every other expression operand are resolved — reading
    // it as a literal `intBitsToFloat` would yield NaN and collapse the path.
    pathCoord(bits) {
      return isNaNBits(bits) ? this.mContext.getFloat(idFromBits(bits)) : intBitsToFloat2(bits);
    }
    buildPath2D(data, start = 0, end = 1) {
      const path = new Path2D();
      let i = 0;
      while (i < data.length) {
        const bits = data[i];
        let cmd = isNaNBits(bits) ? idFromBits(bits) : intBitsToFloat2(bits);
        if (cmd >= _CanvasPaintContext.NANMAP_PATH_BASE && cmd <= _CanvasPaintContext.NANMAP_PATH_BASE + 6) {
          cmd = _CanvasPaintContext.PATH_MOVE + (cmd - _CanvasPaintContext.NANMAP_PATH_BASE);
        }
        switch (cmd) {
          case _CanvasPaintContext.PATH_MOVE:
            i++;
            path.moveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]));
            i += 2;
            break;
          case _CanvasPaintContext.PATH_LINE:
            i += 3;
            path.lineTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]));
            i += 2;
            break;
          case _CanvasPaintContext.PATH_QUADRATIC:
            i += 3;
            path.quadraticCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]));
            i += 4;
            break;
          case _CanvasPaintContext.PATH_CONIC:
            i += 3;
            path.quadraticCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]));
            i += 5;
            break;
          case _CanvasPaintContext.PATH_CUBIC:
            i += 3;
            path.bezierCurveTo(this.pathCoord(data[i]), this.pathCoord(data[i + 1]), this.pathCoord(data[i + 2]), this.pathCoord(data[i + 3]), this.pathCoord(data[i + 4]), this.pathCoord(data[i + 5]));
            i += 6;
            break;
          case _CanvasPaintContext.PATH_CLOSE:
            path.closePath();
            i++;
            break;
          case _CanvasPaintContext.PATH_DONE:
            return path;
          default:
            i++;
            break;
        }
      }
      return path;
    }
    // --- Apply paint ---
    getEffectiveColor() {
      if (this.colorFilterColor === null) {
        return this.gradientStyle ?? this.color;
      }
      switch (this.colorFilterMode) {
        case 0:
          return "rgba(0,0,0,0)";
        // CLEAR: transparent
        case 1:
          return this.colorFilterColor;
        // SRC: filter color
        case 2:
          return this.gradientStyle ?? this.color;
        // DST: paint color
        case 3:
          return this.colorFilterColor;
        // SRC_OVER: opaque filter wins
        case 4:
          return this.gradientStyle ?? this.color;
        // DST_OVER: opaque paint wins
        case 5:
          return this.colorFilterColor;
        // SRC_IN: filter × paint_alpha = filter
        case 6:
          return this.gradientStyle ?? this.color;
        // DST_IN: paint × filter_alpha = paint
        case 7:
          return "rgba(0,0,0,0)";
        // SRC_OUT: filter × (1-paint_alpha) = 0
        case 8:
          return "rgba(0,0,0,0)";
        // DST_OUT: paint × (1-filter_alpha) = 0
        case 9:
          return this.colorFilterColor;
        // SRC_ATOP: filter
        case 10:
          return this.gradientStyle ?? this.color;
        // DST_ATOP: paint
        case 11:
          return "rgba(0,0,0,0)";
        // XOR: both opaque → transparent
        default: {
          return this.computeColorFilterBlend();
        }
      }
    }
    computeColorFilterBlend() {
      const sA = (this.colorFilterArgb >>> 24 & 255) / 255;
      const sR = this.colorFilterArgb >>> 16 & 255;
      const sG = this.colorFilterArgb >>> 8 & 255;
      const sB = this.colorFilterArgb & 255;
      const m = this.color.match(/rgba?\((\d+),(\d+),(\d+),?([^)]*)\)/);
      const dR = m ? parseInt(m[1]) : 0;
      const dG = m ? parseInt(m[2]) : 0;
      const dB = m ? parseInt(m[3]) : 0;
      const dA = m && m[4] ? parseFloat(m[4]) : 1;
      let rR, rG, rB, rA;
      const blend1 = (a, b) => {
        switch (this.colorFilterMode) {
          case 12:
            return Math.min(255, a + b);
          // PLUS
          case 13:
            return a * b / 255;
          // MODULATE
          case 14:
            return a + b - a * b / 255;
          // SCREEN
          case 15:
            return b <= 127 ? 2 * a * b / 255 : 255 - 2 * (255 - a) * (255 - b) / 255;
          case 16:
            return Math.min(a, b);
          // DARKEN
          case 17:
            return Math.max(a, b);
          // LIGHTEN
          case 18:
            return b === 0 ? 0 : Math.min(255, a * 255 / (255 - b));
          // COLOR_DODGE
          case 19:
            return b === 255 ? 255 : Math.max(0, 255 - (255 - a) * 255 / b);
          // COLOR_BURN
          case 20:
            return a <= 127 ? 2 * a * b / 255 : 255 - 2 * (255 - a) * (255 - b) / 255;
          case 21: {
            const t = a / 255;
            return b <= 127 ? b - (1 - 2 * t) * b * (1 - b / 255) * 255 : b + (2 * t - 1) * (Math.sqrt(b / 255) * 255 - b);
          }
          case 22:
            return Math.abs(a - b);
          // DIFFERENCE
          case 23:
            return a + b - 2 * a * b / 255;
          // EXCLUSION
          case 24:
            return a * b / 255;
          // MULTIPLY
          default:
            return a;
        }
      };
      rR = blend1(sR, dR);
      rG = blend1(sG, dG);
      rB = blend1(sB, dB);
      rA = Math.min(1, sA + dA - sA * dA);
      return `rgba(${Math.round(rR)},${Math.round(rG)},${Math.round(rB)},${rA.toFixed(3)})`;
    }
    createSweepGradientPattern(cx, cy, colors, stops) {
      const ctx = this.mContext;
      const w = ctx ? ctx.mWidth || 256 : 256;
      const h = ctx ? ctx.mHeight || 256 : 256;
      try {
        const layerCtx = this.createLayerCanvas(w, h);
        const imgData = layerCtx.createImageData(w, h);
        const data = imgData.data;
        const colorComponents = colors.map((argb) => [
          argb >>> 16 & 255,
          // R
          argb >>> 8 & 255,
          // G
          argb & 255,
          // B
          (argb >>> 24 & 255) / 255
          // A
        ]);
        for (let y = 0; y < h; y++) {
          for (let x = 0; x < w; x++) {
            let angle = Math.atan2(y - cy, x - cx);
            if (angle < 0) angle += Math.PI * 2;
            const t = angle / (Math.PI * 2);
            let idx = 0;
            for (let s = 0; s < stops.length - 1; s++) {
              if (t >= stops[s]) idx = s;
            }
            const t0 = stops[idx];
            const t1 = stops[Math.min(idx + 1, stops.length - 1)];
            const range = t1 - t0;
            const frac = range > 0 ? (t - t0) / range : 0;
            const c0 = colorComponents[idx];
            const c1 = colorComponents[Math.min(idx + 1, colorComponents.length - 1)];
            const off = (y * w + x) * 4;
            data[off] = c0[0] + (c1[0] - c0[0]) * frac;
            data[off + 1] = c0[1] + (c1[1] - c0[1]) * frac;
            data[off + 2] = c0[2] + (c1[2] - c0[2]) * frac;
            data[off + 3] = (c0[3] + (c1[3] - c0[3]) * frac) * 255;
          }
        }
        layerCtx.putImageData(imgData, 0, 0);
        return this.ctx.createPattern(layerCtx.canvas, "no-repeat");
      } catch (_e) {
        return null;
      }
    }
    applyFillStyle() {
      this.ctx.fillStyle = this.getEffectiveColor();
      this.ctx.globalAlpha = this.alpha;
      this.ctx.globalCompositeOperation = this.blendMode;
    }
    applyStrokeStyle() {
      this.ctx.strokeStyle = this.getEffectiveColor();
      this.ctx.lineWidth = this.strokeWidth;
      this.ctx.lineCap = this.lineCap;
      this.ctx.lineJoin = this.lineJoin;
      this.ctx.miterLimit = this.miterLimit;
      this.ctx.globalAlpha = this.alpha;
      this.ctx.globalCompositeOperation = this.blendMode;
    }
    fillOrStroke(doFill, doStroke) {
      if (this.style === 0 || this.style === 2) {
        this.applyFillStyle();
        doFill();
      }
      if (this.style === 1 || this.style === 2) {
        this.applyStrokeStyle();
        doStroke();
      }
    }
    /**
     * The axis name a [tag] int stands for, in either encoding the format uses.
     *
     * A `CoreText` style interns its axis names in the text table like any other string and puts the
     * *text id* in the array; the paint bundle's own `setTextAxis` carries the **raw OpenType tag**
     * packed into four bytes (`0x77676874` = `wght`). Reading the text table first (ids start at
     * `START_ID`, so the two ranges can't collide) and unpacking the bytes otherwise covers both
     * without having to know which writer produced the document. Anything that is neither is dropped
     * rather than guessed at.
     */
    axisName(tag) {
      if (tag >= RemoteComposeState.START_ID) {
        const name = this.getText(tag);
        if (name) return name;
      }
      let packed = "";
      for (let shift = 24; shift >= 0; shift -= 8) {
        const code = tag >> shift & 255;
        if (code < 33 || code > 126) return null;
        packed += String.fromCharCode(code);
      }
      return packed;
    }
    axisValue(tag) {
      const axis = this.fontAxes.find((a) => a.tag === tag);
      return axis ? axis.value : null;
    }
    setFont() {
      const wght = this.axisValue("wght");
      const ital = this.axisValue("ital");
      const italic = this.fontItalic || ital !== null && ital >= 0.5;
      const effectiveWeight = wght !== null ? Math.round(wght) : this.fontWeight;
      const style = italic ? "italic " : "";
      const weight = effectiveWeight !== 400 ? `${effectiveWeight} ` : "";
      this.ctx.font = `${style}${weight}${this.textSize}px ${this.fontFamily}`;
      const stretchable = this.ctx;
      if ("fontStretch" in stretchable) {
        const wdth = this.axisValue("wdth");
        stretchable.fontStretch = wdth !== null ? nearestFontStretch(wdth) : "normal";
      }
    }
    // --- PaintContext abstract methods ---
    applyPaint(paintData) {
      const arr = paintData.getArray();
      const len = paintData.getLength();
      let i = 0;
      while (i < len) {
        const cmd = arr[i++];
        const tag = cmd & 65535;
        const upper = cmd >> 16 & 65535;
        switch (tag) {
          case PaintBundle.TEXT_SIZE:
            this.textSize = intBitsToFloat2(arr[i++]);
            this.setFont();
            break;
          case PaintBundle.COLOR:
            this.color = argbToRgba(arr[i++]);
            this.gradientStyle = null;
            break;
          case PaintBundle.STROKE_WIDTH:
            this.strokeWidth = intBitsToFloat2(arr[i++]);
            break;
          case PaintBundle.STROKE_MITER:
            this.miterLimit = intBitsToFloat2(arr[i++]);
            break;
          case PaintBundle.STROKE_CAP:
            this.lineCap = upper === 0 ? "butt" : upper === 1 ? "round" : "square";
            break;
          case PaintBundle.STYLE:
            this.style = upper;
            break;
          case PaintBundle.SHADER: {
            const shaderId = arr[i++];
            if (shaderId === 0) {
              this.activeShaderData = null;
            } else {
              const sd = this.getContext()?.getShader(shaderId);
              this.activeShaderData = sd ?? null;
            }
            break;
          }
          case PaintBundle.IMAGE_FILTER_QUALITY:
            break;
          case PaintBundle.GRADIENT: {
            const meta = arr[i++];
            const numColors = meta & 255;
            const colors = [];
            for (let c = 0; c < numColors; c++) {
              colors.push(arr[i++]);
            }
            const numStops = arr[i++];
            const stops = [];
            if (numStops > 0) {
              for (let s = 0; s < numStops; s++) {
                stops.push(intBitsToFloat2(arr[i++]));
              }
            } else {
              for (let s = 0; s < numColors; s++) {
                stops.push(numColors > 1 ? s / (numColors - 1) : 0);
              }
            }
            let gradient = null;
            if (upper === PaintBundle.SWEEP_GRADIENT) {
              const cx = intBitsToFloat2(arr[i++]);
              const cy = intBitsToFloat2(arr[i++]);
              if (typeof this.ctx.createConicGradient === "function") {
                gradient = this.ctx.createConicGradient(0, cx, cy);
              } else {
                const sweepPattern = this.createSweepGradientPattern(cx, cy, colors, stops);
                if (sweepPattern) {
                  this.gradientStyle = sweepPattern;
                }
              }
            } else if (upper === PaintBundle.RADIAL_GRADIENT) {
              const cx = intBitsToFloat2(arr[i++]);
              const cy = intBitsToFloat2(arr[i++]);
              const radius = intBitsToFloat2(arr[i++]);
              i++;
              gradient = this.ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
            } else {
              const startX = intBitsToFloat2(arr[i++]);
              const startY = intBitsToFloat2(arr[i++]);
              const endX = intBitsToFloat2(arr[i++]);
              const endY = intBitsToFloat2(arr[i++]);
              i++;
              gradient = this.ctx.createLinearGradient(startX, startY, endX, endY);
            }
            if (gradient) {
              for (let s = 0; s < stops.length && s < colors.length; s++) {
                gradient.addColorStop(
                  Math.max(0, Math.min(1, stops[s])),
                  argbToRgba(colors[s])
                );
              }
              this.gradientStyle = gradient;
            }
            break;
          }
          case PaintBundle.ALPHA:
            this.alpha = intBitsToFloat2(arr[i++]);
            break;
          case PaintBundle.COLOR_FILTER: {
            const cfArgb = arr[i++];
            this.colorFilterArgb = cfArgb;
            this.colorFilterColor = argbToRgba(cfArgb);
            this.colorFilterMode = upper;
            break;
          }
          case PaintBundle.ANTI_ALIAS:
            this.antiAlias = upper !== 0;
            break;
          case PaintBundle.STROKE_JOIN:
            this.lineJoin = upper === 0 ? "miter" : upper === 1 ? "round" : "bevel";
            break;
          case PaintBundle.TYPEFACE: {
            const weight = upper & 1023;
            const italic = (upper & 2048) !== 0;
            this.fontWeight = weight > 0 ? weight : 400;
            this.fontItalic = italic;
            const fontType = arr[i++];
            this.fontFamily = this.fontStackForTypeface(fontType);
            this.setFont();
            break;
          }
          case PaintBundle.FILTER_BITMAP:
            this.filterBitmap = upper !== 0;
            this.ctx.imageSmoothingEnabled = this.filterBitmap;
            break;
          case PaintBundle.BLEND_MODE:
            this.blendMode = this.mapBlendMode(upper);
            break;
          case PaintBundle.COLOR_ID: {
            this.color = argbToRgba(arr[i++]);
            this.gradientStyle = null;
            break;
          }
          case PaintBundle.COLOR_FILTER_ID: {
            const cfColor = arr[i++];
            this.colorFilterColor = argbToRgba(cfColor);
            this.colorFilterArgb = cfColor;
            break;
          }
          case PaintBundle.CLEAR_COLOR_FILTER:
            this.colorFilterColor = null;
            break;
          case PaintBundle.SHADER_MATRIX:
            i++;
            break;
          case PaintBundle.FONT_AXIS: {
            const axisCount = upper;
            const axes = [];
            for (let k = 0; k < axisCount; k++) {
              const tag2 = arr[i++];
              const value = intBitsToFloat2(arr[i++]);
              const name = this.axisName(tag2);
              if (name) axes.push({ tag: name, value });
            }
            this.fontAxes = axes;
            if (this.googleFamily && axes.length > 0) {
              ensureWebFont(
                this.googleFamily,
                this.fontWeight,
                this.fontItalic,
                this.onFontLoaded ?? void 0,
                axes
              );
            }
            this.setFont();
            break;
          }
          case PaintBundle.TEXTURE: {
            const bitmapId = arr[i++];
            const tileModes = arr[i++];
            i++;
            const tileX = tileModes & 15;
            const tileY = tileModes >> 16 & 15;
            const img = this.bitmapCache.get(bitmapId);
            if (img) {
              const xRepeat = tileX === 1 || tileX === 2;
              const yRepeat = tileY === 1 || tileY === 2;
              let repetition;
              if (xRepeat && yRepeat) repetition = "repeat";
              else if (xRepeat) repetition = "repeat-x";
              else if (yRepeat) repetition = "repeat-y";
              else repetition = "no-repeat";
              const pattern = this.ctx.createPattern(img, repetition);
              if (pattern) {
                this.gradientStyle = pattern;
              }
            }
            break;
          }
          case PaintBundle.PATH_EFFECT: {
            const count = upper;
            if (count === 0) {
              this.ctx.setLineDash([]);
              this.ctx.lineDashOffset = 0;
            } else {
              const intervals = [];
              for (let k = 0; k < count; k++) {
                intervals.push(intBitsToFloat2(arr[i++]));
              }
              this.ctx.setLineDash(intervals);
            }
            break;
          }
          case PaintBundle.FALLBACK_TYPEFACE:
            i++;
            break;
          default:
            if (tag === 0) break;
            console.warn(`PaintBundle: unknown tag ${tag} at index ${i - 1}`);
            return;
        }
      }
    }
    replacePaint(paintBundle) {
      this.resetPaintState();
      this.applyPaint(paintBundle);
    }
    resetPaintState() {
      this.color = "rgba(0,0,0,1)";
      this.style = 0;
      this.strokeWidth = 1;
      this.textSize = 14;
      this.alpha = 1;
      this.lineCap = "butt";
      this.lineJoin = "miter";
      this.miterLimit = 10;
      this.blendMode = "source-over";
      this.antiAlias = true;
      this.filterBitmap = true;
      this.letterSpacing = 0;
      this.gradientStyle = null;
      this.fontFamily = cssFontStackFor(0);
      this.fontWeight = 400;
      this.fontItalic = false;
      this.fontAxes = [];
      this.googleFamily = null;
      this.colorFilterColor = null;
      this.colorFilterArgb = 0;
      this.colorFilterMode = 3;
      this.activeShaderData = null;
      this.ctx.setLineDash([]);
      this.ctx.lineDashOffset = 0;
      this.setFont();
    }
    mapBlendMode(mode) {
      switch (mode) {
        case 0:
          return "clear";
        // CLEAR
        case 1:
          return "copy";
        // SRC
        case 2:
          return "destination";
        // DST (keep destination only)
        case 3:
          return "source-over";
        // SRC_OVER (default)
        case 4:
          return "destination-over";
        // DST_OVER
        case 5:
          return "source-in";
        // SRC_IN
        case 6:
          return "destination-in";
        // DST_IN
        case 7:
          return "source-out";
        // SRC_OUT
        case 8:
          return "destination-out";
        // DST_OUT
        case 9:
          return "source-atop";
        // SRC_ATOP
        case 10:
          return "destination-atop";
        // DST_ATOP
        case 11:
          return "xor";
        // XOR
        case 12:
          return "lighter";
        // PLUS/ADD
        case 13:
          return "multiply";
        // MODULATE (closest to multiply)
        case 14:
          return "screen";
        // SCREEN
        case 15:
          return "overlay";
        // OVERLAY
        case 16:
          return "darken";
        // DARKEN
        case 17:
          return "lighten";
        // LIGHTEN
        case 18:
          return "color-dodge";
        // COLOR_DODGE
        case 19:
          return "color-burn";
        // COLOR_BURN
        case 20:
          return "hard-light";
        // HARD_LIGHT
        case 21:
          return "soft-light";
        // SOFT_LIGHT
        case 22:
          return "difference";
        // DIFFERENCE
        case 23:
          return "exclusion";
        // EXCLUSION
        case 24:
          return "multiply";
        // MULTIPLY
        case 25:
          return "hue";
        // HUE
        case 26:
          return "saturation";
        // SATURATION
        case 27:
          return "color";
        // COLOR
        case 28:
          return "luminosity";
        // LUMINOSITY
        case 30:
          return "lighter";
        // PORTER_MODE_ADD
        default:
          return "source-over";
      }
    }
    savePaint() {
      this.paintStack.push({
        color: this.color,
        style: this.style,
        strokeWidth: this.strokeWidth,
        textSize: this.textSize,
        alpha: this.alpha,
        lineCap: this.lineCap,
        lineJoin: this.lineJoin,
        miterLimit: this.miterLimit,
        blendMode: this.blendMode,
        antiAlias: this.antiAlias,
        letterSpacing: this.letterSpacing,
        gradientStyle: this.gradientStyle,
        activeShaderData: this.activeShaderData,
        fontFamily: this.fontFamily,
        fontWeight: this.fontWeight,
        fontItalic: this.fontItalic,
        fontAxes: this.fontAxes,
        googleFamily: this.googleFamily,
        filterBitmap: this.filterBitmap,
        colorFilterColor: this.colorFilterColor,
        colorFilterArgb: this.colorFilterArgb,
        colorFilterMode: this.colorFilterMode
      });
    }
    restorePaint() {
      const state = this.paintStack.pop();
      if (state) {
        Object.assign(this, state);
        this.setFont();
      }
    }
    /**
     * If an active shader is set, render it via WebGL2 into the given
     * rectangle and return true. Otherwise return false (caller does
     * the normal fill).
     */
    tryRenderShader(left, top, right, bottom) {
      const ctx = this.getContext();
      if (!this.activeShaderData || !ctx) return false;
      const sd = this.activeShaderData;
      const textId = sd.getShaderTextId();
      const shaderText = this.textCache.get(textId);
      if (!shaderText) return false;
      const cacheKey = String(textId);
      let glsl = this.glslCache.get(textId);
      if (!glsl) {
        try {
          const result = transpileAgslToGlsl(shaderText);
          glsl = result.glsl;
          this.glslCache.set(textId, glsl);
        } catch (e) {
          console.error(`[shader] transpile failed (textId=${textId}):`, e, "\nAGSL:", shaderText);
          return false;
        }
      }
      if (!this.shaderRenderer) {
        this.shaderRenderer = new WebGLShaderRenderer();
        if (!this.shaderRenderer.isAvailable()) {
          this.shaderRenderer = null;
          return false;
        }
      }
      let w = Math.round(right - left);
      let h = Math.round(top > bottom ? top - bottom : bottom - top);
      if (w <= 0 || h <= 0) return false;
      const maxW = this.ctx.canvas.width || 4096;
      const maxH = this.ctx.canvas.height || 4096;
      if (w > maxW) w = maxW;
      if (h > maxH) h = maxH;
      sd.updateVariables(ctx);
      const floats = /* @__PURE__ */ new Map();
      for (const name of sd.getUniformFloatNames()) {
        const vals = sd.getUniformFloats(name);
        floats.set(name, vals);
      }
      const ints = /* @__PURE__ */ new Map();
      for (const name of sd.getUniformIntegerNames()) {
        ints.set(name, sd.getUniformInts(name));
      }
      let textures;
      const bitmapNames = sd.getUniformBitmapNames();
      if (bitmapNames.length > 0) {
        textures = /* @__PURE__ */ new Map();
        for (const name of bitmapNames) {
          const bmpId = sd.getUniformBitmapId(name);
          if (bmpId < 0) continue;
          const offCtx = this.bitmapCanvasCache.get(bmpId);
          if (offCtx) {
            textures.set(name, offCtx.canvas);
          } else {
            const img = this.bitmapCache.get(bmpId);
            if (img) textures.set(name, img);
          }
        }
      }
      const docW = Math.round(right - left);
      const docH = Math.round(Math.abs(bottom - top));
      const ok = this.shaderRenderer.render(
        glsl,
        w,
        h,
        floats,
        ints,
        textures,
        cacheKey,
        docW,
        docH
      );
      if (!ok) {
        console.error(`[shader] WebGL render FAILED textId=${textId}`);
        return false;
      }
      this.ctx.globalAlpha = this.alpha;
      this.ctx.globalCompositeOperation = this.blendMode;
      const dstW = right - left;
      const dstH = Math.abs(bottom - top);
      this.ctx.drawImage(
        this.shaderRenderer.getCanvas(),
        left,
        Math.min(top, bottom),
        dstW,
        dstH
      );
      return true;
    }
    // --- Drawing ---
    drawRect(left, top, right, bottom) {
      if (this.activeShaderData && this.tryRenderShader(left, top, right, bottom)) {
        return;
      }
      this.fillOrStroke(
        () => {
          this.ctx.fillRect(left, top, right - left, bottom - top);
        },
        () => {
          this.ctx.strokeRect(left, top, right - left, bottom - top);
        }
      );
    }
    drawCircle(centerX, centerY, radius) {
      if (radius <= 0) return;
      this.ctx.beginPath();
      this.ctx.arc(centerX, centerY, radius, 0, Math.PI * 2);
      this.fillOrStroke(
        () => {
          this.ctx.fill();
        },
        () => {
          this.ctx.stroke();
        }
      );
    }
    drawLine(x1, y1, x2, y2) {
      this.applyStrokeStyle();
      this.ctx.beginPath();
      this.ctx.moveTo(x1, y1);
      this.ctx.lineTo(x2, y2);
      this.ctx.stroke();
    }
    drawOval(left, top, right, bottom) {
      const cx = (left + right) / 2;
      const cy = (top + bottom) / 2;
      const rx = (right - left) / 2;
      const ry = (bottom - top) / 2;
      this.ctx.beginPath();
      this.ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
      this.fillOrStroke(
        () => {
          this.ctx.fill();
        },
        () => {
          this.ctx.stroke();
        }
      );
    }
    drawRoundRect(left, top, right, bottom, rx, ry) {
      const w = right - left;
      const h = bottom - top;
      this.ctx.beginPath();
      this.ctx.roundRect(left, top, w, h, [Math.min(rx, ry)]);
      this.fillOrStroke(
        () => {
          this.ctx.fill();
        },
        () => {
          this.ctx.stroke();
        }
      );
    }
    drawArc(left, top, right, bottom, startAngle, sweepAngle) {
      const cx = (left + right) / 2;
      const cy = (top + bottom) / 2;
      const rx = (right - left) / 2;
      const ry = (bottom - top) / 2;
      const start = startAngle * Math.PI / 180;
      const end = (startAngle + sweepAngle) * Math.PI / 180;
      this.ctx.beginPath();
      this.ctx.ellipse(cx, cy, rx, ry, 0, start, end, sweepAngle < 0);
      this.fillOrStroke(
        () => {
          this.ctx.fill();
        },
        () => {
          this.ctx.stroke();
        }
      );
    }
    drawSector(left, top, right, bottom, startAngle, sweepAngle) {
      const cx = (left + right) / 2;
      const cy = (top + bottom) / 2;
      const rx = (right - left) / 2;
      const ry = (bottom - top) / 2;
      const start = startAngle * Math.PI / 180;
      const end = (startAngle + sweepAngle) * Math.PI / 180;
      this.ctx.beginPath();
      this.ctx.moveTo(cx, cy);
      this.ctx.ellipse(cx, cy, rx, ry, 0, start, end, sweepAngle < 0);
      this.ctx.closePath();
      this.fillOrStroke(
        () => {
          this.ctx.fill();
        },
        () => {
          this.ctx.stroke();
        }
      );
    }
    drawPath(id, start, end) {
      const data = this.pathDataCache.get(id);
      if (!data) return;
      const path = this.buildPath2D(data, start, end);
      this.fillOrStroke(
        () => {
          this.ctx.fill(path);
        },
        () => {
          this.ctx.stroke(path);
        }
      );
    }
    drawTextRun(textId, start, end, _contextStart, _contextEnd, x, y, _rtl) {
      const text = this.textCache.get(textId);
      if (!text) return;
      const s = start >= 0 ? start : 0;
      const e = end >= 0 ? Math.min(end, text.length) : text.length;
      const substr = text.substring(s, e);
      this.setFont();
      this.fillOrStroke(
        () => {
          this.applyFillStyle();
          this.ctx.fillText(substr, x, y);
        },
        () => {
          this.applyStrokeStyle();
          this.ctx.strokeText(substr, x, y);
        }
      );
    }
    drawTextOnPath(textId, pathId, hOffset, vOffset) {
      const text = this.textCache.get(textId);
      if (!text) return;
      const data = this.pathDataCache.get(pathId);
      if (!data) return;
      const { segments, totalLen } = this.collectPathSegments(data);
      if (totalLen === 0 || segments.length === 0) return;
      this.setFont();
      this.ctx.textBaseline = "alphabetic";
      let progress = hOffset;
      for (let ci = 0; ci < text.length; ci++) {
        const ch = text[ci];
        const charWidth = this.ctx.measureText(ch).width;
        const charCenter = progress + charWidth / 2;
        if (charCenter >= 0 && charCenter <= totalLen) {
          this.ctx.save();
          this.positionOnPath(segments, charCenter, vOffset);
          this.fillOrStroke(
            () => {
              this.applyFillStyle();
              this.ctx.fillText(ch, -charWidth / 2, 0);
            },
            () => {
              this.applyStrokeStyle();
              this.ctx.strokeText(ch, -charWidth / 2, 0);
            }
          );
          this.ctx.restore();
        }
        progress += charWidth;
      }
    }
    getTextBounds(textId, start, end, flags, bounds) {
      const text = this.textCache.get(textId);
      if (!text) {
        bounds.fill(0);
        return;
      }
      const s = start >= 0 ? start : 0;
      const e = end >= 0 ? Math.min(end, text.length) : text.length;
      const substr = text.substring(s, e);
      this.setFont();
      const metrics = this.ctx.measureText(substr);
      const size = this.textSize > 0 ? this.textSize : DEFAULT_TEXT_SIZE;
      const wantFontBox = (flags & 2) !== 0;
      const pick = (...vals) => {
        for (const v of vals) if (typeof v === "number" && isFinite(v) && v > 0) return v;
        return 0;
      };
      let ascent = wantFontBox ? pick(metrics.fontBoundingBoxAscent, metrics.actualBoundingBoxAscent) : pick(metrics.actualBoundingBoxAscent, metrics.fontBoundingBoxAscent);
      let descent = wantFontBox ? pick(metrics.fontBoundingBoxDescent, metrics.actualBoundingBoxDescent) : pick(metrics.actualBoundingBoxDescent, metrics.fontBoundingBoxDescent);
      if (ascent <= 0 && descent <= 0) {
        ascent = ASCENT_RATIO * size;
        descent = DESCENT_RATIO * size;
      }
      const advance = pick(metrics.width, substr.length * AVG_ADVANCE * size);
      const actualLeft = metrics.actualBoundingBoxLeft;
      const actualRight = metrics.actualBoundingBoxRight;
      let left = typeof actualLeft === "number" && isFinite(actualLeft) ? -actualLeft : 0;
      let right = typeof actualRight === "number" && isFinite(actualRight) ? actualRight : advance;
      if ((flags & 4) !== 0) {
        left = 0;
        right = advance;
      } else if ((flags & 1) !== 0) {
        right = advance - left;
      }
      bounds[0] = left;
      bounds[1] = -ascent;
      bounds[2] = right;
      bounds[3] = descent;
    }
    layoutComplexText(textId, start, end, alignment, overflow, maxLines, maxWidth, maxHeight, _letterSpacing, _lineHeightAdd, lineHeightMultiplier, _lineBreakStrategy, _hyphenationFrequency, _justificationMode, _useUnderline, _strikethrough, _flags) {
      const str = this.textCache.get(textId);
      if (!str) return null;
      const s = start >= 0 ? start : 0;
      const e = end === -1 || end > str.length ? str.length : end;
      const text = str.substring(s, e);
      this.setFont();
      const size = this.textSize > 0 ? this.textSize : DEFAULT_TEXT_SIZE;
      const lineHeight = size * (lineHeightMultiplier || 1.2);
      if (maxLines === 1 && (overflow === 1 || overflow === 2)) {
        const firstLine = text.split("\n", 1)[0];
        return {
          lines: [firstLine],
          alignment,
          lineHeight,
          width: Math.min(this.ctx.measureText(firstLine).width, maxWidth),
          height: Math.min(lineHeight, maxHeight),
          naturalHeight: lineHeight,
          visibleLines: 1
        };
      }
      const lines = [];
      const paragraphs = text.split("\n");
      for (let pi = 0; pi < paragraphs.length; pi++) {
        const para = paragraphs[pi];
        const words = para.split(/(\s+)/);
        let currentLine = "";
        for (const word of words) {
          const testLine = currentLine + word;
          const metrics = this.ctx.measureText(testLine);
          if (metrics.width > maxWidth && currentLine.length > 0) {
            lines.push(currentLine);
            currentLine = word.trimStart();
          } else {
            currentLine = testLine;
          }
        }
        if (currentLine.length > 0) {
          lines.push(currentLine);
        } else if (para.length === 0) {
          lines.push("");
        }
      }
      const graphemes = (value) => {
        const Segmenter = Intl.Segmenter;
        if (Segmenter) {
          return Array.from(
            new Segmenter(void 0, { granularity: "grapheme" }).segment(value),
            (part) => part.segment
          );
        }
        return Array.from(value);
      };
      const ellipsizeEnd = (value) => {
        const parts = graphemes(value);
        while (this.ctx.measureText(parts.join("") + "\u2026").width > maxWidth && parts.length > 0) {
          parts.pop();
        }
        return parts.join("") + "\u2026";
      };
      const ellipsizeStart = (value) => {
        const parts = graphemes(value);
        while (this.ctx.measureText("\u2026" + parts.join("")).width > maxWidth && parts.length > 0) {
          parts.shift();
        }
        return "\u2026" + parts.join("");
      };
      const ellipsizeMiddle = (value) => {
        const parts = graphemes(value);
        let left = Math.ceil(parts.length / 2);
        let right = left;
        while (this.ctx.measureText(parts.slice(0, left).join("") + "\u2026" + parts.slice(right).join("")).width > maxWidth && (left > 0 || right < parts.length)) {
          if (parts.length - right < left) left--;
          else right++;
        }
        return parts.slice(0, left).join("") + "\u2026" + parts.slice(right).join("");
      };
      const retainedLast = maxLines > 0 ? lines[Math.min(lines.length, maxLines) - 1] : void 0;
      const horizontalOverflow = retainedLast !== void 0 && this.ctx.measureText(retainedLast).width > maxWidth;
      if (overflow >= 3 && overflow <= 5 && maxLines > 0 && (lines.length > maxLines || horizontalOverflow)) {
        const lastIdx = Math.min(maxLines, lines.length) - 1;
        const value = lines[lastIdx] || "";
        lines[lastIdx] = overflow === 4 ? ellipsizeStart(value) : overflow === 5 ? ellipsizeMiddle(value) : ellipsizeEnd(value);
        if (lines.length > maxLines) lines.length = maxLines;
      }
      let totalWidth = 0;
      for (const line of lines) {
        const w = this.ctx.measureText(line).width;
        if (w > totalWidth) totalWidth = w;
      }
      const totalHeight = lines.length * lineHeight;
      return {
        lines,
        alignment,
        lineHeight,
        width: Math.min(totalWidth, maxWidth),
        height: Math.min(totalHeight, maxHeight),
        naturalHeight: totalHeight,
        visibleLines: lines.length
      };
    }
    drawComplexText(computedTextLayout) {
      if (!computedTextLayout) return;
      const { lines, alignment, lineHeight, width } = computedTextLayout;
      this.setFont();
      this.ctx.textBaseline = "top";
      for (let i = 0; i < lines.length; i++) {
        let x = 0;
        if (alignment === 2 || alignment === 6) {
          x = width - this.ctx.measureText(lines[i]).width;
        } else if (alignment === 3) {
          x = (width - this.ctx.measureText(lines[i]).width) / 2;
        }
        this.fillOrStroke(
          () => {
            this.applyFillStyle();
            this.ctx.fillText(lines[i], x, i * lineHeight);
          },
          () => {
            this.applyStrokeStyle();
            this.ctx.strokeText(lines[i], x, i * lineHeight);
          }
        );
      }
    }
    drawBitmap(imageId, srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom, _cdId) {
      const img = this.bitmapCache.get(imageId);
      if (!img) return;
      this.ctx.globalAlpha = this.alpha;
      this.ctx.globalCompositeOperation = this.blendMode;
      this.ctx.imageSmoothingEnabled = this.filterBitmap;
      const sw = srcRight - srcLeft;
      const sh = srcBottom - srcTop;
      const dw = dstRight - dstLeft;
      const dh = dstBottom - dstTop;
      if (sw > 0 && sh > 0) {
        this.ctx.drawImage(img, srcLeft, srcTop, sw, sh, dstLeft, dstTop, dw, dh);
      } else {
        this.ctx.drawImage(img, dstLeft, dstTop, dw, dh);
      }
    }
    drawBitmapSimple(id, left, top, right, bottom) {
      const img = this.bitmapCache.get(id);
      if (!img) return;
      this.ctx.globalAlpha = this.alpha;
      this.ctx.globalCompositeOperation = this.blendMode;
      this.ctx.imageSmoothingEnabled = this.filterBitmap;
      this.ctx.drawImage(img, left, top, right - left, bottom - top);
    }
    drawTweenPath(path1Id, path2Id, tween, start, end) {
      const data1 = this.pathDataCache.get(path1Id);
      const data2 = this.pathDataCache.get(path2Id);
      if (!data1 || !data2) {
        if (data1) this.drawPath(path1Id, start, end);
        return;
      }
      const len = Math.min(data1.length, data2.length);
      const tweened = new Int32Array(len);
      for (let i = 0; i < len; i++) {
        const b1 = data1[i];
        if (isNaNBits(b1)) {
          tweened[i] = b1;
        } else {
          const f1 = intBitsToFloat2(b1);
          const f2 = intBitsToFloat2(data2[i]);
          tweened[i] = floatToRawIntBits(f1 + (f2 - f1) * tween);
        }
      }
      const path = this.buildPath2D(tweened, start, end);
      this.fillOrStroke(
        () => {
          this.ctx.fill(path);
        },
        () => {
          this.ctx.stroke(path);
        }
      );
    }
    tweenPath(out, path1, path2, tween) {
      if (tween === 0) {
        const data = this.pathDataCache.get(path1);
        if (data) this.pathDataCache.set(out, Int32Array.from(data));
        return;
      }
      if (tween === 1) {
        const data = this.pathDataCache.get(path2);
        if (data) this.pathDataCache.set(out, Int32Array.from(data));
        return;
      }
      const data1 = this.pathDataCache.get(path1);
      const data2 = this.pathDataCache.get(path2);
      if (!data1 || !data2) return;
      const len = Math.min(data1.length, data2.length);
      const result = new Int32Array(len);
      for (let i = 0; i < len; i++) {
        if (isNaNBits(data1[i]) || isNaNBits(data2[i])) {
          result[i] = data1[i];
        } else {
          const f1 = intBitsToFloat2(data1[i]);
          const f2 = intBitsToFloat2(data2[i]);
          result[i] = floatToRawIntBits((f2 - f1) * tween + f1);
        }
      }
      this.pathDataCache.set(out, result);
    }
    combinePath(_out, _path1, _path2, _operation) {
    }
    // --- Matrix / transforms ---
    scale(scaleX, scaleY) {
      const eps = 1e-10;
      if (scaleX === 0) scaleX = eps;
      if (scaleY === 0) scaleY = eps;
      this.ctx.scale(scaleX, scaleY);
    }
    translate(translateX, translateY) {
      this.ctx.translate(translateX, translateY);
    }
    matrixSave() {
      this.ctx.save();
    }
    matrixRestore() {
      this.ctx.restore();
    }
    matrixTranslate(tx, ty) {
      this.ctx.translate(tx, ty);
    }
    matrixScale(sx, sy, cx, cy) {
      const eps = 1e-10;
      if (sx === 0) sx = eps;
      if (sy === 0) sy = eps;
      this.ctx.translate(cx, cy);
      this.ctx.scale(sx, sy);
      this.ctx.translate(-cx, -cy);
    }
    matrixRotate(angle, px, py) {
      this.ctx.translate(px, py);
      this.ctx.rotate(angle * Math.PI / 180);
      this.ctx.translate(-px, -py);
    }
    matrixSkew(sx, sy) {
      this.ctx.transform(1, sy, sx, 1, 0, 0);
    }
    matrixFromPath(pathId, fraction, vOffset, _flags) {
      const data = this.pathDataCache.get(pathId);
      if (!data) return;
      const { segments, totalLen } = this.collectPathSegments(data);
      if (totalLen === 0) return;
      let target = totalLen * fraction % totalLen;
      if (target < 0) target += totalLen;
      this.positionOnPath(segments, target, vOffset);
    }
    // --- Path measurement helpers ---
    /** Flatten path data into line segments (curves are subdivided). */
    collectPathSegments(data) {
      const segments = [];
      let curX = 0, curY = 0, startX = 0, startY = 0;
      let i = 0;
      const addLine = (x1, y1, x2, y2) => {
        const dx = x2 - x1, dy = y2 - y1;
        const len = Math.sqrt(dx * dx + dy * dy);
        if (len > 0) segments.push({ x1, y1, x2, y2, len });
      };
      while (i < data.length) {
        const bits = data[i];
        let cmd = isNaNBits(bits) ? idFromBits(bits) : intBitsToFloat2(bits);
        if (cmd >= _CanvasPaintContext.NANMAP_PATH_BASE && cmd <= _CanvasPaintContext.NANMAP_PATH_BASE + 6) {
          cmd = _CanvasPaintContext.PATH_MOVE + (cmd - _CanvasPaintContext.NANMAP_PATH_BASE);
        }
        switch (cmd) {
          case _CanvasPaintContext.PATH_MOVE:
            i++;
            curX = startX = intBitsToFloat2(data[i]);
            curY = startY = intBitsToFloat2(data[i + 1]);
            i += 2;
            break;
          case _CanvasPaintContext.PATH_LINE: {
            i += 3;
            const ex = intBitsToFloat2(data[i]), ey = intBitsToFloat2(data[i + 1]);
            i += 2;
            addLine(curX, curY, ex, ey);
            curX = ex;
            curY = ey;
            break;
          }
          case _CanvasPaintContext.PATH_QUADRATIC: {
            i += 3;
            const cpx = intBitsToFloat2(data[i]), cpy = intBitsToFloat2(data[i + 1]);
            const ex = intBitsToFloat2(data[i + 2]), ey = intBitsToFloat2(data[i + 3]);
            i += 4;
            this.flattenQuadratic(curX, curY, cpx, cpy, ex, ey, addLine);
            curX = ex;
            curY = ey;
            break;
          }
          case _CanvasPaintContext.PATH_CONIC: {
            i += 3;
            const cpx = intBitsToFloat2(data[i]), cpy = intBitsToFloat2(data[i + 1]);
            const ex = intBitsToFloat2(data[i + 2]), ey = intBitsToFloat2(data[i + 3]);
            i += 5;
            this.flattenQuadratic(curX, curY, cpx, cpy, ex, ey, addLine);
            curX = ex;
            curY = ey;
            break;
          }
          case _CanvasPaintContext.PATH_CUBIC: {
            i += 3;
            const cp1x = intBitsToFloat2(data[i]), cp1y = intBitsToFloat2(data[i + 1]);
            const cp2x = intBitsToFloat2(data[i + 2]), cp2y = intBitsToFloat2(data[i + 3]);
            const ex = intBitsToFloat2(data[i + 4]), ey = intBitsToFloat2(data[i + 5]);
            i += 6;
            this.flattenCubic(curX, curY, cp1x, cp1y, cp2x, cp2y, ex, ey, addLine);
            curX = ex;
            curY = ey;
            break;
          }
          case _CanvasPaintContext.PATH_CLOSE: {
            i++;
            addLine(curX, curY, startX, startY);
            curX = startX;
            curY = startY;
            break;
          }
          case _CanvasPaintContext.PATH_DONE:
            i = data.length;
            break;
          default:
            i++;
            break;
        }
      }
      let totalLen = 0;
      for (const s of segments) totalLen += s.len;
      return { segments, totalLen };
    }
    /** Subdivide a quadratic bezier into line segments. */
    flattenQuadratic(x0, y0, cpx, cpy, x1, y1, addLine) {
      const N = 16;
      let px = x0, py = y0;
      for (let j = 1; j <= N; j++) {
        const t = j / N, mt = 1 - t;
        const x = mt * mt * x0 + 2 * mt * t * cpx + t * t * x1;
        const y = mt * mt * y0 + 2 * mt * t * cpy + t * t * y1;
        addLine(px, py, x, y);
        px = x;
        py = y;
      }
    }
    /** Subdivide a cubic bezier into line segments. */
    flattenCubic(x0, y0, cp1x, cp1y, cp2x, cp2y, x1, y1, addLine) {
      const N = 16;
      let px = x0, py = y0;
      for (let j = 1; j <= N; j++) {
        const t = j / N, mt = 1 - t;
        const x = mt * mt * mt * x0 + 3 * mt * mt * t * cp1x + 3 * mt * t * t * cp2x + t * t * t * x1;
        const y = mt * mt * mt * y0 + 3 * mt * mt * t * cp1y + 3 * mt * t * t * cp2y + t * t * t * y1;
        addLine(px, py, x, y);
        px = x;
        py = y;
      }
    }
    /** Translate and rotate the canvas to a point at the given distance along segments. */
    positionOnPath(segments, distance, vOffset) {
      let accum = 0;
      for (const s of segments) {
        if (accum + s.len >= distance || s === segments[segments.length - 1]) {
          const t = s.len > 0 ? (distance - accum) / s.len : 0;
          const px = s.x1 + (s.x2 - s.x1) * t;
          const py = s.y1 + (s.y2 - s.y1) * t;
          const angle = Math.atan2(s.y2 - s.y1, s.x2 - s.x1);
          const nx = -Math.sin(angle) * vOffset;
          const ny = Math.cos(angle) * vOffset;
          this.ctx.translate(px + nx, py + ny);
          this.ctx.rotate(angle);
          return;
        }
        accum += s.len;
      }
    }
    // --- Clipping ---
    clipRect(left, top, right, bottom) {
      this.ctx.beginPath();
      this.ctx.rect(left, top, right - left, bottom - top);
      this.ctx.clip();
    }
    clipPath(pathId, _regionOp) {
      const data = this.pathDataCache.get(pathId);
      if (!data) return;
      const path = this.buildPath2D(data);
      this.ctx.clip(path);
    }
    roundedClipRect(width, height, topStart, topEnd, bottomStart, bottomEnd) {
      const r = (v) => Number.isFinite(v) && v > 0 ? v : 0;
      this.ctx.beginPath();
      this.ctx.roundRect(
        0,
        0,
        width,
        height,
        [r(topStart), r(topEnd), r(bottomEnd), r(bottomStart)]
      );
      this.ctx.clip();
    }
    startGraphicsLayer(w, h) {
      this.ctx.save();
      try {
        const offCtx = this.createLayerCanvas(w, h);
        this.layerStack.push({
          previousCtx: this.ctx,
          offscreenCanvas: offCtx.canvas,
          width: w,
          height: h,
          attributes: /* @__PURE__ */ new Map()
        });
        this.ctx = offCtx;
      } catch (_e) {
      }
    }
    setGraphicsLayer(attributes) {
      const layer = this.layerStack[this.layerStack.length - 1];
      if (layer) {
        layer.attributes = attributes;
      }
    }
    endGraphicsLayer() {
      const layer = this.layerStack.pop();
      if (!layer) {
        this.ctx.restore();
        return;
      }
      const layerCanvas = layer.offscreenCanvas;
      this.ctx = layer.previousCtx;
      const attrs = layer.attributes;
      const scaleX = attrs.get(_CanvasPaintContext.GL_SCALE_X) ?? 1;
      const scaleY = attrs.get(_CanvasPaintContext.GL_SCALE_Y) ?? 1;
      const rotationZ = attrs.get(_CanvasPaintContext.GL_ROTATION_Z) ?? 0;
      const transX = attrs.get(_CanvasPaintContext.GL_TRANSLATION_X) ?? 0;
      const transY = attrs.get(_CanvasPaintContext.GL_TRANSLATION_Y) ?? 0;
      const originX = attrs.get(_CanvasPaintContext.GL_TRANSFORM_ORIGIN_X) ?? 0.5;
      const originY = attrs.get(_CanvasPaintContext.GL_TRANSFORM_ORIGIN_Y) ?? 0.5;
      const alpha = attrs.get(_CanvasPaintContext.GL_ALPHA) ?? 1;
      const shape = attrs.get(_CanvasPaintContext.GL_SHAPE);
      const shapeRadius = attrs.get(_CanvasPaintContext.GL_SHAPE_RADIUS) ?? 0;
      const pivotX = originX * layer.width;
      const pivotY = originY * layer.height;
      const hasTransform = scaleX !== 1 || scaleY !== 1 || rotationZ !== 0 || transX !== 0 || transY !== 0;
      if (hasTransform) {
        this.ctx.translate(pivotX + transX, pivotY + transY);
        if (rotationZ !== 0) {
          this.ctx.rotate(rotationZ * Math.PI / 180);
        }
        if (scaleX !== 1 || scaleY !== 1) {
          this.ctx.scale(scaleX, scaleY);
        }
        this.ctx.translate(-pivotX, -pivotY);
      }
      if (shape !== void 0) {
        this.ctx.beginPath();
        if (shape === 2) {
          const r = Math.min(layer.width, layer.height) / 2;
          this.ctx.arc(layer.width / 2, layer.height / 2, r, 0, Math.PI * 2);
        } else if (shape === 1 && shapeRadius > 0) {
          this.ctx.roundRect(0, 0, layer.width, layer.height, [shapeRadius]);
        } else {
          this.ctx.rect(0, 0, layer.width, layer.height);
        }
        this.ctx.clip();
      }
      if (alpha !== 1) {
        this.ctx.globalAlpha = alpha;
      }
      this.ctx.drawImage(layerCanvas, 0, 0);
      this.ctx.restore();
    }
    drawToBitmap(bitmapId, mode, color) {
      if (this.mainCanvas === null) {
        this.mainCanvas = this.ctx;
      }
      if (bitmapId === 0) {
        this.ctx = this.mainCanvas;
        return;
      }
      let offCtx = this.bitmapCanvasCache.get(bitmapId);
      if (!offCtx) {
        const img = this.bitmapCache.get(bitmapId);
        const w = img ? img.width || 256 : 256;
        const h = img ? img.height || 256 : 256;
        offCtx = this.createLayerCanvas(w, h);
        this.bitmapCanvasCache.set(bitmapId, offCtx);
      }
      if ((mode & 1) === 0) {
        const canvas = offCtx.canvas;
        offCtx.clearRect(0, 0, canvas.width, canvas.height);
        if (color !== 0) {
          offCtx.fillStyle = argbToRgba(color);
          offCtx.fillRect(0, 0, canvas.width, canvas.height);
        }
      }
      this.ctx = offCtx;
    }
    reset() {
      this.resetPaintState();
      this.clearNeedsRepaint();
    }
  };
  // Path command IDs (NaN-encoded in the float array)
  // PathExpression uses short IDs (10-16), binary PathData uses NanMap IDs (0x300000+)
  _CanvasPaintContext.PATH_MOVE = 10;
  _CanvasPaintContext.PATH_LINE = 11;
  _CanvasPaintContext.PATH_QUADRATIC = 12;
  _CanvasPaintContext.PATH_CONIC = 13;
  _CanvasPaintContext.PATH_CUBIC = 14;
  _CanvasPaintContext.PATH_CLOSE = 15;
  _CanvasPaintContext.PATH_DONE = 16;
  // NanMap path command base (Java convention used in binary PathData)
  _CanvasPaintContext.NANMAP_PATH_BASE = 3145728;
  // --- Graphics layer (offscreen compositing) ---
  // GraphicsLayer attribute IDs (from GraphicsLayerModifierOperation.java)
  _CanvasPaintContext.GL_SCALE_X = 0;
  _CanvasPaintContext.GL_SCALE_Y = 1;
  _CanvasPaintContext.GL_ROTATION_Z = 4;
  _CanvasPaintContext.GL_TRANSFORM_ORIGIN_X = 5;
  _CanvasPaintContext.GL_TRANSFORM_ORIGIN_Y = 6;
  _CanvasPaintContext.GL_TRANSLATION_X = 7;
  _CanvasPaintContext.GL_TRANSLATION_Y = 8;
  _CanvasPaintContext.GL_ALPHA = 11;
  _CanvasPaintContext.GL_SHAPE = 20;
  _CanvasPaintContext.GL_SHAPE_RADIUS = 21;
  var CanvasPaintContext = _CanvasPaintContext;

  // third_party/remote-compose-player/src/core/DefaultSystemColors.ts
  function C(c) {
    return c | 0;
  }
  var DEFAULT_SYSTEM_COLORS = {
    // ── system_accent1 (Primary Blue tonal palette) ──
    "system_accent1_0": C(4294967295),
    "system_accent1_10": C(4278197054),
    "system_accent1_50": C(4278201706),
    "system_accent1_100": C(4278207136),
    "system_accent1_200": C(4279918796),
    "system_accent1_300": C(4283204328),
    "system_accent1_400": C(4285373695),
    "system_accent1_500": C(4288264447),
    "system_accent1_600": C(4289578751),
    "system_accent1_700": C(4291157759),
    "system_accent1_800": C(4292602367),
    "system_accent1_900": C(4293849343),
    "system_accent1_1000": C(4278190080),
    // ── system_accent2 (Secondary muted blue tonal palette) ──
    "system_accent2_0": C(4294967295),
    "system_accent2_10": C(4279180075),
    "system_accent2_50": C(4280167746),
    "system_accent2_100": C(4281615706),
    "system_accent2_200": C(4283194739),
    "system_accent2_300": C(4284839564),
    "system_accent2_400": C(4286484647),
    "system_accent2_500": C(4288261058),
    "system_accent2_600": C(4289971677),
    "system_accent2_700": C(4291813882),
    "system_accent2_800": C(4292733183),
    "system_accent2_900": C(4293849343),
    "system_accent2_1000": C(4278190080),
    // ── system_accent3 (Tertiary mauve/lavender tonal palette) ──
    "system_accent3_0": C(4294967295),
    "system_accent3_10": C(4281013825),
    "system_accent3_50": C(4282263640),
    "system_accent3_100": C(4283842416),
    "system_accent3_200": C(4285421450),
    "system_accent3_300": C(4287131812),
    "system_accent3_400": C(4288907968),
    "system_accent3_500": C(4290684124),
    "system_accent3_600": C(4292526073),
    "system_accent3_700": C(4293975551),
    "system_accent3_800": C(4294503679),
    "system_accent3_900": C(4294966271),
    "system_accent3_1000": C(4278190080),
    // ── system_neutral1 (Neutral tonal palette) ──
    "system_neutral1_0": C(4294967295),
    "system_neutral1_10": C(4279901214),
    "system_neutral1_50": C(4281217331),
    "system_neutral1_100": C(4282730570),
    "system_neutral1_200": C(4284309602),
    "system_neutral1_300": C(4285954427),
    "system_neutral1_400": C(4287665046),
    "system_neutral1_500": C(4289441200),
    "system_neutral1_600": C(4291217611),
    "system_neutral1_700": C(4293059559),
    "system_neutral1_800": C(4294045940),
    "system_neutral1_900": C(4294835455),
    "system_neutral1_1000": C(4278190080),
    // ── system_neutral2 (Neutral variant tonal palette) ──
    "system_neutral2_0": C(4294967295),
    "system_neutral2_10": C(4279835682),
    "system_neutral2_50": C(4281151544),
    "system_neutral2_100": C(4282664783),
    "system_neutral2_200": C(4284243815),
    "system_neutral2_300": C(4285888384),
    "system_neutral2_400": C(4287599002),
    "system_neutral2_500": C(4289309620),
    "system_neutral2_600": C(4291151568),
    "system_neutral2_700": C(4292993772),
    "system_neutral2_800": C(4293980410),
    "system_neutral2_900": C(4294835199),
    "system_neutral2_1000": C(4278190080),
    // ── system_error (Error red tonal palette) ──
    "system_error_0": C(4294967295),
    "system_error_10": C(4282449922),
    "system_error_50": C(4284219396),
    "system_error_100": C(4286119944),
    "system_error_200": C(4287823882),
    "system_error_300": C(4290386458),
    "system_error_400": C(4292753200),
    "system_error_500": C(4294923337),
    "system_error_600": C(4294936957),
    "system_error_700": C(4294948011),
    "system_error_800": C(4294957782),
    "system_error_900": C(4294965495),
    "system_error_1000": C(4278190080),
    // ── Semantic colors ──
    "system_on_surface_light": C(4279901214),
    "system_on_surface_dark": C(4293059559),
    "system_on_surface_variant_light": C(4282664783),
    "system_on_surface_variant_dark": C(4291151568),
    "system_on_surface_disabled": C(1629101086),
    "system_surface_light": C(4294835455),
    "system_surface_dark": C(4279901214),
    "system_surface_disabled": C(521804830),
    "system_surface_variant_light": C(4292993772),
    "system_surface_variant_dark": C(4282664783),
    "system_surface_bright_light": C(4294835455),
    "system_surface_bright_dark": C(4281809214),
    "system_surface_dim_light": C(4292729309),
    "system_surface_dim_dark": C(4279901214),
    "system_surface_container_lowest_light": C(4294967295),
    "system_surface_container_lowest_dark": C(4279111698),
    "system_surface_container_low_light": C(4294439671),
    "system_surface_container_low_dark": C(4280164388),
    "system_surface_container_light": C(4294044912),
    "system_surface_container_dark": C(4280427816),
    "system_surface_container_high_light": C(4293650154),
    "system_surface_container_high_dark": C(4281151539),
    "system_surface_container_highest_light": C(4293255653),
    "system_surface_container_highest_dark": C(4281875262),
    "system_background_light": C(4294835455),
    "system_background_dark": C(4279901214),
    "system_on_background_light": C(4279901214),
    "system_on_background_dark": C(4293059559),
    "system_primary_light": C(4279918796),
    "system_primary_dark": C(4289578751),
    "system_primary_container_light": C(4292602367),
    "system_primary_container_dark": C(4278207136),
    "system_on_primary_light": C(4294967295),
    "system_on_primary_dark": C(4278201706),
    "system_on_primary_container_light": C(4278197054),
    "system_on_primary_container_dark": C(4292602367),
    "system_on_primary_fixed": C(4278197054),
    "system_on_primary_fixed_variant": C(4278207136),
    "system_primary_fixed": C(4292602367),
    "system_primary_fixed_dim": C(4289578751),
    "system_secondary_light": C(4283194739),
    "system_secondary_dark": C(4289971677),
    "system_secondary_container_light": C(4292733183),
    "system_secondary_container_dark": C(4281615706),
    "system_on_secondary_light": C(4294967295),
    "system_on_secondary_dark": C(4280167746),
    "system_on_secondary_container_light": C(4279180075),
    "system_on_secondary_container_dark": C(4292733183),
    "system_on_secondary_fixed": C(4279180075),
    "system_on_secondary_fixed_variant": C(4281615706),
    "system_secondary_fixed": C(4292733183),
    "system_secondary_fixed_dim": C(4289971677),
    "system_tertiary_light": C(4285421450),
    "system_tertiary_dark": C(4292526073),
    "system_tertiary_container_light": C(4294503679),
    "system_tertiary_container_dark": C(4283842416),
    "system_on_tertiary_light": C(4294967295),
    "system_on_tertiary_dark": C(4282263640),
    "system_on_tertiary_container_light": C(4281013825),
    "system_on_tertiary_container_dark": C(4294503679),
    "system_on_tertiary_fixed": C(4281013825),
    "system_on_tertiary_fixed_variant": C(4283842416),
    "system_tertiary_fixed": C(4294503679),
    "system_tertiary_fixed_dim": C(4292526073),
    "system_error_light": C(4290386458),
    "system_error_dark": C(4294948011),
    "system_error_container_light": C(4294957782),
    "system_error_container_dark": C(4287823882),
    "system_on_error_light": C(4294967295),
    "system_on_error_dark": C(4285071365),
    "system_on_error_container_light": C(4282449922),
    "system_on_error_container_dark": C(4294957782),
    "system_outline_light": C(4285888384),
    "system_outline_dark": C(4287599002),
    "system_outline_variant_light": C(4291151568),
    "system_outline_variant_dark": C(4282664783),
    "system_outline_disabled": C(521804830),
    "system_control_activated_light": C(4279918796),
    "system_control_activated_dark": C(4289578751),
    "system_control_normal_light": C(4285954427),
    "system_control_normal_dark": C(4287665046),
    "system_control_highlight_light": C(689576990),
    "system_control_highlight_dark": C(702735335),
    "system_palette_key_color_primary_light": C(4279918796),
    "system_palette_key_color_primary_dark": C(4285373695),
    "system_palette_key_color_secondary_light": C(4283194739),
    "system_palette_key_color_secondary_dark": C(4286484647),
    "system_palette_key_color_tertiary_light": C(4285421450),
    "system_palette_key_color_tertiary_dark": C(4288907968),
    "system_palette_key_color_neutral_light": C(4284309602),
    "system_palette_key_color_neutral_dark": C(4287665046),
    "system_palette_key_color_neutral_variant_light": C(4284243815),
    "system_palette_key_color_neutral_variant_dark": C(4287599002),
    // ── Text colors ──
    "system_text_hint_inverse_light": C(2147483648),
    "system_text_hint_inverse_dark": C(2164260863),
    "system_text_primary_inverse_light": C(4278190080),
    "system_text_primary_inverse_dark": C(4294967295),
    "system_text_primary_inverse_disable_only_light": C(2147483648),
    "system_text_primary_inverse_disable_only_dark": C(2164260863),
    "system_text_secondary_and_tertiary_inverse_light": C(3003121664),
    "system_text_secondary_and_tertiary_inverse_dark": C(3019898879),
    "system_text_secondary_and_tertiary_inverse_disabled_light": C(2147483648),
    "system_text_secondary_and_tertiary_inverse_disabled_dark": C(2164260863),
    // ── Legacy Holo colors ──
    "holo_blue_bright": C(4278246911),
    "holo_blue_dark": C(4278229452),
    "holo_blue_light": C(4281578981),
    "holo_green_dark": C(4284913920),
    "holo_green_light": C(4288269312),
    "holo_orange_dark": C(4294936576),
    "holo_orange_light": C(4294949683),
    "holo_purple": C(4289357516),
    "holo_red_dark": C(4291559424),
    "holo_red_light": C(4294919236),
    "background_dark": C(4278190080),
    "background_light": C(4294967295),
    "black": C(4278190080),
    "darker_gray": C(4289374890),
    "tab_indicator_text": C(4286611584)
  };
  function getDefaultSystemColor(name) {
    return DEFAULT_SYSTEM_COLORS[name];
  }

  // third_party/remote-compose-player/src/web/WebRemoteContext.ts
  var WebRemoteContext = class extends RemoteContext {
    constructor(paintContext, clock = SYSTEM_CLOCK) {
      super(clock);
      // Named variable tracking
      this.namedVariables = /* @__PURE__ */ new Map();
      this.namedColorOverrides = /* @__PURE__ */ new Map();
      this.namedStringOverrides = /* @__PURE__ */ new Map();
      this.namedBooleanOverrides = /* @__PURE__ */ new Map();
      this.namedIntegerOverrides = /* @__PURE__ */ new Map();
      this.namedFloatOverrides = /* @__PURE__ */ new Map();
      // --- Sound (Web Audio) ---
      this.audioContext = null;
      this.soundBytes = /* @__PURE__ */ new Map();
      this.soundBuffers = /* @__PURE__ */ new Map();
      this.canvasPaintContext = paintContext;
      this.mPaintContext = paintContext;
    }
    // --- Path data ---
    loadPathData(instanceId, winding, path) {
      this.mRemoteComposeState.putPathData(instanceId, path);
      this.mRemoteComposeState.putPathWinding(instanceId, winding);
      this.canvasPaintContext.loadPathData(instanceId, winding, path);
    }
    getPathData(instanceId) {
      return this.mRemoteComposeState.getPathData(instanceId);
    }
    // --- Variables ---
    loadVariableName(varName, varId, varType) {
      this.namedVariables.set(varName, { id: varId, type: varType });
      if (varType === 2) {
        const lookupName = varName.startsWith("color.") ? varName.substring(6) : varName;
        const defaultColor = getDefaultSystemColor(lookupName);
        if (defaultColor !== void 0 && !this.namedColorOverrides.has(varName)) {
          this.mRemoteComposeState.overrideColor(varId, defaultColor);
        }
      }
    }
    getVariableNameMap() {
      const map = /* @__PURE__ */ new Map();
      this.namedVariables.forEach(({ id }, name) => map.set(id, name));
      return map;
    }
    // --- Color ---
    loadColor(id, color) {
      this.mRemoteComposeState.updateColor(id, color);
    }
    setNamedColorOverride(colorName, color) {
      this.namedColorOverrides.set(colorName, color);
      const v = this.namedVariables.get(colorName);
      if (v) this.mRemoteComposeState.overrideColor(v.id, color);
    }
    getColor(id) {
      return this.mRemoteComposeState.getColor(id);
    }
    // --- String ---
    setNamedStringOverride(stringName, value) {
      this.namedStringOverrides.set(stringName, value);
      const v = this.namedVariables.get(stringName);
      if (v) this.mRemoteComposeState.overrideData(v.id, value);
    }
    clearNamedStringOverride(stringName) {
      this.namedStringOverrides.delete(stringName);
      const v = this.namedVariables.get(stringName);
      if (v) this.mRemoteComposeState.clearDataOverride(v.id);
    }
    // --- Boolean ---
    setNamedBooleanOverride(booleanName, value) {
      this.namedBooleanOverrides.set(booleanName, value);
    }
    clearNamedBooleanOverride(booleanName) {
      this.namedBooleanOverrides.delete(booleanName);
    }
    // --- Integer ---
    setNamedIntegerOverride(integerName, value) {
      this.namedIntegerOverrides.set(integerName, value);
      const v = this.namedVariables.get(integerName);
      if (v) this.mRemoteComposeState.overrideInteger(v.id, value);
    }
    clearNamedIntegerOverride(integerName) {
      this.namedIntegerOverrides.delete(integerName);
      const v = this.namedVariables.get(integerName);
      if (v) this.mRemoteComposeState.clearIntegerOverride(v.id);
    }
    // --- Float ---
    setNamedFloatOverride(floatName, value) {
      this.namedFloatOverrides.set(floatName, value);
      const v = this.namedVariables.get(floatName);
      if (v) this.mRemoteComposeState.overrideFloat(v.id, value);
    }
    clearNamedFloatOverride(floatName) {
      this.namedFloatOverrides.delete(floatName);
      const v = this.namedVariables.get(floatName);
      if (v) this.mRemoteComposeState.clearFloatOverride(v.id);
    }
    // --- Long ---
    setNamedLong(_name, _value) {
    }
    // --- Data ---
    setNamedDataOverride(dataName, value) {
      const v = this.namedVariables.get(dataName);
      if (v) this.mRemoteComposeState.overrideData(v.id, value);
    }
    clearNamedDataOverride(dataName) {
      const v = this.namedVariables.get(dataName);
      if (v) this.mRemoteComposeState.clearDataOverride(v.id);
    }
    // --- Collections & DataMaps ---
    addCollection(id, collection) {
      this.mRemoteComposeState.addCollection(id, collection);
    }
    putDataMap(id, map) {
      this.mRemoteComposeState.putDataMap(id, map);
    }
    getDataMap(id) {
      return this.mRemoteComposeState.getDataMap(id);
    }
    // --- Actions ---
    runAction(id, metadata) {
      this.mDocument?.runAction(id, metadata);
    }
    runNamedAction(id, value) {
      const name = this.mRemoteComposeState.getFromId(id);
      this.mDocument?.runNamedAction(name, value);
    }
    // --- Objects ---
    putObject(id, value) {
      this.mRemoteComposeState.updateObject(id, value);
    }
    getObject(id) {
      return this.mRemoteComposeState.getObject(id);
    }
    // --- Haptic ---
    hapticEffect(_type) {
    }
    // --- Bitmap ---
    loadBitmap(imageId, encoding, type, width, height, bitmap) {
      this.canvasPaintContext.loadBitmap(imageId, encoding, type, width, height, bitmap);
    }
    // --- Font ---
    loadFont(fontId, fontData) {
      super.loadFont(fontId, fontData);
      this.canvasPaintContext.loadFont(fontId, fontData);
    }
    // --- Text ---
    loadText(id, text) {
      this.mRemoteComposeState.cacheDataWithId(id, text);
      this.canvasPaintContext.loadText(id, text);
    }
    getText(id) {
      return this.canvasPaintContext.getText(id);
    }
    // --- Float ---
    loadFloat(id, value) {
      this.mRemoteComposeState.updateFloat(id, value);
    }
    overrideFloat(id, value) {
      this.mRemoteComposeState.overrideFloat(id, value);
    }
    getFloat(id) {
      return this.mRemoteComposeState.getFloat(id);
    }
    // --- Integer ---
    loadInteger(id, value) {
      this.mRemoteComposeState.updateInteger(id, value);
    }
    overrideInteger(id, value) {
      this.mRemoteComposeState.overrideInteger(id, value);
    }
    getInteger(id) {
      return this.mRemoteComposeState.getInteger(id);
    }
    getLong(id) {
      return this.mRemoteComposeState.getInteger(id);
    }
    // --- Text override ---
    overrideText(id, valueId) {
      const text = this.mRemoteComposeState.getFromId(valueId);
      if (text) this.loadText(id, text);
    }
    // --- Animated float ---
    loadAnimatedFloat(_id, _animatedFloat) {
    }
    // --- Shader ---
    loadShader(id, value) {
      this.mRemoteComposeState.updateObject(id, value);
    }
    getShader(id) {
      return this.mRemoteComposeState.getObject(id);
    }
    getAudioContext() {
      try {
        if (!this.audioContext) {
          const Ctor = typeof window !== "undefined" && (window.AudioContext || window.webkitAudioContext) || (typeof AudioContext !== "undefined" ? AudioContext : void 0);
          if (!Ctor) return null;
          this.audioContext = new Ctor();
        }
        return this.audioContext;
      } catch (e) {
        console.warn("RemoteCompose: failed to create AudioContext", e);
        return null;
      }
    }
    loadSound(id, data) {
      try {
        this.soundBytes.set(id, data.slice());
        this.soundBuffers.delete(id);
      } catch (e) {
        console.warn(`RemoteCompose: loadSound(${id}) failed`, e);
      }
    }
    playSound(id) {
      try {
        const ctx = this.getAudioContext();
        if (!ctx) return;
        ctx.resume?.().catch(() => {
        });
        const cached = this.soundBuffers.get(id);
        if (cached) {
          this.startBuffer(ctx, cached);
          return;
        }
        const bytes = this.soundBytes.get(id);
        if (!bytes) return;
        const arrayBuf = bytes.slice().buffer;
        const decoded = ctx.decodeAudioData(arrayBuf);
        if (decoded && typeof decoded.then === "function") {
          decoded.then((buffer) => {
            this.soundBuffers.set(id, buffer);
            this.startBuffer(ctx, buffer);
          }).catch((e) => console.warn(`RemoteCompose: decode sound(${id}) failed`, e));
        }
      } catch (e) {
        console.warn(`RemoteCompose: playSound(${id}) failed`, e);
      }
    }
    startBuffer(ctx, buffer) {
      try {
        const source = ctx.createBufferSource();
        source.buffer = buffer;
        const gain = ctx.createGain();
        gain.gain.value = 1;
        source.connect(gain).connect(ctx.destination);
        source.start(0);
      } catch (e) {
        console.warn("RemoteCompose: startBuffer failed", e);
      }
    }
    // --- Listeners ---
    listensTo(id, variableSupport) {
      this.mRemoteComposeState.listenToVar(id, variableSupport);
    }
    // --- Click areas ---
    addClickArea(id, contentDescriptionId, left, top, right, bottom, metadataId) {
      const cd = this.mRemoteComposeState.getFromId(contentDescriptionId) ?? "";
      const meta = this.mRemoteComposeState.getFromId(metadataId) ?? "";
      this.mDocument?.addClickArea(id, cd, left, top, right, bottom, meta);
    }
    // --- Update ops ---
    updateOps() {
      return this.mRemoteComposeState.getOpsToUpdate(this, Date.now());
    }
  };

  // third_party/remote-compose-player/src/web/RcPlayerElement.ts
  function base64ToArrayBuffer(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  }
  function createPlayer(container, options = {}) {
    const canvas = document.createElement("canvas");
    canvas.width = 256;
    canvas.height = 256;
    if (options.background) {
      canvas.style.background = options.background;
    }
    container.appendChild(canvas);
    const player = new RcdPlayer(canvas);
    if (options.theme) {
      player.setTheme(options.theme);
    }
    if (options.onLoad) {
      player.setOnLoad(options.onLoad);
    }
    const applySize = () => {
      if (options.width != null && options.height != null) {
        player.resize(options.width, options.height);
      }
    };
    if (options.data) {
      const buf = base64ToArrayBuffer(options.data);
      player.loadFromArrayBuffer(buf).then(applySize);
    } else if (options.buffer) {
      player.loadFromArrayBuffer(options.buffer).then(applySize);
    } else if (options.src) {
      player.loadFromUrl(options.src).then(applySize);
    }
    const handle = {
      async loadFromUrl(url) {
        const doc = await player.loadFromUrl(url);
        applySize();
        return doc;
      },
      async loadFromBase64(base64) {
        const buf = base64ToArrayBuffer(base64);
        const doc = await player.loadFromArrayBuffer(buf);
        applySize();
        return doc;
      },
      async loadFromArrayBuffer(buffer) {
        const doc = await player.loadFromArrayBuffer(buffer);
        applySize();
        return doc;
      },
      resize(width, height) {
        player.resize(width, height);
      },
      destroy() {
        player.stop();
        canvas.remove();
      },
      player,
      canvas
    };
    return handle;
  }
  var RcPlayerElement = class extends HTMLElement {
    constructor() {
      super();
      this._handle = null;
      this._shadow = this.attachShadow({ mode: "open" });
      this._shadow.innerHTML = `
            <style>
                :host { display: inline-block; }
                .container { display: inline-block; }
                canvas { display: block; }
            </style>
            <div class="container"></div>
        `;
      this._container = this._shadow.querySelector(".container");
    }
    connectedCallback() {
      this._init();
    }
    disconnectedCallback() {
      if (this._handle) {
        this._handle.destroy();
        this._handle = null;
      }
    }
    attributeChangedCallback(name, _oldVal, newVal) {
      if (!this._handle) return;
      if (name === "src" && newVal) {
        this._handle.loadFromUrl(newVal);
      } else if (name === "data" && newVal) {
        this._handle.loadFromBase64(newVal);
      } else if (name === "width" || name === "height") {
        const w = parseInt(this.getAttribute("width") || "0", 10);
        const h = parseInt(this.getAttribute("height") || "0", 10);
        if (w > 0 && h > 0) {
          this._handle.resize(w, h);
        }
      } else if (name === "theme" && newVal) {
        this._handle.player.setTheme(newVal);
      } else if (name === "background" && newVal) {
        this._handle.canvas.style.background = newVal;
      }
    }
    _init() {
      if (this._handle) return;
      const opts = {};
      const src = this.getAttribute("src");
      const data = this.getAttribute("data");
      const w = this.getAttribute("width");
      const h = this.getAttribute("height");
      const theme = this.getAttribute("theme");
      const bg = this.getAttribute("background");
      if (src) opts.src = src;
      if (data) opts.data = data;
      if (w && h) {
        opts.width = parseInt(w, 10);
        opts.height = parseInt(h, 10);
      }
      if (theme) opts.theme = theme;
      if (bg) opts.background = bg;
      this._handle = createPlayer(this._container, opts);
    }
  };
  RcPlayerElement.observedAttributes = ["src", "data", "width", "height", "theme", "background"];

  // third_party/remote-compose-player/src/web/main.ts
  var RcdPlayer = class {
    constructor(canvas) {
      this.document = null;
      this.paintContext = null;
      this.remoteContext = null;
      this.animationFrameId = null;
      this.startTime = 0;
      this.onLoad = null;
      // Theme override: 'light' | 'dark' | 'auto' (default)
      this.themeOverride = "auto";
      // Variable listener
      this.variableListener = null;
      // Touch/pointer tracking
      this.pointerIsDown = false;
      this.pointerHistory = [];
      // Fit transform applied each frame in renderFrame to scale the document's
      // native size into the canvas. Pointer coords must be inverse-mapped.
      this.mContentScale = 1;
      this.mContentOffsetX = 0;
      this.mContentOffsetY = 0;
      this.renderFrame = (timestamp) => {
        this.animationFrameId = null;
        if (!this.document || !this.remoteContext || !this.paintContext) return;
        const elapsed = (timestamp - this.startTime) / 1e3;
        this.remoteContext.setAnimationTime(elapsed);
        this.remoteContext.currentTime = Date.now();
        this.ctx.setTransform(1, 0, 0, 1, 0, 0);
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.mContentScale = 1;
        this.mContentOffsetX = 0;
        this.mContentOffsetY = 0;
        this.paintContext.reset();
        this.paintContext.clearNeedsRepaint();
        let theme;
        if (this.themeOverride === "light") {
          theme = Theme.LIGHT;
        } else if (this.themeOverride === "dark") {
          theme = Theme.DARK;
        } else {
          const isDark = window.matchMedia?.("(prefers-color-scheme: dark)").matches;
          theme = isDark ? Theme.DARK : Theme.LIGHT;
        }
        this.document.paint(this.remoteContext, theme);
        if (this.variableListener) {
          this.variableListener(this.remoteContext.mRemoteComposeState.getFloatEntries());
        }
        const repaintDelay = this.document.needsRepaint();
        if (repaintDelay >= 0) {
          if (repaintDelay <= 1) {
            this.animationFrameId = requestAnimationFrame(this.renderFrame);
          } else {
            setTimeout(() => {
              this.animationFrameId = requestAnimationFrame(this.renderFrame);
            }, repaintDelay);
          }
        }
      };
      this.canvas = canvas;
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("Could not get 2D context");
      this.ctx = ctx;
      this.setupPointerEvents();
    }
    setupPointerEvents() {
      this.canvas.addEventListener("pointerdown", (e) => {
        if (!this.document || !this.remoteContext) return;
        this.pointerIsDown = true;
        const { x, y } = this.canvasCoords(e);
        this.pointerHistory = [{ x, y, t: performance.now() }];
        this.remoteContext.loadFloat(RemoteContext.ID_TOUCH_EVENT_TIME, this.remoteContext.getAnimationTime());
        this.document.touchDown(this.remoteContext, x, y);
        this.scheduleRepaint();
      });
      this.canvas.addEventListener("pointermove", (e) => {
        if (!this.pointerIsDown || !this.document || !this.remoteContext) return;
        const { x, y } = this.canvasCoords(e);
        this.pointerHistory.push({ x, y, t: performance.now() });
        if (this.pointerHistory.length > 5) this.pointerHistory.shift();
        this.remoteContext.loadFloat(RemoteContext.ID_TOUCH_EVENT_TIME, this.remoteContext.getAnimationTime());
        this.document.touchDrag(this.remoteContext, x, y);
        this.scheduleRepaint();
      });
      this.canvas.addEventListener("pointerup", (e) => {
        if (!this.pointerIsDown || !this.document || !this.remoteContext) return;
        this.pointerIsDown = false;
        const { x, y } = this.canvasCoords(e);
        const { dx, dy } = this.computeVelocity(x, y);
        this.remoteContext.loadFloat(RemoteContext.ID_TOUCH_EVENT_TIME, this.remoteContext.getAnimationTime());
        this.document.touchUp(this.remoteContext, x, y, dx, dy);
        this.pointerHistory = [];
        this.scheduleRepaint();
      });
      this.canvas.addEventListener("pointercancel", () => {
        this.pointerIsDown = false;
        this.pointerHistory = [];
      });
    }
    canvasCoords(e) {
      const rect = this.canvas.getBoundingClientRect();
      const scaleX = this.canvas.width / rect.width;
      const scaleY = this.canvas.height / rect.height;
      const px = (e.clientX - rect.left) * scaleX;
      const py = (e.clientY - rect.top) * scaleY;
      const s = this.mContentScale || 1;
      return {
        x: (px - this.mContentOffsetX) / s,
        y: (py - this.mContentOffsetY) / s
      };
    }
    computeVelocity(curX, curY) {
      if (this.pointerHistory.length < 2) return { dx: 0, dy: 0 };
      const first = this.pointerHistory[0];
      const now = performance.now();
      const dt = (now - first.t) / 1e3;
      if (dt <= 0) return { dx: 0, dy: 0 };
      return {
        dx: (curX - first.x) / dt,
        dy: (curY - first.y) / dt
      };
    }
    scheduleRepaint() {
      if (this.animationFrameId === null) {
        this.animationFrameId = requestAnimationFrame(this.renderFrame);
      }
    }
    setOnLoad(cb) {
      this.onLoad = cb;
    }
    setTheme(theme) {
      this.themeOverride = theme;
      this.scheduleRepaint();
    }
    setVariableListener(cb) {
      this.variableListener = cb;
    }
    removeVariableListener() {
      this.variableListener = null;
    }
    async loadFromArrayBuffer(data) {
      this.stop();
      if (this.paintContext) this.paintContext.destroy();
      const buffer = RemoteComposeBuffer.fromArrayBuffer(data);
      const doc = new CoreDocument();
      doc.initFromBuffer(buffer);
      this.document = doc;
      const density = doc.getProperty(Header.DOC_DENSITY_AT_GENERATION) || 1;
      const docWidth = this.canvas.width;
      const docHeight = this.canvas.height;
      doc.setWidth(docWidth);
      doc.setHeight(docHeight);
      this.paintContext = new CanvasPaintContext(null, this.ctx);
      this.paintContext.onFontLoaded = () => {
        this.document?.invalidateMeasure();
        this.scheduleRepaint();
      };
      this.remoteContext = new WebRemoteContext(this.paintContext);
      doc.initializeContext(this.remoteContext);
      this.remoteContext.setPaintContext(this.paintContext);
      this.paintContext.setContext(this.remoteContext);
      this.remoteContext.mWidth = docWidth;
      this.remoteContext.mHeight = docHeight;
      this.remoteContext.setDensity(density);
      doc.applyDataOperations(this.remoteContext);
      await new Promise((resolve) => setTimeout(resolve, 50));
      if (this.onLoad) this.onLoad(doc);
      this.startTime = performance.now();
      this.renderFrame(this.startTime);
      return doc;
    }
    async loadFromUrl(url) {
      const response = await fetch(url);
      const data = await response.arrayBuffer();
      return this.loadFromArrayBuffer(data);
    }
    stop() {
      if (this.animationFrameId !== null) {
        cancelAnimationFrame(this.animationFrameId);
        this.animationFrameId = null;
      }
    }
    /**
     * Stop, and release the WebGL context.
     *
     * `stop()` only cancels the animation frame; a document that used a shader still
     * holds a WebGL context afterwards. Callers showing many documents at once must
     * use this instead, or the browser's context limit silently blanks the earliest
     * ones.
     */
    destroy() {
      this.stop();
      if (this.paintContext) {
        this.paintContext.destroy();
      }
    }
    repaint() {
      if (this.document) {
        this.renderFrame(performance.now());
      }
    }
    /**
     * Resolves once every named font family this document has asked for is paintable.
     *
     * Only a single-shot renderer needs this — it has no later frame in which a face could appear,
     * so it must `await player.fontsReady()` between the first paint (which is what *discovers* the
     * families) and the frame it keeps. Interactive players get the same effect from the repaint the
     * player schedules when a face lands.
     */
    async fontsReady() {
      await webFontsReady();
      this.document?.invalidateMeasure();
    }
    resize(newWidth, newHeight) {
      this.canvas.width = newWidth;
      this.canvas.height = newHeight;
      this.canvas.style.width = newWidth + "px";
      this.canvas.style.height = newHeight + "px";
      if (this.remoteContext) {
        this.remoteContext.mWidth = newWidth;
        this.remoteContext.mHeight = newHeight;
      }
      this.scheduleRepaint();
    }
    getDocument() {
      return this.document;
    }
    getRemoteContext() {
      return this.remoteContext;
    }
    /**
     * Capture a screenshot of the current canvas as a PNG Blob.
     *
     * Usage:
     *   const blob = await player.screenshot();
     *   // Download:
     *   const a = document.createElement('a');
     *   a.href = URL.createObjectURL(blob);
     *   a.download = 'screenshot.png';
     *   a.click();
     *
     *   // Or get as data URL:
     *   const url = await player.screenshotDataURL();
     */
    async screenshot(type = "image/png", quality) {
      return new Promise((resolve, reject) => {
        this.canvas.toBlob(
          (blob) => blob ? resolve(blob) : reject(new Error("toBlob failed")),
          type,
          quality
        );
      });
    }
    /** Capture a screenshot as a data URL string (e.g. "data:image/png;base64,..."). */
    screenshotDataURL(type = "image/png", quality) {
      return this.canvas.toDataURL(type, quality);
    }
    /** Save a screenshot as a file download. */
    async saveScreenshot(filename = "screenshot.png") {
      const blob = await this.screenshot();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    }
  };
  if (typeof window !== "undefined") {
    window.RcdPlayer = RcdPlayer;
    if (!customElements.get("rc-player")) {
      customElements.define("rc-player", RcPlayerElement);
    }
  }
  return __toCommonJS(main_exports);
})();
