package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The name a render is stored under is `sha256(cacheKey)` in lowercase hex, and stays that.
 *
 * It is the on-disk contract: `present` is built by listing the generation's directory, so a change
 * to the naming does not fail — it silently orphans every PNG a previous build wrote, and the
 * catalog re-renders from scratch. Nothing pinned that before, which mattered when
 * yschimke/compose-ai-tools#5322 replaced `"%02x".format(byte)` with `HexFormat`: the two agree for
 * every byte value (`Formatter` renders a negative `Byte` under `%x` as the value plus 2^8, which
 * is the unsigned hex `formatHex` writes), and this is what says so.
 */
class ThemeCacheStoreFileNameTest {

  @Test
  fun `a render is stored under the sha256 of its cache key`(@TempDir root: File) {
    val generation = generation(root)

    generation.put(CACHE_KEY, PNG)

    val expected = sha256Hex(CACHE_KEY)
    assertTrue(
      File(root, "compose-m3/fp1/$expected.png").isFile,
      "expected a file named after the key's digest, found: " +
        File(root, "compose-m3/fp1").list()?.toList(),
    )
    // 32 digest bytes, lowercase, no separators. `expected` above is computed with the formatting
    // this file used to use, so the check that found it on disk is already the equivalence proof;
    // this pins the shape so neither side can drift into upper case or a delimiter unnoticed.
    assertEquals(64, expected.length, expected)
    assertTrue(expected.all { it in "0123456789abcdef" }, expected)
  }

  @Test
  fun `a name computed twice is the same name`(@TempDir root: File) {
    // `fileName` is memoised per generation now; membership has to keep answering about the same
    // file it did on the first call.
    val generation = generation(root)
    generation.put(CACHE_KEY, PNG)

    assertTrue(generation.contains(CACHE_KEY))
    assertTrue(generation.contains(CACHE_KEY))
    assertFalse(generation.contains("$CACHE_KEY-absent"))
    assertEquals(PNG.toList(), generation.get(CACHE_KEY)?.toList())
  }

  @Test
  fun `a key that never lands on disk is not remembered`(@TempDir root: File) {
    // The read path takes whatever key a request produces. `CatalogThemeCache.get` asks about
    // ad-hoc
    // override renders — widths, locales, knob values — that `CatalogThemeCache.put` deliberately
    // refuses to persist because a visitor can mint them without limit. Memoizing those would move
    // the unbounded growth the disk budget refuses into the heap instead, so the memo only keeps
    // names this generation actually holds.
    val generation = generation(root)
    generation.put(CACHE_KEY, PNG)
    // The one persisted key is remembered: that is the case #5322 is about.
    generation.contains(CACHE_KEY)
    assertEquals(1, memoSize(generation))

    repeat(500) { i ->
      generation.contains("$CACHE_KEY|width=$i")
      generation.get("$CACHE_KEY|width=$i")
      generation.wasAdopted("$CACHE_KEY|width=$i")
    }

    assertEquals(
      1,
      memoSize(generation),
      "500 unpersistable keys must not be retained for the generation's lifetime",
    )
  }

  @Test
  fun `a reopened generation finds what the previous one wrote`(@TempDir root: File) {
    // The memo is per generation, so the second one recomputes — and has to land on the same name,
    // which is the whole point of the digest being the contract.
    generation(root).put(CACHE_KEY, PNG)

    val reopened = generation(root)

    assertTrue(reopened.contains(CACHE_KEY))
    assertEquals(PNG.toList(), reopened.get(CACHE_KEY)?.toList())
  }

  private fun generation(root: File): ThemeCacheStore.Generation =
    checkNotNull(
      ThemeCacheStore(root)
        .open(
          "compose-m3",
          "fp1",
          GenerationInputs(
            system = "compose-m3",
            fingerprint = "fp1",
            toolVersion = "test",
            variant = "test",
            renderConfig = "test",
          ),
        )
    )

  private fun memoSize(generation: ThemeCacheStore.Generation): Int =
    (ThemeCacheStore.Generation::class
        .java
        .getDeclaredField("fileNames")
        .apply { isAccessible = true }
        .get(generation) as Map<*, *>)
      .size

  private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  private companion object {
    const val CACHE_KEY = "compose-m3|button-filled|dark|fontScale=1.5"
    val PNG = byteArrayOf(1, 2, 3, 4)
  }
}
