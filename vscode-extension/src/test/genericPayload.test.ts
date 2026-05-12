// Unit tests for the shape-aware generic payload renderers used by
// the focus inspector's "no presenter, but the daemon attached data"
// fallback path. Detectors are pure; DOM builders run under happy-dom.

import * as assert from "assert";
import {
    appendCopyRawButton,
    detectImagePayload,
    isArrayOfObjects,
    isArrayOfPrimitives,
    isColourMap,
    isPrimitiveKv,
    renderArrayOfObjectsTable,
    renderArrayOfPrimitivesList,
    renderColourSwatches,
    renderGenericBody,
    renderImageBody,
    renderJsonPre,
    renderPrimitiveKvDl,
    stringifySafe,
} from "../webview/preview/genericPayload";

describe("genericPayload shape detectors", () => {
    it("detectImagePayload recognises {imageBase64, mediaType}", () => {
        const payload = detectImagePayload({
            imageBase64: "AAAA",
            mediaType: "image/png",
        });
        assert.ok(payload);
        assert.strictEqual(payload!.imageBase64, "AAAA");
        assert.strictEqual(payload!.mediaType, "image/png");
    });

    it("detectImagePayload defaults mediaType when missing", () => {
        const payload = detectImagePayload({ imageBase64: "AAAA" });
        assert.ok(payload);
        assert.strictEqual(payload!.mediaType, "image/png");
    });

    it("detectImagePayload rejects empty imageBase64 / non-strings", () => {
        assert.strictEqual(detectImagePayload({ imageBase64: "" }), null);
        assert.strictEqual(detectImagePayload({ imageBase64: 42 }), null);
        assert.strictEqual(detectImagePayload(null), null);
        assert.strictEqual(detectImagePayload("not an image"), null);
    });

    it("isArrayOfObjects only matches homogeneous object arrays", () => {
        assert.strictEqual(isArrayOfObjects([{ a: 1 }, { a: 2 }]), true);
        // Empty arrays don't qualify — the caller drops them earlier
        // (no point rendering a zero-row table).
        assert.strictEqual(isArrayOfObjects([]), false);
        assert.strictEqual(isArrayOfObjects([1, 2]), false);
        assert.strictEqual(isArrayOfObjects([{ a: 1 }, null]), false);
        assert.strictEqual(isArrayOfObjects([{ a: 1 }, [1, 2]]), false);
        assert.strictEqual(isArrayOfObjects("nope"), false);
    });

    it("isArrayOfPrimitives matches strings/numbers/booleans", () => {
        assert.strictEqual(isArrayOfPrimitives(["a", "b"]), true);
        assert.strictEqual(isArrayOfPrimitives([1, 2.5]), true);
        assert.strictEqual(isArrayOfPrimitives([true, false]), true);
        assert.strictEqual(isArrayOfPrimitives([]), false);
        assert.strictEqual(isArrayOfPrimitives([1, { a: 1 }]), false);
    });

    it("isColourMap matches Record<string, hex-colour>", () => {
        assert.strictEqual(
            isColourMap({ primary: "#ff0000", surface: "#abcdef" }),
            true,
        );
        // 3-digit, 4-digit (with alpha), 8-digit (rgba) all qualify.
        assert.strictEqual(isColourMap({ a: "#fff", b: "#abcd" }), true);
        assert.strictEqual(isColourMap({ a: "#ff00ff80" }), true);
        // Mixed shapes get rejected so the swatches view doesn't
        // render a missing-colour entry.
        assert.strictEqual(
            isColourMap({ primary: "#ff0000", weight: "bold" }),
            false,
        );
        assert.strictEqual(isColourMap({}), false);
        assert.strictEqual(isColourMap(null), false);
        assert.strictEqual(isColourMap(["#fff"]), false);
    });

    it("isPrimitiveKv matches objects whose values are all scalars", () => {
        assert.strictEqual(isPrimitiveKv({ a: 1, b: "two", c: true }), true);
        assert.strictEqual(isPrimitiveKv({ a: null }), true);
        assert.strictEqual(isPrimitiveKv({ a: { b: 1 } }), false);
        assert.strictEqual(isPrimitiveKv({ a: [1, 2] }), false);
        assert.strictEqual(isPrimitiveKv({}), false);
    });

    it("stringifySafe falls back when JSON.stringify throws", () => {
        const cyclic: Record<string, unknown> = {};
        cyclic.self = cyclic;
        // happy-dom's JSON.stringify throws on cycles — exercise the
        // fallback path.
        const out = stringifySafe(cyclic);
        assert.ok(typeof out === "string");
        // BigInt also blows up JSON.stringify; the fallback turns it
        // into a runtime string.
        const big = BigInt(1);
        assert.strictEqual(stringifySafe(big), "1");
    });
});

