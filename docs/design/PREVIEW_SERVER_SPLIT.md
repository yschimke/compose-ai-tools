# Preparing `compose-preview serve` for extraction

Issue [#3824](https://github.com/yschimke/compose-ai-tools/issues/3824) asks whether the preview
server should live in its own repository, measures the coupling, and answers: **not yet, and here is
what to do meanwhile.** This document is the "meanwhile" — what the preparation is, what has landed,
and what is left.

Nothing here moves the server. The gate is red and the depth condition — the load-bearing one — is
red by a factor of four. The server is still the *driver* of protocol and data-product work rather
than a consumer of a finished one, and splitting in that state converts roughly eight PRs a week
into two-repo, two-release round trips.

## Why prepare at all, if the answer is "not yet"

Because the measurement is only actionable if the seam is real. Every number in #3824 is a proxy for
one question — *what would break on the day of the move?* — and today nobody can answer it, because:

- `serve` is a **package**, not a module. There is no boundary, so coupling accrues invisibly. 151
  symbols cross it today; that number was not knowable before this work.
- The contracts the server needs are **assumed** to be publishable, and which module publishes a
  given package is assumed from its name. Both assumptions were wrong somewhere until they were
  checked — see the correction below.
- The contracts that are published carry things a server should never see — an MCP server, an XR
  renderer client — through transitive dependencies nobody looks at.

Prep work is what turns "the split would cost X" from an estimate into a fact.

## Why a separate build, not a subproject

The obvious shape for #3824's first checkbox is `:cli:serve`, a subproject of the root build. That
is the shape that teaches you nothing.

A subproject can take `project(":daemon:core")` dependencies. So its dependency floor stays whatever
the last PR happened to reach for, the boundary is a convention rather than a mechanism, and the day
of the split is the day everyone finds out what the floor actually was.

A **separate Gradle build** cannot. Gradle does not offer the *root* build's projects for
substitution into an included build, so everything in `preview-server/` resolves exactly the way it
will resolve after the split: as a published artifact, by coordinate, from a repository. The build is
also deliberately **not** included in the root `settings.gradle.kts` — wiring it in with
`includeBuild` would be the comfortable choice and would defeat the point, because the contracts
would resolve from the workspace and the missing ones would never be missed.

That is the whole design: make the in-repo build behave like the out-of-repo one, then fix what
breaks, on a normal PR cadence, for as long as it takes the gate to go green.

## What enforces it today

Three checks, each failing in both directions so it cannot rot into decoration.

### 1. `./gradlew :cli:checkServeSeam` — the symbol ratchet

`scripts/check-serve-seam.py` enumerates every symbol crossing between
`ee.schimke.composeai.cli.serve` and the rest of `:cli`, in both directions, for `main` and `test`,
and compares it against `scripts/serve-seam-allowlist.json`. Imports *and* fully qualified
references — Kotlin needs no import to reach across a package, and an import-only scan reports a
green seam while code walks straight through it (three real crossings were invisible until the
scanner was widened). It fails when

- a crossing appears that is not listed — new coupling, which is what the split is trying to stop
  accruing; **or**
- a listed crossing is gone — the allowlist must shrink as extraction proceeds, or it stops
  describing anything. Prune it with `python3 scripts/check-serve-seam.py --write`.

It also enforces two rules that are not ratchets. `serve` never imports a renderer, an MCP server,
or the gradle plugin — there is nothing to grandfather there. And every non-`cli`
`ee.schimke.composeai` package serve imports must map to a module the contract probe knows about
(`contractPackages` in the allowlist). That second rule is what ties serve's real imports to the
probe's hand-maintained coordinate list: without it, serve could start importing a new published
module and the probe would resolve the same coordinates and pass while the dependency floor had
grown underneath it.

Today's register: **21 + 102** crossings in `main` (serve→cli, cli→serve) and **9 + 19** in `test`.

**Known limitation: it reads source text, not the compiler's view.** Deciding what is code and what
is a comment or a literal means the checker carries a small Kotlin tokenizer, and review found three
separate defects in it — a `"Disallow: /*/p/"` literal opening a phantom block comment, `${…}`
interpolations blanked as if they were text, and `'\''` swallowing the rest of a file. Each was real,
each is fixed and tested, and the pattern is the point: a hand-rolled tokenizer is the weak part of
this check.

The robust version does not parse anything. `:cli`'s compiled classes already record every package
they reference, and this repo already reads bytecode that way (`:preview-discovery` uses ASM for
`@Composable` call targets). A bytecode-driven seam check would be immune to every defect above and
would additionally see reflective references the source scan cannot. It is not done here because the
tokenizer is now tested and the register is stable, and because the check becomes moot at
preparation item 7 — once serve is its own module, `checkServeModuleBoundary` reads a resolved
classpath and no source scanning is needed at all. If this check needs substantial work again before
then, rewrite it against bytecode rather than teaching the tokenizer another Kotlin rule.
The `serve→cli` direction is dominated by the bundle format (`BundleReader`, `BundleSigning`,
`BundleClasspathHydration`, `extractBundle*`, `locateBundleSidecarJars`, `BUNDLE_VERSION`) —
preparation item 5 below. The `cli→serve` direction is dominated by `ServeCommand.kt`, which #3824
wants reduced to a thin entry point.

### 2. `scripts/check-preview-server-contracts.sh` — the artifact probe

Publishes the contract modules to Maven Local at a fixed probe version, then builds
`preview-server/` against them. `preview-server/contract-probe/` compiles a file naming one type per
contract, drawn from serve's real import set, and runs `checkContractSurface`, which walks the
resolved runtime classpath — transitives included — and fails on any `ee.schimke.composeai` artifact
that is neither an allowed contract nor a **recorded, shrinking** leak.

Like the seam ratchet, it fails when a recorded leak *disappears*, so fixing one forces the register
and this document to be updated in the same PR.

### 3. `scripts/measure-serve-coupling.py` — the gate itself

The #3824 gate, committed and re-runnable, with #3856's third bucket (the VS Code extension) built
in:

```
python3 scripts/measure-serve-coupling.py               # trailing 300 human PRs, serve + extension
python3 scripts/measure-serve-coupling.py --prs 500 --json
```

The classifier rules are #3824's, verbatim, and are unit-tested (`scripts/test_measure_serve_coupling.py`)
against both the current and post-extraction layouts, so the series stays continuous across the moves
below. Conditions 1 (structural) and 4 (protocol) are reported as MANUAL — the first is
`checkServeSeam`, the second needs a human to judge motivation, and the script prints the candidate
commits rather than guessing.

The gate's known blind spot is preserved rather than patched: condition 2's "≤15% of
component-touching PRs" limb misreads a component in maintenance, where the denominator is tiny. The
script flags that case in its output instead of moving the threshold, for the reason #3856 gives —
editing a threshold so a different component passes it is how a gate stops meaning anything.

## The dependency floor, as measured

#3824 listed six allowed modules. Compiling against serve's real import set found the floor is
wider, and that two of the entries drag things a server should not carry.

| Contract | Status |
| --- | --- |
| `daemon-core` (protocol, `devices`, `DaemonLaunchDescriptor`) | published; carries a leak (below) |
| `preview-data-api` (`designpages`) | published |
| `render-session-api` | published |
| `render-session-subprocess` | published; carries a leak (below) |
| `common-io` | published |
| `data-layoutinspector-core` | published |
| `data-preview-overrides-core` | published |
| `data-remotecompose-core` | **not in #3824's six** — payload schema the viewer renders |
| `data-theme-core` | **not in #3824's six** — payload schema the viewer renders |
| `data-render-core` | **not in #3824's six** — payload schema the viewer renders |
| `data-pseudolocale-core` | **not in #3824's six** — the renderer's locale-direction rule, read to resolve a published capture gutter's leading/trailing edges onto left/right (#4542) |
| `daemon-core` again, via `daemon.bta` (`BtaCompileSession`, `DiagnosticCollector`) | published — see the correction below |
| `:cli`'s bundle format | **not a module at all** — preparation item 5 |

The three payload schemas are the same kind of thing as the six (a `-core` module, a wire shape, no
renderer), so they are contracts rather than leaks. `data-pseudolocale-core` joined them for the
same reason: a pure table of RTL languages and scripts with no renderer behind it, and the
alternative — serve keeping its own copy of that table — is exactly the drift a contract exists to
prevent. The bundle format is the one genuine blocker in
this table: it is not a module at all, so an extracted server cannot name it.

> **Correction.** An earlier revision of this document listed `:daemon:bta-host` as unpublished and
> therefore a split blocker, because serve imports `BtaCompileSession` and `DiagnosticCollector`
> from `ee.schimke.composeai.daemon.bta`. That was wrong, and wrong in an instructive way: the
> package is declared by **both** `:daemon:core` and `:daemon:bta-host`, and the two types serve
> actually uses are the `:daemon:core` ones — `:cli` has no dependency on `:daemon:bta-host` at all.
> A package name is not a module name. The mapping was accepted because the coverage check matched
> packages by *prefix* and nobody checked which module declares the type; `test_check_serve_seam.py`
> now resolves every imported type to its declaring module and fails if the mapping disagrees.
> There is no unpublished blocker *among the packages serve imports*. There are two among the ones
> it loads reflectively — see below, which is where the real answer turned out to be.

### Reflective dependencies — the checks' blind spot

Every check above reads imports or a resolved classpath, so none of them can see a module reached by
class name in a string literal. serve does this four times, and `scripts/serve-seam-allowlist.json`
records them under `reflectiveDependencies` with tests pinning each literal to its file and failing
on an unrecorded new one.

| Reflective reference | Module | Published |
| --- | --- | --- |
| `usagepsi.UsageSourceAnalyzer` | `:usage-source-psi` | **no** |
| `rcembedded.jvm.RcJvmRenderMainKt` | `:third-party-rc-embedded-player-jvm` | **no** |
| `rcembedded.jvm.RcJvmRenderWorkerMainKt` | `:third-party-rc-embedded-player-jvm` | **no** |
| `daemon.DaemonMain` | `:daemon:desktop` / `:daemon:android` | yes |

The two unpublished ones are the genuine article. `:usage-source-psi` degrades — without it the
playground's source cleaner falls back to its text passes, so an extracted server loses
parse-quality cleaning rather than breaking. `:third-party-rc-embedded-player-jvm` does not; #3824
already lists it among the sidecars the CLI tarball stages, and the split turns that staging into a
published-artifact dependency.

`daemon.DaemonMain` is a process-launch string rather than a compile dependency — serve never links
against a daemon implementation, and `checkCliDaemonLibraryBoundary` still forbids one on the
classpath — so it is staging, not a blocker.

### Recorded leaks — must shrink

| Leak | Why it is on the classpath | Fix |
| --- | --- | --- |
| `mcp` | `:render-session-subprocess` builds its transport on `:mcp`'s `DaemonClient` / `SubprocessDaemonClientFactory` | item 3 |

It is recorded in `preview-server/contract-probe/build.gradle.kts`. The render-session library
should not ship an MCP server.

`renderer-xr-client` came off this table with item 4: `JsonRpcServer` now takes an `XrSessions`
port that `:daemon:core` owns, and `:daemon:desktop` adapts `XrSessionManager` onto it, so the
renderer client is no longer on the protocol contract's compile ABI — or on the classpath of a
preview server that never renders XR.

## Preparation order

From #3824's follow-up investigation, with what has landed marked.

1. **Commit the reproducible three-way coupling measurement.** — *done*
   (`scripts/measure-serve-coupling.py`.)
2. **Consolidate the daemon-launch schema into one published contract.** — *partly done.*
   `DaemonLaunchDescriptor` has moved from `:mcp` to `:daemon:core`, which removes serve's only
   direct `:mcp` import and puts the reader on the right side of the boundary. A deprecated
   typealias keeps the old name compiling for source consumers; binary compatibility for an
   already-compiled `DaemonClientFactory` implementation is not preserved, and cannot be without
   keeping two live classes — the duplicate the move exists to end. The `daemon-launch.json` format
   itself is untouched. Two representations
   of the schema still exist — this one and the gradle plugin's published `daemon-launch-builder`
   writer — and they have drifted. Consolidating them is the rest of this item.
3. **Extract the subprocess daemon client from `:mcp`** into its own published module, so
   `:render-session-subprocess` stops dragging an MCP server onto every consumer's classpath.
4. **Remove the renderer-XR implementation dependency from `:daemon:core`** through an injected
   port, so the protocol contract stops shipping a renderer client. — *done.* `JsonRpcServer` takes
   an `XrSessions?` (with `XrFrame`, a structural re-declaration of the client's `StreamFrame`)
   instead of an `XrRenderServerFactory?`, and `:daemon:core` no longer depends on
   `:renderer-xr-client` at all — not as `api`, not as `implementation`. The adapter,
   `XrManagerSessions`, lives in `:daemon:desktop`, which is where the native renderer actually is;
   XR is host-native, so no other daemon wired it anyway. Each test now sits with its subject: the
   RPC surface against a fake port in `:daemon:core`, the multiplexer's own scene-merge and respawn
   behaviour in `:renderer-xr-client`'s `XrSessionManagerTest`, and the mapping between them in
   `XrManagerSessionsTest`.
5. **Extract the bundle schema / read / sign / hydrate / extract path into a published module.**
   `bundle.json` is an external, versioned format that is currently a `:cli` internal, and it is
   most of the `serve→cli` half of the seam register.
6. **Add ABI validation and explicit API enforcement to the contract modules**, so a contract can't
   change shape silently between the two repos.
7. **Extract the server implementation**, leaving a genuinely thin CLI adapter. `ServeCommand.kt`
   (~4.9k lines) currently combines CLI routing, Gradle discovery, rendering, bundle handling,
   sidecar resolution and server startup; it is the `cli→serve` half of the register.
8. **Enforce the allowlist on resolved Gradle project identities, transitives included.**
   `checkContractSurface` does this for the probe; the extracted server's own module needs the same.
9. **Build the server against artifacts from a build-local Maven repository.** — *done* for the
   probe (`scripts/check-preview-server-contracts.sh`); it extends to the server itself with item 7.
10. **Move the serve Playwright fixtures into an independently installable, independently captured
    harness.** — *done.* They now live in `preview-server/preview-harness/` with their own
    `package.json`, `playwright.config.mjs`, static server and `_themes.mjs`; nothing in the
    directory imports across `vscode-extension/`.

    The misfiling was larger than #3824's estimate of 28% of cross-boundary traffic. Counted from
    the extension's side: of 72 PRs touching `vscode-extension/` in the trailing 300, **60 touched
    only this harness** and 11 only the extension's own source. And `harness:snapshot` collected
    **205 tests, 167 of them serve's** — the extension's flagship visual-diff job was 81% somebody
    else's.

    The capture surface is preserved rather than merely relocated, which was the constraint from
    [CLAUDE.md](../../CLAUDE.md): a surface that stops being auto-captured is a regression, not a
    saving. Both harnesses still write `<name>.<theme>.png`, `vscode-preview-comment` merges the two
    `out/` directories before diffing, and capture names are unique across them — so the baselines
    on `vscode-preview/main` matched unchanged and nothing needed regenerating. Two coverage gaps
    were closed on the way: the diff bot now triggers on `preview-server/preview-harness/**`, and on
    the whole of `serve/assets/**` rather than just `format-compare.js` — the fixture pages `<link>`
    the real viewer CSS/JS, so a viewer change moves these captures and previously could land
    without a rebaseline.

    One constraint the split introduced: the two harnesses share a baseline set, so their
    `@playwright/test` versions must stay in step. A skew would move pixels for reasons no PR
    explains. Both READMEs say so.

## After all of that

The move itself becomes a small change: point `preview-server/settings.gradle.kts` at released
coordinates instead of the local probe repo, `git filter-repo` the server's history into the new
repo, and hand over publishing and CI ownership. The contracts, the boundary checks and the harness
are already where they need to be.

And if the gate stays red for two consecutive quarterly checks with the deep tier flat, #3824's own
answer applies: the server and the render engine are one product, the split is not worth paying for,
and every item above was still worth doing — a published bundle format, an MCP-free render-session
library, a protocol contract that doesn't ship a renderer, and a measurement anyone can re-run.
