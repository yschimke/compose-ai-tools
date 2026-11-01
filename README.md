# compose-ai-tools

**Let your coding agent see the Compose UI it just changed.** Start at
**<https://preview.coo.ee/start/>** (designing a new screen
instead? <https://ui.coo.ee/start>).

## Get started

Claude Code:

```text
/plugin marketplace add yschimke/compose-agent-plugins
/plugin install compose-preview@compose-agent-plugins
```

Codex:

```sh
codex plugin marketplace add yschimke/compose-agent-plugins
```

then enable `compose-preview` from `/plugins`.

Antigravity:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-agent-plugins/main/scripts/antigravity-install.py | python3 -
```

Any other agent (Gemini CLI, OpenCode, Cursor, Copilot, …), or no plugin system:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

Then **ask your agent to render one of your previews.**
[Other hosts and install options](https://github.com/yschimke/compose-agent-plugins/blob/main/docs/host-setup.md).

### Without an agent

- **VS Code** (or Cursor / Windsurf / VSCodium): install **Compose Preview** from the
  [Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview) or
  [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview). No project changes.
- **CLI**: the `install.sh` one-liner above installs `compose-preview`; then
  `compose-preview render` (every `@Preview` to PNG) or `compose-preview browse`. No build edits:
  it injects itself into projects that already apply `com.android.application` /
  `com.android.library` / `org.jetbrains.compose`.
- **Gradle plugin**: version-pinned, on
  [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin).
  See the [Install page](https://preview.coo.ee/start/install/) for that, CI
  recipes, and the requirements (Java 17+, Gradle 8.13+, AGP 8.13.0+, Kotlin 2.0.21+).

## When you need more

None of this is required to get value from the tool.

- **PR review and preview CI** — the
  [`apply`](.github/actions/apply) action posts before/after renders on pull requests; the
  optional [`compose-preview-review`](https://github.com/yschimke/skills/tree/main/skills/compose-preview-review)
  and [`compose-preview-ci`](https://github.com/yschimke/skills/tree/main/skills/compose-preview-ci)
  skills review UI PRs and set that CI up.
- **[UI Builder](https://ui.coo.ee/start)** — design a new screen with an agent.
- **[Whole-app simulator](docs/SIMULATOR.md)** — `compose-preview launch app.apk`, no emulator.
- **[Data products](https://preview.coo.ee/start/reference/)** — structured data
  alongside each PNG: accessibility findings, layout trees, theme tokens, recomposition heat maps,
  drawn text, resource captures, and more.
- **[Agents & MCP](https://preview.coo.ee/start/mcp/)** — the local and hosted MCP
  servers and a token-frugal agent loop over Compose UI. The same native MCP process can
  optionally connect agents to a concurrent preview-server UI-builder session; see the
  [MCP design reference](https://github.com/yschimke/compose-preview-daemon/blob/main/docs/daemon/MCP.md#remote-ui-builder-tools).
- **[Daemon](https://preview.coo.ee/start/daemon/)** — an optional long-lived
  renderer that keeps Robolectric / Compose-Desktop warm so re-renders are fast.

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

- [Documentation site](https://preview.coo.ee/start/) — install, reference, agents & MCP, daemon.
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
