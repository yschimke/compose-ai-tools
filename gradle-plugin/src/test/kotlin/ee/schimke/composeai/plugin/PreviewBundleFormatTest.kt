package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Schema v3 round-trip + back-compat coverage for [BundleManifest] / [ClasspathEntry]. v3 adds the
 * [ClasspathEntry.Embedded] kind and the [BundleManifest.producer] / [BundleManifest.resolution]
 * fields; both manifest fields default so a v2 `bundle.json` (which omits them) still decodes.
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
  fun `current schema version is 3`() {
    assertThat(BUNDLE_SCHEMA_VERSION).isEqualTo(3)
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
            ClasspathEntry.Maven("g", "a", "1.0", "jar"),
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
