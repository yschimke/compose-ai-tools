package ee.schimke.composeai.cli

import java.io.File
import java.util.zip.ZipFile

/**
 * Whether a Bazel target's own jars can declare a preview, read before its runtime is built
 * (#6162). A class using `@Preview`, or a multi-preview annotation such as `@PreviewLightDark` or
 * `@WearPreviewDevices`, names the annotation's type in its constant pool, so the bytes `Preview`
 * appear in it. The test is deliberately loose: a class merely named `…Preview…` keeps a target in,
 * which costs only the build this skips. What it misses is a target whose previews use only a
 * multi-preview annotation declared elsewhere and named without `Preview`.
 */
internal object BazelPreviewJars {
  private val MARKER = "Preview".toByteArray(Charsets.US_ASCII)

  /** True when any `.class` in [jars] mentions `Preview`, or a jar cannot be read. */
  fun mayHoldPreviews(jars: List<File>): Boolean = jars.any { jar ->
    runCatching {
        ZipFile(jar).use { zip ->
          zip.entries().asSequence().any { entry ->
            entry.name.endsWith(".class") &&
              zip.getInputStream(entry).use { it.readBytes() }.contains(MARKER)
          }
        }
      }
      .getOrDefault(true)
  }

  private fun ByteArray.contains(marker: ByteArray): Boolean {
    if (marker.isEmpty() || size < marker.size) return false
    outer@ for (i in 0..size - marker.size) {
      for (j in marker.indices) if (this[i + j] != marker[j]) continue@outer
      return true
    }
    return false
  }
}
