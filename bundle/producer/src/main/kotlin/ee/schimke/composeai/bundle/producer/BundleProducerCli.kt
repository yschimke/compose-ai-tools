package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.discovery.PreviewDiscovery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Offline executable adapter. Build systems supply metadata plus materialized, declared inputs. */
public object BundleProducerCli {
  @JvmStatic
  public fun main(args: Array<String>) {
    require(args.size % 2 == 0) {
      "Expected --artifacts file --output bundle.png [--maven-install file] [--filter text] [--renders directory]"
    }
    val pairs = args.toList().chunked(2)
    val flags = pairs.associate { it[0] to it[1] }
    require(
      flags.keys.all {
        it in
          setOf("--artifacts", "--output", "--maven-install", "--filter", "--renders", "--bundle")
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
    require(metadata.getValue("schemaVersion").jsonPrimitive.content == "1") {
      "Unsupported artifacts schema"
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
    writeBazelBundle(
      module,
      project,
      runtime,
      portable,
      flags["--renders"]?.let(::File),
      selected,
      pairs.filter { it[0] == "--maven-install" }.map { File(it[1]) },
      File(flags.getValue("--output")),
    )
  }
}
