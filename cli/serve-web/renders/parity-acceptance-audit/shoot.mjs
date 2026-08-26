// Screenshot the design-parity dashboard's Known differences panel, with and without it.
//
// The fixture is the real page: `ServeWeb.parityPage` output for a catalog that publishes three
// acceptances, beside the `parity/` files the walk fetches. Nothing here is drawn by the script —
// every row is painted by `known-differences.js` running the shared acceptance engine over those
// committed bytes, which is the whole reason for shooting this surface rather than describing it.
//
//   node shoot.mjs after.png            # the dashboard as this branch serves it
//   node shoot.mjs before.png --before  # the same dashboard with the panel taken back out
//
// `--before` strips the band, its payload and the bundle's script tag rather than checking out the
// old assets: the page before this change differed from the fixture in exactly those three lines.
import { chromium } from "playwright";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(here, "fixture");
const assets = path.resolve(
    here,
    "../../../src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";

const types = {
    ".html": "text/html",
    ".css": "text/css",
    ".js": "text/javascript",
    ".json": "application/json",
    ".png": "image/png",
};

/** The page's asset hrefs are content-addressed (`/assets/serve/<hash>/<name>`); only the name matters here. */
const assetFile = (url) => path.join(assets, path.basename(url));

const server = http.createServer((request, response) => {
    const url = decodeURIComponent((request.url ?? "/").split("?")[0]);
    if (url === "/" || url === "/page.html") {
        let html = fs.readFileSync(path.join(fixture, "page.html"), "utf8");
        if (before) {
            html = html
                .replace(
                    /<div class="cp-acceptance-audit"[\s\S]*?<\/div>\n/,
                    "",
                )
                .replace(
                    /<script type="application\/json" id="cp-known-difference-audit">[\s\S]*?<\/script>\n/,
                    "",
                )
                .replace(
                    /<script src="[^"]*known-differences\.js"><\/script>\n/,
                    "",
                )
                .replace(/<cp-acceptance-audit><\/cp-acceptance-audit>/, "");
        }
        response.writeHead(200, { "content-type": "text/html" });
        response.end(html);
        return;
    }
    const file = url.startsWith("/assets/")
        ? assetFile(url)
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
await new Promise((resolve) => server.listen(8793, resolve));

const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || undefined,
});
const page = await browser.newPage({
    viewport: { width: 1024, height: 900 },
    deviceScaleFactor: 2,
});
await page.goto("http://127.0.0.1:8793/", { waitUntil: "networkidle" });
// The panel is painted by the browser after it has fetched the document and every artifact, so wait
// for the band itself rather than for a timer.
if (!before) {
    await page.waitForSelector("#cp-acceptance-audit:not([hidden])", {
        timeout: 10_000,
    });
}
await page.screenshot({ path: path.join(here, out), fullPage: true });
await browser.close();
server.close();
console.log(`wrote ${out}`);
