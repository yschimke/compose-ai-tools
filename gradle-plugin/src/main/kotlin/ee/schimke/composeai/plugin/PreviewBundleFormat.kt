package ee.schimke.composeai.plugin

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * On-disk format for `compose-preview` bundles — portable, self-contained artefacts that record one
 * or more `@Preview` composables together with the **minimal** classpath needed to re-render them.
 *
 * # File shape
 *
 * The bundle is a **PNG + ZIP polyglot**:
 * 1. Bytes `0..n` are a valid PNG (the cover image — the first selected preview's rendered output,
 *    or a stub gray placeholder). Finder, Preview.app, browsers, GitHub, Slack — every PNG viewer
 *    renders the leading image. This is the bundle's **default** preview: the one thing every plain
 *    image viewer shows.
 * 2. Bytes `n+1..EOF` are a standard ZIP archive. ZIP parsers scan backwards from EOF for the
 *    End-Of-Central-Directory signature (`PK\x05\x06`), so the leading PNG bytes are invisible to
 *    them. `unzip foo.png` works.
 *
 * `file(1)` reports "PNG image data". The same file opened by `compose-preview bundle open` (or the
 * VS Code extension) extracts the appended zip and rehydrates the preview.
 *
 * # ZIP layout
 *
 * ```
 * bundle.json              — manifest (this file's [BundleManifest])
 * previews.json            — filtered to selected preview ids; same shape as the original
 * previews/<id>.png        — the rendered PNG for EACH selected preview (see [BUNDLE_PREVIEWS_DIR]).
 *                            The cover's leading-bytes PNG is mirrored here under its own id so
 *                            iterating the well-known directory yields every preview uniformly.
 *                            A preview with no render on disk is simply absent from this directory.
 * previews/<id>.overrides.json — (v8) the author-declared editable knobs the preview exposed via the
 *                            `previewOverride*` lookups (a verbatim `compose/overrides` payload —
 *                            `PreviewOverridesPayload` from `:data-preview-overrides-core`, copied byte
 *                            for byte; the producer never parses it): label / list-length / per-item
 *                            indexed values. Captured during the normal
 *                            render, present only for previews that opted in, so a detached viewer can
 *                            offer the editable controls without a live daemon. Convention-discovered
 *                            (no manifest pointer), like the optional semantics sidecar.
 * previews/<id>.catalog.json — the resolved `@ColorCatalog` / `@TypographyCatalog` token values for a
 *                            `PreviewKind.CATALOG` sheet (a verbatim `compose-preview-catalog-tokens`
 *                            payload the renderer wrote under `data/catalog-tokens/`; copied byte for
 *                            byte, never parsed): hex per colour, size/weight metrics per type style.
 *                            Present only for catalog sheets, so a detached reader (design-parity's
 *                            `catalog-export`) can import an annotation-declared palette / type scale
 *                            without re-rendering. Convention-discovered (no manifest pointer). See
 *                            [BUNDLE_CATALOG_TOKENS_SIDECAR_EXT] and issue #2167.
 * classes/app.jar          — consumer module bytecode, MINIMIZED to classes reachable from the
 *                            selected previews (plus all module resources). For an IR-backed
 *                            preview (see below) the enclosing class is NOT a closure seed, so its
 *                            bytecode is omitted unless some other preview reaches it.
 * libs/<name>.jar          — third-party / project jars carried IN the bundle. Present only for
 *                            [ClasspathEntry.Project] fallbacks and, since v3, [ClasspathEntry.Embedded]
 *                            entries (`resolution = "embedded"` / `"mixed"`). Absent for a pure
 *                            `coordinates` pack.
 * ir/<id>.<ext>            — (v5) the captured **intermediate representation** for a preview whose
 *                            flavour has one: a Remote Compose document byte stream
 *                            (`<id>.rcdoc`) or a Wear protolayout `Layout` proto (`<id>.tilelayout`,
 *                            with the companion resources proto as `<id>.tileresources`). A player
 *                            replays the IR directly through the Remote Compose / ProtoLayout
 *                            runtime — it needs neither the consumer's `@Preview` bytecode nor the
 *                            full Compose graph that produced it. See [BundleIr] and
 *                            [BundleManifest.intermediateRepresentations].
 * signatures.json          — (v8, optional) one or more detached producer signatures over the
 *                            bundle's **canonical digest** (see [BundleSignatures]). Lets a verifier
 *                            (the public preview server) prove a bundle came from a producer it
 *                            trusts before it will re-render the bundle's executable Compose. Purely
 *                            additive and **excluded from the digest it signs**, so a second producer
 *                            can append its own signature without invalidating the first. Absent on
 *                            an unsigned bundle.
 * extensions/<id>.json     — (v7, optional) a data extension's report (a11y findings, theme tokens,
 *                            drawn strings, …) **sliced to the cover (default) preview** — the one
 *                            shown as the leading PNG — so the headline image carries its detailed
 *                            results and the bundle doesn't drag along data for previews it doesn't
 *                            show. A detached reader surfaces the extension's data without
 *                            re-rendering. `previews.json`'s `dataExtensionReports` names these
 *                            reports by extension id; this directory carries their (scoped) *bytes*
 *                            and [BundleManifest.dataExtensions] records the mapping. Present only
 *                            for an opt-in `--include-data-extensions` pack. See [BundleDataExtension]
 *                            and [BUNDLE_EXTENSIONS_DIR].
 * report.json              — [MinimizationReport]: which deps contributed reachable classes
 * web/                     — (optional) a self-contained web embed added after packing by
 *                            `compose-preview bundle embed --in-bundle`: `web/index.html` +
 *                            `web/compose-preview-embed.js` (a `<compose-preview-gallery>` web
 *                            component with the baked previews inlined). Purely additive — the
 *                            renderer / daemon never read it — so a bundle with a `web/` directory is
 *                            still a valid polyglot. Not written by this task.
 * ```
 *
 * # Multiple previews, detached from a project
 *
 * The leading PNG is a *single* default image, but a bundle can carry many previews. Their rendered
 * PNGs are baked into the well-known [BUNDLE_PREVIEWS_DIR] directory so a reader can show every
 * preview **without re-rendering and without the originating Gradle project on disk** — the bundle
 * is fully self-describing when opened from `~/Downloads`, a chat attachment, or a gist. The
 * `classes/app.jar` + classpath are still present for tooling that wants a *live* re-render (the VS
 * Code panel, the desktop daemon), but they are no longer required just to look at the images.
 *
 * **Dependency carriage.** In the default `resolution = "coordinates"` pack there is no `libs/`
 * directory: Maven / Google-resolvable dependencies are recorded as coordinates in
 * [BundleManifest.classpath] and the player (`compose-preview bundle open`, VS Code extension)
 * re-resolves them from the consumer's normal Gradle / Maven repos at open time. That keeps a
 * one-preview bundle ~100 KB instead of dragging the whole Compose graph in. A `resolution =
 * "embedded"` pack (and non-Gradle producers that can't emit coordinates) instead carries the
 * reachable jars in `libs/` as [ClasspathEntry.Embedded] so the bundle renders with no network and
 * no consumer build system — trading size for portability.
 *
 * For Android backends, [ClasspathEntry.Maven.type] = `"aar"` records that the player must resolve
 * the **unprocessed** AAR (not the extracted classes.jar) so AGP's artifact transforms run as they
 * would in a normal build.
 *
 * # Intermediate-representation previews (v5)
 *
 * Some preview flavours don't need their producing code re-executed to render again — they declare
 * a serialisable **intermediate representation** that a small runtime can replay on its own:
 * - **Remote Compose** (`@PreviewWrapper(RemotePreviewWrapper::class)` composables) captures a
 *   `RemoteDocument` byte stream — the "RC doc". A `RemoteDocumentPlayer` paints it back with no
 *   reference to the Kotlin that authored it.
 * - **Wear Tiles / ProtoLayout** (`@androidx.wear.tiles.tooling.preview.Preview`) produces a
 *   `LayoutElementBuilders.Layout` protobuf plus a `ResourceBuilders.Resources` proto. A
 *   `TileRenderer` inflates the proto with no reference to the `fun foo(): TilePreviewData` that
 *   built it.
 *
 * For such previews the bundle carries the IR bytes under `ir/<id>.<ext>` and records a [BundleIr]
 * in [BundleManifest.intermediateRepresentations]; the enclosing class is dropped from the
 * minimisation closure seed, so the consumer bytecode that produced it is **not** packed. The
 * player dispatches on the recorded [BundleIr.format] and replays through the Remote Compose /
 * ProtoLayout library instead of loading consumer classes. A bundle can mix IR-backed and
 * classpath-backed previews; each preview is independently either listed in
 * `intermediateRepresentations` (replayed from IR) or seeded into `classes/app.jar` (replayed by
 * re-running its composable).
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  /** Backend the bundle was packed for. v1 = "desktop"; "android" follows. */
  val backend: String,
  /** Selected preview ids (matches `previews.json[].id`). First entry = cover. */
  val previewIds: List<String>,
  /** Preview id whose PNG forms the polyglot's leading bytes. Usually `previewIds[0]`. */
  val coverPreviewId: String?,
  /**
   * Classpath in load order. First entry is always [ClasspathEntry.Module] for the inlined
   * `classes/app.jar`; remaining entries are [ClasspathEntry.Maven] coordinates the player resolves
   * at open time, [ClasspathEntry.Embedded] jars carried inside the bundle's `libs/` (no resolution
   * needed), or [ClasspathEntry.Project] fallbacks for local artifacts that had to be inlined
   * alongside the app jar.
   */
  val classpath: List<ClasspathEntry>,
  /** Source Gradle path that produced the bundle, e.g. `:samples:cmp`. */
  val modulePath: String,
  /** `BUNDLE_VERSION`-shaped identifier of the producer for diagnostics. */
  val producedBy: String,
  /**
   * Build system that produced the bundle: `"gradle"` (this plugin), `"amper"`, or `"bazel"` (the
   * contrib drivers). Informational — lets a player report provenance and pick heuristics without
   * sniffing the classpath. Defaults to `"gradle"` so a v2 bundle (which omits the field) decodes
   * as Gradle-produced.
   */
  val producer: String = PRODUCER_GRADLE,
  /**
   * How the player is expected to assemble the third-party classpath:
   * - [RESOLUTION_COORDINATES] — resolve [ClasspathEntry.Maven] entries from the consumer's repos
   *   (small bundle; the Gradle default, and the only mode a v2 bundle could express).
   * - [RESOLUTION_EMBEDDED] — everything reachable is carried in `libs/` as
   *   [ClasspathEntry.Embedded] (larger bundle, but renders with no network / no consumer build
   *   system — the portable hand-off mode).
   * - [RESOLUTION_MIXED] — coordinate-less deps embedded, the rest referenced by coordinate.
   *
   * Defaults to [RESOLUTION_COORDINATES] for v2 back-compat.
   */
  val resolution: String = RESOLUTION_COORDINATES,
  /**
   * (v5) Per-preview intermediate-representation records. Each entry names a preview that is
   * replayed from a captured IR ([BundleIr.format] = [IR_FORMAT_REMOTECOMPOSE] /
   * [IR_FORMAT_PROTOLAYOUT]) rather than by re-running its composable. A preview appears here OR
   * has its enclosing class in `classes/app.jar`, never both. Empty (the default) on a classic
   * all-classes bundle, so a v4 reader that ignores this field still decodes a v5 classpath bundle
   * correctly. See the "Intermediate-representation previews" section above.
   */
  val intermediateRepresentations: List<BundleIr> = emptyList(),
  /**
   * (v6) Android resource carriage for IR replay. Present only when the bundle carries protolayout
   * (Wear tile) IR: replaying a tile drives `TileRenderer`, which resolves the library theme
   * `androidx.wear.protolayout.renderer.R.style.ProtoLayoutBaseTheme` through `getResources()` and
   * links the non-final library `R$style` *class*. A detached daemon has neither the merged
   * resource table (no AGP build) nor the generated R classes (an AAR's published `classes.jar`
   * omits them), so without this carriage tile replay dies with `NoClassDefFoundError` on `R$style`
   * and then `Unknown resource value type 0`. The record points at the AGP-built merged resource
   * APK + manifest and the generated R classes packed under `android/`; the player rebuilds a
   * Robolectric `com/android/tools/test_config.properties` from them. `null` for desktop bundles
   * and for Android bundles with no protolayout IR (classic / Remote-Compose-only previews need
   * none). Additive — a pre-v6 reader ignores the field and the `android/` entries.
   */
  val androidResources: BundleAndroidResources? = null,
  /**
   * (v7) Optional carriage of the per-extension data reports a render produced. Each entry names a
   * data extension whose report (the file `previews.json`'s `dataExtensionReports` points at) is
   * packed under `extensions/<id>.json` ([BUNDLE_EXTENSIONS_DIR]) — **sliced to the cover (default)
   * preview**, the one shown as the leading PNG — so a detached reader can surface a11y findings /
   * theme tokens / drawn strings / … for the headline image without re-rendering. Empty unless the
   * producer was asked to include extension data (the opt-in `--include-data-extensions` /
   * `-PbundleIncludeDataExtensions=true` pack) — the default pack stays small and carries no
   * reports. Additive: the field defaults to empty and `ignoreUnknownKeys` readers skip the
   * `extensions/` entries, so a pre-v7 reader opening a v7 bundle still works; only a reader that
   * wants the carried data needs to be v7-aware. See [BundleDataExtension].
   *
   * When this carriage is present the bundled `previews.json`'s `dataExtensionReports` map is
   * realigned to the same in-bundle `extensions/<id>.json` paths, so the two pointers agree and
   * both resolve inside the bundle (the producer's original module-relative report paths don't).
   */
  val dataExtensions: List<BundleDataExtension> = emptyList(),
  /**
   * (v8, post-pack) Large binary resources (fonts, …) that were **lifted out** of `classes/app.jar`
   * by the `compose-preview bundle externalize` step and are fetched on demand instead of carried
   * inline. Each entry records the resource's original classpath path (e.g.
   * `fonts/Roboto-Regular.ttf`), the lowercase-hex SHA-256 of its bytes, and its size. The
   * externalize step publishes the bytes content-addressed (by [BundleExternalResource.sha256])
   * beside the bundle, and a re-rendering server rehydrates them into a shared hash-keyed cache and
   * back onto the daemon classpath at their recorded [BundleExternalResource.path], so
   * `getResourceAsStream("/fonts/…")` resolves exactly as it did with the fonts inline. Empty on a
   * normal pack (the bundle stays self-contained) — populated only after an explicit externalize,
   * which is why it's additive and doesn't bump [schemaVersion] (a post-pack transform, like
   * signing). A pre-externalize reader ignores it and just finds fewer resources in the jar; a
   * font-parity render needs the rehydration. See [BundleExternalResource].
   */
  val externalResources: List<BundleExternalResource> = emptyList(),
)

