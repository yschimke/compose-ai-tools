---
title: Home
layout: default
nav_order: 1
description: "Render @Preview composables to PNG so AI coding agents (and you) can see what they're changing."
permalink: /
---

# compose-ai-tools

**See your Compose UI without opening Android Studio.**

`compose-ai-tools` renders your `@Preview` composables to PNG from the
command line — so your AI coding agent can actually *look* at the screen it
just changed, and so can you. Works with Jetpack Compose (Android) and
Compose Multiplatform Desktop.

That's the whole idea. Everything else is optional.

[Get started](#get-started){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[Reference](./reference/){: .btn .fs-5 .mb-4 .mb-md-0 .mr-2 }
[GitHub](https://github.com/yschimke/compose-ai-tools){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Get started

**Start here: <https://ui.coo.ee/start>** — the guided start page for agents,
editors and the hosted catalogs.

### 🤖 With an AI coding agent

One command installs the `compose-preview` CLI and the default agent skills:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

Then ask your agent to preview a composable. The
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview)
tells it how to render, iterate and check its own work; an agent that can only
fetch URLs can read the
[SKILL.md](https://github.com/yschimke/skills/blob/main/skills/compose-preview/SKILL.md)
and bootstrap the CLI itself.

- Other install routes (`npx skills`, flags, updating):
  [setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/setup.md).
- Plugins and MCP wiring for Claude Code, Codex, Gemini/Antigravity, OpenCode:
  [host setup](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md).
- Which MCP server is which: [Agents & MCP](./mcp/#two-mcp-servers-local-and-hosted).

### 🧩 In VS Code (or Cursor / Windsurf / VSCodium)

Open the Extensions view (⇧⌘X / Ctrl+Shift+X), search **Compose Preview**,
click *Install*. It renders previews inline and needs no project changes.

[Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview) ·
[Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)

### ⌨️ From the command line

Install the CLI (same one-liner as above), then point it at any Compose
project — no build edits required:

```sh
compose-preview render    # render every @Preview to PNG
compose-preview browse    # Storybook-like local component catalog
```

The CLI injects itself into your build at runtime, so projects that already
use `com.android.application` / `com.android.library` /
`org.jetbrains.compose` just work.

### 🐘 With the Gradle plugin

Prefer a version-pinned plugin in your build? It's on
[Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no token. Replace `<published-version>` with the version shown there.

<!-- published-plugin-version-example -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "<published-version>"
}
```

```sh
./gradlew :app:composePreviewRenderAll   # render every @Preview to PNG
```

Render filters, CI recipes and requirements are on the [Install page](./install/).

---

## Going further

Once you're rendering PNGs, there's more your agent can do — but none of it
is required to get value from the tool.

- **[Data products](./reference/)** — alongside each PNG the renderer can
  emit structured data: accessibility findings, layout trees, theme tokens,
  recomposition heat maps, drawn text, and more. One page per product.
- **[MCP server](./mcp/)** — a push-based, token-frugal agent loop over
  Compose UI (target by semantic ref, observe semantics instead of pixels,
  diff renders, generate tests). The aria-snapshot story for Compose.
- **[Daemon](./daemon/)** — a long-lived renderer that keeps Robolectric /
  Compose-Desktop warm so re-renders are fast.

---

## More

- **[How it works](https://github.com/yschimke/compose-ai-tools/blob/main/docs/HOW_IT_WORKS.md)** — discovery, renderer pipeline, caching.
- **[Samples](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)** — rendered baselines for `samples:android`, `samples:wear`, `samples:cmp`, `samples:remotecompose`, regenerated on every push to `main`.
- **[Releases](https://github.com/yschimke/compose-ai-tools/releases)** · **[Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)** · **[License](https://github.com/yschimke/compose-ai-tools/blob/main/LICENSE)** (Apache 2.0).
</content>
</invoke>
