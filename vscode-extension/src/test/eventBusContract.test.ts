// Contract test for `webview/shared/eventBus`.
//
// The bus replaces raw `dispatchEvent`/`addEventListener` for app
// events with typed `emit` / `on` wrappers. This test reads the
// webview source tree and asserts that for every `emit("name", …)`
// callsite there is at least one matching `on("name", …)` callsite.
// Catches the "producer ships, consumer never wired" regression
// class that prompted the bus: the two sides live in files that
// don't import each other and each have their own green unit tests,
// so type checking + per-component tests can't see the gap.
//
// The check is intentionally static (file scan, no runtime mount):
//
//   - It runs in milliseconds and needs no DOM bootstrap.
//   - It exercises the source tree as written, not what happens to
//     execute in `firstUpdated`, so dead-code listeners attached
//     behind a feature flag are still treated as wired.
//
// The trade-off is that the test only checks events flowing through
// `emit`/`on`. Pre-existing `new CustomEvent("foo", …)` callsites are
// invisible to it; migrating them one-at-a-time onto the bus is the
// follow-up work. The point of this PR is to make the contract
// available so the next event added doesn't repeat the regression.

import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import { globSync } from "glob";

// `__dirname` resolves to `out/test` at runtime since tests run from
// compiled JS, but the contract scans `.ts` source files — walk up to
// the extension root and into `src/webview` so the scanner sees the
// authored callsites, not the emitted JS (which loses `as` casts and
// changes some shapes).
const EXTENSION_ROOT = path.resolve(__dirname, "..", "..");
const WEBVIEW_ROOT = path.join(EXTENSION_ROOT, "src", "webview");
const EVENT_BUS_FILE = path.join(WEBVIEW_ROOT, "shared", "eventBus.ts");

/**
 * Locate every `<fnName>(…, "<event-name>", …)` call in [source]
 * and return the event names. Walks balanced parens and string
 * literals so callsites whose first argument contains expressions
 * — `emit(currentBundleTarget(), "kind-toggled", detail)`,
 * `on(getName("x"), "name", …)`, multi-line argument lists —
 * are matched correctly. A regex with `[^)]*?` between the open
 * paren and the event-name literal stops at the first `)`, which
 * silently skips those valid callsites; the parser below treats
 * parens / strings / template literals as real tokens.
 *
 * The matching rule:
 *
 *   1. Find an occurrence of `<fnName>(` not preceded by a word
 *      character or `.` (so `addon(` and `bus.on(` don't match
 *      `on(`, and likewise for `emit`).
 *   2. From inside the paren, skip the first argument by walking
 *      until a comma at depth 0, treating `(){}[]"'`/template
 *      strings as balanced groups.
 *   3. Skip whitespace; expect a string literal next; record the
 *      literal as the event name. Anything else means the call
 *      doesn't match the bus shape, so skip it.
 */
export function scanCallsites(source: string, fnName: string): string[] {
    const names: string[] = [];
    const headerRe = new RegExp(`(?<![.\\w])${fnName}\\s*\\(`, "g");
    let m: RegExpExecArray | null;
    while ((m = headerRe.exec(source)) !== null) {
        const afterOpen = m.index + m[0].length;
        const commaAt = findFirstCommaAtDepth0(source, afterOpen);
        if (commaAt === -1) continue;
        const nameStart = skipWhitespace(source, commaAt + 1);
        const name = readStringLiteral(source, nameStart);
        if (name === null) continue;
        if (!/^[\w-]+$/.test(name)) continue;
        names.push(name);
    }
    return names;
}

/**
 * Scan from [start] (just inside the open paren) until we hit a
 * `,` at the same nesting depth as where we started. Returns the
 * index of that comma, or -1 if the call has only one argument
 * (close paren reached first). Treats nested parens, brackets,
 * braces, single / double / backtick strings as balanced groups
 * so commas inside them don't terminate the scan.
 *
 * Template-string `${…}` interpolations would technically need
 * recursive balancing, but bus callsites don't put templates in
 * the first argument; if that ever changes, extend the inner
 * skip to recurse into `${`.
 */
function findFirstCommaAtDepth0(source: string, start: number): number {
    let depth = 0;
    let i = start;
    while (i < source.length) {
        const c = source[i];
        if (c === '"' || c === "'" || c === "`") {
            i = skipStringLiteral(source, i);
            continue;
        }
        if (c === "/" && source[i + 1] === "/") {
            // Line comment — skip to newline so a `,` inside a
            // comment doesn't fool us.
            const nl = source.indexOf("\n", i);
            i = nl === -1 ? source.length : nl + 1;
            continue;
        }
        if (c === "/" && source[i + 1] === "*") {
            const end = source.indexOf("*/", i + 2);
            i = end === -1 ? source.length : end + 2;
            continue;
        }
        if (c === "(" || c === "[" || c === "{") {
            depth++;
        } else if (c === ")" || c === "]" || c === "}") {
            if (depth === 0) return -1;
            depth--;
        } else if (c === "," && depth === 0) {
            return i;
        }
        i++;
    }
    return -1;
}

/**
 * Walk past a string literal that begins at [i] (the index of the
 * opening quote). Returns the index just after the closing quote.
 * Handles backslash escapes; does not recurse into `${…}` because
 * bus callsites don't put template interpolations in the first
 * argument.
 */
function skipStringLiteral(source: string, i: number): number {
    const quote = source[i];
    let j = i + 1;
    while (j < source.length) {
        const c = source[j];
        if (c === "\\") {
            j += 2;
            continue;
        }
        if (c === quote) return j + 1;
        j++;
    }
    return source.length;
}

function skipWhitespace(source: string, i: number): number {
    while (i < source.length && /\s/.test(source[i])) i++;
    return i;
}

