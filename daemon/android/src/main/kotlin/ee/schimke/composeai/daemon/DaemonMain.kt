@file:JvmName("DaemonMain")
@file:Suppress("NewApi")

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import ee.schimke.composeai.daemon.history.GitProvenance
import ee.schimke.composeai.daemon.history.GitRefHistorySource
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryPruneConfig
import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import ee.schimke.composeai.renderer.AccessibilityAnnotatedPreviewExtension
import ee.schimke.composeai.renderer.AccessibilityOverlayPreviewExtension
import ee.schimke.composeai.renderer.AccessibilitySemanticsPreviewExtension
import ee.schimke.composeai.renderer.AtfChecksPreviewExtension
import java.io.File
import java.nio.file.Path

/**
 * Entry point for the preview daemon JVM — see docs/daemon/DESIGN.md § 4.
 *
 * The Gradle plugin's `composePreviewDaemonStart` task points its launch descriptor at
 * `ee.schimke.composeai.daemon.DaemonMain` (see
 * [`AndroidPreviewSupport.kt:974`](../../../../../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt#L974)),
 * which the JVM resolves via the file-level [JvmName] annotation above.
 *
 * Lifecycle:
 *
 * 1. Print a hello banner to stderr (free-form log per PROTOCOL.md § 1).
 * 2. Build a [RobolectricHost] (B1.3 — holds the Robolectric sandbox open across renders).
 *    Implements the renderer-agnostic [RenderHost] from `:daemon:core`. **D-harness.v2:** when
 *    `composeai.harness.previewsManifest=<path>` is set, wrap with [PreviewManifestRouter] so the
 *    harness's `previewId=<id>` payload resolves to a parseable [RenderSpec]. Mirrors
 *    `:daemon:desktop`'s wireup. Production launches don't pass the sysprop, so production
 *    behaviour is unchanged.
 * 3. Build a [JsonRpcServer] (B1.5 — JSON-RPC 2.0 over stdio with LSP-style Content-Length
 *    framing). Lives in `:daemon:core`; binds to any [RenderHost] implementation.
 * 4. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit` or stdin closes; it
 *    calls `System.exit` itself.
 *
 * `args` is currently unused; future flags (e.g. `--detect-leaks=heavy`, `--foreground`) will be
 * parsed here.
 */
