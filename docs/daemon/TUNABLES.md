<!--
  GENERATED FILE — do not edit by hand.

  Source of truth: DaemonProperties in
  daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/config/DaemonProperties.kt
  Regenerate:      ./gradlew :daemon:core:test --tests '*DaemonPropertiesDocTest*' -Pcomposeai.docs.regenerate=true
-->

# Preview daemon — tunables

Every `composeai.daemon.*` system property the daemon and its launch descriptor understand,
generated from the typed registry so the list cannot drift from the code that reads it.

Pass one with `-D<name>=<value>` on the daemon JVM. The Gradle-facing knobs (`maxHeapMb`, `warmSpare`,
`maxRendersPerSandbox`, `backgroundSandboxBoot`) are normally set through the `composePreview.daemon { … }`
DSL rather than by hand — see [CONFIG.md](CONFIG.md).

"Default" is the value used when the property is unset **or** unparseable: a typo never silently
flips a knob.

## Lifecycle and timeouts

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.idleTimeoutMs` | Long | `5000` | How long the daemon waits after its last client disconnects before exiting. |
| `composeai.daemon.renderTimeoutMs` | Long | `300000` | Initial per-render `host.submit` timeout, before `initialize.options.maxRenderMs` lands. Non-positive values keep the default. |
| `composeai.daemon.classpathDirtyGraceMs` | Long | `2000` | Grace window after a classpath-dirty signal before the daemon acts on it. See PROTOCOL.md § 6. |
| `composeai.daemon.dataFetchRerenderBudgetMs` | Long | `30000` | Budget for the re-render a `data/fetch` may trigger. See DATA-PRODUCTS.md § Re-render semantics. |
| `composeai.daemon.discoveryWatchdogMs` | Long | `1500` | Window between `fileChanged({kind:source})` and the deferred discovery scan. |
| `composeai.daemon.interactive.idleLeaseMs` | Long | `60000` | Idle lease before a held interactive session auto-closes. |
| `composeai.daemon.sandboxBootTimeoutMs` | Long | `600000` | Deadline for a Robolectric sandbox bootstrap. The default covers a cold `android-all-instrumented` download; warm boots are 5–15s. |

## Classpath and discovery

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.userClassDirs` | path list | empty | `File.pathSeparator`-delimited user-class directories the child-first loader serves. |
| `composeai.daemon.userClassPackages` | path list | empty | Packages excluded from Robolectric instrumentation and served from the user classloader. |
| `composeai.daemon.cheapSignalFiles` | path list | empty | Files whose mtime/size form the cheap classpath-dirty signal. |
| `composeai.daemon.previewsJsonPath` | path | unset | Absolute path to the `previews.json` manifest. Unset boots with an empty preview index. |

