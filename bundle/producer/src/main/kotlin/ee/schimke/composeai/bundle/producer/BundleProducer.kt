package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Prefer published coordinates even when Bazel has transformed the original artifact bytes. */
public fun bazelMavenCoordinates(lockFiles: List<File>): List<BundleReader.ClasspathEntry.Maven> {
  val entries = mutableSetOf<BundleReader.ClasspathEntry.Maven>()
  lockFiles.forEach { file ->
    require(file.isFile) { "Maven lockfile missing: $file" }
    val artifacts =
      (bazelJson.parseToJsonElement(file.readText()) as? JsonObject)?.get("artifacts")
        as? JsonObject ?: error("Expected rules_jvm_external artifacts in $file")
    artifacts.forEach artifactLoop@{ (coordinate, value) ->
      val parts = coordinate.split(':')
      if (parts.size != 2 || parts.any { it.isBlank() }) return@artifactLoop
      val artifact = value as? JsonObject ?: return@artifactLoop
      val version = artifact["version"]?.jsonPrimitive?.contentOrNull ?: return@artifactLoop
      val sha =
        (artifact["shasums"] as? JsonObject)
          ?.get("jar")
          ?.jsonPrimitive
          ?.contentOrNull
          ?.lowercase()
          ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
      entries.add(BundleReader.ClasspathEntry.Maven(parts[0], parts[1], version, "jar", sha))
    }
  }
  return entries.toList()
}

public data class BazelMavenMatch(
  val coordinate: BundleReader.ClasspathEntry.Maven,
  val method: String,
)

public fun matchBazelMaven(
  jar: File,
  pins: List<BundleReader.ClasspathEntry.Maven>,
): BazelMavenMatch? = matchBazelMaven(jar, pins, versionStamps = false, ownerLabel = null)

/**
 * [matchBazelMaven] with the opt-in signals of a configured `dependencyPolicy` (#6016):
 * [ownerLabel], the `@repo//:group_artifact` target that produced the jar (`runtimeJarOwners`), is
 * tried first because a lockfile pin it names is exact; [versionStamps] reads an androidx
 * `META-INF/<group>_<artifact>.version` stamp after the content hash, before the weakest name-only
 * guess. With both off this is exactly the original identification.
 */
