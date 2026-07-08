package ee.schimke.composeai.plugin.daemon

import ee.schimke.composeai.daemonlaunch.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Emits `build/compose-previews/daemon-launch.json` — the spawn-descriptor the VS Code extension
 * consumes (via `daemonProcess.ts`, Stream C / task C1.2) to launch the preview daemon JVM
 * directly, bypassing Gradle on the per-save hot path.
 *
 * **What this task is for.** Gradle is the only place that can resolve the exact classpath, JVM
 * args, and system properties the renderer needs (AGP variant configurations, Robolectric
 * properties dir, the boot classpath, the AGP unit-test task's `javaLauncher`, etc.). The daemon
 * itself can't compute these because by the time it runs Gradle has long exited. This task captures
 * the resolved values into a JSON descriptor, so the daemon can be re-launched as `java @args` for
 * the lifetime of the descriptor (which is invalidated when any of its inputs — most notably the
 * classpath — changes).
 *
 * **Where the inputs come from.** The classpath FileCollection / JVM args / system properties are
 * produced by [ee.schimke.composeai.plugin.AndroidPreviewClasspath] — the same helpers the existing
 * `composePreviewRender` task uses. That guarantees the daemon JVM is byte-for-byte equivalent to a
 * `composePreviewRender` JVM, modulo the daemon-specific entries documented below.
 *
 * **Pending Stream B integration.** The daemon's own renderer JAR (`daemon/android`, Phase 1 task
 * B1.1) is NOT yet on disk in this worktree. When it lands, `registerAndroidTasks` should prepend
 * that configuration's resolved files to [classpath] so [DaemonClasspathDescriptor.mainClass]
 * (`ee.schimke.composeai.daemon.DaemonMain`) is loadable by the launched JVM. Until then, the
 * descriptor's [DaemonClasspathDescriptor.enabled] field defaults to `false` and the VS Code
 * extension MUST refuse to launch.
 *
 * **Caching.** `@CacheableTask` because the only output is a small JSON derivable from declared
 * inputs — the entire body is deterministic. The descriptor serializes classpath paths, not class
 * contents, so the classpath FileCollection itself is internal and [classpathPaths] is the declared
 * input. Preview source edits can then reuse the launch descriptor while the daemon reloads changed
 * classes through its own classloader path.
 */
@CacheableTask
abstract class DaemonBootstrapTask : DefaultTask() {

  /** `:samples:android` — the Gradle path of the consumer module. */
  @get:Input abstract val modulePath: Property<String>

  /** AGP variant name, e.g. `debug`. */
  @get:Input abstract val variant: Property<String>

  /**
   * Mirror of [DaemonExtension.enabled]. When `false`, [outputFile] is still written (so VS Code
   * can sniff the descriptor) but its `enabled: false` flag tells the extension not to spawn the
   * JVM.
   *
   * Named `daemonEnabled` rather than `enabled` to avoid colliding with `Task.enabled` — Gradle's
   * class generator rejects abstract `getEnabled()` accessors on subclasses because the parent
   * already declares one.
   */
  @get:Input abstract val daemonEnabled: Property<Boolean>

  /** Mirror of [DaemonExtension.maxHeapMb]. Translates to `-Xmx${value}m`. */
  @get:Input abstract val maxHeapMb: Property<Int>

  /**
   * Mirror of [DaemonExtension.maxRendersPerSandbox]. Baked into a
   * `composeai.daemon.maxRendersPerSandbox` system property the daemon reads at startup.
   */
  @get:Input abstract val maxRendersPerSandbox: Property<Int>

  /** Mirror of [DaemonExtension.warmSpare]. Baked into `composeai.daemon.warmSpare`. */
  @get:Input abstract val warmSpare: Property<Boolean>

  /**
   * Fully-qualified daemon entry point class. Convention is
   * `ee.schimke.composeai.daemon.DaemonMain` (Stream B / task B1.1 will provide the
   * implementation). Surfaced as a [Property] so future variants (foreground / debug / shadow
   * daemon) can plug in different entry points without forking the descriptor schema.
   */
  @get:Input abstract val mainClass: Property<String>

  /**
   * Absolute path to the `java` binary AGP wired into the consumer's unit-test task. Optional —
   * when AGP didn't expose one (rare; nearly every Android setup ships with a default toolchain),
   * VS Code falls back to its own detection.
   */
  @get:Input @get:Optional abstract val javaLauncher: Property<String>

