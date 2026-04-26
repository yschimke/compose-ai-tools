package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Pure-data builders for the renderer test JVM's classpath, JVM args, and system properties.
 *
 * Extracted out of [AndroidPreviewSupport.registerAndroidTasks] so the upcoming "preview daemon"
 * (see `docs/daemon/DESIGN.md`) can reuse the exact same construction logic to launch its own JVM —
 * instead of duplicating the inline Test-task DSL block. Each helper returns a value
 * (FileCollection / List / Map). None of them touches the Test task DSL directly. The Test task
 * lambda still composes the final classpath (it appends the AGP unit-test classes / classpath,
 * which can only be resolved late via `project.tasks.findByName("test${Cap}UnitTest")`) and still
 * registers the dynamic argument providers (a11y / tier) which need lazy `Provider<>` evaluation at
 * execution time.
 *
 * Ordering invariants (load-bearing — see callers' comments and `AndroidPreviewSupport.kt`):
 * - Robolectric properties dir BEFORE consumer test resources, so the renderer's
 *   `robolectric.properties` wins classloader lookup.
 * - Renderer artifacts BEFORE consumer test runtime, so the renderer's pinned kotlinx-serialization
 *   / Roborazzi versions win on classload conflicts.
 * - SDK boot classpath LAST in the outer FileCollection, since it's only there to satisfy JUnit's
 *   introspection of the test class signatures (the sandbox supplies its own `android-all`
 *   framework jars).
 *
 * Behaviour must match the inline construction byte-for-byte; this file is a refactor with no
 * semantic change.
 */
internal object AndroidPreviewClasspath {

  private val artifactType: Attribute<String> = Attribute.of("artifactType", String::class.java)

  /**
   * Builds the renderer test classpath as the existing inline block does today, EXCLUDING the
   * trailing AGP test classes / AGP test classpath additions which are still resolved inside the
   * Test task lambda (they need `project.tasks.findByName("test${Cap}UnitTest")` which only
   * resolves late).
   *
   * Inputs are everything the existing inline block reads. Output is a single FileCollection
   * equivalent to the existing `resolvedClasspath` local.
   */
  fun buildTestClasspath(
    project: Project,
    bootClasspath: Provider<List<RegularFile>>,
    rendererConfig: Configuration,
    rendererClassDirs: FileCollection,
    sourceClassDirs: FileCollection,
    testConfig: Configuration?,
    screenshotTestRuntimeConfig: Configuration?,
    unitTestConfigDir: Provider<Directory>,
    robolectricPropertiesDir: Provider<Directory>,
  ): FileCollection =
    project.files().apply {
      // Robolectric properties dir BEFORE consumer test resources so our
      // Application override wins when classloader.getResource walks the
      // classpath. Consumers with their own `robolectric.properties` at
      // the same package path are unusual — they'd need it specifically
      // for this renderer's test class.
      from(robolectricPropertiesDir)
      from(rendererConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
      from(rendererClassDirs)
      if (testConfig != null) {
        from(testConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
        from(
          testConfig.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }
      // screenshotTest source set has its own runtime config — any
      // `screenshotTestImplementation(...)` dep the consumer declared is
      // only visible here, not via `testConfig`. Include it so previews
      // under `src/screenshotTest/` can reference those classes at
      // render time. No-op when the screenshot plugin isn't applied.
      screenshotTestRuntimeConfig?.let { stConfig ->
        from(stConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
        from(
          stConfig.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }
      from(sourceClassDirs)
      from(unitTestConfigDir)
      // SDK stub android.jar on the OUTER classpath so JUnit can introspect
      // the test class (RobolectricRenderTest.kt references android.graphics.Bitmap,
      // android.view.PixelCopy, etc. in method signatures). Without it, JUnit fails
      // with `NoClassDefFoundError: android/graphics/Bitmap` during test discovery,
      // before Robolectric's sandbox classloader is even created.
      //
      // Inside the sandbox, `ParameterizedRobolectricTestRunner` loads the test class
      // through Robolectric's InstrumentingClassLoader, which delegates `android.*`
      // resolution to its own `android-all` artifact (real framework classes, with
      // shadows applied). The outer stub does NOT shadow the sandboxed PixelCopy.
      //
      // Sourced from AGP's SdkComponents so we don't have to parse local.properties
      // or read rootProject.file(...).
      from(project.files(bootClasspath))
    }

  /**
   * Static JVM open flags that the renderPreviews test JVM needs. Pure data — no Gradle DSL
   * coupling.
   */
  fun buildJvmArgs(): List<String> =
    listOf(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      // Robolectric's `ShadowVMRuntime.getAddressOfDirectByteBuffer`
      // reflectively invokes `DirectByteBuffer.address()`; under JDK 17+
      // module rules this fails with IllegalAccessException without this
      // opens. Reached via `PathIterator` — triggered here by Wear Compose's
      // curved text renderer.
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )

  /**
   * Static system properties (graphicsMode, looperMode, conscryptMode, pixelCopyRenderMode,
   * roborazzi.test.record, composeai.render.manifest, composeai.render.outputDir,
   * composeai.fonts.cacheDir, composeai.fonts.offline). Caller passes the resolved values for the
   * path-bearing ones; the helper returns the full map.
   *
   * Note: the dynamic per-task ArgumentProviders (a11y, tier) stay inline because they need lazy
   * `Provider<>` evaluation at task execution time.
   *
   * The returned map preserves insertion order — callers iterate it to call `systemProperty(...)`
   * on the Test task and the order is irrelevant to the JVM (system properties are an unordered map
   * on the receiving side), but keeping it stable simplifies golden-output comparisons in future
   * tests.
   */
  fun buildSystemProperties(
    manifestPath: String,
    rendersDir: String,
    fontsCacheDir: String,
    fontsOffline: String,
  ): Map<String, String> =
    linkedMapOf(
      // Belt-and-braces for the graphics/looper modes. Config now
      // lives in `ee/schimke/composeai/renderer/robolectric.properties`
      // (see `RobolectricRenderTestBase` KDoc for why we can't use
      // `@GraphicsMode` directly). These system properties are a third
      // independent Robolectric config channel and cost nothing to
      // keep — survive both annotation and properties paths regressing.
      "robolectric.graphicsMode" to "NATIVE",
      "robolectric.looperMode" to "PAUSED",
      // Conscrypt isn't needed for preview rendering (no TLS/HTTP paths
      // execute) and its native library is flaky on some Linux sandboxes
      // — e.g. missing/ABI-mismatched `libstdc++.so.6`. Telling Robolectric
      // to skip the install avoids those failures without shipping our
      // own Conscrypt stubs. See `ConscryptMode` /
      // `ConscryptModeConfigurer` in Robolectric.
      "robolectric.conscryptMode" to "OFF",
      // Routes ShadowPixelCopy through HardwareRenderingScreenshot →
      // ImageReader + HardwareRenderer.syncAndDraw, the only path that
      // replays Compose's RenderNodes correctly.
      "robolectric.pixelCopyRenderMode" to "hardware",
      // Roborazzi defaults to "compare" mode (which doesn't write pixels
      // unless the expected baseline exists). Force "record" so every run
      // writes fresh PNGs.
      "roborazzi.test.record" to "true",
      "composeai.render.manifest" to manifestPath,
      "composeai.render.outputDir" to rendersDir,
      // GoogleFont interceptor cache — defaults to
      // `<project>/.compose-preview-history/fonts/`, same root the
      // history task uses, so committed TTFs sit beside committed PNGs.
      // The renderer class no-ops when this property is absent, so the
      // feature is fully additive for existing consumers.
      "composeai.fonts.cacheDir" to fontsCacheDir,
      // `-PcomposePreview.fontsOffline=true` (or the same Gradle property
      // on a CI profile) skips network on cache miss so the render
      // shows the fallback font rather than silently fetching from
      // `fonts.googleapis.com`.
      "composeai.fonts.offline" to fontsOffline,
    )
}
