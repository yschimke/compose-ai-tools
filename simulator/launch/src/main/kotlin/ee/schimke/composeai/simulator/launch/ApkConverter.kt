package ee.schimke.composeai.simulator.launch

import java.io.File

/**
 * Runs `:simulator-host`'s `ApkClassConverter` (an APK's dex to JVM classes) in a JVM of its own,
 * as [ApkConversionCache.ensure]'s `convert` callback: the same converter, heap and retry for
 * `compose-preview android-simulator` and for a host that launches simulators itself
 * (`AppSimulatorLauncher` in `:render-host`).
 */
public object ApkConverter {
  public const val MAIN: String = "ee.schimke.composeai.simulator.ApkClassConverter"

  /** Jars whose classes the converter looks types up in after the APK's own. */
  public const val CONTEXT_PROPERTY: String = "composeai.simulator.converter.context"

  /** `ApkClassConverter.PROVIDED_PROPERTY`: jars whose classes the APK's copies must not shadow. */
  public const val PROVIDED_PROPERTY: String = "composeai.simulator.converter.provided"

  /**
   * The converter's heap, then the one it retries with if that runs out. It translates one class at
   * a time, so its live set is the dex files and a header per class: about 260 MB for Home
   * Assistant's 61,000 classes. Without a cap the JVM's default heap, a quarter of the machine,
   * lets G1 grow to twice that before it collects.
   */
  public val HEAPS: List<String> = listOf("1g", "3g")

  /** `-XX:+ExitOnOutOfMemoryError`'s exit status. */
  public const val OUT_OF_MEMORY_EXIT: Int = 3

  /**
   * The jars the converter runs from: `:simulator-core` (the converter itself), `:simulator-wear`
   * when present (the shared-library classes it drops from an APK), then dex2jar's.
   *
   * @throws IllegalStateException when [simulatorJars] has no `simulator-core` jar.
   */
  public fun converterJars(simulatorJars: List<File>, dex2jarJars: List<File>): List<File> {
    val core =
      simulatorJars.firstOrNull { it.name.startsWith("simulator-core") }
        ?: error("lib-simulator/ has no simulator-core jar")
    val wear = simulatorJars.firstOrNull {
      it.name.startsWith("simulator-wear.") || it.name.startsWith("simulator-wear-")
    }
    return listOfNotNull(core, wear) + dex2jarJars
  }

  /**
   * The sidecar jars carrying androidx.test classes: those are the simulator's, and the converter
   * drops an APK's copies of them (and only those) so they cannot shadow them
   * ([PROVIDED_PROPERTY]).
   */
  public fun providedJars(robolectricJars: List<File>): List<File> =
    robolectricJars.filter(::carriesAndroidxTest)

  /** Whether [jar] has androidx.test classes; remembered per jar ([FileFacts]). */
  public fun carriesAndroidxTest(jar: File): Boolean =
    FileFacts.remember(jar, "androidx-test-1") {
      runCatching {
          java.util.zip.ZipFile(jar).use { zip ->
            zip.entries().asSequence().any {
              it.name.startsWith("androidx/test/") && it.name.endsWith(".class")
            }
          }
        }
        .getOrDefault(false)
        .toString()
    } == "true"

  /** The converter JVM's command line. Pure, for tests. */
  public fun command(
    java: File,
    converterJars: List<File>,
    apk: File,
    out: File,
    androidJar: File,
    heap: String,
    previous: File? = null,
    context: List<File> = emptyList(),
    provided: List<File> = emptyList(),
  ): List<String> =
    listOfNotNull(
      java.path,
      "-Xmx$heap",
      context
        .takeIf { it.isNotEmpty() }
        ?.let { jars ->
          "-D$CONTEXT_PROPERTY=" + jars.joinToString(File.pathSeparator) { it.path }
        },
      provided
        .takeIf { it.isNotEmpty() }
        ?.let { jars ->
          "-D$PROVIDED_PROPERTY=" + jars.joinToString(File.pathSeparator) { it.path }
        },
      // A single-threaded batch job: the serial collector's footprint is the smallest, and it
      // spends no threads on concurrent marking.
      "-XX:+UseSerialGC",
      "-XX:+ExitOnOutOfMemoryError",
      "-cp",
      converterJars.joinToString(File.pathSeparator) { it.path },
      MAIN,
      apk.path,
      out.path,
      androidJar.path,
      previous?.path,
    )

  /**
   * Convert [apk] into [out] (and its index beside it), on [HEAPS] in turn: an exit on
   * `OutOfMemoryError` is retried once on the larger heap. Returns the converter's summary line.
   *
   * @param onRetry told the heap that ran out and the next one.
   * @throws IllegalStateException when the converter fails for any other reason.
   */
  public fun convert(
    java: File,
    converterJars: List<File>,
    apk: File,
    out: File,
    androidJar: File,
    previous: File? = null,
    context: List<File> = emptyList(),
    provided: List<File> = emptyList(),
    stderr: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
    onRetry: (ranOut: String, next: String) -> Unit = { _, _ -> },
  ): String {
    for ((attempt, heap) in HEAPS.withIndex()) {
      val process =
        ProcessBuilder(
            command(java, converterJars, apk, out, androidJar, heap, previous, context, provided),
          )
          .redirectError(stderr)
          .start()
      val summary = process.inputStream.bufferedReader().readText()
      val exit = process.waitFor()
      if (exit == 0) return summary.lineSequence().lastOrNull { it.startsWith("{") } ?: "{}"
      val next = HEAPS.getOrNull(attempt + 1)
      check(exit == OUT_OF_MEMORY_EXIT && next != null) { "the APK converter exited $exit" }
      // The converter's temporary jars, which an exit on OutOfMemoryError leaves behind.
      out.absoluteFile.parentFile
        .listFiles { f -> f.name.startsWith("dex2jar-") && f.name.endsWith(".jar") }
        .orEmpty()
        .forEach { it.delete() }
      onRetry(heap, next)
    }
    error("unreachable")
  }
}
