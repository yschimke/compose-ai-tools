// rc2json.ts: RC to JSON converter.
// Uses the existing TS parser (CoreDocument) for correct binary parsing of all
// operation formats (including modern Header with tag-value properties, variable-length
// paint bundles, path data, etc.). Walks parsed Operation objects to produce
// agent-friendly JSON with flat fields, $ref:ID for NaN-encoded remote IDs.

import { RemoteComposeBuffer } from './core/RemoteComposeBuffer';
import { CoreDocument } from './core/CoreDocument';
import type { Operation } from './core/Operation';

// --- Opcode to name mapping (from Java Operations.java) ---
const OP_NAMES: Record<number, string> = {
    0: "HEADER",
    2: "COMPONENT_START",
    4: "LOAD_BITMAP",
    14: "ANIMATION_SPEC",
    16: "MODIFIER_WIDTH",
    38: "CLIP_PATH",
    39: "CLIP_RECT",
    40: "PAINT_VALUES",
    42: "DRAW_RECT",
    43: "DRAW_TEXT_RUN",
    44: "DRAW_BITMAP",
    45: "DATA_SHADER",
    46: "DRAW_CIRCLE",
    47: "DRAW_LINE",
    48: "DRAW_BITMAP_FONT_TEXT_RUN",
    51: "DRAW_ROUND_RECT",
    52: "DRAW_SECTOR",
    53: "DRAW_TEXT_ON_PATH",
    54: "MODIFIER_ROUNDED_CLIP_RECT",
    55: "MODIFIER_BACKGROUND",
    56: "DRAW_OVAL",
    57: "DRAW_TEXT_ON_CIRCLE",
    58: "MODIFIER_PADDING",
    59: "MODIFIER_CLICK",
    63: "THEME",
    64: "CLICK_AREA",
    65: "ROOT_CONTENT_BEHAVIOR",
    66: "DRAW_BITMAP_INT",
    67: "MODIFIER_HEIGHT",
    80: "DATA_FLOAT",
    81: "ANIMATED_FLOAT",
    101: "DATA_BITMAP",
    102: "DATA_TEXT",
    103: "ROOT_CONTENT_DESCRIPTION",
    107: "MODIFIER_BORDER",
    108: "MODIFIER_CLIP_RECT",
    123: "DATA_PATH",
    124: "DRAW_PATH",
    125: "DRAW_TWEEN_PATH",
    126: "MATRIX_SCALE",
    127: "MATRIX_TRANSLATE",
    128: "MATRIX_SKEW",
    129: "MATRIX_ROTATE",
    130: "MATRIX_SAVE",
    131: "MATRIX_RESTORE",
    133: "DRAW_TEXT_ANCHOR",
    134: "COLOR_EXPRESSIONS",
    135: "TEXT_FROM_FLOAT",
    136: "TEXT_MERGE",
    137: "NAMED_VARIABLE",
    138: "COLOR_CONSTANT",
    139: "DRAW_CONTENT",
    140: "DATA_INT",
    143: "DATA_BOOLEAN",
    144: "INTEGER_EXPRESSION",
    145: "ID_MAP",
    146: "ID_LIST",
    147: "FLOAT_LIST",
    148: "DATA_LONG",
    149: "DRAW_BITMAP_SCALED",
    150: "COMPONENT_VALUE",
    151: "TEXT_LOOKUP",
    152: "DRAW_ARC",
    153: "TEXT_LOOKUP_INT",
    154: "DATA_MAP_LOOKUP",
    155: "TEXT_MEASURE",
    156: "TEXT_LENGTH",
    157: "TOUCH_EXPRESSION",
    158: "PATH_TWEEN",
    159: "PATH_CREATE",
    160: "PATH_ADD",
    161: "PARTICLE_DEFINE",
    162: "PARTICLE_PROCESS",
    163: "PARTICLE_LOOP",
    164: "IMPULSE_START",
    165: "IMPULSE_PROCESS",
    167: "DATA_BITMAP_FONT",
    170: "ATTRIBUTE_TEXT",
    173: "CANVAS_OPERATIONS",
    174: "MODIFIER_DRAW_CONTENT",
    176: "LAYOUT_FIT_BOX",
    177: "HAPTIC_FEEDBACK",
    178: "CONDITIONAL_OPERATIONS",
    179: "DEBUG_MESSAGE",
    180: "ATTRIBUTE_COLOR",
    181: "MATRIX_FROM_PATH",
    182: "TEXT_SUBTEXT",
    186: "MATRIX_CONSTANT",
    187: "MATRIX_EXPRESSION",
    188: "MATRIX_VECTOR_MATH",
    190: "DRAW_TO_BITMAP",
    191: "WAKE_IN",
    192: "ID_LOOKUP",
    193: "PATH_EXPRESSION",
    194: "PARTICLE_COMPARE",
    196: "COLOR_THEME",
    197: "DYNAMIC_FLOAT_LIST",
    198: "UPDATE_DYNAMIC_FLOAT_LIST",
    199: "TEXT_TRANSFORM",
    200: "LAYOUT_ROOT",
    201: "LAYOUT_CONTENT",
    202: "LAYOUT_BOX",
    203: "LAYOUT_ROW",
    204: "LAYOUT_COLUMN",
    205: "LAYOUT_CANVAS",
    207: "LAYOUT_CANVAS_CONTENT",
    208: "LAYOUT_TEXT",
    209: "HOST_ACTION",
    211: "MODIFIER_VISIBILITY",
    212: "VALUE_INTEGER_CHANGE_ACTION",
    213: "VALUE_STRING_CHANGE_ACTION",
    214: "CONTAINER_END",
    215: "LOOP_START",
    216: "HOST_METADATA_ACTION",
    219: "MODIFIER_TOUCH_DOWN",
    220: "MODIFIER_TOUCH_UP",
    221: "MODIFIER_OFFSET",
    222: "VALUE_FLOAT_CHANGE_ACTION",
    223: "MODIFIER_ZINDEX",
    224: "MODIFIER_GRAPHICS_LAYER",
    225: "MODIFIER_TOUCH_CANCEL",
    226: "MODIFIER_SCROLL",
    227: "VALUE_FLOAT_EXPRESSION_CHANGE_ACTION",
    228: "MODIFIER_MARQUEE",
    229: "MODIFIER_RIPPLE",
    230: "LAYOUT_COLLAPSIBLE_ROW",
    231: "MODIFIER_WIDTH_IN",
    232: "MODIFIER_HEIGHT_IN",
    233: "LAYOUT_COLLAPSIBLE_COLUMN",
    234: "LAYOUT_IMAGE",
    235: "MODIFIER_COLLAPSIBLE_PRIORITY",
    236: "RUN_ACTION",
    237: "MODIFIER_ALIGN_BY",
    238: "LAYOUT_COMPUTE",
    239: "CORE_TEXT",
    240: "LAYOUT_FLOW",
};

