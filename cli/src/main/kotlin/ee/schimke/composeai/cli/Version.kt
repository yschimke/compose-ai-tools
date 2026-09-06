package ee.schimke.composeai.cli

import java.util.Properties

/**
 * Release this CLI was built from. Surfaced via `compose-preview --version`, used as the default
 * `--plugin-version` in [DoctorCommand]'s remediation snippets, and compared against the latest
 * GitHub release tag in `doctor`'s update check.
 *
 * Resolved from `cli-version.properties`, baked into the jar by `cli/build.gradle.kts`'s
 * `generateCliVersionResource`. The previous hand-edited literal here drifted out of sync with
 * `.release-please-manifest.json` (`0.9.0` vs `0.8.12`) which made `compose-preview show` look for
 * a release tag that didn't exist. The build-time resource derives the version from
 * `project.version`, which already honours the `PLUGIN_VERSION` env override CI sets and the
 * `.release-please-manifest.json` patch-bump fallback for local builds.
 */
internal val BUNDLE_VERSION: String by lazy { cliVersionProperty("version") }

/**
 * Release the native `xr-composite` compositor is provisioned from — see [XrCompositeProvision].
 *
 * Deliberately NOT [BUNDLE_VERSION]. Addressing the binary by the CLI's own version forced an
 * `xr-composite-*.tar.gz` asset to exist on every release, so the compositor was rebuilt and
 * republished 226 times for 13 source changes, and every CLI upgrade orphaned the user's cached
 * copy of an unchanged binary. This is a pin in `gradle/libs.versions.toml` (`xr-composite`) that
 * moves only when a new compositor is released from [XR_COMPOSITE_REPO];
 * `check_xr_composite_pin.py` fails a PR whose pin names a release that does not exist there, or
 * one missing a platform tarball — either 404s on download, and a 404 downstream is a graceful skip
 * nobody sees.
 */
internal val XR_COMPOSITE_VERSION: String by lazy { cliVersionProperty("xrCompositeVersion") }

/**
 * Release of the preview server `serve` and `browse` launch — see [ServerDistributionProvision].
 *
 * The `composeai-preview-server-dist` pin from `gradle/libs.versions.toml`, baked in at build time
 * for the same reason [XR_COMPOSITE_VERSION] is: the installed CLI cannot read the version catalog,
 * and the writer of the cache and any later reader of it must derive one directory.
 *
 * Deliberately NOT [BUNDLE_VERSION]. compose-preview-server releases on its own cadence and its
 * version line is independent — it went to 2.0.0 when it left this repository while this one was
 * still on 1.x — so the CLI's own version names no server at all. Deliberately not "latest" either:
 * resolving that at run time would let a server this CLI has never been built against arrive under
 * it without a pull request. Moving the pin is the reviewed act, and `check_preview_server_pin.py`
 * fails a PR whose pin names a release with no distribution attached.
 *
 * Deliberately NOT `composeai-preview-serve` either, since the pin split: that one names the
 * published jar `:cli`'s wire-drift tests compile against, and the server can cut a release that
 * carries the distributions without republishing the library. This names the release fetched.
 */
internal val SERVE_VERSION: String by lazy { cliVersionProperty("serveVersion") }

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
 * GitHub repo slug hosting the bootstrap installer + skill bundles. Separate from [REPO] since
 * `scripts/install.sh` was moved out of `compose-ai-tools` (which still hosts the CLI release
 * tarballs) into a content-only sibling repo. The `update` subcommand and doctor's remediation
 * snippets curl their `scripts/install.sh` URL from here.
 */
internal const val SKILLS_REPO = "yschimke/skills"

/**
 * GitHub repo slug the native `xr-composite` compositor is released from.
 *
 * Separate from [REPO] since the compositor was split out of this repository: it changes roughly
 * twice a quarter where this one releases daily, and republishing it per release cost 1.23 GB
 * across 226 releases for 13 source changes. Its version is [XR_COMPOSITE_VERSION], a pin that
 * moves only when the compositor does — so the release this resolves is almost never the current
 * one here, and that is the point.
 */
internal const val XR_COMPOSITE_REPO = "yschimke/compose-preview-xr"

/**
 * GitHub repo slug the preview server is released from, and whose release assets carry the
 * distribution [ServerDistributionProvision] fetches.
 *
 * Separate from [REPO] since the server was extracted (#4732): `compose-preview serve` is a
 * launcher for a binary built, versioned and released there. The Maven coordinate
 * `ee.schimke.composeai:compose-preview-serve` names the same software; the *distribution* — the
 * launcher script and its `lib/` — is a release asset, not a Maven artifact, which is why this is a
 * GitHub slug rather than a coordinate.
 */
internal const val PREVIEW_SERVER_REPO = "yschimke/compose-preview-server"

/**
 * Compare two version strings componentwise (`major.minor.patch[-suffix]`), returning -1/0/1.
 * `-SNAPSHOT` and other suffixes sort *before* the same numeric base (so `0.8.11-SNAPSHOT` is older
 * than `0.8.11`) — the convention the rest of the build follows. Anything we can't parse falls back
 * to a string compare so a pathological tag never throws.
 *
 * Lives in [Version.kt] so the doctor update check can compare [BUNDLE_VERSION] against the tag
 * resolved from the GitHub `releases/latest` redirect, and so unit tests can exercise the
 * comparator without round-tripping the rest of `doctor`.
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
 * Whether two compose-preview component versions are mutually incompatible — i.e. they parse to
 * different **major** versions. A major release changes the render/daemon wire format and the
 * published `okio.Path` / suspend APIs, so a plugin and CLI (or daemon) on different majors can
 * fail to render or misbehave. Returns `false` when either version is unparseable (we don't warn on
 * strings like `main` / SNAPSHOT-only builds we can't reason about).
 */
internal fun versionsIncompatible(a: String, b: String): Boolean {
  val am = majorVersionOf(a) ?: return false
  val bm = majorVersionOf(b) ?: return false
  return am != bm
}
