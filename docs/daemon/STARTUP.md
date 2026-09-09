# Daemon startup latency

## The cold-start cliff

The Android daemon's first save after boot has a long cliff. Concretely
observed: the first 13 `host.submit()` calls each blocked on a 60-second
`awaitSandboxReady` latch in `RobolectricHost.publishChildLoader`;
submits 14+ succeeded because by the time they arrived, the sandbox had
finished bootstrapping ~60s after submit 1.

The 60-second timeout was sized for warm boots ("5–15s in practice"),
which is accurate when the instrumented `android-all` jar already exists
locally. On a fresh checkout that jar has to come down from Maven
Central first, plus instrumentation of every class on the daemon's
classpath, plus the actual sandbox boot. Cold end-to-end: 60 s+ is
normal.

**Where that jar lives:** Robolectric fetches it with its own Maven
resolver, at run time, into the Maven local repo —
`~/.m2/repository/org/robolectric/android-all-instrumented/{ver}/`. Not
`~/.cache/robolectric`, and not via Gradle: no Gradle dependency
declares it, so neither a Gradle cache nor the BuildFetch remote cache
covers it. compose-preview-server's [`deploy/image/Dockerfile`](https://github.com/yschimke/compose-preview-server/blob/main/deploy/image/Dockerfile)
prefetches that exact coordinate into the image layer, and the
integration matrix restores the same directory from an Actions cache,
both for this reason.

## Where the time goes