/**
 * One resource lifted out of `classes/app.jar` by `bundle externalize` and fetched on demand. See
 * [BundleManifest.externalResources]. The bytes are published content-addressed by [sha256] beside
 * the bundle (`bundle/res/<sha256>` on the design-artifacts branch); a server rehydrates them onto
 * the daemon classpath at [path] so resource lookups resolve unchanged.
 */
@Serializable
data class BundleExternalResource(
  /**
   * The resource's classpath-relative path inside the original jar, e.g.
   * `fonts/Roboto-Regular.ttf`.
   */
  val path: String,
  /**
   * Lowercase-hex SHA-256 of the resource bytes — the content-addressed key it's published under.
   */
  val sha256: String,
  /** Size of the resource in bytes, for diagnostics + a fetch sanity check. */
  val size: Long,
)

/**
 * One data-extension report carried inside the bundle, sliced to the cover (default) preview. The
 * bytes live under `extensions/<id>.json` ([path]); a reader keys on [extensionId] to know which
 * extension produced them and decodes the JSON against that extension's published DTOs (the same
 * shape its live render result carries). The id matches the key in `previews.json`'s
 * `dataExtensionReports` map (e.g. `"a11y"`). Join [BundleManifest.coverPreviewId] to the report's
 * per-preview entries to line the data up with the leading PNG.
 */
