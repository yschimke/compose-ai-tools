package ee.schimke.composeai.plugin.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
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
 * `renderPreviews` task uses. That guarantees the daemon JVM is byte-for-byte equivalent to a
 * `renderPreviews` JVM, modulo the daemon-specific entries documented below.
 *
 * **Pending Stream B integration.** The daemon's own renderer JAR (`renderer-android-daemon`, Phase
 * 1 task B1.1) is NOT yet on disk in this worktree. When it lands, `registerAndroidTasks` should
 * prepend that configuration's resolved files to [classpath] so
 * [DaemonClasspathDescriptor.mainClass] (`ee.schimke.composeai.daemon.DaemonMain`) is loadable by
 * the launched JVM. Until then, the descriptor's [DaemonClasspathDescriptor.enabled] field defaults
 * to `false` and the VS Code extension MUST refuse to launch.
 *
 * **Caching.** `@CacheableTask` because the only output is a small JSON derivable from declared
 * inputs — the entire body is deterministic. The classpath is `@Classpath` (not `@InputFiles`) so a
 * re-ordered but equivalent classpath doesn't bust the cache. The path-bearing system properties go
 * through `@Input` because their values are absolute paths the daemon needs verbatim.
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
   * + AGP unit-test additions. `@Classpath` so re-ordering of equivalent entries doesn't bust the
   *   cache.
   */
  @get:Classpath abstract val classpath: ConfigurableFileCollection

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

  /** `<module>/build/compose-previews/daemon-launch.json`. */
  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "compose preview"
    description =
      "Emit build/compose-previews/daemon-launch.json so VS Code can spawn the preview daemon JVM"
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
        classpath = classpath.files.map { it.absolutePath },
        jvmArgs = jvmArgs.get().toList(),
        // LinkedHashMap preserves the buildSystemProperties iteration order,
        // which matches the renderPreviews task's systemProperty(...) call
        // order. Order is irrelevant to the receiving JVM but stable order
        // simplifies golden-output comparisons in tests.
        systemProperties = LinkedHashMap(systemProperties.get()),
        workingDirectory = workingDirectory.get(),
        manifestPath = manifestPath.get(),
      )

    val out = outputFile.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(JSON.encodeToString(descriptor))
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
