package ee.schimke.composeai.cli.serve

import java.security.MessageDigest

/** Static browser assets for [ServeWeb], served from classpath resources under `/assets/serve/`. */
internal object ServeWebAssets {
  private const val RESOURCE_DIR = "/ee/schimke/composeai/cli/serve/assets"
  private const val URL_BASE = "/assets/serve"

  private val contentTypes =
    mapOf(
      "serve.css" to "text/css; charset=utf-8",
      "playground.css" to "text/css; charset=utf-8",
      "viewer.js" to "text/javascript; charset=utf-8",
      "viewer-groups.js" to "text/javascript; charset=utf-8",
      "viewer-drawers.js" to "text/javascript; charset=utf-8",
      "backend-badge.js" to "text/javascript; charset=utf-8",
    )

  private val cache = java.util.concurrent.ConcurrentHashMap<String, Asset>()

  data class Asset(
    val bytes: ByteArray,
    val contentType: String,
    val etag: String,
    val version: String,
  )

  fun href(name: String): String = "$URL_BASE/${load(name)?.version ?: "missing"}/$name"

  fun load(name: String): Asset? {
    if (name !in contentTypes) return null
    cache[name]?.let {
      return it
    }
    val asset = run {
      val bytes =
        ServeWebAssets::class.java.getResourceAsStream("$RESOURCE_DIR/$name")?.use {
          it.readBytes()
        } ?: return null
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val etag =
        "\"" +
          bytes.size.toString(16) +
          "-" +
          digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
          "\""
      Asset(
        bytes = bytes,
        contentType = contentTypes.getValue(name),
        etag = etag,
        version = etag.trim('"'),
      )
    }
    cache.putIfAbsent(name, asset)
    return cache.getValue(name)
  }
}
