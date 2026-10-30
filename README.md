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

**Start here: <https://yschimke.github.io/compose-ai-tools/>** — rendering and inspecting your project's previews
with the CLI, an agent, VS Code or the Gradle plugin. Designing a new screen
with an agent in the UI Builder? That flow starts at <https://ui.coo.ee/start>.

### 🤖 With an AI coding agent

One command installs the `compose-preview` CLI and the default agent skills:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

Then ask your agent to preview a composable. The
[`compose-preview` skill](https://github.com/yschimke/skills/tree/main/skills/compose-preview)
is the playbook — you don't have to learn the commands; the agent reads the skill.

- Other install routes (`npx skills`, flags, opt-in skills, updating):
  [setup](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/setup.md).
- Plugins and MCP wiring for Claude Code, Codex, Gemini/Antigravity and OpenCode:
  [host setup](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md).
- The hosted catalog MCP server is `https://preview.coo.ee/mcp`; configure it once.
  Local vs hosted MCP: [Agents & MCP](https://yschimke.github.io/compose-ai-tools/mcp/#two-mcp-servers-local-and-hosted).

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
compose-preview browse    # open the streamlined component browser
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
  tests. The aria-snapshot story for Compose. The same native MCP process can
  optionally connect agents to a concurrent preview-server UI-builder session;
  see the [MCP design reference](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/MCP.md#remote-ui-builder-tools).
- **[Daemon](https://yschimke.github.io/compose-ai-tools/daemon/)** — an
  optional long-lived renderer that keeps Robolectric / Compose-Desktop warm
  so re-renders are fast.

## Samples

Rendered baselines (PNGs and animation GIFs, regenerated on every push to
`main`) are browsable inline on the
[`compose-preview/main`](https://github.com/yschimke/compose-ai-tools/tree/compose-preview/main)
branch — `samples:android`, `samples:wear`, `samples:cmp`, and
`samples:remotecompose`. Source under [`samples/`](samples/). The XR renderer and its spatial
sample live in [`compose-preview-xr`](https://github.com/yschimke/compose-preview-xr). The
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
- [Configuration-only plugin](docs/CONFIG_ONLY_PLUGIN.md) — commit `composePreview { }` config to your build without pinning the rendering runtime.
- [PR review workflow](docs/PR_REVIEW_WORKFLOW.md) — reusable, preview-gated AI PR review (Codex / Claude / Gemini).
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source; testing unreleased builds locally.
- [Architecture (contributor)](docs/AGENT_GUIDE.md) — class-by-class map of the four-stage pipeline.
- [Contributor docs index](docs/README.md) — the full map of the `docs/` tree.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)
</content>
