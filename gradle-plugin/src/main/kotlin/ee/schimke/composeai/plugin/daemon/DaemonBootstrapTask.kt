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
 * Emits `build/compose-previews/daemon-launch.json`, the spawn descriptor tools use to launch the
 * preview daemon JVM directly (`java @args`), bypassing Gradle on the per-save hot path.
 *
 * Only Gradle can resolve the renderer's classpath, JVM args and system properties; they come from
 * [ee.schimke.composeai.plugin.AndroidPreviewClasspath], the same helpers `composePreviewRender`
 * uses. Cacheable because the output is derived purely from declared inputs; classpath *paths*, not
 * contents, are the input, so source edits reuse the descriptor and the daemon reloads classes
 * itself.
 */
@CacheableTask
abstract class DaemonBootstrapTask : DefaultTask() {

  /**
   * An `@Input` so a schema bump invalidates the task; otherwise a stale descriptor survives an
   * upgrade, the reader rejects it, and re-running the task is UP-TO-DATE forever.
   */
  @get:Input
  val schemaVersion: Int
    get() = DAEMON_DESCRIPTOR_SCHEMA_VERSION

  /** `:samples:android` — the Gradle path of the consumer module. */
  @get:Input abstract val modulePath: Property<String>

  /** AGP variant name, e.g. `debug`. */
  @get:Input abstract val variant: Property<String>

  /**
   * Mirror of [DaemonExtension.enabled]; the descriptor is written either way. Not named `enabled`,
   * which would collide with `Task.getEnabled()`.
   */
  @get:Input abstract val daemonEnabled: Property<Boolean>

  /** Mirror of [DaemonExtension.maxHeapMb]. Translates to `-Xmx${value}m`. */
  @get:Input abstract val maxHeapMb: Property<Int>

  /** Mirror of [DaemonExtension.maxRendersPerSandbox]. Baked into `composeai.daemon.*`. */
  @get:Input abstract val maxRendersPerSandbox: Property<Int>

  /** Mirror of [DaemonExtension.warmSpare]. Baked into `composeai.daemon.warmSpare`. */
  @get:Input abstract val warmSpare: Property<Boolean>

  /** Mirror of [DaemonExtension.backgroundSandboxBoot]. */
  @get:Input abstract val backgroundSandboxBoot: Property<Boolean>

  /** Fully-qualified daemon entry point, by convention `ee.schimke.composeai.daemon.DaemonMain`. */
  @get:Input abstract val mainClass: Property<String>

  /** The `java` binary AGP wired into the unit-test task, when it exposed one. */
  @get:Input @get:Optional abstract val javaLauncher: Property<String>

  /** The daemon's runtime classpath in load order; only its paths are fingerprinted. */
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
   * Tracked so rewriting the manifest gives the descriptor a new mtime, which tools watch to
   * respawn a daemon warmed before the first discover. Optional because it doesn't exist on the
   * first warm.
   */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val previewsManifest: RegularFileProperty

  // --- Stage-2 in-process compile (BTA) ---------------------------------------------------------
  //
  // `btaCompile` is emitted only when [btaImplClasspath], [btaModuleName], [btaOutputDir] and
  // [btaIcWorkingDir] are all present. The daemon loads BTA lazily, so this costs no memory unless
  // the editor opts in.

  /**
   * `kotlin-build-tools-impl` + matching `kotlin-compiler-embeddable` and runtime JARs, at the
   * consumer's Kotlin version. Empty when no variant wiring populated it.
   */
  @get:Internal abstract val btaImplClasspath: ConfigurableFileCollection

  @get:Input
  val btaImplClasspathPaths: List<String>
    get() = btaImplClasspath.files.map { it.absolutePath }

  /** The module's compile classpath, as `compileKotlin` sees it. */
  @get:Internal abstract val btaCompileClasspath: ConfigurableFileCollection

  @get:Input
  val btaCompileClasspathPaths: List<String>
    get() = btaCompileClasspath.files.map { it.absolutePath }

  /** Compiler plugin JARs (e.g. `kotlin-compose-compiler-plugin-embeddable`). */
  @get:Internal abstract val btaCompilerPluginClasspath: ConfigurableFileCollection

  @get:Input
  val btaCompilerPluginClasspathPaths: List<String>
    get() = btaCompilerPluginClasspath.files.map { it.absolutePath }

  /**
   * Kotlin `MODULE_NAME` for BTA's classes; must match Gradle's own `compileKotlin` so the daemon's
   * hot-swap diff compares like with like.
   */
  @get:Input @get:Optional abstract val btaModuleName: Property<String>

  /** Where BTA writes `.class` files: the directory the daemon's child classloader watches. */
  @get:Input @get:Optional abstract val btaOutputDir: Property<String>

  /** Persistent IC cache, conventionally `build/compose-previews/daemon-state/bta-ic/`. */
  @get:Input @get:Optional abstract val btaIcWorkingDir: Property<String>

  /**
   * Set when KSP / KAPT / annotation processing makes in-process compile ineligible; the daemon
   * then answers `result=fallback` to every `compileSources`.
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
        schemaVersion = schemaVersion,
        modulePath = modulePath.get(),
        variant = variant.get(),
        enabled = daemonEnabled.get(),
        mainClass = mainClass.get(),
        javaLauncher = javaLauncher.orNull,
        classpath = classpathPaths,
        jvmArgs = jvmArgs.get().toList(),
        // Stable order keeps golden-output comparisons simple.
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
   * The [BtaCompileConfig], or null when any required field is missing; the daemon then falls back
   * from in-process compile.
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
    /** Pretty-printed with explicit nulls: the descriptor is a debug surface people `cat`. */
    val JSON: Json = Json {
      prettyPrint = true
      encodeDefaults = true
      explicitNulls = true
    }
  }
}
