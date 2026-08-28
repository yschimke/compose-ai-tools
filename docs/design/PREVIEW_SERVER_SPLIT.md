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

Four checks, each failing in both directions so it cannot rot into decoration.

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
The `serve→cli` direction used to be dominated by the bundle format (`BundleReader`,
`BundleSigning`, `BundleClasspathHydration`, `extractBundle*`, `locateBundleSidecarJars`) —
preparation item 5 — and is now down to **three** entries on `main`. Getting there took the same
lesson twice, and it is worth writing down because it is not the obvious one.

Moving files into a module changes this number by **zero**. The check keys a crossing on the
*package* an import names, not on the module that declares it, so `:bundle-format` was invisible to
it until the package was renamed too. Applying that a second time took the list from 9 to 3: six of
the nine survivors were never `:cli` internals at all — `PreviewInfo`, `PreviewManifest` and
`PreviewParams` belong to the published `:preview-data-api`, and `PreviewModule`,
`PreviewParameterFanout` and `PreviewResultBuilder` to `:gradle-preview-driver`. They only looked
like coupling because they shared the `…cli` package. Both modules now carry their own
(`ee.schimke.composeai.previewdata`, `ee.schimke.composeai.previewdriver`), which is a **breaking
change for external consumers** and was taken deliberately rather than annotated away.

That rename immediately exposed something the shared package had hidden. Serve used three types
from `:gradle-preview-driver`, whose `api` dependency is the **Gradle Tooling API** — so naming it
as a contract would have put the Tooling API on an extracted server's floor. None of the three
needed Gradle: `PreviewParameterFanout` has no imports at all, and `PreviewResultBuilder` /
`previewSha256` use only Okio and the DTOs. They moved down into `:preview-data-api` instead.
`:gradle-preview-driver` is not a contract and serve no longer names it.

The three that remained were genuinely declared in `:cli`, and each needed a decision rather than a
move. All three are now resolved, and **`cliInternalsUsedByServe` is empty in both source sets** —
the `serve→cli` direction of this register is closed:

| symbol | resolution |
| --- | --- |
| `BUNDLE_VERSION` | became serve's own `SERVE_VERSION`. A deployed server answering `/version` is being asked what the *server* is, not what the CLI that happened to launch it is. Same resource today, so the reported string is unchanged; when serve is its own module it generates its own. |
| `CoordinateResolver` | moved to a published `:bundle-coordinates`. It is not part of `:bundle-format` — reading the format is offline and synchronous, this does HTTP over ktor, and a format module should not drag a network client onto the render subprocess classpath. |
| `WebEmbed` | moved into the `serve` package. That converts it from a `serve→cli` crossing into a `cli→serve` one, which is the direction that does not block extraction: an extracted `:cli` depends on the server module, not the reverse. |

`serve` is now its own Gradle module, `:cli:serve` (`cli/serve`), and the `serve→cli` direction is
a **build** fact rather than a scanner's finding: `:cli` depends on it, so Gradle rejects the
reverse, and `checkServeModuleBoundary` walks the module's resolved runtime classpath and fails if
`:cli`, a renderer, or the plugin arrives transitively. The sources keep the
`ee.schimke.composeai.cli.serve` package — moving a module and renaming its package are independent
changes, and doing both at once would make a 300-file diff unreviewable.

What is left is `serveInternalsUsedByCli` — 102 entries on `main`, dominated by `ServeCommand.kt`
(~4.9k lines in `:cli`, 92 of the 102 on its own). Reducing that to a thin entry point is the rest
of preparation item 7.

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
| `daemon-protocol` | published — **split out of `daemon-core`**, see below |
| `daemon-core` (`devices`, `bta`) | published; carries a leak (below) |
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
| `bundle-format` | published — **was** `:cli`'s bundle format, the one entry in this table that was not a module at all; preparation item 5 |