@Serializable
data class BundleDataExtension(
  /** Stable extension id, e.g. `"a11y"`. Matches the `dataExtensionReports` map key. */
  val extensionId: String,
  /** Posix zip path of the carried report bytes, e.g. `extensions/a11y.json`. */
  val path: String,
)

/**
 * (v6) Android resource artefacts carried for protolayout IR replay. See
 * [BundleManifest.androidResources]. All paths are posix zip paths inside the bundle.
 */
@Serializable
data class BundleAndroidResources(
  /**
   * Zip path of the merged resource APK (AAPT2 `apk-for-local-test.ap_`), e.g.
   * `android/resources.ap_`.
   */
  val resourceApkPath: String,
  /** Zip path of the merged `AndroidManifest.xml` Robolectric reads the package + theme from. */
  val mergedManifestPath: String,
  /**
   * Zip path of the jar holding the generated library R classes (`androidx.wear.protolayout.*.R$*`
   * etc.) the tile renderer links against, e.g. `android/r-classes.jar`. `null` when no R classes
   * were found to carry (the renderer then relies on whatever is already reachable).
   */
  val rClassesJarPath: String? = null,
  /**
   * Consumer application package (`android_custom_package`), recorded for the synthesized config.
   */
  val applicationPackage: String? = null,
)

