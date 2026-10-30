package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.io.SystemFileSystem
import java.io.DataInputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

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

  /** A detected level and what it was read from, for the log line that says which SDK renders. */
  data class Detected(val level: Int, val source: String)

  /**
   * The platform metadata beside [androidJar] ([of]), else the jar's own contents ([fromContents]):
   * a jar generated into the build's output tree has no platform directory beside it. Reading the
   * contents opens a 50 MB+ jar, so with [cache] it happens once per version of that jar.
   */
  fun detect(
    androidJar: File,
    cache: BazelHostCache? = null,
    fileSystem: FileSystem = SystemFileSystem,
  ): Detected? =
    of(androidJar, fileSystem)?.let { Detected(it, "platform metadata beside android.jar") }
      ?: fromContents(androidJar, cache)?.let { Detected(it, "android.jar contents") }

  /** [fromContents], memoised in [cache] by the jar's [FileStamp]. */
  fun fromContents(androidJar: File, cache: BazelHostCache?): Int? {
    if (cache == null) return fromContents(androidJar)
    val level =
      cache.androidJarLevel.get(androidJar) { fromContents(it)?.toString() ?: BazelHostCache.NONE }
    cache.androidJarLevel.save()
    return level?.toIntOrNull()
  }

  /**
   * The level, or null when neither file is there or neither names one. A symlinked jar (Bazel's
   * execution root links into the SDK) is also looked up beside its target.
   */
  fun of(androidJar: File, fileSystem: FileSystem = SystemFileSystem): Int? {
    val jar = androidJar.absoluteFile.toOkioPath()
    return listOfNotNull(
        jar.parent,
        runCatching { fileSystem.canonicalize(jar).parent }.getOrNull(),
      )
      .distinct()
      .firstNotNullOfOrNull { inPlatformDirectory(it, fileSystem) }
  }

  private fun inPlatformDirectory(directory: Path, fileSystem: FileSystem): Int? =
    level(directory / "source.properties", "AndroidVersion.ApiLevel", fileSystem)
      ?: level(directory / "build.prop", "ro.build.version.sdk", fileSystem)

  /**
   * The highest `android.os.Build.VERSION_CODES` constant [androidJar] declares: a platform's
   * `android.jar` names every level up to its own. `CUR_DEVELOPMENT` (10000) is not a level. Null
   * when the jar or the class is missing or unreadable.
   */
  fun fromContents(androidJar: File): Int? = runCatching {
    ZipFile(androidJar).use { zip ->
      val entry = zip.getEntry("android/os/Build\$VERSION_CODES.class") ?: return null
      zip.getInputStream(entry).use { classIntegerConstants(DataInputStream(it)) }
    }
  }
    .getOrNull()
    ?.filter { it in 1 until CUR_DEVELOPMENT }
    ?.maxOrNull()

  private const val CUR_DEVELOPMENT = 10000

  /** Every `CONSTANT_Integer` in a class file's constant pool; a constant field's value is one. */
  private fun classIntegerConstants(input: DataInputStream): List<Int> {
    require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
    input.readUnsignedShort() // minor
    input.readUnsignedShort() // major
    val count = input.readUnsignedShort()
    val integers = mutableListOf<Int>()
    var index = 1
    while (index < count) {
      when (val tag = input.readUnsignedByte()) {
        1 -> input.skipBytes(input.readUnsignedShort())
        3 -> integers += input.readInt()
        4 -> input.skipBytes(4)
        5,
        6 -> {
          input.skipBytes(8)
          index++ // Long and Double take two slots.
        }
        7,
        8,
        16,
        19,
        20 -> input.skipBytes(2)
        9,
        10,
        11,
        12,
        17,
        18 -> input.skipBytes(4)
        15 -> input.skipBytes(3)
        else -> error("unknown constant pool tag $tag")
      }
      index++
    }
    return integers
  }

  /** [key]'s leading integer: a minor platform (`36.1`) renders on its major level's framework. */
  private fun level(file: Path, key: String, fileSystem: FileSystem): Int? {
    val properties = runCatching {
      fileSystem.read(file) { Properties().apply { load(inputStream().reader()) } }
    }
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
  target: String? = null,
  source: String? = null,
): Int {
  val ceiling =
    if (javaMajor < 21) minOf(AndroidBundleLaunch.MAX_SDK, SDK_REQUIRING_JAVA_21 - 1)
    else AndroidBundleLaunch.MAX_SDK
  val prefix = "[bazel]" + (target?.let { " $it:" } ?: "")
  val requested =
    compileSdk
      ?: return AndroidBundleLaunch.DEFAULT_SDK.also {
        // A silent default is how a wrong SDK goes unnoticed; say so, and how to set it.
        log(
          "$prefix no compileSdk from the aspect, the platform metadata or android.jar; " +
            "rendering on the default SDK $it (set androidCompileSdk in .compose-preview/bazel.json)"
        )
      }
  val level = requested.coerceIn(AndroidBundleLaunch.MIN_SDK, ceiling)
  if (level != requested) {
    log(
      "$prefix compileSdk $requested${source?.let { " (from $it)" } ?: ""} is outside what " +
        "Robolectric renders here (${AndroidBundleLaunch.MIN_SDK}..$ceiling" +
        (if (ceiling < AndroidBundleLaunch.MAX_SDK) " on JDK $javaMajor" else "") +
        "); rendering on SDK $level"
    )
  } else {
    log("$prefix rendering on SDK $level${source?.let { " (compileSdk from $it)" } ?: ""}")
  }
  return level
}
