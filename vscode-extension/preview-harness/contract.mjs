// Headless contract runner for the preview-harness scenarios. Same
// loop shell as `snapshot.mjs` (start a tiny static server, drive
// Playwright through each fixture), but instead of writing a PNG it
// reads the `postedMessageLog` the harness's `vscode-api.js` shim
// captures and asserts against each fixture's optional
// `expectedPosts` / `forbiddenPosts` arrays.
//
// Closes the regression-prevention strategy started with #1119
// (typed event bus) + #1122 (smoke tests): the harness already
// drives the webview UI end-to-end; this script captures the
// `vscode.postMessage` calls those clicks produce and pins them
// against the per-fixture contract. A regression where (e.g.)
// activating the Accessibility chip stops posting
// `setDataExtensionEnabled` for `a11y/hierarchy` fails the build.
//
// Usage:
//   node esbuild.webview.mjs           # rebuild bundle first
//   node preview-harness/contract.mjs  # checks all fixtures with `expectedPosts`
//
// Override the matrix:
//   node preview-harness/contract.mjs --fixture a11y-findings

import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { dirname, resolve, relative, normalize, sep } from "node:path";
import { readdir, readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const extensionRoot = resolve(harnessDir, "..");
const fixturesDir = resolve(harnessDir, "fixtures");

const mimeByExt = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ttf": "font/ttf",
    ".map": "application/json; charset=utf-8",
};

function startServer(root) {
    return new Promise((resolveServer) => {
        const server = createServer(async (req, res) => {
            try {
                const url = new URL(req.url, "http://localhost");
                const rel = decodeURIComponent(url.pathname).replace(
                    /^\/+/,
                    "",
                );
                const target = normalize(resolve(root, rel));
                if (
                    relative(root, target).startsWith("..") ||
                    target === root + sep + ".."
                ) {
                    res.writeHead(403);
                    res.end("forbidden");
                    return;
                }
                let filePath = target;
                try {
                    const s = await stat(filePath);
                    if (s.isDirectory()) {
                        filePath = resolve(filePath, "index.html");
                    }
                } catch {
                    res.writeHead(404);
                    res.end("not found: " + rel);
                    return;
                }
                const ext = filePath.slice(filePath.lastIndexOf("."));
                const body = await readFile(filePath);
                res.writeHead(200, {
                    "content-type":
                        mimeByExt[ext] ?? "application/octet-stream",
                    "cache-control": "no-store",
                });
                res.end(body);
            } catch (err) {
                res.writeHead(500);
                res.end(String(err));
            }
        });
        server.listen(0, "127.0.0.1", () => {
            const addr = server.address();
            resolveServer({
                origin: `http://127.0.0.1:${addr.port}`,
                close: () => new Promise((r) => server.close(r)),
            });
        });
    });
}

function arg(name, fallback) {
    const i = process.argv.indexOf("--" + name);
    return i >= 0 ? process.argv[i + 1] : fallback;
}

const explicitFixture = arg("fixture", null);

async function listFixtures() {
    if (explicitFixture) return [explicitFixture];
    const entries = await readdir(fixturesDir);
    return entries
        .filter((e) => e.endsWith(".json"))
        .map((e) => e.replace(/\.json$/, ""));
}

/**
 * Subset-equality: every key in [expected] must match the
 * corresponding key in [actual]. Extra keys on [actual] are
 * allowed so fixtures don't have to know about every field the
 * webview attaches (e.g. `previewId` defaults). Recurses into
 * nested objects but treats arrays as opaque (compare with
 * `JSON.stringify` for now — no fixture currently asserts on
 * an array-valued post).
 */
function matchesSubset(expected, actual) {
    if (typeof expected !== "object" || expected === null) {
        return expected === actual;
    }
    if (Array.isArray(expected)) {
        return JSON.stringify(expected) === JSON.stringify(actual);
    }
    if (typeof actual !== "object" || actual === null) return false;
    for (const k of Object.keys(expected)) {
        if (!matchesSubset(expected[k], actual[k])) return false;
    }
    return true;
}

