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
    // Without this, esbuild falls back to the project-root tsconfig.json
    // (the host config — no `experimentalDecorators`) and compiles the
    // Lit `@customElement` / `@state()` decorators as TC39 standard
    // decorators. Lit 3 supports both modes but the source uses the
    // experimental syntax (plain class fields, no `accessor` keyword), so
    // mismatched compilation silently breaks element registration and
    // `@state` reactivity — the panel renders blank. Pinning the webview
    // tsconfig keeps the bundle in experimental-decorator mode.
    tsconfig: resolve(root, "tsconfig.webview.json"),
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
