---
name: compose-preview
description: Render Compose @Preview functions to PNG outside Android Studio. Use this to verify UI changes, iterate on designs, and compare before/after states across Android (Jetpack Compose) and Compose Multiplatform Desktop projects.
---

# Compose Preview

Render `@Preview` composables to PNG images without launching Android Studio.
Works on both Android (Jetpack Compose via Robolectric) and Compose Multiplatform
Desktop (via `ImageComposeScene` + Skia).

## Source

This skill is maintained at
[github.com/yschimke/compose-ai-tools](https://github.com/yschimke/compose-ai-tools)
under `skills/compose-preview/`. The bundle this documentation ships with is
<!-- x-release-please-start-version -->
**v0.9.1**.
<!-- x-release-please-end -->

To check the locally installed version, run `compose-preview --version` (it
also lives at `~/.claude/skills/compose-preview/.skill-version`). Run
`compose-preview doctor` to compare against the latest GitHub release — it
emits an `env.bundle-version` line and warns when the installed bundle
trails. To upgrade, run `compose-preview update`, which re-runs the
[bootstrap installer](https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh).

## What this skill provides

- A Gradle plugin (`ee.schimke.composeai.preview`) that discovers `@Preview`
  annotations from compiled classes and registers rendering tasks.
- A `compose-preview` CLI that drives the Gradle build via the Tooling API
  and surfaces rendered PNG paths.
- A VS Code extension with a preview panel, CodeLens and hover actions on
  `@Preview` functions, and commands for rendering all or a single file.

## Gradle tasks

Applied to each module that declares the plugin:

| Task | Purpose |
|------|---------|
| `:<module>:discoverPreviews` | Scan compiled classes, emit `build/compose-previews/previews.json`. |
| `:<module>:renderAllPreviews` | Discover + render every `@Preview` to PNG under `build/compose-previews/`. |
| `:<module>:discoverAndroidResources` | Walk `res/drawable*` + `res/mipmap*`, parse `AndroidManifest.xml`, emit `build/compose-previews/resources.json`. See [design/RESOURCE_PREVIEWS.md](./design/RESOURCE_PREVIEWS.md). |
| `:<module>:renderAndroidResources` | Render every discovered XML drawable / mipmap to PNG / GIF under `build/compose-previews/renders/resources/`. |

Both are Gradle-cacheable with strict configuration caching — unchanged inputs
produce no re-work.

## CLI

The CLI auto-detects the Gradle project root (walks up for `gradlew`) and, by
default, every module that has the plugin applied.

```
compose-preview <command> [options]

Commands:
  show     Discover + render previews; print id, path, sha256, changed flag
  list     List discovered previews
  render   Render previews; with --output copies a single match to disk
  a11y     Render previews and print ATF accessibility findings
  extensions run a11y-annotated-preview.render
           One-shot a11y hierarchy + ATF + annotated overlay render
  doctor   Verify Java 17+ + project compatibility (run before Setup)

Options:
  --module <name>      Target a single module (default: auto-detect)
  --variant <variant>  Android build variant (default: debug)
  --filter <pattern>   Case-insensitive substring match on preview id
  --id <exact>         Exact match on preview id
  --json               Emit JSON (show, list)
  --output <path>      Copy matched preview PNG to this path (render)
  --progress           Print per-task milestone/heartbeat lines to stderr
  --verbose, -v        Full Gradle build output (implies --progress)
  --timeout <seconds>  Gradle build timeout (default: 300)
```

OSC 9;4 terminal progress (native taskbar/tab progress bar) is on by default
in a TTY and auto-disables when stdout is piped. Textual progress lines are
off by default and opt-in via `--progress`.

Exit codes: `0` success, `1` build failure, `2` render failure, `3` no previews.

JSON output per entry includes the full `PreviewParams` (device, widthDp,
heightDp, fontScale, uiMode, …), the absolute `pngPath`, the `sha256` of
the PNG bytes, and a `changed` boolean computed against the previous
invocation. State is persisted per-module under
`<module>/build/compose-previews/.cli-state.json` and gets wiped by
`./gradlew clean`.

## Workflow: iterate on a design

1. **List** previews: `compose-preview list` (optionally `--filter <name>` or
   `--id <exact>`).
2. **Render** current state: `compose-preview show --json`. Each entry includes
   the absolute `pngPath`, its `sha256`, and a `changed` flag relative to the
   previous invocation — read the PNG to view the image.
3. **Edit** the composable.
4. **Re-render**: `compose-preview show --json` again. Gradle task caching reruns
   only what changed; agents can inspect `changed: true` entries to know
   which PNGs need re-reading, avoiding wasted reads of unchanged images.
5. **Verify visually** — always read the PNG after a UI change. Don't assume
   the change looks correct.

## Designing composables for previewability

`@Preview` only calls composables with zero arguments (or all-default), so
anything taking a `ViewModel`, repository, or DI-injected service can't be
previewed directly. Apply **state hoisting**: split each screen into a
stateful wrapper (wires runtime deps) and a stateless inner composable that
takes state + callbacks. Preview the stateless layer with hand-rolled
fixtures.

**Agent guidance:** if you're asked to iterate on a composable that accepts
a ViewModel or injected dependency, **first propose extracting a stateless
inner composable** and preview that instead. The one-time extraction unlocks
the fast `compose-preview` iteration loop for every future change on that
screen.

See [design/STATE_HOISTING.md](./design/STATE_HOISTING.md) for the full
pattern with code.

## Setup

The plugin is published to Maven Central, so no credentials or registry
configuration is required. Most projects already have `mavenCentral()` in
their plugin repositories.

Bootstrap the CLI and verify the environment:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash

compose-preview doctor
```

From a Compose project root, bootstrap the MCP descriptors. When this runs
inside Antigravity, it also installs the server into Antigravity's MCP config:

```sh
compose-preview mcp install
```

Outside Antigravity, use `compose-preview mcp install --antigravity` to force
the same config write.

`doctor` verifies Java 17+ on `PATH` (JDK 21/25 are fine — the renderer is
compiled to JDK 17 bytecode). If the install path isn't on `PATH`, the script
prints the exact command to add it.

Apply the plugin in `<module>/build.gradle.kts`:

<!-- x-release-please-start-version -->
```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.9.1"
}