/**
 * Read a string literal starting at [i] (the index of the opening
 * quote). Returns the content between the quotes, or `null` if
 * [i] isn't pointed at a quote. Only handles plain `"…"` and
 * `'…'` — backtick template strings are excluded because event
 * names are required to be plain string literals (the typed
 * `WebviewEventMap` keys can't be template-interpolated anyway).
 */
function readStringLiteral(source: string, i: number): string | null {
    const quote = source[i];
    if (quote !== '"' && quote !== "'") return null;
    let j = i + 1;
    const out: string[] = [];
    while (j < source.length) {
        const c = source[j];
        if (c === "\\") {
            // Don't bother decoding escapes — event names use
            // word characters and `-` only, so a `\` in the
            // literal makes it not a valid event name and the
            // caller's regex check rejects it.
            out.push(c, source[j + 1] ?? "");
            j += 2;
            continue;
        }
        if (c === quote) return out.join("");
        out.push(c);
        j++;
    }
    return null;
}

function scanFiles(
    files: readonly string[],
    fnName: string,
): Map<string, string[]> {
    const found = new Map<string, string[]>();
    for (const f of files) {
        const src = fs.readFileSync(f, "utf8");
        for (const name of scanCallsites(src, fnName)) {
            const sites = found.get(name) ?? [];
            sites.push(path.relative(WEBVIEW_ROOT, f));
            found.set(name, sites);
        }
    }
    return found;
}

describe("event bus contract", () => {
    const files = globSync("**/*.ts", {
        cwd: WEBVIEW_ROOT,
        absolute: true,
        ignore: ["**/*.test.ts"],
    }).filter((f) => f !== EVENT_BUS_FILE);

    it("scans webview source files", () => {
        assert.ok(
            files.length > 0,
            `expected to scan webview .ts files under ${WEBVIEW_ROOT}`,
        );
    });

    it("every emit() name has at least one matching on() listener", () => {
        const emitted = scanFiles(files, "emit");
        const consumed = scanFiles(files, "on");
        const orphans: string[] = [];
        for (const [name, sites] of emitted) {
            if (!consumed.has(name)) {
                orphans.push(
                    `  - "${name}" emitted by ${sites.join(", ")} has no on() listener`,
                );
            }
        }
        assert.strictEqual(
            orphans.length,
            0,
            `Orphan events found:\n${orphans.join("\n")}\n` +
                `Each emit() callsite must have at least one matching on() ` +
                `listener somewhere in src/webview. Add the listener or, if ` +
                `the event is intentionally fire-and-forget, drop the emit ` +
                `call and the WebviewEventMap entry.`,
        );
    });

    it("every on() name has at least one matching emit() producer", () => {
        const emitted = scanFiles(files, "emit");
        const consumed = scanFiles(files, "on");
        const dead: string[] = [];
        for (const [name, sites] of consumed) {
            if (!emitted.has(name)) {
                dead.push(
                    `  - "${name}" listened on by ${sites.join(", ")} but no emit() producer`,
                );
            }
        }
        assert.strictEqual(
            dead.length,
            0,
            `Dead listeners found:\n${dead.join("\n")}\n` +
                `Each on() callsite must have at least one matching emit() ` +
                `producer in src/webview. Either remove the listener or wire ` +
                `up the missing producer.`,
        );
    });

    // Scanner self-tests. Each fixture exercises a shape the old
    // regex (`[^)]*?` between header and event name) silently
    // skipped, so a regression to that pattern fails here loudly
    // before falling out as a false-negative orphan check.
    describe("scanCallsites", () => {
        it("matches the baseline shape", () => {
            assert.deepStrictEqual(
                scanCallsites(`emit(target, "kind-toggled", det);`, "emit"),
                ["kind-toggled"],
            );
        });

        it("matches when the first argument contains a call expression", () => {
            assert.deepStrictEqual(
                scanCallsites(
                    `emit(currentBundleTarget(), "kind-toggled", det);`,
                    "emit",
                ),
                ["kind-toggled"],
            );
        });

        it("matches when the first argument has nested parens", () => {
            assert.deepStrictEqual(
                scanCallsites(
                    `emit(scope.lookup(getId(card)), "tab-selected", { id });`,
                    "emit",
                ),
                ["tab-selected"],
            );
        });

        it("does not pick a string inside the first argument", () => {
            // `getName("inner")` should be skipped; the event name
            // is the literal at the second-argument position.
            assert.deepStrictEqual(
                scanCallsites(
                    `emit(getName("inner"), "outer-event", det);`,
                    "emit",
                ),
                ["outer-event"],
            );
        });

        it("handles multi-line argument lists", () => {
            const src = [
                "emit(",
                "    target,",
                '    "kind-toggled",',
                "    {",
                "        bundleId,",
                "        kind,",
                "        enabled: true,",
                "    },",
                ");",
            ].join("\n");
            assert.deepStrictEqual(scanCallsites(src, "emit"), [
                "kind-toggled",
            ]);
        });

        it("rejects method-call shapes like foo.emit(...)", () => {
            assert.deepStrictEqual(
                scanCallsites(`bus.emit(target, "ghost", det);`, "emit"),
                [],
            );
        });

        it("rejects word-extended names like addon(...) for fnName 'on'", () => {
            assert.deepStrictEqual(
                scanCallsites(`addon(target, "ghost", h);`, "on"),
                [],
            );
        });

        it("matches on() with parens in the first argument", () => {
            assert.deepStrictEqual(
                scanCallsites(
                    `on(document.querySelector("input"), "kind-toggled", h);`,
                    "on",
                ),
                ["kind-toggled"],
            );
        });
    });
});
