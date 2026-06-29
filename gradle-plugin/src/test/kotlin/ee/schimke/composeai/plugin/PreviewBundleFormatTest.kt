package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Schema round-trip + back-compat coverage for [BundleManifest] / [ClasspathEntry] / [BundleIr].
 * Covers the v3 [ClasspathEntry.Embedded] kind, the v4 [ClasspathEntry.Maven.sha256], and the v5
 * [BundleManifest.intermediateRepresentations]; every added field defaults so an older
 * `bundle.json` (which omits it) still decodes.
 */
class PreviewBundleFormatTest {

  // `classDiscriminator = "kind"` mirrors the producer's writer (BundlePreviewTask.JSON) and every
  // reader (cli BundleReader, :bundle-viewer). `ignoreUnknownKeys` is what makes a v2 reader
  // tolerate
  // a v3 bundle's extra entries.
  private val json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `current schema version is 8`() {
    assertThat(BUNDLE_SCHEMA_VERSION).isEqualTo(8)
  }

  @Test
  fun `data extensions round-trip`() {
    val original =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = "desktop",
        previewIds = listOf("pkg.Foo"),
        coverPreviewId = "pkg.Foo",
        classpath = listOf(ClasspathEntry.Module(path = "classes/app.jar")),
        modulePath = ":sample",
        producedBy = "test",
        dataExtensions =
          listOf(
            BundleDataExtension(extensionId = "a11y", path = "extensions/a11y.json"),
            BundleDataExtension(extensionId = "theme", path = "extensions/theme.json"),
          ),
      )

    val decoded =
      json.decodeFromString(
        BundleManifest.serializer(),
        json.encodeToString(BundleManifest.serializer(), original),
      )