public fun matchBazelMaven(
  jar: File,
  pins: List<BundleReader.ClasspathEntry.Maven>,
  versionStamps: Boolean,
  ownerLabel: String?,
): BazelMavenMatch? {
  val path = jar.path.replace('\\', '/')
  val name = jar.name.removePrefix("processed_").removeSuffix(".jar")
  ownerLabel
    ?.let { matchOwnerLabel(it, pins) }
    ?.let {
      return BazelMavenMatch(it, "owner-label")
    }
  // Maven-layout paths retain group/version even after filename processing or relocation to
  // runfiles.
  pins
    .singleOrNull { path.contains("/${it.group.replace('.', '/')}/${it.artifact}/${it.version}/") }
    ?.let {
      return BazelMavenMatch(it, "lockfile-path")
    }
  pins
    .singleOrNull { name == "${it.artifact}-${it.version}" }
    ?.let {
      return BazelMavenMatch(it, "lockfile-name")
    }
  val segments = path.split('/')
  if (segments.size >= 4) {
    val artifact = segments[segments.size - 3]
    val version = segments[segments.size - 2]
    val groupStart = segments.indexOfFirst {
      it in setOf("org", "com", "androidx", "io", "net", "dev", "co", "ee")
    }
    if (
      groupStart >= 0 &&
        groupStart < segments.size - 3 &&
        name == "$artifact-$version" &&
        version.firstOrNull()?.isDigit() == true
    ) {
      val group = segments.subList(groupStart, segments.size - 3).joinToString(".")
      return BazelMavenMatch(
        BundleReader.ClasspathEntry.Maven(group, artifact, version, "jar"),
        "maven-layout",
      )
    }
  }
  ZipFile(jar).use { zip ->
    val properties =
      zip
        .entries()
        .asSequence()
        .filter { it.name.startsWith("META-INF/maven/") && it.name.endsWith("/pom.properties") }
        .mapNotNull { entry ->
          val props = java.util.Properties().apply { zip.getInputStream(entry).use { load(it) } }
          val group = props.getProperty("groupId") ?: return@mapNotNull null
          val artifact = props.getProperty("artifactId") ?: return@mapNotNull null
          val version = props.getProperty("version") ?: return@mapNotNull null
          pins.firstOrNull { it.group == group && it.artifact == artifact && it.version == version }
            ?: BundleReader.ClasspathEntry.Maven(group, artifact, version, "jar")
        }
        .toList()
        .distinct()
    properties.singleOrNull()?.let {
      return BazelMavenMatch(it, "pom-properties")
    }
    // Compiler toolchains often expose kotlin-stdlib.jar without a version in its filename.
    val manifest =
      zip.getEntry("META-INF/MANIFEST.MF")?.let {
        zip.getInputStream(it).use { stream -> java.util.jar.Manifest(stream) }
      }
    val pinnedArtifact =
      pins.filter { name == it.artifact }.distinctBy { it.group to it.artifact }.singleOrNull()
    if (pinnedArtifact != null) {
      val version =
        manifest?.mainAttributes?.getValue("Implementation-Version")?.let {
          Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:RC|Beta|alpha)[0-9]*)?").find(it)?.value
        }
      if (version != null)
        return BazelMavenMatch(
          pinnedArtifact.copy(
            version = version,
            sha256 = pinnedArtifact.sha256.takeIf { version == pinnedArtifact.version },
          ),
          "manifest-version",
        )
    }
  }
  // Versioned filenames can identify the same published artifact at a different version.
  val inferred =
    pins
      .mapNotNull { pin ->
        if (!name.startsWith(pin.artifact + "-")) return@mapNotNull null
        val version = name.removePrefix(pin.artifact + "-")
        if (!version.matches(Regex("[0-9]+\\.[0-9]+.*"))) return@mapNotNull null
        pin.copy(version = version, sha256 = pin.sha256.takeIf { pin.version == version })
      }
      .distinct()
  inferred.singleOrNull()?.let {
    return BazelMavenMatch(it, "artifact-name-version")
  }
  val builtHash = bazelBundleSha(jar)
  pins
    .filter { it.sha256 == builtHash }
    .distinct()
    .singleOrNull()
    ?.let {
      return BazelMavenMatch(it, "content-hash")
    }
  if (versionStamps) {
    versionStampCoordinate(jar, name, pins)?.let {
      return BazelMavenMatch(it, "version-stamp")
    }
  }
  pins
    .filter { name == it.artifact }
    .singleOrNull()
    ?.let {
      return BazelMavenMatch(it, "closest-lockfile-artifact")
    }
  return null
}

/**
 * The lockfile pin an external target label names. rules_jvm_external names a pinned artifact's
 * target after its escaped coordinate (`@maven//:androidx_compose_ui_ui_android`); the main
 * repository's own targets (`//app:ui`) name no coordinate. Null unless exactly one pin matches.
 */
internal fun matchOwnerLabel(
  label: String,
  pins: List<BundleReader.ClasspathEntry.Maven>,
): BundleReader.ClasspathEntry.Maven? {
  val match = Regex("^@@?([^/]+)//[^:]*:(.+)$").find(label.trim()) ?: return null
  if (match.groupValues[1].isEmpty()) return null
  fun escape(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]"), "_")
  val target = escape(match.groupValues[2])
  return pins.filter { escape("${it.group}:${it.artifact}") == target }.distinct().singleOrNull()
}

/**
 * The coordinate an androidx `META-INF/<group>_<artifact>.version` stamp names. The stamp names the
 * KMP root artifact (`androidx.compose.ui_ui`) while the runtime jar is usually a platform variant
 * (`ui-android`), so the artifact is chosen by evidence, in order: the jar's own file name
 * (`ui-android.jar`, `ui-android-1.14.0-alpha01.jar`, `libui-android.jar`), then a lockfile pin of
 * the same group for exactly one of `<a>`, `<a>-android`, `<a>-jvm`, `<a>-desktop`, else the root
 * artifact as stamped. The checksum is a pin's only when that exact version is pinned.
 */
