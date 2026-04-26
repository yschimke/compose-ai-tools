package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Wire shape — `<module>/build/compose-previews/resources.json` from the
// `discoverAndroidResources` task. Mirrors PreviewData.kt /
// RenderResourceManifest.kt; CLI doesn't depend on the gradle-plugin module
// so the DTOs are duplicated here, same split the composable side uses.
// ---------------------------------------------------------------------------

@Serializable data class ResourceVariant(val qualifiers: String? = null, val shape: String? = null)

@Serializable
data class ResourceCapture(
  val variant: ResourceVariant? = null,
  val renderOutput: String = "",
  val cost: Float = 1.0f,
)

@Serializable
data class ResourcePreview(
  val id: String,
  val type: String,
  val sourceFiles: Map<String, String> = emptyMap(),
  val captures: List<ResourceCapture> = emptyList(),
)

@Serializable
data class ManifestReference(
  val source: String,
  val componentKind: String,
  val componentName: String? = null,
  val attributeName: String,
  val resourceType: String,
  val resourceName: String,
)

@Serializable
data class ResourceManifest(
  val module: String,
  val variant: String,
  val resources: List<ResourcePreview> = emptyList(),
  val manifestReferences: List<ManifestReference> = emptyList(),
)

// ---------------------------------------------------------------------------
// CLI output DTOs — enrich each manifest entry with the rendered PNG path,
// its sha256, and a `changed` flag computed against the per-module sidecar
// state. Same shape as `PreviewResult` / `CaptureResult` but typed for the
// resource fields tooling cares about.
// ---------------------------------------------------------------------------

@Serializable
data class ResourceCaptureResult(
  val variant: ResourceVariant? = null,
  /** Module-relative renderOutput path verbatim from `resources.json`. */
  val renderOutput: String = "",
  /** Absolute path to the rendered PNG / GIF on disk. `null` when missing. */
  val pngPath: String? = null,
  /** sha256 of the rendered file. `null` when missing. */
  val sha256: String? = null,
  /** True when sha256 differs from the prior `compose-preview show-resources` run. */
  val changed: Boolean? = null,
)

@Serializable
data class ResourcePreviewResult(
  val id: String,
  val module: String,
  val type: String,
  val sourceFiles: Map<String, String> = emptyMap(),
  val captures: List<ResourceCaptureResult> = emptyList(),
  /** First capture's PNG path. Kept for back-compat parity with [PreviewResult]. */
  val pngPath: String? = null,
  /** First capture's sha256. */
  val sha256: String? = null,
  /** First capture's `changed` flag. */
  val changed: Boolean? = null,
)

internal fun ResourcePreviewResult.anyChanged(): Boolean = captures.any { it.changed == true }

/**
 * Versioned envelope for `compose-preview show-resources --json`. Distinct schema from the
 * composable side (`compose-preview-show/v1`) so agents can dispatch on shape without inspecting
 * field names — bump the version when [ResourcePreviewResult]'s shape changes.
 */
@Serializable
data class ResourceListResponse(
  val schema: String = SHOW_RESOURCES_SCHEMA,
  val resources: List<ResourcePreviewResult>,
  val counts: PreviewCounts? = null,
  val manifestReferences: List<ManifestReference> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BriefResourceListResponse(
  @EncodeDefault val schema: String = SHOW_RESOURCES_BRIEF_SCHEMA,
  val resources: List<BriefResourceResult>,
  val counts: PreviewCounts? = null,
)

@Serializable
data class BriefResourceResult(
  val id: String,
  val module: String? = null,
  val type: String,
  val captures: List<BriefResourceCapture>,
)

@Serializable
data class BriefResourceCapture(
  val png: String? = null,
  val sha: String? = null,
  val changed: Boolean? = null,
  /** Qualifier string ('xhdpi', 'night-xhdpi'); omitted when null. */
  val qualifiers: String? = null,
  /** Adaptive icon shape; omitted when null. */
  val shape: String? = null,
)

internal const val SHOW_RESOURCES_SCHEMA = "compose-preview-show-resources/v1"
internal const val SHOW_RESOURCES_BRIEF_SCHEMA = "compose-preview-show-resources-brief/v1"

@Serializable private data class ResourceCliState(val shas: Map<String, String> = emptyMap())

private val resourceJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
}

private val briefResourceJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = false
  encodeDefaults = false
}

