// Screenshot the `/compare` wall's reference lane WHILE IT IS STILL MEASURING — with and without
// the scores the delivery branch published.
//
// That moment is the whole subject. Every other shot of this wall (the preview-harness's
// `serve-format-compare-reference-lane` baseline included) deliberately waits for the in-browser
// scorer to settle, so none of them can show the state a visitor actually lands in: on a real
// catalog the serial scoring pass takes tens of seconds, and until it finishes the wall reads
// "comparing…" in catalog order — the one order that says nothing about which pair is wrong.
//
//   node shoot.mjs after.png            # the wall as this branch serves it
//   node shoot.mjs before.png --before  # the same six rows without the published numbers
//
// Neither shot invents a number. The script runs the wall ONCE to settle, reads the score the
// committed `format-compare.js` computes for each row, and writes those back as the
// `data-match-<variant>` attributes `ServeWeb` now emits from `references/index.json` — which is
// exactly what the publisher bakes, computed by driving this same asset
// (`scripts/design-artifacts/design-reference-score.mjs`). The scorer is then held open so both
// shots catch the wall mid-pass rather than racing it.
//
// The fixture is borrowed wholesale from `../compare-wall-diff-column/fixture`: a real
// `?format=reference` wall trimmed to six rows, beside the render/design-reference pairs those rows
// point at, fetched from the published `wear-m3-catalog`. Nothing is redrawn here.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.resolve(here, "../compare-wall-diff-column/fixture");
const assets = path.resolve(
    here,
    "../../../serve/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";

/**
 * The two assets `--before` serves from `HEAD` instead of from the working tree.
 *
 * Read out of git rather than reconstructed by stripping things in the page, because this change
 * moves behaviour and not only markup: the element now dresses every row — pictures included —
 * before it asks the scorer for anything, which is half of what the pair is showing. A `before`
 * shot taken against the new bundle would already be painting all six rows.
 */
const HEAD_ASSETS = new Set(["serve-components.js", "serve.css"]);
const fromHead = (name) =>
    execFileSync(
        "git",
        [
            "show",
            `HEAD:cli/serve/src/main/resources/ee/schimke/composeai/cli/serve/assets/${name}`,
        ],
        { cwd: path.resolve(here, "../../../.."), maxBuffer: 32 * 1024 * 1024 },
    );

const types = {
    ".html": "text/html",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".xml": "application/xml",
};
const server = http.createServer((request, response) => {
    const url = decodeURIComponent((request.url ?? "/").split("?")[0]);
    const root = url.startsWith("/assets/") ? assets : fixture;
    const file = url.startsWith("/assets/")
        ? path.join(assets, url.slice("/assets/".length))
        : path.join(fixture, url);
    if (before && HEAD_ASSETS.has(path.basename(file)) && root === assets) {
        response.writeHead(200, {
            "content-type": types[path.extname(file)] ?? "text/plain",
        });
        response.end(fromHead(path.basename(file)));
        return;
    }
    if (
        !file.startsWith(root) ||
        !fs.existsSync(file) ||
        fs.statSync(file).isDirectory()
    ) {
        response.writeHead(404).end("not here");
        return;
    }
    response.writeHead(200, {
        "content-type": types[path.extname(file)] ?? "application/octet-stream",
    });
    fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(8792, resolve));

const browser = await chromium.launch({
    executablePath: process.env.HARNESS_CHROMIUM ?? "/opt/pw-browsers/chromium",
});
// Dark, because this catalog is dark-first and that is the ground its references were drawn on.
const newContext = () =>
    browser.newContext({
        viewport: { width: 1180, height: 1500 },
        deviceScaleFactor: 2,
        colorScheme: "dark",
    });
const url = "http://127.0.0.1:8792/page.html?format=reference";

/** Every row's settled score, keyed by label — the numbers the publisher bakes. */
async function measure() {
    const context = await newContext();
    const page = await context.newPage();
    await page.goto(url, { waitUntil: "networkidle" });
    await page.locator('[data-compare-format="reference"]').click();
    await page.waitForFunction(() => {
        const cells = [
            ...document.querySelectorAll(
                ".cp-compare-row:not([hidden]) .cp-compare-score",
            ),
        ];
        return (
            cells.length > 0 &&
            cells.every((cell) => /%|unavailable/.test(cell.textContent ?? ""))
        );
    });
    const scores = await page.evaluate(() =>
        Object.fromEntries(
            [...document.querySelectorAll(".cp-compare-row:not([hidden])")]
                .filter((row) => row.hasAttribute("data-score"))
                .map((row) => [
                    row.getAttribute("data-label"),
                    Number(row.getAttribute("data-score")),
                ]),
        ),
    );
    await context.close();
    return scores;
}

/** A stand-in for `parity/issues.json`, against the two rows a reader of this wall would file. */
const ISSUES = {
    alertdialog: [
        {
            number: 118,
            state: "open",
            title: "Dialog face is offset from the Figma frame",
        },
    ],
    appcard: [
        {
            number: 96,
            state: "closed",
            title: "Card padding re-verified after the token update",
        },
    ],
};

const labelOf = (row) => /data-label="([^"]*)"/.exec(row)?.[1] ?? "";
const matchOf = (row) => {
    const raw = /data-match-neutral="([^"]*)"/.exec(row)?.[1];
    return raw === undefined ? Number.MAX_VALUE : Number(raw);
};

