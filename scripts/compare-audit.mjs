#!/usr/bin/env node
// Bulk fidelity audit for the serve `/compare` page.
//
// Drives the real comparison page in Chromium and scrapes the score every row settles on, so the
// numbers come from the shipped scorer rather than a reimplementation of it. That matters: the
// scorer is the thing under test, and a second copy of the edge-tolerant metric would be free to be
// wrong in the same direction as the first.
//
// Two phases, because an audit that re-downloads the artifacts on every run is neither reproducible
// nor kind to a shared preview server:
//
//   mirror — pull one catalog's compare page and every artifact it references onto disk.
//   run    — replay that mirror from a local static server and scrape the settled scores.
//
// `run --patch <file>` swaps in a local `format-compare.js` via request interception, so a scorer
// change is measured against byte-identical artifacts before and after. That is the whole point:
// A/B a scoring change without deploying it.
//
// Usage:
//   node scripts/compare-audit.mjs mirror --all --dir .audit-mirror
//   node scripts/compare-audit.mjs run --dir .audit-mirror --format reference
//   node scripts/compare-audit.mjs run --dir .audit-mirror --format reference \
//     --patch cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js
//
// Requires `playwright` on NODE_PATH (the vscode-extension dev dependency, or a standalone install).

import { createReadStream, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";

const require = createRequire(import.meta.url);

const FORMATS = new Set(["svg", "rc", "reference"]);
const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".rc": "application/octet-stream",
  ".wasm": "application/wasm",
};

function extensionOf(path) {
  const dot = path.lastIndexOf(".");
  return dot < 0 ? "" : path.slice(dot).toLowerCase();
}

function loadChromium() {
  for (const specifier of ["playwright", "@playwright/test", "playwright-core"]) {
    try {
      return require(specifier).chromium;
    } catch {
      // Try the next one — only the final failure is worth reporting.
    }
  }
  throw new Error(
    "playwright not found. Install it (npm i playwright) and re-run with NODE_PATH set to its node_modules."
  );
}

function parseArgs(argv) {
  const command = argv[0];
  if (command !== "mirror" && command !== "run") {
    throw new Error("first argument must be `mirror` or `run`");
  }
  const args = {
    command,
    base: "https://preview.coo.ee",
    dir: ".audit-mirror",
    catalogs: [],
    all: false,
    format: "svg",
    themes: ["light"],
    patch: null,
    out: null,
    limit: 0,
    timeoutMs: 20 * 60 * 1000,
    concurrency: 3,
  };
  for (let i = 1; i < argv.length; i++) {
    const flag = argv[i];
    const value = () => argv[++i];
    if (flag === "--base") args.base = value().replace(/\/$/, "");
    else if (flag === "--dir") args.dir = value();
    else if (flag === "--catalogs") args.catalogs = value().split(",").map((s) => s.trim()).filter(Boolean);
    else if (flag === "--all") args.all = true;
    else if (flag === "--format") args.format = value();
    else if (flag === "--themes") args.themes = value().split(",").map((s) => s.trim()).filter(Boolean);
    else if (flag === "--patch") args.patch = value();
    else if (flag === "--out") args.out = value();
    else if (flag === "--limit") args.limit = Number(value());
    else if (flag === "--timeout-ms") args.timeoutMs = Number(value());
    else if (flag === "--concurrency") args.concurrency = Math.max(1, Number(value()));
    else throw new Error(`unknown flag: ${flag}`);
  }
  if (!FORMATS.has(args.format)) throw new Error(`--format must be one of ${[...FORMATS].join(", ")}`);
  if (args.command === "mirror" && !args.all && args.catalogs.length === 0) {
    throw new Error("mirror needs --catalogs a,b,c or --all");
  }
  return args;
}

// ---------------------------------------------------------------------------- mirror

/** The preview server's landing page links one path segment per published catalog. */
async function discoverCatalogs(base) {
  const html = await (await fetch(`${base}/`)).text();
  const reserved = new Set(["status", "version", "docs", "assets", "rc-player", "p", "compare", "render"]);
  const names = new Set();
  for (const match of html.matchAll(/href="\/([a-z0-9][a-z0-9-]*)\/?"/g)) {
    if (!reserved.has(match[1])) names.add(match[1]);
  }
  return [...names].sort();
}

/**
 * Every absolute-path asset the compare page needs: the chrome (css/js) plus one URL per artifact
 * the rows point at. Collected from the markup rather than reconstructed, so a new lane or a
 * renamed route is picked up without touching this script.
 */
function assetUrlsIn(html) {
  const urls = new Set();
  for (const match of html.matchAll(/\s(?:src|href)="(\/[^"]+)"/g)) {
    const url = match[1];
    if (CONTENT_TYPES[extensionOf(url.split("?")[0])]) urls.add(url);
  }
  for (const match of html.matchAll(/\sdata-(?:png|svg|rc|reference)-(?:light|dark|neutral)="(\/[^"]+)"/g)) {
    urls.add(match[1]);
  }
  return [...urls];
}