function findMatchingPost(log, expected) {
    return log.find((post) => {
        if (matchesSubset(expected, post)) return true;
        // `setDataExtensionEnabled` switched from one message per kind
        // (`kind: "x"`) to a batched form (`kinds: ["x", "y"]`) so chip
        // activation lands as a single `data/subscribe` sequence rather
        // than racing the daemon's mode-lock-on-first-subscribe. Fixtures
        // still assert one kind at a time; treat `kind: "x"` as a match
        // for any logged post whose `kinds` array contains "x".
        if (
            post?.command === "setDataExtensionEnabled" &&
            Array.isArray(post.kinds) &&
            typeof expected?.kind === "string"
        ) {
            return post.kinds.some((k) => {
                const synthetic = { ...post, kind: k };
                delete synthetic.kinds;
                return matchesSubset(expected, synthetic);
            });
        }
        return false;
    });
}

async function loadFixture(name) {
    const raw = await readFile(resolve(fixturesDir, name + ".json"), "utf8");
    return JSON.parse(raw);
}

const failures = [];

const fixtures = await listFixtures();
const server = await startServer(extensionRoot);
const browser = await chromium.launch();

try {
    for (const fixtureName of fixtures) {
        const fixture = await loadFixture(fixtureName);
        const expected = fixture.expectedPosts ?? [];
        const forbidden = fixture.forbiddenPosts ?? [];
        if (expected.length === 0 && forbidden.length === 0) {
            console.log(
                `[contract] ${fixtureName}: no expectedPosts / forbiddenPosts — skipped`,
            );
            continue;
        }

        const context = await browser.newContext({
            viewport: { width: 1024, height: 720 },
        });
        const page = await context.newPage();
        page.on("pageerror", (err) =>
            console.error(`[${fixtureName}] pageerror:`, err.message),
        );
        page.on("console", (msg) => {
            if (msg.type() === "error") {
                console.error(`[${fixtureName}] console:`, msg.text());
            }
        });

        const url = new URL(server.origin + "/preview-harness/scenario.html");
        url.searchParams.set("fixture", fixtureName);
        url.searchParams.set("theme", "dark");
        await page.goto(url.href);

        await page.waitForFunction(
            () => window.__composePreviewHarness?.ready === true,
            { timeout: 10_000 },
        );

        const log = await page.evaluate(
            () => window.__composePreviewHarness.postedMessageLog,
        );

        const fixtureFailures = [];
        for (const exp of expected) {
            if (!findMatchingPost(log, exp)) {
                fixtureFailures.push({ kind: "missing", expected: exp });
            }
        }
        for (const forb of forbidden) {
            const hit = findMatchingPost(log, forb);
            if (hit) {
                fixtureFailures.push({
                    kind: "forbidden",
                    expected: forb,
                    actual: hit,
                });
            }
        }

        if (fixtureFailures.length === 0) {
            console.log(
                `[contract] ${fixtureName}: ✓ ${expected.length} expected post(s) matched, ${forbidden.length} forbidden absent`,
            );
        } else {
            console.error(
                `[contract] ${fixtureName}: ✗ ${fixtureFailures.length} contract violation(s)`,
            );
            for (const f of fixtureFailures) {
                if (f.kind === "missing") {
                    console.error(
                        "  - missing expected post:",
                        JSON.stringify(f.expected),
                    );
                } else {
                    console.error(
                        "  - forbidden post observed:",
                        JSON.stringify(f.actual),
                        "(matched rule)",
                        JSON.stringify(f.expected),
                    );
                }
            }
            console.error(
                `  Recorded postedMessageLog:`,
                JSON.stringify(log, null, 2),
            );
            failures.push({ fixture: fixtureName, fixtureFailures });
        }

        await context.close();
    }
} finally {
    await browser.close();
    await server.close();
}

if (failures.length > 0) {
    console.error(
        `\n[contract] FAILED — ${failures.length} fixture(s) had violations`,
    );
    process.exit(1);
}
console.log("\n[contract] PASSED");