/**
 * `compose-preview show-resources` — sibling of [ShowCommand] for Android XML resource previews.
 * Triggers `:<module>:renderAndroidResources`, walks `resources.json`, hashes the rendered PNGs /
 * GIFs against a per-module sidecar state file, and emits one row per `(resource id, capture
 * variant)` with the same id / png / sha / changed shape as the composable side.
 *
 * Kept separate from `show` (rather than folded behind a `--with-resources` flag) because the
 * workflows are disjoint: a Compose UI dev iterating on a `@Preview` doesn't want to pay the
 * resource renderer's cold-start cost, and a designer iterating on launcher icons doesn't need
 * every composable re-rendered. Two commands, two JSON envelopes, two state files — independent
 * persistence so neither path's `changed` calculation can poison the other's diff signal.
 */
class ShowResourcesCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args

  override fun run() {
    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val tasks = modules.map { ":${it.gradlePath}:renderAndroidResources" }.toTypedArray()

      if (!runGradle(gradle, *tasks)) {
        reportRenderFailures(gradle)
        System.err.println("Resource render failed")
        exitProcess(2)
      }

      val manifests = readAllResourceManifests(modules)
      if (manifests.isEmpty() || manifests.all { it.second.resources.isEmpty() }) {
        if (jsonOutput) println(encodeResourceResponse(emptyList(), emptyList()))
        else println("No Android resource previews found.")
        // Resources are an opt-out feature — exit 0 (not 3) so consumers
        // running `show-resources` against a workspace that legitimately
        // has no XML drawables don't get a non-zero exit on every CI run.
        exitProcess(0)
      }

      val all = buildResourceResults(manifests)
      val filtered = applyResourceFilters(all)
      val allReferences = manifests.flatMap { it.second.manifestReferences }

      if (filtered.isEmpty()) {
        if (jsonOutput) println(encodeResourceResponse(emptyList(), allReferences, all))
        else println("No resource previews matched.")
        exitProcess(3)
      }

      if (jsonOutput) {
        println(encodeResourceResponse(filtered, allReferences, all))
      } else {
        printText(modules, filtered)
      }

      val missing = filtered.filter { r -> r.captures.any { it.pngPath == null } }
      if (missing.isNotEmpty()) {
        System.err.println(
          "Resource render completed but produced no PNG for ${missing.size} of " +
            "${filtered.size} resource preview(s)."
        )
        exitProcess(2)
      }
    }
  }

  // -------------------------------------------------------------------
  // Filesystem + state plumbing — siblings of Command's composable
  // helpers. Lives on this subclass rather than the base because none
  // of the other commands consume them.
  // -------------------------------------------------------------------

  private fun readResourceManifest(module: PreviewModule): ResourceManifest? {
    val manifestFile = module.projectDir.resolve("build/compose-previews/resources.json")
    if (!manifestFile.exists()) return null
    return resourceJson.decodeFromString(manifestFile.readText())
  }

  private fun readAllResourceManifests(
    modules: List<PreviewModule>
  ): List<Pair<PreviewModule, ResourceManifest>> = modules.mapNotNull { module ->
    readResourceManifest(module)?.let { module to it }
  }

  private fun buildResourceResults(
    manifests: List<Pair<PreviewModule, ResourceManifest>>
  ): List<ResourcePreviewResult> {
    val out = mutableListOf<ResourcePreviewResult>()
    for ((module, manifest) in manifests) {
      val prior = readResourceState(module).shas
      val updated = mutableMapOf<String, String>()

      for (resource in manifest.resources) {
        val captureResults =
          resource.captures.mapIndexed { index, capture ->
            val pngFile =
              capture.renderOutput
                .takeIf { it.isNotEmpty() }
                ?.let { module.projectDir.resolve("build/compose-previews/$it") }
                ?.takeIf { it.exists() }
            val sha = pngFile?.let { sha256Hex(it) }
            // Same `<id>` for the first capture, `<id>#<n>` for the rest —
            // matches the composable side's keying so existing state-file
            // conventions transfer.
            val key = if (index == 0) resource.id else "${resource.id}#$index"
            val changed: Boolean? =
              when {
                sha == null -> null
                prior[key] == null -> true
                else -> prior[key] != sha
              }
            if (sha != null) updated[key] = sha
            ResourceCaptureResult(
              variant = capture.variant,
              renderOutput = capture.renderOutput,
              pngPath = pngFile?.absolutePath,
              sha256 = sha,
              changed = changed,
            )
          }
        val first = captureResults.firstOrNull()
        out +=
          ResourcePreviewResult(
            id = resource.id,
            module = module.gradlePath,
            type = resource.type,
            sourceFiles = resource.sourceFiles,
            captures = captureResults,
            pngPath = first?.pngPath,
            sha256 = first?.sha256,
            changed = first?.changed,
          )
      }
      writeResourceState(module, ResourceCliState(shas = updated))
    }
    return out
  }

  private fun applyResourceFilters(all: List<ResourcePreviewResult>): List<ResourcePreviewResult> =
    all.filter {
      val matches =
        (exactId == null || it.id == exactId) &&
          (filter == null || it.id.contains(filter, ignoreCase = true))
      matches && (!changedOnly || it.anyChanged())
    }

  private fun encodeResourceResponse(
    results: List<ResourcePreviewResult>,
    references: List<ManifestReference>,
    countsScope: List<ResourcePreviewResult>? = null,
  ): String {
    val counts = countsScope?.let { resourceCountsOf(it) }
    if (brief) {
      val multiModule = results.map { it.module }.distinct().size > 1
      val brief = results.map { r ->
        BriefResourceResult(
          id = r.id,
          module = r.module.takeIf { multiModule },
          type = r.type,
          captures =
            r.captures.map { c ->
              BriefResourceCapture(
                png = c.pngPath,
                sha = c.sha256?.take(12),
                changed = c.changed,
                qualifiers = c.variant?.qualifiers,
                shape = c.variant?.shape,
              )
            },
        )
      }
      return briefResourceJson.encodeToString(
        BriefResourceListResponse.serializer(),
        BriefResourceListResponse(resources = brief, counts = counts),
      )
    }
    return resourceJson.encodeToString(
      ResourceListResponse.serializer(),
      ResourceListResponse(resources = results, counts = counts, manifestReferences = references),
    )
  }

  private fun resourceCountsOf(results: List<ResourcePreviewResult>) =
    PreviewCounts(
      total = results.size,
      changed = results.count { it.anyChanged() },
      unchanged = results.count { !it.anyChanged() && it.captures.any { c -> c.pngPath != null } },
      missing = results.count { it.captures.all { c -> c.pngPath == null } },
    )

  private fun printText(modules: List<PreviewModule>, results: List<ResourcePreviewResult>) {
    var lastModule: String? = null
    for (r in results) {
      if (modules.size > 1 && r.module != lastModule) {
        println("[${r.module}]")
        lastModule = r.module
      }
      val statusTag =
        when {
          r.pngPath == null -> " [no PNG]"
          r.anyChanged() -> " [changed]"
          else -> ""
        }
      val shaTag = r.sha256?.let { "  sha=${it.take(12)}" } ?: ""
      println("${r.id} (${r.type})$statusTag$shaTag")
      if (r.captures.size <= 1) {
        if (r.pngPath != null) println("  ${r.pngPath}")
      } else {
        for (c in r.captures) {
          val tag =
            when {
              c.pngPath == null -> " [no PNG]"
              c.changed == true -> " [changed]"
              else -> ""
            }
          val coord =
            listOfNotNull(c.variant?.qualifiers, c.variant?.shape).joinToString(" · ").ifEmpty {
              "default"
            }
          println("  [$coord]$tag ${c.pngPath ?: ""}")
        }
      }
    }
  }

  private fun stateFile(module: PreviewModule): File =
    // Sibling of `.cli-state.json` (the composable state file) — separate
    // so neither feature can poison the other's diff signal.
    module.projectDir.resolve("build/compose-previews/.cli-state-resources.json")

  private fun readResourceState(module: PreviewModule): ResourceCliState {
    val f = stateFile(module)
    if (!f.exists()) return ResourceCliState()
    return try {
      resourceJson.decodeFromString(ResourceCliState.serializer(), f.readText())
    } catch (e: Exception) {
      if (verbose)
        System.err.println(
          "Warning: corrupt resource state file ${f.path}, resetting: ${e.message}"
        )
      ResourceCliState()
    }
  }

  private fun writeResourceState(module: PreviewModule, state: ResourceCliState) {
    val f = stateFile(module)
    f.parentFile?.mkdirs()
    f.writeText(resourceJson.encodeToString(ResourceCliState.serializer(), state))
  }

  private fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
      val buf = ByteArray(8192)
      while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