/**
 * One preview replayed from a captured intermediate representation rather than from its consumer
 * bytecode. The player keys on [format] to pick the replay library and reads the IR bytes from
 * [path] (and [resourcesPath] for protolayout).
 */
@Serializable
data class BundleIr(
  /** Preview id this IR renders; matches an entry in [BundleManifest.previewIds]. */
  val previewId: String,
  /**
   * IR flavour: [IR_FORMAT_REMOTECOMPOSE], [IR_FORMAT_PROTOLAYOUT], [IR_FORMAT_LOTTIE], or
   * [IR_FORMAT_SVG].
   */
  val format: String,
  /** Posix zip path of the IR bytes, e.g. `ir/<id>.rcdoc` or `ir/<id>.tilelayout`. */
  val path: String,
  /**
   * Posix zip path of a companion artefact the format needs, e.g. the protolayout
   * `ResourceBuilders.Resources` proto (`ir/<id>.tileresources`). `null` for formats that carry
   * everything in [path] (Remote Compose).
   */
  val resourcesPath: String? = null,
)

/**
 * (v8) The detached producer signatures carried in `signatures.json` ([BUNDLE_SIGNATURES_PATH]).
 *
 * # Why a public preview server needs this
 *
 * A portable bundle's baked PNGs and IR (`previews/<id>.png`, `ir/<id>.rcdoc`, …) are **data** —
 * replaying them executes no consumer code, so a public server renders them safely from any
 * uploader. But a bundle that carries `classes/app.jar` can be **re-rendered**, which runs the
 * producer's Kotlin on the server. A public server must therefore only re-render a bundle it can
 * attribute to a producer it trusts. A signature is that attribution: the producer signs the
 * bundle's canonical digest with a private key, and the server verifies it against an allowlist of
 * trusted public keys (multiple producers, each with a `keyId`). Unsigned / untrusted bundles still
 * serve their data tiers; only server-side re-render is gated.
 *
 * # Canonical digest (what a signature signs)
 *
 * The signed bytes are **not** the raw file (zip ordering / compression aren't stable and
 * `signatures.json` itself must be excluded so signatures can be appended independently). Instead
 * the digest is computed over the bundle's logical content:
 * 1. Enumerate every zip entry **except** `signatures.json` and directory entries.
 * 2. For each, form the line `"<posix-path>:<lowercase-hex-sha256-of-bytes>"`.
 * 3. Sort the lines by path (UTF-8 byte order), join with `"\n"`.
 * 4. The **canonical digest** is the SHA-256 of that joined string's UTF-8 bytes.
 *
 * A signature is `Ed25519(privateKey, canonicalDigest)`. Verification recomputes the digest from
 * the received bundle and checks each signature against the trusted public key named by its
 * `keyId`. Tampering with any covered entry changes a per-entry hash → changes the digest → every
 * signature fails. The reference implementation lives in `:cli` (`BundleSigning`), which the
 * `compose-preview bundle sign` / `verify` commands and the serve verifier share.
 */
@Serializable
data class BundleSignatures(
  /** Schema id, pinned so a verifier can detect a format break. [BUNDLE_SIGNATURES_SCHEMA]. */
  val schema: String = BUNDLE_SIGNATURES_SCHEMA,
  /** One entry per producer that signed this bundle. At least one on a signed bundle. */
  val signatures: List<BundleSignature>,
)

