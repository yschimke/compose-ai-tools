package ee.schimke.composeai.bundle.producer

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * [ReleasedVersions] from `maven-metadata.xml`: Google Maven for `androidx.*`, `com.android.*` and
 * `com.google.android.*`, Maven Central otherwise. Every fetched document is cached under
 * [cacheDir] (normally `composeAiCacheDir("maven-metadata")`) in a `<host>/<group
 * path>/<artifact>/` layout, so a lookup made once answers offline afterwards.
 *
 * With [network] false only the cache is read, whatever its age: the `COMPOSE_PREVIEW_OFFLINE` and
 * hermetic-action view. A caller under `COMPOSE_PREVIEW_NO_NETWORK` passes [ReleasedVersions.NONE]
 * instead, so only lockfile-pinned knowledge counts. With [network] true a cached copy younger than
 * [maxAgeMillis] is used as-is; an older one is refreshed, and kept if the refresh fails.
 */
public class MavenMetadataVersions(
  private val cacheDir: File,
  private val network: Boolean,
  private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
  private val fetch: (String) -> String? = ::httpGet,
  private val now: () -> Long = System::currentTimeMillis,
) : ReleasedVersions {

  override fun versions(group: String, artifact: String): List<String>? {
    if (!SAFE_SEGMENT.matches(group) || !SAFE_SEGMENT.matches(artifact)) return null
    val base = repositoryFor(group)
    val host = URI(base).host
    val cached = File(cacheDir, "$host/${group.replace('.', '/')}/$artifact/maven-metadata.xml")
    val fresh = cached.isFile && now() - cached.lastModified() < maxAgeMillis
    val text =
      if (!network || fresh) cached.takeIf { it.isFile }?.readText()
      else
        runCatching { fetch("$base/${group.replace('.', '/')}/$artifact/maven-metadata.xml") }
          .getOrNull()
          ?.also { body ->
            cached.parentFile.mkdirs()
            val temp =
              File(cached.parentFile, "${cached.name}.${ProcessHandle.current().pid()}.tmp")
            temp.writeText(body)
            if (!temp.renameTo(cached)) {
              cached.delete()
              temp.renameTo(cached)
            }
          } ?: cached.takeIf { it.isFile }?.readText()
    return text?.let(::parseMavenMetadataVersions)
  }

  public companion object {
    public const val GOOGLE_MAVEN: String = "https://dl.google.com/android/maven2"
    public const val MAVEN_CENTRAL: String = "https://repo1.maven.org/maven2"

    private val SAFE_SEGMENT = Regex("[A-Za-z0-9_.-]+")

    public fun repositoryFor(group: String): String =
      if (
        group == "androidx" ||
          group.startsWith("androidx.") ||
          group == "com.android" ||
          group.startsWith("com.android.") ||
          group == "com.google.android" ||
          group.startsWith("com.google.android.")
      )
        GOOGLE_MAVEN
      else MAVEN_CENTRAL

    /** The `<version>` entries of a `maven-metadata.xml`, in document order. */
    public fun parseMavenMetadataVersions(xml: String): List<String> =
      Regex("<version>\\s*([^<\\s]+)\\s*</version>")
        .findAll(xml.substringAfter("<versions>", ""))
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    private fun httpGet(url: String): String? {
      val connection = URI(url).toURL().openConnection() as HttpURLConnection
      return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        if (connection.responseCode != 200) null
        else connection.inputStream.use { it.readBytes().decodeToString() }
      } finally {
        connection.disconnect()
      }
    }
  }
}
