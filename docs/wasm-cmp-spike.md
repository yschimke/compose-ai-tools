# Spike: in-browser CMP rendering via Kotlin/Wasm

**Status: spike / design — not yet built.** This records the feasibility findings and the concrete
plan so the build can be executed (and *measured*) as a focused follow-up, per the "spike first,
report before the full build" approach.

## Goal

Render a **Compose Multiplatform (CMP)** preview *in the browser* with Kotlin/Wasm, mounted into the
existing `serve` viewer, so a CMP component is interactive client-side with no server round-trip. This
is one tier of the per-format render model in [public-preview-server.md](public-preview-server.md):

- **CMP → Kotlin/Wasm (browser sandbox)** ← *this spike*
- Compose Android → server (Robolectric)
- Remote Compose / Protolayout / Lottie → their own data-only players
- Baked PNG → universal fallback

The viewer falls back down that list when a tier isn't available for a given preview.

## What's already in place (confirmed)

- **Toolchain supports it.** Kotlin `2.3.21` + Compose Multiplatform `1.10.3` (`gradle/libs.versions.toml`)
  both ship the `wasmJs` target with Compose; the `org.jetbrains.compose` plugin is already on the
  build's classpath (used by `jetbrains-compose-*` deps). No version bump needed.
- **The viewer has the seam.** `ServeWeb`'s viewer is written against a `data-mode` attribute
  (`snapshot` today) precisely so a `live` transport mounts into the same page — see the kdoc on
  `ServeWeb` and `data-mode="snapshot"` in `viewerPage`. The `cp-canvas` element already sits beside
  `cp-img` for exactly this.
- **The bundle has a carriage convention.** The portable bundle already reserves a `web/` directory
  (`BUNDLE_WEB_DIR`, written by `bundle embed --in-bundle`) — additive and ignored by older readers —
  so a Wasm app slots in under `web/wasm/` without a new top-level surface.

So the **risk isn't the toolchain** — it's (a) what exactly gets compiled to Wasm, (b) artifact size /
cold-load, and (c) confirming a headless in-browser render. Those need a measured build.

## The core design question: what does the Wasm app contain?

A Wasm app can only render composables **compiled into it** — it can't reflectively render an arbitrary
uploaded consumer composable the way the server's classpath player can. Two models:

1. **Fixed catalog app (recommended first).** Build one `wasmJs` app from a module **we control** (the
   `design-catalog-*` catalogs) that registers its composables by preview id. Serve it for our
   *published* catalogs (`--catalogs`). No per-bundle build; one artifact per design system. This
   directly delivers "CMP renders in the browser" for the catalogs the public server already hosts.
2. **Per-bundle app (later).** Compile the *consumer's* selected previews to Wasm at `bundle pack`
   time and carry the app in `web/wasm/`. Fully general, but adds a `wasmJs` compile to every CMP pack
   (heavy) and only works for pure-CMP previews (no Android-only APIs). Gate behind a `--with-wasm`
   opt-in once the size/build cost is known.

Recommend shipping (1) first — it's bounded, uses modules we own, and proves the whole viewer path —
then (2) as an opt-in once the size/build cost is known.

## Plan

### 1. A `wasmJs` CMP module
A new KMP module (e.g. `samples/cmp-wasm-catalog`) applying `kotlin("multiplatform")` +
`org.jetbrains.compose` with:
```kotlin
kotlin {
  wasmJs { browser() ; binaries.executable() }
  sourceSets.commonMain.dependencies {
    implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3)
  }
}
```
A `main()` reads a preview id from the URL/JS bridge and calls `CanvasBasedWindow` /
`ComposeViewport` to mount the matching composable from a `Map<String, @Composable () -> Unit>`
registry (the same ids the catalog uses, e.g. `button-filled__ideal__default__dark`).

### 2. Bundle carriage (format v9, additive)
Package the built app under `web/wasm/{index.html, <app>.wasm, <app>.js, skiko.wasm, …}` and add a
`webRender` manifest descriptor (`kind = "compose-wasm"`, preview-id → entry mapping) — same additive
pattern as the v5–v8 IR/extension fields, so older readers ignore it.

### 3. Viewer integration
`ServeWeb.viewerPage`, when the session advertises a `compose-wasm` webRender for the preview's
backend (`compose-multiplatform`), mounts the Wasm app in a **sandboxed `<iframe>`** at the
`data-mode="live"` seam (Wasm runs in the browser sandbox, so even an *unverified* bundle is safe to
execute client-side — see the trust × format table). Order: Wasm (CMP) → RemoteCompose canvas →
server frames (Android / override re-render) → baked PNG.

## Open questions the measured build must answer
- **Artifact size & cold-load** — a Compose/Wasm app pulls in skiko-wasm; measure the gzipped
  `web/wasm/` total and first-paint time. Sets whether (2) is viable per-bundle.
- **Headless render confirmation** — build the fixed catalog app and open `web/wasm/index.html` in the
  pre-installed Chromium (Playwright) to confirm a composable paints for a given preview id.
- **Build cost** — wall-clock for `wasmJsBrowserDistribution`; whether it fits the design-artifacts CI
  job or needs its own.

## Go / no-go
- **Go** if the fixed catalog app renders headlessly and the gzipped `web/wasm/` is within a few MB.
  Ship model (1) wired into the viewer + the `--catalogs` path.
- **Defer (2)** (per-bundle Wasm) until (1)'s size/build cost is known; keep it behind `--with-wasm`.
- **No-go fallback** is already shipped: CMP previews keep rendering via **server frames** (desktop
  Skiko) and the **baked PNG**, so nothing regresses if Wasm is deferred.
