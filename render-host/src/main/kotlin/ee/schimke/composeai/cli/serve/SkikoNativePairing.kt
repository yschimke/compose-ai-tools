package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import java.io.File

/**
 * Keeps a served bundle's Skiko **bindings** and the `libskiko` **native** they call at one
 * version, by resolving the platform runtime artifact the bundle forgot to record.
 *
 * ## The failure this exists to prevent
 *
 * A packed bundle records its runtime classpath as Maven *coordinates*, and [ServeBundleDaemon]
 * promotes the Compose/Skiko ones ahead of the server's own daemon sidecar so the catalog's
 * framework versions win (see `shouldPrecedeDaemonSidecar`). Skiko splits across two artifacts: the
 * bindings (`skiko-awt`, carrying `org.jetbrains.skia.*` and its `external` declarations) and one
 * `skiko-awt-runtime-<os>-<arch>` per platform, carrying the actual `libskiko` for that host. The
 * bindings depend on all six with a `strictly` constraint, so Gradle resolves the pair together —
 * but a **constraint is not a classpath entry**, and the platform jar reaches the render classpath
 * as a transitive artifact the bundle's coordinate capture never wrote down.
 *
 * The result is a bundle that lists `org.jetbrains.skiko:skiko-awt:0.148.2` and no native at all.
 * Promoted first, its bindings win class resolution; the only `libskiko` on the classpath is the
 * sidecar's own (0.144.6 at the time of writing), so the JVM links 0.148 declarations against a
 * 0.144 library. Every render then dies with `UnsatisfiedLinkError: 'int
 * org.jetbrains.skia.paragraph.ParagraphKt._nGetUnresolvedCodepointsCount(long)'` — a symbol
 * 0.148.2 added and 0.144.6 never exported — which the [RenderCircuitBreaker] correctly classifies
 * as fatal and latches the whole catalog behind (compose-ai-tools#4220; the same split-Skiko shape
 * as #3447 and #1844, reached through the serve path rather than through Gradle conflict
 * resolution).
 *
 * ## The rule
 *
 * A bundle that promotes Skiko bindings must bring its **own** native. So when the recorded
 * coordinates name bindings at version V with no `skiko-awt-runtime-<host>` at V, synthesize that
 * coordinate and let [ee.schimke.composeai.bundle.coordinates.CoordinateResolver] fetch it like any
 * other — it is a published artifact on the same repository the bindings came from. Bindings and
 * native then travel together, exactly as they do on a Gradle-resolved render classpath
 * ([ee.schimke.composeai.plugin.ValidateComposePreviewClasspathTask] enforces the same invariant
 * there).
 *
 * Deliberately narrow: it fires only when the bundle carries bindings *and* no host native for
 * them. A bundle that already recorded the pair (or that records no Skiko at all, i.e. an Android
 * backend) is left exactly as it was.
 */
internal object SkikoNativePairing {

  const val GROUP: String = "org.jetbrains.skiko"

  /** Artifacts that carry `org.jetbrains.skia.*` bindings but no platform library. */
  private val BINDINGS_ARTIFACTS = setOf("skiko", "skiko-awt")

  private const val RUNTIME_PREFIX = "skiko-awt-runtime-"

