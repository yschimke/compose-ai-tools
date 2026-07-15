# Preview-server component **state toggle** — implementation spec

> Context: requested by @yschimke. The preview server (`compose-preview serve`, hosted at preview.coo.ee) shows each baked component STATE as its own grid card and offers no way to toggle a component between its states (e.g. `https://preview.coo.ee/compose-m3/p/checkbox-checked__ideal__default__dark` can't flip to `unchecked`). Two changes, applied throughout every catalog:
>
> 1. **Always allow a component to be toggled to its other states** in the viewer.
> 2. **Fold the "off"/"unchecked" (non-default) states out of the grid** — one card per component, not a duplicate per state. (The separate `@Preview` functions stay; they're useful in code.)
>
> Scope decision (confirmed): **all baked states** — checked/unchecked, on/off, selected/unselected, AND interaction states pressed/focused/disabled.
>
> This spec was written after a full implementation was completed in a worktree but lost to a broken shell (`E2BIG`, oversized bash snapshot inlined into every command). Re-implement from this spec in a session with a working shell, then build/test/screenshot/commit/PR.

## Key finding: the catalog is already correct — this is a **serve-side** change only

In all three `catalog.spec.json` files the "on/checked/selected" render is the `componentId` and the "off/unchecked/pressed/disabled" render is a **`variant`** tagged by `state`. `foldVariants` (`scripts/design-artifacts/catalog-variants.mjs`) merges each variant's images onto the parent component's `images[]`, re-tagged with `image.state`. So the published `catalog.json` already carries ONE component whose `images[]` holds all its states × themes. **Do not change the specs or the generator.**

The gaps are entirely in `cli/src/main/kotlin/ee/schimke/composeai/cli/serve/`:
- The grid grouping (`ServeWeb.kt` `baseKey`) only collapses the *theme* segment, so each `state` render becomes its own card → the "off" is a duplicate.
- `ServeCatalogStore` drops `image.state`/`image.theme`; the viewer only builds a Light/Dark toggle → no state switcher.

Preview id shape (from `previewIdFor`): `images/<slug>/ideal__<state>[__theme][__size][__k-v…].png` → id `<slug>__ideal__<state>[__theme]…`. The component slug = `id.substringBefore("__")` and is unique per component. Default state is `state == null` or `"default"`.

## Design

- **Task B (grid):** feed `groupPreviews(previews.filterNot { isNonDefaultState(it) })` so non-default states are folded out; each component shows only its default-state card, which keeps the EXISTING light/dark swap. Plain bundles / app screens (no `state`) are untouched (their `state` is null → treated as default → shown).
- **Task A (viewer):** the `/p/<id>` page gets a `<nav class="cp-states">` **state switcher** — plain links (no daemon, no JS state machine) to the component's sibling states in the SAME theme, current state marked `aria-current="page"`. Rendered only when ≥2 states exist for the current theme.

Driven by per-preview `state`/`theme` metadata carried from the store to the host via a small `previews/variants.json` manifest.

## Exact per-file changes (as implemented + verified by inspection in the lost worktree)

### 1. `serve/ServeRenderHost.kt` — `ServePreview`
Add two nullable, documented fields to the `data class ServePreview`:
```kotlin
val state: String? = null,   // component state ("unchecked"/"pressed"/"disabled"/…); null for plain bundles
val theme: String? = null,   // baked theme ("light"/"dark"); null when unthemed / plain bundle
```
Both from the catalog's `variants.json`; null keeps current behavior everywhere else.

### 2. `serve/ServeCatalogStore.kt`
- Add `val state: String? = null` and `val theme: String? = null` to the private `@Serializable data class Image` (~line 640). `ignoreUnknownKeys` is already on, so this is safe.
- In the staging loop (`for (component in catalog.components) { for (image in component.images) { … } }`, ~line 179), after `val id = previewIdFor(path)` and writing `previews/<id>.png`, collect `id -> {state, theme}` **only when state or theme is non-null**.
- After the loop, **before** the atomic `staging.renameTo(dir)` swap, write the manifest into `staging`: `previews/variants.json`, shape `{ "<id>": { "state": "unchecked", "theme": "light" }, … }` (omit null keys). Use the existing `json` instance; add a `@Serializable VariantMeta(val state: String? = null, val theme: String? = null)` and a `VARIANTS_FILE = "variants.json"` constant; import `MapSerializer`/`serializer` as needed.

### 3. `serve/ServeBundleHost.kt`
- Add a `VARIANTS_JSON = "variants.json"` constant and a matching `@Serializable VariantMeta`.
- Before building `previews`, best-effort read `previews/variants.json` (`readVariantMeta()`, try/catch → empty map; mirror `readOverrides`/`declaredThemes`).
- When mapping each `previews/<id>.png` to a `ServePreview`, set `state`/`theme` from the manifest entry for its id (null if absent). A plain bundle without the manifest keeps both null (unchanged).

### 4. `serve/ServeWeb.kt`
- Add helpers: `isNonDefaultState(p) = p.state != null && p.state != "default"`; `stateLabel(state)` → `null`/`"default"` ⇒ "Default", else replace `-`→space + capitalize first (`"keyboard-focus"`→"Keyboard focus"); `stateSwitcherHtml(current, siblings, q)`.
- **Grid:** in the grid-building function (~line 840–928) feed `groupPreviews(previews.filterNot { isNonDefaultState(it) })`. Nothing else about theme grouping/swap changes.
- **Viewer:** where the `/p/<id>` page is built (~line 1257, `data-preview-id`, and the `cp-uiMode` Theme select ~1271), compute the current preview's **sibling states**: all previews with the same slug (`id.substringBefore("__")`) whose `theme` equals the current preview's theme (or both null), one id per distinct `state`, default state first. If ≥2 distinct states, render `<nav class="cp-states" aria-label="Component state"><a class="cp-state-btn" href="…/p/<sibling-id><q>" [aria-current="page"]>Label</a>…</nav>` near the Theme select. Preserve the viewer's existing query/token suffix `$q` on links. No switcher when only one state.
- Add `.cp-states` / `.cp-state-btn` CSS next to the existing `.cp-theme` / `.cp-theme-btn` rules (light + dark).
- Escape all interpolated ids/labels with `WebEscaping.htmlEscape` / `urlEncodeSegment`. Match the existing string-template + `.trimIndent()` style. Do NOT touch the daemon/live lane, overrides, wasm, or figma-svg logic.

## Tests (this repo gates on tests)
Add/extend under `cli/src/test/kotlin/ee/schimke/composeai/cli/serve/`:
- **ServeWebTest.kt (new):** (a) a component with `default`+`unchecked` (both light+dark) yields ONE grid card (the default, still carrying light/dark swap attrs) and NO card for the `unchecked` id; (b) the default viewer renders a `cp-states` switcher linking the same-theme `unchecked` sibling with Default marked active; (c) a single-state component renders NO `cp-states`; (d) a plain stateless catalog renders grid + viewer unchanged.
- **ServeCatalogStoreTest.kt:** `previews/variants.json` is written with the right `{id:{state,theme}}` (null keys omitted) for a state-bearing fixture, and round-trips onto host previews.
- **ServeBundleHostTest.kt:** previews tagged from `variants.json`; a plain bundle (no manifest) keeps null state/theme.

## Required build / verify steps (need a working shell)
1. `./gradlew :cli:compileKotlin :cli:compileTestKotlin`
2. `./gradlew :cli:test --tests "*ServeWebTest*" --tests "*ServeCatalogStoreTest*" --tests "*ServeBundleHostTest*"`
3. **Regenerate the golden fixtures** — the new CSS + the `$stateSwitcher` line change every rendered page, so `ServeWebFixtureTest` WILL fail until: `UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'`, then commit the updated fixtures under `vscode-extension/preview-harness/fixtures/pages/`.
4. `./gradlew :cli:ktfmtFormat` (CI has a `ktfmt (Google style)` gate).
5. Screenshots (pre-installed Chromium at `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`): the grid (one card per toggle component) and the viewer (state switcher). Save under `docs/preview-embed/serve-state-grid.png` and `serve-state-viewer.png`, embed in the PR body.

## Follow-up worth noting
compose-m3 `RadioButton/Selected` has **no** `unselected` variant (spec: `samples/design-catalog-m3/catalog.spec.json`), so it has nothing to toggle to. If radio should be togglable, add a `RadioUnselected` `@Preview` + an `unselected` variant — separate catalog change.

## Commit / PR hygiene (repo rules)
- Branch `agent/serve-state-toggle`. Author/committer **must** be `Yuri Schimke <yuri@schimke.ee>` — NO agent attribution, NO `Co-authored-by` agent trailer (CI gate + commit-msg hook enforce this).
- Conventional-commit title: `feat(serve): fold component states into one grid card + add a viewer state switcher`.
- UI-affecting PR → embed the two screenshots in the body.
