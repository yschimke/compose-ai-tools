import fs from "node:fs";
import path from "node:path";

import { PNG } from "pngjs";

/** `figma:<fileKey>/<nodeId>` -> `{ fileKey, nodeId }`, or null. */
export function parseFigmaRef(ref) {
  const match = /^figma:([^/]+)\/(.+)$/.exec(String(ref ?? ""));
  return match ? { fileKey: match[1], nodeId: match[2] } : null;
}

function chunks(values, size) {
  const out = [];
  for (let start = 0; start < values.length; start += size) {
    out.push(values.slice(start, start + size));
  }
  return out;
}

function retryDelay(response, attempt) {
  const retryAfter = response.headers.get("retry-after");
  if (retryAfter) {
    const seconds = Number(retryAfter);
    if (Number.isFinite(seconds)) return Math.min(30_000, Math.max(0, seconds * 1_000));
    const at = Date.parse(retryAfter);
    if (Number.isFinite(at)) return Math.min(30_000, Math.max(0, at - Date.now()));
  }
  return Math.min(8_000, 1_000 * 2 ** attempt);
}

function entryFor(nodes, nodeId) {
  return nodes?.[nodeId] ?? nodes?.[nodeId.replace("-", ":")];
}

function imageFor(images, nodeId) {
  return images?.[nodeId] ?? images?.[nodeId.replace("-", ":")];
}

/**
 * Batched, run-scoped Figma REST rasterizer.
 *
 * Node metadata is fetched once per file in chunks, image exports are fetched once per
 * file/scale in chunks, and downloaded PNGs are memoized by node/scale. A catalog with dozens of
 * references therefore spends a handful of Figma API requests instead of two requests per node.
 */
