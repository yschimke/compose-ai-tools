package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.*
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
     * When `true`, every [ResourceType.VECTOR] resource gets an extra horizontal contact-sheet
     * capture per source qualifier — one cell per entry in [densities], each labelled with its
     * bucket name — so reviewers can eyeball density-fan-out divergence at a glance. The contact
     * sheet is *additional*: per-density captures stay as-is. Skipped silently when [densities] has
     * fewer than two entries (a single-cell contact sheet is silly). Mirror of
     * `composePreview.resourcePreviews.contactSheet`.
     */
    val contactSheet: Boolean = true,
    /** Module-relative path to use as the [ManifestReference.source] root, e.g. `src/main`. */
    val sourceRootRelativePath: (File) -> String = { it.path },
  )

  /**
   * Walks [resSourceRoots] and returns one [ResourcePreview] per `(base, name)` pair, with captures
   * fanned out across the configured [densities] (and [shapes] for adaptive icons, [stretches] for
   * 9-patches). XML files whose root tag we don't render (`<shape>`, `<selector>`, …) are dropped;
   * raster `.png` files that aren't `.9.png` 9-patches are also dropped (out of scope — we render
   * vector / animated-vector / adaptive-icon XML, 9-patch raster, and nothing else).
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
          if (builder.type != type) {
            // Same logical id classifies as different ResourceTypes across qualifier dirs (e.g.
            // `drawable/ic_foo.xml` is a vector but `drawable-night/ic_foo.xml` is an
            // animated-vector). Pathological — last write wins, but we keep the first type since
            // that's what the consumer's default-qualifier file said.
          }
          val relativeSourcePath =
            "$rootRelative/${child.name}/${file.name}".replace(File.separatorChar, '/')
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
    styles: List<AdaptiveStyle> = AdaptiveStyle.entries.toList(),
    stretches: List<NinePatchStretch> = DEFAULT_NINE_PATCH_STRETCHES,
    contactSheet: Boolean = true,
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
            // Two-axis fan-out: every (shape × non-LEGACY style) plus one bare LEGACY capture
            // (mask-independent — pre-O fallback ignores the system mask).
            val maskedStyles = styles.filter { it != AdaptiveStyle.LEGACY }
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
            // Fan out across stretch variants — same drawable, different `setBounds` targets.
            // Empty `stretches` would mean "no captures", which is almost certainly a config
            // mistake; default to all four to keep the previewer well-defined.
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
      // Density-bucketed contact sheet — one extra capture per source qualifier showing every
      // configured density side-by-side. VECTOR only: animated vectors already get one GIF per
      // density; adaptive icons fan out across shape × style; 9-patches fan out across stretch
      // axes. Skipped when densities has fewer than two entries — a single-cell contact sheet
      // is silly.
      if (type == ResourceType.VECTOR && contactSheet && densities.size >= 2) {
        val combined = combineQualifiers(cleaned, null)
        out +=
          ResourceCapture(
            variant = ResourceVariant(qualifiers = combined, contactSheet = true),
            renderOutput =
              renderOutputPath(
                resourceId = resourceId,
                qualifier = combined,
                shape = null,
                style = null,
                extension = "png",
                contactSheet = true,
              ),
            cost = RESOURCE_CONTACT_SHEET_COST_PER_CELL * densities.size,
            contactSheetDensities = densities,
          )
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
    style: AdaptiveStyle?,
    extension: String,
    stretch: NinePatchStretch? = null,
    contactSheet: Boolean = false,
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
      if (contactSheet) add("contact-sheet")
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
          styles = config.styles,
          stretches = config.stretches,
          contactSheet = config.contactSheet,
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
