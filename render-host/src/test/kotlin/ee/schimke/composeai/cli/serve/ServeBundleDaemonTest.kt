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
 * Exercises [ServeBundleDaemon.materialize] against a real packed desktop bundle (as `serve
 * --catalogs --allow-render-trusted` fetches). Needs a bundle on disk (from `compose-preview bundle
 * pack --module :samples:design-catalog-m3 -o <path>`) and the CLI's `:cli:installDist` sidecars,
 * neither produced by `:cli:test`, so it self-skips without them.
 *
 * `-Dcomposeai.test.bundlePath=<file>` points at the bundle (default `/tmp/m3-bundle.png`).
 * Sidecars are found under this checkout's `cli/build/install/compose-preview/`, or via
 * `-Dcomposeai.cli.appHome=<install-root>`.
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
    // CMP artifacts ship `androidx.compose.*` packages, which `mustDelegateToParent` delegates;
    // left in the child loader, the sidecar's Compose answers and mismatched versions fail with
    // NoSuchMethodError.
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
    // Skiko must move with Compose: `skiko-awt`'s `org.jetbrains.skia.*` bindings are delegated
    // too, and must match the native library (see DesktopRendererGraphAlignmentFunctionalTest).
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

    // The output dir makes DaemonMain.dataRoot non-null, which registers file-based products like
    // compose/figma-svg; it must sit under the working dir so `data/` is inside the temp tree.
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

    // Knob sidecars must be folded into the ServePreview set; the M3 FilledButton declares a
    // `label` string knob.
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
   * Compatibility proof for a published Android catalog whose Compose/AndroidX/Kotlin versions
   * differ from the sidecar's: those parent-delegated packages must come from the catalog.
   *
   * Self-skips without `-Dcomposeai.test.androidCompatibilityBundlePath=<bundle.png>`;
   * `-Dcomposeai.test.androidCompatibilityPreviewContains=<substring>` picks a preview (default:
   * the first).
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
   * The android backend: a Wear catalog's `liveBundle` materialises to a Robolectric daemon whose
   * `compose/figma-svg` lane is per-variant, unlike a baked per-slug SVG. Renders slug-sharing
   * pairs that differ in state and asserts the bytes differ.
   *
   * Self-skips unless `-Dcomposeai.test.androidBundlePath` names a packed android bundle
   * (`:samples:design-catalog-wear-m3:composePreviewBundle`), the Android sidecar is reachable
   * (`-Dcomposeai.cli.libDaemonAndroidDir=<…>/staged-daemon-android-libs`), and an SDK is present.
   * The first render cold-starts Robolectric, so the budget is generous.
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
      // Warm the daemon with throwaway renders first: the cold first render can exceed the host's
      // 180s budget. Skip (not fail) if it never warms.
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
      // A daemon that never warms is an environment signal; skip rather than pass silently.
      org.junit.jupiter.api.Assumptions.assumeTrue(
        warm,
        "android daemon never warmed after 4 render attempts (cold Robolectric start too slow " +
          "for this box) — skipping the per-variant SVG assertions",
      )

      // Slug-sharing state pairs that a baked per-slug SVG collapses. The `off` / `disabled` halves
      // are `<fn>_VARIANT_<name>` captures of the primary function.
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
   * Point `-Dcomposeai.cli.appHome` at this checkout's `cli/build/install/compose-preview/` when it
   * exists and no override is set.
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