  /**
   * The full daemon test-runtime classpath, in load order, derived from
   * [ee.schimke.composeai.plugin.AndroidPreviewClasspath.buildTestClasspath]
   * + AGP unit-test additions. The descriptor only needs paths, so contents are intentionally not
   *   part of the task fingerprint.
   */
  @get:Internal abstract val classpath: ConfigurableFileCollection

  @get:Input
  val classpathPaths: List<String>
    get() = classpath.files.map { it.absolutePath }

  /** Static JVM open flags (`--add-opens=...`) plus the `-Xmx` derived from [maxHeapMb]. */
  @get:Input abstract val jvmArgs: org.gradle.api.provider.ListProperty<String>

  /**
   * `-D` system properties built from
   * [ee.schimke.composeai.plugin.AndroidPreviewClasspath.buildSystemProperties] plus the
   * `composeai.daemon.*` values derived from [DaemonExtension].
   */
  @get:Input abstract val systemProperties: MapProperty<String, String>

  /** Working directory for the daemon JVM (consumer module's project dir). */
  @get:Input abstract val workingDirectory: Property<String>

  /** Absolute path to `previews.json`. */
  @get:Input abstract val manifestPath: Property<String>

  /**
   * The `previews.json` file itself, tracked as an `@Optional @InputFile` so Gradle invalidates the
   * launch descriptor when `composePreviewDiscover` writes (or rewrites) the manifest. The
   * descriptor's [outputFile] gets a new mtime on each invalidation, which the VS Code extension
   * observes to dispose + re-spawn an alive daemon — closing the "fresh module, daemon warmed
   * before first discover" gap where the daemon was stuck in the no-router fallback for the rest of
   * the session (see DaemonMain's `manifestFile.isFile` check). `@Optional` because the file does
   * not exist on the very first warm; `RELATIVE` path sensitivity because the absolute path is
   * already encoded in [manifestPath], so only the *content* of the manifest matters for
   * invalidation here.
   */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val previewsManifest: RegularFileProperty

  // --- Stage-2 in-process compile -----------------------------------------------------------
  //
  // The descriptor's `btaCompile` field is populated when [btaImplClasspath] is non-empty
  // (i.e. the plugin's variant wiring actually resolved the BTA implementation JARs) and
  // [btaModuleName] / [btaOutputDir] / [btaIcWorkingDir] are present. The daemon JVM lazily
  // loads BTA only when the editor's save loop calls `compileSources` (gated by the VS Code
  // workspace setting `composePreview.daemon.compileInProcess`), so populating the descriptor
  // unconditionally costs only the on-disk classpath resolution at config time — the daemon
  // pays no resident-memory tax unless the editor actually opts in.

  /**
   * BTA implementation classpath: `kotlin-build-tools-impl` + matching
   * `kotlin-compiler-embeddable` + transitive runtime JARs. Resolved by the gradle plugin from
   * `org.jetbrains.kotlin:kotlin-build-tools-impl:${kotlinVersion}` where `kotlinVersion` is
   * sniffed from the consumer's applied Kotlin plugin. Empty when no variant wiring populated it
   * (the common case until stage 3b lands the resolution).
   */
  @get:Internal abstract val btaImplClasspath: ConfigurableFileCollection

  @get:Input
  val btaImplClasspathPaths: List<String>
    get() = btaImplClasspath.files.map { it.absolutePath }

  /**
   * Consumer's compile classpath for this module — same JAR list `compileKotlin` would see. Empty
   * when no variant wiring populated it.
   */
  @get:Internal abstract val btaCompileClasspath: ConfigurableFileCollection

  @get:Input
  val btaCompileClasspathPaths: List<String>
    get() = btaCompileClasspath.files.map { it.absolutePath }

  /**
   * Compiler plugin JARs (e.g. `kotlin-compose-compiler-plugin-embeddable`). Empty when the
   * consumer doesn't apply Compose, or when stage-2 wiring hasn't populated it yet.
   */
  @get:Internal abstract val btaCompilerPluginClasspath: ConfigurableFileCollection

  @get:Input
  val btaCompilerPluginClasspathPaths: List<String>
    get() = btaCompilerPluginClasspath.files.map { it.absolutePath }

