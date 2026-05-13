# compose-ai-tools

**[Documentation](https://yschimke.github.io/compose-ai-tools/)** —
install, VS Code Marketplace, and one-page-per-product reference for
each data extension.

Render `@Preview` composables to PNG outside Android Studio, so AI coding
agents can see what they're changing. Works with Jetpack Compose (Android,
via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

Renders include
[paused-clock animation captures](skills/compose-preview/design/CAPTURE_MODES.md#animations-and-the-paused-frame-clock-android-only)
(GIF or single frame) and opt-in
[ATF accessibility checks](skills/compose-preview/design/A11Y.md)
with annotated overlays.

Also renders [Android XML resources](skills/compose-preview/design/RESOURCE_PREVIEWS.md) —
vector drawables, adaptive launcher icons, animated-vector drawables — and indexes the icon
attributes in `AndroidManifest.xml` so tooling can link manifest lines to the same rendered PNG.
Modules without any matching resources self-no-op, so this comes along for free with the plugin.

## What it ships

- **Agent skill** — [`skills/compose-preview/SKILL.md`](skills/compose-preview/SKILL.md).
  Point any agent that can fetch a URL at it; the skill is a complete
  install-and-iterate playbook. Bootstrap a host machine with
  [`scripts/install.sh`](scripts/install.sh).
- **VS Code extension** — published to the
  [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
  and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
  (for VSCodium / Cursor / Windsurf). Install from inside the IDE: open
  the Extensions view (⇧⌘X / Ctrl+Shift+X), search **Compose Preview**,
  click *Install*. Source in [`vscode-extension/`](vscode-extension/).
- **GitHub Actions** — composite actions for CI:
  [`install`](.github/actions/install/) (CLI on `$PATH`),
  [`preview-baselines`](.github/actions/preview-baselines/) (push baselines),
  [`preview-comment`](.github/actions/preview-comment/) (before/after PR
  comments), [`a11y-report`](.github/actions/a11y-report/) (accessibility
  findings).

## Setup

The plugin is published to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.10.10"
}
```
<!-- x-release-please-end -->

Working examples: [`samples/android/build.gradle.kts`](samples/android/build.gradle.kts),
[`samples/wear/build.gradle.kts`](samples/wear/build.gradle.kts),
[`samples/cmp/build.gradle.kts`](samples/cmp/build.gradle.kts).

### Zero-Code Integration (Alternative)

You can apply the plugin dynamically without modifying the project's source code by using a Gradle init script. This is particularly useful for AI agents or when exploring the tool without committing changes to the repository.

To use this method:
1. Create a Gradle init script (e.g., `~/.gradle/init.d/compose-ai-tools.gradle`) that resolves the plugin and applies it to Android application projects.
2. Control its application with an environment variable (e.g., `COMPOSE_AI_TOOLS=true`).

See [skills/compose-preview/SKILL.md](skills/compose-preview/SKILL.md) for a sample init script and details.

Then:

```sh
./gradlew :app:discoverPreviews    # scan @Preview annotations
./gradlew :app:renderAllPreviews   # render every @Preview to PNG
```

Requires Java 17+, Gradle 9.4.1+, AGP 9.1+ (Android), Kotlin 2.2.21,
Compose Multiplatform 1.10.3 (Desktop).

## Samples

Source under [`samples/`](samples/). Rendered baselines (PNGs and animation
GIFs, regenerated on every push to `main`) are browsable inline on the
[`compose-preview/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)
branch:

- [`samples:android`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplesandroid) — phone, font-family showcase, scrolling captures, animation timelines.
- [`samples:wear`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#sampleswear) — Wear OS Material 3 Expressive, `EdgeButton`, tile previews.
- [`samples:cmp`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplescmp) — Compose Multiplatform Desktop.
- [`samples:remotecompose`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main#samplesremotecompose) — Remote Compose against `wear-compose-remote-material3`.

ATF a11y findings for the same samples are on the
[`compose-preview/a11y/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/a11y/main)
branch.

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
- [Cloud sandbox setup](skills/compose-preview/design/AGENT_CLOUD.md) — Claude Code on the web, network allowlist.
- [CI workflows](skills/compose-preview-review/design/CI_PREVIEWS.md) — `compose-preview/main` baselines, PR diff comments.
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source; consuming `-SNAPSHOT` builds.
- [Architecture (contributor)](docs/AGENTS.md) — class-by-class map of the four-stage pipeline.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)

## Reusable Codex PR review workflow (Preview-gated)

Use `.github/workflows/codex-pr-review-reusable.yml` to run AI PR review **only after** preview generation succeeds and with both code + visual context. The reusable workflow supports Codex, Claude, or Gemini based on which API key is configured (exactly one).

### Minimal caller setup
This repository wires the reusable workflow in `.github/workflows/codex-pr-review.yml` using a `preview` job plus a thin `uses:` call to the reusable workflow.

To avoid duplicate PR review comments in this repository, `.github/workflows/preview-comment.yml` is kept manual (`workflow_dispatch`). Consumer repos can still choose either workflow (or both) based on their needs.


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
      - run: ./gradlew :app:renderAllPreviews
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
- `compose-preview-review` skill installation from `yschimke/compose-ai-tools`.
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
