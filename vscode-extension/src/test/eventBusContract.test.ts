// Contract test for `webview/shared/eventBus`.
//
// The bus replaces raw `dispatchEvent`/`addEventListener` for app
// events with typed `emit` / `on` wrappers. This test reads the
// webview source tree and asserts that for every `emit("name", …)`
// callsite there is at least one matching `on("name", …)` callsite.
// Catches the "producer ships, consumer never wired" regression
// class that prompted the bus (#1118-ish): the two sides live in
// files that don't import each other and each have their own green
// unit tests, so type checking + per-component tests can't see the
// gap.
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
// the extension root and into `src/webview` so the regex sees the
// authored callsites, not the emitted JS (which loses `as` casts and
// changes some shapes).
const EXTENSION_ROOT = path.resolve(__dirname, "..", "..");
const WEBVIEW_ROOT = path.join(EXTENSION_ROOT, "src", "webview");
const EVENT_BUS_FILE = path.join(WEBVIEW_ROOT, "shared", "eventBus.ts");

// Match `emit(target, "name", …)` and `on(target, "name", …)` where
// the name is a string literal. The first arg can be any expression;
// we don't care what target the event is dispatched on. Multi-line
// args are fine — the regex only needs the leading `emit(` / `on(`
// followed by anything up to the first quoted literal that ends in
// `,`. Restricting the second arg to `[^,]*` would break for cases
// like `emit(target, "name", …)` with detail objects on the same
// line; here we just locate the quoted literal directly.
// Negative lookbehind rejects `foo.emit(...)` / `foo.on(...)` style
// method calls so the contract only counts bare `emit` / `on` imports
// from the bus.
const EMIT_RE = /(?<![.\w])emit\s*\([^)]*?["']([\w-]+)["']/g;
const ON_RE = /(?<![.\w])on\s*\([^)]*?["']([\w-]+)["']/g;

function scan(files: readonly string[], re: RegExp): Map<string, string[]> {
    const found = new Map<string, string[]>();
    for (const f of files) {
        const src = fs.readFileSync(f, "utf8");
        // Reset the lastIndex on each file since we're reusing the
        // global regex object across iterations.
        const localRe = new RegExp(re.source, re.flags);
        let m: RegExpExecArray | null;
        while ((m = localRe.exec(src)) !== null) {
            const name = m[1];
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
        const emitted = scan(files, EMIT_RE);
        const consumed = scan(files, ON_RE);
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
        const emitted = scan(files, EMIT_RE);
        const consumed = scan(files, ON_RE);
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
});