composePreview {
    variant.set("debug")   // Android build variant (default: "debug")
    sdkVersion.set(35)     // Robolectric SDK version (default: 35)
    enabled.set(true)      // set false to skip task registration
}
```
<!-- x-release-please-end -->

CMP Desktop projects additionally need
`implementation(compose.components.uiToolingPreview)` — the bundled `@Preview`
annotation has `SOURCE` retention and is invisible to classpath scanning
otherwise.

The Android variant relies on Robolectric with native graphics; the plugin
takes care of the relevant test/tooling dependencies. Agents MUST NOT run
internal tasks like `collectPreviewInfo` — they're wired by the plugin itself.

## Reference docs

Loaded on demand. Read only what the current task needs.

| Path | When to read |
|---|---|
| [design/PERMISSIONS.md](./design/PERMISSIONS.md) | Setting up agent allowlists; staging PNGs outside `build/`. |
| [design/STATE_HOISTING.md](./design/STATE_HOISTING.md) | Full state-hoisting pattern with code examples. |
| [design/CAPTURE_MODES.md](./design/CAPTURE_MODES.md) | Multi-preview annotations, `@AnimatedPreview` GIFs, MCP scripted recordings, paused-clock snapshots, scrolling captures. |
| [design/A11Y.md](./design/A11Y.md) | ATF accessibility checks (`compose-preview a11y`). |
| [design/DATA_PRODUCTS.md](./design/DATA_PRODUCTS.md) | Structured per-render data (a11y findings + hierarchy, layout tree, recomposition heat-map, …) via MCP tools and on-disk Gradle output. |
| [design/MCP.md](./design/MCP.md) | Driving compose-preview from an MCP-aware agent host (push notifications, multi-workspace, in-process server bundled in the CLI). |
| [design/CMP_SHARED.md](./design/CMP_SHARED.md) | Compose Multiplatform `:shared` modules (`commonMain` previews via Desktop pipeline). |
| [design/RESOURCE_PREVIEWS.md](./design/RESOURCE_PREVIEWS.md) | Android XML resources (`<vector>`, `<animated-vector>`, `<adaptive-icon>`). |
| [design/WEAR_UI.md](./design/WEAR_UI.md) | Wear OS Material 3 Expressive design. |
| [design/WEAR_TILES.md](./design/WEAR_TILES.md) | Wear Tiles (protolayout, not Compose). |
| [design/REMOTE_COMPOSE.md](./design/REMOTE_COMPOSE.md) | Remote Compose dialect + `RemoteDocument`. |
| [design/CLAUDE_CLOUD.md](./design/CLAUDE_CLOUD.md) | Running compose-preview in Claude Code cloud sandboxes (allowlist, JDK, install paths). |
| [design/VSCODE.md](./design/VSCODE.md) | VS Code extension (humans, not agents). |

## Related skill

PR-review and CI workflows live in the sibling
[**compose-preview-review** skill](../compose-preview-review/SKILL.md):
authoring agent-opened PRs, reviewing UI PRs locally (base + head render,
diff, text comment), and wiring `compose-preview/main` baselines +
PR-comment GitHub Actions. The bootstrap installer
([`scripts/install.sh`](https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh))
sets up both skills together.

## Tips

- First render is slow (module compile + renderer bootstrap); later renders
  reuse Gradle caching and are much faster.
- Resource changes (`.xml`, `.json`) trigger recompilation and re-render on the
  next task run.
- Always visually verify after UI changes — show the user the before and after
  PNG.
- Iterate on a single variant first (e.g. `small_round` at 1x font scale), then
  follow up with fixes for other sizes and scales.
- Use a coloured border or an overlay `Canvas` when highlighting something
  specific for the user.