The three payload schemas are the same kind of thing as the six (a `-core` module, a wire shape, no
renderer), so they are contracts rather than leaks. `data-pseudolocale-core` joined them for the
same reason: a pure table of RTL languages and scripts with no renderer behind it, and the
alternative — serve keeping its own copy of that table — is exactly the drift a contract exists to
prevent. The bundle format was the one genuine blocker in
this table — not a module at all, so an extracted server could not name it. It is
`:bundle-format` now, published and named in the probe (preparation item 5), which is why its row
reads *published* like every other. **Nothing in this table is a blocker any more** — the
remaining ones are two unpublished modules serve reaches by class name, recorded under
`reflectiveDependencies` in the seam allowlist, which no import scan and no probe can see.

### `daemon-core` was a contract 14× the size of the contract

`daemon-core` was published, so the table read *published* and the row looked settled. Published is
not the same as being the right size to depend on. Serve's imports of it, counted:

| package | imports |
| --- | --- |
| `daemon.protocol` | 46 |
| `daemon.bta` | 2 |
| `daemon.devices` | 2 |
| `daemon` (root) | 1 |

Forty-six of fifty-one are wire shapes, and to name one of them an extracted server took the whole
daemon with it: the JSON-RPC server, the APNG/GIF/ffmpeg encoders, the sandbox lifecycle,
incremental discovery, the recording test generator, the XR session registry, the history archive.
653 public declarations to reach the ones that describe a request.

`:daemon-protocol` splits on **shape versus behaviour**, not client versus server. Anything that
only describes what crosses the wire moved; anything that reads a file, opens a socket or computes
a result stayed. `HistoryDataDelta` is the case that names the rule — the delta shape moved, while
`HistoryDataDiff`, which reads two archived entries off disk to produce one, did not.

Measured on the ABI dumps: `daemon-core` 7,317 lines → 2,479, with `daemon-protocol` at 4,843. The
package did not move (`ee.schimke.composeai.daemon.protocol` is unchanged) and `:daemon:core`
exposes the new module as `api`, so no consumer needed an import change.

What is left of serve's coupling to `daemon-core` is now four imports, in two groups that want
different answers:

- `DeviceDimensions` and `frameDpOverriddenBy` — a device catalog and the dp-override arithmetic
  over it. Pure table plus arithmetic, no IO, and both ends have to agree on it: serve builds the
  device menu from the same catalog the backend resolves against. The same shape as
  `data-pseudolocale-core`, and a contract by the same reasoning.
- `BtaCompileSession` and `DiagnosticCollector` — the playground's in-process Kotlin compile.
  This is behaviour, not a shape, and it is genuine coupling rather than an accident of module
  layout: a preview server that offers a playground really does need a compiler. Whether an
  extracted server keeps the playground, or reaches a compile service, is a product question and
  not one the module graph can answer.

`DaemonLaunchDescriptor` was the third group and is now in `:daemon-protocol` — it is the
`daemon-launch.json` file shape, written by the gradle plugin and read by the daemon JVM, the CLI
doctor, the VS Code extension and serve. A format four programs agree on is the definition of a
protocol; it sat in `:daemon:core` only because that is where the daemon was written.

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

### 4. `./gradlew :cli:checkDaemonLaunchSchema` — the cross-language schema gate

The first three checks police *which* modules the server touches. This one polices a contract that
has no module at all: `<module>/build/compose-previews/daemon-launch.json`, written by the Gradle
plugin and read by the daemon JVM, `compose-preview doctor`, and the VS Code extension. Four
representations of one file, in two languages, in three separate Gradle builds — and nothing in the
build knew they described the same thing.

The schema version was the sharp edge. It was declared four times, as a literal `2` each time, and
two of those copies carried a comment asking a human to remember:

```
render-session/subprocess/.../SubprocessRenderSession.kt
    /** ... mirrors the gradle plugin's writer. */
    private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

cli/.../McpCommand.kt
    // Keep in sync — bump together.
    internal const val EXPECTED_DESCRIPTOR_SCHEMA_VERSION: Int = 2
```

Bumping the writer alone left both writing and expecting v2 with nothing failing, while
`daemonProcess.ts` — the only reader that checks the version — rejected every descriptor the plugin
produced. `:render-session-subprocess` is one of the published contract modules an extracted server
links against, so after the split that stops being a same-commit mistake and becomes cross-repo
version skew no compiler sees.

