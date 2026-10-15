package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.producer.bazelBundleSha
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The local jars a build already holds for a bundle's Maven coordinates (`bundle render
 * --local-jars`), keyed by `group:artifact:version`.
 *
 * A Bazel-prepared bundle records, in `dependency-resolution.json`, every runtime jar the producer
 * saw: the coordinate it travels as and the SHA-256 of the jar's bytes (`builtSha256`). A jar in
 * [localJars] whose content hash matches a record is exactly the file the producer identified as
 * that coordinate, wherever and however it is named (a `processed_` stamped jar, rules_kotlin's
 * unversioned `kotlin-stdlib.jar`, an AAR's merged classes jar). So a build that renders its own
 * prepared bundle resolves those coordinates from its own inputs, by content, rather than from a
 * Maven repository where only a re-derived guess at the same coordinate could be staged.
 *
 * Empty for a bundle without those records (any non-Bazel producer) or when [localJars] is empty.
 */
internal fun bundleLocalJars(zipBytes: ByteArray, localJars: List<File>): Map<String, File> {
  if (localJars.isEmpty()) return emptyMap()
  val records = dependencyResolutionRecords(zipBytes)
  if (records.isEmpty()) return emptyMap()
  val bySha = localJars.filter { it.isFile }.distinct().associateBy { bazelBundleSha(it) }
  return records
    .mapNotNull { (coordinate, sha) -> bySha[sha]?.let { coordinate to it } }
    .distinctBy { it.first }
    .toMap()
}

/** `group:artifact:version`, the key [bundleLocalJars] uses. */
internal fun BundleReader.ClasspathEntry.Maven.gav(): String = "$group:$artifact:$version"

/**
 * `--local-jars <file>`: one jar path per line, relative to the working directory (a Bazel
 * execution root) or absolute. Blank lines are skipped.
 */
internal fun readLocalJarList(list: File): List<File> {
  require(list.isFile) { "--local-jars is not a file: ${list.path}" }
  return list.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { File(it).absoluteFile }
}

/** (coordinate, builtSha256) for each record of `dependency-resolution.json` that has both. */
private fun dependencyResolutionRecords(zipBytes: ByteArray): List<Pair<String, String>> {
  val text =
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
      generateSequence { zip.nextEntry }
        .firstOrNull { it.name == "dependency-resolution.json" }
        ?.let { zip.readBytes().decodeToString() }
    } ?: return emptyList()
  val array = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
  return array.mapNotNull { element ->
    val record = element as? JsonObject ?: return@mapNotNull null
    val coordinate = (record["coordinate"] as? JsonPrimitive)?.contentOrNull
    val sha = (record["builtSha256"] as? JsonPrimitive)?.contentOrNull?.lowercase()
    if (coordinate == null || sha == null) null else coordinate to sha
  }
}
