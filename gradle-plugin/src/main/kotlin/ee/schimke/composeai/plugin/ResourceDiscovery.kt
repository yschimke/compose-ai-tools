package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
import java.io.File

/**
 * Core of [DiscoverAndroidResourcesTask]: walks `res/` roots for `drawable*` / `mipmap*` dirs,
 * classifies XML via [ResourceXmlClassifier], groups by `(base, name)`, and computes the capture
 * fan-out from the [ResourcePreviewsExtension] knobs. Separate from the task for unit testing.
 */
object ResourceDiscovery {

  private val RESOURCE_BASES = setOf("drawable", "mipmap")

  private const val NINE_PATCH_SUFFIX = ".9.png"

  /** Default fan-out for [ResourceType.NINE_PATCH] captures. */
  private val DEFAULT_NINE_PATCH_STRETCHES: List<NinePatchStretch> =
    NinePatchStretch.entries.toList()

  /** Inputs to the discovery pass. */
  data class Config(
    val resSourceRoots: List<File>,
    val densities: List<String>,
    val shapes: List<AdaptiveShape>,
    val styles: List<AdaptiveStyle> = AdaptiveStyle.entries.toList(),
    val stretches: List<NinePatchStretch> = DEFAULT_NINE_PATCH_STRETCHES,
    /**
     * Pair each [ResourceType.ANIMATED_VECTOR] capture with a filmstrip at [filmstripFractions];
     * mirrors `resourcePreviews.filmstrip`.
     */
    val filmstrip: Boolean = true,
    /**
     * Filmstrip keyframe fractions in `[0, 1]`; defaults to [DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS].
     */
    val filmstripFractions: List<Float> = DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS,
    /** Module-relative path to use as the [ManifestReference.source] root, e.g. `src/main`. */
    val sourceRootRelativePath: (File) -> String = { it.path },
  )

  /**
   * One [ResourcePreview] per `(base, name)` in [resSourceRoots], fanned out across [densities]
   * (plus [shapes] for adaptive icons, [stretches] for 9-patches). Unrendered XML roots (`<shape>`,
   * `<selector>`, …) and non-9-patch rasters are dropped.
   */
  fun discover(config: Config): List<ResourcePreview> {
    val collected = linkedMapOf<String, Builder>()
    for (root in config.resSourceRoots) {
      if (!root.isDirectory) continue
      val rootRelative = config.sourceRootRelativePath(root)
      // Sorted so `drawable/` walks before `drawable-night/` and capture order is deterministic
      // (`listFiles()` has no order).
      val children = root.listFiles()?.sortedBy { it.name } ?: continue
      for (child in children) {
        if (!child.isDirectory) continue
        val parsed = ResourceQualifierParser.parse(child.name)
        if (parsed.base !in RESOURCE_BASES) continue
        val candidates =
          child
            .listFiles { f ->
              f.isFile && (f.name.endsWith(".xml") || f.name.endsWith(NINE_PATCH_SUFFIX))
            }
            ?.sortedBy { it.name } ?: continue
        for (file in candidates) {
          val (type, resourceName) =
            when {
              file.name.endsWith(NINE_PATCH_SUFFIX) ->
                ResourceType.NINE_PATCH to file.name.removeSuffix(NINE_PATCH_SUFFIX)
              file.name.endsWith(".xml") ->
                (ResourceXmlClassifier.classify(file) ?: continue) to file.nameWithoutExtension
              else -> continue
            }
          val id = "${parsed.base}/$resourceName"
          val builder = collected.getOrPut(id) { Builder(id = id, type = type) }
          if (
            type == ResourceType.ADAPTIVE_ICON && ResourceXmlClassifier.hasMonochromeLayer(file)
          ) {
            // Themed styles need a `<monochrome>` layer; record it so plain icons skip them.
            builder.hasMonochrome = true
          }
          if (builder.type != type) {
            // The same id may classify differently across qualifier dirs; keep the
            // default-qualifier file's type.
          }
          val relativeSourcePath =
            "$rootRelative/${child.name}/${file.name}".replace(File.separatorChar, '/')
          builder.sourceFiles[parsed.qualifierSuffix.orEmpty()] = relativeSourcePath
        }
      }
    }
    return collected.values.map { it.build(config) }
  }

