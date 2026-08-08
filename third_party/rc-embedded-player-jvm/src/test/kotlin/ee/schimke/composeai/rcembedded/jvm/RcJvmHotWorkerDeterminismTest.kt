/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * The gate on reusing a **long-lived** jvm render worker instead of spawning one JVM per document.
 *
 * `RcJvmServerRenderer` currently forks a fresh JVM for every `.rc` it draws, so every render pays
 * Compose Desktop + Skiko boot. A pooled worker amortises that across documents — but only if a
 * worker that has already drawn many documents still produces **byte-identical** output to a worker
 * that has drawn none. That is not free: `ImageComposeScene`, Skia, the AWT/font stack and the
 * `GoogleFontTypefaceResolver` cache all hold process-global state, and `rc-compare` gates the PR
 * on pixel parity — so hot-worker drift would turn a correctness gate into a flake source.
 *
 * This test is that check, and it is the reason the pool is allowed to exist. It renders a corpus
 * cold (first touch in a fresh JVM — exactly what the one-shot subprocess produces today), churns
 * the process with many renders at varied sizes, densities, seeds and formats, then re-renders the
 * corpus at the original spec and asserts the bytes are identical.
 *
 * Deliberately compares **encoded PNG bytes**, not a pixel tolerance: the pool's promise is that
 * pooling is unobservable, and anything short of byte identity means a cached render could differ
 * from a freshly-computed one depending only on worker age — which is precisely the
 * non-reproducibility this repo's diffing exists to catch.
 *
 * Like the other skiko tests here it needs the natives and skips loudly where they are absent.
 */
class RcJvmHotWorkerDeterminismTest {

  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmHotWorkerDeterminismTest skipped entirely: skiko's native library did not load, so " +
          "the hot-worker determinism gate never ran. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @Test
  fun aHotWorkerRendersByteIdenticallyToAColdOne() {
    val corpus = corpus()
    check(corpus.isNotEmpty()) { "no .rc fixtures found — the determinism gate would be vacuous" }

    // Cold: the first touch of each document in this JVM. This is what the one-shot subprocess
    // produces today, so it is the reference the pool must reproduce.
    val coldNanos = ArrayList<Long>()
    val cold = corpus.map { doc ->
      val start = System.nanoTime()
      val png = renderRemoteDocumentToPng(doc.bytes, WIDTH, HEIGHT, DENSITY)
      coldNanos += System.nanoTime() - start
      doc.name to png
    }

    // Churn: age the process the way a pooled worker would be aged — many documents, varied specs,
    // seeds applied, and the SVG lane interleaved (it allocates temp dirs and a second scene type).
    var churned = 0
    repeat(CHURN_ROUNDS) { round ->
      corpus.forEach { doc ->
        val width = WIDTH + ((round * 37) % 240)
        val height = HEIGHT + ((round * 53) % 180)
        val density = DENSITIES[(round + churned) % DENSITIES.size]
        val seeds =
          if (round % 3 == 0) emptyMap()
          else mapOf("prototype.churn" to RcSeed.FloatValue(round.toFloat()))
        renderRemoteDocumentToPng(doc.bytes, width, height, density, seeds)
        churned++
        if (round % 4 == 0) {
          runCatching { renderRemoteDocumentToSvg(doc.bytes, width, height, density) }
        }
      }
    }

    // Hot: the same documents at the same spec, on a worker that has now drawn `churned` others.
    val hotNanos = ArrayList<Long>()
    val hot = corpus.map { doc ->
      val start = System.nanoTime()
      val png = renderRemoteDocumentToPng(doc.bytes, WIDTH, HEIGHT, DENSITY)
      hotNanos += System.nanoTime() - start
      doc.name to png
    }

    System.err.println(
      "hot-worker determinism: ${corpus.size} document(s), $churned churn render(s); " +
        "cold ${coldNanos.sum() / 1_000_000}ms total (first ${coldNanos.first() / 1_000_000}ms), " +
        "hot ${hotNanos.sum() / 1_000_000}ms total"
    )

    cold.zip(hot).forEach { (coldEntry, hotEntry) ->
      val (name, coldPng) = coldEntry
      val (_, hotPng) = hotEntry
      if (!coldPng.contentEquals(hotPng)) {
        System.err.println(
          "DRIFT on $name: cold ${coldPng.size} bytes vs hot ${hotPng.size} bytes; " +
            "first difference at byte ${firstDifference(coldPng, hotPng)}"
        )
      }
      assertArrayEquals(
        "a worker that has drawn $churned document(s) must render '$name' byte-identically to a " +
          "cold one — otherwise pooling is observable and rc-compare's pixel gate becomes flaky",
        coldPng,
        hotPng,
      )
    }
  }

  private data class Doc(val name: String, val bytes: ByteArray)

  /**
   * Every `.rc` fixture in the repository, not just this module's own: the pool's premise is that a
   * worker takes a document from anywhere, so the gate should see documents captured by different
   * lanes (the shared title-card fixture plus the design-artifacts export fixtures).
   */
  private fun corpus(): List<Doc> {
    val bundled = javaClass.getResourceAsStream("/$FIXTURE")?.use { Doc(FIXTURE, it.readBytes()) }
    val fromRepo =
      repoRoot()
        ?.resolve("scripts/design-artifacts/fixtures")
        ?.listFiles { f: File -> f.extension == "rc" }
        ?.sortedBy { it.name }
        ?.map { Doc(it.name, it.readBytes()) }
        .orEmpty()
    return listOfNotNull(bundled) + fromRepo
  }

  /** Walk up from the module dir until the repo root (the one with `settings.gradle.kts`). */
  private fun repoRoot(): File? {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    return null
  }

  private fun firstDifference(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) if (a[i] != b[i]) return i
    return n
  }

  private companion object {
    const val FIXTURE = "rc-fixtures/TitleCardRemote-640x480.rc"
    const val WIDTH = 640
    const val HEIGHT = 480
    const val DENSITY = 2f
    const val CHURN_ROUNDS = 24
    val DENSITIES = floatArrayOf(1f, 1.5f, 2f, 3f).toTypedArray()

    var skikoLoadFailure: String? = null

    /** Whether Skia is callable at all — decided once by touching a class that loads the native. */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
