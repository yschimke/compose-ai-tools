import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { PNG } from "pngjs";

import {
  FigmaRestRasterizer,
  parseFigmaRef,
} from "./figma-rest-raster.mjs";

function json(value, init) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

function onePixelPng() {
  const png = new PNG({ width: 1, height: 1 });
  png.data.set([1, 2, 3, 255]);
  return PNG.sync.write(png);
}

test("parseFigmaRef preserves node ids containing colons", () => {
  assert.deepEqual(parseFigmaRef("figma:file/57994:2227"), {
    fileKey: "file",
    nodeId: "57994:2227",
  });
  assert.equal(parseFigmaRef("design/button.png"), null);
});

test("batches nodes and equal-scale image exports, then caches duplicate rasters", async () => {
  const calls = [];
  const png = onePixelPng();
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/nodes?")) {
      const ids = new URL(url).searchParams.get("ids").split(",");
      return json({
        nodes: Object.fromEntries(
          ids.map((id, index) => [
            id,
            { document: { absoluteBoundingBox: { width: index === 0 ? 10 : 20 } }, styles: {} },
          ]),
        ),
      });
    }
    if (url.includes("/images/")) {
      const ids = new URL(url).searchParams.get("ids").split(",");
      return json({ images: Object.fromEntries(ids.map((id) => [id, `https://cdn.test/${id}`])) });
    }
    return new Response(png, { status: 200, headers: { "content-type": "image/png" } });
  };
  const rasterizer = new FigmaRestRasterizer({ token: "token", fetchImpl });
  const requests = [
    { ref: "figma:file/1:1", target: { width: 20, height: 20, density: 2.625 } },
    { ref: "figma:file/2:2", target: { width: 40, height: 40, density: 2.625 } },
    { ref: "figma:file/1:1", target: { width: 20, height: 20, density: 2.625 } },
  ];

  await rasterizer.prepare(requests);
  await Promise.all(requests.map(({ ref, target }) => rasterizer.rasterize(ref, target)));
  await rasterizer.rasterize(requests[0].ref, requests[0].target);

  assert.equal(calls.filter((url) => url.includes("/nodes?")).length, 1);
  assert.equal(calls.filter((url) => url.includes("/images/")).length, 1);
  assert.equal(calls.filter((url) => url.includes("cdn.test")).length, 2);
  assert.match(calls.find((url) => url.includes("/nodes?")), /ids=1%3A1%2C2%3A2/);
  assert.match(
    calls.find((url) => url.includes("/images/")),
    /scale=2.625/,
  );
  assert.equal(
    new URL(calls.find((url) => url.includes("/images/"))).searchParams.get("contents_only"),
    "true",
  );
});

test("can include overlapping Figma layers such as component-sheet backgrounds", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/nodes?")) {
      return json({ nodes: { "1:1": { document: { absoluteBoundingBox: { width: 10 } } } } });
    }
    if (url.includes("/images/")) return json({ images: { "1:1": "https://cdn.test/1:1" } });
    return new Response(onePixelPng(), { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({
    token: "token",
    fetchImpl,
    contentsOnly: false,
  });

  await rasterizer.rasterize("figma:file/1:1", {
    width: 20,
    height: 20,
    density: 2.625,
  });

  const imageUrl = calls.find((url) => url.includes("/images/"));
  assert.equal(new URL(imageUrl).searchParams.get("contents_only"), "false");
});

test("exports at renderer density instead of scaling a tight node to the padded canvas", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/nodes?")) {
      return json({ nodes: { "1:1": { document: { absoluteBoundingBox: { width: 95 } } } } });
    }
    if (url.includes("/images/")) return json({ images: { "1:1": "https://cdn.test/1:1" } });
    return new Response(onePixelPng(), { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({ token: "token", fetchImpl });

  await rasterizer.rasterize("figma:file/1:1", {
    width: 382,
    height: 210,
    density: 2.625,
  });

  const imageUrl = calls.find((url) => url.includes("/images/"));
  assert.equal(new URL(imageUrl).searchParams.get("scale"), "2.625");
  assert.notEqual(new URL(imageUrl).searchParams.get("scale"), "4");
});

