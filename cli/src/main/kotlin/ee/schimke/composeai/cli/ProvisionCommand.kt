package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `compose-preview provision [--desktop] [--android] [--server] [--mcp] [--xr] [--all]
 * [--android-sdk <n>] [--json]`: fetch now everything a render would otherwise fetch on first use,
 * so the machine (or a copy of its cache) renders with `COMPOSE_PREVIEW_OFFLINE=1` and no network.
 *
 * Each part goes through the provisioner a render uses, so there is one fetch per thing and no
 * second copy of where it lives:
 * - `--desktop`: the desktop daemon sidecars ([DaemonSidecarProvision]) and this host's Skiko
 *   native ([SkikoNativeProvision]).
 * - `--android`: the Android daemon sidecar, and the `android-all-instrumented` jar its Robolectric
 *   requests for each `--android-sdk` level ([RobolectricDepsProvision]; default
 *   [AndroidBundleLaunch.DEFAULT_SDK]), into the directory [ROBOLECTRIC_DEPS_DIR_ENV] names.
 * - `--server` / `--mcp`: the preview server and the MCP server ([ServerDistributionProvision]).
 * - `--xr`: the XR compositor ([XrCompositeProvision]).
 *
 * With no part named, `--desktop --android`. Exits 1 when any part could not be provisioned.
 */
