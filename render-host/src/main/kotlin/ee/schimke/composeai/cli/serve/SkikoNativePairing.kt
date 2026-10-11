package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import java.io.File

/**
 * Keeps a served bundle's Skiko bindings and the `libskiko` native they call at one version, by
 * resolving the platform runtime artifact the bundle didn't record.
 *
 * Skiko ships bindings (`skiko-awt`) and one `skiko-awt-runtime-<os>-<arch>` per platform. Gradle
 * pairs them via `strictly` constraints, but the native arrives transitively and isn't in the
 * bundle's recorded coordinates. [ServeBundleDaemon] promotes the bundle's bindings ahead of the
 * sidecar, so they link against the sidecar's older native and every render dies with
 * `UnsatisfiedLinkError`, latching [RenderCircuitBreaker].
 *
 * Rule: when the coordinates name bindings at version V with no host runtime at V, synthesize that
 * coordinate for [ee.schimke.composeai.bundle.coordinates.CoordinateResolver] to fetch (the same
 * invariant [ee.schimke.composeai.plugin.ValidateComposePreviewClasspathTask] enforces in Gradle).
 * Bundles that already record the pair, or no Skiko at all, are untouched.
 */
internal object SkikoNativePairing {

  const val GROUP: String = "org.jetbrains.skiko"

  /** Artifacts that carry `org.jetbrains.skia.*` bindings but no platform library. */
  private val BINDINGS_ARTIFACTS = setOf("skiko", "skiko-awt")

  private const val RUNTIME_PREFIX = "skiko-awt-runtime-"

  /**
   * The `skiko-awt-runtime-<os>-<arch>` artifact for this host, or null where Skiko publishes none.
   * Targets: `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`,
   * `windows-arm64`.
   */
  fun hostRuntimeArtifact(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
  ): String? {
    val os =
      osName.lowercase().let {
        when {
          it.contains("mac") || it.contains("darwin") -> "macos"
          it.contains("win") -> "windows"
          it.contains("linux") -> "linux"
          else -> return null
        }
      }
    val arch =
      when (osArch.lowercase()) {
        "aarch64",
        "arm64" -> "arm64"
        "x86_64",
        "amd64",
        "x64" -> "x64"
        else -> return null
      }
    return "$RUNTIME_PREFIX$os-$arch"
  }

  /**
   * The host Skiko native missing from [coords], or null (no bindings, native already present, or
   * unsupported platform). The synthesized coordinate has no `sha256` (never recorded), which the
   * resolver treats as unverifiable; the version comes from the recorded bindings.
   */
  fun missingHostRuntime(
    coords: List<BundleReader.ClasspathEntry.Maven>,
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
  ): BundleReader.ClasspathEntry.Maven? {
    val bindings =
      coords.firstOrNull { it.group == GROUP && it.artifact in BINDINGS_ARTIFACTS } ?: return null
    val hostArtifact = hostRuntimeArtifact(osName, osArch) ?: return null
    val carried = coords.any {
      it.group == GROUP && it.artifact == hostArtifact && it.version == bindings.version
    }
    if (carried) return null
    return BundleReader.ClasspathEntry.Maven(
      group = GROUP,
      artifact = hostArtifact,
      version = bindings.version,
      type = "jar",
      sha256 = null,
    )
  }

  /**
   * The Skiko skew the assembled daemon classpath will actually load, or null when coherent — a
   * backstop for causes the repair can't fix (offline, no published native, wrong-platform native).
   *
   * Classpath order decides, since both halves are classloader lookups: compare the first bindings
   * and the first native on the `-cp`, not the set of versions (a fully shadowed matched pair is
   * harmless). Read from filenames, matching
   * [ee.schimke.composeai.plugin.ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath].
   */
  fun classpathSkew(orderedClasspath: List<String>): String? {
    fun firstVersion(nativeJar: Boolean): String? = orderedClasspath.firstNotNullOfOrNull { path ->
      val filename = path.replace('\\', '/').substringAfterLast('/')
      val version =
        SKIKO_ARTIFACT.matchEntire(filename)?.groupValues?.get(1)
          ?: return@firstNotNullOfOrNull null
      val isNative = filename.startsWith(RUNTIME_PREFIX)
      if (isNative == nativeJar) version else null
    }
    val bindings = firstVersion(nativeJar = false) ?: return null
    val native = firstVersion(nativeJar = true) ?: return null
    if (bindings == native) return null
    return "Skiko bindings $bindings will link against libskiko $native — the daemon classpath " +
      "resolves them from different artifacts, and every render that touches a symbol the two do " +
      "not share will fail with UnsatisfiedLinkError. Republish the catalog against a Compose " +
      "Multiplatform version whose Skiko this server ships."
  }

  /**
   * [classpathSkew] for the daemon launched from [descriptorPath], appended to a fatal linkage
   * failure; null when the failure isn't Skia's, the descriptor is unreadable, or the classpath is
   * coherent. The breaker reason is the only diagnosis outsiders see (every `409` body), so the
   * skew belongs there. Read at trip time so healthy hosts pay nothing.
   */
  fun linkageDiagnosis(reason: String, descriptorPath: File): String? {
    if (SKIA_PACKAGE !in reason) return null
    val descriptor = ServeBundleDaemon.readLaunchDescriptor(descriptorPath) ?: return null
    return classpathSkew(descriptor.classpath)
  }

  /** Matches both `org.jetbrains.skia.*` (bindings) and `org.jetbrains.skiko.*` (loader). */
  private const val SKIA_PACKAGE = "org.jetbrains.ski"

  /** `skiko`, `skiko-awt`, `skiko-awt-runtime-<platform>` — anything but the version suffix. */
  private val SKIKO_ARTIFACT = Regex("""^skiko(?:-[a-z0-9]+)*-(\d[\w.\-]*)\.jar$""")

  /** Log line naming the added coordinate and why. */
  fun repairLog(coordinate: BundleReader.ClasspathEntry.Maven): String =
    "bundle carries Skiko bindings ${coordinate.version} with no native runtime for this host — " +
      "adding ${coordinate.group}:${coordinate.artifact}:${coordinate.version} so the bindings " +
      "and libskiko match (an unpaired bindings jar links against the server's own older " +
      "libskiko and fails every render with UnsatisfiedLinkError)"
}