// --- NaN-encoded remote ID handling ---
const _nanBuf = new ArrayBuffer(4);
const _nanView = new DataView(_nanBuf);

function nanToRef(v: number): string {
    _nanView.setFloat32(0, v);
    const bits = _nanView.getInt32(0);
    const id = bits & 0x003FFFFF;
    return `$ref:${id}`;
}

// --- Value serialization ---

function uint8ToBase64(data: Uint8Array): string {
    if (typeof Buffer !== 'undefined') {
        return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString('base64');
    }
    let binary = '';
    for (let i = 0; i < data.length; i++) {
        binary += String.fromCharCode(data[i]);
    }
    return btoa(binary);
}

// IntMap sentinel value
const INT_MAP_NOT_PRESENT = -2147483648;

// Detect and serialize IntMap objects
function isIntMap(v: any): boolean {
    return v && typeof v === 'object' &&
        'mKeys' in v && 'mValues' in v && 'mSize' in v &&
        v.mKeys instanceof Int32Array;
}

function serializeIntMap(v: any): Record<string, any> {
    const result: Record<string, any> = {};
    const keys = v.mKeys as Int32Array;
    const values = v.mValues as any[];
    for (let i = 0; i < keys.length; i++) {
        if (keys[i] !== INT_MAP_NOT_PRESENT) {
            result[String(keys[i])] = serializeValue(values[i], 1);
        }
    }
    return result;
}

// Fields to skip (internal/resolved state, not useful for JSON output)
const SKIP_FIELDS = new Set([
    // Base Operation internal field
    'mDirty',
    // Resolved output duplicates (wire values are more informative)
    'mOutLeft', 'mOutTop', 'mOutRight', 'mOutBottom',
    'mOutX', 'mOutY', 'mOutPanX', 'mOutPanY',
    'mOutValue', 'mOutMin', 'mOutMax',
    'mOutputPath',
    // Internal computation state
    'mPathChanged', 'mLastString', 'mBounds',
    'mLastChange', 'mLastCalculatedValue', 'mLastAnimatedValue',
    'mR0', 'mR1', 'mR2', 'mR3', 'mVar',
    'mPreCalcValue', 'mFloatAnimation', 'mSpring',
    'mComputedTextLayout', 'mNewString', 'mCachedString',
    'mTextX', 'mTextY', 'mTextW', 'mTextH', 'mBaseline',
    'mMeasureFontSize',
    'mIsDynamicColorEnabled', 'mColorValue',
    'mFontSizeValue', 'mFontWeightValue', 'mTextAlignValue',
    // LayoutComponent runtime state (not wire data)
    'mX', 'mY', 'mWidth', 'mHeight',   // layout position/size (runtime)
    'mZIndex', 'mVisibility',
    'mNeedsMeasure', 'mNeedsRepaint', 'mFirstLayout',
    'mWidthMod', 'mHeightMod', 'mWidthInMod', 'mHeightInMod',
    'mZIndexMod', 'mGraphicsLayerMod',
    'mPaddingLeft', 'mPaddingRight', 'mPaddingTop', 'mPaddingBottom',
    'mChildrenComponents', 'mDrawContentOperations',
    'mComponentModifiers', 'mContentOps',
    'mHasCanvasLayoutContent', 'mComputedLayoutModifiers',
    'mComponentValues', 'mCachedWrapSize',
    'mCurrentId', 'mAnimationSpec',
    'mParent', 'mDocument', 'mLayoutDone',
    'mMeasureWidth', 'mMeasureHeight',
    'mLayoutX', 'mLayoutY', 'mLayoutWidth', 'mLayoutHeight',
    // LayoutManager children list (handled via _children)
    'mChildren',
    // Layout container state
    'mHasTouchListeners',
    // Modifier resolved output state
    'mOutR', 'mOutG', 'mOutB', 'mOutA',
    'mLayoutW', 'mLayoutH',
    'mComponent',
    // Paint state
    'mPaint',
]);