  /**
   * Kotlin `MODULE_NAME` for BTA's emitted classes. Matches the consumer's Gradle module name so
   * `kotlin.Metadata.d2[]` agrees with what Gradle's own `compileKotlin` produces — load-bearing
   * for the daemon's child classloader hot-swap, which diffs BTA-emitted classes against
   * Gradle-emitted ones.
   */
  @get:Input @get:Optional abstract val btaModuleName: Property<String>

  /**
   * Where BTA writes `.class` files. Same directory the daemon's child classloader watches —
   * typically `build/intermediates/built_in_kotlinc/<variant>/classes/` (Android) or
   * `build/classes/kotlin/<variant>/main/` (JVM/CMP).
   */
  @get:Input @get:Optional abstract val btaOutputDir: Property<String>

  /**
   * Per-module persistent IC cache directory. Conventionally
   * `<module>/build/compose-previews/daemon-state/bta-ic/`.
   */
  @get:Input @get:Optional abstract val btaIcWorkingDir: Property<String>

  /**
   * Daemon-warm-time KSP/KAPT/annotationProcessor detection: when this property is set, the
   * descriptor's `btaCompile.ineligibilityReason` carries the value verbatim and the daemon returns
   * `result=fallback` for every `compileSources` call. The other BTA inputs are still populated
   * honestly (the daemon validates them at startup even when ineligible) — a future flag flip can
   * then unblock the path without a re-bootstrap.
   */
  @get:Input @get:Optional abstract val btaIneligibilityReason: Property<String>

  // -------------------------------------------------------------------------------------------

  /** `<module>/build/compose-previews/daemon-launch.json`. */
  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "compose preview"
    description =
      "Emit build/compose-previews/daemon-launch.json so VS Code can spawn the preview daemon JVM"
    dependsOn(classpath)
  }

  @TaskAction
  fun emit() {
    val descriptor =
      DaemonClasspathDescriptor(
        schemaVersion = DAEMON_DESCRIPTOR_SCHEMA_VERSION,
        modulePath = modulePath.get(),
        variant = variant.get(),
        enabled = daemonEnabled.get(),
        mainClass = mainClass.get(),
        javaLauncher = javaLauncher.orNull,
        // Stable ordering: FileCollection iteration is deterministic for a
        // configured collection (Gradle preserves the insertion order of the
        // sources). Filter to existing files so dropouts (a `from(...)` that
        // resolves to a non-existent dir on this OS / variant) don't bake
        // missing paths into the descriptor.
        classpath = classpathPaths,
        jvmArgs = jvmArgs.get().toList(),
        // LinkedHashMap preserves the buildSystemProperties iteration order,
        // which matches the composePreviewRender task's systemProperty(...) call
        // order. Order is irrelevant to the receiving JVM but stable order
        // simplifies golden-output comparisons in tests.
        systemProperties = LinkedHashMap(systemProperties.get()),
        workingDirectory = workingDirectory.get(),
        manifestPath = manifestPath.get(),
        btaCompile = assembleBtaCompileConfig(),
      )

    val out = outputFile.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(JSON.encodeToString(descriptor))
  }

  /**
   * Returns a populated [BtaCompileConfig] when stage-2 wiring is complete for this variant, `null`
   * otherwise. Every required field must be set, else we emit `null` and the daemon's
   * `compileSources` handler returns `result=fallback` so the editor falls back to stage 1 / 0.
   */
  private fun assembleBtaCompileConfig(): BtaCompileConfig? {
    val implCp = btaImplClasspathPaths
    val moduleName = btaModuleName.orNull
    val outputDir = btaOutputDir.orNull
    val icDir = btaIcWorkingDir.orNull
    if (implCp.isEmpty() || moduleName == null || outputDir == null || icDir == null) return null
    return BtaCompileConfig(
      implClasspath = implCp,
      compileClasspath = btaCompileClasspathPaths,
      compilerPlugins = btaCompilerPluginClasspathPaths,
      outputDir = outputDir,
      moduleName = moduleName,
      icWorkingDir = icDir,
      ineligibilityReason = btaIneligibilityReason.orNull,
    )
  }

  internal companion object {
    /**
     * Pretty-printed JSON so the descriptor is reviewable by humans (it's a debug surface — devs
     * `cat` it when the daemon misbehaves). Encoding defaults so optional fields like
     * `javaLauncher` render as `null` rather than being omitted; the VS Code reader treats both
     * equivalently but explicit-null reduces "is the field missing or null?" confusion.
     */
    val JSON: Json = Json {
      prettyPrint = true
      encodeDefaults = true
      explicitNulls = true
    }
  }
}
