// Static file server for the preview-server harness: serves this directory's fixture pages and
// the serve viewer's own CSS/JS so a capture exercises the real assets.
//
// A copy of `vscode-extension/preview-harness/_server.mjs`, rooted here instead of at the
// extension. Copied rather than shared for the same reason as `_themes.mjs`: this directory
// travels to the preview-server repo, and importing across `vscode-extension/` would be a new
// cross-boundary dependency. The two servers answer to different roots and different fixtures;
// what they share is 100 lines of MIME table and `createServer` boilerplate, which is a cheaper
// duplicate than a coupling the split has to unpick later.
//
// Note what the ORIGINAL already did: it resolved the serve viewer's assets out of
// `cli/serve/src/main/resources/.../serve/assets`. The extension's harness server was serving serve's
// stylesheets — one more way the misfiling showed.

import { fileURLToPath } from "node:url";
import {
    dirname,
    resolve,
    relative,
    normalize,
    sep,
    isAbsolute,
} from "node:path";
import { readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const harnessDir = dirname(fileURLToPath(import.meta.url));
export const harnessRoot = resolve(harnessDir, "..");

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

// The CLI viewer's CSS/JS, resolved from this module rather than from the server root so it holds
// however the harness is booted (in-process or standalone).
const SERVE_ASSETS_DIR = resolve(
    harnessDir,
    "../../cli/serve/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);

export function startServer(root, port = 0) {
    return new Promise((resolveServer) => {
        const server = createServer(async (req, res) => {
            try {
                const url = new URL(req.url, "http://localhost");
                const rel = decodeURIComponent(url.pathname).replace(
                    /^\/+/,
                    "",
                );
                // Serve-page fixtures embed the CLI viewer's hashed asset URLs
                // (`/assets/serve/<hash>/serve.css`). Those live in the CLI's resources, not under
                // the extension root, so without this they 404 — which is why every `serve-*` page
                // capture has been rendering unstyled and with no JS at all, making the captures
                // far weaker evidence than they look (a JS-driven surface could regress or be
                // deleted and the capture would not move). The hash is cache-busting and changes
                // whenever the asset does, so match on the basename and ignore it.
                const assetMatch = /^assets\/serve\/[^/]+\/([^/]+)$/.exec(rel);
                if (assetMatch) {
                    const name = assetMatch[1];
                    const assetPath = resolve(SERVE_ASSETS_DIR, name);
                    // Check the RESOLVED path, not the shape of the input. Rejecting `..` and `/`
                    // only covers the escapes you thought of: on Windows `%5C` decodes to `\`,
                    // which the pattern above happily accepts, and `resolve()` then treats
                    // `C:\Users\…` as absolute and silently leaves this directory. Asking whether
                    // the result is still inside SERVE_ASSETS_DIR is platform-independent and does
                    // not depend on enumerating attack shapes — the same containment test the
                    // static handler below already uses.
                    const within = relative(SERVE_ASSETS_DIR, assetPath);
                    if (
                        within.startsWith("..") ||
                        within === "" ||
                        isAbsolute(within)
                    ) {
                        res.writeHead(403);
                        res.end("forbidden");
                        return;
                    }
                    try {
                        const body = await readFile(assetPath);
                        const ext = name.slice(name.lastIndexOf("."));
                        res.writeHead(200, {
                            "content-type":
                                mimeByExt[ext] ?? "application/octet-stream",
                            "cache-control": "no-store",
                        });
                        res.end(body);
                        return;
                    } catch {
                        res.writeHead(404);
                        res.end("not found: " + rel);
                        return;
                    }
                }
                const target = normalize(resolve(root, rel));
                if (
                    relative(root, target).startsWith("..") ||
                    target === root + sep + ".." // safety
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
                // Don't echo the error (stack trace / internal paths) back
                // to the client — log it server-side and return a generic
                // 500. (CodeQL: information exposure through a stack trace.)
                console.error("[harness] request error:", err);
                res.writeHead(500);
                res.end("internal server error");
            }
        });
        server.listen(port, "127.0.0.1", () => {
            const addr = server.address();
            resolveServer({
                origin: `http://127.0.0.1:${addr.port}`,
                port: addr.port,
                close: () => new Promise((r) => server.close(r)),
            });
        });
    });
}

// Standalone entry point for Playwright's `webServer`.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const port = Number(process.env.HARNESS_PORT ?? 5599);
    const { origin } = await startServer(harnessRoot, port);
    // Playwright polls the configured `url`; this log is just for humans.
    console.log(`[harness] serving ${harnessRoot} at ${origin}`);
}