internal fun versionStampCoordinate(
  jar: File,
  name: String,
  pins: List<BundleReader.ClasspathEntry.Maven>,
): BundleReader.ClasspathEntry.Maven? {
  val stamps =
    ZipFile(jar).use { zip ->
      zip
        .entries()
        .asSequence()
        .filter { !it.isDirectory }
        .mapNotNull { entry ->
          val file =
            Regex("^META-INF/([^/]+)\\.version$").find(entry.name)?.groupValues?.get(1)
              ?: return@mapNotNull null
          val split = file.indexOf('_')
          if (split <= 0) return@mapNotNull null
          val group = file.substring(0, split)
          val artifact = file.substring(split + 1)
          // `kotlinx_coroutines_core.version` and friends are not group_artifact stamps.
          if ('.' !in group || '_' in artifact || artifact.isEmpty()) return@mapNotNull null
          val version = zip.getInputStream(entry).use { it.readBytes().decodeToString() }.trim()
          if (!version.matches(Regex("[0-9]+\\.[0-9]+[0-9A-Za-z.+-]*"))) return@mapNotNull null
          Triple(group, artifact, version)
        }
        .distinct()
        .toList()
    }
  val (group, root, version) = stamps.singleOrNull() ?: return null
  val candidates = listOf(root, "$root-android", "$root-jvm", "$root-desktop")
  val bare = name.removePrefix("lib")
  val byName =
    candidates
      .sortedByDescending { it.length }
      .firstOrNull { candidate ->
        listOf(name, bare).any {
          it == candidate ||
            (it.startsWith("$candidate-") &&
              it.removePrefix("$candidate-").firstOrNull()?.isDigit() == true)
        }
      }
  val artifact =
    byName
      ?: pins
        .filter { it.group == group && it.artifact in candidates }
        .map { it.artifact }
        .distinct()
        .singleOrNull()
      ?: root
  val sha =
    pins
      .firstOrNull { it.group == group && it.artifact == artifact && it.version == version }
      ?.sha256
  return BundleReader.ClasspathEntry.Maven(group, artifact, version, "jar", sha)
}

public fun bazelBundleSha(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val size = input.read(buffer)
      if (size < 0) break
      digest.update(buffer, 0, size)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Keep complete own bytecode/resources; reflection makes reachability-only pruning unsafe. */
public fun bazelBundleAppJar(jars: List<File>): ByteArray {
  val contents = linkedMapOf<String, ByteArray>()
  jars.forEach { file ->
    ZipFile(file).use { zip ->
      zip
        .entries()
        .asSequence()
        .filter { !it.isDirectory }
        .forEach { entry ->
          val name = entry.name
          require(!name.startsWith('/') && name.split('/').none { it == ".." }) {
            "Invalid application JAR entry: $name"
          }
          if (
            name.equals("META-INF/MANIFEST.MF", true) ||
              Regex("META-INF/[^/]+\\.(SF|RSA|DSA)", RegexOption.IGNORE_CASE).matches(name)
          )
            return@forEach
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          val previous = contents.putIfAbsent(name, bytes)
          require(previous == null || previous.contentEquals(bytes)) {
            "Conflicting application JAR entry: $name"
          }
        }
    }
  }
  return bazelBundleZip(contents)
}

private fun bazelBundleZip(contents: Map<String, ByteArray>): ByteArray =
  ByteArrayOutputStream().use { bytes ->
    ZipOutputStream(bytes).use { zip ->
      contents.toSortedMap().forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0)) })
        zip.write(content)
        zip.closeEntry()
      }
    }
    bytes.toByteArray()
  }