fun main(args: Array<String>) {
  // D-harness.v2 — capture the real stdout *before* swapping. Robolectric (and Roborazzi) write
  // diagnostic messages directly to `System.out` during sandbox bootstrap and HardwareRenderer
  // setup (e.g. "This workaround is used when an ActionBar is present and the SDK version is 35
  // or higher."). The JSON-RPC channel is the captured `realOut`; everything else lands on
  // stderr (free-form log per [PROTOCOL.md § 1]). Mirrors `:daemon:desktop`'s
  // [DaemonMain][ee.schimke.composeai.daemon.DaemonMain] (B-desktop.1.5).
  val realOut = System.out
  System.setOut(System.err)

  System.err.println("compose-ai-tools daemon: hello (args=${args.toList()})")

  // D-harness.v2 — when the harness drives real-mode runs it sets
  // `composeai.harness.previewsManifest=<json>` so the daemon can resolve the protocol-level
  // previewId (forwarded by JsonRpcServer as `payload="previewId=<id>"`) into a parseable
  // RenderSpec via `PreviewManifestRouter`. Production launches don't set this sysprop, so the
  // plain RobolectricHost path is unchanged.
  // B2.0 — build the disposable user-class holder from `composeai.daemon.userClassDirs` (set by
  // the gradle plugin's daemon launch descriptor). The holder's child URLClassLoader is mirrored
  // into `DaemonHostBridge.childLoaderRef` so the sandbox-side `RenderEngine.render` resolves
  // preview classes against the recompiled bytecode after every `fileChanged({ kind: "source" })`
  // swap. When the sysprop is unset (legacy/in-process tests), the holder is null and the legacy
  // sandbox-classpath path stays — pre-B2.0 behaviour.
  val userClassUrls = UserClassLoaderHolder.urlsFromSysprop()
  val hasUserClasses = userClassUrls.isNotEmpty()
  if (hasUserClasses) {
    System.err.println(
      "compose-ai-tools daemon: UserClassLoaderHolder active " +
        "(urls=${userClassUrls.size}, dirs=${userClassUrls.map { it.path }})"
    )
  }

  // The plugin has exposed `composeai.daemon.warmSpare=true` by default since the daemon launch
  // descriptor was introduced, but the Android daemon previously ignored it and came up with a
  // single sandbox unless the experimental sandbox-count property was set manually. Held
  // interactive sessions need slot 1 pinned while slot 0 continues normal renders, and a typical
  // preview grid wants extra slots for parallel renders, so default the pool to five sandboxes
  // when warmSpare is on. Explicit `composeai.daemon.sandboxCount` still wins.
  val warmSpareEnabled = System.getProperty(WARM_SPARE_PROP)?.toBooleanStrictOrNull() ?: true
  val defaultSandboxCount = if (warmSpareEnabled) 5 else 1

  // SANDBOX-POOL.md (Layer 3) — read the supervisor-supplied sandbox-count knob. When unset, use
  // the warm-spare-derived default above so production Android daemons have the second sandbox
  // required for held interactive sessions.
  val sandboxCount =
    (System.getProperty(SANDBOX_COUNT_PROP)?.toIntOrNull() ?: defaultSandboxCount).coerceAtLeast(1)

  // Per-slot child loaders. The factory closes over the URL list and constructs one holder per
  // slot, parented to the slot's own sandbox classloader. The
  // host invokes the factory lazily on first dispatch to each slot, after the sandbox prologue
  // has registered its loader on `DaemonHostBridge.slot(i).sandboxClassLoaderRef`.
  val userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? =
    if (hasUserClasses) {
      { sandboxClassLoader ->
        UserClassLoaderHolder(urls = userClassUrls, parentSupplier = { sandboxClassLoader })
      }
    } else null

  // B2.2 phase 1 — load the in-memory preview index from `previews.json`. The gradle plugin's
  // `composePreviewDaemonStart` task emits the absolute path as a sysprop on the daemon JVM (see
  // `composeai.daemon.previewsJsonPath` in AndroidPreviewSupport.kt). Load this before host
  // construction so Android's held interactive session can resolve `interactive/start.previewId`
  // into the concrete class/function/display spec. Without this resolver the daemon still accepts
  // `interactive/start`, but clicks fall back to stateless re-renders and `remember` state such as
  // "Taps: 0" never mutates.
  val previewsJsonPath = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
  val previewIndex: PreviewIndex =
    if (!previewsJsonPath.isNullOrBlank()) {
      val loaded = PreviewIndex.loadFromFile(Path.of(previewsJsonPath))
      System.err.println(
        "compose-ai-tools daemon: PreviewIndex loaded " +
          "(path=${loaded.path}, previewCount=${loaded.size})"
      )
      loaded
    } else {
      PreviewIndex.empty()
    }

  // Issue #1204 — the Android `compose/recomposition` producer plugs into the host's interactive
  // session lifecycle. Build it here so the RobolectricHost constructor below can wire it as the
  // host's [InteractiveSessionListener]; the same instance is registered in the ExtensionRegistry
  // a few hundred lines down so `data/subscribe` / `attachmentsFor` reach the same bookkeeping.
  val recompositionRegistry = AndroidRecompositionDataProductRegistry()

  // `composeai.harness.previewsManifest` is set unconditionally by the gradle plugin for production
  // Android daemons (the "harness" prefix is historical) — see AndroidPreviewSupport.kt wire-up.
  // The file may not exist on the very first warm: VS Code runs `composePreviewDaemonStart` (which
  // writes only the launch descriptor) and spawns this JVM *before* `composePreviewDiscover` writes
  // `previews.json`. Treat a missing file as "no router yet" and fall through to the same
  // previewIndex-backed RobolectricHost the no-sysprop path uses; the next warm after discover
  // lands picks up the populated manifest and re-enters the router branch.
  val manifestPath = System.getProperty("composeai.harness.previewsManifest")
  val manifestFile = manifestPath?.takeIf { it.isNotBlank() }?.let(::File)
  if (manifestFile != null && !manifestFile.isFile) {
    System.err.println(
      "compose-ai-tools daemon: composeai.harness.previewsManifest set to '$manifestPath' but " +
        "file does not exist; falling back to PreviewIndex-backed RobolectricHost until " +
        "composePreviewDiscover writes the manifest"
    )
  }
  val host: RenderHost =
    if (manifestFile != null && manifestFile.isFile) {
      val manifest = PreviewManifestRouter.loadManifest(manifestFile)
      System.err.println(
        "compose-ai-tools daemon: PreviewManifestRouter active " +
          "(manifest=$manifestPath, previews=${manifest.previews.map { it.id }})"
      )
      // The Gradle plugin also sets the historical "harness" manifest sysprop for production
      // Android daemons so previewId renderNow calls still route through the manifest. In that
      // production shape we must preserve the warm-spare pool and per-slot user classloaders;
      // otherwise the router silently downgrades the daemon to v1 interactive mode and scroll/click
      // inputs only trigger stateless re-renders. Standalone harness launchers have no preview
      // index/user class dirs, so they default to the single-sandbox route — but an explicit
      // `composeai.daemon.sandboxCount=N` sysprop wins so harness scenarios that need interactive
      // sessions (issue #1204's `compose/recomposition` real-mode wire test) can opt into the
      // pool without paying the warm-spare-default cost for every other harness scenario.
      val productionManifestRoute = previewIndex.size > 0 || hasUserClasses
      val explicitSandboxCount = System.getProperty(SANDBOX_COUNT_PROP)?.toIntOrNull() != null
      val routerSandboxCount =
        when {
          productionManifestRoute -> sandboxCount
          explicitSandboxCount -> sandboxCount
          else -> 1
        }
      val singletonHolder: UserClassLoaderHolder? =
        if (routerSandboxCount == 1)
          userClassloaderHolderFactory?.let { factory ->
            UserClassLoaderHolder(
              urls = userClassUrls,
              parentSupplier = {
                DaemonHostBridge.currentSandboxClassLoader()
                  ?: error(
                    "DaemonHostBridge.sandboxClassLoaderRef is null — sandbox prologue didn't run. " +
                      "Did SandboxHoldingRunner.holdSandboxOpen execute setSandboxClassLoader before " +
                      "the host called publishChildLoader?"
                  )
              },
            )
          }
        else null
      if (routerSandboxCount > 1) {
        System.err.println(
          "compose-ai-tools daemon: sandbox pool active (sandboxCount=$routerSandboxCount)"
        )
      }
      PreviewManifestRouter(
        manifest = manifest,
        userClassloaderHolder = singletonHolder,
        sandboxCount = routerSandboxCount,
        userClassloaderHolderFactory =
          if (routerSandboxCount > 1) userClassloaderHolderFactory else null,
        interactiveSessionListener =
          RobolectricHost.InteractiveSessionListener { event ->
            recompositionRegistry.onSessionLifecycle(event)
          },
      )
    } else {
      if (sandboxCount > 1) {
        System.err.println(
          "compose-ai-tools daemon: sandbox pool active (sandboxCount=$sandboxCount)"
        )
      }
      RobolectricHost(
        userClassloaderHolderFactory = userClassloaderHolderFactory,
        sandboxCount = sandboxCount,
        previewSpecResolver =
          previewIndexBackedSpecResolver(previewIndex)?.takeIf { previewIndex.size > 0 },
        interactiveSessionListener =
          RobolectricHost.InteractiveSessionListener { event ->
            recompositionRegistry.onSessionLifecycle(event)
          },
      )
    }

  // B2.1 — wire Tier-1 classpath fingerprinting (DESIGN § 8). Mirrors the desktop daemon's
  // construction shape — cheap-signal set from `composeai.daemon.cheapSignalFiles`, authoritative
  // hash from this JVM's `java.class.path`. Sysprop unset → null fingerprint → pre-B2.1 no-op.
  val classpathFingerprint: ClasspathFingerprint? =
    ClasspathFingerprint.parseCheapSignalFilesSysprop()
      .takeIf { it.isNotEmpty() }
      ?.let { cheap ->
        val classpath =
          (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
        System.err.println(
          "compose-ai-tools daemon: ClasspathFingerprint active " +
            "(cheap=${cheap.size}, classpath=${classpath.size})"
        )
        ClasspathFingerprint(cheapSignalFiles = cheap, classpathEntries = classpath)
      }

  // B2.2 phase 2 — wire the incremental rescan path. Mirrors `:daemon:desktop`'s wireup; the
  // ClassGraph scan happens against this JVM's `java.class.path` and is scoped to the smallest
  // classpath element overlapping the saved `.kt` file (see [IncrementalDiscovery]). Skip wiring
  // when the index is empty — no baseline → scan-on-save has nothing to diff against.
  val incrementalDiscovery: IncrementalDiscovery? =
    if (previewIndex.size > 0) {
      val classpath =
        (System.getProperty("java.class.path") ?: "")
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map { Path.of(it) }
      System.err.println(
        "compose-ai-tools daemon: IncrementalDiscovery active " +
          "(classpath=${classpath.size}, previewCount=${previewIndex.size})"
      )
      IncrementalDiscovery(classpath = classpath)
    } else null

  // History feature gated to 1.1 (see [HistoryFeature]). When disabled — the 1.0 cut —
  // `historyManager` stays null and every history wireup (sysprop reads, git-ref sources, prune
  // budgets) compiles out. Mirrors the desktop daemon's gate; HISTORY.md describes the original
  // H1+H2 wiring this block preserves verbatim for the 1.1 re-enable.
  val historyManager: HistoryManager? =
    if (HistoryFeature.ENABLED) {
      val historyDirProp = System.getProperty(HISTORY_DIR_PROP)
      val workspaceRootProp = System.getProperty(WORKSPACE_ROOT_PROP)
      val gitProvenance =
        if (historyDirProp != null) {
          GitProvenance(workspaceRoot = workspaceRootProp?.let(Path::of))
        } else null
      val gitRefHistoryRefs = GitRefHistorySource.parseRefsSysprop()
      val pruneConfig = HistoryPruneConfig.fromSysprops()
      historyDirProp?.let { dir ->
        System.err.println(
          "compose-ai-tools daemon: HistoryManager active (dir=$dir, gitRefs=${gitRefHistoryRefs}, " +
            "pruneConfig=$pruneConfig)"
        )
        HistoryManager.forLocalFsAndGitRefs(
          historyDir = Path.of(dir),
          module = System.getProperty(MODULE_ID_PROP) ?: "",
          gitProvenance = gitProvenance,
          gitRefs = gitRefHistoryRefs,
          repoRoot = workspaceRootProp?.let(Path::of) ?: Path.of(dir).parent,
          pruneConfig = pruneConfig,
        )
      }
    } else null

  // ExtensionRegistry — every extension is registered here in the inactive state. Clients call
  // `extensions/enable` to opt in to specific contributions.
  val renderOutputDir = System.getProperty(RenderEngine.OUTPUT_DIR_PROP)
  val composeTraceEnabled = renderOutputDir != null && PerfettoTraceDataProducer.enabled()
  val dataRoot: File? =
    renderOutputDir?.let { File(it).parentFile?.resolve("data") ?: File(it) }
  val extensions =
    ExtensionRegistry(
      buildList {
        tryAdd("device/clip") {
          Extension(
            id = "device/clip",
            displayName = "Device clip",
            dataProductRegistry = DeviceClipDataProductRegistry(previewIndex = previewIndex),
            previewExtensionDescriptors = listOf(RenderPreviewExtension.deviceClipDescriptor),
          )
        }
        tryAdd("device/background") {
          Extension(
            id = "device/background",
            displayName = "Device background",
            dataProductRegistry = DeviceBackgroundDataProductRegistry(previewIndex = previewIndex),
            previewExtensionDescriptors =
              listOf(RenderPreviewExtension.deviceBackgroundDescriptor),
          )
        }
        tryAdd("render/trace") {
          Extension(
            id = "render/trace",
            displayName = "Render trace",
            dataProductRegistry = RenderTraceDataProductRegistry(),
            previewExtensionDescriptors = listOf(RenderPreviewExtension.renderTraceDescriptor),
          )
        }
        tryAdd("render/test-failure") {
          Extension(
            id = "render/test-failure",
            displayName = "Test failure",
            dataProductRegistry = TestFailureDataProductRegistry(),
          )
        }
        tryAdd("render/overlay-legend") {
          Extension(
            id = "render/overlay-legend",
            displayName = "Render overlay legend",
            previewExtensionDescriptors = listOf(RenderPreviewExtension.overlayLegendDescriptor),
          )
        }
        tryAdd("data/theme") {
          Extension(
            id = "data/theme",
            displayName = "Material 3 theme override",
            dataProductRegistry = ThemeDataProductRegistry(),
          )
        }
        tryAdd("data/wallpaper") {
          Extension(
            id = "data/wallpaper",
            displayName = "Wallpaper override",
            dataProductRegistry = WallpaperDataProductRegistry(),
          )
        }
        // Issue #1204 — real `compose/recomposition` producer. Wired through the host's
        // [RobolectricHost.InteractiveSessionListener] above so observer installs hit the
        // sandbox-side held-rule loop when a `data/subscribe(mode=delta)` lands while an
        // interactive session is held.
        tryAdd("data/recomposition") {
          Extension(
            id = "data/recomposition",
            displayName = "Recomposition counts",
            dataProductRegistry = recompositionRegistry,
          )
        }
        tryAdd("data/ambient") {
          Extension(
            id = "data/ambient",
            displayName = "Wear OS ambient override",
            dataProductRegistry = AmbientDataProductRegistry(),
          )
        }
        tryAdd("data/permissions") {
          // Runtime-permissions override + query tracker. Registry serves the
          // `compose/permissions` payload (effective grant map + permissions the screen has
          // queried). The actual planner lives in `RobolectricHost`'s
          // `previewOverrideExtensions` list — same wiring shape as keyboard / focus — so the
          // controller seed + shadow-tracker hookup are in place even when no client has
          // explicitly enabled this extension. `dataExtensionDescriptors` advertises the planner
          // so panel / MCP clients can gate their permission-override UI on the daemon actually
          // shipping it.
          Extension(
            id = "data/permissions",
            displayName = "Runtime permissions override",
            dataProductRegistry = PermissionsDataProductRegistry(),
            dataExtensionDescriptors =
              listOf(
                DataExtensionDescriptor(
                  id = PermissionsOverrideExtension.ID,
                  displayName = "Runtime permissions override",
                )
              ),
          )
        }
        // Remote Compose extension is gated on the consumer's classpath shipping
        // `androidx.compose.remote.*`. The connector classes reference alpha API types at
        // composition time (`HostAction`, `RcPlatformProfiles`); registering the extension on a
        // non-Remote-Compose consumer would surface a confusing `NoClassDefFoundError` the first
        // time a render fires the around-composable. Mirrors the Wear-AAR gate
        // (`isWearAmbientAvailable`) used for `:data-ambient-connector`.
        if (isRemoteComposeAvailable(javaClass.classLoader)) {
          tryAdd("data/remotecompose") {
            // Remote Compose data product. Registry serves the `compose/remotecompose` payload
            // (named-value map applied / written during the render, ring-buffered HostAction
            // emissions, active profile). The around-composable's `LocalRemoteComposeHost`
            // composition local is in place on every render so a screen's `RemotePreview { ... }`
            // block can read the daemon-seeded named values + profile and report fired actions
            // back. `dataExtensionDescriptors` advertises the planner so the panel / MCP can
            // discover the extension via `initialize.capabilities.dataExtensions`.
            Extension(
              id = "data/remotecompose",
              displayName = "Remote Compose state + host actions",
              dataProductRegistry = RemoteComposeDataProductRegistry(),
              dataExtensionDescriptors =
                listOf(
                  DataExtensionDescriptor(
                    id = RemoteComposeOverrideExtension.ID,
                    displayName = "Remote Compose state + host actions",
                  )
                ),
            )
          }
        }
        tryAdd("data/touch-overlay") {
          // Touch-event visualization overlay (`AroundComposableHook` from
          // `:data-touch-overlay-connector`) — paints a translucent ring at every active pointer
          // plus short-lived expanding pulses on down / up, same shape as Android's "Show
          // touches" developer-mode toggle. Activated when `renderNow.overrides.touchOverlay =
          // true`; mirrors the desktop registration so panel / MCP clients see the same id and
          // descriptor across both backends. The planner itself is registered directly in
          // `RobolectricHost`'s `previewOverrideExtensions` list (see the keyboard / focus
          // precedent — `ExtensionRegistry` carries the descriptor metadata, the engine carries
          // the runtime planner).
          Extension(
            id = "data/touch-overlay",
            displayName = "Touch event overlay",
            dataExtensionDescriptors =
              listOf(
                DataExtensionDescriptor(
                  id = TouchOverlayExtension.ID,
                  displayName = "Touch event overlay",
                )
              ),
          )
        }
        tryAdd("data/launcher-widget") {
          // Launcher-widget container-size override — same shape as the touch-overlay registration
          // above. The actual planner is wired into `RobolectricHost.previewOverrideExtensions`;
          // this entry carries the discoverable descriptor for `extensions/list` and the
          // `LauncherWidgetDataProductRegistry` that captures the applied size for
          // `data/fetch?kind=compose/launcher-widget`. The registry has no state besides the
          // captured per-preview payload and is safe to construct lazily here.
          Extension(
            id = "data/launcher-widget",
            displayName = "Launcher widget container size",
            dataProductRegistry = LauncherWidgetDataProductRegistry(),
            dataExtensionDescriptors =
              listOf(
                DataExtensionDescriptor(
                  id = LauncherWidgetExtension.ID,
                  displayName = "Launcher widget container size",
                )
              ),
          )
        }
        if (historyManager != null) {
          tryAdd("history/diff-regions") {
            Extension(
              id = "history/diff-regions",
              displayName = "History diff regions",
              dataProductRegistry =
                HistoryDiffRegionsDataProductRegistry(historyManager = historyManager),
            )
          }
        }
        if (dataRoot != null) {
          tryAdd("compose/semantics") {
            Extension(
              id = "compose/semantics",
              displayName = "Compose semantics snapshot",
              dataProductRegistry = ComposeSemanticsDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("compose/semantics-wireframe") {
            Extension(
              id = "compose/semantics-wireframe",
              displayName = "Compose semantics wireframe",
              dataProductRegistry = ComposeSemanticsWireframeDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("compose/spatial-semantics") {
            Extension(
              id = "compose/spatial-semantics",
              displayName = "Compose spatial semantics",
              dataProductRegistry = SpatialSemanticsDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("layout/inspector") {
            Extension(
              id = "layout/inspector",
              displayName = "Layout inspector",
              dataProductRegistry = LayoutInspectorDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("resources/used") {
            Extension(
              id = "resources/used",
              displayName = "Resources used",
              dataProductRegistry = ResourcesUsedDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("i18n/translations") {
            Extension(
              id = "i18n/translations",
              displayName = "i18n translations",
              dataProductRegistry = I18nTranslationsDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("fonts/used") {
            Extension(
              id = "fonts/used",
              displayName = "Fonts used",
              dataProductRegistry = FontsUsedDataProductRegistry(rootDir = dataRoot),
            )
          }
          tryAdd("data/navigation") {
            Extension(
              id = "data/navigation",
              displayName = "Navigation snapshot",
              dataProductRegistry = NavigationDataProductRegistry(rootDir = dataRoot),
            )
          }
          if (composeTraceEnabled) {
            tryAdd("compose/trace") {
              Extension(
                id = "compose/trace",
                displayName = "Compose Perfetto trace",
                dataProductRegistry = PerfettoTraceDataProductRegistry(rootDir = dataRoot),
                previewExtensionDescriptors =
                  listOf(RenderPreviewExtension.composeTraceDescriptor),
              )
            }
          }
          tryAdd("text/strings") {
            Extension(
              id = "text/strings",
              displayName = "Text strings",
              dataProductRegistry =
                TextStringsDataProductRegistry(rootDir = dataRoot, previewIndex = previewIndex),
            )
          }
          tryAdd("a11y") {
            Extension(
              id = "a11y",
              displayName = "Accessibility",
              dataProductRegistry = AccessibilityDataProductRegistry(rootDir = dataRoot),
              dataExtensionDescriptors = AccessibilityRecordingScriptEvents.descriptors,
              previewExtensionDescriptors =
                listOf(
                  AccessibilitySemanticsPreviewExtension.descriptor,
                  AtfChecksPreviewExtension.descriptor,
                  AccessibilityOverlayPreviewExtension.descriptor,
                  AccessibilityAnnotatedPreviewExtension.descriptor,
                ),
            )
          }
          // Issue #1528 — daemon-side scroll artefact production. The registry advertises
          // `render/scroll/long` and `render/scroll/gif` as `requiresRerender = true`, so a
          // missing scroll artefact returns `Outcome.RequiresRerender("scroll-long"|"scroll-gif")`
          // and the dispatcher queues a per-preview re-render in the right scenario instead of
          // the host falling back to a module-wide Gradle `composePreviewRenderAll`. Writes to
          // the same `<modulePreviewsDir>/data/render-scroll-{long,gif}/<id>.{png,gif}` paths
          // Gradle does, so the host's `gradleService.readPreviewImage` reads the same file
          // either way. Descriptors are also advertised here so MCP /
          // `previewExtensions/list` clients see the scroll extension surface.
          tryAdd("scroll") {
            Extension(
              id = "scroll",
              displayName = "Scrolling preview artifacts",
              dataProductRegistry = ScrollDataProductRegistry(rootDir = dataRoot),
              previewExtensionDescriptors =
                listOf(
                  ee.schimke.composeai.scroll.ScrollPreviewExtension.longScrollDescriptor,
                  ee.schimke.composeai.scroll.ScrollPreviewExtension.gifScrollDescriptor,
                ),
            )
          }
          // UIAutomator-shaped script events (`uia.click`, `uia.inputText`, etc.) plus the
          // `uia/hierarchy` data product (#874). Always wired on the Android backend — the
          // dispatch path lives in RobolectricHost and the hierarchy producer ride along on
          // the same render pass; neither depends on the a11y opt-in.
          tryAdd(UiAutomatorRecordingScriptEvents.EXTENSION_ID) {
            Extension(
              id = UiAutomatorRecordingScriptEvents.EXTENSION_ID,
              displayName = "UIAutomator script actions",
              dataProductRegistry = UiAutomatorDataProductRegistry(rootDir = dataRoot),
              dataExtensionDescriptors = UiAutomatorRecordingScriptEvents.descriptors,
            )
          }
          // Navigation script events (`navigation.deepLink`, `navigation.back`,
          // `navigation.predictiveBack*`). Always wired on the Android backend — the dispatch
          // path lives in RobolectricHost.performNavigationAction and exercises the held
          // activity's `OnBackPressedDispatcher` / `startActivity`.
          tryAdd(NavigationRecordingScriptEvents.EXTENSION_ID) {
            Extension(
              id = NavigationRecordingScriptEvents.EXTENSION_ID,
              displayName = "Navigation script controls",
              dataExtensionDescriptors = NavigationRecordingScriptEvents.descriptors,
            )
          }
          // Display filters — post-capture color-matrix variants (grayscale/bedtime, invert,
          // daltonizer simulations). Gated on `composeai.displayfilter.filters` being non-empty
          // so an `extensions/list` doesn't surface a phantom kind that has nothing on disk yet.
          // The same prop drives the host's writeArtifacts call site (when wired up); keeping
          // both reads in DisplayFilterConfig avoids drift between "registered" and "produced".
          if (DisplayFilterConfig.fromSystemProperties().isNotEmpty()) {
            tryAdd("displayfilter") {
              Extension(
                id = "displayfilter",
                displayName = "Display filter variants",
                dataProductRegistry = DisplayFilterDataProductRegistry(rootDir = dataRoot),
              )
            }
          }
        }
        // host-wired recording-script extensions + renderer-agnostic roadmap descriptors. The
        // host's contribution flips supported flags as new handlers land in its session registry.
        tryAdd("recording/script") {
          Extension(
            id = "recording/script",
            displayName = "Recording-script extensions",
            dataExtensionDescriptors =
              host.recordingScriptEventDescriptors() +
                RecordingScriptDataExtensions.roadmapDescriptors,
          )
        }
      }
    )

  // Stage-2 in-process compile service — same read path as :daemon:desktop's DaemonMain.
  // Reads the `composeai.daemon.bta.*` sysprops the gradle plugin populates whenever the
  // variant wiring resolved the BTA classpath. Returns null when the sysprops are absent
  // or the module is KSP/KAPT-tainted (Android's ineligibilityReason flows verbatim
  // through `BtaCompileService.Outcome.Fallback`). The editor's save loop only calls
  // `compileSources` when the VS Code workspace setting
  // `composePreview.daemon.compileInProcess` is on, so an active service still costs
  // nothing until that switch is flipped. See docs/daemon/COMPILE-IN-PROCESS.md.
  val btaCompileService = ee.schimke.composeai.daemon.bta.DefaultBtaCompileService.fromSysprops()
  if (btaCompileService != null) {
    System.err.println(
      "compose-ai-tools daemon-android: in-process compile available (Kotlin Build Tools API " +
        "loaded) — engaged only when the editor sets composePreview.daemon.compileInProcess=true; " +
        "otherwise compiles run via Gradle"
    )
  } else {
    System.err.println(
      "compose-ai-tools daemon-android: in-process compile unavailable (Build Tools API not " +
        "configured for this module) — compiles run via Gradle"
    )
  }

  val server =
    JsonRpcServer(
      input = System.`in`,
      output = realOut,
      host = host,
      daemonVersion = DaemonVersion.value,
      classpathFingerprint = classpathFingerprint,
      previewIndex = previewIndex,
      incrementalDiscovery = incrementalDiscovery,
      historyManager = historyManager,
      extensions = extensions,
      btaCompileService = btaCompileService,
    )
  server.run()
}

/**
 * Adds [build]'s result to the list, catching `LinkageError`
 * (`NoClassDefFoundError` / `ClassNotFoundException`-shaped failures) so one missing connector
 * module's class does not crash the entire daemon process.
 *
 * The classpath the daemon JVM launches with comes from
 * `samples/<module>/build/compose-previews/daemon-launch.json`, materialised by the gradle
 * plugin's `composePreviewDaemonStart` task. A stale descriptor produced before a connector
 * module was wired in — e.g. after pulling PR #1226's extraction of
 * `NavigationDataProductRegistry` into `:data-navigation-connector` without re-running the
 * bootstrap — leaves the registry class off the classpath but DaemonMain still references it
 * directly, exploding the process on the very first registration. With this helper the spawn
 * survives one missing connector: the affected extension is skipped with a stderr line naming
 * the kind, so the user sees which classpath entry is missing and which `extensions/list` chip
 * will disappear until the descriptor refreshes.
 */
private inline fun MutableList<Extension>.tryAdd(label: String, build: () -> Extension) {
  try {
    add(build())
  } catch (e: LinkageError) {
    System.err.println(
      "compose-ai-tools daemon: extension '$label' unavailable on this classpath — " +
        "${e.javaClass.simpleName}: ${e.message}"
    )
  }
}

/** HISTORY.md § "What this PR lands § H1" — null disables history. */
private const val HISTORY_DIR_PROP = "composeai.daemon.historyDir"

/** Optional override for git-provenance resolution; defaults to JVM CWD. */
private const val WORKSPACE_ROOT_PROP = "composeai.daemon.workspaceRoot"

/** Module project path stamped into every history entry's `module` field. */
private const val MODULE_ID_PROP = "composeai.daemon.moduleId"

/**
 * Adapts [PreviewIndex] into the resolver [RobolectricHost] needs for held interactive sessions.
 * Production Android live mode receives only a protocol-level preview id from `interactive/start`;
 * this resolver maps it back to the class/function and display properties from `previews.json`.
 */
private fun previewIndexBackedSpecResolver(previewIndex: PreviewIndex): ((String) -> RenderSpec?)? {
  return { previewId -> previewIndex.byId(previewId)?.let { renderSpecFromInfo(it) } }
}

/**
 * Builds the Android [RenderSpec] for a held interactive composition from discovery metadata.
 * Mirrors the desktop daemon's resolver, with Android-specific fields such as device, locale and
 * resource uiMode threaded through so a live Wear preview matches the one-shot render.
 */
internal fun renderSpecFromInfo(info: PreviewInfoDto): RenderSpec {
  val defaults =
    RenderSpec(previewId = info.id, className = info.className, functionName = info.methodName)
  val params = info.params ?: return defaults
  val density = params.density ?: defaults.density
  val widthPx = params.widthDp?.let { (it * density).toInt() } ?: defaults.widthPx
  val heightPx = params.heightDp?.let { (it * density).toInt() } ?: defaults.heightPx
  val uiMode = if (uiModeIsNight(params.uiMode)) RenderSpec.SpecUiMode.DARK else defaults.uiMode
  return RenderSpec(
    previewId = info.id,
    className = info.className,
    functionName = info.methodName,
    widthPx = widthPx,
    heightPx = heightPx,
    density = density,
    showBackground = params.showBackground ?: defaults.showBackground,
    backgroundColor = params.backgroundColor ?: defaults.backgroundColor,
    device = params.device ?: defaults.device,
    outputBaseName = defaults.outputBaseName,
    localeTag = params.locale ?: defaults.localeTag,
    fontScale = params.fontScale ?: defaults.fontScale,
    uiMode = uiMode,
    orientation = defaults.orientation,
    kind = params.kind ?: defaults.kind,
    wrapperClassName = params.wrapperClassName ?: defaults.wrapperClassName,
  )
}

/**
 * SANDBOX-POOL.md (Layer 3) — sandbox-pool size knob. Set by [DaemonSupervisor] from `1 +
 * replicasPerDaemon`. Default 1 preserves the pre-pool single-sandbox behaviour. Values < 1 are
 * coerced to 1.
 */
private const val SANDBOX_COUNT_PROP = "composeai.daemon.sandboxCount"

private const val WARM_SPARE_PROP = "composeai.daemon.warmSpare"
