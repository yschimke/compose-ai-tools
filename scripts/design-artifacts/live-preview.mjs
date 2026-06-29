/**
 * Live-preview deep links for the published design-artifact catalogs — the
 * cross-tool bridge that makes browsing a `design-artifacts/<system>` branch and
 * opening an editable live preview the same render.
 *
 * The upstream `compose-preview serve --catalogs <system>` hosts each published
 * catalog as a `?session=<system>` session and derives a **route-safe** preview
 * id from the image's bundle path. This module reproduces that exact derivation
 * so a link in `catalog.json` / the README resolves to the matching live render.
 *
 * Contract (keep in lockstep with `ServeCatalogStore.previewIdFor` in
 * `compose-ai-tools` and `livePreviewUrl` in design-parity's `catalog-export`):
 * the preview id is the image path minus the `images/` prefix and `.png` suffix,
 * with the component-subdir `/` flattened to `__` (the serve routes capture a
 * single path segment, so the id must be slash-free).
 */

/** Default public preview server. Override with `PREVIEW_SERVER_BASE` / `--preview-base`. */
export const DEFAULT_PREVIEW_BASE = "https://preview.coo.ee";

/** Route-safe live-server preview id for a catalog image path. */
export function catalogPreviewId(imagePath) {
  return imagePath
    .replace(/^images\//, "")
    .replace(/\.png$/, "")
    .replace(/\//g, "__");
}

/**
 * Deep link that opens a catalog image's variant in the live preview server. Targets the **viewer**
 * route `/p/{name}` (not `/?preview=`, which only renders the session landing page) with the
 * route-safe preview id as the single path segment and `?session=<system>` selecting the catalog.
 */
export function livePreviewUrl(base, system, imagePath) {
  const root = base.replace(/\/+$/, "");
  const id = catalogPreviewId(imagePath);
  return `${root}/p/${encodeURIComponent(id)}?session=${encodeURIComponent(system)}`;
}

/** Session-level link (the system's landing page on the live server), for the README banner. */
export function liveSessionUrl(base, system) {
  return `${base.replace(/\/+$/, "")}/?session=${encodeURIComponent(system)}`;
}

/**
 * Design systems that have an **in-browser Kotlin/Wasm** render tier (the
 * `:samples:cmp-wasm-catalog` CMP app — see `docs/wasm-cmp-spike.md`). Only the
 * Compose Multiplatform catalog qualifies: `material3` ships a `wasmJs` target,
 * so its components run client-side in the browser sandbox. `wear-m3` stays
 * server-only — `androidx.wear.compose` has no wasm target.
 */
export const WASM_CATALOG_SYSTEMS = new Set(["compose-m3"]);

/** Whether `system` advertises the in-browser Wasm tier. */
export function hasWasmTier(system) {
  return WASM_CATALOG_SYSTEMS.has(system);
}

/**
 * Deep link that mounts a catalog component **live in the browser** via the CMP
 * Wasm app the preview server carries for the system at `/wasm/<system>/`. The
 * `?id=<component>` selects the registered composable (the catalog's component
 * id); `uiMode=dark` flips the theme. Returns null for server-only systems.
 *
 * Keep the `?id=` / `?uiMode=` contract in lockstep with the Wasm entrypoint
 * (`Main.kt` in `:samples:cmp-wasm-catalog`).
 */
export function wasmLiveUrl(base, system, componentId, { dark = false } = {}) {
  if (!hasWasmTier(system)) return null;
  const root = base.replace(/\/+$/, "");
  const q = `?id=${encodeURIComponent(componentId)}${dark ? "&uiMode=dark" : ""}`;
  return `${root}/wasm/${encodeURIComponent(system)}/${q}`;
}