public fun writeBazelBundle(
  modulePath: String,
  projectJars: List<File>,
  runtimeJars: List<File>,
  discovery: PreviewManifest,
  renders: File?,
  selectedIds: Set<String>,
  lockFiles: List<File>,
  output: File,
  embedDeps: Boolean = false,
): BundleReader.Manifest =
  writeBazelBundle(
    modulePath,
    projectJars,
    runtimeJars,
    discovery,
    renders,
    selectedIds,
    lockFiles,
    output,
    embedDeps,
    android = null,
  )

/**
 * The Android resources an Android Bazel target renders against: the linked, standalone resource
 * APK, the manifest Robolectric reads the package and theme from, and the final-ID R classes.
 * Packed under `android/` exactly where a Gradle `backend="android"` bundle carries them, so
 * `bundle render` and `bundle daemon` replay both the same way.
 */
public class BazelAndroidResources(
  public val packageName: String,
  public val resourceApk: File,
  public val manifest: File,
  public val rJar: File?,
)

/** [writeBazelBundle] for an Android target when [android] is set: `backend="android"`. */
public fun writeBazelBundle(
  modulePath: String,
  projectJars: List<File>,
  runtimeJars: List<File>,
  discovery: PreviewManifest,
  renders: File?,
  selectedIds: Set<String>,
  lockFiles: List<File>,
  output: File,
  embedDeps: Boolean,
  android: BazelAndroidResources?,
): BundleReader.Manifest =
  writeBazelBundle(
    modulePath,
    projectJars,
    runtimeJars,
    discovery,
    renders,
    selectedIds,
    lockFiles,
    output,
    embedDeps,
    android,
    DependencyResolutionOptions.DEFAULT,
  )

/**
 * [writeBazelBundle] under a `dependencyPolicy` (#6016): [dependencies] decides, per runtime jar,
 * whether it travels as a coordinate (possibly a substituted, approximate one) or embedded.
 * [embedDeps] still embeds everything. With [DependencyResolutionOptions.DEFAULT] the bundle is
 * byte-for-byte what it was before policies existed.
 */
