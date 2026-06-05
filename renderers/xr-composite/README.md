# xr-composite

A small native (C++) tool that renders a **SpatialScene** — the `scene.json` +
per-panel `<id>.png` textures emitted by `:renderer-xr` (see
[`docs/design/SPATIAL_SCENE_CONTRACT.md`](../../docs/design/SPATIAL_SCENE_CONTRACT.md)) —
into a composite PNG: a baked still of the 3D spatial layout, the same scene the
VS Code WebGL viewer shows interactively.

It runs two ways: **one-shot** (`--scene` → one PNG → exit) and a long-lived
**server** (`--serve`) that speaks JSON-RPC over stdio, holds one Filament engine
across frames, and streams rendered frames back as panels are updated per-frame —
the first increment of the [renderer-service RFC](../../docs/design/xr-spatial/RENDERER_SERVICE.md).
The `SpatialScene` types it parses are generated from
[`schema/spatial-scene.schema.json`](../../schema/spatial-scene.schema.json) (see
[`spatial_scene.hpp`](src/spatial_scene.hpp)).

It exists because the still has to be produced **headless, with no GPU**, on
ordinary CI. It uses [Filament](https://github.com/google/filament)'s OpenGL
backend on Mesa **llvmpipe** (software rasterization) under **Xvfb**. The
Filament desktop SDK ships only Android JVM bindings, so we drive it as a native
binary rather than via JNI.

> **Status: prototype.** The renderer is proven end-to-end (parses a real
> `scene.json`, places a textured quad per panel, orbit camera, reads pixels to
> PNG — all GPU-free in ~1s), and `--serve` adds a working per-frame JSON-RPC
> server (CI-smoked). **Not yet daemon-fronted** — the daemon spawning/proxying
> this process (RENDERER_SERVICE RFC) is the next step — and only the Linux build
> is runtime-exercised. See "Roadmap" below.

## Build

```sh
./build.sh                      # downloads the pinned Filament SDK (cached under build/sdk), then CMake+Ninja
FILAMENT_SDK=/path/to/filament ./build.sh   # or reuse an already-unpacked SDK / CI cache
```

Outputs `build/xr-composite` and the compiled materials in `build/materials/`.

Requirements:
- **clang / libc++** — Filament's Linux/macOS release libs are built against
  libc++, so the tool must link `-stdlib=libc++` (CMake enforces this).
- The build links `-lzstd` in addition to the SDK README's library list
  (Filament compresses material shaders with zstd).

## Run (headless, no GPU)

```sh
xvfb-run -a -s "-screen 0 2000x1400x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ./build/xr-composite \
    --scene  /path/to/render-output/scene.json \
    --out    composite.png \
    --materials build/materials \
    --width 1600 --height 1000
```

`--scene` points at the `scene.json`; panel `texture` paths are resolved
relative to its directory. A panel whose texture is missing is skipped.

## Server mode (`--serve`)

`--serve` turns the tool into a long-lived JSON-RPC peer over stdio, framed with LSP-style
`Content-Length` headers — the same framing the daemon's subprocess render-session backend speaks, so
the daemon can front it (RENDERER_SERVICE RFC). One Filament engine/scene is held for the life of the
process; panels can be mutated per-frame and each render is streamed back.

```sh
xvfb-run -a -s "-screen 0 2000x1400x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ./build/xr-composite --serve --materials build/materials --width 1280 --height 800
```

Methods (all params are JSON):

| method | params | effect |
|--------|--------|--------|
| `initialize` | `{frameStreamId?}` | returns `{serverInfo, capabilities}` (`render`/`updatePanels`/`streamFrame`, `spatialSceneVersion`, `dataProducts:["xr/composite"]`) |
| `render` (alias `xr/render`) | `{scene: SpatialScene, sceneDir?, environment?, out?}` | (re)build the whole scene + camera, render; emits a `streamFrame`; `out` also writes a PNG file |
| `xr/updatePanels` | `{panels: [{id, texture?, poseInRoot?, sizeDp?}], out?}` | mutate matching panels (or append a full new panel) and re-render; emits a `streamFrame` |
| `shutdown` / `exit` | — | `shutdown` acks; `exit` ends the loop |

Rendered frames are delivered as **`streamFrame` notifications** —
`{encoding:"png", width, height, seq, data:<base64>, frameStreamId?}` — reusing the daemon's
`composestream/1` shape (RFC decision #4: base64-over-JSON). The
[`test/serve_smoke.py`](test/serve_smoke.py) harness drives this flow and is run in CI under Xvfb.

### Why Xvfb?

The prebuilt Linux Filament backend creates its GL context via **GLX**, which
needs an X display (there is no EGL-surfaceless path in the release). Under
Xvfb, Mesa routes GLX to the **llvmpipe** software rasterizer, so no GPU is
required. (Confirmed against Filament's own `BUILDING.md` "Software
Rasterization" section.)

## Runtime requirements (where it actually bakes)

Having the binary (auto-provisioned — see "Consumer flow" below) and being able to *run* it are two
separate conditions; **both** must hold for a still to appear. When the host can't create a GL
context the pipeline **skips gracefully** — the composite is an optional capture, so the render
never fails and the interactive viewer + `scene.json` are unaffected.

- **Linux (the proven path):** needs a virtual X display + Mesa software GL —
  `sudo apt-get install -y xvfb libgl1-mesa-dri libglx-mesa0`. The plugin/CLI wrap the binary in
  `xvfb-run -a` automatically when `DISPLAY` is unset; if neither a display nor `xvfb-run` is
  available it skips. **No GPU** is required (llvmpipe). This repo's `Render XR composite (sample)`
  CI job installs exactly these — a consumer's Linux box/CI needs them too, or composites are
  skipped.
- **macOS / Windows:** the binaries are published but **not yet runtime-verified** — only
  Linux + llvmpipe is exercised end-to-end. Treat mac/win as best-effort until a runtime smoke
  lands.

## CLI

| flag | meaning | default |
|------|---------|---------|
| `--scene <path>` | path to `scene.json` (required) | — |
| `--out <path>` | output PNG | `composite.png` |
| `--materials <dir>` | directory with the compiled `.filamat` blobs | `.` |
| `--width` / `--height` | output size in px | `1280` × `800` |
| `--environment <preset\|color:#RRGGBB>` | backdrop override (see "Background"): a named preset, or `color:#RRGGBB` for a flat skybox. Overrides the scene's `environment`. | `warm-room` |
| `--serve` | run as a long-lived JSON-RPC server over stdio instead of one-shot (see "Server mode"). `--scene`/`--out` are ignored. | off |

## Background (swappable presets)

The backdrop is a **vertical-gradient, room-like environment cubemap** (an HDRI-style
ceiling / wall / floor) so the light Material panels pop. It is **swappable** via named
presets:

| preset | look |
|--------|------|
| `warm-room` *(default)* | a softly-lit, muted warm passthrough room — warm-taupe ceiling (`#332e27`), a warm wall at the horizon with a gentle glow (`#5a4d40`), and a deep warm-brown floor (`#1e1a16`). Echoes a real Android XR room while staying mid-dark so panels read clearly. |
| `studio-dark` | the legacy cold gradient — sky `#05070d` → horizon `#1a1f2b`, no floor (2-stop), preserved byte-for-byte. |

The gradient is built as a cubemap: the upper hemisphere interpolates horizon → sky, the
lower hemisphere interpolates horizon → floor (when the preset has one), with a soft glow
band on the horizon. The horizon colour also doubles as the readback **clear colour**.

**How to swap.** Selection precedence, most → least specific:

1. **CLI** `--environment <name>` picks a preset; `--environment color:#RRGGBB` forces a
   flat-colour skybox. This overrides whatever the scene says.
2. **`scene.json` `environment`**: `kind:"color"` → flat colour (`color`); otherwise a
   `preset` field selects a named preset, and explicit `sky` / `horizon` / `floor`
   fields override the preset's stops (a custom gradient). Legacy scenes with
   `kind:"gradient"` + `sky`/`horizon` still render (a custom-gradient override on the
   default). A `floor` enables the 3-stop room look.
3. **Built-in default** = `warm-room`.

```sh
# swap to the legacy cold look:
./build/xr-composite --scene scene.json --out studio.png --environment studio-dark
# force a flat backdrop:
./build/xr-composite --scene scene.json --out flat.png   --environment color:#101014
```

## Consumer flow — auto-provisioned by the CLI

Consumers do **not** build this tool or run an install step. On each GitHub Release the binary +
compiled `materials/` are published per OS as `xr-composite-<platform>-<version>.tar.gz`
(`linux-x86_64` / `macos-arm64` / `windows-x86_64`, see `.github/workflows/release.yml`). When the
`compose-preview` CLI drives a render and discovers an `XR_SUBSPACE` preview, it **auto-fetches** the
tarball matching its own released version into a shared, well-known cache:

```
${XDG_CACHE_HOME:-~/.cache}/composeai/xr-composite/<version>/<platform>/xr-composite   (+ materials/)
```

The Gradle plugin's `composePreviewCompositeXr` task then *reads* that cache (resolution order:
`composePreview.xrCompositeBinary` property → `XR_COMPOSITE_BIN` env → the cache path for the
plugin's version + host platform). The CLI is the only writer; the plugin never downloads, so a raw
`./gradlew composePreviewRenderAll` stays explicit (set the property/env or pre-populate the cache).

Everything is best-effort: an offline machine, a missing asset for a local `-SNAPSHOT` build (no
published release → 404), or an unsupported platform logs a concise note and the composite still is
simply absent — the render is never failed. Daemon-side auto-provisioning is a follow-up (see
`docs/design/xr-spatial/RENDERER_SERVICE.md` decision #6).

## Implementation notes / gotchas

These tripped up the initial implementation and are load-bearing:

- **Buffer lifetime:** Filament's `BufferDescriptor` does **not** copy — it reads
  the pointer later on the driver thread (during `flushAndWait`). Vertex/index
  data is heap-allocated and freed in the descriptor callback, never stack-local.
- **Transparency:** panel captures carry straight alpha, so the material uses
  `blending : transparent` and **premultiplies** (`rgb * a`); an opaque material
  renders the transparent regions as black.
- **Orientation:** Filament's `readPixels` on the headless swapchain is
  top-origin, and UV0 `(0,0)` maps to the quad's bottom-left, so the PNG is
  written without a vertical flip and textures are loaded top-down (no stb flip).
- **Color:** panel textures are `SRGB8_A8`; the view uses a `LinearToneMapper`
  (no filmic curve) so panel colors stay faithful.
- **Floating-panel fidelity:** to match Android XR's floating glass panels, each
  panel quad rounds its corners and adds a faint edge rim in `unlit_texture.mat`
  (a rounded-rect SDF drives the alpha mask + rim), and a separate soft
  rounded-rect **shadow quad** (`panel_shadow.mat`) is composited behind/below it.
  Corner radius / rim / shadow are computed in an **aspect-corrected rect space**
  (half-extents `(aspect,1)` or `(1,1/aspect)`) so corners stay circular on wide
  panels. Real Filament shadow maps are unstable/expensive on llvmpipe, so the
  contact shadow is a baked transparent quad — deterministic and GPU-cheap.

## Roadmap

- Match the WebGL viewer's look: grid, axes, panel labels, camera-framing parity
  (`flat_color.mat` is kept for the grid/axes pass).
- Embed materials via `resgen` so the binary is self-contained (drop `--materials`).
- Wire into the render pipeline and fold the composite into the preview manifest;
  degrade gracefully when the binary / display / software GL is unavailable.
- ~~macOS and Windows builds + a distribution/bootstrap story.~~ Done: per-OS Release tarballs,
  CLI auto-provisioning into a shared cache (see "Consumer flow" above).
