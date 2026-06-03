# xr-composite

A small native (C++) tool that renders a **SpatialScene** — the `scene.json` +
per-panel `<id>.png` textures emitted by `:renderer-xr` (see
[`docs/design/SPATIAL_SCENE_CONTRACT.md`](../../docs/design/SPATIAL_SCENE_CONTRACT.md)) —
into a single composite PNG: a baked still of the 3D spatial layout, the same
scene the VS Code WebGL viewer shows interactively.

It exists because the still has to be produced **headless, with no GPU**, on
ordinary CI. It uses [Filament](https://github.com/google/filament)'s OpenGL
backend on Mesa **llvmpipe** (software rasterization) under **Xvfb**. The
Filament desktop SDK ships only Android JVM bindings, so we drive it as a native
binary rather than via JNI.

> **Status: prototype.** The renderer is proven end-to-end (parses a real
> `scene.json`, places a textured quad per panel, orbit camera, reads pixels to
> PNG — all GPU-free in ~1s). It is **not yet wired into the Gradle pipeline**,
> and only the Linux build is exercised. See "Roadmap" below.

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

### Why Xvfb?

The prebuilt Linux Filament backend creates its GL context via **GLX**, which
needs an X display (there is no EGL-surfaceless path in the release). Under
Xvfb, Mesa routes GLX to the **llvmpipe** software rasterizer, so no GPU is
required. (Confirmed against Filament's own `BUILDING.md` "Software
Rasterization" section.)

## CLI

| flag | meaning | default |
|------|---------|---------|
| `--scene <path>` | path to `scene.json` (required) | — |
| `--out <path>` | output PNG | `composite.png` |
| `--materials <dir>` | directory with the compiled `.filamat` blobs | `.` |
| `--width` / `--height` | output size in px | `1280` × `800` |

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

## Roadmap

- Match the WebGL viewer's look: grid, axes, panel labels, camera-framing parity
  (`flat_color.mat` is kept for the grid/axes pass).
- Embed materials via `resgen` so the binary is self-contained (drop `--materials`).
- Wire into the render pipeline and fold the composite into the preview manifest;
  degrade gracefully when the binary / display / software GL is unavailable.
- macOS and Windows builds + a distribution/bootstrap story.
