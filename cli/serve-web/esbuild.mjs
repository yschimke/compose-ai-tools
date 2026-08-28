// Bundles the serve web components to a single IIFE committed under the CLI's
// resources, where `ServeWebAssets` picks it up like any other static asset.
//
// Committed output, not a Gradle-driven build. `:cli` is a plain Kotlin/JVM
// module and the whole release chain (`installDist`, `distTar`, the GHCR image)
// runs without node; putting an npm build on that critical path would mean every
// CLI build, every release, and every sandbox needs a node toolchain to produce a
// tarball whose Kotlin didn't change. The vendored `codemirror.js` beside it is
// committed for the same reason. `npm run verify` (wired into CI) rebuilds and
// fails if the committed bundle has drifted from source, so "committed" can't
// quietly become "stale".
//
// ONE bundle, deliberately, where the legacy assets are per-page. Lit is ~6 kB
// min+gzip and every serve page already loads `url-state.js`, so a shared bundle
// costs less than the selective loading would save, and it lets components import
// each other. The heavy per-page scripts that selective loading actually exists
// for — `codemirror.js` (11 k lines, playground only), `format-compare.js`,
// `viewer.js` — stay exactly where they are and keep their own script tags.

import { build, context } from "esbuild";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = dirname(fileURLToPath(import.meta.url));
const watch = process.argv.includes("--watch");

const assets = (name) =>
    resolve(
        root,
        "..",
        // The server is its own Gradle module (#3824 item 7); its resources moved with it, so
        // the bundles are written into `cli/serve/`, not `cli/`.
        "serve",
        "src",
        "main",
        "resources",
        "ee",
        "schimke",
        "composeai",
        "cli",
        "serve",
        "assets",
        name,
    );

// THREE bundles, for one reason: they are loaded from different places and cost
// different amounts.
//
// `serve-components.js` carries the Lit elements and is emitted by the surfaces
// that actually contain their tags. It is ~12 kB gzipped, most of that Lit.
//
// `serve-chrome.js` carries what EVERY page needs — the URL-state global and the
// Page theme setting — so the page shell emits it unconditionally, on the front
// door and `/status` too. Folding those into the component bundle would put
// Lit's 12 kB on a front door whose imagery is prebaked precisely so a visit
// costs the HTML and nothing else. Neither chrome module is a custom element, so
// this bundle carries no Lit at all and lands around 3 kB.
//
// The split also fixes an ordering problem a single bundle could not: chrome has
// to be evaluated BEFORE the components bundle and before the legacy scripts,
// because they read `window.cpUrlState` — while the components bundle stays
// where each surface already puts it.
//
// `keyboard-navigation.js` is also emitted by every page, but stays separate so
// its opt-in power-user UI can initialize after the complete page DOM exists.
//
// `report-capture.js` is the screen-capture tool behind the report launcher, and
// it is separate for the opposite reason to the shell: it is on NO page until
// somebody opens the launcher panel or lands on `/report-bug`, at which point
// `chrome/reportLauncher.ts` injects the tag. Folding it into the shell would put
// a selection overlay, a canvas cropper and the clipboard path on every front-door
// visit, none of which will ever run there.
//
// `format-compare.js` keeps its own name and tag because two consumers OUTSIDE the
// browser load that exact path — the publish-time reference-score driver and the
// compare audit — and because only the comparison surfaces need its several
// hundred lines. It is a generated file now rather than a hand-written one; the
// four external consumers are unaffected, since what they depend on is the path
// and the `window.ComposePreviewCompare` shape, both unchanged.
//
// `known-differences.js` is the acceptance band and the engine behind it, emitted only by the
// focused comparison. It is the heaviest bundle here after `viewer.js` because it carries the
// contract's whole reference implementation — the document ladder, five gates, a dependency-free
// PNG reader and the separated-plane scorer, shared verbatim with `scripts/design-artifacts/` so the
// browser and the offline driver cannot disagree about what an acceptance means. Folding it into
// `serve-components.js` would put all of that on the catalog grid and the design pages; folding it
// into `format-compare.js` would charge that file's four external consumers for a surface none of
// them uses.
//
// `viewer.js` is generated for the same reason and on the same terms: same path,
// same script tag, same position after `serve-components.js` and
// `format-compare.js`. It stays its own bundle because only the viewer page
// carries it and it is by far the largest of these — folding 3,000 lines of lane
// machinery into `serve-components.js` would put it on the catalog grid, the
// compare wall and the design pages, none of which have a stage to drive.
const BUNDLES = [
    { entry: "src/main.ts", out: "serve-components.js" },
    { entry: "src/chrome.ts", out: "serve-chrome.js" },
    { entry: "src/keyboardNavigation.ts", out: "keyboard-navigation.js" },
    { entry: "src/reportCapture.ts", out: "report-capture.js" },
    { entry: "src/formatCompare.ts", out: "format-compare.js" },
    { entry: "src/knownDifferences.ts", out: "known-differences.js" },
    { entry: "src/viewer.ts", out: "viewer.js" },
];

const optionsFor = ({ entry, out }) => ({
    entryPoints: [resolve(root, entry)],
    outfile: assets(out),
    bundle: true,
    format: "iife",
    platform: "browser",
    target: ["es2022"],
    // No sourcemap: the output is a committed artifact served from a public
    // host, and a `.map` beside it would be a second committed blob to keep in
    // sync for no reviewer benefit.
    sourcemap: false,
    logLevel: "info",
    legalComments: "none",
    minify: !watch,
    // Without this, esbuild falls back to the nearest tsconfig it can find and
    // may compile the Lit `@customElement` / `@state()` decorators as TC39
    // standard decorators. Lit 3 supports both, but this source uses the
    // experimental syntax, and a mismatch silently breaks element registration
    // and `@state` reactivity — the control renders as an empty tag with no
    // console error. Same pin, same reason, as yschimke/compose-preview-vscode's `esbuild.webview.mjs`.
    tsconfig: resolve(root, "tsconfig.json"),
    banner: {
        js: "/* Generated by cli/serve-web — do not edit. Run `npm run build` in cli/serve-web/. */",
    },
});

if (watch) {
    for (const bundle of BUNDLES) {
        const ctx = await context(optionsFor(bundle));
        await ctx.watch();
    }
    console.log("[esbuild] watching serve-web bundles…");
} else {
    for (const bundle of BUNDLES) await build(optionsFor(bundle));
}