internal class ProvisionCommand(
  private val args: List<String>,
  private val steps: ProvisionSteps = ProvisionSteps.Default,
  private val out: (String) -> Unit = ::println,
  private val exit: (Int) -> Unit = { exitProcess(it) },
) {
  enum class Part(val flag: String) {
    DESKTOP("--desktop"),
    ANDROID("--android"),
    SERVER("--server"),
    MCP("--mcp"),
    XR("--xr"),
  }

  fun run() {
    if ("--help" in args || "-h" in args) {
      out(USAGE)
      return
    }
    val parts = parts(args)
    val levels =
      try {
        androidLevels(args)
      } catch (e: IllegalArgumentException) {
        System.err.println("compose-preview provision: ${e.message}")
        exit(1)
        return
      }
    val report = provision(parts, levels)
    if ("--json" in args) out(json.encodeToString(ProvisionReport.serializer(), report))
    else out(text(report))
    if (!report.ok) exit(1)
  }

  internal fun provision(parts: Set<Part>, androidLevels: List<Int>): ProvisionReport {
    val items = mutableListOf<ProvisionedItem>()
    val env = linkedMapOf<String, String>()
    fun attempt(part: Part, name: String, block: () -> ProvisionedItem) {
      items +=
        try {
          block()
        } catch (e: Exception) {
          ProvisionedItem(part.name.lowercase(), name, ok = false, error = e.message ?: "$e")
        }
    }

    if (Part.DESKTOP in parts) {
      var sidecars: List<File>? = null
      attempt(Part.DESKTOP, "desktop daemon") {
        val directories = steps.sidecar(DaemonSidecarProvision.Sidecar.DESKTOP)
        sidecars = directories.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile } }
        sidecarItem(Part.DESKTOP, "desktop daemon", directories, env)
      }
      sidecars?.let { jars ->
        attempt(Part.DESKTOP, "skiko native") {
          val jar = steps.skiko(jars)
          ProvisionedItem("desktop", "skiko native", ok = true, path = jar.absolutePath)
        }
      }
    }

    if (Part.ANDROID in parts) {
      var jars: List<File>? = null
      attempt(Part.ANDROID, "android daemon") {
        val directories = steps.sidecar(DaemonSidecarProvision.Sidecar.ANDROID)
        jars = directories.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile } }
        sidecarItem(Part.ANDROID, "android daemon", directories, env)
      }
      jars?.let { sidecarJars ->
        val directory = steps.robolectricDepsDir()
        for (level in androidLevels) {
          attempt(Part.ANDROID, "android-all SDK $level") {
            val resolved = steps.androidAll(sidecarJars, level)
            val file = steps.fetchAndroidAll(resolved.artifact, directory)
            env[ROBOLECTRIC_DEPS_DIR_ENV] = directory.absolutePath
            ProvisionedItem(
              "android",
              "android-all SDK $level",
              ok = true,
              path = file.absolutePath,
              version = resolved.artifact.version,
              note =
                if (Runtime.version().feature() < resolved.requiredJava)
                  "SDK $level renders need JDK ${resolved.requiredJava}+ " +
                    "(this is JDK ${Runtime.version().feature()})"
                else null,
            )
          }
        }
      }
    }

    for ((part, distribution) in
      listOf(Part.SERVER to ReleasedDistribution.SERVER, Part.MCP to ReleasedDistribution.MCP)) {
      if (part !in parts) continue
      attempt(part, distribution.binary) {
        val binary =
          steps.distribution(distribution)
            ?: error("could not provision ${distribution.label} (see above)")
        // `<root>/<version>/bin/<binary>`
        val version = binary.parentFile?.parentFile?.name
        version?.let { env[distribution.versionEnv] = it }
        ProvisionedItem(
          part.name.lowercase(),
          distribution.binary,
          ok = true,
          path = binary.absolutePath,
          version = version,
        )
      }
    }

    if (Part.XR in parts) {
      attempt(Part.XR, "xr-composite") {
        val binary = steps.xr() ?: error("could not provision xr-composite (see above)")
        ProvisionedItem("xr", "xr-composite", ok = true, path = binary.absolutePath)
      }
    }

    val cacheRoot = steps.cacheRoot()
    val ok = items.isNotEmpty() && items.all { it.ok }
    if (ok) env["COMPOSE_PREVIEW_OFFLINE"] = "1"
    return ProvisionReport(
      cacheRoot = cacheRoot.absolutePath,
      items = items,
      env = (linkedMapOf("XDG_CACHE_HOME" to cacheRoot.parentFile.absolutePath) + env),
      ok = ok,
    )
  }

  private fun sidecarItem(
    part: Part,
    name: String,
    directories: List<File>,
    env: MutableMap<String, String>,
  ): ProvisionedItem {
    val version = DaemonSidecarProvision.version()
    env[DaemonSidecarProvision.VERSION_ENV] = version
    return ProvisionedItem(
      part.name.lowercase(),
      name,
      ok = true,
      path = directories.map { it.absolutePath }.distinct().joinToString(File.pathSeparator),
      version = version,
    )
  }

  private fun text(report: ProvisionReport): String = buildString {
    appendLine("compose-preview provision: cache root ${report.cacheRoot}")
    for (item in report.items) {
      val status = if (item.ok) "ok    " else "FAILED"
      append("  $status ${item.name}")
      item.version?.let { append(" $it") }
      appendLine()
      item.path?.let { appendLine("         $it") }
      item.note?.let { appendLine("         note: $it") }
      item.error?.let { appendLine("         $it") }
    }
    if (report.ok) {
      appendLine()
      appendLine("Ship ${report.cacheRoot} (and the CLI install), then render with:")
      report.env.forEach { (key, value) -> appendLine("  export $key=$value") }
      append(
        "XDG_CACHE_HOME is the directory holding composeai/; set it to wherever the copy lands."
      )
    } else {
      append("Some parts could not be provisioned; renders will fetch them on first use.")
    }
  }

  companion object {
    private val json = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    internal fun parts(args: List<String>): Set<Part> {
      if ("--all" in args) return Part.entries.toSet()
      val named = Part.entries.filter { it.flag in args }.toSet()
      return named.ifEmpty { setOf(Part.DESKTOP, Part.ANDROID) }
    }

    /** `--android-sdk 35 --android-sdk 36` or `--android-sdk 35,36`; default the render default. */
    internal fun androidLevels(args: List<String>): List<Int> {
      val raw = args.flagValuesAll("--android-sdk").flatMap { it.split(',') }.map { it.trim() }
      if (raw.isEmpty()) return listOf(AndroidBundleLaunch.DEFAULT_SDK)
      return raw
        .filter { it.isNotEmpty() }
        .map {
          val level =
            requireNotNull(it.toIntOrNull()) { "--android-sdk takes API levels, not '$it'" }
          require(level in AndroidBundleLaunch.MIN_SDK..AndroidBundleLaunch.MAX_SDK) {
            "--android-sdk $level is outside Robolectric's " +
              "${AndroidBundleLaunch.MIN_SDK}..${AndroidBundleLaunch.MAX_SDK}"
          }
          level
        }
        .distinct()
    }

    internal val USAGE =
      """
      compose-preview provision [--desktop] [--android] [--server] [--mcp] [--xr] [--all]
                                [--android-sdk <n>[,<n>…]] [--json]

      Fetch everything a render would otherwise download on first use into
      ${'$'}{XDG_CACHE_HOME:-~/.cache}/composeai, then print that directory, each provisioned
      path and the environment that points a later (offline) run at them.

        --desktop      desktop render daemon + this host's Skiko native
        --android      Android render daemon + Robolectric's android-all-instrumented jar
                       for each --android-sdk level (default ${AndroidBundleLaunch.DEFAULT_SDK}), into the
                       directory COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR names
        --server       the preview server (serve, browse, ui-builder)
        --mcp          the MCP server (mcp serve)
        --xr           the XR compositor (XR previews)
        --all          every part above
        --android-sdk  API levels to fetch android-all for (repeatable or comma-separated);
                       a Gradle module renders on its compileSdk, a Bazel target on its own
        --json         machine-readable report (schema compose-preview-provision/v1)

      With no part named: --desktop --android. Exits 1 when any part failed.
      """
        .trimIndent()
  }
}

