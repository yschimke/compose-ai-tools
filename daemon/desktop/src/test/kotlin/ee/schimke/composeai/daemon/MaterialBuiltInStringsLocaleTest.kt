@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The **positive control** for the locale story, and the canary that goes red the day upstream
 * closes the gap — issues #3718 / #3721, filed upstream as yschimke/m3-catalog#54.
 *
 * [OverrideIntegrationTest.localeTagOverrideReachesComposeResourceLocale] already covers one locale
 * consumer: CMP *resource* resolution, the previewed app's own `composeResources`. That is the one
 * axis with a per-composition lever — `LocalComposeEnvironment`, public since 1.11.1 — so on its
 * own it left an open question: if resources were plumbed through the composition, could the
 * process-global `Locale.setDefault` (and the gate serialising it) be narrowed or dropped?
 *
 * No. Material resolves **its own** strings on a second, entirely separate path:
 * ```
 * androidx.compose.material3.internal.Strings_skikoKt.getString-2EP1pXo(int, Composer, int)
 *   26: invokevirtual androidx/compose/ui/text/intl/Locale$Companion.getCurrent:()Locale;
 *   32: invokestatic  getTranslation:(I;Locale;)String;
 * ```
 *
 * `Locale.current` is the JVM default on desktop. `material3-desktop` ships 75 locale bundles under
 * `androidx/compose/material3/l10n/`, and every built-in Material string — date-picker labels,
 * slider range descriptions, navigation and dropdown content descriptions — resolves through that
 * call, from inside a composable that never reads its own `Composer`. `LocalComposeEnvironment`
 * does not touch it, and nothing else does either.
 *
 * So this file asserts three things, and the first is what makes the third falsifiable:
 * 1. Material's own strings **do** change with the JVM default locale — the positive control. An
 *    earlier attempt at this canary (PR #3733, closed unmerged) failed precisely because it had no
 *    such control: it asserted that an ambient locale changed nothing, with no demonstration that
 *    *any* locale changed anything observable, so it would have stayed green forever.
 * 2. A composition-scoped `LocalLocaleList` does **not** reach them. This is the assertion that
 *    turns red when upstream wires the local into text resolution.
 * 3. The daemon's own `localeTag` override does reach them — i.e. what the gate in #3724 protects
 *    is real user-visible copy, not just app resources.
 */
class MaterialBuiltInStringsLocaleTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun materialsOwnStringsFollowTheJvmDefaultLocale() {
    assertEquals(
      "Material's own strings must follow the JVM default locale — this is the control that " +
        "makes the composition-local assertion below meaningful. If this fails, either the " +
        "material3 l10n bundles moved or RangeSlider stopped describing its thumbs, and the " +
        "canary below is asserting nothing.",
      listOf("Range start", "Range end"),
      underJvmDefault("en-US") { materialStrings() },
    )
    assertEquals(
      "the same strings under a German JVM default",
      listOf("Bereichsstart", "Bereichsende"),
      underJvmDefault("de") { materialStrings() },
    )
  }

  /**
   * The canary. Providing the composition local *and nothing else* must leave Material's strings
   * English, because nothing reads that local.
   *
   * Note the local is not merely unprovided by default — `ProvideCommonCompositionLocals` already
   * provides it per scene from `Owner.localeList` → `PlatformContext.localeList`. The provider side
   * is fully built; the consumer side is empty. This overrides it explicitly so the assertion is
   * about consumption rather than about whether anything provided a value.
   */
  @Test
  fun materialsOwnStringsIgnoreTheCompositionLocaleList() {
    // Asserted, not skipped-on-null. A `return` here would make the whole test pass vacuously the
    // day the local moved or was renamed — which is the failure mode that killed PR #3733's first
    // two canary attempts. `RenderEngine.localeProviders` no-ops without this local, so if it has
    // gone missing the locale override is inert and that is a louder bug than this test's own.
    val local =
      requireNotNull(localProvidableLocaleListOrNull()) {
        "androidx.compose.ui.platform.CompositionLocalsKt.getLocalProvidableLocaleList is gone — " +
          "RenderEngine.localeProviders provides nothing without it, so the localeTag override's " +
          "composition-local half is silently dead. Fix that before this test."
      }

    // Read the local back from inside the composition it was provided to. Without this the test
    // below would pass just as happily if the provide had silently failed to apply — "the local is
    // ignored" and "the local was never set" produce identical English output. This is the
    // positive half of the pair the upstream report records: the provider applies, and nothing
    // consumes it.
    val seenByComposition = mutableListOf<String>()
    val observed =
      underJvmDefault("en-US") {
        materialStrings { content ->
          CompositionLocalProvider(local provides LocaleList("de")) {
            seenByComposition += local.current.map { it.language }
            content()
          }
        }
      }
    assertEquals(
      "the composition local must actually carry `de` inside the composition — otherwise the " +
        "assertion below is vacuous, proving only that an unset local changes nothing",
      listOf("de"),
      seenByComposition.distinct(),
    )

    assertEquals(
      "`LocalLocaleList provides LocaleList(\"de\")` must NOT localize Material's own strings " +
        "while the JVM default is en-US — no consumer of that local exists in ui-text, " +
        "foundation, material or material3 (audited across 6,373 classes of the pinned desktop " +
        "artifacts; see yschimke/m3-catalog#54).\n\n" +
        "If this test is failing with German strings, that is GOOD NEWS: upstream has wired the " +
        "composition local into string resolution. Re-open #3721 and re-measure what the " +
        "process-global `Locale.setDefault` in RenderEngine is still needed for — the read/write " +
        "gate may finally be narrowable. Check the `String.format` sites in material3 first " +
        "(they read `Locale.getDefault(Category.FORMAT)`, which no composition local can reach).",
      listOf("Range start", "Range end"),
      observed,
    )
  }

  /**
   * End-to-end through the daemon's real path: a `localeTag` spec must reach Material's own
   * strings, not just CMP resources. This is the coverage the gate's justification rested on and
   * that no test asserted.
   */
  @Test
  fun localeTagOverrideReachesMaterialsOwnStrings() {
    val outputDir: File = tempFolder.newFolder("renders-material-strings")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val original = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("en-US"))
    val engine = RenderEngine()
    val state =
      engine.setUp(
        RenderSpec(
          previewId = "material-strings-de",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "MaterialBuiltInStringsSlider",
          widthPx = 320,
          heightPx = 96,
          density = 1.0f,
          localeTag = "de",
        )
      )
    try {
      assertEquals(
        "a localeTag render must localize Material's own built-in strings, not only the " +
          "previewed app's `stringResource(...)` — they resolve from the JVM default the " +
          "override switches, which is exactly why concurrent held sessions had to be " +
          "serialised (#3718, #3724)",
        listOf("Bereichsstart", "Bereichsende"),
        materialStringsIn(state.scene),
      )
      assertEquals(
        "and the switch must not outlive the composition",
        "en",
        Locale.getDefault().language,
      )
    } finally {
      engine.tearDown(state)
      Locale.setDefault(original)
    }
  }

  // --- helpers

  /**
   * The two `contentDescription`s a Material 3 `RangeSlider` puts on its thumbs, in thumb order.
   * Content descriptions rather than drawn glyphs because the assertion is about *which string
   * Material chose*; the glyphs follow it (`DatePicker`'s headline renders "Datum auswählen"
   * visibly under the same mechanism, but pinning drawn text costs a font-metrics dependency this
   * does not need).
   */
  private fun materialStrings(
    wrap: @Composable (content: @Composable () -> Unit) -> Unit = { it() }
  ): List<String> {
    val scene =
      ImageComposeScene(width = 320, height = 96, density = Density(1f)) {
        wrap { MaterialBuiltInStringsSlider() }
      }
    return try {
      scene.render()
      materialStringsIn(scene)
    } finally {
      scene.close()
    }
  }

  private fun materialStringsIn(scene: ImageComposeScene): List<String> {
    val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
    val labels = mutableListOf<String>()
    collectLabels(ComposeSemanticsDataProducer.buildPayload(root, 1f).root, labels)
    assertTrue("expected the RangeSlider's two thumb descriptions; got $labels", labels.size == 2)
    return labels
  }

  private fun collectLabels(node: ComposeSemanticsNode, out: MutableList<String>) {
    node.label?.let { out += it }
    node.children.forEach { collectLabels(it, out) }
  }

  private fun <T> underJvmDefault(tag: String, body: () -> T): T {
    val original = Locale.getDefault()
    return try {
      Locale.setDefault(Locale.forLanguageTag(tag))
      body()
    } finally {
      Locale.setDefault(original)
    }
  }

  /**
   * The same reflective probe `RenderEngine.localeProviders` uses — the local is not source-visible
   * from here, and a catalog served against an older CMP may not have it at all.
   */
  @Suppress("UNCHECKED_CAST")
  private fun localProvidableLocaleListOrNull(): ProvidableCompositionLocal<LocaleList>? =
    runCatching {
        Class.forName("androidx.compose.ui.platform.CompositionLocalsKt")
          .getMethod("getLocalProvidableLocaleList")
          .invoke(null) as ProvidableCompositionLocal<LocaleList>
      }
      .getOrNull()
}
