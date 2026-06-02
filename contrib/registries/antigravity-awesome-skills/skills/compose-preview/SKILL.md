---
name: compose-preview
description: "Render Jetpack Compose and Compose Multiplatform @Preview functions to PNG outside Android Studio to verify, iterate on, and compare UI changes."
category: developer-tools
risk: safe
source: community
source_repo: yschimke/skills
source_type: community
date_added: "2026-06-02"
author: yschimke
tags: [android, jetpack-compose, compose-multiplatform, ui, screenshot, preview, kotlin]
tools: [claude, cursor, gemini, codex, antigravity]
license: "Apache-2.0"
license_source: "https://github.com/yschimke/skills/blob/main/LICENSE"
---

# Compose Preview

## Overview

Render `@Preview` composables to PNG images without launching Android Studio.
It works on both Android (Jetpack Compose via Robolectric) and Compose
Multiplatform Desktop (via `ImageComposeScene` + Skia), so an agent can see
exactly what a screen looks like, iterate on the code, and re-render to confirm
the change — all from the command line. This is the canonical
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview)
distributed through the [compose-ai-tools](https://github.com/yschimke/compose-ai-tools)
project.

## When to Use This Skill

- Use when you change a Compose `@Preview` and want to *see* the rendered UI
  before claiming the change works.
- Use when iterating on a layout, theme, typography, or spacing tweak and you
  need a fast render/look/adjust loop without an emulator or device.
- Use when comparing before/after states of a composable, or capturing a screen
  for a PR, design review, or bug report.
- Use when working in a headless environment (CI, a remote agent sandbox) where
  Android Studio is not available.

## How It Works

### Step 1: Install the CLI and skill

The canonical installer lives in `yschimke/skills` and bootstraps the
`compose-preview` CLI plus the skill files:

```bash
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

For Android targets, pass `--android-sdk` to provision the SDK in a fresh
environment. See the
[agent-cloud reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md)
for network-allowlist and cloud-sandbox setup.

### Step 2: Apply the Gradle plugin

In the module that holds the previews, apply the plugin:

```kotlin
plugins {
    id("ee.schimke.composeai.preview")
}
```

### Step 3: Render a preview

```bash
# Render every @Preview in a module to PNGs under build/compose-previews/.
compose-preview render :samples:cmp

# Render a single preview by fully-qualified name.
compose-preview render :samples:cmp --preview com.example.RedSquare
```

### Step 4: Iterate

Edit the composable, re-run the render, and diff the PNGs. For a live,
push-on-change render loop, attach the MCP server (see
[references/mcp.md](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/mcp.md))
so renders refresh automatically as you edit source files.

## Examples

### Example 1: Verify a spacing change on Compose Multiplatform Desktop

```bash
# 1. Render the baseline.
compose-preview render :samples:cmp --preview com.example.ProfileCard
# 2. Edit ProfileCard.kt (adjust padding), then re-render.
compose-preview render :samples:cmp --preview com.example.ProfileCard
# 3. Compare the two PNGs under build/compose-previews/.
```

### Example 2: Capture a Wear OS preview for a PR

```bash
compose-preview render :samples:wear --preview com.example.WatchFace
```

See the
[Wear UI reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/wear-ui.md)
for Material 3 Expressive guidance on Wear.

## Best Practices

- ✅ Render after every meaningful UI edit and actually look at the PNG before
  declaring success.
- ✅ Hoist state out of composables so a `@Preview` can supply deterministic
  inputs (see
  [state-hoisting.md](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/state-hoisting.md)).
- ✅ Stage rendered PNGs under `build/` so they stay out of version control.
- ❌ Don't claim a UI change works from reading the diff alone — render it.
- ❌ Don't commit generated PNGs into the source tree.

## Limitations

- This skill does not replace environment-specific validation, testing, or
  expert review.
- Android rendering uses Robolectric, so behavior that depends on a real device
  GPU, hardware sensors, or runtime permissions is out of scope.
- Cold start for the Android (Robolectric) renderer is ~5–10s; the Desktop
  renderer is ~600ms.
- Stop and ask for clarification if the project does not apply the plugin, or if
  required inputs, permissions, or safety boundaries are missing.

## Security & Safety Notes

- The installer is fetched over HTTPS and piped to `bash`. Review the
  [canonical installer](https://github.com/yschimke/skills/blob/main/scripts/install.sh)
  before running it in a sensitive environment.

<!-- security-allowlist: documented installer fetch for the compose-preview toolchain -->

- Rendering runs Gradle tasks and spawns per-module daemon JVMs against your
  project's build output. Run it in the project you intend to build; treat it
  like any other local Gradle invocation.
- No network egress is required to render once the toolchain and SDK are in
  place.

## Common Pitfalls

- **Problem:** `compose-preview render` reports no previews found.
  **Solution:** Confirm the module applies `id("ee.schimke.composeai.preview")`
  and that the `@Preview` functions are discoverable (top-level or in a class
  the discovery task can see).
- **Problem:** Android renders fail in a fresh cloud sandbox.
  **Solution:** Re-run the installer with `--android-sdk` and check the
  [agent-cloud reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md)
  for the network allowlist.

## Related Skills

- `@compose-preview-review` - Use when reviewing a Compose UI pull request:
  renders previews on base and head and diffs the screenshots.