Measured, not estimated (compose-preview-server#626). The deployed preview
server's shape — the released 2.4.0 `lib-daemon-android` sidecar, Temurin 21,
`sandboxCount=3`, `backgroundSandboxBoot=true`, a warm `android-all` cache —
on an idle 4-core / 16 GB box, with `StartupTimings` marks, `-Xlog:class+load`
and a JFR recording across one boot:

```
[+   407ms] worker 0 launched (Robolectric init begins)      daemon JVM
[+  5515ms] sandbox-ready latch fired (slot 0)               → slot 0 boot   5.1 s
[+  6610ms] sandbox-ready latch fired                        worker 1 JVM, from ITS start
[+ 19912ms] sandbox 1 warm render done (7425ms)              → slot 1: 6.6 s boot + 7.4 s warm
[+ 32190ms] sandbox 2 warm render done (6003ms)              → slot 2: 6.3 s boot + 6.0 s warm
```

So a **sandbox "boot" as the pool reports it is two equal halves**: ~6 s of
Robolectric bootstrap and ~6-8 s of boot-time warm render, in series, and the
3-sandbox pool completes ~32 s after the daemon JVM started. On the deployed
box the same marks read 9-60 s (p50 22 s) with the CPU idle, so the box is
2-3× slower per boot than this one — consistent with its 81 % memory and
active swap paging the 450 MB of jars each boot re-reads, not with CPU
queueing.

Inside one single-sandbox boot (4.3 s JVM start → ready, 3.9 s after
`host.start()`), by the class-load timeline:

| window | what loads | cost |
|---|---|---|
| 0.0-0.75 s | JVM + daemon classes, kotlin stdlib, guava, JUnit, Robolectric's injector | ~0.5 s |
| 1.5-2.25 s | **BouncyCastle**: 709 classes from a *signed* jar (the JDK verifies the manifest on first load), then the JCE provider registers every algorithm. Robolectric installs it because `conscryptMode=OFF` | ~0.75 s |
| 2.25-5.25 s | the sandbox: 2,154 classes defined by Robolectric's `SandboxClassLoader` (android-all, shadows, resources, `ActivityThread`), plus **2,392 `java.lang.invoke` hidden classes** — one `LambdaForm`/species class per distinct invokedynamic call-site shape the instrumented framework links | ~3 s |

JFR's Java-level sampler caught only 52 samples in that window: the time is
inside the VM, parsing, defining and verifying classes, not in Java code.
GC pauses total ~0.1 s; file reads ~20 ms; there is no I/O wait on a warm
page cache.

The **warm render** is the same story, bigger. The worker JVM loads 5,600
classes to boot its sandbox and **12,500 more** to render the 64×64
`DaemonWarmupPreview`: `Activity`/`View`/Espresso setup, Compose runtime + UI
+ foundation, `sun.font` / `java2d` / `imageio` for the PNG, then the data
products (`compose/figma-svg` alone is ~0.9 s). Of the worker's 18,184
classes, **6,580 are `java.lang.invoke` hidden classes** and 6,261 are
sandbox-defined — the cost of Robolectric's invokedynamic dispatch, paid per
JVM, per call-site shape, and not cacheable by anything the JVM ships.

The old description of this section — "the classloader instruments every
class on the classpath, ~5-10 s" — no longer holds: `android-all-instrumented`
is pre-instrumented, so no ASM rewriting happens at boot (2 of 52 samples).
What is paid every boot is *defining* those classes, and linking their
call sites.

### What each JVM-level lever is worth

Same box, single-sandbox boot, JVM start → `sandbox-ready latch fired`,
best of 2-3 runs (run-to-run noise is ~±0.2 s):

| variant | JVM → ready | after `host.start()` |
|---|---|---|
| baseline | 4.3 s | 3.9 s |
| `-Xshare:off` (no CDS at all — sizes what the JDK's own archive buys) | 5.2 s | 4.7 s |
| static AppCDS archive of the daemon classpath (3,081 shared classes) | 3.7 s | 3.5 s |
| … + `-XX:-BytecodeVerificationRemote` | 3.4 s | 3.25 s |
| … + bcprov repacked unsigned (so CDS can archive it) + `-XX:TieredStopAtLevel=1` | 3.0 s | 2.8 s |
| `-XX:TieredStopAtLevel=1` alone, `-XX:+UseSerialGC`, `-Xmx1g`, `-Xmx512m` | noise | noise |
| `-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30` (keep LambdaForms interpreted) | noise | noise |
| … `=1000` | 4.9 s | 4.5 s |

Reading it: about a second of a four-second boot is class parsing and
verification of the builtin-loader classes, which an archive and a
verification opt-out remove. The other three seconds are the sandbox loader
defining classes and the JVM spinning `java.lang.invoke` classes, which no
JVM flag touches. The `COMPILE_THRESHOLD` result rules out "LambdaForms are
compiled to bytecode too eagerly" — those hidden classes are species and
invoker shapes, not compiled forms.

An archive is worth the same ~0.5 s again on every worker, and more on a box
whose page cache is under pressure, since a mapped archive replaces
re-reading and re-parsing jars. `ServeBundleDaemon` therefore hands every
catalog daemon `-XX:+AutoCreateSharedArchive` with a per-classpath archive
under the shared `composeai` cache dir (`composeai.serve.androidDaemonCds`),
plus `-XX:-BytecodeVerificationRemote`
(`composeai.serve.androidDaemonBytecodeVerification`). The archive has to be
per classpath: serve puts a catalog's own Compose/AndroidX overlay jars
*ahead* of the sidecar, so one image-wide archive would never validate.

### The pool, before and after

Same box, `sandboxCount=3`, time from the daemon JVM's start to the last
slot published (`sandbox 2 ready`), and the resident memory of the daemon
plus its two workers once the pool is up (`Rss` from `smaps_rollup`):

| daemon | pool complete | daemon + worker + worker RSS |
|---|---|---|
| released 2.4.0 | 21.7-23.4 s | 375 + 582 + 592 MB |
| + warm render overlapped with the next boot | 19.9 s | same |
| + archive, first boot of this classpath (records + writes it) | 27.4 s | +0-100 MB |
| + archive, every later boot | 14.5-15.2 s | metaspace 52→13 / 98→24 MB, RSS about the same |
| + serial collector, archive present | 14.9-15.2 s | 307 + 459 + 451 MB |

The archive moves ~40 MB (daemon) and ~75 MB (worker) of metaspace into a
mapped file; RSS does not fall by that much because the mapping is
relocated on load and its touched pages are private.
`-XX:ArchiveRelocationMode=0` would keep them shared, but on JDK 21 it
silently disabled the archive here (mapping at the requested address
failed), so it is not used. The serial collector is where the memory goes:
~20-25 % per JVM, no boot cost, and on a box running dozens of three-JVM
daemons that is the difference between sitting at its limit and not. Every
JVM in the container otherwise inherits `-XX:MaxRAMPercentage=70` of the
*container*, i.e. a 28 GB heap ceiling each on a 40 GB box, which is the
first thing to look at for the memory climb the same issue reports.

### What it means for the target

A single-sandbox boot floors at ~3 s on an idle box with every JVM lever
pulled, and the warm render adds ~6 s that no flag reaches. **A 2-3 s
"boot" is only reachable by not booting**: the sandbox does not depend on the
catalog — the catalog's classes ride the disposable child loader, and the
warm render touches none of them — so a worker booted and warmed against no
catalog at all can be adopted by whichever daemon needs one next and pay only
the catalog's first real render. A warmed idle worker is ~570 MB RSS (the
daemon JVM ~380 MB), so a few spares fit on the deployed box. That is the
"pre-booted spare workers" item under Future options; the changes shipped
with this profile are the ones that were cheap: overlapping each worker's
warm render with the next worker's boot (SANDBOX-POOL.md), and the two flags
above.

## What caching can and can't reach

Three of these costs look cacheable and only two are:

| Cost | Cacheable today | By what |
|------|-----------------|---------|
| `android-all` jar download | yes | Actions cache / image layer over `~/.m2/repository/org/robolectric/android-all-instrumented` |
| Building the plugin + CLI the daemon runs | yes | BuildFetch remote Gradle cache |
| Robolectric instrumenting the classpath | **no** | — nothing caches it yet |
| Sequential per-slot sandbox boot | **no** | — it's pool policy, not a cache miss |

**BuildFetch does not touch daemon boot, and can't.** It's a Gradle
*task-output* cache: it replays the outputs of tasks whose inputs hash
the same. Sandbox boot isn't a Gradle task — it's work a spawned JVM
does at run time (Maven fetch, then ASM rewriting at class-load), inside
a process Gradle has already handed off to. There is no task, so there
is no cache key. The integration matrix compounds this: those jobs build
*external* consumer repos, which never see our `settings.gradle.kts` and
so never reach the BuildFetch cache at all.

Where BuildFetch *does* pay off in that workflow is the `build-plugin`
job — the one job that builds this repo (`publishToMavenLocal` +
`:cli:installDist`) and was running cold. It's wired to
`.github/actions/buildfetch-cache` on the same read-only-on-PRs gating
as `ci.yml`.

BuildFetch becomes relevant to boot only *downstream* of the
instrumented-bytecode work in the menu below: if instrumentation output
were produced by a cacheable Gradle task keyed on (classpath, Robolectric
version, shadow set), then that task's output would ride the remote cache
to every CI runner and developer machine. Persisting the bytes is the
hard part; sharing them is free once it exists.

## The current fix

Two changes shipped:

1. **`RobolectricHost.start()` blocks until ready.** The 60s
   `awaitSandboxReady` was racing the cold-cache cliff per-submit.
   Moving the await to `start()` fixes the symptom:
   - `start()` blocks until the sandbox-ready latch fires (or its budget
     runs out — default 10 minutes, configurable via
     `composeai.daemon.sandboxBootTimeoutMs`).
   - `JsonRpcServer.run()` only enters its read loop after `start()`
     returns.
   - `daemonReady = sandboxReady` — `initialize` cannot return success
     while the sandbox is bootstrapping.
   - `publishChildLoader` is no longer a latch-wait point; it's just a
     cheap mirror of the holder's child classloader on every submit.

   This is a correctness fix. It doesn't make cold start faster.

   **Client contract:** because `initialize` can't answer until the
   eager slots are up — and `start()` boots them sequentially, applying
   the budget to *each* — a client's `initialize` timeout has to cover
   `slots × sandboxBootTimeoutMs`, not one slot's worth.
   `.github/ci/daemon-roundtrip.py` derives `--init-timeout-s` from the
   launch descriptor's own `sandboxCount` / `warmSpare` /
   `backgroundSandboxBoot` / `sandboxBootTimeoutMs` properties for
   exactly this reason — its old flat 120s ceiling turned a 141s cold
   boot on a GitHub runner into a red `wear-os-samples (ComposeStarter)`
   leg. The high ceiling costs nothing when the daemon is genuinely
   stuck: a slot that misses its budget exits the daemon, and the client
   sees EOF immediately.

2. **`StartupTimings` instrumentation.**
   [`StartupTimings.kt`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/StartupTimings.kt)
   records labelled instants on a JVM-start-relative timeline. Marks emit
   to stderr live and buffer for a final `summary()`:

   ```
   [+   0ms] JsonRpcServer.run() entered
   [+ 20ms] RobolectricHost.start() entered
   [+ 25ms] worker thread launched (Robolectric init begins)
   [+8120ms] sandbox-ready latch fired
   [+8121ms] host.start() returned (sandbox ready)
   [+8122ms] read loop entering
   [+8123ms] initialize received
   [+8125ms] initialize responded
   [+8260ms] first renderNow received
   [+8420ms] first renderFinished sent
   ```

   `summary()` emits automatically once after the first `renderFinished`
   lands. Suppress with `-Dcomposeai.daemon.startupQuiet=true`.

## Future options

The ranked medium-term plan, including what a Robolectric fork would change,
is [BOOT-ROADMAP.md](BOOT-ROADMAP.md). The list below predates it.

Menu of follow-ups, by leverage:

- ~~**Reconsider the eager warm-spare pool on the launch-descriptor
  path.**~~ **Done** — `composePreview.daemon.backgroundSandboxBoot`
  ([CONFIG.md](CONFIG.md)) exposes it to the Gradle-plugin launch path,
  and it **defaults to `true`** — optimise for latency. Nothing renders
  until `initialize` returns, so the eager contract charged every client
  for the whole pool before it could draw anything: capacity not needed
  until the second render. Eager slots on the critical path drop from 5
  to 1.

  Measured on the integration daemon leg: `initialize` answers in
  **~6.5 s**, against the ~141 s that started this whole thread (that
  figure also included a cold `android-all` fetch, now cached, so the two
  changes share the credit).

  Clients that would rather have full capacity up front set
  `backgroundSandboxBoot = false`. The integration daemon cell
  deliberately leaves the `daemon_background_boot` matrix field unset so
  the leg exercises the default consumers actually get;
  `.github/ci/daemon-roundtrip.py` prints the measured `initialize`
  latency and the eager-slot count on every run, so a silent regression
  to 5 slots is readable off the log rather than inferred.
- **Machine-resident daemon** (highest priority). Daemon survives editor
  restarts; cold start moves from "every editor open" to "every reboot."
  Lifecycle change only; needs a different anchor than parent-PID.
- ~~**AppCDS / Class Data Sharing.**~~ **Measured** (§ "What each JVM-level
  lever is worth"): ~0.5-0.6 s per JVM, because it can only hold the
  builtin-loader classes — Robolectric's sandbox loader defines the rest
  from streams and the JVM cannot archive those. Shipped for serve-spawned
  catalog daemons as an auto-created per-classpath archive.
- **Pre-booted spare workers.** The lever that actually reaches a 2-3 s
  boot: a sandbox worker booted and warm-rendered against *no* catalog, held
  idle, and handed to the next daemon that needs a slot with its child
  loader pointed at that catalog's classes. Needs a `configure(userClassDirs)`
  step on the worker protocol (the `UserClassLoaderHolder` URL list is fixed
  at construction today) and a way for a worker to re-dial a different parent
  daemon, or for the spare pool to live in the serve process and lend workers
  to daemons. ~570 MB RSS per warmed spare.
- **Cache instrumented bytecode on disk.** Robolectric's instrumentation
  is deterministic per (input class, Robolectric version, shadow set).
  Persist post-instrumentation bytes to a side cache. ~2 weeks of work;
  60–80% reduction on warm-cache boots.
- **Shared daemon supervisor across projects.** One JVM hosts per-project
  sandboxes. Combines well with machine-resident.
- **JVM checkpoint/restore (CRaC, Project Leyden).** Research project.
- **Drop Robolectric for Layoutlib.** Rewrite. Worth scoping as
  research.

## Cross-references

- [DESIGN.md](DESIGN.md) — daemon architecture overview.
- [PROTOCOL.md](PROTOCOL.md) — the `daemonReady` notification model.
- [CLASSLOADER.md](CLASSLOADER.md) — disposable user classloader.
- [ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md) — what
  `InstrumentingClassLoader` does and why cost (1) is unavoidable.
