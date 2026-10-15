package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.discovery.PreviewDiscovery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Offline executable adapter. Build systems supply metadata plus materialized, declared inputs. */
public object BundleProducerCli {
  @JvmStatic
  public fun main(args: Array<String>) {
    require(args.size % 2 == 0) {
      "Expected --artifacts file --output bundle.png [--maven-install file] [--filter text] " +
        "[--renders directory] [--dependency-policy file] [--metadata-cache directory]"
    }
    val pairs = args.toList().chunked(2)
    val flags = pairs.associate { it[0] to it[1] }
    require(
      flags.keys.all {
        it in
          setOf(
            "--artifacts",
            "--output",
            "--maven-install",
            "--filter",
            "--renders",
            "--bundle",
            "--dependency-policy",
            "--metadata-cache",
          )
      }
    ) {
      "Unknown producer option"
    }
    if ("--bundle" in flags) {
      require(flags.keys.all { it in setOf("--bundle", "--renders", "--output") }) {
        "Baking takes only --bundle, --renders and --output"
      }
      bakePreparedBundle(
        File(flags.getValue("--bundle")),
        File(flags.getValue("--renders")),
        File(flags.getValue("--output")),
      )
      return
    }
    val json = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }
    val metadata =
      json.parseToJsonElement(File(flags.getValue("--artifacts")).readText()).jsonObject
    // Schema 2 is schema 1 plus the `android` block an Android aspect writes (the linked resource
    // APK, the manifest it was linked against and the final-ID R classes); it makes the bundle an
    // `backend="android"` one, exactly as `compose-preview bazel bundle` does.
    val schema = metadata.getValue("schemaVersion").jsonPrimitive.content
    // The artifacts contract, as the CLI's Bazel importer enforces it: the `android` block is
    // present exactly for schema 2, so malformed metadata can't render on the wrong backend.
    when (schema) {
      "1" ->
        require("android" !in metadata) {
          "schemaVersion 1 artifacts cannot carry an android block"
        }
      "2" -> require("android" in metadata) { "schemaVersion 2 artifacts need an android block" }
      else -> throw IllegalArgumentException("Unsupported artifacts schema $schema")
    }
    fun files(key: String): List<File> =
      metadata[key]
        ?.jsonArray
        .orEmpty()
        .map { File(it.jsonPrimitive.content) }
        .distinct()
        .also { paths -> require(paths.all { it.isFile }) { "Unmaterialized $key: $paths" } }
    val project = files("projectJars")
    val runtime = files("runtimeJars")
    require(project.isNotEmpty() && runtime.containsAll(project)) {
      "Runtime must include all project jars"
    }
    val module = metadata.getValue("target").jsonPrimitive.content
    val root = File(".").canonicalFile
    val android = if (schema == "2") androidResources(metadata) else null
    val result =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = emptyList(),
          projectClassJars = project,
          dependencyJars = runtime.filter { it !in project },
          sourceFiles = files("sourceFiles"),
          moduleName = module,
          variantName = metadata.getValue("configuration").jsonPrimitive.content,
          projectDirectory = root,
          failOnEmpty = true,
          catalogRenderSupported = false,
          animatedPreviewApngSupported = true,
        )
      )
    check(result is PreviewDiscovery.Outcome.Success) { "Preview discovery failed: $result" }
    // Discovery uses absolute source paths for editor navigation. Portable preparation must not
    // encode a sandbox or checkout location; retain execution-root-relative source identity.
    val portable =
      result.manifest.copy(
        previews =
          result.manifest.previews.map { preview ->
            preview.copy(
              sourceFile =
                preview.sourceFile?.let { path ->
                  if (!File(path).isAbsolute) path.replace('\\', '/')
                  else {
                    val source =
                      files("sourceFiles").firstOrNull {
                        it.canonicalFile == File(path).canonicalFile
                      } ?: error("Discovery returned an undeclared absolute source: $path")
                    source.absoluteFile.toPath().normalize().let {
                      root.toPath().relativize(it).toString().replace('\\', '/')
                    }
                  }
                }
            )
          }
      )
    val selected =
      portable.previews
        .filter { flags["--filter"]?.let { filter -> it.id.contains(filter, true) } ?: true }
        .map { it.id }
        .toSet()
    // A Bazel action is hermetic: release lookups read only a declared, pre-populated cache.
    val dependencies =
      DependencyResolutionOptions.Builder()
        .apply {
          flags["--dependency-policy"]?.let { policy = readDependencyPolicy(File(it)) }
          flags["--metadata-cache"]?.let {
            releasedVersions = MavenMetadataVersions(File(it), network = false)
          }
          runtimeJarOwners = runtimeJarOwners(metadata, File(".").absoluteFile)
        }
        .build()
    writeBazelBundle(
      module,
      project,
      runtime,
      portable,
      flags["--renders"]?.let(::File),
      selected,
      pairs.filter { it[0] == "--maven-install" }.map { File(it[1]) },
      File(flags.getValue("--output")),
      embedDeps = false,
      android = android,
      dependencies = dependencies,
    )
  }
}

/**
 * The linked resources of a schema-2 artifacts file's `android` block, or null when it has none.
 * Paths are execution-root relative, like every other path in the file, and must be materialized.
 */
internal fun androidResources(artifacts: JsonObject): BazelAndroidResources? {
  val android = artifacts["android"] as? JsonObject ?: return null
  fun string(key: String): String? = (android[key] as? JsonPrimitive)?.contentOrNull
  fun file(key: String): File? =
    string(key)?.let(::File)?.also { require(it.isFile) { "Unmaterialized android.$key: $it" } }
  return BazelAndroidResources(
    packageName = requireNotNull(string("packageName")) { "android.packageName missing" },
    resourceApk = requireNotNull(file("resourceApk")) { "android.resourceApk missing" },
    manifest = requireNotNull(file("manifest")) { "android.manifest missing" },
    rJar = file("rJar"),
  )
}

/**
 * A `dependencyPolicy` from [file]: either the block itself or a config holding one under
 * `dependencyPolicy` (so `.compose-preview/bazel.json` can be passed as-is).
 */
public fun readDependencyPolicy(file: File): DependencyPolicy {
  require(file.isFile) { "Dependency policy missing: $file" }
  val json = Json.parseToJsonElement(file.readText()).jsonObject
  val block = json["dependencyPolicy"]?.jsonObject ?: json
  return DependencyPolicy.parse(block)
}

/**
 * The artifacts contract's optional `runtimeJarOwners` (runtime jar path → producing target label),
 * read from the raw JSON so an aspect that predates it, or a contracts release without the typed
 * field, degrades to no owners. Relative paths resolve against [root].
 */
public fun runtimeJarOwners(artifacts: JsonObject, root: File): Map<File, String> =
  (artifacts["runtimeJarOwners"] as? JsonObject)
    .orEmpty()
    .mapNotNull { (path, label) ->
      val text = (label as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.absoluteFile
      file to text
    }
    .toMap()
