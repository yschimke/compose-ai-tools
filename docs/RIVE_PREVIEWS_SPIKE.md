# Rive previews — feasibility spike

**Status:** spike / design (no implementation yet). Decides the rendering strategy for [Rive](https://rive.app)
previews before any code lands, the open question left by
[`LOTTIE_PREVIEWS.md`](LOTTIE_PREVIEWS.md) follow-up #4.

**TL;DR.** Rive ships **no JVM / Desktop / headless runtime** — the thing that made Lottie easy
(Compottie, a pure-Kotlin/Compose renderer) has no Rive equivalent. The three ways to get a `.riv`
onto a PNG are (A) `rive-android` under Robolectric, (B) the `rive-runtime` C++ core via JNI, or
(C) Rive's **web runtime (WASM + canvas/WebGL) inside a headless browser**. This spike recommends
**(C)** for a v1 Rive preview path: it reuses Rive's best-supported runtime and this repo's existing
headless-browser snapshot harness, needs no native toolchain, and maps cleanly onto the discovery +
animated-GIF + interactive-scrub patterns already built for Lottie.

## 1. Goal

Mirror the Lottie preview experience for Rive: drop a `.riv` file under `src/main/resources/`, have
discovery emit a `kind=RIVE` preview with **no consumer composable**, render a still PNG + an
animated GIF spanning the default state-machine / animation, and (later) scrub it interactively in
VS Code. The asset *is* the IR — the bundle should replay it with zero consumer bytecode, exactly
like the Lottie path.

## 2. The constraint: Rive has no JVM/Desktop renderer

| Runtime | Language | Platforms | Headless / JVM? |
| --- | --- | --- | --- |
| [`rive-android`](https://github.com/rive-app/rive-android) | Kotlin + C++ (JNI/NDK) | Android (minSdk 21) | **No** — Android framework + GPU surface bound |
| [`rive-runtime`](https://github.com/rive-app/rive-runtime) | C++ | Metal / Vulkan / D3D11/12 / OpenGL(/WebGL) | **No documented CPU/headless path**; GPU backend required |
| `@rive-app/canvas`, `@rive-app/webgl2` | WASM + JS | Browser (canvas2d / WebGL2) | Browser only — but a *headless* browser counts |
| iOS / Flutter / React Native | platform-native | mobile/web | No |

Key facts (verified against the upstream repos, June 2026):

- There is **no Kotlin Multiplatform / Compose Multiplatform Rive runtime**, and none announced.
  Compottie's whole appeal — a JVM-resident Compose renderer the desktop daemon can call in-process —
  simply doesn't exist for Rive.
- `rive-runtime` (the C++ core) renders through **GPU** backends (Metal/Vulkan/D3D/GL). There is no
  documented software/CPU rasteriser or "render `.riv` → PNG" CLI. It exposes an *abstract Renderer
  interface*, so a custom CPU/Skia renderer is theoretically pluggable, but that's a large upstream-
  shaped effort, not an integration.
- `rive-android`'s renderer draws into a real `Surface`/`TextureView` via JNI + EGL/GL. Robolectric
  is headless with no GPU and no EGL surface by default.

So every option below is fundamentally heavier than Lottie. There is no "just call a Kotlin
function" path.

## 3. Options

### Option A — `rive-android` under Robolectric (JNI + GPU)
Add `rive-android` to a Robolectric render path (sibling to the Android-only runtime modules in
[`LOTTIE_PREVIEWS.md`](LOTTIE_PREVIEWS.md) #3).

- **Blockers:** the native renderer needs an EGL context + GL surface. Robolectric provides neither;
  it would require a software GL stack (SwiftShader / Mesa `llvmpipe`) plus an offscreen EGL pbuffer
  and a JNI hand-off that `rive-android` doesn't expose for headless capture. `rive-android`'s public
  API is built around `RiveAnimationView` (a `View`), not an offscreen bitmap.
- **Verdict:** high risk, Android-only, fights both Robolectric and Rive's API shape. Not
  recommended for v1.

### Option B — `rive-runtime` (C++) via JNI on desktop Linux
Build the C++ core + write JNI bindings + render offscreen on a headless GPU (Mesa `llvmpipe` /
`EGL_PLATFORM=surfaceless`), read the FBO back to a `BufferedImage`.

- **Precedent in-repo:** [`renderers/xr-composite`](../renderers/xr-composite) already ships a native
  offline renderer, so a native-artifact build path is not unprecedented here.
- **Blockers:** a per-OS native artifact (Linux/macOS/Windows) to build, sign, and ship; CI needs a
  headless GL stack; JNI lifecycle + Skia/Rive-Renderer linkage to maintain against upstream. This is
  a multi-week native effort with ongoing maintenance — disproportionate to "preview a `.riv`".
- **Verdict:** most "native-correct" and backend-agnostic, but by far the most expensive. Defer.

### Option C — Rive web runtime in a headless browser (**recommended**)
Render `.riv` with `@rive-app/webgl2` (WASM) inside a headless Chromium, screenshot the canvas to
PNG, and frame-step for the GIF — exactly mirroring the Lottie still + GIF pair.

- **Use the WebGL2 runtime, not Canvas2D, for fidelity.** `@rive-app/webgl2` drives the **Rive
  Renderer**, which is required for Renderer-only features (e.g. vector feathering); `@rive-app/canvas`
  (Canvas2D) is simpler but renders those features with lower fidelity or not at all. Since the whole
  point is a *faithful* asset-as-IR preview, the harness should target `@rive-app/webgl2`. Headless
  Chromium provides WebGL2 via ANGLE's software backend (SwiftShader), so no physical GPU is needed —
  the same way the existing WebGL viewer renders in CI. `@rive-app/canvas` stays a documented fallback
  only for environments where WebGL2 is genuinely unavailable, with the fidelity caveat called out to
  the user.
- **Precedent in-repo:** the VS Code extension already runs a **headless-browser snapshot harness**
  ([`vscode-extension/preview-harness/snapshot.mjs`](../vscode-extension/preview-harness/snapshot.mjs),
  Playwright/Chromium per `vscode-extension/package.json`) and a WebGL viewer
  ([`vscode-extension/src/webview/spatial/spatialViewer.ts`](../vscode-extension/src/webview/spatial/spatialViewer.ts)),
  so a headless Chromium renders Rive's WebGL2 runtime without a native GL stack.
- **How it maps onto existing patterns:**
  - *Discovery* — scan `src/main/resources/` for `.riv` (binary magic / extension), emit `kind=RIVE`
    with the asset path, mirroring `discoverLottieAssets`. Asset-as-IR, zero consumer bytecode.
  - *Still + animated GIF* — load the artboard, advance the default animation / state machine over
    its duration, capture frames → PNG + looping GIF (the `ScrollGifEncoder` already encodes a
    `List<BufferedImage>`; the browser hands back PNG frames).
  - *Interactive scrub & inputs* — Rive's real interactivity is **state-machine inputs** (bool /
    number / trigger), a natural superset of Lottie's single `progress` scalar. The same
    `renderNow.overrides` pattern carries an input bag; the WASM canvas can also drive a VS Code
    interactive presenter directly (set input → re-render), reusing the `animation/lottie`-style data
    product to advertise the artboard's inputs/animations.
- **Blockers / unknowns:** ships a JS/WASM render path distinct from the JVM renderers (a new seam in
  the bundle/daemon story — the "renderer" for `kind=RIVE` is a Node/Chromium subprocess, not the
  desktop daemon); Chromium in CI (already present for the extension tests, not yet for the Gradle
  render pipeline); licensing/version pin of the Rive WASM bundle.
- **Verdict:** lowest total cost, uses Rive's first-class runtime, reuses existing headless-browser
  infrastructure, and the interactivity model is *richer* than Lottie's. Recommended for v1.

### Option D — defer / static poster only
Not viable: there is no JVM `.riv` decoder, so even a single static frame requires one of A–C. A
"poster image alongside the `.riv`" convention (consumer commits a PNG) is a stopgap, not a renderer.

## 4. Recommendation

Pursue **Option C** when Rive support is scheduled. Concretely, a v1 slice:

1. A small Node + `@rive-app/webgl2` render harness (`rive-render/`) that takes a `.riv` + width/height
   + optional `{ animation | stateMachine, inputs, timeMs }` and writes `out.png` (and a frames dir
   for the GIF), run under the headless Chromium the extension tests already provision.
2. Discovery: `.riv` → `kind=RIVE` preview (asset-as-IR), sibling to `discoverLottieAssets`.
3. A `RIVE` render dispatch that shells out to the harness (like the scroll/Lottie-GIF dispatch shells
   to `renderer-desktop`), producing the still PNG + the animated GIF by default.
4. `animation/rive` data product advertising artboards / animations / state-machine inputs, and an
   interactive presenter that posts input changes — reusing the Lottie timeline scrubber's plumbing.

Until then, Rive stays **explicitly out of scope**, and this document is the decision record for why.

## 5. Why not just wait for a KMP Rive runtime

There is no signal that Rive is building a JVM/KMP runtime. Betting the feature on an upstream that
may never ship would block it indefinitely; the web-runtime path is available today and is the same
runtime Rive itself recommends for the broadest reach. If a JVM/Skia Rive renderer ever lands, the
daemon can adopt it behind the same `kind=RIVE` discovery + bundle contract without changing the
authoring story.