function bugCell(row) {
    const detail =
        /data-reference-detail-neutral="([^"]*)"/.exec(row)?.[1] ?? "#";
    const links = (ISSUES[labelOf(row)] ?? [])
        .map(
            (issue) =>
                `<a class="cp-compare-bug${issue.state === "closed" ? " cp-compare-bug--closed" : ""}"` +
                ` href="https://github.com/yschimke/wear-m3-catalog/issues/${issue.number}"` +
                ` rel="noopener" title="${issue.state} · #${issue.number} ${issue.title}">#${issue.number}</a>`,
        )
        .join("");
    return (
        `<td class="cp-compare-bugs">${links}` +
        `<a class="cp-compare-bug-new" href="${detail}"` +
        ' title="Report what is wrong with this comparison">+&#8202;file</a></td>'
    );
}

/**
 * The page as `ServeWeb` now serves it: the published score on each row, the rows worst-first, and
 * the Bugs column joined from the catalog's issue index.
 *
 * Rewritten here rather than regenerated from Kotlin because the fixture is a trimmed capture of a
 * catalog this repository does not carry. The shape mirrors `comparisonPage`'s emitter, and the
 * tests in `ServeWebTest` are what hold that emitter to it.
 */
function published(html, scores) {
    const rows = [
        ...html.matchAll(/<tr class="cp-compare-row"[\s\S]*?<\/tr>/g),
    ].map((match) => match[0]);
    const rewritten = rows
        .map((row) => {
            const score = scores[labelOf(row)];
            // Seated after `data-preview-ids`, which every row carries — the attribute order after
            // it varies by what the row has, so anchoring on the last one silently skipped rows.
            const scored =
                typeof score === "number" &&
                Number.isFinite(score) &&
                score >= 0
                    ? row.replace(
                          /(data-preview-ids="[^"]*")/,
                          `$1 data-match-neutral="${score.toFixed(2)}"`,
                      )
                    : row;
            return scored.replace("</tr>", `${bugCell(row)}\n</tr>`);
        })
        .sort((a, b) => matchOf(a) - matchOf(b));
    // Two passes with placeholders between them, so a rewritten row can never be matched again by
    // the pass that is still seating the others.
    let next = html;
    rows.forEach((row, index) => {
        next = next.replace(row, `<!--row:${index}-->`);
    });
    rewritten.forEach((row, index) => {
        next = next.replace(`<!--row:${index}-->`, () => row);
    });
    return next
        .replace(
            "<th>Match</th>",
            '<th>Match</th><th class="cp-compare-bugs-head">Bugs</th>',
        )
        .replace('id="cp-compare"', 'id="cp-compare" data-has-bugs="1"');
}

const scores = before ? {} : await measure();
const context = await newContext();
const page = await context.newPage();
// Hold the scorer open, so the shot catches the wall in the state a visitor lands in rather than
// racing a pass that has already finished. Everything else — the pictures, the seeding, the order —
// runs exactly as it does on the served page.
await page.addInitScript(() => {
    const held = new Set([
        "scoreSvgUrls",
        "scoreImageUrls",
        "scoreImages",
        "scoreCanvas",
        "normaliseImageUrls",
    ]);
    let real;
    Object.defineProperty(window, "ComposePreviewCompare", {
        configurable: true,
        get: () =>
            real &&
            new Proxy(real, {
                get: (target, key) => {
                    const value = target[key];
                    if (typeof value !== "function") return value;
                    if (held.has(key)) return () => new Promise(() => {});
                    return value.bind(target);
                },
            }),
        set: (value) => {
            real = value;
        },
    });
});
if (!before) {
    const source = fs.readFileSync(path.join(fixture, "page.html"), "utf8");
    const body = published(source, scores);
    await page.route("**/page.html*", (route) =>
        route.fulfill({ contentType: "text/html", body }),
    );
}
await page.goto(url, { waitUntil: "networkidle" });
await page.locator('[data-compare-format="reference"]').click();
// Only the pictures are waited for. The scores are held open on purpose.
await page.waitForFunction(() =>
    [...document.images].every((image) => image.complete),
);
await page.waitForTimeout(400);
await page.locator("#cp-compare-formats").screenshot({ path: out });
await context.close();
await browser.close();
server.close();
console.log("wrote", out);
