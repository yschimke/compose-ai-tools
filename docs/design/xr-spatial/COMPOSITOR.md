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
- `environment.color` → skybox / clear color (defaults to a neutral dark).

Implementation gotchas (buffer lifetime, alpha premultiply, readPixels
orientation) are documented in the tool's
[README](../../../renderers/xr-composite/README.md#implementation-notes--gotchas).

## What's not done yet

This is the renderer half. Still open: visual parity with the WebGL viewer
(grid / axes / labels), self-contained material embedding (`resgen`), wiring the
composite into the render pipeline + preview manifest with graceful degradation
when the binary / display / software GL is unavailable, and macOS/Windows builds
plus a distribution/bootstrap story. Those are the real remaining cost — the
GPU-free rendering itself is solved.
