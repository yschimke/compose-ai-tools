---
title: Home
layout: default
nav_order: 1
description: "Render @Preview composables to PNG so AI coding agents can see what they're changing."
permalink: /
---

# compose-ai-tools

Render `@Preview` composables to PNG outside Android Studio, so AI coding
agents can see what they're changing. Works with Jetpack Compose
(Android, via Robolectric) and Compose Multiplatform Desktop (via
`ImageComposeScene`).

Alongside each PNG the renderer can emit **structured data products** —
ATF accessibility findings, layout-inspector trees, recomposition heat
maps, resolved theme tokens, drawn text, and more — so an agent can
reason about the UI, not just look at it.

[Get started](#installation){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[Reference](./reference/){: .btn .fs-5 .mb-4 .mb-md-0 .mr-2 }
[GitHub](https://github.com/yschimke/compose-ai-tools){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## The agent loop

A tight, token-frugal feedback loop over Compose UI — the way Playwright gave
web agents one over the DOM. Exposed by the preview daemon's MCP server (and
the `compose-preview` CLI where noted):

- **Target by semantic ref, not pixels** — drive a preview by `testTag` /
  `role`+`text` / a stable node `ref`; the daemon resolves it to the node's
  centre, so interactions survive layout changes (Android + Desktop).
- **Token-frugal observation** — `render_preview observe=semantics|hash`
  returns the semantics tree + hash + dimensions (a few hundred tokens)
  instead of a base64 PNG; fetch pixels only when you need to look.
- **Semantics diff** — `diff_semantics` / `compose-preview diff-semantics`
  report what changed *semantically* between two renders, a deterministic
  pixel-free regression signal (the aria-snapshot diff for Compose).
- **Matrix render** — `render_matrix` (and the `compose-preview render-matrix`
  CLI) covers `device × locale × uiMode × fontScale` in one call with per-cell
  hashes, a "which changed" flag, and an optional stitched contact-sheet image.
- **Recording → test** — `record_preview emitTest=true` emits a runnable
  Compose UI test from a scripted interaction, inferring assertions at each
  `recording.probe` from the captured semantics.
- **Structured failures** — a failed render reports a typed kind + a one-line
  fix hint instead of an opaque message.

---

## Installation

### Gradle plugin

The plugin is published to
[Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.15.10"
}
```
<!-- x-release-please-end -->

Then:

```sh
./gradlew :app:composePreviewDiscover    # scan @Preview annotations
./gradlew :app:composePreviewRenderAll   # render every @Preview to PNG
```

Requires Java 17+, Gradle 8.13+, AGP 8.13.0+ (Android), Kotlin 2.0.21+,
Compose Multiplatform 1.10.3+ (Desktop).

### VS Code extension

Published to the
[VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
(for VSCodium / Cursor / Windsurf).

Install from inside the IDE: open the Extensions view (⇧⌘X /
Ctrl+Shift+X), search **Compose Preview**, click *Install*.

### CLI

Install on `$PATH` for shell or agent use via the bootstrap script:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

### Agent skill

Point any agent that can fetch a URL at
[`skills/compose-preview/SKILL.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/SKILL.md)
— the skill is a complete install-and-iterate playbook.

### CI / GitHub Actions

Composite actions for CI pipelines:

- [`install`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/install) — pin the CLI on `$PATH`.
- [`apply`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/apply) — unified pipeline: baselines on push, before/after PR comments, a11y + notification surfaces.

---

## Where next?

- **[Reference](./reference/)** — one page per data product (a11y,
  layout, theme, recomposition, scroll captures, …): what it is, what
  it answers, and how to enable it.
- **[How it works](https://github.com/yschimke/compose-ai-tools/blob/main/docs/HOW_IT_WORKS.md)** — discovery, renderer pipeline, caching.
- **[Samples](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)** — rendered baselines for `samples:android`, `samples:wear`, `samples:cmp`, `samples:remotecompose`, regenerated on every push to `main`.
- **[Releases](https://github.com/yschimke/compose-ai-tools/releases)** · **[Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)** · **[License](https://github.com/yschimke/compose-ai-tools/blob/main/LICENSE)** (Apache 2.0).
