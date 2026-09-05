package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises [ServeBundleDaemon.materialize] against a real **packed desktop bundle** — the same
 * shape `serve --catalogs --allow-render-trusted` fetches for a catalog's `liveBundle`. Needs a
 * bundle on disk (produced by `compose-preview bundle pack --module :samples:design-catalog-m3 -o
 * <path>`, e.g. via `NonGradleContractTest`'s pattern) plus the CLI's own `:cli:installDist`
 * sidecars (`lib-daemon-desktop` / `lib-renderer`) to resolve a real daemon classpath — neither is
 * produced by a normal `:cli:test` run, so this self-skips (same convention as
 * `NonGradleContractTest` in `:render-session-subprocess`) rather than failing when they're
 * missing.
 *
 * Point `-Dcomposeai.test.bundlePath=<file>` at a pre-packed bundle (defaults to
 * `/tmp/m3-bundle.png`, the path this feature's own verification pass packs to). The
 * `lib-daemon-desktop`/`lib-renderer` sidecars are auto-discovered from this checkout's
 * `cli/build/install/compose-preview/` when present (i.e. after `./gradlew :cli:installDist`);
 * override via `-Dcomposeai.cli.appHome=<install-root>` to point elsewhere.
 */
class ServeBundleDaemonTest {

  @Test
  fun `bundle dependencies precede daemon sidecars on the shared parent classpath`() {
    val root = Files.createTempDirectory("serve-bundle-classpaths").toFile()
    val classes = File(root, "classes").apply { mkdirs() }
    val resources = File(root, "resources").apply { mkdirs() }
    val embedded = File(root, "app-project.jar")
    val catalogMaterial = File(root, "material3-catalog.jar")
    val catalogCoroutines = File(root, "coroutines-catalog.jar")
    val catalogSerialization = File(root, "serialization-catalog.jar")
    val sidecarMaterial = File(root, "material3-sidecar.jar")
    val daemon = File(root, "daemon.jar")
    val androidResources = File(root, "android-resources")

    val result =
      ServeBundleDaemon.bundleDaemonClasspaths(
        classesDir = classes,
        extraClasspathDirs = listOf(resources, File(root, "missing-resources")),
        embeddedLibJars = listOf(embedded),
        parentOverlayJars = listOf(catalogMaterial, catalogCoroutines, catalogMaterial),
        childDependencyJars = listOf(catalogSerialization),
        daemonSidecarClasspath = listOf(sidecarMaterial.absolutePath, daemon.absolutePath),
        androidResourceClasspath = listOf(androidResources.absolutePath),
        hasIr = false,
      )

    assertEquals(
      listOf(
        catalogMaterial.absolutePath,
        catalogCoroutines.absolutePath,
        androidResources.absolutePath,
        sidecarMaterial.absolutePath,
        daemon.absolutePath,
      ),
      result.daemonClasspath,
      "catalog dependencies must win parent-classpath lookup ahead of host sidecars",
    )
    assertEquals(
      listOf(classes, resources, embedded, catalogSerialization).joinToString(File.pathSeparator) {
        it.absolutePath
      },
      result.userClassPath,
    )
    assertTrue(
      catalogMaterial.absolutePath !in result.userClassPath,
      "parent-loaded framework dependencies must not be duplicated in the user child loader",
    )
  }

  @Test
  fun `IR replay dependencies are visible to the daemon parent after its sidecars`() {
    val root = Files.createTempDirectory("serve-bundle-ir-classpaths").toFile()
    val classes = File(root, "classes").apply { mkdirs() }
    val embeddedPlayer = File(root, "embedded-player.jar")
    val childRuntime = File(root, "child-runtime.jar")
    val sharedOverlay = File(root, "shared-overlay.jar")
    val daemon = File(root, "daemon.jar")

    val result =
      ServeBundleDaemon.bundleDaemonClasspaths(
        classesDir = classes,
        extraClasspathDirs = emptyList(),
        embeddedLibJars = listOf(embeddedPlayer),
        parentOverlayJars = listOf(sharedOverlay),
        childDependencyJars = listOf(childRuntime),
        daemonSidecarClasspath = listOf(daemon.absolutePath),
        androidResourceClasspath = emptyList(),
        hasIr = true,
      )

    assertEquals(
      listOf(
        sharedOverlay.absolutePath,
        daemon.absolutePath,
        embeddedPlayer.absolutePath,
        childRuntime.absolutePath,
      ),
      result.daemonClasspath,
      "IR replay connectors are parent-loaded and must see every carried player dependency",
    )
    assertTrue(embeddedPlayer.absolutePath in result.userClassPath)
    assertTrue(childRuntime.absolutePath in result.userClassPath)
  }

  @Test
  fun `materialize wires carried IR into the daemon descriptor`() {
    val root = Files.createTempDirectory("serve-bundle-ir-descriptor").toFile()
    val daemonDir = File(root, "daemon").apply { mkdirs() }
    val rendererDir = File(root, "renderer").apply { mkdirs() }
    File(daemonDir, "daemon.jar").writeBytes(byteArrayOf())
    File(rendererDir, "renderer.jar").writeBytes(byteArrayOf())
    val previewId = "com.example.CatalogKt.RemotePreview"
    val document = byteArrayOf(0x52, 0x43, 0x01)
    val manifest =
      """
      {"schemaVersion":8,"backend":"desktop","previewIds":["$previewId"],
       "coverPreviewId":"$previewId","classpath":[],"modulePath":":remote",
       "producedBy":"test","intermediateRepresentations":[
         {"previewId":"$previewId","format":"remotecompose","path":"ir/$previewId.rc"}]}
      """
        .trimIndent()
        .toByteArray()
    val previews =
      """
      {"module":":remote","variant":"debug","previews":[
        {"id":"$previewId","functionName":"RemotePreview","className":"com.example.CatalogKt",
         "params":{"uiMode":32}}]}
      """
        .trimIndent()
        .toByteArray()
    val bundle = File(root, "remote-bundle.zip")
    java.util.zip.ZipOutputStream(bundle.outputStream()).use { zip ->
      for ((name, bytes) in
        listOf(
          "bundle.json" to manifest,
          "previews.json" to previews,
          "ir/$previewId.rc" to document,
        )) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }

    System.setProperty("composeai.cli.libDaemonDesktopDir", daemonDir.absolutePath)
    System.setProperty("composeai.cli.libRendererDir", rendererDir.absolutePath)
    try {
      val state =
        assertNotNull(
          ServeBundleDaemon.materialize(bundle, File(root, "session"), "remote"),
          "a fully IR-backed bundle should materialize without classes/app.jar",
        )
      val descriptor =
        descriptorJson.decodeFromString(
          DaemonLaunchDescriptor.serializer(),
          state.descriptor.readText(),
        )
      val irDir = assertNotNull(descriptor.systemProperties["composeai.daemon.irDir"])
      val bundleManifest =
        assertNotNull(descriptor.systemProperties["composeai.daemon.bundleManifestPath"])

      assertTrue(File(irDir, "$previewId.rc").readBytes().contentEquals(document))
      assertTrue(File(bundleManifest).readBytes().contentEquals(manifest))
      assertEquals(listOf(previewId), state.previews.map { it.id })
      assertEquals(0x20, state.previews.single().uiMode)
    } finally {
      System.clearProperty("composeai.cli.libDaemonDesktopDir")
      System.clearProperty("composeai.cli.libRendererDir")
    }
  }

  @Test
  fun `only consumer shared ABI dependencies overlay daemon internals`() {
    fun coordinate(group: String, artifact: String) =
      BundleReader.ClasspathEntry.Maven(group, artifact, "1", "jar")

    assertTrue(
      ServeBundleDaemon.shouldPrecedeDaemonSidecar(
        coordinate("androidx.compose.material3", "material3")
      )
    )
    assertTrue(
      ServeBundleDaemon.shouldPrecedeDaemonSidecar(
        coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
      )
    )
    assertTrue(
      ServeBundleDaemon.shouldPrecedeDaemonSidecar(
        coordinate("org.jetbrains.kotlinx", "kotlinx-io-bytestring-jvm")
      ),
      "bundle kotlinx-io must win because the child delegates its packages to the daemon parent",
    )
    assertTrue(
      ServeBundleDaemon.shouldPrecedeDaemonSidecar(
        coordinate("org.jetbrains.compose.components", "components-resources")
      ),
      "bundle resource APIs must share the parent-loaded LocalResourceReader with the daemon",
    )
    // Compose Multiplatform is `org.jetbrains.compose.*` by group but ships `androidx.compose.*`
    // packages, which `mustDelegateToParent` force-delegates. Keyed on the group alone, these fell
    // into the isolated child, were never consulted, and the sidecar's own Compose answered — a
    // consumer pinning a different version got NoSuchMethodError mid-render (meshcore-mobile on
    // material3 1.10.0-alpha05: `AppBarKt.TopAppBar-gNPyAyM`).
    for (group in
      listOf(
        "org.jetbrains.compose.material3",
        "org.jetbrains.compose.ui",
        "org.jetbrains.compose.foundation",
        "org.jetbrains.compose.animation",
        "org.jetbrains.compose.runtime",
        "org.jetbrains.compose.material",
      )) {
      assertTrue(
        ServeBundleDaemon.shouldPrecedeDaemonSidecar(coordinate(group, "whatever-desktop")),
        "$group ships androidx.compose.* packages, so the consumer ABI must win over the sidecar",
      )
    }
    // Skiko must travel WITH Compose. `skiko-awt` carries the `org.jetbrains.skia.*` bindings that
    // mustDelegateToParent force-delegates as well as `org.jetbrains.skiko.*`; promoting Compose
    // without it pairs the consumer's newer bindings with the sidecar's older native library —
    // the UnsatisfiedLinkError on skia.paragraph.TextStyleKt._nSetFontEdging that
    // DesktopRendererGraphAlignmentFunctionalTest documents (#1844).
    assertTrue(
      ServeBundleDaemon.shouldPrecedeDaemonSidecar(coordinate("org.jetbrains.skiko", "skiko-awt")),
      "Skiko bindings and native must stay version-coherent with the promoted Compose graph",
    )
    assertTrue(
      !ServeBundleDaemon.shouldPrecedeDaemonSidecar(
        coordinate("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm")
      ),
      "daemon protocol serializers must remain version-aligned with the sidecar runtime",
    )
    assertTrue(
      !ServeBundleDaemon.shouldPrecedeDaemonSidecar(coordinate("com.squareup.okhttp3", "okhttp"))
    )
  }

  @Test
  fun `playground shared runtimes overlay daemon sidecar by artifact path`() {
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/cache/org.jetbrains.compose.components/components-resources-desktop/library.jar")
      )
    )
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/cache/org.jetbrains.kotlinx/kotlinx-io-bytestring-jvm/0.9.1/library.jar")
      )
    )
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/m2/org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.9.1/library.jar")
      )
    )
    // The whole Compose Multiplatform graph, in both cache layouts — not just components-resources.
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/cache/org.jetbrains.compose.material3/material3-desktop/1.10.0-alpha05/lib.jar")
      ),
      "material3-desktop ships androidx.compose.material3, which the child delegates to the parent",
    )
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/m2/org/jetbrains/compose/ui/ui-desktop/1.11.1/ui-desktop-1.11.1.jar")
      )
    )
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/cache/org.jetbrains.skiko/skiko-awt/0.9.4.2/skiko-awt-0.9.4.2.jar")
      ),
      "Skiko carries the parent-delegated org.jetbrains.skia bindings, so it moves with Compose",
    )
    assertTrue(
      ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/m2/org/jetbrains/skiko/skiko-awt-runtime-linux-x64/0.9.4.2/native.jar")
      ),
      "the native runtime artifact must not be split from its bindings",
    )
    assertTrue(
      !ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/work/catalog-kotlinx-io-demo/cache/com.example/unrelated/library.jar")
      ),
      "a system or work-root name must not promote unrelated jars to the daemon parent",
    )
    assertTrue(
      !ServeBundleDaemon.jarPrecedesDaemonSidecar(
        File("/work/org.jetbrains.composure/thing/library.jar")
      ),
      "a group merely prefixed by org.jetbrains.compose must not be promoted",
    )
  }

  @Test
  fun `materialize produces a valid descriptor plus previews from a packed desktop bundle`() {
    val state = materializeOrSkip("descriptor-shape") ?: return

    val descriptorFile = state.descriptor
    assertTrue(descriptorFile.isFile, "daemon-launch.json should exist at ${descriptorFile.path}")
    val parsed =
      descriptorJson.decodeFromString(
        DaemonLaunchDescriptor.serializer(),
        descriptorFile.readText(),
      )

    assertEquals("ee.schimke.composeai.daemon.DaemonMain", parsed.mainClass)
    assertEquals("desktop", parsed.variant)
    assertEquals(":catalog", parsed.modulePath)
    assertTrue(parsed.enabled)
    assertTrue(parsed.classpath.isNotEmpty(), "daemon classpath should not be empty")
    assertTrue(
      parsed.classpath.all { File(it).isFile },
      "every daemon classpath entry should exist on disk: ${parsed.classpath}",
    )
    assertEquals(listOf("--enable-native-access=ALL-UNNAMED"), parsed.jvmArgs)

    val userClassDirs = parsed.systemProperties["composeai.daemon.userClassDirs"]
    assertTrue(!userClassDirs.isNullOrBlank(), "userClassDirs sysprop should be set")
    assertTrue(
      userClassDirs.split(File.pathSeparator).all { File(it).exists() },
      "every userClassDirs entry should exist on disk: $userClassDirs",
    )
    val previewsJsonPath = parsed.systemProperties["composeai.daemon.previewsJsonPath"]
    assertTrue(!previewsJsonPath.isNullOrBlank())
    assertTrue(File(previewsJsonPath).isFile)
    assertEquals(previewsJsonPath, parsed.manifestPath)
    assertEquals(state.workspaceRoot.absolutePath, parsed.workingDirectory)

    // The render-output dir must be set so DaemonMain.dataRoot is non-null and the file-based data
    // products register — notably compose/figma-svg, without which an override-bearing .svg render
    // fails "-32020 kind not advertised". It must sit under the working dir so its sibling `data/`
    // (where both DaemonMain's registry and RenderEngine's producer resolve) is inside this
    // session's temp tree.
    val outputDir = parsed.systemProperties["composeai.render.outputDir"]
    assertTrue(
      !outputDir.isNullOrBlank(),
      "composeai.render.outputDir must be set so figma-svg registers",
    )
    assertEquals(
      File(state.workspaceRoot, "renders").absolutePath,
      outputDir,
      "output dir lives under the session dir, so its sibling data/ dir does too",
    )

    assertTrue(state.previews.isNotEmpty(), "materialize should discover at least one preview")
    assertEquals("compose-m3", state.label)

    // The author-declared knob sidecars (`previews/<id>.overrides.json`) must be folded into the
    // ServePreview set so the daemon-backed session (and, via ServeCatalogLiveHost, the baked
    // browse
    // surface) can advertise the editable knobs. The M3 catalog's FilledButton declares a `label`
    // string knob; assert it round-trips from the packed bundle.
    val filled = state.previews.firstOrNull { it.id.endsWith("FilledButton_Light") }
    if (filled != null) {
      assertTrue(
        filled.overrides.any { it.key == "label" },
        "FilledButton should carry its declared `label` knob, got ${filled.overrides}",
      )
    }
  }

  @Test
  fun `materialized bundle renders one preview through a real daemon`() {
    val state = materializeOrSkip("live-render") ?: return
    val targetId = state.previews.first().id

    val session =
      try {
        SubprocessRenderSessions.open(
          RenderSessionConfig(
            descriptorPath = state.descriptor,
            workspaceRoot = state.workspaceRoot,
            workspaceName = state.workspaceName,
            logSink = { line -> System.err.println("[daemon] $line") },
          )
        )
      } catch (e: Exception) {
        System.err.println(
          "[ServeBundleDaemonTest] skipping live render — daemon failed to open (${e.message}). " +
            "Needs a display (run under xvfb-run + LIBGL_ALWAYS_SOFTWARE=1) and the CLI's " +
            "installDist sidecars."
        )
        return
      }

    session.use {
      val finished = AtomicReference<String?>(null)
      val latch = CountDownLatch(1)
      session
        .onNotification { method, params ->
          if (method == "renderFinished" && params != null) {
            val id = params["id"]?.jsonPrimitive?.contentOrNull
            if (id == targetId) {
              finished.set(params["pngPath"]?.jsonPrimitive?.contentOrNull)
              latch.countDown()
            }
          }
        }
        .use {
          val ack = session.renderNow(previewIds = listOf(targetId), tier = RenderTier.FULL)
          assertTrue(
            ack.rejected.none { it.id == targetId },
            "renderNow should queue $targetId, got rejected=${ack.rejected}",
          )
          assertTrue(
            latch.await(90, TimeUnit.SECONDS),
            "daemon should emit renderFinished for $targetId within 90s",
          )
        }

      val pngPath = finished.get()
      assertTrue(!pngPath.isNullOrBlank(), "renderFinished should carry a pngPath")
      val png = File(pngPath)
      assertTrue(png.isFile, "rendered PNG must exist on disk: $pngPath")
      assertTrue(png.length() > 0L)
    }
  }

  @Test
  fun `materialized m3 button surfaces its Material typography in preview inspection`() {
    val state = materializeOrSkip("typography-inspection") ?: return
    val target =
      assertNotNull(
        state.previews.firstOrNull { it.id.endsWith("CatalogButtonsKt.FilledButton_Light") },
        "packed m3 catalog should contain the typical FilledButton_Light preview",
      )

    val host =
      ServeRenderHost.open(
        descriptorPath = state.descriptor,
        workspaceRoot = state.workspaceRoot,
        workspaceName = state.workspaceName,
        previews = state.previews,
        label = state.label,
        declaredThemes = state.declaredThemes,
        onLog = { line -> System.err.println("[daemon] $line") },
      )

    host.use {
      val outcome = host.renderAnnotations(target.id, PreviewOverrides())
      assertTrue(
        outcome is AnnotationsOutcome.Ok,
        "typography inspection for ${target.id} should succeed, got $outcome",
      )
      val payload = Json.parseToJsonElement(outcome.json.decodeToString()).jsonObject
      val annotations =
        Json.decodeFromJsonElement(
          ListSerializer(DesignAnnotation.serializer()),
          payload.getValue("annotations"),
        )
      val label =
        assertNotNull(
          annotations.firstOrNull { it.kind == AnnotationKind.TYPOGRAPHY && it.role == "Filled" },
          "the Filled button label should be available in the Typography inspection layer",
        )

      assertTrue(
        label.detail["token"]?.split(',')?.contains("labelLarge") == true,
        "the stock Button label should resolve to the Material labelLarge token: $label",
      )
      assertTrue(!label.detail["fontSize"].isNullOrBlank(), "fontSize should be resolved: $label")
      assertTrue(
        !label.detail["fontFamily"].isNullOrBlank(),
        "fontFamily should be resolved: $label",
      )
      assertTrue(
        !label.detail["fontWeight"].isNullOrBlank(),
        "fontWeight should be resolved: $label",
      )
    }
  }

  /**
   * Production compatibility proof for a published Android catalog whose Compose/AndroidX/Kotlin
   * dependencies differ from the daemon sidecar's. This caught Jetcaster's
   * `MotionScheme.expressive()` / mangled `TopAppBar` failures: materialization used to leave the
   * catalog dependency graph in the child loader even though those packages delegate to the parent,
   * so the older sidecar APIs won.
   *
   * Self-skips unless `-Dcomposeai.test.androidCompatibilityBundlePath=<bundle.png>` is supplied.
   * Optionally select a known compatibility-sensitive preview with
   * `-Dcomposeai.test.androidCompatibilityPreviewContains=<substring>`; otherwise the first preview
   * is rendered.
   */
  @Test
  fun `android bundle renders against its carried dependency versions`() {
    val bundlePath = System.getProperty(ANDROID_COMPATIBILITY_BUNDLE_PATH_PROPERTY)
    if (bundlePath.isNullOrBlank()) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping android dependency compatibility — set " +
          "-D$ANDROID_COMPATIBILITY_BUNDLE_PATH_PROPERTY=<published android bundle .png>."
      )
      return
    }
    val bundleFile = File(bundlePath)
    assertTrue(bundleFile.isFile, "no compatibility bundle at $bundlePath")
    ensureAppHomeConfigured()

    val state =
      assertNotNull(
        ServeBundleDaemon.materialize(
          bundleFile,
          Files.createTempDirectory("serve-bundle-daemon-android-compat").toFile(),
          "android-compatibility",
        ),
        "published Android compatibility bundle should materialize",
      )
    val parsed =
      descriptorJson.decodeFromString(
        DaemonLaunchDescriptor.serializer(),
        state.descriptor.readText(),
      )
    val firstSidecar =
      parsed.classpath.indexOfFirst {
        it.contains("staged-daemon-android-libs") || it.contains("lib-daemon-android")
      }
    assertTrue(firstSidecar > 0, "descriptor should contain bundle dependencies before the sidecar")
    assertTrue(
      parsed.classpath.take(firstSidecar).any { it.contains("bundle-deps") },
      "at least one bundle-resolved dependency should precede the Android daemon sidecar",
    )

    val selector = System.getProperty(ANDROID_COMPATIBILITY_PREVIEW_CONTAINS_PROPERTY)
    val target =
      selector?.let { needle -> state.previews.firstOrNull { needle in it.id } }
        ?: state.previews.firstOrNull()
    assertNotNull(target, "compatibility bundle should contain a selectable preview")

    ServeRenderHost.open(
        descriptorPath = state.descriptor,
        workspaceRoot = state.workspaceRoot,
        workspaceName = state.workspaceName,
        previews = state.previews,
        label = state.label,
        declaredThemes = state.declaredThemes,
        onLog = { line -> System.err.println("[android compatibility daemon] $line") },
      )
      .use { host ->
        var outcome: RenderOutcome? = null
        repeat(3) {
          outcome = host.render(target.id, PreviewOverrides())
          if (outcome is RenderOutcome.Ok) return@use
        }
        assertTrue(
          outcome is RenderOutcome.Ok,
          "catalog preview ${target.id} should render with its carried dependency APIs; got $outcome",
        )
      }
  }

  /**
   * The load-bearing proof for the **android** backend: an Android/Wear catalog's `liveBundle`
   * materialises to a Robolectric daemon whose `compose/figma-svg` lane is **per-variant** — the
   * fix for the baked `figma/<slug>.svg` collapsing every state/selection variant of a component
   * onto one vector (`FilledButton` == `ButtonDisabled` == … in the served SVG). Renders the SVG
   * for pairs that share a slug but differ in state and asserts the bytes differ.
   *
   * Self-skips unless pointed at a packed **android** bundle via
   * `-Dcomposeai.test.androidBundlePath` (pack one with
   * `:samples:design-catalog-wear-m3:composePreviewBundle`) with the Android daemon sidecar
   * reachable (`-Dcomposeai.cli.libDaemonAndroidDir=<…>/staged-daemon-android-libs`) and a local
   * Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT`). The first render cold-starts Robolectric
   * (fetches `android-all-instrumented`), so the budget is generous.
   */
  @Test
  fun `android bundle serves per-variant SVG through a real Robolectric daemon`() {
    val bundlePath = System.getProperty(ANDROID_BUNDLE_PATH_PROPERTY)
    if (bundlePath.isNullOrBlank()) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping android per-variant SVG — set " +
          "-D$ANDROID_BUNDLE_PATH_PROPERTY=<wear bundle .png> (from " +
          "`:samples:design-catalog-wear-m3:composePreviewBundle`)."
      )
      return
    }
    val bundleFile = File(bundlePath)
    if (!bundleFile.isFile) {
      System.err.println("[ServeBundleDaemonTest] skipping android — no bundle at $bundlePath")
      return
    }
    ensureAppHomeConfigured()

    val destDir = Files.createTempDirectory("serve-bundle-daemon-android").toFile()
    val state = ServeBundleDaemon.materialize(bundleFile, destDir, "wear-m3")
    if (state == null) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping android — materialize returned null (see log). Needs the " +
          "lib-daemon-android sidecar (-Dcomposeai.cli.libDaemonAndroidDir=…) + android.jar " +
          "(ANDROID_HOME/ANDROID_SDK_ROOT)."
      )
      return
    }
    // Sanity: the descriptor really is the android launch (Robolectric flags present).
    val parsed =
      descriptorJson.decodeFromString(
        DaemonLaunchDescriptor.serializer(),
        state.descriptor.readText(),
      )
    assertEquals("android", parsed.variant, "wear-m3 bundle should materialize an android daemon")
    assertTrue(
      parsed.systemProperties["robolectric.graphicsMode"] == "NATIVE",
      "android descriptor should carry the robolectric.* render flags",
    )
    assertTrue(
      parsed.systemProperties["composeai.daemon.backgroundSandboxBoot"] == "true",
      "serve-spawned android daemons should default to background pool boot (fast cold start)",
    )

    val host =
      try {
        ServeRenderHost.open(
          descriptorPath = state.descriptor,
          workspaceRoot = state.workspaceRoot,
          workspaceName = state.workspaceName,
          previews = state.previews,
          label = state.label,
          declaredThemes = state.declaredThemes,
          onLog = { line -> System.err.println("[android daemon] $line") },
        )
      } catch (e: Exception) {
        System.err.println(
          "[ServeBundleDaemonTest] skipping android live render — daemon failed to open " +
            "(${e.message})."
        )
        return
      }

    host.use {
      val ids = state.previews.map { it.id }
      // Warm the Robolectric daemon: its FIRST render cold-starts (android-all instrumentation +
      // Compose init) and can blow the host's internal 180s render budget. The daemon stays alive
      // across a timed-out render, so retry a throwaway PNG render until one lands before timing
      // the
      // real per-variant SVG lane. Skip (not fail) if it never warms — that's an
      // environment-too-slow
      // signal, not a regression.
      val warmId = ids.firstOrNull { it.endsWith("CatalogPreviewsKt.FilledButton") } ?: ids.first()
      var warm = false
      for (attempt in 1..4) {
        when (val r = host.render(warmId, PreviewOverrides())) {
          is RenderOutcome.Ok -> {
            warm = true
            break
          }
          else -> System.err.println("[android daemon] warm-up attempt $attempt: $r")
        }
      }
      // A daemon that never warms is an environment signal (a box too slow/small to cold-start
      // Robolectric), NOT a pass — mark it SKIPPED via Assume so it can't masquerade as green while
      // the per-variant assertions below never ran.
      org.junit.jupiter.api.Assumptions.assumeTrue(
        warm,
        "android daemon never warmed after 4 render attempts (cold Robolectric start too slow " +
          "for this box) — skipping the per-variant SVG assertions",
      )

      // Slug-sharing state pairs that the baked per-slug SVG collapses; each must now differ.
      // The `off` / `disabled` halves are `@OverrideVariant` captures riding the primary function,
      // so they are `<fn>_VARIANT_<name>` rather than separate `*Off` / `*Disabled` functions —
      // naming those long-deleted wrappers made every pair `continue` past its assertion.
      val pairs =
        listOf(
          "CatalogPreviewsKt.FilledButton" to "CatalogPreviewsKt.FilledButton_VARIANT_disabled",
          "CatalogPreviewsKt.SwitchButtonOn" to "CatalogPreviewsKt.SwitchButtonOn_VARIANT_off",
        )
      var checked = 0
      for ((aSuffix, bSuffix) in pairs) {
        val aId = ids.firstOrNull { it.endsWith(aSuffix) } ?: continue
        val bId = ids.firstOrNull { it.endsWith(bSuffix) } ?: continue
        val a = host.renderSvg(aId, PreviewOverrides())
        val b = host.renderSvg(bId, PreviewOverrides())
        assertTrue(a is SvgOutcome.Ok, "SVG render of $aId should succeed, got $a")
        assertTrue(b is SvgOutcome.Ok, "SVG render of $bId should succeed, got $b")
        val aBytes = a.svg
        val bBytes = b.svg
        assertTrue(aBytes.isNotEmpty() && bBytes.isNotEmpty(), "SVGs must be non-empty")
        // Optional: dump the rendered vectors so a human can eyeball the per-variant difference.
        System.getProperty("composeai.test.svgDumpDir")
          ?.takeIf { it.isNotBlank() }
          ?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$aSuffix.svg").writeBytes(aBytes)
            File(dir, "$bSuffix.svg").writeBytes(bBytes)
          }
        assertTrue(
          !aBytes.contentEquals(bBytes),
          "per-variant SVG regression: $aSuffix and $bSuffix rendered byte-identical SVGs " +
            "(the daemon collapsed the state variant)",
        )
        checked++
      }
      assertTrue(checked > 0, "expected at least one slug-sharing state pair in the wear bundle")
    }
  }

  /**
   * Locates the bundle + sidecars and calls [ServeBundleDaemon.materialize] into a fresh temp dir
   * under [label], or logs why and returns `null` so the caller self-skips.
   */
  private fun materializeOrSkip(label: String): ServeSessionState? {
    val bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY) ?: DEFAULT_BUNDLE_PATH
    val bundleFile = File(bundlePath)
    if (!bundleFile.isFile) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping ($label) — no bundle at $bundlePath. Pack one via " +
          "`compose-preview bundle pack --module :samples:design-catalog-m3 -o $bundlePath`."
      )
      return null
    }

    ensureAppHomeConfigured()

    val destDir = Files.createTempDirectory("serve-bundle-daemon-test-$label").toFile()
    val state = ServeBundleDaemon.materialize(bundleFile, destDir, "compose-m3")
    if (state == null) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping ($label) — materialize returned null (see log above); " +
          "likely missing lib-daemon-desktop/lib-renderer sidecars. Run `:cli:installDist` and/or " +
          "pass -Dcomposeai.cli.appHome=<install-root>."
      )
      return null
    }
    return state
  }

  /**
   * If no explicit `-Dcomposeai.cli.appHome` override is set, point it at this checkout's own
   * `cli/build/install/compose-preview/` when that `:cli:installDist` output exists — lets the test
   * run end to end in a normal dev/CI checkout without extra flags, while still respecting an
   * explicit override.
   */
  private fun ensureAppHomeConfigured() {
    if (System.getProperty(APP_HOME_PROPERTY) != null) return
    val installDir = File(locateRepoRoot(), "cli/build/install/compose-preview")
    if (installDir.isDirectory) {
      System.setProperty(APP_HOME_PROPERTY, installDir.absolutePath)
    }
  }

  /** Walk up from the test JVM's working dir to find the repo root (has `settings.gradle.kts`). */
  private fun locateRepoRoot(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("Could not locate repo root above ${File(".").canonicalFile}")
  }

  private companion object {
    const val BUNDLE_PATH_PROPERTY = "composeai.test.bundlePath"
    const val ANDROID_BUNDLE_PATH_PROPERTY = "composeai.test.androidBundlePath"
    const val ANDROID_COMPATIBILITY_BUNDLE_PATH_PROPERTY =
      "composeai.test.androidCompatibilityBundlePath"
    const val ANDROID_COMPATIBILITY_PREVIEW_CONTAINS_PROPERTY =
      "composeai.test.androidCompatibilityPreviewContains"
    const val APP_HOME_PROPERTY = "composeai.cli.appHome"
    const val DEFAULT_BUNDLE_PATH = "/tmp/m3-bundle.png"

    val descriptorJson = Json { ignoreUnknownKeys = true }
  }
}
