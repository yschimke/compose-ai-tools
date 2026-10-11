package ee.schimke.composeai.viewer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Mirrors `gradle-plugin/PreviewBundleFormat.kt` and `PreviewData.kt`, duplicated to keep the
 * viewer's classpath minimal. Keep field names in lockstep; `ignoreUnknownKeys` keeps forward
 * compat.
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
  /**
   * v9+: extra Maven repository base URLs (beyond Maven Central / Google Maven) needed to
   * re-resolve [ClasspathEntry.Maven] coordinates.
   */
  val repositories: List<String> = emptyList(),
)

/** v7+ mirror of `BundleDataExtension` in `PreviewBundleFormat.kt`. */
@Serializable data class BundleDataExtension(val extensionId: String, val path: String)

/** v5+ mirror of `BundleIr` in `PreviewBundleFormat.kt`. */
@Serializable
data class BundleIr(
  val previewId: String,
  /**
   * `remotecompose` (RC doc), `protolayout` (Wear tile Layout proto), `lottie` or `svg` (assets
   * packed from module resources).
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
  /**
   * Bound a wrapped axis was measured against (see `discovery.PreviewParams.wrapSandboxWidthDp`);
   * the viewer's window size for previews without explicit dims.
   */
  val wrapSandboxWidthDp: Int? = null,
  /** See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
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
