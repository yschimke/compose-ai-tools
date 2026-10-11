package ee.schimke.composeai.cli

import java.util.Properties

/**
 * Release this CLI was built from: `compose-preview --version`, the default in [DoctorCommand]'s
 * remediation snippets, and the update check. Read from `cli-version.properties`, generated from
 * `project.version` by `cli/build.gradle.kts`'s `generateCliVersionResource`.
 */
internal val BUNDLE_VERSION: String by lazy { cliVersionProperty("version") }

/**
 * Release the native `xr-composite` compositor is provisioned from ([XrCompositeProvision]). Not
 * [BUNDLE_VERSION]: it is the `xr-composite` pin in `gradle/libs.versions.toml`, which moves only
 * with the compositor, so CLI upgrades don't orphan cached binaries. `check_xr_composite_pin.py`
 * verifies the pinned release and its tarballs exist.
 */
internal val XR_COMPOSITE_VERSION: String by lazy { cliVersionProperty("xrCompositeVersion") }

/**
 * Version of this repository's Maven artifacts the CLI resolves: the auto-injected plugin and the
 * coordinate `doctor` recommends. Not [BUNDLE_VERSION], because a release may skip publishing to
 * Central, and injecting an unpublished version would break every consumer. Baked from the
 * `MAVEN_LINE_VERSION` the release planner sets (the tag when it publishes, else the last published
 * version).
 */
internal val MAVEN_LINE_VERSION: String by lazy { cliVersionProperty("mavenLineVersion") }

/** Read one key from the build-time-generated `cli-version.properties`. */
private fun cliVersionProperty(key: String): String {
  val props = Properties()
  val stream =
    object {}
      .javaClass
      .classLoader
      .getResourceAsStream("ee/schimke/composeai/cli/cli-version.properties")
      ?: error("cli-version.properties missing from compose-preview jar")
  stream.use { props.load(it) }
  return props.getProperty(key) ?: error("$key property missing from cli-version.properties")
}

/** GitHub repo slug used to resolve CLI releases (tarballs, action tags, issue links). */
internal const val REPO = "yschimke/compose-ai-tools"

/**
 * Repo hosting the bootstrap installer and skill bundles; `update` and doctor's snippets fetch
 * `scripts/install.sh` from here.
 */
internal const val SKILLS_REPO = "yschimke/skills"

/**
 * Repo the `xr-composite` compositor is released from, on its own cadence; see
 * [XR_COMPOSITE_VERSION].
 */
internal const val XR_COMPOSITE_REPO = "yschimke/compose-preview-xr"

/**
 * Repo whose release assets carry the preview server distribution [ServerDistributionProvision]
 * fetches. A release asset (launcher + `lib/`), not a Maven artifact, hence a GitHub slug.
 */
internal const val PREVIEW_SERVER_REPO = "yschimke/compose-preview-server"

/**
 * compose-preview-daemon release whose sidecars the CLI fetches (`lib-renderer/`,
 * `lib-daemon-desktop/`, `lib-daemon-android/`; see [DaemonSidecarProvision]). The
 * `composeai-preview-daemon` catalog pin, also baked into the plugin as `PreviewDaemonVersion`, so
 * plugin-driven and CLI-fetched daemons match.
 */
internal val PREVIEW_DAEMON_VERSION: String by lazy { cliVersionProperty("previewDaemonVersion") }

/** GitHub repo slug the render daemons and renderers are released from. */
internal const val PREVIEW_DAEMON_REPO = "yschimke/compose-preview-daemon"

/**
 * Compare `major.minor.patch[-suffix]` strings componentwise, returning -1/0/1. Suffixes sort
 * before the same numeric base (`0.8.11-SNAPSHOT` < `0.8.11`); unparseable input falls back to a
 * string compare so it never throws.
 */
internal fun compareSemver(a: String, b: String): Int {
  fun parts(v: String): Pair<List<Int>, Boolean> {
    val (head, suffix) = v.split('-', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
    val nums = head.split('.').map { it.toIntOrNull() }
    val parsed = nums.none { it == null }
    return (if (parsed) nums.filterNotNull() else emptyList()) to suffix.isNotEmpty()
  }
  val (aNums, aPre) = parts(a)
  val (bNums, bPre) = parts(b)
  if (aNums.isEmpty() || bNums.isEmpty()) return a.compareTo(b)
  val len = maxOf(aNums.size, bNums.size)
  for (i in 0 until len) {
    val ai = aNums.getOrElse(i) { 0 }
    val bi = bNums.getOrElse(i) { 0 }
    if (ai != bi) return ai.compareTo(bi)
  }
  return when {
    aPre && !bPre -> -1
    !aPre && bPre -> 1
    else -> 0
  }
}

/**
 * Major version (the first numeric segment) of a semver-ish string, or `null` when it can't be
 * parsed. `"1.2.3"` → 1, `"v2.0.0-SNAPSHOT"` → 2, `"main"` → null.
 */
internal fun majorVersionOf(v: String): Int? =
  v.trim().removePrefix("v").substringBefore('-').split('.').firstOrNull()?.toIntOrNull()

/**
 * Whether two compose-preview versions have different majors (a major changes the render/daemon
 * wire format and public APIs). False when either is unparseable.
 */
internal fun versionsIncompatible(a: String, b: String): Boolean {
  val am = majorVersionOf(a) ?: return false
  val bm = majorVersionOf(b) ?: return false
  return am != bm
}
