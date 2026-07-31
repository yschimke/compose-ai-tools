package ee.schimke.composeai.daemon

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Robolectric native runtime's system-font resources against classpath shadowing.
 *
 * Robolectric's `DefaultNativeRuntimeLoader` extracts the native runtime's system fonts from the
 * `fonts` resource **directory**, resolved through the classloader — so the *first* `fonts/` root on
 * the test classpath wins outright, and nothing merges the rest. A module that ships its own
 * `src/test/resources/fonts/` sorts ahead of `nativeruntime-dist-compat` and replaces
 * `fonts/fonts.xml` plus the ~200 system faces with whatever it happens to contain.
 *
 * When that happened (#3086, a single stand-in TTF at `/fonts/warm-cache-face.ttf`),
 * `Typeface.loadPreinstalledSystemFontMap()` built a font map with no `sans-serif` entry and
 * `setSystemFontMap` NPE'd on the null family — failing **every** sandbox bootstrap in this module,
 * 40 tests at once, with a stack that points into Robolectric and never mentions the real cause.
 *
 * This test costs milliseconds and needs no sandbox, so it fails first and names the problem
 * directly. Keep test font fixtures out of `/fonts/` — see `FigmaSvgDownloadableFontEmbedTest`,
 * which loads its fixture face from `/composeai-test-fonts/`.
 */
class NativeRuntimeFontResourcesTest {

  @Test
  fun nativeRuntimeSystemFontsAreNotShadowedByAModuleFontsResourceRoot() {
    val loader = javaClass.classLoader!!

    // The *directory*, not a file inside it. `getResource("fonts/fonts.xml")` keeps resolving out
    // of the native-runtime jar even while a module-local `fonts/` root shadows it, because a
    // per-file lookup scans every classpath entry until it hits. Robolectric resolves the
    // directory, and a directory lookup stops at the first root — so the directory is the thing
    // that has to be asserted, and the only thing that actually regresses.
    val fontsRoot = loader.getResource("fonts")
    assertNotNull(
      "Robolectric's native-runtime `fonts/` directory must be resolvable on the test classpath; " +
        "without it `Typeface.loadPreinstalledSystemFontMap()` has no system fonts to load.",
      fontsRoot,
    )
    assertTrue(
      "the `fonts/` resource root that wins on this module's test classpath must be Robolectric's " +
        "native-runtime distribution, got `$fontsRoot`. Something on the test runtime classpath — " +
        "most likely a `src/test/resources/fonts/` directory in this module — is shadowing " +
        "`nativeruntime-dist-compat`, and a directory lookup stops at the first root, so the ~200 " +
        "system faces and `fonts.xml` never get extracted. " +
        "`Typeface.loadPreinstalledSystemFontMap()` then builds a font map with no `sans-serif` " +
        "entry and `setSystemFontMap` NPEs on the null family, failing every sandbox bootstrap in " +
        "this module (#3086). Move the fixture out of `/fonts/` — see `/composeai-test-fonts/`.",
      fontsRoot.toString().contains("nativeruntime"),
    )
  }
}
