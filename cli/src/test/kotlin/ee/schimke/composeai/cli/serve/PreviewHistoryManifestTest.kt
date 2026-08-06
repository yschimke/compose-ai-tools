package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewHistoryManifestTest {

  private val a = "a".repeat(40)
  private val b = "b".repeat(40)

  private fun gitLog(vararg entries: Pair<String, List<Pair<String, String>>>): String =
    buildString {
      entries.forEach { (subject, files) ->
        val sha = subject.hashCode().toUInt().toString(16).padStart(40, '0')
        append("\u0001").append(sha).append("\u001F").append("2026-08-06T10:00:00+00:00")
        append("\u001F").append(subject).append("\n\n")
        files.forEach { (path, blob) ->
          append(":100644 100644 ").append(b).append(' ').append(blob).append(" M\t").append(path)
          append('\n')
        }
      }
    }

  private fun timelines(log: String) = PreviewHistory.collapse(PreviewHistory.parseGitLog(log))

  private val baselines =
    """
    {
      "samples:wear/com.example.PreviewsKt.Foo_Large Round": {
        "module": "samples:wear",
        "renderBasename": "Foo_Large_Round.png",
        "sha256": "irrelevant"
      }
    }
    """
      .trimIndent()

  @Test
  fun `baselines entries reverse into a render-path lookup`() {
    val lookup = PreviewHistoryManifest.renderPathsToPreviewIds(baselines)

    assertEquals(
      mapOf(
        "renders/samples:wear/Foo_Large_Round.png" to
          "samples:wear/com.example.PreviewsKt.Foo_Large Round"
      ),
      lookup,
    )
  }

  @Test
  fun `entries missing module or basename are skipped rather than guessed`() {
    val partial =
      """
      {
        "no-module": { "renderBasename": "Foo.png" },
        "no-basename": { "module": "samples:wear" },
        "empty-module": { "module": "", "renderBasename": "Foo.png" },
        "not-an-object": 7,
        "good": { "module": "m", "renderBasename": "Foo.png" }
      }
      """
        .trimIndent()

    val lookup = PreviewHistoryManifest.renderPathsToPreviewIds(partial)

    assertEquals(mapOf("renders/m/Foo.png" to "good"), lookup)
  }

  @Test
  fun `malformed baselines yield an empty lookup instead of throwing`() {
    assertEquals(emptyMap(), PreviewHistoryManifest.renderPathsToPreviewIds("{not json"))
  }

  @Test
  fun `the manifest is keyed by preview id and carries the render path`() {
    val log =
      gitLog(
        "Update preview baselines from 27ea28c1" to
          listOf("renders/samples:wear/Foo_Large_Round.png" to a)
      )

    val manifest =
      PreviewHistoryManifest.build(
        timelines(log),
        PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
        generatedFrom = "tip",
      )

    val entry = manifest.previews.getValue("samples:wear/com.example.PreviewsKt.Foo_Large Round")
    assertEquals("renders/samples:wear/Foo_Large_Round.png", entry.path)
    assertEquals("27ea28c1", entry.versions.single().sourceSha, "source commit survives the join")
    assertEquals(PreviewHistoryManifest.FORMAT_VERSION, manifest.formatVersion)
    assertEquals("tip", manifest.generatedFrom)
  }

  @Test
  fun `a render with no preview id is dropped`() {
    // The normal fate of a deleted or renamed preview: still has branch commits, but nothing in
    // baselines.json addresses it, so no viewer could ask for it.
    val log = gitLog("publish" to listOf("renders/samples:wear/Orphaned.png" to a))

    val manifest =
      PreviewHistoryManifest.build(
        timelines(log),
        PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
        generatedFrom = "tip",
      )

    assertTrue(manifest.previews.isEmpty())
  }

  @Test
  fun `an unstable preview is marked and carries its trimmed versions`() {
    val path = "renders/samples:wear/Foo_Large_Round.png"
    val log = gitLog(*Array(8) { i -> "publish $i" to listOf(path to if (i % 2 == 0) a else b) })

    val entry =
      PreviewHistoryManifest.build(
          timelines(log),
          PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
          generatedFrom = "tip",
        )
        .previews
        .values
        .single()

    assertTrue(entry.unstable)
    assertEquals(6, entry.flapCount)
    assertEquals(8, entry.observations, "the raw count stays, so the trim is visible not hidden")
    assertEquals(2, entry.versions.size, "trimmed to the states it flips between")
    assertEquals(4, entry.versions.first().occurrences)
  }

  @Test
  fun `occurrences is omitted for the ordinary single-run version`() {
    val log = gitLog("publish" to listOf("renders/samples:wear/Foo_Large_Round.png" to a))

    val manifest =
      PreviewHistoryManifest.build(
        timelines(log),
        PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
        generatedFrom = "tip",
      )
    val encoded = PreviewHistoryManifest.encode(manifest)

    assertNull(manifest.previews.values.single().versions.single().occurrences)
    assertFalse(encoded.contains("occurrences"), "no redundant field on every stable entry")
  }

  @Test
  fun `sinceCommit is omitted when it would repeat commit`() {
    val log = gitLog("publish" to listOf("renders/samples:wear/Foo_Large_Round.png" to a))

    val manifest =
      PreviewHistoryManifest.build(
        timelines(log),
        PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
        generatedFrom = "tip",
      )
    val version = manifest.previews.values.single().versions.single()

    assertNull(version.sinceCommit, "single-publish version introduces itself")
    assertEquals(version.commit, version.introducedBy, "readers still get a commit")
    assertFalse(PreviewHistoryManifest.encode(manifest).contains("sinceCommit"))
  }

  @Test
  fun `sinceCommit is kept when a version spans several publishes`() {
    val path = "renders/samples:wear/Foo_Large_Round.png"
    val log = gitLog("newest" to listOf(path to a), "oldest" to listOf(path to a))

    val version =
      PreviewHistoryManifest.build(
          timelines(log),
          PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
          generatedFrom = "tip",
        )
        .previews
        .values
        .single()
        .versions
        .single()

    assertNotNull(version.sinceCommit)
    assertEquals(version.sinceCommit, version.introducedBy)
    assertTrue(version.sinceCommit != version.commit, "spans two distinct commits")
  }

  @Test
  fun `regenerating an unchanged branch produces a byte-identical file`() {
    // Otherwise every publish would show a spurious history.json diff on the delivery branch.
    val log =
      gitLog(
        "publish" to listOf("renders/m/B.png" to a, "renders/m/A.png" to b, "renders/m/C.png" to a)
      )
    val lookup =
      mapOf("renders/m/A.png" to "m/A", "renders/m/B.png" to "m/B", "renders/m/C.png" to "m/C")

    val first =
      PreviewHistoryManifest.encode(PreviewHistoryManifest.build(timelines(log), lookup, "tip"))
    val again =
      PreviewHistoryManifest.encode(PreviewHistoryManifest.build(timelines(log), lookup, "tip"))

    assertEquals(first, again)
    val order = Regex("\"(m/[ABC])\"").findAll(first).map { it.groupValues[1] }.toList()
    assertEquals(listOf("m/A", "m/B", "m/C"), order, "sorted, not git's discovery order")
  }

  @Test
  fun `the format version is always written to the wire`() {
    // Regression: formatVersion is a defaulted field and the encoder sets encodeDefaults = false
    // to keep redundant fields off the wire, which silently dropped the one discriminator a viewer
    // needs to reject an incompatible manifest. A round-trip assertion cannot catch this — decode
    // restores the default — so this asserts on the encoded text.
    val log = gitLog("publish" to listOf("renders/samples:wear/Foo_Large_Round.png" to a))

    val encoded =
      PreviewHistoryManifest.encode(
        PreviewHistoryManifest.build(
          timelines(log),
          PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
          generatedFrom = "tip",
        )
      )

    assertTrue(
      encoded.contains("\"formatVersion\": \"${PreviewHistoryManifest.FORMAT_VERSION}\""),
      "published manifests must carry the discriminator; got:\n${encoded.take(200)}",
    )
  }

  @Test
  fun `a manifest missing the format version still decodes`() {
    // It is always written now, but keeping it a defaulted field means an older or hand-edited
    // file is still readable rather than a hard parse failure.
    val text =
      """
      {
        "generatedFrom": "tip",
        "previews": {}
      }
      """
        .trimIndent()

    val decoded = assertNotNull(PreviewHistoryManifest.decode(text))

    assertEquals(PreviewHistoryManifest.FORMAT_VERSION, decoded.formatVersion)
  }

  @Test
  fun `the encoded manifest round-trips`() {
    val log =
      gitLog("publish from 1a2b3c4d" to listOf("renders/samples:wear/Foo_Large_Round.png" to a))
    val manifest =
      PreviewHistoryManifest.build(
        timelines(log),
        PreviewHistoryManifest.renderPathsToPreviewIds(baselines),
        generatedFrom = "tip",
      )

    val decoded = PreviewHistoryManifest.decode(PreviewHistoryManifest.encode(manifest))

    assertEquals(manifest, assertNotNull(decoded))
  }

  @Test
  fun `a manifest with unknown fields still decodes`() {
    // Forward-compat: a branch written by a newer CLI must not break an older viewer.
    val text =
      """
      {
        "formatVersion": "${PreviewHistoryManifest.FORMAT_VERSION}",
        "generatedFrom": "tip",
        "somethingNew": {"nested": true},
        "previews": {
          "m/A": {
            "path": "renders/m/A.png",
            "versions": [
              {"blob": "$a", "commit": "c1", "date": "d", "commits": 1, "extra": 9}
            ],
            "observations": 1,
            "unstable": false,
            "flapCount": 0
          }
        }
      }
      """
        .trimIndent()

    val decoded = assertNotNull(PreviewHistoryManifest.decode(text))

    assertEquals("renders/m/A.png", decoded.previews.getValue("m/A").path)
  }

  @Test
  fun `undecodable text yields null rather than throwing`() {
    assertNull(PreviewHistoryManifest.decode("{not json"))
  }
}
