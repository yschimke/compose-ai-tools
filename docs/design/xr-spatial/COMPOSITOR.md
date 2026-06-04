# Baking the spatial scene to a still: the `xr-composite` tool

How we turn a `SpatialScene` (`scene.json` + per-panel PNGs, the
[contract](../SPATIAL_SCENE_CONTRACT.md)) into a **composite still PNG** of the
3D layout — the version that shows up "in the previews" next to ordinary
`@Preview` captures, as opposed to the interactive WebGL viewer in VS Code.

Source + build: [`renderers/xr-composite/`](../../../renderers/xr-composite/).

## The constraint that drove the design

The still has to be produced **headless, on ordinary CI, with no GPU**. We
evaluated the options against that bar:

- **Reuse the Three.js viewer via Playwright/headless Chromium** — rejected: a
  browser dependency in the render path.
- **Hand-rolled software rasterizer** — rejected: reinventing 3D rendering
  (perspective, texturing, AA) badly.
- **A real GPU engine from the JVM** — Filament is the obvious engine, but its
  published artifacts are **Android-only JVM bindings**; a desktop-JVM binding
  would be a DIY JNI + per-OS native-jar project with no upstream support.

So we run Filament as a **native binary** invoked as a subprocess — no JNI — and
lean on the parts Filament officially supports and tests:

- **Headless offscreen rendering:** `Engine::createSwapChain(width, height, flags)`
  (a real headless swapchain) + `Renderer::readPixels(...)` → PNG.
- **Software rasterization, no GPU:** the OpenGL backend on **Mesa llvmpipe**.
  The prebuilt Linux backend creates its GL context via **GLX** (no
  EGL-surfaceless path), so it runs under **Xvfb**, where Mesa routes GLX to
  llvmpipe. This matches Filament's own `BUILDING.md` "Software Rasterization"
  section. (Software *Vulkan* — lavapipe — is avoided: Filament's Vulkan
  headless path has a history of black-image bugs, and lavapipe isn't always
  present.)

A spike rendered the real `NowPlayingSpatialPreview` scene (two captured panels)
in ~1s on llvmpipe with zero GPU, validating the whole approach.

## How it maps the contract

Per [`SPATIAL_SCENE_CONTRACT.md`](../SPATIAL_SCENE_CONTRACT.md): units are dp,
right-handed +x right / +y up / +z toward the viewer — which is also Filament's
convention, so the transform is essentially identity.

- Each panel → a textured quad sized `sizeDp`, placed by `poseInRoot`
  (`translation` dp + `rotation` quaternion), facing +z.
- Panel PNG → an `SRGB8_A8` texture; an **unlit, transparent, premultiplied**
  material so the captures' transparent regions show the environment instead of
  black, and a `LinearToneMapper` so colors stay faithful.
- `camera` (orbit) → eye = `target + distance · dir(yaw, pitch)`, `lookAt`,
  45° vertical FoV.
- `environment` → a **swappable** backdrop. `kind=="color"` → a flat skybox / clear
  color (`color`). Otherwise a **vertical-gradient, room-like** backdrop chosen by a
  named **preset** (`warm-room`, the softly-lit warm default, or `studio-dark`, the
  legacy cold look), with optional explicit `sky`/`horizon`/`floor` stops overriding
  the preset. The `--environment <preset|color:#RRGGBB>` CLI flag overrides the scene's
  choice; the default is `warm-room`. The horizon doubles as the readback clear color.

Implementation gotchas (buffer lifetime, alpha premultiply, readPixels
orientation) are documented in the tool's
[README](../../../renderers/xr-composite/README.md#implementation-notes--gotchas).

## Consumer flow (distribution)

Consumers never build the tool. Per-OS Release tarballs
(`xr-composite-<platform>-<version>.tar.gz`) are **auto-provisioned by the CLI**: when
`compose-preview` drives a render and sees an `XR_SUBSPACE` preview, it fetches the binary matching
its own release into a shared cache
(`${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/`), and the Gradle plugin's
`composePreviewCompositeXr` task reads it from there (after the
`composePreview.xrCompositeBinary` / `XR_COMPOSITE_BIN` overrides). The plugin only reads; the CLI is
the writer, so raw `./gradlew` stays explicit. Any fetch failure (offline, no asset for a
`-SNAPSHOT`, unsupported platform) is a graceful skip. Full notes in the tool
[README](../../../renderers/xr-composite/README.md#consumer-flow--auto-provisioned-by-the-cli);
daemon-side auto-provisioning is a follow-up (see [`RENDERER_SERVICE.md`](RENDERER_SERVICE.md)
decision #6).

## What's not done yet

This is the renderer half. Still open: visual parity with the WebGL viewer
(grid / axes / labels), self-contained material embedding (`resgen`). Wiring the
composite into the render pipeline + preview manifest (with graceful degradation when the binary /
display / software GL is unavailable), the macOS/Windows builds, and the distribution/provisioning
story are now done (see "Consumer flow" above). The GPU-free rendering itself is solved.

The longer-term direction — turning this one-shot CLI into a long-lived,
extensible, multi-session render *service* behind the daemon (live panel/pose
input + frame streaming, capability-negotiated data-product kinds for overlays /
structure / XR a11y) — is sketched in [`RENDERER_SERVICE.md`](RENDERER_SERVICE.md).
