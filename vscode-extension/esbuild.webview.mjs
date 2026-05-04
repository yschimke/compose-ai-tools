import { build, context } from "esbuild";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = dirname(fileURLToPath(import.meta.url));
const watch = process.argv.includes("--watch");

const common = {
    bundle: true,
    format: "iife",
    platform: "browser",
    target: ["es2022"],
    sourcemap: true,
    logLevel: "info",
    legalComments: "none",
    define: { "process.env.NODE_ENV": '"production"' },
    minify: !watch,
};

const entries = [
    {
        in: resolve(root, "src/webview/preview/main.ts"),
        out: resolve(root, "media/webview/preview.js"),
    },
    {
        in: resolve(root, "src/webview/history/main.ts"),
        out: resolve(root, "media/webview/history.js"),
    },
];

if (watch) {
    for (const entry of entries) {
        const ctx = await context({
            ...common,
            entryPoints: [entry.in],
            outfile: entry.out,
        });
        await ctx.watch();
    }
    console.log("[esbuild] watching webview bundles…");
} else {
    await Promise.all(
        entries.map((entry) =>
            build({
                ...common,
                entryPoints: [entry.in],
                outfile: entry.out,
            }),
        ),
    );
}
