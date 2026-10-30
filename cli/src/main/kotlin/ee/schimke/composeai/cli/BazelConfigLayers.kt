package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.JsonObject

/**
 * Classpath resource a repackaged CLI may bundle with distribution-level [BazelBuildConfig]
 * defaults (`buildCommand`, `buildArgs`, `queryArgs`, `providers`, `digestXattr`, …). Upstream
 * releases ship none.
 */
internal const val BAZEL_DEFAULTS_RESOURCE = "/ee/schimke/composeai/cli/bazel-defaults.json"

/** Environment variable naming a defaults JSON file, layered over [BAZEL_DEFAULTS_RESOURCE]. */
internal const val BAZEL_CONFIG_ENV = "COMPOSE_PREVIEW_BAZEL_CONFIG"

/** One JSON object of the Bazel configuration, and where it came from (for diagnostics). */
internal data class BazelConfigLayer(
  val source: String,
  val json: JsonObject,
  val defaults: Boolean,
)

/** The merged configuration and the layers it was merged from, lowest precedence first. */
internal class BazelConfigLayers(val layers: List<BazelConfigLayer>) {
  /** Top-level keys merged in order: a later layer's key replaces an earlier one's whole value. */
  val merged: JsonObject = JsonObject(layers.fold(emptyMap()) { acc, layer -> acc + layer.json })

  val config: BazelBuildConfig
    get() = bazelJson.decodeFromJsonElement(BazelBuildConfig.serializer(), merged)

  /** The highest-precedence layer that sets [key], or null when none does. */
  fun sourceOf(key: String): BazelConfigLayer? = layers.lastOrNull { key in it.json }

  /** True when [key] is set by the workspace configuration rather than a defaults layer. */
  fun setByWorkspace(key: String): Boolean = sourceOf(key)?.defaults == false
}

/**
 * The Bazel configuration layers, lowest precedence first: the classpath [BAZEL_DEFAULTS_RESOURCE],
 * the file [BAZEL_CONFIG_ENV] names, then [workspaceConfig] (the workspace's
 * `.compose-preview/bazel.json` or `--config`) when it exists. CLI flags are applied over the
 * decoded result by the caller.
 *
 * A missing [BAZEL_CONFIG_ENV] file is an error rather than silently ignored: the variable was set
 * to configure the build, and a typo would otherwise build with different flags.
 */
internal fun loadBazelConfigLayers(
  workspaceConfig: File?,
  envConfig: String? = System.getenv(BAZEL_CONFIG_ENV),
  classpathDefaults: String? = bundledBazelDefaults(),
): BazelConfigLayers {
  fun parse(text: String, source: String): JsonObject =
    bazelJson.parseToJsonElement(text) as? JsonObject ?: error("$source must contain a JSON object")
  val layers = buildList {
    classpathDefaults?.let {
      add(
        BazelConfigLayer(
          "classpath $BAZEL_DEFAULTS_RESOURCE",
          parse(it, "classpath $BAZEL_DEFAULTS_RESOURCE"),
          defaults = true,
        )
      )
    }
    envConfig
      ?.takeIf { it.isNotBlank() }
      ?.let(::File)
      ?.let { file ->
        require(file.isFile) { "\$$BAZEL_CONFIG_ENV names a missing file: $file" }
        add(
          BazelConfigLayer(
            "\$$BAZEL_CONFIG_ENV ($file)",
            parse(file.readText(), file.path),
            defaults = true,
          )
        )
      }
    workspaceConfig
      ?.takeIf { it.isFile }
      ?.let { add(BazelConfigLayer(it.path, parse(it.readText(), it.path), defaults = false)) }
  }
  return BazelConfigLayers(layers)
}

/** The bundled [BAZEL_DEFAULTS_RESOURCE], or null when this distribution bundles none. */
internal fun bundledBazelDefaults(): String? =
  BazelConfigLayers::class.java.getResourceAsStream(BAZEL_DEFAULTS_RESOURCE)?.use {
    it.readBytes().decodeToString()
  }
