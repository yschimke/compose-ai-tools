package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import java.io.File
import java.util.Properties

/**
 * The API level of the SDK platform an `android.jar` belongs to, read from the platform directory
 * beside it rather than from its path.
 *
 * The Bazel Android aspect writes `android.compileSdk` only when the jar's path has an
 * `android-<N>` segment, and rules_android's `AndroidSdkInfo` carries no API level to fall back on,
 * so the CLI reads what the SDK manager installed next to the jar: `source.properties`
 * (`AndroidVersion.ApiLevel=36`), else the platform image's `build.prop`
 * (`ro.build.version.sdk=36`).
 */
internal object AndroidPlatformLevel {

  /**
   * The level, or null when neither file is there or neither names one. A symlinked jar (Bazel's
   * execution root links into the SDK) is also looked up beside its target.
   */
  fun of(androidJar: File): Int? =
    listOfNotNull(
        androidJar.absoluteFile.parentFile,
        runCatching { androidJar.canonicalFile.parentFile }.getOrNull(),
      )
      .distinct()
      .firstNotNullOfOrNull(::inPlatformDirectory)

  private fun inPlatformDirectory(directory: File): Int? =
    level(directory.resolve("source.properties"), "AndroidVersion.ApiLevel")
      ?: level(directory.resolve("build.prop"), "ro.build.version.sdk")

  /** [key]'s leading integer: a minor platform (`36.1`) renders on its major level's framework. */
  private fun level(file: File, key: String): Int? {
    if (!file.isFile) return null
    val properties = runCatching { file.reader().use { Properties().apply { load(it) } } }
    return properties
      .getOrNull()
      ?.getProperty(key)
      ?.trim()
      ?.substringBefore('.')
      ?.toIntOrNull()
      ?.takeIf { it > 0 }
  }
}

/** First level Robolectric refuses to bootstrap below JDK 21 (`DefaultSdkProvider`). */
private const val SDK_REQUIRING_JAVA_21 = 36

/**
 * The Robolectric sandbox SDK a Bazel Android target renders on: its `compileSdk` (as the Gradle
 * plugin's `GenerateRobolectricPropertiesTask` resolves it), else
 * [AndroidBundleLaunch.DEFAULT_SDK]; clamped to the bundled Robolectric's range, and below
 * [SDK_REQUIRING_JAVA_21] when the daemon's JVM (this one: the descriptor names no other launcher)
 * is older than 21.
 */
internal fun bazelAndroidSdkLevel(
  compileSdk: Int?,
  javaMajor: Int = Runtime.version().feature(),
  log: (String) -> Unit = { System.err.println(it) },
): Int {
  val ceiling =
    if (javaMajor < 21) minOf(AndroidBundleLaunch.MAX_SDK, SDK_REQUIRING_JAVA_21 - 1)
    else AndroidBundleLaunch.MAX_SDK
  val requested = compileSdk ?: return AndroidBundleLaunch.DEFAULT_SDK
  val level = requested.coerceIn(AndroidBundleLaunch.MIN_SDK, ceiling)
  if (level != requested) {
    log(
      "[bazel] compileSdk $requested is outside what Robolectric renders here " +
        "(${AndroidBundleLaunch.MIN_SDK}..$ceiling" +
        (if (ceiling < AndroidBundleLaunch.MAX_SDK) " on JDK $javaMajor" else "") +
        "); rendering on SDK $level"
    )
  }
  return level
}