/** One producer's detached signature over the bundle's canonical digest. See [BundleSignatures]. */
@Serializable
data class BundleSignature(
  /**
   * Stable id of the signing key, e.g. `"compose-ai-tools-ci"`. The verifier's trust store maps
   * this to a trusted public key; it's also how a second producer's signature is told apart from
   * the first. Free-form but conventionally `[A-Za-z0-9._@-]+`.
   */
  val keyId: String,
  /** Signature algorithm. Only [SIGNATURE_ALG_ED25519] is defined today. */
  val algorithm: String = SIGNATURE_ALG_ED25519,
  /**
   * Lowercase-hex SHA-256 canonical digest the signature was computed over (see
   * [BundleSignatures]).
   */
  val digest: String,
  /** Base64 (standard, padded) of the raw Ed25519 signature bytes over [digest]'s raw bytes. */
  val signature: String,
  /** Human-readable producer label for diagnostics, e.g. `"Compose AI Tools CI"`. Optional. */
  val producer: String? = null,
  /**
   * Optional keyless-provenance attestation (GitHub OIDC / Sigstore). When present the verifier can
   * trust the signature by matching [BundleProvenance.identity] against its OIDC allowlist instead
   * of (or in addition to) a pinned public key — useful for CI-produced bundles with no long-lived
   * key.
   */
  val provenance: BundleProvenance? = null,
)

/** Keyless-provenance attestation attached to a [BundleSignature] (GitHub OIDC / Sigstore). */
@Serializable
data class BundleProvenance(
  /** Provenance flavour: [PROVENANCE_GITHUB_OIDC] or [PROVENANCE_SIGSTORE]. */
  val type: String,
  /**
   * The workload identity that produced the bundle, e.g. a GitHub Actions subject like
   * `repo:yschimke/compose-ai-tools:ref:refs/heads/main`. The verifier matches this against its
   * trusted-identity globs.
   */
  val identity: String,
  /** Optional opaque attestation bundle / certificate (Sigstore) for full offline verification. */
  val attestation: String? = null,
)

/** Discriminator field `kind`, values: `module`, `maven`, `project`. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ClasspathEntry {
  /** The minimized consumer-module jar inlined inside the bundle. */
  @Serializable
  @kotlinx.serialization.SerialName("module")
  data class Module(
    /** Posix relative path inside the bundle zip, e.g. `classes/app.jar`. */
    val path: String
  ) : ClasspathEntry

  /**
   * A Maven (or Google Maven, JitPack, …) coordinate the player resolves at open time. This is the
   * **canonical** way a bundle carries a third-party dependency: the bytes stay *detached* and the
   * player re-attaches them from wherever they live (Maven Central, the colleague's local Gradle /
   * Coursier cache, an internal mirror, a future content-addressable store). Embedding
   * ([ClasspathEntry.Embedded]) is only an offline fallback.
   *
   * Encoded as separate fields rather than a `group:artifact:version` string so consumers can pick
   * a subset (e.g. only allow certain groups) without re-parsing.
   */
  @Serializable
  @kotlinx.serialization.SerialName("maven")
  data class Maven(
    val group: String,
    val artifact: String,
    val version: String,
    /**
     * Packaging the player must resolve. `"jar"` for pure-JVM deps (desktop), `"aar"` for Android
     * library archives — the player resolves the unprocessed AAR so AGP can run its normal
     * artifact-transform pipeline.
     */
    val type: String,
    /**
     * Lowercase hex SHA-256 of the resolved artifact's bytes at pack time, or null when the
     * producer couldn't compute it (older bundles, non-Gradle producers). Lets a player that
     * re-attaches a *detached* dep from **any** source (Maven, a local cache, a mirror, a CAS)
     * check the fetched bytes against the bytes the bundle was built with.
     *
     * **Mismatch policy: warn, never fail.** A player MUST NOT refuse to render on a hash mismatch.
     * A different artifact for the same coordinate is usually *almost* compatible — a point-release
     * skew, a repackaged-but-equivalent jar, a stripped vs. full variant — and a preview that
     * renders slightly off is far more useful than no preview at all. So a mismatch (or a
     * missing/unverifiable hash) is a **noisy warning**: surface it loudly (which coordinate,
     * expected vs. actual hash) and proceed with the resolved bytes. There is no strict mode that
     * hard-fails. Verification is a fidelity signal, not a gate.
     */
    val sha256: String? = null,
  ) : ClasspathEntry

  /**
   * Project-local dep that had no Maven coordinate. The bundle inlines it alongside the consumer
   * jar so the artefact stays self-contained even when consumed offline.
   */
  @Serializable
  @kotlinx.serialization.SerialName("project")
  data class Project(
    /** Gradle path of the producing project, e.g. `:my-lib`. Informational. */
    val path: String,
    /** Posix relative path inside the bundle zip, e.g. `libs/my-lib.jar`. */
    val inlinedAs: String,
  ) : ClasspathEntry

  /**
   * A third-party dependency carried **inside** the bundle's `libs/` directory rather than
   * referenced by coordinate — no resolution, no network, no consumer build system needed to put it
   * on the classpath. Emitted by `--embed-deps` / `resolution = "embedded"` packs and by non-Gradle
   * producers that can't (or don't want to) express resolvable Maven coordinates. Unlike [Project],
   * an embedded entry carries no Gradle path — it's just "this jar, here".
   */
  @Serializable
  @kotlinx.serialization.SerialName("embedded")
  data class Embedded(
    /** Posix relative path inside the bundle zip, e.g. `libs/coil-compose-2.6.0.jar`. */
    val inlinedAs: String
  ) : ClasspathEntry
}