function serializeValue(v: any, depth: number): any {
    if (v === null || v === undefined) return null;

    if (typeof v === 'number') {
        if (Number.isNaN(v)) return nanToRef(v);
        if (!Number.isFinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        return v;
    }

    if (typeof v === 'string' || typeof v === 'boolean') return v;

    // Typed arrays
    if (v instanceof Float32Array) {
        return Array.from(v).map((x: number) =>
            Number.isNaN(x) ? nanToRef(x) : x
        );
    }
    if (v instanceof Int32Array || v instanceof Uint32Array ||
        v instanceof Int16Array || v instanceof Uint16Array) {
        return Array.from(v);
    }
    if (v instanceof Uint8Array) return uint8ToBase64(v);

    // Regular arrays
    if (Array.isArray(v)) {
        return v.map(x => serializeValue(x, depth + 1));
    }

    // Maps
    if (v instanceof Map) {
        const obj: Record<string, any> = {};
        v.forEach((val: any, key: any) => {
            obj[String(key)] = serializeValue(val, depth + 1);
        });
        return obj;
    }

    // IntMap (custom hash map)
    if (isIntMap(v)) {
        return serializeIntMap(v);
    }

    // Nested objects with m-prefixed fields (PaintBundle etc.)
    if (depth < 3 && typeof v === 'object' && v.constructor) {
        // PaintBundle: has mArray + mPos
        if ('mArray' in v && 'mPos' in v) {
            const arr = v.mArray;
            const pos = v.mPos;
            if (Array.isArray(arr)) {
                return arr.slice(0, pos).map((x: any) => serializeValue(x, depth + 1));
            }
        }

        // Generic object with m-prefixed fields
        const keys = Object.keys(v).filter(k => k.startsWith('m') && !SKIP_FIELDS.has(k));
        if (keys.length > 0) {
            const obj: Record<string, any> = {};
            for (const key of keys) {
                const fn = key.length > 1 ? key[1].toLowerCase() + key.slice(2) : key;
                const sv = serializeValue(v[key], depth + 1);
                if (sv !== undefined) obj[fn] = sv;
            }
            return obj;
        }
    }

    // Skip unserializable values
    return undefined;
}

function fieldName(key: string): string {
    if (key.length <= 1) return key;
    // mXxx → xxx, but handle mX1 → x1 etc.
    return key[1].toLowerCase() + key.slice(2);
}

function operationToJson(op: Operation): Record<string, any> {
    const ctor = op.constructor as any;
    const opcode: number = ctor.OP_CODE ?? -1;
    const name = OP_NAMES[opcode] || ctor.name || `unknown_${opcode}`;

    const result: Record<string, any> = { op: name, opcode };

    for (const key of Object.keys(op)) {
        if (!key.startsWith('m')) continue;  // only m-prefixed wire fields
        if (SKIP_FIELDS.has(key)) continue;

        const val = (op as any)[key];
        const serialized = serializeValue(val, 0);
        if (serialized === undefined) continue;

        result[fieldName(key)] = serialized;
    }

    return result;
}

// Recursively traverse operations, descending into containers via getList()
function flattenOperations(ops: Operation[], out: Record<string, any>[]): void {
    for (const op of ops) {
        // If this operation has children (layout containers), recurse into them first
        const asList = (op as any).getList;
        if (typeof asList === 'function') {
            const children: Operation[] = asList.call(op);
            if (children && children.length > 0) {
                const json = operationToJson(op);
                json._children = [];
                flattenOperations(children, json._children);
                out.push(json);
                continue;
            }
        }
        out.push(operationToJson(op));
    }
}

/**
 * Convert a binary RC buffer to an agent-friendly JSON object.
 *
 * Uses the full TS parser for correct handling of all operation formats
 * (modern Header, variable-length paint bundles, path data, etc.).
 */
export function rc2json(buffer: ArrayBuffer): object {
    const rcb = RemoteComposeBuffer.fromArrayBuffer(buffer);
    const doc = new CoreDocument();
    doc.initFromBuffer(rcb);

    const ops = doc.getOperations();
    const operations: Record<string, any>[] = [];
    flattenOperations(ops, operations);

    // Count total ops including nested children
    function countOps(list: Record<string, any>[]): number {
        let n = 0;
        for (const op of list) {
            n++;
            if (op._children) n += countOps(op._children);
        }
        return n;
    }

    return {
        format: "rc-json",
        version: 1,
        document: {
            width: doc.getWidth(),
            height: doc.getHeight(),
            operationCount: countOps(operations),
        },
        operations,
    };
}