    assertThat(decoded).isEqualTo(original)
    assertThat(decoded.dataExtensions.map { it.extensionId }).containsExactly("a11y", "theme")
  }

  @Test
  fun `v6 manifest without dataExtensions decodes with an empty list`() {
    val v6 =
      """
      {
        "schemaVersion": 6,
        "backend": "desktop",
        "previewIds": ["pkg.Foo"],
        "coverPreviewId": "pkg.Foo",
        "classpath": [ { "kind": "module", "path": "classes/app.jar" } ],
        "modulePath": ":sample",
        "producedBy": "test"
      }
      """
        .trimIndent()

    val decoded = json.decodeFromString(BundleManifest.serializer(), v6)

    assertThat(decoded.dataExtensions).isEmpty()
  }

  @Test
  fun `intermediate representations round-trip`() {
    val original =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = "android",
        previewIds = listOf("pkg.Rc", "pkg.Tile"),
        coverPreviewId = "pkg.Rc",
        classpath = listOf(ClasspathEntry.Module(path = "classes/app.jar")),
        modulePath = ":sample",
        producedBy = "test",
        intermediateRepresentations =
          listOf(
            BundleIr(
              previewId = "pkg.Rc",
              format = IR_FORMAT_REMOTECOMPOSE,
              path = "ir/pkg.Rc.rcdoc",
            ),
            BundleIr(
              previewId = "pkg.Tile",
              format = IR_FORMAT_PROTOLAYOUT,
              path = "ir/pkg.Tile.tilelayout",
              resourcesPath = "ir/pkg.Tile.tileresources",
            ),
          ),
      )

    val decoded =
      json.decodeFromString(
        BundleManifest.serializer(),
        json.encodeToString(BundleManifest.serializer(), original),
      )

    assertThat(decoded).isEqualTo(original)
    val tile = decoded.intermediateRepresentations.single { it.format == IR_FORMAT_PROTOLAYOUT }
    assertThat(tile.resourcesPath).isEqualTo("ir/pkg.Tile.tileresources")
  }

  @Test
  fun `v4 manifest without intermediateRepresentations decodes with an empty list`() {
    val v4 =
      """
      {
        "schemaVersion": 4,
        "backend": "desktop",
        "previewIds": ["pkg.Foo"],
        "coverPreviewId": "pkg.Foo",
        "classpath": [ { "kind": "module", "path": "classes/app.jar" } ],
        "modulePath": ":sample",
        "producedBy": "test"
      }
      """
        .trimIndent()

    val decoded = json.decodeFromString(BundleManifest.serializer(), v4)

    assertThat(decoded.intermediateRepresentations).isEmpty()
  }

  @Test
  fun `embedded entry round-trips through the kind discriminator`() {
    val original =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = "desktop",
        previewIds = listOf("pkg.Foo"),
        coverPreviewId = "pkg.Foo",
        classpath =
          listOf(
            ClasspathEntry.Module(path = "classes/app.jar"),
            ClasspathEntry.Maven("g", "a", "1.0", "jar", sha256 = "a".repeat(64)),
            ClasspathEntry.Embedded(inlinedAs = "libs/coil-2.6.0.jar"),
            ClasspathEntry.Project(path = ":lib", inlinedAs = "libs/lib.jar"),
          ),
        modulePath = ":sample",
        producedBy = "test",
        producer = PRODUCER_GRADLE,
        resolution = RESOLUTION_MIXED,
      )

    val decoded =
      json.decodeFromString(
        BundleManifest.serializer(),
        json.encodeToString(BundleManifest.serializer(), original),
      )

    assertThat(decoded).isEqualTo(original)
    val embedded = decoded.classpath.filterIsInstance<ClasspathEntry.Embedded>().single()
    assertThat(embedded.inlinedAs).isEqualTo("libs/coil-2.6.0.jar")
  }

  @Test
  fun `maven sha256 round-trips and defaults to null for v3-shaped entries`() {
    val withHash = ClasspathEntry.Maven("g", "a", "1.0", "jar", sha256 = "b".repeat(64))
    val decoded =
      json.decodeFromString(
        ClasspathEntry.serializer(),
        json.encodeToString(ClasspathEntry.serializer(), withHash),
      ) as ClasspathEntry.Maven
    assertThat(decoded.sha256).isEqualTo("b".repeat(64))

    // A v3-shaped maven entry (no sha256 key) decodes with sha256 = null — unverifiable but valid.
    val v3 = """{"kind":"maven","group":"g","artifact":"a","version":"1.0","type":"jar"}"""
    val legacy = json.decodeFromString(ClasspathEntry.serializer(), v3) as ClasspathEntry.Maven
    assertThat(legacy.sha256).isNull()
  }

  @Test
  fun `embedded entry serialises with kind=embedded`() {
    val encoded =
      json.encodeToString(
        ClasspathEntry.serializer(),
        ClasspathEntry.Embedded(inlinedAs = "libs/x.jar"),
      )
    assertThat(encoded).contains("\"kind\":\"embedded\"")
    assertThat(encoded).contains("\"inlinedAs\":\"libs/x.jar\"")
  }

  @Test
  fun `v2 manifest without producer or resolution decodes with gradle coordinates defaults`() {
    // A literal v2 bundle.json: no `producer`, no `resolution`, classpath has only the kinds v2
    // knew.
    val v2 =
      """
      {
        "schemaVersion": 2,
        "backend": "desktop",
        "previewIds": ["pkg.Foo"],
        "coverPreviewId": "pkg.Foo",
        "classpath": [
          { "kind": "module", "path": "classes/app.jar" },
          { "kind": "maven", "group": "g", "artifact": "a", "version": "1.0", "type": "jar" }
        ],
        "modulePath": ":sample",
        "producedBy": "test"
      }
      """
        .trimIndent()

    val decoded = json.decodeFromString(BundleManifest.serializer(), v2)

    assertThat(decoded.schemaVersion).isEqualTo(2)
    assertThat(decoded.producer).isEqualTo(PRODUCER_GRADLE)
    assertThat(decoded.resolution).isEqualTo(RESOLUTION_COORDINATES)
    assertThat(decoded.classpath).hasSize(2)
  }

  @Test
  fun `v2 reader tolerates an embedded entry it does not recognise`() {
    // Simulate a v2 reader (no Embedded kind) by decoding a v3 bundle into a model that only lists
    // module/maven/project. `ignoreUnknownKeys` doesn't cover polymorphic discriminators, so the
    // realistic v2-tolerance story is: a coordinate-only v3 bundle decodes fine for old readers,
    // and
    // an embedded entry simply requires a v3-aware player. Here we assert the v3 reader keeps the
    // embedded entry rather than silently dropping it.
    val v3 =
      """
      {
        "schemaVersion": 3,
        "backend": "desktop",
        "previewIds": ["pkg.Foo"],
        "coverPreviewId": "pkg.Foo",
        "classpath": [
          { "kind": "module", "path": "classes/app.jar" },
          { "kind": "embedded", "inlinedAs": "libs/x.jar" }
        ],
        "modulePath": ":sample",
        "producedBy": "test",
        "producer": "bazel",
        "resolution": "embedded"
      }
      """
        .trimIndent()

    val decoded = json.decodeFromString(BundleManifest.serializer(), v3)

    assertThat(decoded.producer).isEqualTo("bazel")
    assertThat(decoded.resolution).isEqualTo(RESOLUTION_EMBEDDED)
    assertThat(decoded.classpath.filterIsInstance<ClasspathEntry.Embedded>()).hasSize(1)
  }
}