public fun writeBazelBundle(
  modulePath: String,
  projectJars: List<File>,
  runtimeJars: List<File>,
  discovery: PreviewManifest,
  renders: File?,
  selectedIds: Set<String>,
  lockFiles: List<File>,
  output: File,
  embedDeps: Boolean,
  android: BazelAndroidResources?,
  dependencies: DependencyResolutionOptions,
): BundleReader.Manifest {
  val policy = dependencies.policy
  val owners = dependencies.runtimeJarOwners.mapKeys { it.key.absoluteFile.normalize() }
  var substituted = false
  val previews = discovery.previews.filter { it.id in selectedIds }.sortedBy { it.id }
  require(previews.isNotEmpty()) { "No previews selected for bundle" }
  val known = bazelMavenCoordinates(lockFiles)
  val appJar = bazelBundleAppJar(projectJars)
  val contents = linkedMapOf("classes/app.jar" to appJar)
  val androidResources = android?.let {
    contents[ANDROID_RESOURCE_APK] = it.resourceApk.readBytes()
    contents[ANDROID_MANIFEST] = it.manifest.readBytes()
    it.rJar?.let { jar -> contents[ANDROID_R_CLASSES] = jar.readBytes() }
    BundleReader.AndroidResources(
      resourceApkPath = ANDROID_RESOURCE_APK,
      mergedManifestPath = ANDROID_MANIFEST,
      rClassesJarPath = ANDROID_R_CLASSES.takeIf { _ -> it.rJar != null },
      applicationPackage = it.packageName,
    )
  }
  val classpath =
    mutableListOf<BundleReader.ClasspathEntry>(
      BundleReader.ClasspathEntry.Module("classes/app.jar")
    )
  val decisions = mutableListOf<kotlinx.serialization.json.JsonObject>()
  // The R classes travel as `android/r-classes.jar`, which the Android replay puts on the `-cp`.
  runtimeJars
    .filter { it !in projectJars && it != android?.rJar }
    .forEachIndexed { index, jar ->
      val sha = bazelBundleSha(jar)
      val match =
        matchBazelMaven(
          jar,
          known,
          versionStamps = policy.versionStamps,
          ownerLabel = if (policy.ownerLabels) owners[jar.absoluteFile.normalize()] else null,
        )
      val outcome =
        applyDependencyPolicy(match, sha, known, policy, dependencies.releasedVersions, embedDeps)
      // The record keeps naming what the jar was identified as; a substitution names both.
      val coordinate = outcome.coordinate ?: match?.coordinate
      if (outcome.substitutedFrom != null) substituted = true
      decisions.add(
        kotlinx.serialization.json.buildJsonObject {
          put("jar", kotlinx.serialization.json.JsonPrimitive(jar.name))
          put(
            "coordinate",
            kotlinx.serialization.json.JsonPrimitive(
              coordinate?.let { "${it.group}:${it.artifact}:${it.version}" }
            ),
          )
          put(
            "method",
            kotlinx.serialization.json.JsonPrimitive(match?.method ?: "unidentified-embedded"),
          )
          put("builtSha256", kotlinx.serialization.json.JsonPrimitive(sha))
          put("publishedSha256", kotlinx.serialization.json.JsonPrimitive(coordinate?.sha256))
          // Only a configured policy extends the record, so a default bundle stays byte-identical.
          if (policy.explicit) {
            put("rule", kotlinx.serialization.json.JsonPrimitive(outcome.rule))
            put("policy", kotlinx.serialization.json.JsonPrimitive(outcome.policy))
            put(
              "fallbacks",
              kotlinx.serialization.json.JsonArray(
                outcome.fallbacks.map {
                  kotlinx.serialization.json.buildJsonObject {
                    put("policy", kotlinx.serialization.json.JsonPrimitive(it.policy))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(it.reason))
                  }
                }
              ),
            )
            put(
              "substitutedFrom",
              kotlinx.serialization.json.JsonPrimitive(outcome.substitutedFrom?.gav()),
            )
            put("reason", kotlinx.serialization.json.JsonPrimitive(outcome.reason))
          }
        }
      )
      if (outcome.coordinate != null) classpath.add(outcome.coordinate)
      else {
        val path = "libs/$index-${sha.take(16)}.jar"
        contents[path] = jar.readBytes()
        classpath.add(BundleReader.ClasspathEntry.Embedded(path))
      }
    }
  val maven = classpath.any { it is BundleReader.ClasspathEntry.Maven }
  val embedded = classpath.any { it is BundleReader.ClasspathEntry.Embedded }
  val manifest =
    BundleReader.Manifest(
      schemaVersion = 9,
      backend = if (android != null) "android" else "desktop",
      previewIds = previews.map { it.id },
      rawPreviewIds = previews.map { it.id },
      coverPreviewId = previews.first().id,
      classpath = classpath,
      modulePath = modulePath,
      producedBy = "compose-preview Bazel bundle",
      producer = "bazel",
      // A substituted coordinate is not the built bytes: say so where every reader looks.
      resolution =
        if (substituted) "approximate"
        else if (maven && embedded) "mixed" else if (maven) "coordinates" else "embedded",
      androidResources = androidResources,
    )
  contents["dependency-resolution.json"] =
    canonicalJsonBytes(kotlinx.serialization.json.JsonArray(decisions).toString())
  contents["bundle.json"] = canonicalJsonBytes(bazelJson.encodeToString(manifest))
  contents["previews.json"] =
    canonicalJsonBytes(bazelJson.encodeToString(discovery.copy(previews = previews)))
  previews.forEach { preview ->
    require(!preview.id.contains('/') && !preview.id.contains('\\')) {
      "Invalid bundle preview ID: ${preview.id}"
    }
    val capture = preview.captures.firstOrNull() ?: error("Preview has no capture: ${preview.id}")
    if (renders == null) return@forEach
    val png = renders.resolve(capture.renderOutput)
    require(png.isFile) { "Rendered capture missing: $png" }
    contents["previews/${preview.id}.png"] = png.readBytes()
  }
  // All classpath inputs are retained, including runtime-only/reflection dependencies.
  output.parentFile?.mkdirs()
  output.outputStream().use { stream ->
    stream.write(contents["previews/${previews.first().id}.png"] ?: stubCover)
    stream.write(bazelBundleZip(contents))
  }
  return manifest
}

