package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the file form of the `--preview-id` filter (issue #5172).
 *
 * System properties reach this render JVM as process arguments, which the Gradle daemon encodes
 * with its own `sun.jnu.encoding`. Under a C/POSIX locale (`ANSI_X3.4-1968` — containers, CI
 * runners, cloud agent sandboxes) every non-ASCII character in a preview id is replaced by `?` in
 * transit, so `…_Cadence — Sync ready` could never equal the id discovered here and a narrowed
 * render failed for exactly the previews it was meant to speed up. The plugin now passes an ASCII
 * path to a UTF-8 list instead, which is what [PreviewManifestLoader.idFilterPatterns] reads.
 */
class PreviewIdFilterFileTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `reads the file as UTF-8, one pattern per line`() {
    val file = tmp.newFile("id-filter.txt")
    file.writeText("=SyncPreview_Cadence — Sync ready\n\n  =HomePreview  \n", Charsets.UTF_8)

    val patterns = PreviewManifestLoader.idFilterPatterns { key ->
      if (key == PreviewManifestLoader.ID_FILTER_FILE_PROPERTY) file.path else null
    }

    assertEquals(listOf("=SyncPreview_Cadence — Sync ready", "=HomePreview"), patterns)
  }

  @Test
  fun `an unset file property leaves the joined property in charge`() {
    assertEquals(emptyList<String>(), PreviewManifestLoader.idFilterPatterns { null })
  }

  @Test
  fun `a missing file falls back rather than silently rendering everything as a match`() {
    val patterns = PreviewManifestLoader.idFilterPatterns { key ->
      if (key == PreviewManifestLoader.ID_FILTER_FILE_PROPERTY) "/no/such/id-filter.txt" else null
    }

    // The fallback is the joined property, which is unset here — an unfiltered render, exactly as
    // if no filter had been passed, with the reason on stderr.
    assertEquals(emptyList<String>(), patterns)
  }
}