  /**
   * The `skiko-awt-runtime-<os>-<arch>` artifact for this host, or null on a platform Skiko
   * publishes no native for (in which case the render was never going to work and there is nothing
   * useful to add to the classpath).
   *
   * The six published targets are `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`,
   * `windows-x64`, `windows-arm64` — see the `strictly` constraints on `skiko-awt`'s POM.
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
   * The Skiko native coordinate [coords] is missing for this host, or null when nothing is missing
   * — the bundle records no Skiko bindings, already records the matching native, or runs on a
   * platform with no published native.
   *
   * The synthesized coordinate carries **no `sha256`**: the bundle never recorded one for an
   * artifact it never listed, and the resolver treats a null hash as "unverifiable", which is the
   * honest description. It is still the right bytes — the version is taken from the bindings the
   * bundle *did* record, and Skiko publishes the pair together.
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
   * The Skiko skew the assembled daemon classpath will actually load, or null when it is coherent.
   *
   * The repair above closes the one cause this server knows about; this is the backstop that says
   * so out loud for every other cause — an offline box that could not fetch the native, a host with
   * no published native, a bundle that recorded a native for the wrong platform. **Classpath order
   * decides**, because both halves are plain classloader lookups: the bindings are `.class` files
   * and `libskiko-<target>.so` is a root-level resource in the platform jar
   * ([ee.schimke.composeai.bundle.coordinates.CoordinateResolver] puts each resolved jar where the
   * caller asked). So the pair that runs is the FIRST of each on the ordered `-cp`, which is what
   * this reads — not the set of versions present, which would call a harmless shadowed duplicate a
   * skew.
   *
   * The **first of each**, never a count of distinct versions — the distinction #4234/#4235 drew
   * for the Gradle-side guard, and it holds here for the same reason. Two independently-resolved
   * but individually-matched pairs on one classpath are fine: the trailing pair is shadowed whole.
   * What is not fine is a leading half-pair, which is precisely the serve case — bindings with no
   * native lead, so they pair with the first native behind them, which belongs to somebody else.
   *
   * Read off filenames, matching
   * [ee.schimke.composeai.plugin.ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath]:
   * these are resolved files, not a dependency graph, and the version travels with the artifact.
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
   * [classpathSkew] for the daemon launched from [descriptorPath], to be appended to a **fatal**
   * linkage failure — or null when this failure is not Skia's, the descriptor is unreadable, or the
   * classpath is coherent and the link error therefore has some other cause.
   *
   * The startup log already carries [classpathSkew], but nobody outside the box reads it. What they
   * read is the open breaker's reason, which the host returns as the body of every `409` the dead
   * lane answers with — and in compose-ai-tools#4220 that body was the whole report: it named the
   * missing symbol and nothing that explains it, so the skew had to be inferred from the outside.
   * Attaching the skew here means a lane that dies of a split Skiko says so to whoever finds it.
   *
   * Read at trip time rather than at host construction: the descriptor is a file the daemon already
   * launched from, and a diagnosis nobody needs must not cost a read per host.
   */
  fun linkageDiagnosis(reason: String, descriptorPath: File): String? {
    if (SKIA_PACKAGE !in reason) return null
    val descriptor = ServeBundleDaemon.readLaunchDescriptor(descriptorPath) ?: return null
    return classpathSkew(descriptor.classpath)
  }

  /**
   * Enough of the package name to catch both halves — `org.jetbrains.skia.*` (the bindings whose
   * `external` declarations fail to link) and `org.jetbrains.skiko.*` (the loader). A linkage error
   * naming neither belongs to some other library, and the Skiko pair says nothing about it.
   */
  private const val SKIA_PACKAGE = "org.jetbrains.ski"

  /** `skiko`, `skiko-awt`, `skiko-awt-runtime-<platform>` — anything but the version suffix. */
  private val SKIKO_ARTIFACT = Regex("""^skiko(?:-[a-z0-9]+)*-(\d[\w.\-]*)\.jar$""")

  /**
   * One log line naming what was added and why, so a catalog whose bundle needed the repair says so
   * in the daemon startup log rather than only in the absence of a later crash.
   */
  fun repairLog(coordinate: BundleReader.ClasspathEntry.Maven): String =
    "bundle carries Skiko bindings ${coordinate.version} with no native runtime for this host — " +
      "adding ${coordinate.group}:${coordinate.artifact}:${coordinate.version} so the bindings " +
      "and libskiko match (an unpaired bindings jar links against the server's own older " +
      "libskiko and fails every render with UnsatisfiedLinkError)"
}
