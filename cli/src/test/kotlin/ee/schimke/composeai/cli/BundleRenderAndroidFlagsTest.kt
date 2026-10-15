package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `bundle render`'s Android flags, which a Bazel `compose_preview` render passes. */
class BundleRenderAndroidFlagsTest {
  @Test
  fun `clock packages split trim and drop blanks and duplicates`() {
    assertEquals(
      listOf("com.example.app.", "com.example.lib."),
      bundleRenderClockPackages(" com.example.app., ,com.example.lib.,com.example.app.,."),
    )
  }

  @Test
  fun `absent or blank clock packages pin nothing`() {
    assertEquals(emptyList(), bundleRenderClockPackages(null))
    assertEquals(emptyList(), bundleRenderClockPackages(""))
  }

  @Test
  fun `the bundle path stays the first positional with the android flags before it`() {
    val args =
      listOf(
        "--android-sdk",
        "36",
        "--android-jar",
        "sdk/android.jar",
        "--clock-packages",
        "com.example.",
        "out/ui.bundle.png",
        "--output",
        "out/renders",
      )
    assertEquals("out/ui.bundle.png", CliFlags.firstPositional(args))
  }

  @Test
  fun `the android flags are known bundle options`() {
    for (flag in listOf("--android-sdk", "--android-jar", "--clock-packages")) {
      assertTrue(flag in CliFlags.VALUE_FLAGS, flag)
    }
  }
}
