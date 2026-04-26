package ee.schimke.composeai.plugin

import java.io.File

/**
 * Pure-Kotlin core of the [DiscoverAndroidResourcesTask]. Walks the consumer's `res/` source roots
 * for `drawable<qualifier>` and `mipmap<qualifier>` subdirectories, classifies each XML file via
 * [ResourceXmlClassifier], groups source files by `(base, name)`, and computes the capture fan-out
 * (qualifier × adaptive shape) per the [ResourcePreviewsExtension] DSL knobs.
 *
 * Lives outside the task class so the bulk of the logic can be unit-tested without spinning up a
 * Gradle ProjectBuilder — the task is a thin shell that hands paths to [discover].
 */
object ResourceDiscovery {

  private val RESOURCE_BASES = setOf("drawable", "mipmap")

  /** Inputs to the discovery pass. */
  data class Config(
    val resSourceRoots: List<File>,
    val densities: List<String>,
    val shapes: List<AdaptiveShape>,
    /** Module-relative path to use as the [ManifestReference.source] root, e.g. `src/main`. */
    val sourceRootRelativePath: (File) -> String = { it.path },
  )

  /**
   * Walks [resSourceRoots] and returns one [ResourcePreview] per `(base, name)` pair, with captures
   * fanned out across the configured [densities] (and [shapes] for adaptive icons). Source files
   * that classify as something we don't render (`<shape>`, `<selector>`, …) are dropped.
   */
  fun discover(config: Config): List<ResourcePreview> {
    val collected = linkedMapOf<String, Builder>()
    for (root in config.resSourceRoots) {
      if (!root.isDirectory) continue
      val rootRelative = config.sourceRootRelativePath(root)
      // Sort directories alphabetically so the default-qualifier `drawable/` walks before
      // `drawable-night/`, which makes the `null` slot in `sourceFiles` populate first and the
      // capture order deterministic across filesystems (`listFiles()` makes no order guarantee).
      val children = root.listFiles()?.sortedBy { it.name } ?: continue
      for (child in children) {
        if (!child.isDirectory) continue
        val parsed = ResourceQualifierParser.parse(child.name)
        if (parsed.base !in RESOURCE_BASES) continue
        val xmlFiles =
          child.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name }
            ?: continue
        for (xml in xmlFiles) {
          val type = ResourceXmlClassifier.classify(xml) ?: continue
          val resourceName = xml.nameWithoutExtension
          val id = "${parsed.base}/$resourceName"
          val builder = collected.getOrPut(id) { Builder(id = id, type = type) }
          if (builder.type != type) {
            // Same logical id classifies as different ResourceTypes across qualifier dirs (e.g.
            // `drawable/ic_foo.xml` is a vector but `drawable-night/ic_foo.xml` is an
            // animated-vector). Pathological — last write wins, but we keep the first type since
            // that's what the consumer's default-qualifier file said.
          }
          val relativeSourcePath =
            "$rootRelative/${child.name}/${xml.name}".replace(File.separatorChar, '/')
          builder.sourceFiles[parsed.qualifierSuffix.orEmpty()] = relativeSourcePath
        }
      }
    }
    return collected.values.map { it.build(config) }
  }

  /**
   * Computes the capture set for one resource. Public so [DiscoverAndroidResourcesTask] tests can
   * pin specific fan-outs without driving the filesystem walk.
   */
  fun captures(
    type: ResourceType,
    qualifierSuffixes: Set<String?>,
    densities: List<String>,
    shapes: List<AdaptiveShape>,
    resourceId: String,
  ): List<ResourceCapture> {
    val out = linkedSetOf<ResourceCapture>()
    val baseQualifierSets =
      if (qualifierSuffixes.isEmpty()) setOf<String?>(null) else qualifierSuffixes
    for (sourceQualifier in baseQualifierSets) {
      val cleaned = cleanSourceQualifier(sourceQualifier)
      val effectiveDensities =
        if (densities.isEmpty()) listOf(null) else densities.map<String, String?> { it }
      for (density in effectiveDensities) {
        val combined = combineQualifiers(cleaned, density)
        when (type) {
          ResourceType.ADAPTIVE_ICON -> {
            for (shape in shapes) {
              out +=
                ResourceCapture(
                  variant = ResourceVariant(qualifiers = combined, shape = shape),
                  renderOutput =
                    renderOutputPath(
                      resourceId = resourceId,
                      qualifier = combined,
                      shape = shape,
                      extension = "png",
                    ),
                  cost = RESOURCE_ADAPTIVE_COST,
                )
            }
          }
          ResourceType.ANIMATED_VECTOR -> {
            out +=
              ResourceCapture(
                variant = ResourceVariant(qualifiers = combined),
                renderOutput =
                  renderOutputPath(
                    resourceId = resourceId,
                    qualifier = combined,
                    shape = null,
                    extension = "gif",
                  ),
                cost = RESOURCE_ANIMATED_COST,
              )
          }
          ResourceType.VECTOR -> {
            out +=
              ResourceCapture(
                variant = ResourceVariant(qualifiers = combined),
                renderOutput =
                  renderOutputPath(
                    resourceId = resourceId,
                    qualifier = combined,
                    shape = null,
                    extension = "png",
                  ),
                cost = RESOURCE_STATIC_COST,
              )
          }
        }
      }
    }
    return out.toList()
  }

  /**
   * Cleans a source-file qualifier suffix into the prefix the renderer should pass to Robolectric.
   * Density tokens are stripped so the implicit density fan-out can re-add a specific bucket
   * (`anydpi` counts here — adaptive-icon source dirs like `mipmap-anydpi-v26` carry it but we want
   * to render at concrete densities for sharp output). Version tokens (`v26`, `v34`) are stripped
   * too — they gate which file AAPT picks at resolution time, not how the picked file renders, so
   * they don't belong in the capture qualifier.
   */
  private fun cleanSourceQualifier(suffix: String?): String? {
    if (suffix == null) return null
    val kept =
      suffix.split('-').filterNot {
        ResourceQualifierParser.isDensityQualifier(it) ||
          ResourceQualifierParser.isVersionQualifier(it)
      }
    return if (kept.isEmpty()) null else kept.joinToString("-")
  }

  private fun combineQualifiers(left: String?, right: String?): String? =
    when {
      left.isNullOrEmpty() && right.isNullOrEmpty() -> null
      left.isNullOrEmpty() -> right
      right.isNullOrEmpty() -> left
      else -> "$left-$right"
    }

  internal fun renderOutputPath(
    resourceId: String,
    qualifier: String?,
    shape: AdaptiveShape?,
    extension: String,
  ): String {
    val (base, name) = resourceId.split('/', limit = 2).let { it[0] to it[1] }
    val safeName = sanitiseFilename(name)
    val safeQualifier = qualifier?.let { sanitiseFilename(it) }
    val parts = buildList {
      add(safeName)
      if (!safeQualifier.isNullOrEmpty()) add(safeQualifier)
      if (shape != null) {
        if (shape == AdaptiveShape.LEGACY) add("LEGACY") else add("SHAPE_${shape.name.lowercase()}")
      }
    }
    return "renders/resources/$base/${parts.joinToString("_")}.$extension"
  }

  /** Conservative whitelist matching `docs/RENDER_FILENAMES.md`'s `[A-Za-z0-9._-]` rule. */
  private fun sanitiseFilename(input: String): String =
    buildString(input.length) {
      for (ch in input) {
        if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') append(ch) else append('_')
      }
    }

  private class Builder(
    val id: String,
    val type: ResourceType,
    val sourceFiles: LinkedHashMap<String, String> = linkedMapOf(),
  ) {
    fun build(config: Config): ResourcePreview {
      val qualifierSuffixes: Set<String?> =
        sourceFiles.keys.mapTo(linkedSetOf()) { it.ifEmpty { null } }
      val captures =
        captures(
          type = type,
          qualifierSuffixes = qualifierSuffixes,
          densities = config.densities,
          shapes = config.shapes,
          resourceId = id,
        )
      return ResourcePreview(
        id = id,
        type = type,
        sourceFiles = sourceFiles.toMap(),
        captures = captures,
      )
    }
  }
}
