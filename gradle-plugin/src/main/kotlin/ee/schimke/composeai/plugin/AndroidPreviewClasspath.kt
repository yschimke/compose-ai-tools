package ee.schimke.composeai.plugin

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Pure-data builders for the renderer test JVM's classpath, JVM args and system properties, shared
 * by the render Test task and the preview daemon (see `docs/daemon/DESIGN.md`). The Test task still
 * appends AGP's late-resolved unit-test classes and registers the dynamic argument providers.
 *
 * Ordering invariants:
 * - Robolectric properties dir before consumer test resources, so our `robolectric.properties`
 *   wins.
 * - Renderer artifacts before the consumer's remaining entries, so the renderer's pinned versions
 *   win.
 * - SDK boot classpath last; it only satisfies JUnit's introspection (the sandbox has its own
 *   `android-all`).
 *
 * Every module artifact comes from `rendererConfig`, which extends `testConfig` and resolves one
 * version per module. Keeping the classpath duplicate-free (see [buildAgpClasspathExtras],
 * [RenderClasspathDuplicates]) is the primary mechanism; ordering is the safety net.
 */
internal object AndroidPreviewClasspath {

  private val artifactType: Attribute<String> = Attribute.of("artifactType", String::class.java)

  /**
   * The renderer test classpath, excluding AGP's test classes / classpath, which the Test task
   * appends once AGP's task exists.
   */
  fun buildTestClasspath(
    project: Project,
    bootClasspath: Provider<List<RegularFile>>,
    bootClasspathFallback: Provider<List<File>>,
    rendererConfig: Configuration,
    rendererClasspathEntries: FileCollection,
    sourceClassDirs: FileCollection,
    testConfig: Configuration?,
    screenshotTestRuntimeConfig: Configuration?,
    /**
     * AGP's `test_config.properties` directory, or null without a host-test component (KMP-Android
     * default); `from(null)` would throw.
     */
    unitTestConfigDir: Provider<Directory>?,
    robolectricPropertiesDir: Provider<Directory>,
    legacyClasspathUnion: Boolean = false,
  ): FileCollection =
    project.files().apply {
      // Before consumer test resources so our Application override wins `getResource`.
      from(robolectricPropertiesDir)
      from(rendererConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
      // `android-classes` alongside `jar` so AAR modules contribute their `classes.jar`; sourced
      // from `rendererConfig` so all module artifacts come from one resolution.
      from(
        rendererConfig.incoming
          .artifactView { attributes.attribute(artifactType, "android-classes") }
          .files
      )
      // Directories or jars only: a `zipTree` would add each `.class` as an invalid classpath
      // element (#5562).
      from(rendererClasspathEntries)
      // `rendererConfig` already extends `testConfig`, resolving one graph. Re-adding
      // `testConfig`'s view is a separate resolution that puts upgraded modules on the classpath
      // twice at two versions — e.g. two `bcprov` versions failing every a11y preview with
      // `NoSuchFieldError` (homeassistant-remotecompose#495). AGP-only non-module entries are kept
      // via [buildAgpClasspathExtras]. `-PcomposePreview.legacyClasspathUnion=true` restores the
      // old concatenation.
      if (testConfig != null && legacyClasspathUnion) {
        from(testConfig.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)
        from(
          testConfig.incoming
            .artifactView { attributes.attribute(artifactType, "android-classes") }
            .files
        )
      }
      // screenshotTest runtime deps are already in the single graph (`rendererConfig` extends that
      // config); re-adding it is the legacy concatenation.
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
      unitTestConfigDir?.let { from(it) }
      // SDK stub android.jar on the outer classpath so JUnit can introspect the test class's
      // `android.*` signatures before the sandbox exists; inside the sandbox Robolectric uses its
      // own `android-all`. From AGP's SdkComponents, with a `local.properties` / `ANDROID_HOME`
      // fallback when that's empty (#1243).
      from(project.files(bootClasspath))
      from(project.files(bootClasspathFallback))
    }

  /**
   * AGP registers its unit-test task after onVariants, so an eagerly realized render task can't
   * find it. Populate a shared collection after evaluation instead, still at configuration time.
   */
  fun lateAgpClasspathExtras(
    project: Project,
    unitTestTaskName: String,
    testConfig: Configuration?,
    legacyClasspathUnion: Boolean,
  ): ConfigurableFileCollection {
    val extras = project.files()
    project.afterEvaluate {
      val agpTest = project.tasks.findByName(unitTestTaskName) as? Test
      if (agpTest != null) {
        extras.from(
          buildAgpClasspathExtras(project, agpTest.classpath, testConfig, legacyClasspathUnion)
        )
      }
    }
    return extras
  }

  /**
   * AGP's `test<Variant>UnitTest` classpath minus module artifacts: only entries found nowhere else
   * (the unit-test merged `R.jar`, generated class dirs). AGP's module artifacts come from an
   * independent resolution and would add older duplicates of anything the renderer graph upgraded
   * ([RenderClasspathDuplicates]).
   *
   * Subtracted by file identity against `testConfig`'s views, so on a version disagreement the
   * consumer copy is dropped. The views filter by component identity too, since a `jar` view also
   * returns raw file deps like the generated R.jar, which must be kept. Lazy via
   * `FileCollection.minus`.
   *
   * Returns [agpTestClasspath] unchanged without `testConfig` or in legacy-union mode.
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
        testConfig.incoming
          .artifactView {
            attributes.attribute(artifactType, "jar")
            componentFilter { it is ModuleComponentIdentifier || it is ProjectComponentIdentifier }
          }
          .files,
        testConfig.incoming
          .artifactView {
            attributes.attribute(artifactType, "android-classes")
            componentFilter { it is ModuleComponentIdentifier || it is ProjectComponentIdentifier }
          }
          .files,
      )
    return agpTestClasspath.minus(moduleArtifacts)
  }

  /**
   * Maps each resolved artifact file on the render classpath to its `group:name:version`, so
   * [RenderClasspathDuplicates] compares modules by identity. Uses the classpath's own `jar` /
   * `android-classes` views across [configurations] (the default view returns `.aar`s that never
   * appear). Project artifacts are keyed `project:<path>` with no version. A Provider, so nothing
   * resolves at configuration time.
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
            // Diagnostic input: degrade to a smaller map rather than fail the render.
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
   * Fallback `android.jar` when AGP's `bootClasspath` is empty (#1243): `sdk.dir` from
   * `local.properties`, else `ANDROID_HOME` / `ANDROID_SDK_ROOT`, picking the highest
   * `platforms/android-N/android.jar`. Any version works for the outer classpath. Empty when no SDK
   * is found; [validateApplicationOnClasspath] then explains.
   */
  fun buildBootClasspathFallback(project: Project): Provider<List<File>> {
    // `project.rootDir` is an IP-safe snapshot; `rootProject.layout` is rejected under isolated
    // projects (#1546).
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
   * Fails with a fixable message when no classpath entry defines `android/app/Application.class`,
   * instead of Robolectric's opaque `NoClassDefFoundError` (#1243). For a `doFirst` on the render
   * task.
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
  }
    .getOrDefault(false)

  /** Static JVM open flags for the render JVM. */
  fun buildJvmArgs(): List<String> =
    listOf(
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      // Robolectric's `ShadowVMRuntime` reflects into `DirectByteBuffer.address()` (reached via
      // Wear curved text).
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      // Robolectric's `FileDescriptorInterceptor` uses `SharedSecrets`; SDK 36 sandboxes hit it
      // during setup (#1328).
      "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )

  /**
   * Static system properties; the caller supplies the path-bearing / opt-in values. Dynamic
   * providers (tier etc.) stay on the task because they need lazy evaluation. Insertion order is
   * kept for stable output.
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
    rcPlayer: String = "androidx-embedded",
    rcDensity: String = "fixed",
  ): Map<String, String> =
    linkedMapOf(
      // Redundant with `robolectric.properties` (see `RobolectricRenderTestBase`), kept as an
      // independent channel.
      "robolectric.graphicsMode" to "NATIVE",
      "robolectric.looperMode" to "PAUSED",
      // Conscrypt isn't needed and its native library is flaky on some Linux sandboxes.
      "robolectric.conscryptMode" to "OFF",
      // The only PixelCopy path that replays Compose's RenderNodes correctly.
      "robolectric.pixelCopyRenderMode" to "hardware",
      // Roborazzi defaults to compare mode; record writes fresh PNGs every run.
      "roborazzi.test.record" to "true",
      "composeai.render.manifest" to manifestPath,
      "composeai.render.outputDir" to rendersDir,
      // Shared GoogleFont cache ([composeAiFontsCacheDir]); the renderer no-ops without it.
      "composeai.fonts.cacheDir" to fontsCacheDir,
      // Skip network on cache miss and render the fallback font.
      "composeai.fonts.offline" to fontsOffline,
      // The following are read in the render / daemon JVM, so they must be forwarded from the
      // Gradle invocation. Embed fonts in the figma-svg export (default on;
      // `-Dcomposeai.svg.embedFonts=false` to opt out).
      "composeai.svg.embedFonts" to svgEmbedFonts,
      // Default figma-svg background mode: `none` (default), `device`, `content-shape`, or
      // `full-bleed`; `true`/`false` alias `device`/`none`. A per-render override wins.
      "composeai.svg.background" to svgBackground,
      // Whether an unresolved downloadable font fails its preview (default) or only warns.
      "composeai.fonts.failOnFallback" to fontsFailOnFallback,
      // Host activity theme (e.g. `@style/Theme.Foo`), needed only by library modules. Forwarded
      // even when blank.
      "composeai.render.hostTheme" to hostTheme,
      // Pinned wall clock (`10:10` when blank, or `off`) (#3239).
      "composeai.render.fixedTime" to fixedTime,
      // Link-buffer composer opt-in; must be a launch property set before the first composition.
      "composeai.render.linkBufferComposer" to linkBufferComposer,
      // Remote Compose replay player: `androidx-embedded` (default) or `androidx-view`.
      "composeai.render.rcPlayer" to rcPlayer,
      // Remote Compose capture density: `fixed` (default, constants) or `host` (player variables).
      // Decides what is written, so no later request can change it.
      "composeai.render.rcDensity" to rcDensity,
    )
}

/**
 * `$XDG_CACHE_HOME/composeai/fonts` (if set), else `~/.cache/composeai/fonts`. Mirrors
 * `common/io`'s `composeAiCacheDir("fonts")`. User-level because fonts are identical across
 * projects. Read through [org.gradle.api.provider.ProviderFactory] so the configuration cache
 * tracks the inputs.
 */
internal fun composeAiFontsCacheDir(project: Project): String =
  File(composeAiCacheRoot(project), "fonts").absolutePath

/**
 * `$XDG_CACHE_HOME/composeai` (if set), else `~/.cache/composeai`, via
 * [org.gradle.api.provider.ProviderFactory].
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
 * This module's render-history archive:
 * `$XDG_CACHE_HOME/composeai/history/<workspaceSlug>/<moduleRel>`.
 *
 * **Mirrors `common/io`'s `composeAiHistoryDir` and the VS Code extension's
 * [`src/historyPaths.ts`](https://github.com/yschimke/compose-preview-vscode/blob/main/src/historyPaths.ts).**
 * All three must agree byte-for-byte (pinned by shared golden vectors in `HistoryPathsTest`,
 * `AndroidPreviewClasspathTest` and `historyPaths.test.ts`); a drift silently empties the history
 * drawer.
 *
 * An existing `<projectDir>/.compose-preview-history` still wins, so upgrades keep their timeline.
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
 * Byte-identical to `common/io`'s `sanitiseHistorySegmentInjectively`: rewritten segments carry an
 * 8-hex digest of the original, keeping `ui components` and `ui-components` distinct.
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
 * Value for `composeai.svg.embedFonts`: the system property, then `-PcomposePreview.svgEmbedFonts`,
 * else `"true"` (embedding only improves the export). Provider-based for the configuration cache.
 */
internal fun composeAiSvgEmbedFonts(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.svg.embedFonts")
    .orElse(project.providers.gradleProperty("composePreview.svgEmbedFonts"))
    .orElse("true")

/**
 * Value for `composeai.svg.background`: the system property, then `-PcomposePreview.svgBackground`,
 * else `"false"` (= `none`, so imports land as editable layers). Mirrors [composeAiSvgEmbedFonts].
 */
internal fun composeAiSvgBackground(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.svg.background")
    .orElse(project.providers.gradleProperty("composePreview.svgBackground"))
    .orElse("false")

/**
 * Value for `composeai.fonts.failOnFallback`: the system property, then
 * `-PcomposePreview.fontsFailOnFallback`, else `"true"`. Mirrors [composeAiSvgEmbedFonts].
 */
internal fun composeAiFontsFailOnFallback(
  project: Project
): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.fonts.failOnFallback")
    .orElse(project.providers.gradleProperty("composePreview.fontsFailOnFallback"))
    .orElse("true")

/**
 * Value for `composeai.render.hostTheme` (see `PreviewHostTheme`): `@style/Theme.Foo`,
 * `com.example:style/Theme.Foo`, or `Theme.Foo`. From the system property, then
 * `-PcomposePreview.hostTheme`, then the DSL value, else empty (application modules inherit their
 * theme; library modules need one for `AndroidView` previews).
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
 * Value for `composeai.render.fixedTime` (see `PreviewClock`): `HH:mm`, ISO-8601 local date-time,
 * epoch millis, or `off`. From the system property, then `-PcomposePreview.fixedTime`, then the DSL
 * value, else empty (the renderer pins `10:10`).
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
 * Value for `composeai.render.rcPlayer`: which player replays a Remote Compose capture —
 * `"androidx-embedded"` (default) or `"androidx-view"` (legacy `embedded`, `java`, `view` also
 * accepted; `cmp-android` only replays). Build-wide, read by `RemoteComposePlayerSelection` in the
 * composing JVM.
 *
 * The weakest tier: a per-preview pin or a per-render `renderNow.overrides.remoteCompose.player`
 * wins. From the system property, then `-PcomposePreview.rcPlayer`. Android-only. Defaults to
 * embedded because the View lane reports an unlabelled-player a11y error on every preview (#5259).
 */
internal fun composeAiRcPlayer(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.render.rcPlayer")
    .orElse(project.providers.gradleProperty("composePreview.rcPlayer"))
    .orElse("androidx-embedded")

/**
 * Value for `composeai.render.rcDensity`: whether Remote Compose captures write density and font
 * scale as constants (`"fixed"`, default) or player-variable references (`"host"`). Build-wide and
 * the only tier, since it decides the document bytes. From the system property, then
 * `-PcomposePreview.rcDensity`. Defaults to `fixed` because flipping it rewrites every captured
 * document. Android-only.
 */
internal fun composeAiRcDensity(project: Project): org.gradle.api.provider.Provider<String> =
  project.providers
    .systemProperty("composeai.render.rcDensity")
    .orElse(project.providers.gradleProperty("composePreview.rcDensity"))
    .orElse("fixed")

/**
 * Value for `composeai.render.linkBufferComposer` (`"true"`/`"false"`): the system property, then
 * `-PcomposePreview.linkBufferComposer`, then the DSL value, else `"false"`. Unlike
 * [composeAiFixedTime], also forwarded to Desktop, since the flag lives in the shared Compose
 * runtime.
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
