package ee.schimke.composeai.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Builds the per-preview Watch Face Format source bundle (`previews/<id>.wff.zip`) and its
 * provenance manifest (`previews/<id>.wff.json`) for a `<WatchFace>` preview, so a detached
 * consumer can repackage the face (for example into a Watch Face Push APK) without the source
 * checkout. The format is documented in `docs/portable-bundles.md` → "Watch Face Format source
 * bundle"; consumers decode the manifest with `ee.schimke.composeai.bundle.WffSourceManifest`.
 *
 * Everything here is best effort: a missing manifest, git checkout or licence leaves the matching
 * field null rather than failing the pack, and nothing is filtered by licence.
 */
internal object WffSourceBundle {

  /** The zip plus its manifest, both ready to write into the bundle. */
  class Packed(val zip: ByteArray, val manifest: Manifest)

  /** Mirrors `ee.schimke.composeai.bundle.WffSourceManifest`; keep the two in lockstep. */
  @Serializable
  data class Manifest(
    val schemaVersion: Int = 1,
    val previewId: String,
    val bundle: String,
    val wffVersion: Int? = null,
    val repoUrl: String? = null,
    val commit: String? = null,
    val sourcePath: String? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
    val licenseFile: String? = null,
    val files: List<String> = emptyList(),
  )

  /** Where the module's sources came from. Every field is independently optional. */
  data class Provenance(
    val repoUrl: String? = null,
    val commit: String? = null,
    /** The git work tree root, used for repository-relative paths and the licence lookup. */
    val repoRoot: File? = null,
  )

