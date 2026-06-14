# compose-ai-tools

**[Documentation](https://yschimke.github.io/compose-ai-tools/)** —
install, VS Code Marketplace, and one-page-per-product reference for
each data extension.

Render `@Preview` composables to PNG outside Android Studio, so AI coding
agents can see what they're changing. Works with Jetpack Compose (Android,
via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

Renders include
[paused-clock animation captures](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/capture-modes.md#animations-and-the-paused-frame-clock-android-only)
(GIF or single frame) and opt-in
[ATF accessibility checks](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/a11y.md)
with annotated overlays.

Also renders [Android XML resources](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/resource-previews.md) —
vector drawables, adaptive launcher icons, animated-vector drawables — and indexes the icon
attributes in `AndroidManifest.xml` so tooling can link manifest lines to the same rendered PNG.
Modules without any matching resources self-no-op, so this comes along for free with the plugin.

## The agent loop

These tools give AI coding agents a tight, token-frugal feedback loop over
Compose UI — the way [Playwright](https://playwright.dev) gave web agents one
over the DOM: act by a stable reference, observe structure rather than pixels,
and turn exploration into durable tests. The capabilities below are exposed by
the preview daemon's MCP server (and, where noted, the `compose-preview` CLI);
see [`docs/daemon/MCP.md`](docs/daemon/MCP.md) for the full tool surface.

- **Target by semantic ref, not pixels.** `interactive/input` and
  `record_preview` accept a `target` (`testTag` / `role`+`text` / a stable
  node `ref`) that the daemon resolves to the node's centre, so a click
  survives layout changes instead of breaking on a coordinate. Works on
  Android (Robolectric) and Desktop (Skiko).
- **Token-frugal observation.** `render_preview observe=semantics|hash`
  returns the `compose/semantics` tree + a hash + dimensions instead of a
  base64 PNG — typically a few hundred tokens versus ~1.5k. Fetch pixels only
  when you actually need to look.
- **Semantics diff.** `diff_semantics` (MCP) and `compose-preview
  diff-semantics` (CLI) diff two semantics trees and report what changed
  *semantically* (text, label, role, testTag, overflow…), matched by stable
  ref — a deterministic, pixel-free regression signal, the Compose analogue of
  Playwright's aria-snapshot diff.
- **Matrix render.** `render_matrix` (and the `compose-preview render-matrix`
  CLI) renders one preview across a cross-product of
  `device × locale × uiMode × fontScale` in a single call, returning a per-cell
  hash and which cells changed — "does this survive small screen + RTL + large
  font?" without N screenshots. Opt into a stitched contact-sheet image when you
  want to eyeball every cell at once.
- **Recording → test.** `record_preview emitTest=true` turns a scripted
  interaction into a runnable Compose UI test (semantic targets become
  `onNodeWithTag(...).performClick()` steps; each `recording.probe` is diffed
  against the previous probe's captured semantics into `assertExists()` /
  `assertDoesNotExist()` assertions).
- **Structured failures.** A failed render reports a typed `kind` plus a
  one-line fix hint for recognized signatures (classpath skew, Robolectric SDK
  mismatch, missing `@Composable`, …) instead of an opaque message.

Cost budget for these in [`docs/TOKEN_USAGE.md`](docs/TOKEN_USAGE.md).

## What it ships

- **Agent skills** — the `compose-preview` and `compose-preview-review`
  skill bundles live in
  [`yschimke/skills`](https://github.com/yschimke/skills). Point any
  agent that can fetch a URL at them; each skill is a complete
  install-and-iterate playbook. Bootstrap a host machine (CLI + skills
  in one shot) with the installer in
  [`yschimke/skills`](https://github.com/yschimke/skills/blob/main/scripts/install.sh):

  ```sh
  curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
  ```
- **VS Code extension** — published to the
  [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
  and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
  (for VSCodium / Cursor / Windsurf). Install from inside the IDE: open
  the Extensions view (⇧⌘X / Ctrl+Shift+X), search **Compose Preview**,
  click *Install*. Source in [`vscode-extension/`](vscode-extension/).
- **GitHub Actions** — composite actions for CI:
  [`install`](.github/actions/install/) (CLI on `$PATH`),
  [`apply`](.github/actions/apply/) (unified pipeline — baselines on push,
  before/after PR comments, a11y + notification surfaces).

## Setup

The plugin is published to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.15.4"
}
```
<!-- x-release-please-end -->

Working examples: [`samples/android/build.gradle.kts`](samples/android/build.gradle.kts),
[`samples/wear/build.gradle.kts`](samples/wear/build.gradle.kts),
[`samples/cmp/build.gradle.kts`](samples/cmp/build.gradle.kts).

### Zero-Code Integration (Alternative)

You can apply the plugin dynamically without modifying the project's source code, useful for AI agents on the CLI, in CI, or when exploring the tool without committing changes. The `compose-preview` CLI ships a bundled Gradle init script and passes it via `--init-script` on every invocation, so projects that already apply `com.android.application` / `com.android.library` / `org.jetbrains.compose` pick up the preview plugin without an edit to `build.gradle.kts`:

```sh
compose-preview list                # scan @Preview annotations
compose-preview render              # render every @Preview to PNG
compose-preview render-matrix --id com.example.MyPreview --ui-mode light,dark --font-scale 1.0,2.0
                                    # one preview across a device × locale × uiMode × fontScale grid
```

For direct `./gradlew` use (e.g., a CI step that needs extra Gradle flags), materialise the same init script once and thread its path through each invocation:

```sh
INIT_SCRIPT="$(compose-preview init-script --path)"
./gradlew --init-script "$INIT_SCRIPT" :app:composePreviewDiscover
./gradlew --init-script "$INIT_SCRIPT" :app:composePreviewRenderAll
```

> **VS Code users:** the [`Compose Preview` extension](vscode-extension/) already auto-injects via `--init-script` on every Gradle invocation it makes — no extra setup needed.

The CLI's [auto-inject script](cli/src/main/kotlin/ee/schimke/composeai/cli/AutoInject.kt) detects projects that already declare the plugin (either literally as `id("ee.schimke.composeai.preview") version "..."` or via a `gradle/libs.versions.toml` alias resolved through `alias(libs.plugins.<x>)`) and skips the classpath injection for those builds, so mixed setups work without conflicts.

Requires Java 17+, Gradle 8.13+, AGP 8.13.0+ (Android), Kotlin 2.0.21+,
Compose Multiplatform 1.10.3+ (Desktop). The bottom edge of the supported
consumer envelope is exercised on every push by the
[`agp8-min` job](.github/workflows/integration.yml) against the fixture
under [`.github/ci/fixtures/agp8-min/`](.github/ci/fixtures/agp8-min/);
the project's own build runs on a newer toolchain (see
[`docs/AGENTS.md`](docs/AGENTS.md)).

## Samples

Source under [`samples/`](samples/). Rendered baselines (PNGs and animation
GIFs, regenerated on every push to `main`) are browsable inline on the
[`compose-preview/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)
branch:

- [`samples:android`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplesandroid) — phone, font-family showcase, scrolling captures, animation timelines.
- [`samples:wear`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#sampleswear) — Wear OS Material 3 Expressive, `EdgeButton`, tile previews.
- [`samples:cmp`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplescmp) — Compose Multiplatform Desktop.
- [`samples:remotecompose`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplesremotecompose) — Remote Compose against `wear-compose-remote-material3`.
- [`samples:xr-spatial`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplesxr-spatial) — Jetpack Compose for XR (`androidx.xr.compose`), 2D Home-Space fallback of `Orbiter` / `SpatialElevation` / `SpatialPanel` content.

ATF a11y findings for the same samples are on the
[`compose-preview/a11y/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/a11y/main)
branch.

## Integration previews

The [integration matrix](.github/workflows/integration.yml) renders the
plugin against real-world external Compose projects on every push to `main`.
Each render-enabled project publishes its own browsable
`compose-preview/integration/<slug>` branch — same gallery layout as
`compose-preview/main`, with a **CI notes** section recording any
workarounds the harness applied (consumer patches, stubbed credentials,
non-blocking status):

- [`wear-os-samples` (ComposeStarter)](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/integration/wear-os-samples) — full Android render + daemon round-trip, isolated-projects + configuration-cache.
- [`wear-os-samples` (WearTilesKotlin)](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/integration/wear-tiles) — Wear Tiles render path (`kind: TILE`).
- [`adaptive-apps-samples` (AdaptiveJetStream)](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/integration/jetstream-xr) — XR spatial Compose, rendered on the `androidx.xr.compose` alpha14 baseline.

## Agent PR hall of fame

Real-world PRs opened by AI coding agents that used `compose-preview` to
verify their changes.

<!-- Add interesting agent PRs here as they happen — link + one-liner. -->

- [`yschimke/meshcore-mobile#36`](https://github.com/yschimke/meshcore-mobile/pull/36) — renders Play Store listing screenshots (phone + 7"/10" tablet) directly from `Play Store — …` `@Preview` composables, replacing hand-crafted PNGs.

Have one to add? Open a PR or [an issue](https://github.com/yschimke/compose-ai-tools/issues/new).

## More

- [Documentation site](https://yschimke.github.io/compose-ai-tools/) — installation, VS Code Marketplace, and the per-product data-extension reference.
- [How it works](docs/HOW_IT_WORKS.md) — discovery, renderer, caching, project structure, plugin configuration.
- [CI install action](.github/actions/install/README.md) — pin the CLI on `$PATH` in any GitHub Actions job, with version-catalog + Renovate recipes.
- [Cloud sandbox setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md) — Claude Code on the web, network allowlist.
- [CI workflows](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/ci-previews.md) — `compose-preview/main` baselines, PR diff comments.
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source; consuming `-SNAPSHOT` builds.
- [Architecture (contributor)](docs/AGENTS.md) — class-by-class map of the four-stage pipeline.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)

## Reusable Codex PR review workflow (Preview-gated)

Use `.github/workflows/codex-pr-review-reusable.yml` to run AI PR review **only after** preview generation succeeds and with both code + visual context. The reusable workflow supports Codex, Claude, or Gemini based on which API key is configured (exactly one).

### Minimal caller setup
This repository wires the reusable workflow in `.github/workflows/codex-pr-review.yml` using a `preview` job plus a thin `uses:` call to the reusable workflow.

In this repository the unified `.github/workflows/compose-preview.yml` runs on every push to `main`, every PR, and `workflow_dispatch`. Consumer repos can split that into separate workflows (or keep one workflow per surface) based on their needs.


```yaml
name: PR Review (Codex + Preview)

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  preview:
    runs-on: ubuntu-latest
    outputs:
      preview_status: ${{ job.status }}
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew :app:composePreviewRenderAll
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-images
          path: app/build/compose-previews/renders
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-diff-images
          path: app/build/compose-previews/diffs
      - uses: actions/upload-artifact@v4
        with:
          name: compose-preview-metadata
          path: app/build/compose-previews/**/*.json

  codex-review:
    needs: [preview]
    uses: yschimke/compose-ai-tools/.github/workflows/codex-pr-review-reusable.yml@main
    with:
      preview_status: ${{ needs.preview.result }}
      strict_mode: true
    secrets:
      codex_api_key: ${{ secrets.CODEX_API_KEY }}
      claude_api_key: ${{ secrets.CLAUDE_API_KEY }}
      gemini_api_key: ${{ secrets.GEMINI_API_KEY }}
      github_token: ${{ secrets.GITHUB_TOKEN }}
```

### Artifact contract

- Agent selection is key-driven: set exactly one of `codex_api_key`, `claude_api_key`, or `gemini_api_key`.
- Codex has a built-in default command. Claude/Gemini require `claude_review_command` / `gemini_review_command` inputs unless you wrap them in your own caller.
- `compose-preview-images`: rendered head/PR preview images.
- `compose-preview-diff-images`: visual diffs (baseline vs PR/head), if your preview pipeline generates them.
- `compose-preview-baseline`: optional baseline images used to generate diffs.
- `compose-preview-metadata`: preview index and mapping files (for example: preview id → file path/module).

### Runtime/toolchain provided by reusable workflow

- Java 21 (`actions/setup-java`, `JAVA_HOME` from the action).
- Android SDK (`android-actions/setup-android`).
- `compose-preview-review` skill installation from `yschimke/skills`.
- Code diff capture (`git diff`) plus artifact download for visual review.

### Failure modes / troubleshooting

- **Preview failed/cancelled/skipped**: workflow posts a blocked comment and does not run Codex visual review.
- **Artifacts missing**: workflow posts a blocked comment with “missing context” details.
- **Strict mode enabled** + blocking findings: reusable workflow fails its check.
- **PR branch update** (`update_pr_branch`, default `true`): workflow attempts to commit `.codex/review-output/{codex-review.md,codex-review.json}` to the PR branch; for fork PRs or restricted tokens it skips with a warning.
- **No preview diffs available**: Codex still reviews code + available preview images and explicitly marks missing visual-diff context.

### Optional integration patterns

- `needs:` pattern (shown above): same workflow, same run.
- `workflow_run` pattern: trigger a second workflow after preview workflow completion and pass `preview_status: success` plus artifact names into the reusable workflow call.

### Example review comment template

```md
## Codex PR Review

### Code findings
- [blocking] `ui/ProfileCard.kt:84` Null-state branch removed; can crash in empty profile payload.
- [warning] `ui/Theme.kt:42` Hard-coded color bypasses design token.

### Preview findings
- [blocking] `ProfileCard_Default.png` text overlaps avatar at 320dp width.
- [warning] `SettingsScreen_Dark.png` contrast drop on secondary action.

### Missing context / blocked checks
- Baseline metadata for `WearSummaryPreview` missing.
- Visual diff for `TabletLandscape` not present in uploaded artifacts.
```

### Validation recipe with intentional bad UI change

1. Intentionally regress a composable (for example, shrink parent width and increase fixed text size to force clipping).
2. Run your preview job to regenerate preview images and visual diffs.
3. Open/update a PR and confirm:
   - Preview job succeeds.
   - Reusable Codex workflow runs after preview (`needs`/`workflow_run` gate).
   - PR comment includes both code and preview findings.
   - In strict mode, blocking visual regression findings fail the check.