async function fetchInto(base, urlPath, dir) {
  // Artifact URLs may carry a session token; the path alone is the on-disk identity.
  const filePath = join(dir, decodeURIComponent(urlPath.split("?")[0]));
  if (existsSync(filePath)) return { skipped: true };
  const response = await fetch(`${base}${urlPath}`);
  if (!response.ok) return { failed: response.status };
  mkdirSync(dirname(filePath), { recursive: true });
  writeFileSync(filePath, Buffer.from(await response.arrayBuffer()));
  return { written: true };
}

/** Run `tasks` with at most `limit` in flight — the preview server is shared, so stay polite. */
async function pooled(tasks, limit) {
  const results = [];
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, tasks.length) }, async () => {
    while (next < tasks.length) {
      const index = next++;
      results[index] = await tasks[index]();
    }
  });
  await Promise.all(workers);
  return results;
}

async function mirror(args) {
  const dir = resolve(args.dir);
  const catalogs = args.all ? await discoverCatalogs(args.base) : args.catalogs;
  const mirrored = [];
  for (const catalog of catalogs) {
    const response = await fetch(`${args.base}/${catalog}/compare`);
    if (!response.ok) {
      console.log(`${catalog.padEnd(30)} skipped (compare ${response.status})`);
      continue;
    }
    const html = await response.text();
    const pagePath = join(dir, catalog, "compare.html");
    mkdirSync(dirname(pagePath), { recursive: true });
    writeFileSync(pagePath, html);
    const urls = assetUrlsIn(html);
    const outcomes = await pooled(
      urls.map((url) => () => fetchInto(args.base, url, dir).catch((error) => ({ failed: String(error) }))),
      args.concurrency * 2
    );
    const failed = outcomes.filter((o) => o.failed).length;
    console.log(
      `${catalog.padEnd(30)} ${String(urls.length).padStart(4)} assets` +
        `${failed ? ` (${failed} failed)` : ""}`
    );
    mirrored.push(catalog);
  }
  writeFileSync(
    join(dir, "mirror.json"),
    JSON.stringify({ base: args.base, catalogs: mirrored, mirroredAt: new Date().toISOString() }, null, 2)
  );
  console.log(`\nmirrored ${mirrored.length} catalog(s) into ${dir}`);
}

// ---------------------------------------------------------------------------- run

/**
 * Static server over the mirror. `/{catalog}/compare` maps to the saved page; everything else is a
 * plain path lookup, which is why the mirror keeps the server's own URL layout.
 */
function serveMirror(dir) {
  return new Promise((resolveServer) => {
    const server = createServer((request, response) => {
      const path = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname);
      const compare = /^\/([^/]+)\/compare\/?$/.exec(path);
      const filePath = compare ? join(dir, compare[1], "compare.html") : join(dir, path);
      if (!filePath.startsWith(dir) || !existsSync(filePath) || statSync(filePath).isDirectory()) {
        response.writeHead(404).end("not found");
        return;
      }
      response.writeHead(200, {
        "content-type": CONTENT_TYPES[extensionOf(filePath)] || "application/octet-stream",
        "cache-control": "no-store",
      });
      createReadStream(filePath).pipe(response);
    });
    server.listen(0, "127.0.0.1", () => resolveServer(server));
  });
}

/**
 * Scrape one catalog/format/theme. Resolves to `null` when the catalog publishes nothing in this
 * lane — a catalog without design references is not a failure, it just has no rows.
 */
async function auditOne(context, { origin, catalog, format, theme, patchSource, limit, timeoutMs }) {
  const page = await context.newPage();
  try {
    if (patchSource) {
      // The asset URL carries a content hash (`/assets/serve/<version>/format-compare.js`), so match
      // on the basename rather than the full path.
      await page.route("**/format-compare.js", (route) =>
        route.fulfill({ contentType: "text/javascript; charset=utf-8", body: patchSource })
      );
    }
    const url = `${origin}/${catalog}/compare?format=${encodeURIComponent(format)}&theme=${encodeURIComponent(theme)}`;
    const response = await page.goto(url, { waitUntil: "domcontentloaded", timeout: 60_000 });
    if (!response || !response.ok()) return null;
    const root = await page.$("#cp-compare");
    if (!root) return null;
    const attribute =
      format === "rc" ? "data-has-rc" : format === "reference" ? "data-has-reference" : "data-has-svg";
    if ((await root.getAttribute(attribute)) !== "1") return null;

    // Every visible row ends with a `data-score` attribute — `-1` when the comparison failed. Rows
    // with no artifact in this lane are hidden by the page itself and never scored.
    await page.waitForFunction(
      () => {
        const rows = [...document.querySelectorAll(".cp-compare-row")].filter((r) => !r.hidden);
        return rows.length > 0 && rows.every((r) => r.hasAttribute("data-score"));
      },
      undefined,
      { timeout: timeoutMs, polling: 500 }
    );

    let rows = await page.$$eval(".cp-compare-row", (nodes) =>
      nodes
        .filter((n) => !n.hidden)
        .map((n) => ({
          label: n.getAttribute("data-label") || "",
          score: Number(n.getAttribute("data-score")),
          // Reported only by a scorer that measures geometry separately; absent on older builds.
          geometry: n.hasAttribute("data-geometry-delta") ? Number(n.getAttribute("data-geometry-delta")) : null,
        }))
    );
    if (limit > 0) rows = rows.slice(0, limit);
    return { catalog, format, theme, rows };
  } catch (error) {
    return { catalog, format, theme, rows: [], error: String(error?.message || error) };
  } finally {
    await page.close();
  }
}