/** [BundleManifest.producer] values. */
const val PRODUCER_GRADLE: String = "gradle"

/** [BundleManifest.resolution] values. */
const val RESOLUTION_COORDINATES: String = "coordinates"

const val RESOLUTION_EMBEDDED: String = "embedded"

const val RESOLUTION_MIXED: String = "mixed"

/** [BundleIr.format] values. */
const val IR_FORMAT_REMOTECOMPOSE: String = "remotecompose"

const val IR_FORMAT_PROTOLAYOUT: String = "protolayout"

/**
 * [BundleIr.format] for a Lottie animation asset. Unlike Remote Compose / protolayout — whose IR is
 * *captured* by running a composable — a Lottie preview's IR is the asset file itself, read
 * straight off the module resources at pack time. The zip entry keeps the asset's own extension
 * (`ir/<id>.json` or `ir/<id>.lottie`); `format` is what the replayer keys on.
 */
const val IR_FORMAT_LOTTIE: String = "lottie"

/**
 * [BundleIr.format] for an SVG image asset. Like [IR_FORMAT_LOTTIE] its IR is the asset file itself
 * — the raw `.svg` read straight off the module resources at pack time — so the bundle is
 * self-contained and travels the source artwork regardless of which render subdir the still PNG
 * landed in. The zip entry keeps the `.svg` extension (`ir/<id>.svg`); `format` is what a replayer
 * keys on. Static, so there is no animated companion (contrast [IR_FORMAT_LOTTIE]).
 */
const val IR_FORMAT_SVG: String = "svg"

/** Well-known directory inside the bundle zip holding per-preview IR bytes (`ir/<id>.<ext>`). */
const val BUNDLE_IR_DIR: String = "ir"

/** Well-known zip path of the detached producer signatures (v8). See [BundleSignatures]. */
const val BUNDLE_SIGNATURES_PATH: String = "signatures.json"

/** Schema id stamped into [BundleSignatures.schema]. */
const val BUNDLE_SIGNATURES_SCHEMA: String = "compose-preview-bundle/signatures/v1"

/** [BundleSignature.algorithm] value for an Ed25519 signature (the only one defined today). */
const val SIGNATURE_ALG_ED25519: String = "ed25519"

/** [BundleProvenance.type] values. */
const val PROVENANCE_GITHUB_OIDC: String = "github-oidc"

const val PROVENANCE_SIGSTORE: String = "sigstore"

/** File extension for a captured Remote Compose document byte stream. */
const val IR_EXT_REMOTECOMPOSE: String = "rcdoc"

/** File extension for a captured Wear protolayout `Layout` proto. */
const val IR_EXT_PROTOLAYOUT_LAYOUT: String = "tilelayout"

/** File extension for the companion protolayout `Resources` proto. */
const val IR_EXT_PROTOLAYOUT_RESOURCES: String = "tileresources"

/** Well-known directory inside the bundle zip holding Android resource carriage (v6). */
const val BUNDLE_ANDROID_DIR: String = "android"

/** Zip path of the carried merged resource APK (v6). See [BundleAndroidResources]. */
const val ANDROID_RESOURCE_APK_PATH: String = "android/resources.ap_"

/** Zip path of the carried merged `AndroidManifest.xml` (v6). */
const val ANDROID_MERGED_MANIFEST_PATH: String = "android/AndroidManifest.xml"

/** Zip path of the carried generated R-class jar (v6). */
const val ANDROID_R_CLASSES_JAR_PATH: String = "android/r-classes.jar"

/**
 * Well-known directory inside the bundle zip holding optional per-extension data reports
 * (`extensions/<extensionId>.json`), one verbatim sidecar per [BundleManifest.dataExtensions] entry
 * (v7).
 */
const val BUNDLE_EXTENSIONS_DIR: String = "extensions"

/**
 * Conventional on-disk report filenames (relative to the preview output dir, i.e. `previews.json`'s
 * parent) for built-in data extensions that write their aggregated report **without** stamping
 * `previews.json`'s `dataExtensionReports` pointer. An `--include-data-extensions` pack probes
 * these for any registered extension the manifest names no report for, so a report produced by the
 * standard flow is still carried — the daemon / `compose-preview a11y` writes `accessibility.json`
 * but the standalone plugin leaves the manifest map empty (`ComposePreviewTasks` discovery). A
 * manifest pointer, when present, always wins over the conventional fallback. Keyed by the same
 * extension id as `dataExtensionReports` / [BundleDataExtension.extensionId]; mirrors the
 * conventional fallback in `:cli`'s `A11yReportRenderer`.
 */
val CONVENTIONAL_DATA_EXTENSION_REPORTS: Map<String, String> = mapOf("a11y" to "accessibility.json")

