// Screenshot the `/compare` wall's reference lane, with and without the middle diff column.
//
// The fixture is a real `?format=reference` wall — the page `ServeWeb` emits, trimmed to six rows,
// beside the six render/design-reference pairs those rows point at. Nothing here is drawn by the
// script: the delta maps in the middle column are painted by `format-compare.js` from the committed
// artwork, which is the whole point of shooting this surface rather than describing it.
//
//   node shoot.mjs after.png            # the wall as this branch serves it
//   node shoot.mjs before.png --before  # the same wall with the diff column taken back out
//
// `--before` strips the cells and restores the pre-change panel width rather than checking out the
// old assets: the component before this change differed only in never painting a canvas that did
// not exist, and the stylesheet only in not narrowing the reference lane's panels to make room for
// a third one. Verified against a shot of the same six rows taken with the DEPLOYED assets from
// preview.coo.ee, which is pixel-identical to what this produces.
import { chromium } from "playwright";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(here, "fixture");
const assets = path.resolve(
    here,
    "../../../serve/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";

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
    const file = url.startsWith("/assets/")
        ? path.join(assets, url.slice("/assets/".length))
        : path.join(fixture, url);
    const root = url.startsWith("/assets/") ? assets : fixture;
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
await new Promise((resolve) => server.listen(8791, resolve));

const browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium",
});
// Dark, because this catalog is dark-first and that is the ground its references were drawn on.
const context = await browser.newContext({
    viewport: { width: 1180, height: 1400 },
    deviceScaleFactor: 2,
    colorScheme: "dark",
});
const page = await context.newPage();
await page.goto("http://127.0.0.1:8791/page.html?format=reference", {
    waitUntil: "networkidle",
});
if (before) {
    await page.evaluate(() => {
        for (const cell of document.querySelectorAll(
            ".cp-compare-diff-cell, .cp-compare-diff-head",
        ))
            cell.remove();
    });
    await page.addStyleTag({
        content:
            '#cp-compare[data-format="reference"] .cp-compare-shot' +
            " { width: min(260px, 30vw); min-width: 150px; }",
    });
}
await page.locator('[data-compare-format="reference"]').click();
// Every visible row has to have finished scoring, or the shot catches a wall mid-run.
await page.waitForFunction(() => {
    const scores = [
        ...document.querySelectorAll(
            ".cp-compare-row:not([hidden]) .cp-compare-score",
        ),
    ];
    return (
        scores.length > 0 &&
        scores.every((cell) => /%|unavailable/.test(cell.textContent ?? ""))
    );
});
await page.waitForTimeout(600);
await page.locator("#cp-compare-formats").screenshot({ path: out });
await browser.close();
server.close();
console.log("wrote", out);
