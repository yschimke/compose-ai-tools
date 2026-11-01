---
title: Home
layout: default
nav_order: 1
description: "Render @Preview composables to PNG so AI coding agents (and you) can see what they're changing."
permalink: /
---

# compose-ai-tools

**Let your coding agent see the Compose UI it just changed.**

`compose-ai-tools` renders your `@Preview` composables to PNG outside Android
Studio, so your agent can *look* at its work, and so can you. Jetpack Compose
(Android) and Compose Multiplatform Desktop.

[Get started](#get-started){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[Reference](./reference/){: .btn .fs-5 .mb-4 .mb-md-0 .mr-2 }
[GitHub](https://github.com/yschimke/compose-ai-tools){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Get started

Pick your agent and run its one install.

**Claude Code**

```text
/plugin marketplace add yschimke/compose-agent-plugins
/plugin install compose-preview@compose-agent-plugins
```

**Codex**

```sh
codex plugin marketplace add yschimke/compose-agent-plugins
```

then enable `compose-preview` from `/plugins`.

**Antigravity**

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-agent-plugins/main/scripts/antigravity-install.py | python3 -
```

**Any other agent** (Gemini CLI, OpenCode, Cursor, Copilot, …), or no plugin system:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

Then: **ask your agent to render one of your previews.** That's the whole onboarding.

Designing a new screen instead? Start at <https://ui.coo.ee/start>.

[Other hosts and install options](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md)

### Without an agent

**VS Code** (or Cursor / Windsurf / VSCodium): search the Extensions view for
**Compose Preview** and click *Install*. It renders previews inline and needs no
project changes.
([Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview) ·
[Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview))

**Command line.** The `install.sh` one-liner above also installs the
`compose-preview` CLI. Point it at any Compose project, no build edits required:

```sh
compose-preview render    # render every @Preview to PNG
compose-preview browse    # Storybook-like local component catalog
```

**Gradle plugin.** Prefer a version-pinned plugin in your build? It's on
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

## When you need more

None of this is required to get value from the tool. Your agent will offer the
relevant one when it comes up.

- **PR review and preview CI.** Before/after renders posted on every pull
  request by the
  [`apply`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/apply)
  action, and the
  [`compose-preview-review`](https://github.com/yschimke/skills/tree/main/skills/compose-preview-review)
  and [`compose-preview-ci`](https://github.com/yschimke/skills/tree/main/skills/compose-preview-ci)
  skills that review a UI PR and set that CI up. Claude Code:
  `/plugin install compose-review@compose-agent-plugins`; elsewhere,
  `install.sh --skills compose-preview-review,compose-preview-ci`.
- **[UI Builder](https://ui.coo.ee/start)** — design a new screen with an agent
  in the browser, then export it as Compose code.
- **[Whole-app simulator](https://github.com/yschimke/compose-ai-tools/blob/main/docs/SIMULATOR.md)**
  — `compose-preview launch app.apk` runs an APK without an emulator, for
  navigation, deep links, Wear OS and more.
- **[Data products](./reference/)** — structured data alongside each PNG:
  accessibility findings, layout trees, theme tokens, recomposition heat maps,
  drawn text, and more.
- **[Agents & MCP](./mcp/)** — the local and hosted MCP servers, and a
  token-frugal agent loop over Compose UI (semantic refs, semantics diffs,
  recording to tests).
- **[Daemon](./daemon/)** — a long-lived renderer that keeps Robolectric /
  Compose-Desktop warm so re-renders are fast.

---

## More

- **[How it works](https://github.com/yschimke/compose-ai-tools/blob/main/docs/HOW_IT_WORKS.md)** — discovery, renderer pipeline, caching.
- **[Samples](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)** — rendered baselines for `samples:android`, `samples:wear`, `samples:cmp`, `samples:remotecompose`, regenerated on every push to `main`.
- **[Releases](https://github.com/yschimke/compose-ai-tools/releases)** · **[Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)** · **[License](https://github.com/yschimke/compose-ai-tools/blob/main/LICENSE)** (Apache 2.0).
