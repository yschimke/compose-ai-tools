# compose-ai-tools

**See your Compose UI without opening Android Studio.**

`compose-ai-tools` renders your `@Preview` composables to PNG from the
command line — so your AI coding agent can actually *look* at the screen it
just changed, and so can you. Works with Jetpack Compose (Android, via
Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

That's the whole idea. Everything else on this page is optional.

**[📖 Documentation](https://yschimke.github.io/compose-ai-tools/)** ·
[Install](https://yschimke.github.io/compose-ai-tools/install/) ·
[Reference](https://yschimke.github.io/compose-ai-tools/reference/)

## Get started

Pick the one that fits you. Each is a single step.

### 🤖 With an AI coding agent

Run the one-line installer once. It drops the `compose-preview` CLI **and**
the agent skill into place (Claude Code, Codex, Gemini):

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

Then just ask your agent to preview a composable. The
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview)
is the playbook — it tells the agent how to render, iterate, and check its own
work. You don't have to learn the commands; the agent reads the skill. (If
your agent can fetch URLs but not run the installer, point it straight at the
[SKILL.md](https://github.com/yschimke/skills/blob/main/skills/compose-preview/SKILL.md)
— it bootstraps the CLI itself.)

### 🧩 In VS Code (or Cursor / Windsurf / VSCodium)

Open the Extensions view (⇧⌘X / Ctrl+Shift+X), search **Compose Preview**,
click *Install*. It renders previews inline and needs no project changes.
([Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview) ·
[Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview))

### ⌨️ From the command line

Install the CLI (same one-liner as above), then point it at any Compose
project — **no build edits required**:

```sh
compose-preview render    # render every @Preview to PNG
```

The CLI injects itself into your build at runtime, so projects that already
apply `com.android.application` / `com.android.library` /
`org.jetbrains.compose` just work.

> Prefer a version-pinned Gradle plugin? It's on
> [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
> (no auth, no token). See the
> [Install page](https://yschimke.github.io/compose-ai-tools/install/) for
> that, CI recipes, and the full requirements (Java 17+, Gradle 8.13+, AGP
> 8.13.0+, Kotlin 2.0.21+).

## What else it can do

None of this is required to get value from the tool — it's there when you want
more than a PNG.

- **[Data products](https://yschimke.github.io/compose-ai-tools/reference/)** —
  alongside each PNG the renderer can emit structured data: accessibility
  findings, layout trees, theme tokens, recomposition heat maps, drawn text,
  resource captures, and more. One page per product.
- **[Agents & MCP](https://yschimke.github.io/compose-ai-tools/mcp/)** — a
  push-based, token-frugal agent loop over Compose UI: target by semantic ref,
  observe semantics instead of pixels, diff renders, turn recordings into
  tests. The aria-snapshot story for Compose.
- **[Daemon](https://yschimke.github.io/compose-ai-tools/daemon/)** — an
  optional long-lived renderer that keeps Robolectric / Compose-Desktop warm
  so re-renders are fast.

## Samples

Rendered baselines (PNGs and animation GIFs, regenerated on every push to
`main`) are browsable inline on the
[`compose-preview/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)
branch — `samples:android`, `samples:wear`, `samples:cmp`,
`samples:remotecompose`, `samples:xr-spatial`. Source under
[`samples/`](samples/). The
[integration matrix](.github/workflows/integration.yml) also renders the
plugin against real-world external Compose projects on every push.

## Agent PR hall of fame

Real-world PRs opened by AI coding agents that used `compose-preview` to
verify their changes.

<!-- Add interesting agent PRs here as they happen — link + one-liner. -->

- [`yschimke/meshcore-mobile#36`](https://github.com/yschimke/meshcore-mobile/pull/36) — renders Play Store listing screenshots (phone + 7"/10" tablet) directly from `Play Store — …` `@Preview` composables, replacing hand-crafted PNGs.

Have one to add? Open a PR or [an issue](https://github.com/yschimke/compose-ai-tools/issues/new).

## More

- [Documentation site](https://yschimke.github.io/compose-ai-tools/) — install, reference, agents & MCP, daemon.
- [How it works](docs/HOW_IT_WORKS.md) — discovery, renderer, caching, project structure.
- [PR review workflow](docs/PR_REVIEW_WORKFLOW.md) — reusable, preview-gated AI PR review (Codex / Claude / Gemini).
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source; consuming `-SNAPSHOT` builds.
- [Architecture (contributor)](docs/AGENTS.md) — class-by-class map of the four-stage pipeline.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)
</content>