  private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
  }

  fun encodeManifest(manifest: Manifest): ByteArray =
    JSON.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

  /** Resource types carried into the zip; anything else under a res root is ignored. */
  private val CARRIED_TYPES = setOf("raw", "xml", "values", "drawable", "mipmap", "font")

  /** Types carried whole (they're small and name the rest); others only when referenced. */
  private val ALWAYS_TYPES = setOf("values", "xml")

  /**
   * A `<WatchFace>` element — a face document or a shape map's entry — but not `<WatchFaceInfo>`,
   * which `res/xml/watch_face_info.xml` holds and every face needs.
   */
  private val WATCH_FACE_ROOT = Regex("<WatchFace\\b")

  private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

  /**
   * Packs the face at [dwfAssetPath] (resource-root relative, e.g. `raw/watchface.xml`) found under
   * one of [resRoots]. Returns null when no root contains it.
   *
   * The face always lands at `res/raw/watchface.xml`, the path WFF requires, whatever it was called
   * in the module; [Manifest.sourcePath] keeps the original. Other `<WatchFace>` documents in the
   * same module are left out, so each preview's zip is exactly one face.
   */
  fun pack(
    previewId: String,
    bundlePath: String,
    dwfAssetPath: String,
    resRoots: List<File>,
    androidManifests: List<File>,
    provenance: Provenance,
  ): Packed? {
    val roots = resRoots.filter { it.isDirectory }.distinctBy { it.absoluteFile.normalize() }
    val faceRoot = roots.firstOrNull { File(it, dwfAssetPath).isFile } ?: return null
    val face = File(faceRoot, dwfAssetPath)
    val orderedRoots = listOf(faceRoot) + (roots - faceRoot)

    // Candidate resources by `<type-dir>/<file>`, first root wins (the face's own root first).
    val candidates = LinkedHashMap<String, File>()
    for (root in orderedRoots) {
      root
        .listFiles()
        ?.sortedBy { it.name }
        ?.forEach { typeDir ->
          if (!typeDir.isDirectory) return@forEach
          val type = typeDir.name.substringBefore('-')
          if (type !in CARRIED_TYPES) return@forEach
          typeDir
            .listFiles()
            ?.sortedBy { it.name }
            ?.forEach { file ->
              if (file.isFile) candidates.putIfAbsent("${typeDir.name}/${file.name}", file)
            }
        }
    }
    // The face itself, every other face, and any shape map (`res/xml/watch_face_shapes.xml`,
    // `<WatchFaces><WatchFace file="@raw/…"/>`) are never carried as plain resources: the zip is
    // exactly one face at `res/raw/watchface.xml`, and a shape map would name faces it lacks.
    val faceRel = dwfAssetPath.replace('\\', '/')
    candidates.remove(faceRel)
    candidates.entries.removeAll { (_, file) ->
      file.extension == "xml" && isWatchFaceDocument(file)
    }

    val manifestFile = androidManifests.firstOrNull { it.isFile }
    val included = LinkedHashMap<String, File>()
    val tokens = HashSet<String>()
    fun scan(file: File) {
      if (!file.extension.equals("xml", ignoreCase = true)) return
      val text = runCatching { file.readText() }.getOrNull() ?: return
      IDENTIFIER.findAll(text).forEach { tokens += it.value }
    }
    scan(face)
    manifestFile?.let(::scan)
    for ((rel, file) in candidates) {
      if (rel.substringBefore('/').substringBefore('-') in ALWAYS_TYPES) {
        included[rel] = file
        scan(file)
      }
    }
    // Fixed point: a referenced font family XML or drawable XML can name further resources.
    var changed = true
    while (changed) {
      changed = false
      for ((rel, file) in candidates) {
        if (rel in included) continue
        if (file.name.substringBefore('.') in tokens) {
          included[rel] = file
          scan(file)
          changed = true
        }
      }
    }

    val repoRoot = provenance.repoRoot
    val licenseFile = repoRoot?.let(::findLicenseFile)
    val entries = sortedMapOf<String, ByteArray>()
    entries["res/raw/watchface.xml"] = face.readBytes()
    included.forEach { (rel, file) -> entries["res/$rel"] = file.readBytes() }
    manifestFile?.let { entries["AndroidManifest.xml"] = it.readBytes() }
    licenseFile?.let { entries["LICENSE"] = it.readBytes() }

    val sourcePath = repoRoot?.let { face.relativeToOrNull(it)?.invariantSeparatorsPath }
    val manifest =
      Manifest(
        previewId = previewId,
        bundle = bundlePath,
        wffVersion = manifestFile?.let { wffVersion(it, orderedRoots) },
        repoUrl = provenance.repoUrl,
        commit = provenance.commit,
        sourcePath = sourcePath,
        sourceUrl = sourceUrl(provenance.repoUrl, provenance.commit, sourcePath),
        license = licenseFile?.let { runCatching { detectSpdx(it.readText()) }.getOrNull() },
        licenseFile =
          licenseFile?.let { f -> repoRoot.let { f.relativeTo(it).invariantSeparatorsPath } },
        files = entries.keys.toList(),
      )
    return Packed(zip(entries), manifest)
  }

  private fun isWatchFaceDocument(file: File): Boolean = runCatching {
    WATCH_FACE_ROOT.containsMatchIn(file.readText())
  }
    .getOrDefault(false)

  private fun zip(entries: Map<String, ByteArray>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      for ((path, bytes) in entries) {
        val entry = ZipEntry(path)
        // Fixed timestamp so the nested zip, and so the bundle, is byte-stable across packs.
        entry.time = ZIP_EPOCH_MS
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  /** 1980-01-01, as `BundlePreviewTask.ZIP_DOS_EPOCH_MS`. */
  private val ZIP_EPOCH_MS: Long =
    java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis

  private const val WFF_VERSION_PROPERTY = "com.google.wear.watchface.format.version"

  /**
   * `com.google.wear.watchface.format.version` from a `<property>` or `<meta-data>` element of
   * [manifest], resolving an `@integer/<name>` value against the `values*` resources in [resRoots].
   */
  internal fun wffVersion(manifest: File, resRoots: List<File>): Int? {
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    val element =
      Regex("<(?:property|meta-data)\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .firstOrNull { attribute(it.value, "name") == WFF_VERSION_PROPERTY } ?: return null
    val value = attribute(element.value, "value")?.trim() ?: return null
    value.toIntOrNull()?.let {
      return it
    }
    val name =
      value.removePrefix("@integer/").takeIf { value.startsWith("@integer/") } ?: return null
    val pattern = Regex("<integer\\s+name=\"${Regex.escape(name)}\"\\s*>\\s*(\\d+)\\s*</integer>")
    for (root in resRoots) {
      root
        .listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("values") }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
          dir
            .listFiles()
            ?.filter { it.extension == "xml" }
            ?.sortedBy { it.name }
            ?.forEach { f ->
              pattern.find(runCatching { f.readText() }.getOrDefault(""))?.let {
                return it.groupValues[1].toInt()
              }
            }
        }
    }
    return null
  }

  private fun attribute(tag: String, name: String): String? =
    Regex("\\b(?:android:)?$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

  internal fun sourceUrl(repoUrl: String?, commit: String?, sourcePath: String?): String? {
    if (repoUrl == null || commit == null || sourcePath == null) return null
    if (!repoUrl.startsWith("https://github.com/")) return null
    return "$repoUrl/blob/$commit/$sourcePath"
  }

  private val LICENSE_NAMES =
    listOf(
      "LICENSE",
      "LICENSE.md",
      "LICENSE.txt",
      "LICENCE",
      "LICENCE.md",
      "LICENCE.txt",
      "COPYING",
      "COPYING.md",
      "COPYING.txt",
      "UNLICENSE",
    )

  /** The repository's top-level licence file, matched case-insensitively, or null. */
  internal fun findLicenseFile(repoRoot: File): File? {
    val files = repoRoot.listFiles()?.filter { it.isFile } ?: return null
    for (name in LICENSE_NAMES) {
      files
        .firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.let {
          return it
        }
    }
    return null
  }

  /**
   * The SPDX id of a licence text, from the phrases each licence's canonical text carries, or null
   * when it matches none. Conservative: an unrecognised or ambiguous text is null, never a guess.
   */
  internal fun detectSpdx(text: String): String? {
    val t = text.replace(Regex("\\s+"), " ").lowercase()
    fun has(vararg phrases: String) = phrases.all { it in t }
    return when {
      has("apache license", "version 2.0") -> "Apache-2.0"
      has("this is free and unencumbered software released into the public domain") -> "Unlicense"
      has("cc0 1.0 universal") -> "CC0-1.0"
      has("creative commons", "attribution 4.0 international") &&
        listOf("noncommercial", "sharealike", "share-alike", "noderivatives").none { it in t } ->
        "CC-BY-4.0"
      has("mozilla public license", "version 2.0") -> "MPL-2.0"
      has("gnu lesser general public license", "version 3") -> "LGPL-3.0"
      has("gnu lesser general public license", "version 2.1") -> "LGPL-2.1"
      has("gnu affero general public license", "version 3") -> "AGPL-3.0"
      has("gnu general public license", "version 3") -> "GPL-3.0"
      has("gnu general public license", "version 2") -> "GPL-2.0"
      has("eclipse public license", "2.0") -> "EPL-2.0"
      has("permission is hereby granted, free of charge") -> "MIT"
      has("redistribution and use in source and binary forms") ->
        when {
          "neither the name" in t || "names of its contributors" in t -> "BSD-3-Clause"
          else -> "BSD-2-Clause"
        }
      has("isc license") ||
        has("permission to use, copy, modify, and/or distribute this software") -> "ISC"
      else -> null
    }
  }

  /**
   * Reads the git checkout containing [dir] without running git: the work tree root, `HEAD`'s
   * commit (detached, a loose ref or `packed-refs`) and the `origin` remote, normalised to an
   * `https://` URL with any credentials removed. Fields that cannot be read are null.
   */
  fun gitProvenance(dir: File): Provenance {
    var root: File? = dir.absoluteFile
    while (root != null && !File(root, ".git").exists()) root = root.parentFile
    if (root == null) return Provenance()
    val dotGit = File(root, ".git")
    val gitDir =
      if (dotGit.isDirectory) dotGit
      else {
        val pointer = runCatching { dotGit.readText().trim() }.getOrNull()
        pointer
          ?.takeIf { it.startsWith("gitdir:") }
          ?.removePrefix("gitdir:")
          ?.trim()
          ?.let { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } }
      } ?: return Provenance(repoRoot = root)
    // A linked worktree keeps HEAD in its own dir but refs and config in the common dir.
    val commonDir =
      File(gitDir, "commondir")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.let { File(it).let { f -> if (f.isAbsolute) f else File(gitDir, it) } } ?: gitDir
    return Provenance(
      repoUrl = originUrl(File(commonDir, "config")),
      commit = headCommit(gitDir, commonDir),
      repoRoot = root,
    )
  }

  private val SHA = Regex("^[0-9a-f]{40}$")

  private fun headCommit(gitDir: File, commonDir: File): String? {
    val head = runCatching { File(gitDir, "HEAD").readText().trim() }.getOrNull() ?: return null
    if (SHA.matches(head)) return head
    val ref = head.removePrefix("ref:").trim().takeIf { head.startsWith("ref:") } ?: return null
    for (base in listOf(gitDir, commonDir)) {
      val loose = File(base, ref)
      if (loose.isFile) return loose.readText().trim().takeIf(SHA::matches)
    }
    val packed = File(commonDir, "packed-refs").takeIf { it.isFile } ?: return null
    return packed.readLines().firstNotNullOfOrNull { line ->
      val parts = line.trim().split(' ')
      if (parts.size == 2 && parts[1] == ref && SHA.matches(parts[0])) parts[0] else null
    }
  }

  private fun originUrl(config: File): String? {
    val lines = runCatching { config.readLines() }.getOrNull() ?: return null
    var inOrigin = false
    for (raw in lines) {
      val line = raw.trim()
      if (line.startsWith("[")) {
        inOrigin = Regex("^\\[remote\\s+\"origin\"\\]$").matches(line)
        continue
      }
      if (inOrigin && line.startsWith("url")) {
        val value = line.substringAfter('=', "").trim()
        if (value.isNotEmpty()) return normalizeRemoteUrl(value)
      }
    }
    return null
  }

  /**
   * `git@host:owner/repo.git`, `ssh://git@host/owner/repo` and
   * `https://user:token@host/owner/repo.git` all become `https://host/owner/repo`. Anything else (a
   * local path, an unknown scheme) is null: the manifest is published, so it only ever carries a
   * public-looking URL.
   */
  internal fun normalizeRemoteUrl(url: String): String? {
    val scp = Regex("^[A-Za-z0-9._-]+@([A-Za-z0-9.-]+):(?!/)(.+)$").find(url)
    val (host, path) =
      when {
        scp != null -> scp.groupValues[1] to scp.groupValues[2]
        else -> {
          val m =
            Regex("^(?:https?|ssh|git)://(?:[^@/]*@)?([A-Za-z0-9.-]+)(?::\\d+)?/(.+)$").find(url)
              ?: return null
          m.groupValues[1] to m.groupValues[2]
        }
      }
    val clean = path.trim('/').removeSuffix(".git").trim('/')
    if (clean.isEmpty() || ".." in clean.split('/')) return null
    return "https://${host.lowercase()}/$clean"
  }
}

/**
 * `"<repoUrl> <commit>"` of the git checkout containing [Params.directory], re-read on every build
 * so a new commit re-packs an otherwise unchanged bundle (the WFF manifest records the commit).
 * Empty outside a git checkout.
 */
internal abstract class WffSourceRevisionValueSource :
  ValueSource<String, WffSourceRevisionValueSource.Params> {
  interface Params : ValueSourceParameters {
    val directory: Property<File>
  }

  override fun obtain(): String {
    val provenance = WffSourceBundle.gitProvenance(parameters.directory.get())
    return "${provenance.repoUrl.orEmpty()} ${provenance.commit.orEmpty()}".trim()
  }
}
