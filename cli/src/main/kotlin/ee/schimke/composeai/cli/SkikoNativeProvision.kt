package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking

/** Fetches the single host-specific Skiko native jar omitted from the portable CLI distribution. */
internal object SkikoNativeProvision {
  private const val CLI_SKIKO_DIR_PROPERTY = "composeai.cli.skikoDir"
  private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
  private val apiJarPattern = Regex("^skiko-awt-(?!runtime-)(.+)\\.jar$")

  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  private val defaultFetcher = Fetcher { url, dest ->
    HttpClient(OkHttp).use { client ->
      runBlocking {
        client.prepareGet(url).execute { response ->
          if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
          dest.outputStream().use { output -> response.bodyAsChannel().copyTo(output) }
        }
      }
    }
  }

  internal fun platformArtifact(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    return when {
      os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x64"
      os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-arm64"
      (os.contains("mac") || os.contains("darwin")) && (arch == "x86_64" || arch == "amd64") ->
        "macos-x64"
      (os.contains("mac") || os.contains("darwin")) && (arch == "aarch64" || arch == "arm64") ->
        "macos-arm64"
      os.contains("windows") && (arch == "x86_64" || arch == "amd64") -> "windows-x64"
      os.contains("windows") && (arch == "aarch64" || arch == "arm64") -> "windows-arm64"
      else -> null
    }
  }

  internal fun skikoVersion(sidecarJars: List<File>): String? =
    sidecarJars
      .asSequence()
      .mapNotNull { apiJarPattern.matchEntire(it.name)?.groupValues?.get(1) }
      .firstOrNull()

  internal fun artifactName(platform: String): String = "skiko-awt-runtime-$platform"

  internal fun jarName(version: String, platform: String): String =
    "${artifactName(platform)}-$version.jar"

  internal fun artifactUrl(version: String, platform: String): String {
    val artifact = artifactName(platform)
    return "$MAVEN_CENTRAL/org/jetbrains/skiko/$artifact/$version/$artifact-$version.jar"
  }

  internal fun cacheJar(cacheRoot: File, version: String, platform: String): File =
    File(File(File(cacheRoot, version), platform), jarName(version, platform))

  /** Resolve the shipped desktop sidecars and provision the matching native before serving. */
  internal fun prepareInstalledDesktopSidecars(): File {
    val jars =
      listOf("lib-daemon-desktop", "lib-renderer", "lib-rcjvm").flatMap {
        locateBundleSidecarJars(it)
      }
    return prepare(jars)
  }

  /** Provision against an already-resolved renderer/daemon classpath. */
  internal fun prepare(sidecarJars: List<File>): File {
    val platform =
      platformArtifact(
        System.getProperty("os.name") ?: "",
        System.getProperty("os.arch") ?: "",
      )
        ?: error(
          "Skiko has no CLI native artifact for " +
            "${System.getProperty("os.name")}/${System.getProperty("os.arch")}."
        )
    val version =
      skikoVersion(sidecarJars)
        ?: error(
          "Cannot determine the Skiko version: no skiko-awt-<version>.jar was found in the " +
            "installed desktop sidecars. Reinstall the CLI distribution."
        )
    val configuredDir =
      System.getProperty(CLI_SKIKO_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
    val offline =
      System.getProperty("composeai.bundle.offline").toBoolean() ||
        System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1"
    val jar =
      ensureAvailable(
        version = version,
        platform = platform,
        configuredDir = configuredDir,
        cacheRoot = composeAiCacheDir("skiko"),
        offline = offline,
        fetcher = defaultFetcher,
      )
    System.setProperty(CLI_SKIKO_DIR_PROPERTY, jar.parentFile.absolutePath)
    return jar
  }

  internal fun ensureAvailable(
    version: String,
    platform: String,
    configuredDir: File?,
    cacheRoot: File,
    offline: Boolean,
    fetcher: Fetcher,
  ): File {
    val expectedName = jarName(version, platform)
    if (configuredDir != null) {
      val configuredJar = File(configuredDir, expectedName)
      requireValid(configuredJar) {
        "-D$CLI_SKIKO_DIR_PROPERTY=${configuredDir.absolutePath} does not contain a valid " +
          "$expectedName. Download that Maven artifact into the directory or remove the override."
      }
      return configuredJar
    }

    val cached = cacheJar(cacheRoot, version, platform)
    if (isValidNativeJar(cached)) return cached
    if (offline) {
      error(
        "Skiko native $expectedName is not cached at ${cached.absolutePath}, and offline mode is " +
          "enabled. Pre-warm the cache while online or provide " +
          "-D$CLI_SKIKO_DIR_PROPERTY=<dir> containing $expectedName."
      )
    }

    val url = artifactUrl(version, platform)
    val parent = cached.parentFile
    val temporary = File(parent, ".${cached.name}.${UUID.randomUUID()}.tmp")
    try {
      parent.mkdirs()
      fetcher.fetchTo(url, temporary)
      requireValid(temporary) { "Downloaded Skiko native from $url is not a valid native jar." }
      try {
        Files.move(
          temporary.toPath(),
          cached.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: Exception) {
        Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      return cached
    } catch (e: Exception) {
      throw IllegalStateException(
        "Could not provision Skiko native $expectedName from $url (${e.message}). " +
          "Retry online, pre-warm ${cached.absolutePath}, or provide " +
          "-D$CLI_SKIKO_DIR_PROPERTY=<dir> containing $expectedName.",
        e,
      )
    } finally {
      temporary.delete()
    }
  }

  internal fun isValidNativeJar(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    return try {
      ZipFile(file).use { zip ->
        zip.entries().asSequence().any { entry ->
          !entry.isDirectory &&
            (entry.name.endsWith(".so") ||
              entry.name.endsWith(".dylib") ||
              entry.name.endsWith(".dll"))
        }
      }
    } catch (_: Exception) {
      false
    }
  }

  private inline fun requireValid(file: File, message: () -> String) {
    if (!isValidNativeJar(file)) error(message())
  }
}
