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
covers it. `deploy/image/Dockerfile` prefetches that exact coordinate
into the image layer, and the integration matrix restores the same
directory from an Actions cache, both for this reason.

## Where the time goes

Rough breakdown on cold first run:

1. **Robolectric `InstrumentingClassLoader` instrumenting every class on
   the classpath at load time.** ~5–10 s for a 344-class daemon. Happens
   *every* boot, hot or cold. Bytecode rewriting via ASM.
2. **First-time Maven download of `android-all-instrumented-{ver}-{sdk}.jar`
   (~150 MB).** 0–60 s+ depending on network. Cached after first run.
3. **JVM startup + classpath resolve + first render.** ~1–2 s + ~50–500 ms.

(1) is the persistent cost. (2) piles on for cold-cache first runs.

**Multiply (1) by the pool.** `start()` boots the eager slots
*sequentially*, and `warmSpare` (on by default on the Gradle-plugin
launch path) means five of them. At ~11.6 s per sandbox on a warm
`android-all` cache — the figure
[SANDBOX-POOL.md](SANDBOX-POOL.md) measures — a warm-spare pool is
~58 s of boot before `initialize` can answer, on top of (2). That is
what makes the observed 141 s on a GitHub runner: a cold jar download
plus five sequential sandbox boots.

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

Menu of follow-ups, by leverage:

- ~~**Reconsider the eager warm-spare pool on the launch-descriptor
  path.**~~ **Done** — `composePreview.daemon.backgroundSandboxBoot`
  ([CONFIG.md](CONFIG.md)) exposes it to the Gradle-plugin launch path,
  default `false` so the eager contract is still what consumers get
  unless they ask otherwise. The integration daemon cell opts in via the
  `daemon_background_boot` matrix field, which drops its eager slots from
  5 to 1. `.github/ci/daemon-roundtrip.py` prints the measured
  `initialize` latency and the eager-slot count on every run, so the
  effect is readable off the leg's log rather than inferred.

  What remains open is the *default*: whether the eager
  all-sandboxes-ready contract is the right one for editor clients, or
  whether they'd also rather render sooner. That's a product question,
  not a perf one.
- **Machine-resident daemon** (highest priority). Daemon survives editor
  restarts; cold start moves from "every editor open" to "every reboot."
  Lifecycle change only; needs a different anchor than parent-PID.
- **AppCDS / Class Data Sharing.** Standard OpenJDK feature. May be
  defeated by Robolectric's load-time bytecode rewriting; needs a
  benchmark.
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