private const val ANDROID_RESOURCE_APK = "android/resources.ap_"
private const val ANDROID_MANIFEST = "android/AndroidManifest.xml"
private const val ANDROID_R_CLASSES = "android/r-classes.jar"

private val bazelJson = Json {
  prettyPrint = true
  encodeDefaults = true
  ignoreUnknownKeys = true
}

// A valid one-pixel PNG identifies an unrendered runnable bundle without a renderer dependency.
private val stubCover =
  java.util.Base64.getDecoder()
    .decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
    )

/**
 * Add required baked images to a prepared bundle, preserving its runtime and discovery metadata.
 */
public fun bakePreparedBundle(bundle: File, renders: File, output: File): Unit {
  val manifest = BundleReader.readMetadata(bundle).manifest
  val contents = linkedMapOf<String, ByteArray>()
  java.util.zip
    .ZipInputStream(java.io.ByteArrayInputStream(BundleReader.extractZipBytes(bundle)))
    .use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) {
          "Unsafe bundle entry"
        }
        if (!entry.isDirectory) contents[entry.name] = zip.readBytes()
      }
    }
  require(manifest.previewIds.map(::previewPngName).distinct().size == manifest.previewIds.size) {
    "Preview filenames collide"
  }
  // The Android renderer names a capture by its `renderOutput` leaf rather than [previewPngName].
  val leaves = renderOutputLeaves(contents["previews.json"])
  manifest.previewIds.forEach { id ->
    require(!id.contains('/') && !id.contains('\\')) { "Unsafe preview ID: $id" }
    val png =
      listOfNotNull(previewPngName(id), leaves[id])
        .map { renders.resolve(it) }
        .firstOrNull { it.isFile } ?: renders.resolve(previewPngName(id))
    require(png.isFile) { "Rendered capture missing: $png" }
    contents["previews/$id.png"] = png.readBytes()
  }
  output.parentFile?.mkdirs()
  output.outputStream().use { stream ->
    stream.write(
      contents.getValue("previews/${manifest.coverPreviewId ?: manifest.previewIds.first()}.png")
    )
    stream.write(bazelBundleZip(contents))
  }
}

/**
 * Each preview's primary capture file name (its `renderOutput` leaf) from a bundle's
 * `previews.json`, the name the Android renderer writes; only safe single-segment names are kept.
 */
internal fun renderOutputLeaves(previewsJson: ByteArray?): Map<String, String> {
  if (previewsJson == null) return emptyMap()
  val root = bazelJson.parseToJsonElement(previewsJson.decodeToString()) as? JsonObject
  val previews = root?.get("previews") as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
  return previews
    .mapNotNull { element ->
      val preview = element as? JsonObject ?: return@mapNotNull null
      val id = preview["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val capture =
        (preview["captures"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
      val leaf =
        capture?.get("renderOutput")?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')
          ?: return@mapNotNull null
      if (leaf.isEmpty() || leaf == "." || leaf == ".." || '\\' in leaf) null else id to leaf
    }
    .toMap()
}

/** Filename shared by the existing bundle renderer and offline baked-image packaging. */
public fun previewPngName(id: String): String =
  id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("") + ".png"

// Object member order is not semantic. Arrays (particularly classpaths/captures) retain order.
private fun canonicalJsonBytes(text: String): ByteArray {
  fun normalize(
    value: kotlinx.serialization.json.JsonElement
  ): kotlinx.serialization.json.JsonElement =
    when (value) {
      is kotlinx.serialization.json.JsonObject ->
        kotlinx.serialization.json.JsonObject(value.toSortedMap().mapValues { normalize(it.value) })
      is kotlinx.serialization.json.JsonArray ->
        kotlinx.serialization.json.JsonArray(value.map(::normalize))
      else -> value
    }
  return normalize(bazelJson.parseToJsonElement(text)).toString().toByteArray(Charsets.UTF_8)
}
