---
title: Install
layout: default
nav_order: 2
permalink: /install/
---

# Install

Most people don't need this page — the [home page](./) covers the one-step
paths for agents, VS Code, and the CLI. This page is the full reference:
every install surface, requirements, and CI recipes.

## CLI

The `compose-preview` binary works against any Compose project with **no
build edits** — it injects the preview plugin at runtime via a bundled
Gradle init script.

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

The installer drops the CLI on your `$PATH` and (unless you pass
`--cli-only`) the `compose-preview` / `compose-preview-review` agent skills
into the Claude / Codex / Gemini skill directories. Useful flags:

| Flag | Effect |
|------|--------|
| `--cli-only` | Install the CLI only, skip the skill bundles. |
| `--android-sdk` | Also install the Android `cmdline-tools` + platform + build-tools. |
| `--jdk 17,21` | Install the listed JDK majors. |
| `VERSION` | Install a specific release instead of latest. |

Then:

```sh
compose-preview doctor    # check Java + project compatibility
compose-preview list      # scan @Preview annotations
compose-preview render    # render every @Preview to PNG
compose-preview render-matrix --id com.example.MyPreview \
    --ui-mode light,dark --font-scale 1.0,2.0   # one preview across a grid
```

Because the CLI auto-injects, projects that already apply
`com.android.application` / `com.android.library` / `org.jetbrains.compose`
work without touching `build.gradle.kts`. Projects that already declare the
plugin are detected and left alone, so mixed setups don't conflict.

## Gradle plugin (version-pinned)

If you'd rather wire it into your build explicitly, the plugin is published
to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "<latest>"
}
```

Pin the version shown on Maven Central. (The in-repo `samples/` apply it
*without* a version because they resolve it from this repository's own
included build — that's not drop-in for an external project.)

```sh
./gradlew :app:composePreviewDiscover    # scan @Preview annotations
./gradlew :app:composePreviewRenderAll   # render every @Preview to PNG
```

For direct `./gradlew` use with the auto-inject script (e.g. a CI step that
needs extra Gradle flags), materialise the init script once and thread its
path through each invocation:

```sh
INIT_SCRIPT="$(compose-preview init-script --path)"
./gradlew --init-script "$INIT_SCRIPT" :app:composePreviewRenderAll
```

## VS Code extension

Published to the
[VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
(VSCodium / Cursor / Windsurf). Open the Extensions view (⇧⌘X /
Ctrl+Shift+X), search **Compose Preview**, click *Install*. It auto-injects
on every Gradle invocation it makes — no project changes needed.

## Agent skill

Point any agent that can fetch a URL at the
[`compose-preview` skill](https://github.com/yschimke/skills/blob/main/skills/compose-preview/SKILL.md)
— a complete install-and-iterate playbook. The skill checks whether the CLI
is present and bootstraps it (via the installer above) if not, so "point the
agent at the skill" and "run the installer" converge on the same place. See
[Agents & MCP](./mcp/) for the agent loop the skill drives.

## CI / GitHub Actions

Composite actions for pipelines:

- [`install`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/install) — pin the CLI on `$PATH`, with version-catalog + Renovate recipes.
- [`apply`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/apply) — unified pipeline: baselines on push, before/after PR comments, a11y + notification surfaces.

There's also a reusable, preview-gated AI PR-review workflow (Codex / Claude
/ Gemini): see
[PR review workflow](https://github.com/yschimke/compose-ai-tools/blob/main/docs/PR_REVIEW_WORKFLOW.md).

## Requirements

Java 17+, Gradle 8.13+, AGP 8.13.0+ (Android), Kotlin 2.0.21+, Compose
Multiplatform 1.10.3+ (Desktop). The bottom edge of this envelope is
exercised on every push by the
[`agp8-min` job](https://github.com/yschimke/compose-ai-tools/blob/main/.github/workflows/integration.yml).

Running an agent in a cloud sandbox (Claude Code on the web, etc.)? See the
[cloud sandbox setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md)
for the network allowlist and `install.sh --android-sdk` Setup recipe.
</content>
