# Handover — design-catalog toggle work (2026-07-15)

Two pieces are **fully implemented but not shipped**: the session's shell died mid-work (`E2BIG` — an oversized bash startup snapshot inlined into every command, so no `git`/Gradle/`node` could run), so nothing could be built, tested, committed, or pushed after that point. This doc + the preserved files let a **fresh session** (working shell) finish both end-to-end.

## Already shipped this session (merged to `main` + published to delivery branches) — no action needed
- **#2474** — remote-m3 ↔ wear-m3 catalog parity + the cross-system `matches.html` page.
- **#2479** — reusable **static preview embeds** (fixed the "loading forever" columns on htmlpreview) + remote **OutlinedButton** render fix.
- **#2483** — content **alignment** across paired catalogs (Progress determinate 66%, shared Text copy, matched labels, outlined-card label visibility).
- **#2484** — new wear **OutlinedCard** sticker so `Card/Outlined` pairs **outlined↔outlined**.
- All `design-artifacts/{remote-m3,wear-m3,compose-m3}` branches were regenerated after each merge (via `design-artifacts.yml` on `main`).

---

## Pending 1 — Preview-server component **state toggle**

**Goal (confirmed with @yschimke, scope = all baked states):**
1. In the viewer (`/p/<id>`), always let a component toggle to its other states (e.g. `checkbox-checked` → `unchecked`; `button-filled` → pressed/focused/disabled).
2. In the grid, fold non-default states into ONE card per component (no duplicate "off"/"unchecked" card). The separate `@Preview` functions stay — useful in code.

**Key finding:** the catalog + generator are already correct (states are `variant`s folded into one component's `images[]` tagged with `image.state`). The gap is **serve-side only**, in `cli/src/main/kotlin/ee/schimke/composeai/cli/serve/`.

**Design + exact per-file edits:** see `docs/design/SERVE_STATE_TOGGLE_SPEC.md` (already committed on branch `agent/serve-state-toggle`). Summary: carry `state`/`theme` from the catalog store to the host via a `previews/variants.json` manifest; grid feeds `groupPreviews(previews.filterNot { isNonDefaultState(it) })`; the viewer renders a `<nav class="cp-states">` switcher of plain links to the same-theme sibling states.

**The implemented code** (4 production + 3 test files, verified by inspection only — NOT compiled) was delivered to @yschimke as exact-bytes attachments in chat:
`serve/ServeRenderHost.kt`, `serve/ServeCatalogStore.kt`, `serve/ServeBundleHost.kt`, `serve/ServeWeb.kt`, and tests `ServeWebTest.kt` (new), `ServeCatalogStoreTest.kt`, `ServeBundleHostTest.kt`. Drop them back into `cli/src/.../serve/`.

**Finish steps (working shell):**
1. `./gradlew :cli:compileKotlin :cli:compileTestKotlin`
2. `./gradlew :cli:test --tests "*ServeWebTest*" --tests "*ServeCatalogStoreTest*" --tests "*ServeBundleHostTest*"`
3. **Regenerate goldens** — the new CSS + switcher change every rendered page, so `ServeWebFixtureTest` fails until: `UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'`, then commit the updated `vscode-extension/preview-harness/fixtures/pages/`.
4. `./gradlew :cli:ktfmtFormat`
5. Screenshot the grid (one card per toggle component) + the viewer (state switcher) → `docs/preview-embed/serve-state-grid.png`, `serve-state-viewer.png`; embed in the PR body.
6. Commit on `agent/serve-state-toggle` (author **Yuri Schimke <yuri@schimke.ee>**, NO agent attribution), push, open PR titled `feat(serve): fold component states into one grid card + add a viewer state switcher`.

---

## Pending 2 — compose-m3 **RadioButton `unselected`** variant

**Why:** `RadioButton/Selected` had no `unselected` variant, so radio had nothing to toggle to. This adds it (mirrors `switch-off` / `checkbox-unchecked`), completing the toggle story for radio. Independent of Pending 1 (catalog change vs. server change) — can be its own small PR or bundled.

**The exact edits** (also delivered as 3 exact-bytes files: `CatalogComponents.kt`, `CatalogPreviews.kt`, `catalog.spec.json`). Inlined here so they stand alone:

**`samples/design-catalog-m3-shared/src/commonMain/kotlin/com/example/designcatalogm3/shared/CatalogComponents.kt`** — add a registry branch after the `chip-filter-unselected` branch (before `"segmentedbutton"`):
```kotlin
    "radiobutton-unselected" ->
      RadioButton(selected = catalogOverrideBoolean("selected", false), onClick = {})
```
…and add `"radiobutton-unselected",` to the `catalogComponentIds` list, right after `"chip-filter-unselected",`.

**`samples/design-catalog-m3/src/main/kotlin/com/example/designcatalogm3/CatalogPreviews.kt`** — add next to `FilterChipUnselected`:
```kotlin
@CatalogModes @Composable fun RadioUnselected() = Sticker("radiobutton-unselected")
```

**`samples/design-catalog-m3/catalog.spec.json`** — give the RadioButton entry an `unselected` variant:
```json
{ "componentId": "RadioButton/Selected", "preview": "RadioSelected", "variants": [ { "state": "unselected", "preview": "RadioUnselected", "caption": "Unselected state." } ] },
```

**Finish steps (working shell):**
1. `./gradlew :samples:design-catalog-m3:composePreviewRenderAll` — confirm `RadioUnselected` renders (unselected radio).
2. `ktfmtFormat` the two Kotlin modules.
3. Commit (author Yuri Schimke), push, PR titled `feat(samples): add compose-m3 RadioButton unselected state variant`. It then flows through `design-artifacts.yml` → the delivery branches like #2483/#2484.
4. (After the serve toggle also lands + the compose-m3 branch regenerates, radio will be togglable selected↔unselected in the preview server.)

---

## Environment note (why this was a handover)
The break was a session-local shell failure (`E2BIG`: ~163 KB bash snapshot inlined into every `bash -c`, exceeding the OS arg limit; not fixable mid-session because it's cached at session start). A **fresh session gets a clean snapshot** and a working shell — start there. It was made worse by a worktree subagent modifying the shell-snapshot file while trying to work around the same failure; not a repo problem.
