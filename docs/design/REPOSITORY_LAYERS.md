# Repository layers

**Status: normative.** This is the rule that decides which repository a module belongs to. It is
written once, here, and cited — never restated — from each repository's `AGENTS.md`.

It exists because the question had no answer. Two of the repositories had a placement rule and both
held: `compose-preview-contracts` is *shape, never behaviour*, and `compose-preview-server` is
*server behavior and browser/offline scoring implementation*. Nothing covered a module that is
behaviour but not a server, so `:render-host` — 48 main-source files, entirely offline, with zero
project dependencies inside the repository that stores it — ended up wherever it happened to be
standing when the server was extracted. That is the root cause of the one dependency cycle across
the five repositories
([compose-preview-server#180](https://github.com/yschimke/compose-preview-server/issues/180)).

## The layers

```
0  compose-preview-contracts        shape, never behaviour
1  compose-ai-tools                 offline behaviour: renders, daemons, bundles, history
2  compose-preview-server           HTTP, web surfaces, the UI builder
3  compose-preview-vscode           leaves: editors and platform hosts
   compose-preview-xr
```

**A module may depend on a strictly lower layer, and on nothing else.** Same-layer dependencies are
an internal matter for that repository. A dependency on a *higher* layer is the cycle, and there is
no exception for a single class or a single coordinate.

### 0 — `compose-preview-contracts`

Wire shapes and nothing that executes: DTOs, schemas, protocol definitions, generated mirrors. No
transport, no filesystem, no rendering. A type belongs here when two repositories must agree on its
bytes.

Cheap to publish and safe for everyone to depend on, which is what makes it the right home for a
seam that would otherwise become a library edge between layers 1 and 2.

### 1 — `compose-ai-tools`

**Behaviour that opens no socket.** Preview discovery, the Gradle plugin and the Tooling-API driver,
the render daemons and render sessions, the bundle format and coordinate hydration, the git-backed
preview history, the offline CLI commands, the data extensions.

The test is not "is it a library" — layer 2 publishes libraries too. It is: *does it need an HTTP
server, a browser, or the UI builder to do its job?* If not, it is layer 1, even when the only
consumer today is the server.

Under this rule `:render-host` is compose-ai-tools' module. It renders, it reads history, and
`checkRenderHostIsServerFree` already asserts that it is free of a web server — which is the layer-1
test, enforced from inside layer 2.

### 2 — `compose-preview-server`

**HTTP and what is reachable over it.** The Ktor layer and the runner, the catalog store, the web
surfaces and `serve-web`, the playground, the Wasm UI, the UI builder and its runtime, the visual
harness.

`checkServeModuleBoundary` is this layer's floor as a resolved-classpath positive allowlist, and it
stays that way: an allowlist is the only form that catches a coordinate arriving transitively.

### 3 — leaves

`compose-preview-vscode`, `compose-preview-xr`. Hosts and editors. Nothing depends on them.

## Consequences worth naming

**The Gradle Tooling API is layer 1's, permanently.** Keeping it off the server's floor was a
deliberate outcome of the split and this rule is why it stays off: driving a build is behaviour that
opens no socket. A server that needs a local Gradle build therefore asks layer 1 for one across a
process boundary, over a contract in layer 0 — it does not link a Gradle driver. See
[compose-preview-server#9](https://github.com/yschimke/compose-preview-server/issues/9) for the
options that were considered and rejected.

**A fourth repository is not the answer to a misplaced module.** `compose-preview-runtime` and
`compose-preview-daemon` have both been proposed; each buys another release train for the same
graph while leaving the placement question unanswered. Move the module to the layer the rule names.

**`rc-players` is placed by this rule too.** compose-ai-tools consumes `rc-player-*` and rc-players
consumes `data-fonts-google` and `data-layoutinspector-connector` back. Both are layer 1: at module
granularity neither direction is a cycle, and the repository split between them is a publishing
convenience rather than a layer.

## What enforces it

Both sides now, as resolved-classpath positive allowlists.

compose-preview-server has held its floor since the split: `checkServeModuleBoundary`,
`checkRenderHostIsServerFree` and `checkUiBuilderRuntimeBoundary`.

compose-ai-tools enforced nothing until `checkLayerBoundary` (`build-logic`, registered on every
project with a `runtimeClasspath` and wired onto `check`). It fails on any
`ee.schimke.composeai:compose-preview-*` coordinate that is not either published by this repository
or one of the three known layer-2 edges being removed. A fourth cannot be added by accident.

**Resolved identity, not build files** — and the gate proved why on its first run. It failed on
`compose-preview-ui-builder-runtime`, which is declared nowhere in this repository: no build-script
line, no catalog alias. The server publishes its three modules in lockstep and
`compose-preview-serve`'s POM names all of them, so depending on the server drags the UI-builder
runtime onto `:cli`'s runtime classpath. The forward edge is three coordinates, not the two the
analysis on
[compose-preview-server#180](https://github.com/yschimke/compose-preview-server/issues/180) counted.

**The allowlist is now empty, which is what closing the cycle looks like.** It held three
coordinates; `compose-preview-render-host` and the `compose-preview-ui-builder-runtime` it dragged
went when the render host moved here, and `compose-preview-serve` went when `serve` and `browse`
became launchers over the published server binary. An empty positive allowlist means any
`ee.schimke.composeai:compose-preview-*` coordinate reaching a runtime classpath fails, with no
exceptions to argue about.

**A launcher needs something to launch, and nothing installed it.** Closing the cycle moved a
problem rather than removing one: the documented one-line installer in
[`yschimke/skills`](https://github.com/yschimke/skills) fetches the CLI and the skill bundle and
knows nothing about the server, so for everyone who installed the documented way `serve` printed an
installation hint and exited
([#5183](https://github.com/yschimke/compose-ai-tools/issues/5183)). The fix is on this side, not
the installer's: `ServerDistributionProvision` fetches the pinned release's distribution on the
first `serve` that finds no server and caches it under `<cache>/composeai/preview-server/<version>/`,
the same first-use provisioning the CLI already does for the Skiko native and the XR compositor.
Fetching it in the installer instead would have put a 120 MB download in front of everyone who only
ever runs `render`, and would have made the install story span two repositories. Which server is
fetched, and how the two version lines relate:
[`docs/VERSIONING.md` § 8.1](../VERSIONING.md#81-the-preview-server-is-on-its-own-train).

One edge survives, deliberately, and this task cannot see it. `compose-preview-serve` is still a
`testImplementation` of `:cli`: two tests drive the CLI's own HTTP clients against a real
`ServeHttpServer` to catch the two repositories' independently-declared wire types drifting apart,
and five of those cases approve or deny a grant by reaching into the server's store, which is only
possible in-process. A stub would make them pass while testing nothing they exist for — the point is
checking the halves against *each other*, not each against its own idea of the other. The gate reads
`runtimeClasspath`, so it does not fail on that, and should not: the claim it enforces is about what
ships.

Two limits worth stating rather than discovering later. The task covers projects with a
`runtimeClasspath`, so Android modules — which resolve per-variant classpaths — are not checked;
none of them consumes a server artifact, and covering them means resolving every variant on every
`check`. And it has no unit test, because `build-logic` has no test lane that CI runs; what
exercises it is every module's `check`.
