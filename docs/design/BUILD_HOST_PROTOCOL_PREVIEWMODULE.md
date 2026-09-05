# Where `PreviewModule` lives when `ServeBuildHost` becomes a wire protocol

> **Status: decision requested.** One question, blocking the build-host protocol
> ([compose-preview-server#180](https://github.com/yschimke/compose-preview-server/issues/180)
> step 3, [#9](https://github.com/yschimke/compose-preview-server/issues/9)). Nothing is
> implemented; this records the options and a recommendation so the choice is made once,
> deliberately, rather than discovered halfway through writing the module.

## The plan this sits inside

`ServeBuildHost` is the seam where the preview server asks for Gradle work. Today it is a Kotlin
interface with exactly one real implementation — the CLI's `ServeCommand` — which is why `serve`
and `browse` cannot leave compose-ai-tools and why `StandaloneBuildHost` stubs all seven methods.
The agreed direction is to make that seam a **process boundary** rather than a library one: a small
request/response protocol, a `compose-preview build-host` process on this side, and a server that
spawns it when a local Gradle project is asked for. The Gradle Tooling API then stays in layer 1
permanently, which is what `docs/design/REPOSITORY_LAYERS.md` says it is.

The seam is small enough for that to be cheap: **seven methods, sixteen call sites**, all inside
`ServeRunner`.

## The question

Two of the seven methods carry `PreviewModule`:

```kotlin
public fun gradleProjects(): List<PreviewModule>
public fun discoverAndBuild(silenceStdout: Boolean): ServeDiscovery   // manifests: List<Pair<PreviewModule, PreviewManifest>>
```

`PreviewModule` is declared in **this** repository, in `:preview-data-api`, and it is small:

```kotlin
public data class PreviewModule(val gradlePath: String, val projectDir: File) : java.io.Serializable
```

The recommendation recorded on #180 was to put the protocol in `compose-preview-contracts` — layer
0, *shape never behaviour*, already depended on by both repositories, no new release train. That
recommendation was made without checking where `PreviewModule` lives. **It is not in contracts**,
and contracts today has *zero* `ee.schimke.composeai` coordinates on any of its POMs. So the
protocol cannot simply name the type, and the question has to be answered before the module exists.

## Options

### A. The contracts protocol module depends on `:preview-data-api`

Rejected, and not marginally. `:preview-data-api` is layer 1 and contracts is layer 0, so this is
an **upward** dependency — the exact shape `REPOSITORY_LAYERS.md` forbids and `checkLayerBoundary`
now enforces on this side. Contracts' own `AGENTS.md` guards it independently: *"Do not add a
dependency without asking what it does to the consumers' floor. Every `ee.schimke.composeai` module
on a POM here is a module a client must resolve."* Today that floor is empty. Filling it to reach
a two-field data class would be the most expensive possible way to get one.

### B. Redeclare the shape inside the contracts protocol module

Two fields, and `projectDir` has to change type regardless — see *The `File` problem* below — so
the "duplication" is smaller than it sounds, and the second declaration is a genuine wire DTO
rather than a copy.

There is direct precedent: `:daemon-protocol` moved 21 types in-house for this reason, and contracts'
`AGENTS.md` states the principle — **"a wire field's type belongs to the wire"** — recording that
the move took a client from 9,111 lines of published ABI across five coordinates down to one
coordinate and 5,695 lines.

Cost: two declarations of one concept, and a change loop that runs through three repositories.

### C. Move `PreviewModule` down into contracts, re-exported by `:preview-data-api`

The tempting one, because it leaves one declaration. It should still be rejected, for a reason
that is easy to miss: **`PreviewModule`'s shape is dictated by the Gradle Tooling API, not by a
wire.** It is `java.io.Serializable` and carries a `java.io.File` because instances are constructed
inside `DiscoverPreviewModulesAction` — a `BuildAction` that runs in the Gradle daemon and is
serialised back across the Tooling-API boundary.

Contracts would therefore be owning a type whose constraints come from a build tool it must never
depend on, and freezing `java.io.File` into a repository whose rule is *shape, never behaviour*. It
is also an ABI move affecting every existing consumer of `:preview-data-api`, paid across a release
boundary, to serve a module that does not exist yet.

### D. Publish the protocol from compose-ai-tools instead of contracts

The protocol module lives here, beside the type it already owns. `PreviewModule` is reused
directly, nothing is redeclared, and no contracts module is created.

The layer question this raises answers itself: the server is layer 2 and this repository is layer 1,
so a server that consumes the protocol is depending **downward**, which the rule allows. It is also
not a new edge — the server already resolves thirteen coordinates from here, `:preview-data-api`
among them. A fourteenth costs nothing that the thirteen have not already cost.

## The `File` problem, which applies to B and D alike

`projectDir` is a `java.io.File`. A process boundary cannot carry one, so the wire form is a path
string and both ends convert at the adapter. This is worth stating because it is the part that makes
"just reuse the type" incomplete under *any* option: even option D, which reuses `PreviewModule` in
the protocol module's API, needs a serialisable form for the actual message. What D avoids is a
second *declaration* of the concept, not the conversion.

It also raises a question the protocol has to answer explicitly: a path is only meaningful relative
to something. The build host and the server are separate processes and may not share a working
directory. Whichever option is taken, the protocol should carry absolute paths, or paths relative to
a root the handshake establishes — decided in the protocol, not left to whichever adapter is written
first.

## Recommendation: D

Put the build-host protocol in compose-ai-tools, and revisit contracts if a third consumer appears.

The reasoning is about the change loop rather than purity. This protocol is **new and will iterate** —
the first version will be wrong about progress streaming, cancellation, or error shape, and that is
normal for a seam being extracted from an in-process interface. In compose-ai-tools, a protocol
change and the `compose-preview build-host` implementation of it land in **one PR, compiled against
each other**, followed by a bump in the server. In contracts it is: change contracts, release
contracts, bump contracts here, release here, bump here in the server — with contracts' own
`AGENTS.md` warning that *"a change to a wire contract therefore reaches that repository only through
a release… that is the cost the split bought, and the reason an ABI break is expensive."*

Paying that cost is right for a settled wire contract. It is the wrong cost to pay while the shape
is still being learned, and the migration from D to B later is mechanical — moving a stable DTO down
a layer, which is exactly what contracts exists to receive.

Two honest marks against D, recorded rather than argued away:

- **It puts a wire protocol somewhere other than the wire-protocol repository**, which weakens
  "contracts is where shapes live" as a rule someone can rely on without checking. Mitigated by
  precedent rather than dismissed: contracts already publishes five modules that are *not* wire
  contracts, because an extracted preview server needs them — that repository's bar is already
  "what does the consumer need", not "is this a wire shape".
- **It does not shrink the back edge.** D adds a fourteenth coordinate to server → compose-ai-tools.
  That edge is sanctioned by the layer rule and is not the cycle — the cycle closes because the
  *forward* edge goes when `serve` becomes a launcher — but anyone hoping this step also thins the
  back edge should know that it does not.

## What this does not decide

The protocol's actual shape: the seven operations' messages, how build progress streams back, how
cancellation and `silenceStdout` map onto a process, and what happens when no build host is present
(today's `StandaloneBuildHost` behaviour, which stops being a stub and becomes an honest *no Gradle
here*). Those follow the placement decision; this document exists so they are not designed twice.