test("honours an explicit preview renderer density", async () => {
  const rasterizer = new FigmaRestRasterizer({ token: "token", fetchImpl: async () => json({}) });
  const parsed = parseFigmaRef("figma:file/1:1");
  rasterizer.nodes.set(rasterizer.nodeKey(parsed), { document: { absoluteBoundingBox: { width: 95 } } });
  assert.equal(rasterizer.scaleFor(parsed, { width: 382, height: 210, density: 3 }), 3);
  assert.equal(
    rasterizer.scaleFor(parsed, { width: 382, height: 210, density: 3, boardDensity: 3 }),
    1,
  );
  assert.throws(
    () => rasterizer.scaleFor(parsed, { width: 382, height: 210, density: 5 }),
    /exceeds the API maximum/,
  );
});

test("retries a rate-limited batch once and respects Retry-After", async () => {
  const sleeps = [];
  let nodeAttempts = 0;
  const png = onePixelPng();
  const fetchImpl = async (url) => {
    if (url.includes("/nodes?")) {
      nodeAttempts += 1;
      if (nodeAttempts === 1) return new Response("limited", { status: 429, headers: { "retry-after": "0" } });
      return json({ nodes: { "1:1": { document: { absoluteBoundingBox: { width: 10 } } } } });
    }
    if (url.includes("/images/")) return json({ images: { "1:1": "https://cdn.test/1:1" } });
    return new Response(png, { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({
    token: "token",
    fetchImpl,
    sleep: async (millis) => sleeps.push(millis),
  });

  const raster = await rasterizer.rasterize("figma:file/1:1", {
    width: 20,
    height: 20,
    density: 2.625,
  });

  assert.equal(nodeAttempts, 2);
  assert.deepEqual(sleeps, [0]);
  assert.equal(raster.width, 1);
});

/** A design-parity reference cache holding structure for the given node ids. */
function referenceCache(fileKey, nodeIds) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "reference-cache-"));
  for (const nodeId of nodeIds) {
    const entry = path.join(dir, fileKey, nodeId.replace(/:/g, "-"));
    fs.mkdirSync(entry, { recursive: true });
    fs.writeFileSync(
      path.join(entry, "node.json"),
      JSON.stringify({ document: { absoluteBoundingBox: { width: 10 } }, styles: {} }),
    );
  }
  return dir;
}

test("reads node structure from a reference cache instead of the nodes endpoint", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/images/")) return json({ images: { "1:1": "https://cdn.test/1:1" } });
    return new Response(onePixelPng(), { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({
    token: "token",
    fetchImpl,
    cacheDir: referenceCache("file", ["1:1"]),
  });
  const target = { width: 20, height: 20, density: 2.625 };

  const raster = await rasterizer.rasterize("figma:file/1:1", target);

  // The structure came off disk, so the rate-limited nodes endpoint is never asked.
  assert.equal(calls.filter((url) => url.includes("/nodes?")).length, 0);
  // Pixels still come from Figma's own renderer: the published `match` score is
  // computed from them, so they are deliberately NOT taken from the cache's SVG.
  assert.equal(calls.filter((url) => url.includes("/images/")).length, 1);
  assert.deepEqual(raster.document, { absoluteBoundingBox: { width: 10 } });
});

test("falls through to the nodes endpoint when the cache misses", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/nodes?")) {
      return json({ nodes: { "2:2": { document: { absoluteBoundingBox: { width: 10 } } } } });
    }
    if (url.includes("/images/")) return json({ images: { "2:2": "https://cdn.test/2:2" } });
    return new Response(onePixelPng(), { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({
    token: "token",
    fetchImpl,
    // Holds a different node, so `2:2` is a miss.
    cacheDir: referenceCache("file", ["1:1"]),
  });

  await rasterizer.rasterize("figma:file/2:2", { width: 20, height: 20, density: 2.625 });

  // A miss is not fatal here — unlike the parity run, where the cache is
  // authoritative. It just costs the request it would have cost anyway.
  assert.equal(calls.filter((url) => url.includes("/nodes?")).length, 1);
});

test("ignores a cacheDir that does not exist", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes("/nodes?")) {
      return json({ nodes: { "1:1": { document: { absoluteBoundingBox: { width: 10 } } } } });
    }
    if (url.includes("/images/")) return json({ images: { "1:1": "https://cdn.test/1:1" } });
    return new Response(onePixelPng(), { status: 200 });
  };
  const rasterizer = new FigmaRestRasterizer({
    token: "token",
    fetchImpl,
    cacheDir: "/nope/not/a/cache",
  });

  await rasterizer.rasterize("figma:file/1:1", { width: 20, height: 20, density: 2.625 });

  assert.equal(calls.filter((url) => url.includes("/nodes?")).length, 1);
});
