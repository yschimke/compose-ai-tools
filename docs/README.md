# Contributor docs

These are **contributor** docs — for working on *this repo* (plugin, CLI,
renderers, daemon, VS Code extension). They assume you're building from source
with `./gradlew` against the local tree.

> **Consumer docs live elsewhere.** If you just want to *use* the published
> plugin/CLI, see the [documentation site](https://yschimke.github.io/compose-ai-tools/)
> and the [`yschimke/skills`](https://github.com/yschimke/skills) bundles
> (`compose-preview`, `compose-preview-review`). Don't duplicate consumer
> guidance here.

Start with **[AGENTS.md](AGENTS.md)** — the architecture map and the
conventions every change must follow. This index is just the map of everything
else.

## Orientation

- [AGENTS.md](AGENTS.md) — class-by-class architecture, commands, constraints, conventions. **Read first.**
- [HOW_IT_WORKS.md](HOW_IT_WORKS.md) — end-to-end: how a `@Preview` becomes a PNG.
- [DEVELOPMENT.md](DEVELOPMENT.md) — building the plugin, CLI, and extension from source.
- [RENDER_FILENAMES.md](RENDER_FILENAMES.md) — render output layout and filename normalization.

## Rendering & compatibility

- [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — renderer/consumer AndroidX alignment (consult before bumping versions).
- [SDK_COMPATIBILITY.md](SDK_COMPATIBILITY.md) — JDK × Android SDK support matrix.
- [DATA_PRODUCTS.md](DATA_PRODUCTS.md) — the two senses of "data product" and the single-producer model.
- [DEVICE_FRAMES.md](DEVICE_FRAMES.md) · [LOTTIE_PREVIEWS.md](LOTTIE_PREVIEWS.md) · [notifications.md](notifications.md) — shipped feature guides.
- [APP_TOURS.md](APP_TOURS.md) — app-level previews: real activities (hero image), intents, scripted multi-step tours.

## Integration & distribution

- [CONFIG_ONLY_PLUGIN.md](CONFIG_ONLY_PLUGIN.md) — committing `composePreview { }` without pinning the runtime.
- [NON_GRADLE_INTEGRATION.md](NON_GRADLE_INTEGRATION.md) — driving the renderer from non-Gradle builds.
- [portable-bundles.md](portable-bundles.md) — the portable-bundle format across build systems.
- [public-preview-server.md](public-preview-server.md) — the `serve` public preview server.
- [isolated-projects-autoinject.md](isolated-projects-autoinject.md) — why Isolated Projects stays off.

## Process & policy

- [RELEASING.md](RELEASING.md) — release-please flow.
- [VERSIONING.md](VERSIONING.md) — versioning policy for public surfaces.
- [API_STABILITY.md](API_STABILITY.md) — what counts as a public contract.
- [AGENT_INVOCATION.md](AGENT_INVOCATION.md) — summoning an agent onto an issue/PR.
- [PR_REVIEW_WORKFLOW.md](PR_REVIEW_WORKFLOW.md) — preview-gated AI PR review.
- [TOKEN_USAGE.md](TOKEN_USAGE.md) — token-budget reference for agent recipes.
- [CLOUD-TESTING.md](CLOUD-TESTING.md) — testing the extension in a cloud sandbox.

## Daemon

The long-lived renderer. See **[daemon/README.md](daemon/README.md)** for its
own index; the load-bearing specs are
[daemon/DESIGN.md](daemon/DESIGN.md), [daemon/PROTOCOL.md](daemon/PROTOCOL.md)
(wire format), [daemon/DATA-PRODUCTS.md](daemon/DATA-PRODUCTS.md), and
[daemon/MCP.md](daemon/MCP.md). The in-process / continuous-compile save loops
(`composePreview.daemon.compileInProcess` and `composePreview.daemon.continuousCompile`)
are experimental features whose behaviour is documented in the code that implements
them — `:daemon:core`'s `bta/` package and the VS Code extension's daemon client.

## Specs behind shipped code (`design/`)

The `design/` tree used to hold many speculative proposals; what remains are the
specs the code actually depends on:

- [design/SPATIAL_SCENE_CONTRACT.md](design/SPATIAL_SCENE_CONTRACT.md) — the XR scene wire format (schema-generated Kotlin/TS/C++ mirrors).
- [design/DESIGN_CATALOGS.md](design/DESIGN_CATALOGS.md) — the code-led sticker-sheet catalog system.
- [design/UI_BUILDER.md](design/UI_BUILDER.md) — **proposal**: assembling screens from catalog components (scaffold-first slots, a persisted composition document, Figma round-trip). Product analysis + phased plan; no code yet.

Component-level contracts (the XR semantics tree, `FigmaLayeredSvg`, the font
preview wrapper, `@XrSubspacePreview`) now live as **KDoc on the owning class**,
not as separate docs — read them next to the code.

## Internal tooling

- [serve/SESSION-VIEWER-PROTOCOL.md](serve/SESSION-VIEWER-PROTOCOL.md) — the versioned wire contract
  (streamed-frame WebSocket protocol + `composeai://` session-link format + `_composeai._tcp` mDNS
  discovery) that the mobile/Wear session-viewer clients build against. The clients themselves now
  live in [yschimke/compose-preview-client](https://github.com/yschimke/compose-preview-client) —
  split out in [#2533](https://github.com/yschimke/compose-ai-tools/issues/2533).