describe("genericPayload renderers", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("renderImageBody emits an <img> with the right data URI", () => {
        const el = renderImageBody(
            { imageBase64: "AAAA", mediaType: "image/png" },
            "Sample",
        );
        const img = el.querySelector<HTMLImageElement>("img");
        assert.ok(img);
        assert.strictEqual(img!.src, "data:image/png;base64,AAAA");
        assert.strictEqual(img!.alt, "Sample");
    });

    it("renderArrayOfObjectsTable unions keys and renders rows", () => {
        const rows = [
            { fn: "A", count: 3 },
            { fn: "B", count: 7, slow: true },
        ];
        const table = renderArrayOfObjectsTable(rows);
        const headers = [...table.querySelectorAll("thead th")].map(
            (th) => th.textContent,
        );
        assert.deepStrictEqual(headers, ["fn", "count", "slow"]);
        const cells = [...table.querySelectorAll("tbody tr")].map((tr) =>
            [...tr.querySelectorAll("td")].map((td) => td.textContent),
        );
        assert.deepStrictEqual(cells, [
            ["A", "3", ""],
            ["B", "7", "true"],
        ]);
    });

    it("renderArrayOfObjectsTable formats nested cells succinctly", () => {
        const rows = [{ name: "X", tags: ["a", "b"], meta: { x: 1 } }];
        const table = renderArrayOfObjectsTable(rows);
        const cells = [...table.querySelectorAll("tbody td")].map(
            (td) => td.textContent,
        );
        assert.deepStrictEqual(cells, ["X", "[…2]", "{…}"]);
    });

    it("renderArrayOfPrimitivesList renders one <li> per item", () => {
        const ul = renderArrayOfPrimitivesList(["alpha", "beta", "gamma"]);
        const items = [...ul.querySelectorAll("li")].map(
            (li) => li.textContent,
        );
        assert.deepStrictEqual(items, ["alpha", "beta", "gamma"]);
    });

    it("renderColourSwatches paints sample + label + value per entry", () => {
        const wrap = renderColourSwatches({
            primary: "#ff0000",
            surface: "#00ff00",
        });
        const rows = [
            ...wrap.querySelectorAll<HTMLElement>(".focus-report-swatch"),
        ];
        assert.strictEqual(rows.length, 2);
        const sample = rows[0].querySelector<HTMLElement>(
            ".focus-report-swatch-sample",
        )!;
        // happy-dom normalises hex to rgb in computed styles, but the
        // raw style attribute keeps the input form.
        assert.ok(
            sample.style.backgroundColor.includes("ff0000") ||
                sample.style.backgroundColor.includes("rgb"),
        );
        assert.strictEqual(
            rows[0].querySelector(".focus-report-swatch-label")!.textContent,
            "primary",
        );
        assert.strictEqual(
            rows[0].querySelector(".focus-report-swatch-value")!.textContent,
            "#ff0000",
        );
    });

    it("renderPrimitiveKvDl produces alternating <dt>/<dd> pairs", () => {
        const dl = renderPrimitiveKvDl({ a: 1, b: "two", c: null });
        const dts = [...dl.querySelectorAll("dt")].map((d) => d.textContent);
        const dds = [...dl.querySelectorAll("dd")].map((d) => d.textContent);
        assert.deepStrictEqual(dts, ["a", "b", "c"]);
        assert.deepStrictEqual(dds, ["1", "two", "null"]);
    });

    it("renderJsonPre wraps pretty JSON in a <pre>", () => {
        const wrap = renderJsonPre({ z: 1, a: 2 });
        const pre = wrap.querySelector("pre");
        assert.ok(pre);
        // Pretty-printed JSON keeps key order — easier for the user
        // to compare against the raw payload from the daemon log.
        assert.ok(pre!.textContent?.includes('"z": 1'));
        assert.ok(pre!.textContent?.includes('"a": 2'));
    });
});