@Serializable
internal data class ProvisionedItem(
  val part: String,
  val name: String,
  val ok: Boolean,
  val path: String? = null,
  val version: String? = null,
  val note: String? = null,
  val error: String? = null,
)

@Serializable
internal data class ProvisionReport(
  val schema: String = "compose-preview-provision/v1",
  val cacheRoot: String,
  val items: List<ProvisionedItem>,
  /** What a later run needs set to use only what was provisioned. */
  val env: Map<String, String>,
  val ok: Boolean,
)

/** The provisioners [ProvisionCommand] drives; faked in tests so they touch no network. */
internal interface ProvisionSteps {
  /** The sidecar's directories, provisioned; throws with the reason when it cannot be. */
  fun sidecar(sidecar: DaemonSidecarProvision.Sidecar): List<File>

  fun skiko(desktopSidecarJars: List<File>): File

  fun robolectricDepsDir(): File

  fun androidAll(androidSidecarJars: List<File>, apiLevel: Int): RobolectricDepsProvision.Resolved

  fun fetchAndroidAll(artifact: RobolectricDepsProvision.Artifact, directory: File): File

  fun distribution(distribution: ReleasedDistribution): File?

  fun xr(): File?

  /** `${XDG_CACHE_HOME:-~/.cache}/composeai`. */
  fun cacheRoot(): File

  object Default : ProvisionSteps {
    override fun sidecar(sidecar: DaemonSidecarProvision.Sidecar): List<File> {
      check(DaemonSidecarProvision.install(sidecar)) { "could not provision ${sidecar.label}" }
      return sidecar.directories.map { name ->
        locateBundleSidecarJars(name)
          .firstOrNull { !it.name.startsWith("skiko-awt-runtime-") }
          ?.parentFile ?: error("${sidecar.label} has no $name after provisioning")
      }
    }

    override fun skiko(desktopSidecarJars: List<File>): File =
      SkikoNativeProvision.prepare(desktopSidecarJars)

    override fun robolectricDepsDir(): File =
      System.getenv(ROBOLECTRIC_DEPS_DIR_ENV)?.takeIf { it.isNotBlank() }?.let(::File)
        ?: RobolectricDepsProvision.defaultDir()

    override fun androidAll(
      androidSidecarJars: List<File>,
      apiLevel: Int,
    ): RobolectricDepsProvision.Resolved =
      RobolectricDepsProvision.artifactFor(androidSidecarJars, apiLevel)

    override fun fetchAndroidAll(
      artifact: RobolectricDepsProvision.Artifact,
      directory: File,
    ): File {
      val target = File(directory, artifact.fileName)
      if (!target.isFile && ServerDistributionProvision.defaultOffline()) {
        error("${artifact.fileName} is not in $directory, and offline mode is enabled")
      }
      return RobolectricDepsProvision.ensure(artifact, directory)
    }

    override fun distribution(distribution: ReleasedDistribution): File? =
      ServerDistributionProvision.ensure(distribution)

    override fun xr(): File? = XrCompositeProvision.ensureCached(XR_COMPOSITE_VERSION)

    override fun cacheRoot(): File = DaemonSidecarProvision.defaultCacheRoot().parentFile
  }
}