function summarise(rows) {
  const scored = rows.filter((r) => Number.isFinite(r.score) && r.score >= 0).map((r) => r.score);
  const sorted = [...scored].sort((a, b) => a - b);
  const at = (q) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] : null);
  return {
    rows: rows.length,
    scored: scored.length,
    unavailable: rows.length - scored.length,
    mean: scored.length ? scored.reduce((a, b) => a + b, 0) / scored.length : null,
    p10: at(0.1),
    median: at(0.5),
    min: sorted.length ? sorted[0] : null,
    below75: scored.filter((s) => s < 75).length,
    below90: scored.filter((s) => s < 90).length,
  };
}

function fmt(value) {
  return value === null || value === undefined ? "  —  " : value.toFixed(1).padStart(5);
}

async function run(args) {
  const dir = resolve(args.dir);
  if (!existsSync(join(dir, "mirror.json"))) {
    throw new Error(`no mirror at ${dir} — run \`compare-audit.mjs mirror --dir ${args.dir} --all\` first`);
  }
  const manifest = JSON.parse(readFileSync(join(dir, "mirror.json"), "utf8"));
  const catalogs = args.catalogs.length ? args.catalogs : manifest.catalogs;
  const patchSource = args.patch ? readFileSync(args.patch, "utf8") : null;

  const server = await serveMirror(dir);
  const origin = `http://127.0.0.1:${server.address().port}`;
  const chromium = loadChromium();
  // CI images often ship a Chromium build that does not match the npm package's pinned revision.
  const executablePath = process.env.COMPARE_AUDIT_CHROMIUM || undefined;
  const browser = await chromium.launch({
    ...(executablePath ? { executablePath } : {}),
    // The mirror is local, so no proxy and no TLS are involved; `--no-sandbox` is what lets this
    // run inside an unprivileged container.
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({ deviceScaleFactor: 1 });
  try {
    const jobs = [];
    for (const catalog of catalogs) {
      for (const theme of args.themes) {
        jobs.push(() =>
          auditOne(context, {
            origin,
            catalog,
            format: args.format,
            theme,
            patchSource,
            limit: args.limit,
            timeoutMs: args.timeoutMs,
          })
        );
      }
    }
    const settled = (await pooled(jobs, args.concurrency)).filter(Boolean);

    console.log(`\n${args.format} lane · ${patchSource ? `PATCHED (${args.patch})` : "as-built"} · ${dir}\n`);
    console.log("catalog                        theme   rows  scored   mean    p10    min  <75  <90");
    const all = [];
    for (const result of settled) {
      if (result.error) {
        console.log(`${result.catalog.padEnd(30)} ${result.theme.padEnd(6)} error: ${result.error}`);
        continue;
      }
      all.push(...result.rows.map((r) => ({ ...r, catalog: result.catalog, theme: result.theme })));
      const s = summarise(result.rows);
      console.log(
        `${result.catalog.padEnd(30)} ${result.theme.padEnd(6)} ${String(s.rows).padStart(4)} ` +
          `${String(s.scored).padStart(7)} ${fmt(s.mean)} ${fmt(s.p10)} ${fmt(s.min)} ` +
          `${String(s.below75).padStart(4)} ${String(s.below90).padStart(4)}`
      );
    }
    const overall = summarise(all);
    console.log(
      `\nTOTAL  rows=${overall.rows} scored=${overall.scored} unavailable=${overall.unavailable} ` +
        `mean=${fmt(overall.mean)} p10=${fmt(overall.p10)} median=${fmt(overall.median)} ` +
        `min=${fmt(overall.min)} <75=${overall.below75} <90=${overall.below90}\n`
    );
    if (args.out) {
      writeFileSync(
        args.out,
        JSON.stringify({ format: args.format, patched: !!patchSource, overall, rows: all }, null, 2)
      );
      console.log(`wrote ${args.out}`);
    }
  } finally {
    await browser.close();
    server.close();
  }
}

const args = parseArgs(process.argv.slice(2));
await (args.command === "mirror" ? mirror(args) : run(args));
