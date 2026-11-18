package ee.schimke.composeai.bundle

import kotlinx.serialization.Serializable

/**
 * Suffix for a Watch Face Format preview's source bundle, `previews/<id>.wff.zip`: the face in the
 * standard WFF resource layout (`res/raw/watchface.xml`, `res/xml/watch_face_info.xml`, the
 * `res/values*`, `res/drawable*`, `res/mipmap*` and `res/font*` it references), plus the module's
 * source `AndroidManifest.xml` and the source repository's licence file as `LICENSE`, when found.
 * Described by the [WffSourceManifest] at [BUNDLE_WFF_MANIFEST_SUFFIX]. Additive: readers that do
 * not know it ignore it.
 */
public const val BUNDLE_WFF_SOURCE_SUFFIX: String = ".wff.zip"

/**
 * Suffix for the [WffSourceManifest] beside [BUNDLE_WFF_SOURCE_SUFFIX], `previews/<id>.wff.json`.
 */
public const val BUNDLE_WFF_MANIFEST_SUFFIX: String = ".wff.json"

/**
 * `previews/<id>.wff.json`: where a Watch Face Format preview's source bundle came from and under
 * which licence. Every provenance field is nullable: the producer records what it could detect and
 * never filters. A consumer that redistributes the face (for example packaging it into an APK)
 * applies its own licence allowlist to [license].
 */
@Serializable
public data class WffSourceManifest(
  /** Bumped only on an incompatible change; additions keep the number. */
  public val schemaVersion: Int = WFF_SOURCE_MANIFEST_SCHEMA_VERSION,
  /** The bundle preview id this face belongs to (`previews/<previewId>.png`). */
  public val previewId: String,
  /** Path of the source zip inside the bundle, `previews/<previewId>.wff.zip`. */
  public val bundle: String,
  /**
   * `com.google.wear.watchface.format.version` from the module's `AndroidManifest.xml`, or null
   * when the manifest does not declare it.
   */
  public val wffVersion: Int? = null,
  /** `https://` URL of the source repository (from its `origin` remote), or null. */
  public val repoUrl: String? = null,
  /** Full commit SHA the face was packed from, or null outside a git checkout. */
  public val commit: String? = null,
  /** Repository-relative path of the face XML, e.g. `app/src/main/res/raw/watchface.xml`. */
  public val sourcePath: String? = null,
  /** Browsable link to [sourcePath] at [commit] (GitHub repositories only), or null. */
  public val sourceUrl: String? = null,
  /** SPDX identifier detected from the repository's licence file, or null when unknown. */
  public val license: String? = null,
  /** Repository-relative path of the licence file that was read (carried as `LICENSE`), or null. */
  public val licenseFile: String? = null,
  /** Every entry of the zip, sorted. */
  public val files: List<String> = emptyList(),
)

/** Schema version written into [WffSourceManifest.schemaVersion]. */
public const val WFF_SOURCE_MANIFEST_SCHEMA_VERSION: Int = 1
