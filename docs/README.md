# Contributor docs

These are **contributor** docs — for working on *this repo* (plugin, CLI,
renderers, daemon, VS Code extension). They assume you're building from source
with `./gradlew` against the local tree.

> **Consumer docs live elsewhere.** If you just want to *use* the published
> plugin/CLI, see the [documentation site](https://yschimke.github.io/compose-ai-tools/)
> and the [`yschimke/skills`](https://github.com/yschimke/skills) bundles
> (`compose-preview`, `compose-preview-review`). Don't duplicate consumer
> guidance here.

Rules live in **[root AGENTS.md](../AGENTS.md)** — the short, canonical file every
agent loads on every turn. This tree is the detail behind it: start with
**[AGENT_GUIDE.md](AGENT_GUIDE.md)** for the architecture map and the rationale, and treat
this index as the map of everything else.

## Orientation

- [root AGENTS.md](../AGENTS.md) — the CI-enforced invariants and the normative PR workflow, stated once. Every agent loads it on every turn.
- [AGENT_GUIDE.md](AGENT_GUIDE.md) — class-by-class architecture, commands, constraints, and the rationale behind the rules. **Read first.**
- [HOW_IT_WORKS.md](HOW_IT_WORKS.md) — end-to-end: how a `@Preview` becomes a PNG.
- [DEVELOPMENT.md](DEVELOPMENT.md) — building the plugin, CLI, and extension from source.
- [RENDER_FILENAMES.md](RENDER_FILENAMES.md) — render output layout and filename normalization.

## Rendering & compatibility

- [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — renderer/consumer AndroidX alignment (consult before bumping versions).
- [SDK_COMPATIBILITY.md](SDK_COMPATIBILITY.md) — JDK × Android SDK support matrix.
- [RENDER_LANE_PARITY.md](RENDER_LANE_PARITY.md) — measured snapshot / live / Wasm / SVG parity for the `compose-m3` + `wear-m3` catalogs.
- [DATA_PRODUCTS.md](DATA_PRODUCTS.md) — the two senses of "data product" and the single-producer model.
- [DEVICE_FRAMES.md](DEVICE_FRAMES.md) · [LOTTIE_PREVIEWS.md](LOTTIE_PREVIEWS.md) · [notifications.md](notifications.md) — shipped feature guides.
- [APP_TOURS.md](APP_TOURS.md) — app-level previews: real activities (hero image), intents, scripted multi-step tours.

## Integration & distribution

- [VERSION_PIN.md](VERSION_PIN.md) — one version pin, honoured by the CLI, the extension and the CI actions.
- [CONFIG_ONLY_PLUGIN.md](CONFIG_ONLY_PLUGIN.md) — committing `composePreview { }` without pinning the runtime.
- [NON_GRADLE_INTEGRATION.md](NON_GRADLE_INTEGRATION.md) — driving the renderer from non-Gradle builds.
- [portable-bundles.md](portable-bundles.md) — the portable-bundle format across build systems.
- [public-preview-server.md](public-preview-server.md) — the `serve` public preview server.
- [HOSTED_SERVICE_PLAN.md](HOSTED_SERVICE_PLAN.md) — sketch: running `serve` as a hosted service (costs, scaling, precedents).
- [isolated-projects-autoinject.md](isolated-projects-autoinject.md) — why Isolated Projects stays off.

## Process & policy

- [RELEASING.md](RELEASING.md) — release-please flow.
- [VERSIONING.md](VERSIONING.md) — versioning policy for public surfaces.
- [API_STABILITY.md](API_STABILITY.md) — what counts as a public contract.
- [AGENT_INVOCATION.md](AGENT_INVOCATION.md) — summoning an agent onto an issue/PR.
- [AGENT_ENTRYPOINTS.md](AGENT_ENTRYPOINTS.md) — **measurement**: which instruction file each agent (Claude Code, Codex, Gemini, Copilot) actually resolves, why none of them follows a markdown link, what the old layout cost the three non-Claude agents, and the CI gate that keeps every CI-enforced invariant reachable from all four.
- [MERGE_QUEUE.md](MERGE_QUEUE.md) — `main` merges through a GitHub merge queue: which workflows moved to `merge_group`, which kept `push: [main]` and why, and the ruleset settings that are not in any file.
- [PR_REVIEW_WORKFLOW.md](PR_REVIEW_WORKFLOW.md) — preview-gated AI PR review.
- [TOKEN_USAGE.md](TOKEN_USAGE.md) — token-budget reference for agent recipes.
- Testing the extension in a cloud sandbox — moved with the extension to
  [`docs/CLOUD-TESTING.md`](https://github.com/yschimke/compose-preview-vscode/blob/main/docs/CLOUD-TESTING.md) in compose-preview-vscode.

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
- [design/USAGE_SNIPPET_CORPUS.md](design/USAGE_SNIPPET_CORPUS.md) — **measurement**: whether the Source panel's "plain Compose" actually compiles. Samples previews from real catalog checkouts, cleans them with each catalog's own rules, and compiles the result against a *consumer's* classpath (Compose + material3 only, deliberately not the catalog's). Records the current ratios, the failure taxonomy, and why one catalog's zero is structural rather than a missing rules file.
- [design/PSI_PARSE_SPIKE.md](design/PSI_PARSE_SPIKE.md) — **spike**: should the usage cleaner parse instead of scan? Measures parse-only Kotlin PSI (no analysis, no classpath), what it costs per file, which of the corpus's defects it settles, and that it reaches the CLI through the existing isolated `lib-bta/` loader. Includes what it does *not* buy, and two corrections to its own earlier numbers.
- [design/UI_BUILDER.md](design/UI_BUILDER.md) — **proposal**: assembling screens from catalog components (scaffold-first slots, a persisted composition document, Figma round-trip). Product analysis + phased plan; no code yet.
- [design/COMPONENT_RECORD.md](design/COMPONENT_RECORD.md) — **plan**: one derived *component record* replacing the three hand-written tables the UI builder keeps in sync by hand, overrides moved out of composable bodies and into the preview function's own parameter list, code generated by printing a call site rather than subtracting scaffolding, and a mechanical conformance ladder deciding which surface a component reaches. Root-cause analysis measured over this repo, m3-catalog, wear-m3-catalog/remote-m3 and a typical app; supersedes UI_BUILDER.md on architecture.
- [design/AGENT_ACCESS_GRANTS.md](design/AGENT_ACCESS_GRANTS.md) — **shipped**: how an agent with no credential gets temporary, scoped, revocable access to a `serve` host — an RFC 8628-shaped device grant where the agent prints a link and a verification code, a human approves in a browser, and the token is delivered to the poller rather than to whoever opens the link. Covers why the link is not the credential, what each scope maps to, and the two ceilings on what an approver may pass on.
- [design/PLAYGROUND.md](design/PLAYGROUND.md) — **proposal**: a hosted Kotlin/Compose editor over the `serve` preview server (compile-then-permalink handoff, CMP / Compose-Android / Remote-Compose modes, the preview-token capability, isolation requirements). Product analysis + phased plan; Phase 1 in progress.
- [design/CATALOG_ONBOARDING.md](design/CATALOG_ONBOARDING.md) — **plan**: onboarding a GitHub project into the preview server from its URL alone, with no change to the target repo (#4789). A separate builder repository checks the target out, auto-injects the plugin and publishes its catalog branch; the approval is a PR against that builder, with a trial render attached. Covers why the build job holds no write credential, why one builder must not mean one trusted producer, and the consent/attribution policy for rendering repos that never asked.
- [design/RC_CMP_WASM_PLAYER.md](design/RC_CMP_WASM_PLAYER.md) — **proposal**: an original non-JVM CMP player for Remote Compose documents, with a typed binary codec, Compose/Skiko rendering boundaries, operation clusters, and per-operation conformance gates.
- [design/RC_PLAYER_PROFILING.md](design/RC_PLAYER_PROFILING.md) — **measurement**: what `androidx.tracing` 2.x says about the CMP player's decode/link/layout/paint/input phases over four reference documents (static button, canvas, animated canvas, interactive button), why the tracing seam is an `expect`/`actual` facade, and how to re-run the profile.
- [design/RC_PLAYER_TYPEFACES.md](design/RC_PLAYER_TYPEFACES.md) — **audit**: how each of the five Remote Compose player lanes (`js`, `cmp-wasm`, `java`, `cmp-android`, `cmp-jvm`) resolves built-in, named, downloadable, and document-embedded typefaces, and where two chips in the same viewer disagree about one document.
- [design/COMPONENT_PARITY_WORKFLOW.md](design/COMPONENT_PARITY_WORKFLOW.md) — **Phase 1 shipped, rest proposal**: turning parity reporting into an iterative loop — a stable component/preview/reference locator, a published GitHub issue index (`parity/issues.json`), and issue-linked *scoped* acceptance of one known difference that still detects everything else. The locator, the index and its four display surfaces are live and carrying real issues; scoped acceptance, element selection and resolution automation are still a phased plan ([#3680](https://github.com/yschimke/compose-ai-tools/issues/3680)).
- [design/RC_TEXT_METRICS.md](design/RC_TEXT_METRICS.md) — **harness**: Remote Compose documents that measure their own text with `TextMeasure` and draw the answers as guide lines, so each player lane renders *its own* metrics and a text divergence gets a name instead of a pixel percentage. Companion to the typefaces audit — that one covers *which face*, this one covers *how it is laid out once chosen*.
- [design/CATALOG_CONTENT_CACHE.md](design/CATALOG_CONTENT_CACHE.md) — **proposal**: a durable, commit-addressed home for fetched catalog content, so a redeployed `serve` adopts the catalogs it already had, converges to the branch tip in the background, and re-fetches only what moved. Companion to the theme cache — that one persists derived pixels, this one persists published bytes. Phased plan; no code yet.

Component-level contracts (the XR semantics tree, `FigmaLayeredSvg`, the font
preview wrapper, `@XrSubspacePreview`) now live as **KDoc on the owning class**,
not as separate docs — read them next to the code.

## Internal tooling

- [serve/SESSION-VIEWER-PROTOCOL.md](serve/SESSION-VIEWER-PROTOCOL.md) — the versioned wire contract
  (streamed-frame WebSocket protocol + `composeai://` session-link format + `_composeai._tcp` mDNS
  discovery) that the mobile/Wear session-viewer clients build against. The clients themselves now
  live in [yschimke/compose-preview-client](https://github.com/yschimke/compose-preview-client) —
  split out in [#2533](https://github.com/yschimke/compose-ai-tools/issues/2533).