`scripts/check-daemon-launch-schema.py` enforces ten things:

- **version agreement** — every declared copy equals the writer's, *and* every copy is registered.
  An unregistered version constant anywhere in the tree fails, so a new mirror has to be declared,
  which is the moment someone can ask whether it should exist at all. Discovery works **by use**
  as well as by name: every `schemaVersion = …` at a descriptor construction site must be a
  registered constant named as `schemaVersion = …` (a positional argument names nothing, so
  neither scan can see it), never a bare literal. That arm exists because name matching alone was not
  enough — `ServeBundleDaemon` calls its copy `DAEMON_LAUNCH_SCHEMA_VERSION` rather than
  `…DESCRIPTOR_SCHEMA_VERSION`, and stamps real descriptors with it, so a fifth mirror sat outside
  a check whose entire claim was that every mirror is registered;
- **structural agreement** — every field a reader requires is one the writer emits, shared fields
  carry corresponding types across Kotlin and TypeScript, and a reader-only field is optional;
- **`BtaCompileConfig` field-for-field** across the two languages, which its KDoc claimed and
  nothing enforced;
- **unknown-key tolerance** — the JVM reader keeps `ignoreUnknownKeys = true`, the single line that
  makes the writer's `btaCompile` (which that reader does not declare) safe rather than fatal;
- **mirrored constants** — sysprop keys the descriptor carries that other modules re-declare
  privately, such as `SANDBOX_COUNT_PROP`, whose own KDoc says "both sides MUST agree". Not only
  Kotlin ones: the production image passes that key as a literal in `deploy/image/Dockerfile`'s
  `JAVA_TOOL_OPTIONS`, so renaming it across every Kotlin copy would still strand deployed hosts on
  a property nothing reads, silently falling back to a pool of one;
- **raw-key readers** — `compose-preview doctor` indexes the parsed JSON by string rather than
  deserialising a DTO, so a renamed field leaves it asking for a key that reads as `null` and
  reporting the daemon disabled instead of failing. Its keys are checked against the writer, and
  each one records the **type it assumes** — `enabled` going `Boolean` -> `String` would keep its
  name, pass every other rule, and crash `.jsonPrimitive.boolean` at runtime with no compiler in
  the way;
- **annotation refusal** — an annotation can change what kotlinx.serialization emits while the
  declaration every other rule reads stays identical: `@SerialName` moves the key, `@Transient`
  drops the field, `@EncodeDefault` changes whether a default is written. Refused as a *class*
  rather than one at a time — nothing is allowed on these properties unless it is on an
  (currently empty) allowlist, so the next annotation someone reaches for has to be considered
  rather than slipping through;
- **the writers' encoder configuration** — the two production writers each configure their own
  `Json`, and that is part of the wire format rather than a formatting preference: a
  `namingStrategy` would rename every key while the declaration, the digest, both readers and every
  version constant stayed identical. Two hand-maintained copies of one contract is the same
  duplication this check exists for, one level out, so they are compared against the record and
  thereby against each other;
- **a wire fingerprint** — a digest of the writer's field names, types and optionality *and of the
  DTOs it nests*, recorded per version and **immutable once written**, pinned
  against the version it describes. Version agreement across the copies is necessary and not
  sufficient: a PR could rename a field in the writer and every in-repo reader at once, leaving
  all constants at v2 while a released extension accepts the new descriptor as v2 and misreads
  it. Changing the shape fails until someone records the new digest, which is where the question
  "is this breaking?" actually gets asked. Nesting is load-bearing: a top-level-only digest left
  `btaCompile` as the opaque token `BtaCompileConfig?`, so a rename *inside* that class moved the
  wire contract while the digest held steady. Immutability is the other half: while the digest was
  editable in place, a breaking rename could pass by updating the writer, the readers *and* the
  record together, leaving released v2 consumers accepting a v2 descriptor they now misread.
  Changing the shape means adding a version, never refreshing the entry for an existing one.

