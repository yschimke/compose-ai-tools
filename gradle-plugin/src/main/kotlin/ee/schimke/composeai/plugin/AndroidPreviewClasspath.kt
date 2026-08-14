package ee.schimke.composeai.plugin

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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
 * - Renderer artifacts BEFORE the consumer's remaining test entries, so the renderer's pinned
 *   kotlinx-serialization / Roborazzi versions win on classload conflicts.
 * - SDK boot classpath LAST in the outer FileCollection, since it's only there to satisfy JUnit's
 *   introspection of the test class signatures (the sandbox supplies its own `android-all`
 *   framework jars).
 *
 * Single-resolution invariant: every *module* artifact on the render classpath comes from
 * `rendererConfig`, which `extendsFrom(testConfig)` and therefore resolves the renderer's and the
 * consumer's test dependencies as ONE graph with one version per module. The consumer's
 * separately-resolved graph is no longer concatenated on top — see [buildAgpClasspathExtras] and
 * [RenderClasspathDuplicates] for what went wrong when it was. Ordering only decides class-lookup
 * winners when duplicates exist, so keeping the classpath duplicate-free is what makes the ordering
 * rules above a safety net rather than the primary mechanism.
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
    bootClasspathFallback: Provider<List<File>>,
    rendererConfig: Configuration,
    rendererClassDirs: FileCollection,
    sourceClassDirs: FileCollection,
    testConfig: Configuration?,
    screenshotTestRuntimeConfig: Configuration?,
    unitTestConfigDir: Provider<Directory>,
    robolectricPropertiesDir: Provider<Directory>,
    legacyClasspathUnion: Boolean = false,
  ): FileCollection =
    project.files().apply {
      // Robolectric properties dir BEFORE consumer test resources so our
      // Application override wins when classloader.getResource walks the
      // classpath. Consumers with their own `robolectric.properties` at
      // the same package path are unusual — they'd need it specifically
      // for this renderer's test class.
      from(robolectricPropertiesDir)
      from(rendererConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
      // `android-classes` alongside `jar` so AAR-packaged modules (sibling project deps published
      // as AARs, AndroidX libraries) contribute their `classes.jar`. Previously this view was
      // taken from `testConfig` instead; sourcing it from `rendererConfig` keeps every module
      // artifact coming from ONE resolution — see the block below.
      from(
        rendererConfig.incoming
          .artifactView { attributes.attribute(artifactType, "android-classes") }
          .files
      )
      from(rendererClassDirs)
      // `rendererConfig` already `extendsFrom(testConfig)` (see AndroidPreviewSupport), so it
      // resolves the renderer's dependencies and the consumer's test-runtime dependencies in ONE
      // graph — Gradle picks a single coherent version per module. Re-adding `testConfig`'s own
      // artifact view on top of that undoes exactly what `extendsFrom` bought: the second view is
      // a SEPARATE resolution, so any module the combined graph upgraded lands here twice, at two
      // versions, in front of one classloader.
      //
      // Measured on homeassistant-remotecompose (plugin 0.17.16, AGP 9.3): every module in the
      // unit-test graph is also in the renderer graph — the re-add contributed zero unique modules
      // and nine duplicated ones (androidx.test:core 1.5.0+1.6.1, monitor 1.6.1+1.8.0, espresso,
      // asm 9.7.1+9.10.1, commons-logging, okhttp …). Also `org.bouncycastle:bcprov-jdk18on` at
      // 1.85 (Robolectric, via renderer-android) and 1.84 (the consumer's own mockserver test
      // dep), whose mixed asn1/provider classes threw `NoSuchFieldError` out of
      // `compositekem.KeyFactorySpi.<clinit>` and failed every a11y preview —
      // homeassistant-remotecompose#495, worked around downstream with a version force.
      //
      // Non-module entries that live ONLY on AGP's test classpath (the unit-test merged `R.jar`,
      // generated dirs) are preserved — they're appended separately via
      // [buildAgpClasspathExtras], which subtracts just the module artifacts.
      //
      // `-PcomposePreview.legacyClasspathUnion=true` restores the old concatenation for a
      // consumer who turns out to depend on a testConfig-only artifact we haven't anticipated.
      if (testConfig != null && legacyClasspathUnion) {
        from(testConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
        from(
          testConfig.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }
      // screenshotTest source set has its own runtime config — any
      // `screenshotTestImplementation(...)` dep the consumer declared is
      // only visible here, not via `testConfig`. `rendererConfig` now
      // `extendsFrom`s it (see AndroidPreviewSupport), so those deps are already
      // in the single graph above at coherent versions; re-adding this
      // separately-resolved view is the legacy concatenation behaviour.
      // No-op when the screenshot plugin isn't applied.
      screenshotTestRuntimeConfig
        ?.takeIf { legacyClasspathUnion }
        ?.let { stConfig ->
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
      // or read rootProject.file(...). When AGP's bootClasspath resolves empty (rare
      // — e.g. compileSdk left at its DSL default on a freshly-applied AGP variant,
      // or a module type that doesn't bind sdkComponents), the fallback derived from
      // `local.properties` / `ANDROID_HOME` rescues the load. See issue #1243 — when
      // neither path supplies android.jar, Robolectric's own `Config.<clinit>` fails
      // with `NoClassDefFoundError: android/app/Application` before the test runs.
      from(project.files(bootClasspath))
      from(project.files(bootClasspathFallback))
    }

  /**
   * AGP's `test<Variant>UnitTest` classpath with the module artifacts removed — i.e. only the
   * entries that exist *nowhere else*, which is the reason that classpath is appended at all.
   *
   * The render tasks append `agpTestTask.classpath` to pick up files AGP contributes outside the
   * resolved artifact views: chiefly the unit-test merged `R.jar` (added to
   * `<variant>UnitTestRuntimeClasspath` as a raw file dep with no `artifactType` attribute, so the
   * attribute-filtered `artifactView` in [buildTestClasspath] drops it — issue #136), plus
   * generated class dirs. Those must stay.
   *
   * What must NOT stay is the rest of it: AGP's classpath also carries every module artifact from
   * the consumer's unit-test graph, resolved independently of the renderer graph. Appending those
   * puts a second, older copy of any module the renderer graph upgraded in front of the same
   * classloader — the duplicate-jar failure mode described on [RenderClasspathDuplicates].
   *
   * Subtraction is by *file identity* against `testConfig`'s own artifact views, which is exactly
   * right for this: when the two graphs disagree on a version they resolve to different files, so
   * the consumer-graph copy is the one that gets dropped and the renderer-graph copy survives.
   * `FileCollection.minus` keeps the whole thing lazy and configuration-cache friendly.
   *
   * Returns [agpTestClasspath] untouched when there's no `testConfig` to subtract (nothing was
   * double-added in the first place) or when `legacyClasspathUnion` restores the old behaviour.
   */
  fun buildAgpClasspathExtras(
    project: Project,
    agpTestClasspath: FileCollection,
    testConfig: Configuration?,
    legacyClasspathUnion: Boolean = false,
  ): FileCollection {
    if (testConfig == null || legacyClasspathUnion) return agpTestClasspath
    val moduleArtifacts =
      project.files(
        testConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files,
        testConfig.incoming
          .artifactView { attributes.attribute(artifactType, "android-classes") }
          .files,
      )
    return agpTestClasspath.minus(moduleArtifacts)
  }

  /**
   * Maps every resolved artifact file on the render classpath to the exact `group:name:version`
   * Gradle picked for it, so [RenderClasspathDuplicates] can compare modules by identity instead of
   * guessing from filenames.
   *
   * Built from the same two artifact views the classpath itself uses (`jar` and `android-classes`)
   * across [configurations] — the *default* view would return `.aar` files that never appear on the
   * classpath, so the map would match nothing. Pass every configuration that can contribute module
   * artifacts (renderer or daemon, plus screenshotTest and — in legacy-union mode — testConfig);
   * overlapping entries agree by construction, since the key is the file path.
   *
   * Project artifacts are keyed `project:<path>` with an empty version, so a project's own jar
   * forms a single-version bucket rather than being mistaken for a module.
   *
   * Returns a `Provider` so nothing resolves at configuration time: task actions call `get()`, and
   * the configuration cache serialises the provider rather than the live `Configuration`.
   */
  fun buildArtifactCoordinates(
    project: Project,
    configurations: List<Configuration>,
  ): Provider<Map<String, String>> {
    val views = configurations.flatMap { configuration ->
      listOf("jar", "android-classes").map { type ->
        configuration.incoming
          .artifactView {
            attributes.attribute(artifactType, type)
            // A view that can't resolve some artifact must not sink the whole render — this is
            // diagnostic input, so degrade to a smaller map instead of failing the task.
            isLenient = true
          }
          .artifacts
          .resolvedArtifacts
          .map { artifacts ->
            artifacts.associate { artifact ->
              val id = artifact.id.componentIdentifier
              val coordinate =
                when (id) {
                  is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                  is ProjectComponentIdentifier -> "project:${id.projectPath}:"
                  else -> "${id.displayName}:"
                }
              artifact.file.absolutePath to coordinate
            }
          }
      }
    }
    if (views.isEmpty()) return project.provider { emptyMap() }
    return views.reduce { acc, next -> acc.zip(next) { a, b -> a + b } }
  }

  /**
   * Lazy fallback for `android.jar`, used when AGP's `sdkComponents.bootClasspath` provider returns
   * an empty list (issue #1243). Reads `sdk.dir` from `local.properties` first, falls back to the
   * `ANDROID_HOME` / `ANDROID_SDK_ROOT` env vars, then picks the highest-versioned `platforms/
   * android-N/android.jar` under that SDK root. Any version is acceptable for outer-classpath
   * resolution of `android.app.Application` — Robolectric still drives the in-sandbox framework
   * from its own `android-all` artifact, gated by `sdk=…` in the generated
   * `robolectric.properties`.
   *
   * Returns an empty list when no SDK can be located on disk; in that case
   * [validateApplicationOnClasspath] surfaces a clear error before the test JVM forks.
   */
  fun buildBootClasspathFallback(project: Project): Provider<List<File>> {
    // `project.rootDir` is a plain `File` snapshot of the build root and IP-safe to read from
    // a sub-project (no `Project.method` round-trip into the root project). `rootProject.layout
    // .projectDirectory.file(...)` is rejected under isolated projects as "Project.layout
    // functionality on another project". See issue #1546.
    val localProperties = File(project.rootDir, "local.properties")
    val androidHomeEnv = project.providers.environmentVariable("ANDROID_HOME")
    val androidSdkRootEnv = project.providers.environmentVariable("ANDROID_SDK_ROOT")
    return project.providers.provider {
      val sdkDir =
        sdkDirFromLocalProperties(localProperties)
          ?: androidHomeEnv.orNull?.takeIf { it.isNotBlank() }
          ?: androidSdkRootEnv.orNull?.takeIf { it.isNotBlank() }
          ?: return@provider emptyList<File>()
      val androidJar = highestPlatformAndroidJar(File(sdkDir))
      if (androidJar != null && androidJar.isFile) listOf(androidJar) else emptyList()
    }
  }

  /**
   * Throws a Gradle-friendly `IllegalStateException` describing how to fix the situation when the
   * resolved test classpath has no entry that defines `android/app/Application.class`. Intended for
   * a `doFirst {}` on the `composePreviewRender` `Test` task so the user sees a precise error
   * rather than the `NoClassDefFoundError` in Robolectric's `Config.<clinit>` (issue #1243).
   */
  fun validateApplicationOnClasspath(classpath: Iterable<File>) {
    val scanned = classpath.filter { it.isFile && it.name.endsWith(".jar") }
    val found = scanned.any { jar -> jarContainsEntry(jar, "android/app/Application.class") }
    if (found) return
    val sample = scanned.take(10).joinToString("\n") { " - ${it.absolutePath}" }
    val more = if (scanned.size > 10) "\n - (+${scanned.size - 10} more)" else ""
    throw IllegalStateException(
      """
        |compose-preview: android.jar is not on the composePreviewRender test classpath, so
        |Robolectric's Config.<clinit> will fail with NoClassDefFoundError: android/app/Application
        |before any preview renders. (issue #1243)
        |
        |Common causes:
        | * `compileSdk` is unset in the module's `android { }` block (AGP's sdkComponents
        |   .bootClasspath then resolves empty).
        | * `sdk.dir` is missing from `local.properties` AND `ANDROID_HOME` / `ANDROID_SDK_ROOT`
        |   are unset, so the plugin's fallback couldn't locate the SDK either.
        | * No `platforms/android-*/android.jar` is installed at the resolved SDK root.
        |
        |Classpath JARs scanned (none contained android/app/Application.class):
        |$sample$more
        """
        .trimMargin()
    )
  }

  private fun sdkDirFromLocalProperties(localProperties: File): String? {
    if (!localProperties.isFile) return null
    return runCatching {
      val props = java.util.Properties()
      localProperties.inputStream().use { props.load(it) }
      props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    }
      .getOrNull()
  }

  private fun highestPlatformAndroidJar(sdkRoot: File): File? {
    val platforms = File(sdkRoot, "platforms")
    if (!platforms.isDirectory) return null
    return platforms
      .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
      .orEmpty()
      .mapNotNull { dir ->
        val jar = File(dir, "android.jar")
        if (jar.isFile) dir.name.removePrefix("android-").toIntOrNull()?.let { it to jar } else null
      }
      .maxByOrNull { it.first }
      ?.second
  }

  private fun jarContainsEntry(jar: File, entryPath: String): Boolean = runCatching {
    ZipFile(jar).use { it.getEntry(entryPath) != null }
  }.getOrDefault(false)

  /**
   * Static JVM open flags that the composePreviewRender test JVM needs. Pure data — no Gradle DSL
   * coupling.
   */
  fun buildJvmArgs(): List<String> =
    listOf(
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      // Robolectric's `ShadowVMRuntime.getAddressOfDirectByteBuffer`
      // reflectively invokes `DirectByteBuffer.address()`; under JDK 17+
      // module rules this fails with IllegalAccessException without this
      // opens. Reached via `PathIterator` — triggered here by Wear Compose's
      // curved text renderer.
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      // Robolectric's `FileDescriptorInterceptor.setInt` reflects into
      // `jdk.internal.access.SharedSecrets#getJavaIOFileDescriptorAccess()`
      // to mutate `FileDescriptor.fd` (the older `Field.setInt` path was
      // replaced in 4.13+). On SDK 36 sandboxes the framework's
      // `com.android.internal.os.ApplicationSharedMemory.create()` runs
      // during `AndroidTestEnvironment.setUpApplicationState`, hits the
      // interceptor, and without this opens the JDK raises
      // `IllegalAccessException: class … cannot access class
      // jdk.internal.access.SharedSecrets (in module java.base) because
      // module java.base does not export jdk.internal.access` — wrapped by
      // Robolectric as `Failed to interact with raw FileDescriptor
      // internals; perhaps JRE has changed?`. Issue #1328.
      "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )

  /**
   * Static system properties (graphicsMode, looperMode, conscryptMode, pixelCopyRenderMode,
   * roborazzi.test.record, composeai.render.manifest, composeai.render.outputDir,
   * composeai.fonts.cacheDir, composeai.fonts.offline, composeai.svg.embedFonts,
   * composeai.fonts.failOnFallback). Caller passes the resolved values for the path-bearing /
   * opt-in ones; the helper returns the full map.
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
    svgEmbedFonts: String = "true",
    svgBackground: String = "false",
    fontsFailOnFallback: String = "true",
    hostTheme: String = "",
    fixedTime: String = "",
    linkBufferComposer: String = "false",
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
      // GoogleFont interceptor cache — a shared, machine-local cache under
      // `${'$'}XDG_CACHE_HOME/composeai/fonts` (else `~/.cache/composeai/fonts`),
      // computed by [composeAiFontsCacheDir]. The renderer class no-ops when
      // this property is absent, so the feature is fully additive for existing
      // consumers.
      "composeai.fonts.cacheDir" to fontsCacheDir,
      // `-PcomposePreview.fontsOffline=true` (or the same Gradle property
      // on a CI profile) skips network on cache miss so the render
      // shows the fallback font rather than silently fetching from
      // `fonts.googleapis.com`.
      "composeai.fonts.offline" to fontsOffline,
      // Controls whether the `compose/figma-svg` export embeds each text node's face as an
      // `@font-face` (so the layered SVG renders the real typeface instead of a browser-substituted
      // `sans-serif`). ON by default; opt out with `-Dcomposeai.svg.embedFonts=false` (or
      // `-PcomposePreview.svgEmbedFonts=false`). Read in the daemon JVM by
      // `ComposeFigmaSvgExtension`,
      // so it must be forwarded here — else the value set on the Gradle invocation never reaches
      // the
      // daemon.
      "composeai.svg.embedFonts" to svgEmbedFonts,
      // Daemon-wide default background mode for the `compose/figma-svg` export — `none` (the
      // default: an import should land as editable layers, not on an opaque rect a designer has to
      // delete), `device` (the Wear mask shape), `content-shape` (the component's own silhouette),
      // or `full-bleed` (a plain tile). Set it with `-Dcomposeai.svg.background=device` (or
      // `-PcomposePreview.svgBackground=device`); `true`/`false` still work as the pre-modes
      // aliases for `device`/`none`. A per-render `PreviewOverrides.svgBackground` wins over it.
      // Read in the daemon JVM by
      // `ComposeFigmaSvgDataProducer`, so it must be forwarded here — else the value set on the
      // Gradle invocation never reaches the daemon and the opt-in does nothing.
      "composeai.svg.background" to svgBackground,
      // Whether an unresolved downloadable `Font(GoogleFont(...))` fails its preview (default) or
      // degrades to a `<png>.warnings.json` warning. Read in the forked render / daemon JVM by
      // `FontResolutionDiagnostics`, so it must be forwarded here — else
      // `-Dcomposeai.fonts.failOnFallback=false` (or `-PcomposePreview.fontsFailOnFallback=false`)
      // set on the Gradle invocation never reaches the JVM that reads it and the opt-out is
      // unreachable.
      "composeai.fonts.failOnFallback" to fontsFailOnFallback,
      // The Android theme the preview host activity runs under, e.g. `@style/Theme.Foo`. Read in
      // the forked render / daemon JVM by `PreviewHostTheme`, which is what lets an `AndroidView`
      // preview resolve app-owned `?attr/…` references. Empty by default: an application module
      // inherits `<application android:theme>` without any configuration, and only a **library**
      // module — which has no application theme to inherit — needs to name one. Forwarded even
      // when blank so the property's presence isn't a second thing to keep in sync.
      "composeai.render.hostTheme" to hostTheme,
      // The instant the render JVM pins its wall clock to, e.g. `10:10` (the default when blank) or
      // `off`. Read in the forked render / daemon JVM by `PreviewClock`, which is what stops an
      // activity hero showing `TimeText` from producing a different PNG every minute (issue #3239).
      // Forwarded here because the property has to reach the JVM that renders, not the Gradle one.
      "composeai.render.fixedTime" to fixedTime,
      // Whether this render opts into the Compose runtime's rewritten `SlotTable` (the "link
      // buffer" composer, `ComposeRuntimeFlags.isLinkBufferComposerEnabled`). Read in the forked
      // render / daemon JVM by `LinkBufferComposer`, which has to set the flag before the first
      // composition — so it has to arrive as a launch property, not as something the Gradle JVM
      // reads. `"false"` by default: an opt-in stays opt-in.
      "composeai.render.linkBufferComposer" to linkBufferComposer,
    )
}

/**
 * Absolute path of the shared GoogleFont download cache: `$XDG_CACHE_HOME/composeai/fonts` when
 * `XDG_CACHE_HOME` is set and non-blank, else `~/.cache/composeai/fonts`. Mirrors `common/io`'s
 * `composeAiCacheDir("fonts")` — the plugin can't depend on that module, so the XDG resolution is
 * inlined here.
 *
 * Downloaded fonts are regenerable and identical across projects (keyed by family/weight/italic),
 * so they belong in one user-level cache rather than inside each project's
 * `.compose-preview-history/`. Resolved through [org.gradle.api.provider.ProviderFactory] so the
 * configuration cache records `XDG_CACHE_HOME` / `user.home` as inputs instead of flagging a raw
 * `System.getenv` read.
 */
internal fun composeAiFontsCacheDir(project: Project): String =
  File(composeAiCacheRoot(project), "fonts").absolutePath

/**
 * Root of the user-level cache — `$XDG_CACHE_HOME/composeai` when `XDG_CACHE_HOME` is set and
 * non-blank, else `~/.cache/composeai`. Resolved through [org.gradle.api.provider.ProviderFactory]
 * so the configuration cache records `XDG_CACHE_HOME` / `user.home` as inputs instead of flagging a
 * raw `System.getenv` read.
 */
private fun composeAiCacheRoot(project: Project): File {
  val xdg =
    project.providers.environmentVariable("XDG_CACHE_HOME").orNull?.takeIf { it.isNotBlank() }
  return if (xdg != null) File(xdg, "composeai")
  else File(project.providers.systemProperty("user.home").get(), ".cache/composeai")
}

/** Directory name of the legacy in-tree history archive, kept for backwards compatibility. */
internal const val LEGACY_HISTORY_DIRNAME: String = ".compose-preview-history"

/**
 * Absolute path of this module's render-history archive:
 * `$XDG_CACHE_HOME/composeai/history/<workspaceSlug>/<moduleRel>`.
 *
 * **Mirror of `common/io`'s `composeAiHistoryDir` — the plugin can't depend on that module, so the
 * layout is inlined here.** A third implementation lives in the VS Code extension
 * (`vscode-extension/src/historyPaths.ts`), which reads the archive the daemon writes. All three
 * must agree byte-for-byte; they're pinned by `HistoryPathsTest` (`:common-io`),
 * `AndroidPreviewClasspathTest` (here) and `historyPaths.test.ts` sharing the same golden vectors.
 * A drift between them doesn't crash — it silently gives the reader an empty history drawer.
 *
 * History used to live at `<projectDir>/.compose-preview-history`, which grew an untracked
 * directory next to every previewed module's sources. It's a semi-persistent timeline of local
 * edits — cache-shaped data, never user-authored — so it belongs beside the font cache rather than
 * in the working tree. The reporting-branch flow is unaffected: that publishes to a git ref, and
 * the in-tree directory was only its local staging area.
 *
 * An existing `<projectDir>/.compose-preview-history` wins, so upgrading doesn't strand a timeline
 * someone already has. Nothing recreates it once removed.
 */
internal fun composeAiHistoryDir(project: Project): String {
  val projectDir = project.layout.projectDirectory.asFile
  val legacy = File(projectDir, LEGACY_HISTORY_DIRNAME)
  if (legacy.isDirectory) return legacy.absolutePath
  val historyRoot = File(composeAiCacheRoot(project), "history")
  return File(
      File(historyRoot, composeAiHistoryWorkspaceSlug(project.rootDir)),
      composeAiHistoryModuleSegment(project.rootDir, projectDir),
    )
    .absolutePath
}

/** See `common/io`'s `composeAiHistoryWorkspaceSlug`. Kept byte-identical to it. */
internal fun composeAiHistoryWorkspaceSlug(workspaceRoot: File): String {
  val normalised = workspaceRoot.absolutePath.replace('\\', '/').trimEnd('/')
  val digest =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(normalised.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(12)
  val name = sanitiseHistorySegment(normalised.substringAfterLast('/'))
  return if (name.isEmpty()) digest else "$name-$digest"
}

/** See `common/io`'s `composeAiHistoryModuleSegment`. Kept byte-identical to it. */
internal fun composeAiHistoryModuleSegment(workspaceRoot: File, projectDir: File): String {
  val root = workspaceRoot.absolutePath.replace('\\', '/').trimEnd('/')
  val module = projectDir.absolutePath.replace('\\', '/').trimEnd('/')
  if (module == root) return "_root"
  if (!module.startsWith("$root/")) {
    return "_external-" + composeAiHistoryWorkspaceSlug(projectDir)
  }
  return module
    .removePrefix("$root/")
    .split('/')
    .filter { it.isNotEmpty() }
    .joinToString("/") { sanitiseHistorySegmentInjectively(it) }
    .ifEmpty { "_root" }
}

/**
 * See `common/io`'s `sanitiseHistorySegmentInjectively`. Kept byte-identical to it: a segment that
 * sanitising had to rewrite carries an 8-hex digest of its original text, so `ui components` and
 * `ui-components` stay distinct modules.
 */
private fun sanitiseHistorySegmentInjectively(segment: String): String {
  val sanitised = sanitiseHistorySegment(segment)
  if (sanitised == segment) return sanitised
  val digest =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(segment.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(8)
  return "$sanitised-$digest"
}

/** ASCII-only on purpose — see `common/io`'s counterpart for why. */
private fun sanitiseHistorySegment(segment: String): String =
  segment
    .map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".-_") it else '-' }
    .joinToString("")

/**
 * The resolved value to forward as the daemon JVM's `composeai.svg.embedFonts`, so a
 * `-Dcomposeai.svg.embedFonts=…` on the Gradle invocation reaches the daemon that reads it. Sourced
 * from that system property first (the documented flag, matching the desktop render), then a
 * `-PcomposePreview.svgEmbedFonts` Gradle property, else `"true"` — font embedding is on by default
 * (it degrades to `sans-serif` offline, so it only ever improves the export), and opting out means
 * passing `false` explicitly. Provider-based so the configuration cache records the property reads
 * as inputs.
 */
internal fun composeAiSvgEmbedFonts(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.svg.embedFonts")
    .orElse(project.providers.gradleProperty("composePreview.svgEmbedFonts"))
    .orElse("true")

/**
 * The resolved value to forward as the daemon JVM's `composeai.svg.background`, so a
 * `-Dcomposeai.svg.background=device` on the Gradle invocation reaches the daemon that reads it —
 * without this the setting is unreachable through Gradle, since the property is consulted in the
 * spawned daemon rather than the parent. Sourced from that system property first (the documented
 * flag), then a `-PcomposePreview.svgBackground` Gradle property, else `"false"` (= `none`) — the
 * layered SVG exports background-free so an import lands as editable layers rather than sitting on
 * an opaque rect, and asking for a fill back means naming the mode you want. Mirrors
 * [composeAiSvgEmbedFonts].
 */
internal fun composeAiSvgBackground(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.svg.background")
    .orElse(project.providers.gradleProperty("composePreview.svgBackground"))
    .orElse("false")

/**
 * The resolved value to forward as the render / daemon JVM's `composeai.fonts.failOnFallback`, so a
 * `-Dcomposeai.fonts.failOnFallback=…` (or `-PcomposePreview.fontsFailOnFallback=…`) on the Gradle
 * invocation reaches the forked JVM that actually reads it. Sourced from the system property first
 * (the documented flag, matching the renderer), then the Gradle property, else `"true"` — a
 * downloadable font that falls back to Roboto fails its preview by default; opting out (warn + keep
 * the PNG) means passing `false` explicitly. Mirrors [composeAiSvgEmbedFonts].
 */
internal fun composeAiFontsFailOnFallback(
  project: Project
): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.fonts.failOnFallback")
    .orElse(project.providers.gradleProperty("composePreview.fontsFailOnFallback"))
    .orElse("true")

/**
 * The resolved value to forward as the render / daemon JVM's `composeai.render.hostTheme` — the
 * Android theme the preview host activity runs under (see
 * `ee.schimke.composeai.renderer.PreviewHostTheme`). Accepts `@style/Theme.Foo`,
 * `com.example:style/Theme.Foo`, or a bare `Theme.Foo`.
 *
 * Sourced from `-Dcomposeai.render.hostTheme` first (the documented flag, matching the renderer),
 * then `-PcomposePreview.hostTheme`, then the module's `composePreview.hostTheme` DSL value (the
 * durable declaration — the two command-line forms are per-run overrides), else empty — an
 * **application** module already inherits `<application android:theme>` with no configuration at
 * all, so the default has to be "don't override". A **library** module has no application theme to
 * inherit, which is where naming one turns an `AndroidView` preview that resolves app-owned
 * `?attr/…` from a hard render failure into a rendered PNG. Mirrors [composeAiSvgEmbedFonts] /
 * [composeAiFontsFailOnFallback].
 */
internal fun composeAiHostTheme(
  project: Project,
  extension: PreviewExtension? = null,
): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.render.hostTheme")
    .orElse(project.providers.gradleProperty("composePreview.hostTheme"))
    .let { if (extension != null) it.orElse(extension.hostTheme) else it }
    .orElse("")

/**
 * The resolved value to forward as the render / daemon JVM's `composeai.render.fixedTime` — the
 * instant preview renders pin their wall clock to (see
 * `ee.schimke.composeai.renderer.PreviewClock`). Accepts `HH:mm`, an ISO-8601 local date-time,
 * epoch millis, or `off`.
 *
 * Sourced from `-Dcomposeai.render.fixedTime` first (the documented flag, matching the renderer),
 * then `-PcomposePreview.fixedTime`, then the module's `composePreview.fixedTime` DSL value (the
 * durable declaration — the two command-line forms are per-run overrides), else empty, which the
 * renderer reads as "pin the default `10:10`". Forwarded even when blank so the property's presence
 * isn't a second thing to keep in sync. Mirrors [composeAiHostTheme].
 */
internal fun composeAiFixedTime(
  project: Project,
  extension: PreviewExtension? = null,
): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.render.fixedTime")
    .orElse(project.providers.gradleProperty("composePreview.fixedTime"))
    .let { if (extension != null) it.orElse(extension.fixedTime) else it }
    .orElse("")

/**
 * The resolved value to forward as the render / daemon JVM's `composeai.render.linkBufferComposer`
 * — whether the render opts into the Compose runtime's rewritten `SlotTable` (see
 * `ee.schimke.composeai.data.render.LinkBufferComposer`). `"true"` or `"false"`.
 *
 * Sourced from `-Dcomposeai.render.linkBufferComposer` first (the documented flag, matching the
 * renderer), then `-PcomposePreview.linkBufferComposer`, then the module's
 * `composePreview.linkBufferComposer` DSL value (the durable declaration — the two command-line
 * forms are per-run overrides), else `"false"`: an opt-in stays opt-in. Mirrors
 * [composeAiFixedTime] / [composeAiHostTheme], except that this one **is** forwarded to the Desktop
 * lane as well — the flag lives in the Compose runtime both backends share, so unlike the
 * Robolectric-shadowed clock there is nothing platform-specific for it to depend on.
 */
internal fun composeAiLinkBufferComposer(
  project: Project,
  extension: PreviewExtension? = null,
): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.render.linkBufferComposer")
    .orElse(project.providers.gradleProperty("composePreview.linkBufferComposer"))
    .let { provider ->
      if (extension != null) provider.orElse(extension.linkBufferComposer.map { it.toString() })
      else provider
    }
    .orElse("false")