/**
 * Schema version stamped into [BundleManifest.schemaVersion].
 * - v1 — `bundle.json` + `previews.json` + `classes/app.jar` + `report.json`, cover PNG as the
 *   polyglot's leading bytes only.
 * - v2 — adds the [BUNDLE_PREVIEWS_DIR] directory: a baked PNG per selected preview so the bundle
 *   renders detached from its project. Readers gate on `>= 2` before looking for
 *   `previews/<id>.png` (v1 bundles simply have no such directory); the additive zip entries are
 *   otherwise ignored by `ignoreUnknownKeys` readers, so a v1 reader opening a v2 bundle still
 *   works.
 * - v3 — adds [BundleManifest.producer] / [BundleManifest.resolution] and the
 *   [ClasspathEntry.Embedded] kind for `libs/`-carried third-party deps (the `--embed-deps` /
 *   `resolution = "embedded"` portable-hand-off mode and non-Gradle producers). Both new manifest
 *   fields default, and `ignoreUnknownKeys` readers skip the `embedded` discriminator they don't
 *   recognise, so a v2 reader opening a v3 *coordinate* bundle still works; only the embedded jars
 *   need a v3-aware player.
 * - v4 — adds [ClasspathEntry.Maven.sha256], the content hash that makes a *detached* coordinate
 *   safe to re-attach from any source (Maven, a local cache, a mirror, a CAS): the player resolves
 *   the coordinate however it can, then verifies the bytes against the hash. Purely additive —
 *   `sha256` defaults to null, so a v3 reader opening a v4 bundle just ignores it and an older
 *   bundle reads as "unverifiable coordinate".
 * - v5 — adds [BundleManifest.intermediateRepresentations] and the `ir/` directory: previews with a
 *   serialisable IR (Remote Compose doc, Wear protolayout proto) are replayed from the IR via the
 *   matching runtime library instead of by re-running their consumer bytecode, which is then
 *   dropped from `classes/app.jar`. Additive — the field defaults to empty and `ignoreUnknownKeys`
 *   readers skip the `ir/` entries, so a v4 reader opening a v5 *classpath* bundle still works;
 *   only the IR previews need a v5-aware player.
 * - v6 — adds [BundleManifest.androidResources] and the `android/` directory: an Android bundle
 *   carrying protolayout (Wear tile) IR also carries the AGP-built merged resource APK + manifest
 *   and the generated library R classes, so a detached daemon can resolve the tile renderer's theme
 *   resource and link its `R$style` class on replay. Additive — the field defaults to null and
 *   `ignoreUnknownKeys` readers skip the `android/` entries, so a v5 reader opening a v6 bundle
 *   still works; only protolayout IR replay on a detached Android daemon needs a v6-aware player.
 * - v7 — adds [BundleManifest.dataExtensions] and the `extensions/` directory: an opt-in pack
 *   (`--include-data-extensions`) carries the per-extension data reports (a11y findings, theme
 *   tokens, drawn strings, …) named by `previews.json`'s `dataExtensionReports`, sliced to the
 *   cover (default) preview, so a detached reader can surface the headline image's data without
 *   re-rendering. Additive — the field defaults to empty and `ignoreUnknownKeys` readers skip the
 *   `extensions/` entries, so a v6 reader opening a v7 bundle still works; only a reader that wants
 *   the carried data needs to be v7-aware.
 * - v8 — adds the `previews/<id>.overrides.json` sidecar: the author-declared editable knobs a
 *   preview exposed via the `previewOverride*` lookups (the `compose/overrides` payload), captured
 *   during the normal render so a detached viewer can present editable controls (label / list
 *   length / per-item indexed values) with no live daemon. Convention-discovered (no manifest
 *   field), present only for previews that opted in. Additive — the sidecar is ignored by older
 *   readers and absent for previews that declare no knobs, so a v7 reader opening a v8 bundle still
 *   works; only a reader that wants the editable knobs needs to be v8-aware.
 *
 * Orthogonal to the version sequence above, the optional `signatures.json`
 * ([BUNDLE_SIGNATURES_PATH], [BundleSignatures]) carries detached producer signatures over the
 * bundle's canonical digest so a public preview server can attribute a bundle to a trusted producer
 * before re-rendering its executable Compose. It is excluded from the digest it signs and does
 * **not** bump [schemaVersion] (signing is a post-pack step, like `bundle embed`); an unsigned
 * bundle has no such entry and an unaware reader ignores it.
 */
const val BUNDLE_SCHEMA_VERSION: Int = 8

/**
 * File extension of the per-preview override sidecar the render step writes next to the PNG
 * (`renders/<stem>.overrides.json`) and the bundle packs under `previews/<id>.overrides.json`.
 * Holds the serialized `compose/overrides` payload — the editable knobs the preview declared. Kept
 * in lockstep with the consumer runtime's writer.
 */
const val BUNDLE_OVERRIDES_SIDECAR_EXT: String = "overrides.json"

/**
 * File extension of the per-preview Remote Compose knob sidecar the render step writes next to the
 * PNG (`renders/<stem>.remotecompose.json`) and the bundle packs under
 * `previews/<id>.remotecompose.json`. Holds the serialized `compose/remotecompose`
 * `RemoteComposeDeclarationsPayload` — the editable named-value knobs the preview declared through
 * `LocalRemoteComposeHost` (a separate channel from the plain-Compose `overrides.json`, since a
 * Remote Compose sticker's edits round-trip via `renderNow.overrides.remoteCompose` / the serve
 * `rc.<name>=` param, not the generic knob lane). Kept in lockstep with the consumer runtime's
 * writer (`RobolectricRenderTest.writeRemoteComposeSidecar`).
 */
