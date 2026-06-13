package ee.schimke.composeai.viewer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * On-disk shape mirrors `gradle-plugin/PreviewBundleFormat.kt` and `gradle-plugin/PreviewData.kt`.
 * Duplicated here (rather than depending on either source module) so the viewer's runtime classpath
 * stays minimal — bundle parsing is tiny and rarely changes.
 *
 * Keep field names in lockstep with the plugin-side definitions; `ignoreUnknownKeys = true` makes
 * forward-compat round-trips safe.
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  val backend: String,
  val previewIds: List<String>,
  val coverPreviewId: String?,
  val classpath: List<ClasspathEntry>,
  val modulePath: String,
  val producedBy: String,
  /**
   * v3+: producing build system (`gradle`|`amper`|`bazel`). Defaults to `gradle` for v2 bundles.
   */
  val producer: String = "gradle",
  /**
   * v3+: classpath assembly strategy (`coordinates`|`embedded`|`mixed`). Defaults for v2 bundles.
   */
  val resolution: String = "coordinates",
  /**
   * v5+: previews replayed from a captured intermediate representation (`ir/<id>.<ext>`) instead of
   * by re-running their consumer bytecode. Empty for a classic all-classes bundle.
   */
  val intermediateRepresentations: List<BundleIr> = emptyList(),
  /**
   * v7+: optional per-extension data reports carried under `extensions/<id>.json`. Empty unless the
   * bundle was packed with `--include-data-extensions`.
   */
  val dataExtensions: List<BundleDataExtension> = emptyList(),
)

/** v7+ mirror of `BundleDataExtension` in `PreviewBundleFormat.kt`. */
@Serializable data class BundleDataExtension(val extensionId: String, val path: String)

/** v5+ mirror of `BundleIr` in `PreviewBundleFormat.kt`. */
@Serializable
data class BundleIr(
  val previewId: String,
  /**
   * `remotecompose` (RC doc), `protolayout` (Wear tile Layout proto), or `lottie` (a Lottie
   * animation asset packed straight from the module resources).
   */
  val format: String,
  val path: String,
  val resourcesPath: String? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ClasspathEntry {
  @Serializable
  @kotlinx.serialization.SerialName("module")
  data class Module(val path: String) : ClasspathEntry

  @Serializable
  @kotlinx.serialization.SerialName("maven")
  data class Maven(
    val group: String,
    val artifact: String,
    val version: String,
    val type: String,
    /** v4+: hex SHA-256 of the artifact bytes; verify after re-resolving. Null = unverifiable. */
    val sha256: String? = null,
  ) : ClasspathEntry

  @Serializable
  @kotlinx.serialization.SerialName("project")
  data class Project(val path: String, val inlinedAs: String) : ClasspathEntry

  /** v3+: a third-party jar carried inside the bundle's `libs/` — no coordinate, no resolution. */
  @Serializable
  @kotlinx.serialization.SerialName("embedded")
  data class Embedded(val inlinedAs: String) : ClasspathEntry
}

@Serializable
data class PreviewManifest(
  val module: String = "",
  val variant: String = "",
  val previews: List<PreviewInfo>,
  val dataExtensionReports: Map<String, String> = emptyMap(),
)

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: PreviewParams = PreviewParams(),
)

@Serializable
data class PreviewParams(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val fontScale: Float = 1.0f,
  val showSystemUi: Boolean = false,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0,
  val uiMode: Int = 0,
  val locale: String? = null,
  val group: String? = null,
  val wrapperClassName: String? = null,
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int = Int.MAX_VALUE,
  val kind: String = "COMPOSE",
)