describe("renderGenericBody picks the most specific shape", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    function bodyOf(data: unknown): HTMLElement {
        return renderGenericBody(data, "test");
    }

    it("image payload → <img>", () => {
        const el = bodyOf({ imageBase64: "AAAA", mediaType: "image/png" });
        assert.ok(el.querySelector("img"));
    });

    it("array of objects → <table>", () => {
        const el = bodyOf([{ k: 1 }, { k: 2 }]);
        assert.ok(el.querySelector("table"));
    });

    it("array of primitives → <ul>", () => {
        const el = bodyOf(["a", "b", "c"]);
        assert.ok(el.querySelector("ul"));
    });

    it("colour map → swatches", () => {
        const el = bodyOf({ primary: "#abcdef", surface: "#123456" });
        assert.ok(el.querySelector(".focus-report-swatches"));
    });

    it("primitive kv → <dl>", () => {
        const el = bodyOf({ name: "x", scale: 1.5 });
        assert.ok(el.querySelector("dl"));
    });

    it("nested / mixed → JSON <pre>", () => {
        const el = bodyOf({ tree: { left: { value: 1 }, right: null } });
        assert.ok(el.querySelector("pre"));
    });

    it("every generic body gets a Copy raw button", () => {
        const el = bodyOf({ name: "x" });
        assert.ok(el.querySelector(".focus-report-copy-raw"));
    });
});

describe("appendCopyRawButton wiring", () => {
    let originalClipboardDescriptor: PropertyDescriptor | undefined;

    beforeEach(() => {
        // happy-dom defines `navigator.clipboard` as a getter, so a plain
        // assignment in the test errors. Snapshot the descriptor so we
        // can restore between cases and override via defineProperty
        // for the duration of each test.
        originalClipboardDescriptor = Object.getOwnPropertyDescriptor(
            globalThis.navigator,
            "clipboard",
        );
    });

    afterEach(() => {
        document.body.innerHTML = "";
        if (originalClipboardDescriptor) {
            Object.defineProperty(
                globalThis.navigator,
                "clipboard",
                originalClipboardDescriptor,
            );
        }
    });

    it("invokes navigator.clipboard.writeText with stringified JSON", () => {
        const writes: string[] = [];
        Object.defineProperty(globalThis.navigator, "clipboard", {
            configurable: true,
            value: {
                writeText: (s: string) => {
                    writes.push(s);
                    return Promise.resolve();
                },
            },
        });
        const host = document.createElement("div");
        appendCopyRawButton(host, { a: 1 });
        const btn = host.querySelector<HTMLButtonElement>("button");
        assert.ok(btn);
        btn!.click();
        assert.deepStrictEqual(writes, ['{\n  "a": 1\n}']);
        assert.strictEqual(btn!.textContent, "Copied");
        assert.strictEqual(btn!.disabled, true);
    });

    it("silently no-ops when clipboard is unavailable (decorative button)", () => {
        Object.defineProperty(globalThis.navigator, "clipboard", {
            configurable: true,
            value: undefined,
        });
        const host = document.createElement("div");
        appendCopyRawButton(host, { a: 1 });
        const btn = host.querySelector<HTMLButtonElement>("button")!;
        // No throw — the click handler tolerates the missing API.
        btn.click();
        assert.strictEqual(btn.textContent, "Copied");
    });
});