const val BUNDLE_REMOTECOMPOSE_SIDECAR_EXT: String = "remotecompose.json"

/**
 * File extension of the per-sheet catalog-token sidecar the render step writes under
 * `data/catalog-tokens/<id>.catalog.json` (issue #2167) and the bundle packs under
 * `previews/<id>.catalog.json`. Holds the resolved `@ColorCatalog` / `@TypographyCatalog` token
 * values (hex / type metrics) so a detached reader — e.g. design-parity's `catalog-export` — can
 * import an annotation-declared palette or type scale without re-rendering. Only
 * `PreviewKind.CATALOG` sheets carry one. Kept in lockstep with the renderer's
 * `CatalogTokenSidecar` writer.
 */
const val BUNDLE_CATALOG_TOKENS_SIDECAR_EXT: String = "catalog.json"

/**
 * Well-known directory inside the bundle zip holding one rendered PNG per selected preview, keyed
 * by preview id: `previews/<previewId>.png`. The cover (the polyglot's leading bytes) is mirrored
 * here under its own id so a reader can iterate this single directory to enumerate every preview.
 */
const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Diagnostic record describing how aggressive the minimization was. Always written into the bundle
 * as `report.json` so users can audit whether the closure walk was effective.
 */
@Serializable
data class MinimizationReport(
  val entryClassFqns: List<String>,
  val reachableClassCount: Int,
  val totalScannedClassCount: Int,
  val moduleClasses: ModuleClassesStats,
  /** One entry per resolved runtime dep — kept ones list as [ClasspathEntry] in the manifest. */
  val dependencies: List<DependencyDecision>,
)

@Serializable
data class ModuleClassesStats(
  val totalClasses: Int,
  val reachableClasses: Int,
  val packedBytes: Long,
)

@Serializable
data class DependencyDecision(
  /** Original absolute path the dep resolved to (jar file). Useful for forensic comparison. */
  val sourcePath: String,
  /**
   * Maven coordinate string `group:artifact:version[:type]` when known; `null` for project deps.
   */
  val coordinate: String?,
  /** Gradle project path for project deps; `null` for Maven deps. */
  val projectPath: String?,
  val totalClasses: Int,
  val reachableClasses: Int,
  val originalBytes: Long,
  /** `true` when the dep contributed at least one class to the closure (and is in `classpath`). */
  val kept: Boolean,
)

/**
 * Writes a PNG + ZIP polyglot. The leading bytes are [coverPng] verbatim; the appended bytes are
 * [zipBytes] verbatim. Both inputs must already be valid in their respective formats; this writer
 * does not reframe chunks or rewrite the zip's central directory.
 *
 * Most image viewers and zip readers tolerate trailing/leading extra bytes respectively, so the raw
 * concatenation is enough to satisfy both formats. ZIP's End-Of-Central-Directory record is
 * searched from EOF (which is in the appended zip), and PNG's chunk loop terminates at the IEND
 * record (which is inside [coverPng]). See: <https://en.wikipedia.org/wiki/Polyglot_(computing)>.
 */
internal fun writePngZipPolyglot(coverPng: ByteArray, zipBytes: ByteArray, out: File) {
  out.parentFile?.mkdirs()
  out.outputStream().use { stream ->
    stream.write(coverPng)
    stream.write(zipBytes)
  }
}

/**
 * Reads a bundle file produced by [writePngZipPolyglot] (or a plain `.zip`) and returns the zip
 * bytes. Detects the PNG signature on the leading bytes and seeks past the IEND chunk to find the
 * zip start; plain zips (signature `PK\x03\x04`) are returned as-is.
 *
 * Throws [IllegalArgumentException] when neither signature is found.
 */
internal fun extractZipBytes(file: File): ByteArray {
  val bytes = file.readBytes()
  if (bytes.size < 8) {
    throw IllegalArgumentException("not a bundle: ${file.path} is too small (${bytes.size}B)")
  }
  if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
    return bytes
  }
  if (isPngSignature(bytes)) {
    val zipStart = pngLength(bytes)
    return bytes.copyOfRange(zipStart, bytes.size)
  }
  throw IllegalArgumentException(
    "not a bundle: ${file.path} — leading bytes match neither PNG (\\x89PNG…) nor ZIP (PK\\x03\\x04)"
  )
}

private val PNG_SIGNATURE: ByteArray =
  byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 P N G \r \n SUB \n

private fun isPngSignature(bytes: ByteArray): Boolean {
  if (bytes.size < PNG_SIGNATURE.size) return false
  for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
  return true
}

/**
 * Returns the byte offset of the first byte past the PNG's IEND chunk — equivalently, the length of
 * the leading PNG in the polyglot. Each chunk is `[length:4][type:4][data:length][crc:4]`; the
 * stream ends after IEND's CRC.
 */
private fun pngLength(bytes: ByteArray): Int {
  var offset = PNG_SIGNATURE.size
  while (offset < bytes.size) {
    val length =
      ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
    val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
    offset += 4 + 4 + length + 4
    if (type == "IEND") return offset
  }
  throw IllegalArgumentException("truncated PNG: IEND not found before EOF")
}