## History

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.historyDir` | path | unset | Render-history archive directory. Unset disables history entirely. |
| `composeai.daemon.workspaceRoot` | path | unset | Repository root used for git provenance. Defaults to the daemon JVM's working directory. |
| `composeai.daemon.moduleId` | String | unset | Gradle project path stamped into every history entry's `module` field. |
| `composeai.daemon.gitRefHistory` | list | empty | Reporting refs wired as read-only history sources, e.g. `refs/heads/preview/main`. |
| `composeai.daemon.gitRefHistorySyncMode` | String | `READ_ONLY` | Sync mode for the reporting refs: `READ_ONLY`, `WRITE_LOCAL` or `WRITE_PUSH`. |
| `composeai.daemon.gitRefHistoryDebounceMs` | Long | `1000` | Debounce window coalescing a render burst into one reporting-branch commit. `0` disables. |
| `composeai.daemon.gitRefHistoryPublishPolicy` | String | `cleanOnBranch` | Which renders reach the reporting branch: `all`, or `cleanOnBranch` (the default). |
| `composeai.daemon.history.maxEntriesPerPreview` | Int | `50` | Prune knob — entries retained per preview. `0` or negative disables this knob. |
| `composeai.daemon.history.maxAgeDays` | Int | `14` | Prune knob — maximum entry age in days. `0` or negative disables this knob. |
| `composeai.daemon.history.maxTotalSizeBytes` | Long | `500000000` | Prune knob — total archive size ceiling in bytes. `0` or negative disables this knob. |
| `composeai.daemon.history.autoPruneIntervalMs` | Long | `3600000` | Prune knob — auto-prune scheduler period. `0` or negative disables the scheduler. |

## Sandbox pool

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.sandboxCount` | Int | `1` | In-JVM Robolectric sandbox slots. Set by the supervisor to `1 + replicasPerDaemon`. The Android daemon main derives a larger default (5) when `warmSpare` is on; this default applies to every other reader. |
| `composeai.daemon.warmSpare` | Boolean | `true` | Whether the pool keeps a warm spare sandbox so recycle is an atomic swap. |
| `composeai.daemon.backgroundSandboxBoot` | Boolean | `false` | Whether `initialize` may return once the first sandbox is ready, the rest booting behind it. The Gradle-plugin descriptor sets this `true`; the raw sysprop default is off. |
| `composeai.daemon.warmRenderOnBoot` | Boolean | `true` | Whether each background-booted slot performs a boot-time warm render. |
| `composeai.daemon.useConsumerApplication` | Boolean | `false` | Whether Robolectric instantiates the consumer manifest's `Application` instead of the pinned `android.app.Application` stub. |
| `composeai.daemon.maxRendersPerSandbox` | Int | `1000` | Renders a sandbox handles before it is recycled regardless of drift signals. |
| `composeai.daemon.maxHeapMb` | Int | `1024` | Post-GC heap ceiling for the daemon JVM, in MiB. Also becomes `-Xmx`. |
| `composeai.daemon.sandboxWorker.port` | Int | `0` | Loopback port a spawned sandbox worker connects back on. Set by `SandboxProcessPool`; `SandboxWorkerMain` fails fast when unset. |
| `composeai.daemon.sandboxWorker.slot` | Int | `0` | Pool slot index a spawned sandbox worker owns. Set by `SandboxProcessPool`. |

## Tracing and diagnostics

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.startupQuiet` | Boolean | `false` | Suppress startup-timing marks on stderr. Marks are still buffered for the summary. |
| `composeai.daemon.atrace` | Boolean | `false` | Mirror render trace sections into `android.os.Trace`. |
| `composeai.daemon.perfettoTrace` | Boolean | `false` | Emit a Perfetto trace for each render. Set from the Gradle plugin's daemon DSL. |
| `composeai.daemon.compositionTrace` | Boolean | `false` | Enable Compose composition tracing in the render extension. |
| `composeai.daemon.recordingsDir` | path | unset | Where interactive recordings are written. Unset falls back to a sibling of the render output directory. |

## Bundle IR replay

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.bundleManifestPath` | path | unset | Portable-bundle manifest backing IR replay. Both this and `irDir` must be set. |
| `composeai.daemon.irDir` | path | unset | Directory holding the bundle's serialised preview IR. |

## Data products

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.resDirs` | path list | empty | Android resource directories the resource / i18n data products read. |
| `composeai.daemon.defaultLocale` | String | unset | Locale treated as the source language by the i18n translations data product. |

## Launch descriptor (set by the Gradle plugin)

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.protocolVersion` | String | unset | Daemon protocol version stamped by the Gradle plugin's launch descriptor. |
| `composeai.daemon.modulePath` | String | unset | Gradle project path of the module the daemon serves. |
| `composeai.daemon.moduleProjectDir` | path | unset | Absolute project directory of the module the daemon serves. |

## In-process compile (BTA)

| Property | Type | Default | Effect |
| --- | --- | --- | --- |
| `composeai.daemon.bta.implClasspath` | path list | empty | Build Tools API implementation JARs loaded into BTA's isolated classloader. |
| `composeai.daemon.bta.compileClasspath` | path list | empty | Compile classpath for stage-2 compiles. |
| `composeai.daemon.bta.compilerPlugins` | path list | empty | Compiler-plugin JARs (Compose, serialization, …) passed to the in-process compile. |
| `composeai.daemon.bta.moduleName` | String | unset | Kotlin module name for stage-2 compiles. |
| `composeai.daemon.bta.outputDir` | path | unset | Class output directory for stage-2 compiles. |
| `composeai.daemon.bta.icWorkingDir` | path | unset | Incremental-compilation working directory for stage-2 compiles. |
| `composeai.daemon.bta.ineligibilityReason` | String | unset | Why the Gradle plugin ruled this module out of in-process compile; surfaced in diagnostics. |