Divergences that are correct by design live in `scripts/daemon-launch-schema-allowlist.json` with
the reason written down — the same debt-register discipline as the seam allowlist, not an exemption
list. The three that exist today: serve's sandbox-only `jailCommand` and `hardTtlSeconds` (which
the plugin never writes, so both must default), and the writer's `btaCompile` (which the daemon JVM
does consume, but through flattened system properties rather than the nested block).

Every rule was falsified — each made to fail on purpose, then restored — and the gate is
unit-tested by `scripts/test_check_daemon_launch_schema.py`.

Two reachability notes, both of which made earlier versions of this weaker than they looked. The
Gradle task declares **every** Kotlin/TypeScript source as its input, not just the representations:
the repo-wide rule reads files nobody listed, so a narrower input set let Gradle call the task
up-to-date on precisely the change that added a new mirror. And because CI runs `test` tasks rather
than `check`, the enforcement that actually runs on a PR is `test_check_daemon_launch_schema.py` in
the `Actions Script Tests` job — which is why `ci-paths.json` scopes that job to `**/*.kt`. A
repo-wide claim is only worth as much as the trigger that runs it.

The TypeScript side is scoped to `vscode-extension/src/daemon/**` rather than `**/*.ts`, and that
is a deliberate limitation rather than an oversight: `test_path_scope.py` pins the rule that a
VS Code-only change skips every Gradle CI group, and a blanket `**/*.ts` broke it. A new mirror in
some other TypeScript file would therefore be caught on `main` rather than on the PR. Widening the
trigger means relaxing that rule, which is a CI-cost decision worth making on its own terms rather
than smuggling in here.

The representations agreed when the gate landed. That is the point: it was written while the
answer was still "no drift", so the first time it fails will be the first real drift.

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
   `bundle.json` is an external, versioned format that used to be a `:cli` internal, and it was
   most of the `serve→cli` half of the seam register. — *done.* It is `:bundle-format`
   (`bundle/format`), published as `ee.schimke.composeai:bundle-format` and named in the contract
   probe: the manifest DTO, the well-known entry names, the sidecar injectors, the deterministic
   zip helpers, the detached signature scheme, classpath hydration, the Android resource/launch
   support, and the figma-raster bound. It depends only on `:common-io` and `:preview-data-api`,
   which the build enforces — nothing in it can reach `:cli`, `serve`, the daemon, or a data
   product. It carries `explicitApi()` and a committed ABI dump like every other contract. The
   `bundle` subcommands stayed in `:cli`; they are argument parsing, not format.

   The register moved with it: `cliInternalsUsedByServe` went from 21 entries to 9 on `main` and
   9 to 6 on `test`. `BUNDLE_VERSION` is the one bundle-ish name left, and it is genuinely still
   declared in `:cli`.

   **What actually moved that number, because it is not what the first attempt assumed.** The
   extraction landed in two PRs. The first moved the files into a module and kept their
   `ee.schimke.composeai.cli` package for source-compat, the way `:gradle-preview-driver` did for
   its step-B carve-out — and the register did not shrink by a single entry, because
   `scripts/check-serve-seam.py` keys a crossing on the *package* an import names, not on the
   module that declares it. A module boundary the seam check cannot see is not progress the seam
   check can report. The second PR renamed the package to `ee.schimke.composeai.bundle`, and that
   is what took the twelve symbols off the list.

   > **Correction.** That first PR said publishing this module would have to wait for a release,
   > on the reasoning that the probe compiles against artifacts at an already-released
   > `contractVersion` and so a module first published in the *next* release would have to sit in
   > `unpublishedContracts` until then. That was wrong, and checkable in one file:
   > `scripts/check-preview-server-contracts.sh` publishes every contract **from source** to Maven
   > Local at a fixed `0.0.0-contract-probe-SNAPSHOT`, precisely so the exchange does not depend on
   > where release-please left the repo version. There was never a release to wait for.
   > `unpublishedContracts` is still `{}`.

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
    [AGENTS.md](../../AGENTS.md): a surface that stops being auto-captured is a regression, not a
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
