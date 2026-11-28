package ee.schimke.composeai.cli.simulator

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Properties

/**
 * Self-contained Android SDK, Robolectric JVM args, and sidecar jar lookup for `:simulator-cli`,
 * keeping the module free of project dependencies on `:bundle-format` or `:cli`.
 */
internal object SimulatorRuntimeSupport {
  /** Ceiling of the bundled Robolectric's supported `android-all` range (API 36). */
  const val MAX_SDK: Int = 36

  /** Default SDK level when the user does not pass `--sdk`. */
  const val DEFAULT_SDK: Int = 36

  private const val CLI_SKIKO_DIR_PROPERTY = "composeai.cli.skikoDir"
  private const val CLI_SKIKO_PROVISIONER_CLASS_PROPERTY = "composeai.cli.skikoProvisionerClass"
  private const val CLI_SKIKO_PROVISIONER_METHOD = "provisionSkikoNativeForDesktopSidecar"

  /**
   * JVM args the Robolectric process needs before anything else initialises
   * (`--enable-native-access`
   * + the `--add-opens` set for JDK 17+ / SDK 36 sandboxes).
   */
  fun robolectricJvmArgs(): List<String> =
    listOf(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )

  /**
   * The highest-versioned `platforms/android-N/android.jar` under the SDK named by `sdk.dir` in
   * [localPropertiesFile], else `ANDROID_HOME` / `ANDROID_SDK_ROOT`; null when unreachable.
   */
  fun resolveAndroidJar(
    localPropertiesFile: File?,
    env: (String) -> String? = { System.getenv(it) },
  ): File? {
    val root = sdkRoot(localPropertiesFile, env) ?: return null
    return highestPlatformAndroidJar(root)
  }

  private fun sdkRoot(localPropertiesFile: File?, env: (String) -> String?): File? {
    localPropertiesFile
      ?.takeIf { it.isFile }
      ?.let { f ->
        val props = Properties().apply { f.inputStream().use { load(it) } }
        props
          .getProperty("sdk.dir")
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            return File(it)
          }
      }
    for (name in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
      env(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          return File(it)
        }
    }
    return null
  }

  private fun highestPlatformAndroidJar(sdkRoot: File): File? {
    val platforms = File(sdkRoot, "platforms").takeIf { it.isDirectory } ?: return null
    return platforms
      .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
      .orEmpty()
      .mapNotNull { dir ->
        val jar = File(dir, "android.jar").takeIf { it.isFile } ?: return@mapNotNull null
        val level = platformLevel(dir.name) ?: return@mapNotNull null
        level to jar
      }
      .maxWithOrNull(compareBy({ it.first.first }, { it.first.second }))
      ?.second
  }

  private fun platformLevel(name: String): Pair<Int, Int>? {
    val match = Regex("""android-(\d+)(?:\.(\d+))?""").matchEntire(name) ?: return null
    return match.groupValues[1].toInt() to (match.groupValues[2].toIntOrNull() ?: 0)
  }

  /**
   * Locate a sidecar jar dir (`lib-simulator` / `lib-dex2jar` / `lib-daemon-android` / `lib-rcjvm`
   * / `lib-daemon-desktop`) inside the CLI install. In order: explicit sysprop override
   * (`composeai.cli.lib<Name>Dir`), `$APP_HOME/<name>`, `<jar-parent>/../<name>`.
   */
  fun locateSidecarJars(sidecarName: String): List<File> {
    val sysprop = sidecarSysprop(sidecarName)
    val override = System.getProperty(sysprop)
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    val candidates =
      listOfNotNull(
          override?.let { File(it) },
          appHome?.let { File(it, sidecarName) },
          inferSidecarFromClasspath(sidecarName),
        )
        .distinct()
    val firstExistingDir = candidates.firstOrNull { it.isDirectory } ?: return emptyList()
    val sidecarJars =
      firstExistingDir
        .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (sidecarJars.none { !it.name.startsWith("skiko-awt-runtime-") }) return emptyList()
    val provisionedSkiko =
      if (sidecarName == "lib-daemon-desktop") provisionDesktopNative(sidecarJars) else null
    return if (sidecarName == "lib-daemon-desktop") {
      val nativeJars = provisionedSkiko?.let(::listOf) ?: configuredSkikoJars()
      (nativeJars + sidecarJars).distinctBy { it.absoluteFile.normalize().path }
    } else {
      sidecarJars
    }
  }

  /** Human-readable description of where [locateSidecarJars] looked, for error messages. */
  fun sidecarSearchDescription(sidecarName: String): String {
    val sysprop = sidecarSysprop(sidecarName)
    val override = System.getProperty(sysprop)
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(
        override?.let { "-D$sysprop=$it" },
        appHome?.let { "$it/$sidecarName" },
        "<classpath-parent>/../$sidecarName",
        if (sidecarName == "lib-daemon-desktop") {
          System.getProperty(CLI_SKIKO_DIR_PROPERTY)?.let { "-D$CLI_SKIKO_DIR_PROPERTY=$it" }
        } else null,
      )
      .joinToString(" or ")
  }

  private fun sidecarSysprop(sidecarName: String): String =
    when (sidecarName) {
      "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
      "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
      "lib-renderer" -> "composeai.cli.libRendererDir"
      "lib-rcjvm" -> "composeai.cli.libRcjvmDir"
      "lib-bta" -> "composeai.cli.libBtaDir"
      "lib-usage-psi" -> "composeai.cli.libUsagePsiDir"
      "lib-simulator" -> "composeai.cli.libSimulatorDir"
      "lib-dex2jar" -> "composeai.cli.libDex2jarDir"
      else -> "composeai.cli.${sidecarName.replace('-', '.')}Dir"
    }

  private fun inferSidecarFromClasspath(sidecarName: String): File? {
    val cp = System.getProperty("java.class.path") ?: return null
    val firstEntry = cp.split(File.pathSeparator).firstOrNull { it.endsWith(".jar") } ?: return null
    val libDir = File(firstEntry).parentFile ?: return null
    val installRoot = libDir.parentFile ?: return null
    val candidate = File(installRoot, sidecarName)
    return candidate.takeIf { it.isDirectory }
  }

  private fun provisionDesktopNative(sidecarJars: List<File>): File? {
    val className = System.getProperty(CLI_SKIKO_PROVISIONER_CLASS_PROPERTY) ?: return null
    return try {
      Class.forName(className)
        .getMethod(CLI_SKIKO_PROVISIONER_METHOD, List::class.java)
        .invoke(null, sidecarJars) as? File
    } catch (e: InvocationTargetException) {
      val cause = e.cause ?: e
      if (cause is RuntimeException) throw cause
      throw IllegalStateException("Desktop native provisioning failed: ${cause.message}", cause)
    } catch (e: ReflectiveOperationException) {
      throw IllegalStateException(
        "Cannot invoke desktop native provisioner $className.$CLI_SKIKO_PROVISIONER_METHOD",
        e,
      )
    }
  }

  private fun configuredSkikoJars(): List<File> {
    val dir = System.getProperty(CLI_SKIKO_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
    if (dir?.isDirectory != true) return emptyList()
    return dir
      .listFiles { file ->
        file.isFile && file.name.startsWith("skiko-awt-runtime-") && file.name.endsWith(".jar")
      }
      ?.sortedBy { it.name }
      .orEmpty()
  }
}