  /** The capture set for one resource; public for tests. */
  fun captures(
    type: ResourceType,
    qualifierSuffixes: Set<String?>,
    densities: List<String>,
    shapes: List<AdaptiveShape>,
    resourceId: String,
    styles: List<AdaptiveStyle> = AdaptiveStyle.entries.toList(),
    stretches: List<NinePatchStretch> = DEFAULT_NINE_PATCH_STRETCHES,
    filmstrip: Boolean = true,
    filmstripFractions: List<Float> = DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS,
    hasMonochrome: Boolean = true,
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
            // Every (shape × non-LEGACY style) plus one mask-independent LEGACY capture; themed
            // styles only with a `<monochrome>` layer.
            val maskedStyles = styles.filter {
              it != AdaptiveStyle.LEGACY &&
                (hasMonochrome ||
                  (it != AdaptiveStyle.THEMED_LIGHT && it != AdaptiveStyle.THEMED_DARK))
            }
            for (shape in shapes) {
              for (style in maskedStyles) {
                out +=
                  ResourceCapture(
                    variant = ResourceVariant(qualifiers = combined, shape = shape, style = style),
                    renderOutput =
                      renderOutputPath(
                        resourceId = resourceId,
                        qualifier = combined,
                        shape = shape,
                        style = style,
                        extension = "png",
                      ),
                    cost = RESOURCE_ADAPTIVE_COST,
                  )
              }
            }
            if (AdaptiveStyle.LEGACY in styles) {
              out +=
                ResourceCapture(
                  variant =
                    ResourceVariant(
                      qualifiers = combined,
                      shape = null,
                      style = AdaptiveStyle.LEGACY,
                    ),
                  renderOutput =
                    renderOutputPath(
                      resourceId = resourceId,
                      qualifier = combined,
                      shape = null,
                      style = AdaptiveStyle.LEGACY,
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
                    style = null,
                    extension = "gif",
                  ),
                cost = RESOURCE_ANIMATED_COST,
              )
            if (filmstrip && filmstripFractions.isNotEmpty()) {
              out +=
                ResourceCapture(
                  variant = ResourceVariant(qualifiers = combined, filmstrip = true),
                  renderOutput =
                    renderOutputPath(
                      resourceId = resourceId,
                      qualifier = combined,
                      shape = null,
                      style = null,
                      extension = "png",
                      filmstrip = true,
                    ),
                  cost = RESOURCE_ANIMATED_FILMSTRIP_COST,
                  filmstripFractions = filmstripFractions,
                )
            }
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
                    style = null,
                    extension = "png",
                  ),
                cost = RESOURCE_STATIC_COST,
              )
          }
          ResourceType.NINE_PATCH -> {
            // Empty `stretches` is almost certainly a mistake; default to all four.
            val effectiveStretches = stretches.ifEmpty { DEFAULT_NINE_PATCH_STRETCHES }
            for (stretch in effectiveStretches) {
              out +=
                ResourceCapture(
                  variant = ResourceVariant(qualifiers = combined, stretch = stretch),
                  renderOutput =
                    renderOutputPath(
                      resourceId = resourceId,
                      qualifier = combined,
                      shape = null,
                      style = null,
                      stretch = stretch,
                      extension = "png",
                    ),
                  cost = RESOURCE_NINE_PATCH_COST,
                )
            }
          }
        }
      }
    }
    return out.toList()
  }

  /**
   * Turns a source qualifier into the capture qualifier: density tokens (including `anydpi`) are
   * stripped so the density fan-out re-adds a concrete bucket, and version tokens (`v26`) are
   * stripped since they only affect which file AAPT picks.
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
    style: AdaptiveStyle?,
    extension: String,
    stretch: NinePatchStretch? = null,
    filmstrip: Boolean = false,
  ): String {
    val (base, name) = resourceId.split('/', limit = 2).let { it[0] to it[1] }
    val safeName = sanitiseFilename(name)
    val safeQualifier = qualifier?.let { sanitiseFilename(it) }
    val parts = buildList {
      add(safeName)
      if (!safeQualifier.isNullOrEmpty()) add(safeQualifier)
      if (shape != null) add("SHAPE_${shape.name.lowercase()}")
      when (style) {
        null,
        AdaptiveStyle.FULL_COLOR -> Unit
        AdaptiveStyle.THEMED_LIGHT -> add("themed-light")
        AdaptiveStyle.THEMED_DARK -> add("themed-dark")
        AdaptiveStyle.LEGACY -> add("LEGACY")
      }
      if (stretch != null) add("STRETCH_${stretch.name.lowercase()}")
      if (filmstrip) add("filmstrip")
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
    /**
     * Set when any adaptive-icon source declares `<monochrome>`; gates themed captures
     * ([ResourceXmlClassifier.hasMonochromeLayer]).
     */
    var hasMonochrome: Boolean = false,
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
          styles = config.styles,
          stretches = config.stretches,
          filmstrip = config.filmstrip,
          filmstripFractions = config.filmstripFractions,
          hasMonochrome = hasMonochrome,
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