export class FigmaRestRasterizer {
  constructor({
    token,
    fetchImpl = globalThis.fetch,
    sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis)),
    batchSize = 50,
    maxAttempts = 5,
    contentsOnly = true,
    /**
     * A design-parity reference cache checkout (the `design-parity/reference`
     * branch). When set, node STRUCTURE is read from `<fileKey>/<nodeId>/node.json`
     * instead of `/v1/files/:key/nodes`, which is the same JSON that endpoint
     * returns — the import wrote it from exactly that response.
     *
     * Images are deliberately NOT read from it. The cache holds a
     * resolution-free `image.svg`, but this rasterizer needs a PNG at a density
     * derived per record, and the published `match` score is computed from these
     * very pixels. Rasterising the SVG locally would move every score in the
     * manifest, so pixels stay on Figma's own renderer until that is a decision
     * somebody makes deliberately.
     */
    cacheDir = "",
  }) {
    this.token = token;
    this.cacheDir = cacheDir;
    this.cacheIndex = undefined;
    this.cacheCurrent = new Map();
    this.fetchImpl = fetchImpl;
    this.sleep = sleep;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.contentsOnly = contentsOnly;
    this.nodes = new Map();
    this.imageUrls = new Map();
    this.rasters = new Map();
  }

  async fetchWithRetry(url, options, label) {
    let response;
    for (let attempt = 0; attempt < this.maxAttempts; attempt += 1) {
      response = await this.fetchImpl(url, options);
      if (response.ok) return response;
      const retryable = response.status === 429 || response.status >= 500;
      if (!retryable || attempt + 1 >= this.maxAttempts) break;
      await this.sleep(retryDelay(response, attempt));
    }
    throw new Error(`${label} ${response?.status ?? "request failed"}`);
  }

  headers() {
    return { "X-Figma-Token": this.token };
  }

  nodeKey({ fileKey, nodeId }) {
    return `${fileKey}/${nodeId}`;
  }

  /**
   * The cached structure for a node, or null for a miss.
   *
   * A miss is not an error and never fatal: the node simply falls through to the
   * API below, exactly as it did before a cache existed. That keeps a partial or
   * absent cache a matter of how many requests this run makes, never of what it
   * can publish — the opposite of the parity run, where the cache is
   * authoritative and a miss is reported.
   *
   * Node ids contain a colon (`1:42`); the cache spells directories with a dash
   * (`1-42`), the same way Figma's own URLs do.
   */
  /** The file `version` the cache was imported at, or null. */
  cacheVersion(fileKey) {
    if (!this.cacheDir) return null;
    if (this.cacheIndex === undefined) {
      try {
        this.cacheIndex = JSON.parse(
          fs.readFileSync(path.join(this.cacheDir, "index.json"), "utf8"),
        );
      } catch {
        this.cacheIndex = null;
      }
    }
    return this.cacheIndex?.files?.[fileKey]?.version ?? null;
  }

  /**
   * Whether the cache still describes the revision the images endpoint will
   * render. Memoized per file: one request, however many nodes it covers.
   *
   * Anything unclear is a NO — no index, no version for the file, a request that
   * failed. The cache is an optimisation here, so the safe answer when freshness
   * cannot be established is to fetch structure live, exactly as before.
   */
  async cacheIsCurrent(fileKey) {
    if (!this.cacheDir) return false;
    const known = this.cacheCurrent.get(fileKey);
    if (known !== undefined) return known;
    let current = false;
    const cached = this.cacheVersion(fileKey);
    if (cached) {
      try {
        const response = await this.fetchWithRetry(
          `https://api.figma.com/v1/files/${encodeURIComponent(fileKey)}?depth=1`,
          { headers: this.headers() },
          "figma file version",
        );
        const json = await response.json();
        current = Boolean(json?.version) && String(json.version) === String(cached);
      } catch {
        current = false;
      }
    }
    this.cacheCurrent.set(fileKey, current);
    return current;
  }

  nodeFromCache({ fileKey, nodeId }) {
    if (!this.cacheDir) return null;
    const file = path.join(this.cacheDir, fileKey, nodeId.replace(/:/g, "-"), "node.json");
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return null;
    }
  }

  scaleFor(parsed, target) {
    const entry = this.nodes.get(this.nodeKey(parsed));
    if (entry instanceof Error) throw entry;
    const requested = target?.density;
    if (typeof requested !== "number" || !Number.isFinite(requested) || requested <= 0) {
      throw new Error("catalog image is missing its renderer density");
    }
    const boardDensity = target?.boardDensity ?? 1;
    if (typeof boardDensity !== "number" || !Number.isFinite(boardDensity) || boardDensity <= 0) {
      throw new Error("Figma board density must be a positive number");
    }
    const raw = requested / boardDensity;
    if (raw > 4) {
      throw new Error(`Figma export scale ${raw} exceeds the API maximum of 4`);
    }
    // Four decimals keep requests stable and batch components rendered at the same density while
    // remaining far below a physical pixel of error at Figma's maximum scale.
    return Number(Math.max(0.01, raw).toFixed(4));
  }

  imageKey(parsed, target) {
    return `${this.nodeKey(parsed)}@${this.scaleFor(parsed, target)}`;
  }

  async prepare(requests) {
    const parsedRequests = requests
      .map(({ ref, target }) => ({ parsed: parseFigmaRef(ref), target }))
      .filter(({ parsed }) => parsed);

    const missingByFile = new Map();
    for (const { parsed } of parsedRequests) {
      const key = this.nodeKey(parsed);
      if (this.nodes.has(key)) continue;
      const ids = missingByFile.get(parsed.fileKey) ?? new Set();
      ids.add(parsed.nodeId);
      missingByFile.set(parsed.fileKey, ids);
    }

    // Serve what the cache can — but only for a file whose revision it still
    // describes. Structure and pixels have to come from the SAME revision: the
    // images request below is unversioned and always renders the file as it is
    // now, while the cache holds whatever the last import saw. Mixing them
    // publishes annotations and finding anchors measured against one raster on
    // top of a different one, which is worse than either being stale.
    //
    // The check is one request per FILE (`?depth=1` for its `version`), against
    // ~one per 50 nodes it saves — the same version gate the import itself uses
    // to make an unchanged kit cost a single request.
    if (this.cacheDir) {
      for (const [fileKey, ids] of [...missingByFile]) {
        // Read from disk BEFORE paying for the revision check. A file listed in
        // `index.json` whose requested nodes are not actually cached — a partial
        // import, a node added to the map since — would otherwise cost a
        // `?depth=1` request that cannot enable a single hit, turning a
        // one-request miss into two. Worse under a 429: `fetchWithRetry` would
        // spend its whole backoff budget on a question whose answer is moot.
        const hits = new Map();
        for (const nodeId of ids) {
          const cached = this.nodeFromCache({ fileKey, nodeId });
          if (cached) hits.set(nodeId, cached);
        }
        if (hits.size === 0) continue;
        if (!(await this.cacheIsCurrent(fileKey))) continue;
        for (const [nodeId, cached] of hits) {
          this.nodes.set(`${fileKey}/${nodeId}`, cached);
          ids.delete(nodeId);
        }
        if (ids.size === 0) missingByFile.delete(fileKey);
      }
    }

    for (const [fileKey, ids] of missingByFile) {
      for (const batch of chunks([...ids], this.batchSize)) {
        try {
          const url =
            `https://api.figma.com/v1/files/${encodeURIComponent(fileKey)}/nodes` +
            `?ids=${encodeURIComponent(batch.join(","))}`;
          const response = await this.fetchWithRetry(url, { headers: this.headers() }, "figma nodes");
          const json = await response.json();
          for (const nodeId of batch) {
            this.nodes.set(
              `${fileKey}/${nodeId}`,
              entryFor(json?.nodes, nodeId) ?? new Error(`figma nodes returned no node ${nodeId}`),
            );
          }
        } catch (error) {
          for (const nodeId of batch) this.nodes.set(`${fileKey}/${nodeId}`, error);
        }
      }
    }

    const missingImages = new Map();
    for (const { parsed, target } of parsedRequests) {
      let scale;
      try {
        scale = this.scaleFor(parsed, target);
      } catch {
        continue;
      }
      const key = `${this.nodeKey(parsed)}@${scale}`;
      if (this.imageUrls.has(key)) continue;
      const groupKey = `${parsed.fileKey}\0${scale}`;
      const group = missingImages.get(groupKey) ?? { fileKey: parsed.fileKey, scale, ids: new Set() };
      group.ids.add(parsed.nodeId);
      missingImages.set(groupKey, group);
    }

    for (const { fileKey, scale, ids } of missingImages.values()) {
      for (const batch of chunks([...ids], this.batchSize)) {
        try {
          const url =
            `https://api.figma.com/v1/images/${encodeURIComponent(fileKey)}` +
            `?ids=${encodeURIComponent(batch.join(","))}&format=png&scale=${scale}` +
            `&contents_only=${this.contentsOnly}`;
          const response = await this.fetchWithRetry(url, { headers: this.headers() }, "figma images");
          const json = await response.json();
          for (const nodeId of batch) {
            this.imageUrls.set(
              `${fileKey}/${nodeId}@${scale}`,
              imageFor(json?.images, nodeId) ??
                new Error(`figma images returned no url for the node ${nodeId}`),
            );
          }
        } catch (error) {
          for (const nodeId of batch) this.imageUrls.set(`${fileKey}/${nodeId}@${scale}`, error);
        }
      }
    }
  }

  async rasterize(ref, target) {
    const parsed = parseFigmaRef(ref);
    if (!parsed) throw new Error(`not a figma ref: ${ref}`);
    await this.prepare([{ ref, target }]);
    const node = this.nodes.get(this.nodeKey(parsed));
    if (node instanceof Error) throw node;
    const key = this.imageKey(parsed, target);
    const url = this.imageUrls.get(key);
    if (url instanceof Error) throw url;
    if (!url) throw new Error("figma images returned no url for the node");

    if (!this.rasters.has(key)) {
      this.rasters.set(
        key,
        (async () => {
          const response = await this.fetchWithRetry(url, {}, "figma image download");
          const decoded = PNG.sync.read(Buffer.from(await response.arrayBuffer()));
          return {
            width: decoded.width,
            height: decoded.height,
            data: decoded.data,
            document: node?.document,
            styles: node?.styles,
            // This export was requested at the renderer's density. The emitter must preserve that
            // scale when it adds the preview canvas around the tight Figma component.
            preserveScale: true,
          };
        })(),
      );
    }
    return this.rasters.get(key);
  }
}
