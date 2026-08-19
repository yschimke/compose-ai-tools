package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sticker → usage-code rewrite.
 *
 * The fixture is **not** shaped like a section file; it is a verbatim extract of one, taken from
 * m3-catalog's `catalog/src/main/kotlin/ee/schimke/m3catalog/sections/Buttons.kt`. That matters:
 * the whole question this prototype answers is whether real catalog source — a matrix-driven
 * sticker with a click tally, three knobs, a private frame and a translated label — comes out as
 * something a visitor can press Run on. A hand-tidied fixture would answer a question nobody asked.
 *
 * The rules below are the subset of m3-catalog's own `compose-usage.json` these fixtures exercise.
 * That file declares seventeen scaffolding helpers in total, for a catalog of ~400 components.
 */
class PlaygroundSourceCleanerTest {

  private val rules =
    UsageRules(
      scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview", "ee.schimke.m3catalog"),
      scaffolds =
        mapOf(
          "Sticker" to
            UsageRules.Scaffold(
              kind = UsageRules.Kind.RENAME,
              renameTo = "MaterialTheme",
              addImport = "androidx.compose.material3.MaterialTheme",
            ),
          "counted" to
            UsageRules.Scaffold(
              kind = UsageRules.Kind.INLINE,
              members = mapOf("label" to "\$0", "onClick" to "{}"),
            ),
          "catalogEnabled" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "catalogButtonShape" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "catalogButtonSize" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "ButtonFrame" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP),
        ),
      stringsPath = "src/main/composeResources/values/strings.xml",
    )

  private val strings = mapOf("label_filled" to "Filled", "label_tonal" to "Tonal")

  /** Verbatim from `Buttons.kt`, trimmed to the declarations the anchors below land in. */
  private val buttonsKt =
    """
    @file:CatalogGroup(name = "Buttons", section = "Actions")
    @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

    package ee.schimke.m3catalog.sections

    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.width
    import androidx.compose.material3.Button
    import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
    import androidx.compose.material3.Icon
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import ee.schimke.composeai.preview.CatalogComponent
    import ee.schimke.composeai.preview.CatalogGroup
    import ee.schimke.composeai.preview.CatalogVariant
    import ee.schimke.m3catalog.CatalogModes
    import ee.schimke.m3catalog.CatalogSize
    import ee.schimke.m3catalog.SizeShapeMatrix
    import ee.schimke.m3catalog.Sticker
    import ee.schimke.m3catalog.catalogButtonShape
    import ee.schimke.m3catalog.catalogButtonSize
    import ee.schimke.m3catalog.catalogEnabled
    import ee.schimke.m3catalog.counted
    import ee.schimke.m3catalog.generated.resources.Res
    import ee.schimke.m3catalog.generated.resources.label_filled
    import org.jetbrains.compose.resources.stringResource

    @Composable
    private fun ButtonFrame(size: CatalogSize, content: @Composable () -> Unit) {
      Box(
        modifier = Modifier.height(if (size == CatalogSize.Small) 48.dp else size.containerHeight),
        contentAlignment = Alignment.Center,
      ) {
        content()
      }
    }

    @CatalogComponent(
      id = "Button/Filled",
      reference = "figma:ocdacdEsnHipMJD3egzxKb/57994:2324",
      caption = "Highest emphasis; the primary action. Five sizes x two shapes fold in as variants.",
    )
    @CatalogModes
    @SizeShapeMatrix
    @Composable
    fun FilledButton() = Sticker {
      val c = counted(stringResource(Res.string.label_filled))
      val size = catalogButtonSize()
      ButtonFrame(size) {
        Button(
          onClick = c.onClick,
          enabled = catalogEnabled(),
          shape = catalogButtonShape(),
          contentPadding = size.contentPadding,
          modifier = Modifier.height(size.containerHeight),
        ) {
          Text(c.label)
        }
      }
    }

    @CatalogVariant(
      of = "Button/Filled",
      props = ["content=label"],
      caption = "Label only, vs the kit's icon + label default.",
    )
    @CatalogModes
    @Composable
    fun FilledButtonLabelOnly() = Sticker {
      val c = counted(stringResource(Res.string.label_filled))
      Button(onClick = c.onClick) { Text(c.label) }
    }
    """
      .trimIndent()

  private fun lineOf(needle: String): Int =
    buttonsKt
      .lines()
      .indexOfFirst { it.contains(needle) }
      .let {
        require(it >= 0) { "fixture has no line containing $needle" }
        it + 1
      }

  private fun cleanAt(needle: String) =
    PlaygroundSourceCleaner.clean(buttonsKt, lineOf(needle), rules, strings)

  /**
   * The headline case: the simple variant comes out as the four lines somebody would actually
   * write, with nothing left of the catalog but the component call.
   */
  @Test
  fun `a variant sticker becomes plain compose`() {
    val result = assertNotNull(cleanAt("Button(onClick = c.onClick) { Text(c.label) }"))
    assertEquals(
      """
      @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

      import androidx.compose.material3.Button
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      @Composable
      fun FilledButtonLabelOnly() = MaterialTheme {
        Button(onClick = {}) { Text("Filled") }
      }
      """
        .trimIndent(),
      result.text,
    )
    assertEquals(emptyList(), result.residue)
    assertEquals("FilledButtonLabelOnly", result.entryFunction)
  }

  /**
   * The matrix-driven case — the one the whole design has to survive. Three knobs, a private frame,
   * a click tally and a resource lookup all resolve away, leaving the default render's call.
   */
  @Test
  fun `a matrix sticker loses its knobs, its frame and its tally`() {
    val result = assertNotNull(cleanAt("""caption = "Highest emphasis"""))
    assertEquals(
      """
      @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

      import androidx.compose.material3.Button
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      @Composable
      fun FilledButton() = MaterialTheme {
        Button(onClick = {}) {
          Text("Filled")
        }
      }
      """
        .trimIndent(),
      result.text,
    )
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper keeps its lambda and follows preview uiMode`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker {
        Text("Card")
      }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Text(\"Card\")") } + 1,
          rules,
        )
      )

    assertTrue(
      result.text.contains(
        "androidx.compose.material3.MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {"
      ),
      result.text,
    )
    assertTrue(result.text.contains("Text(\"Card\")"), result.text)
    assertFalse(result.text.contains("import androidx.compose.foundation.isSystemInDarkTheme"))
    assertFalse(result.text.contains("import androidx.compose.material3.MaterialTheme"))
    assertFalse(result.text.contains("import com.example.catalog.Sticker"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper accepts an empty argument list`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker() { Unit }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertFalse(result.text.contains("Sticker"), result.text)
    assertTrue(result.text.contains("androidx.compose.material3.MaterialTheme("), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper treats comment-only arguments as empty`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    for (arguments in
      listOf(
        "/* default theme */",
        "/* outer /* detail */ outer */",
        "\n        // default theme\n        ",
      )) {
      val source =
        """
        package com.example.catalog

        import androidx.compose.runtime.Composable
        import com.example.catalog.Sticker

        @Composable
        fun CardPreview() = Sticker($arguments) { Unit }
        """
          .trimIndent()

      val result =
        assertNotNull(
          PlaygroundSourceCleaner.clean(
            source,
            source.lines().indexOfFirst { it.contains("fun CardPreview") } + 1,
            rules,
          ),
          "comment-only arguments were not cleaned: ${arguments.replace("\n", "\\n")}",
        )

      assertFalse(result.text.contains("Sticker"), result.text)
      assertTrue(result.text.contains("androidx.compose.material3.MaterialTheme("), result.text)
      assertEquals(emptyList(), result.residue)
    }
  }

  @Test
  fun `a system Material 3 expansion cannot collide with retained simple imports`() {
    val source =
      """
      package com.example.catalog

      import com.example.brand.MaterialTheme
      import com.example.catalog.Sticker
      import androidx.compose.runtime.Composable

      @Composable
      fun CardPreview() = Sticker { MaterialTheme { Unit } }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
                addImports =
                  listOf(
                    "androidx.compose.material3.MaterialTheme",
                    "androidx.compose.material3.darkColorScheme",
                    "androidx.compose.material3.lightColorScheme",
                    "androidx.compose.foundation.isSystemInDarkTheme",
                  ),
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("MaterialTheme { Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("import com.example.brand.MaterialTheme"), result.text)
    assertFalse(
      result.text.contains("import androidx.compose.material3.MaterialTheme"),
      result.text,
    )
    assertTrue(result.text.contains("MaterialTheme { Unit }"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme rule does not discard wrapper arguments`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker("brand") {
        Unit
      }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("Sticker(\"brand\")"), result.text)
    assertEquals(listOf("Sticker"), result.residue)
    assertFalse(result.text.contains("darkColorScheme"), result.text)
  }

  /** Not one annotation from either annotation package may reach the editor. */
  @Test
  fun `catalog annotations and their imports are gone`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    for (noise in
      listOf(
        "@CatalogComponent",
        "@CatalogModes",
        "@SizeShapeMatrix",
        "@CatalogVariant",
        "@file:CatalogGroup",
        "ee.schimke.m3catalog",
        "ee.schimke.composeai.preview",
        "figma:",
      )) {
      assertFalse(text.contains(noise), "cleaned source still carries $noise:\n$text")
    }
  }

  /**
   * The playground compiles a snippet and then looks for a `@Preview` in it. Stripping the
   * catalog's own `@CatalogModes` (which is where the preview came from) without putting a real one
   * back would produce a snippet that compiles and renders nothing.
   */
  @Test
  fun `a real Preview replaces the catalog's meta-annotation`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    assertTrue(text.contains("@Preview"))
    assertTrue(text.contains("import androidx.compose.ui.tooling.preview.Preview"))
    assertTrue(
      PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS.contains(rules.previewAnnotation),
      "the stamped annotation must be one the playground's discoverer recognises",
    )
  }

  /**
   * The package line is dropped: the snippet is plain Compose derived from the catalog, not the
   * catalog's own code, and compiling it into that package would let it reach `internal` members a
   * real consumer could not.
   */
  @Test
  fun `the catalog package is not carried over`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    assertFalse(text.contains("package ee.schimke.m3catalog"))
  }

  /**
   * A helper the entry point still calls after cleaning is pulled in with it. This is what turns
   * the old seed note's "expect unresolved references to delete" into a buffer that builds.
   */
  @Test
  fun `same-file helpers the cleaned body still needs are carried along`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      private fun Caption(text: String) {
        Text(text)
      }

      @Composable
      fun Card() = Sticker {
        Caption("hello")
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "Caption(\"hello\")"), rules)
      )
    assertTrue(result.text.contains("private fun Caption"), result.text)
    assertTrue(result.text.indexOf("fun Card") < result.text.indexOf("private fun Caption"))
  }

  /**
   * The fail-safe. `Spacer(Modifier.width(size.iconSpacing))` has no argument name to reason about,
   * so dropping the `size` knob here would leave `Spacer()`, which does not compile. The pass must
   * abandon the rewrite and say so rather than emit clean-looking broken code.
   */
  @Test
  fun `a drop that cannot complete is abandoned, not half-applied`() {
    val source =
      """
      package demo

      import androidx.compose.foundation.layout.Spacer
      import androidx.compose.foundation.layout.width
      import androidx.compose.ui.Modifier
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.catalogButtonSize

      @Composable
      fun Row() {
        val size = catalogButtonSize()
        Spacer(Modifier.width(size.iconSpacing))
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Spacer("), rules))
    assertTrue(result.text.contains("val size = catalogButtonSize()"), result.text)
    assertFalse(
      result.text.contains("Spacer()"),
      "emitted an uncompilable Spacer():\n${result.text}",
    )
    assertEquals(listOf("catalogButtonSize"), result.residue)
  }

  /** No anchor, or an anchor the file has moved out from under, means "seed it verbatim". */
  @Test
  fun `an unusable anchor declines rather than guesses`() {
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, null, rules))
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, 9_999, rules))
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, lineOf("package ee.schimke"), rules))
  }

  /** Nothing may be rewritten inside a string or a comment. */
  @Test
  fun `literals and comments are never rewritten`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      // Sticker is the frame every preview gets.
      @Composable
      fun Note() = Sticker {
        Text("wrapped in a Sticker, counted by counted()")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"wrapped"), rules))
        .text
    assertTrue(text.contains("\"wrapped in a Sticker, counted by counted()\""), text)
    assertTrue(text.contains("// Sticker is the frame every preview gets."), text)
    assertTrue(text.contains("= MaterialTheme {"), text)
  }

  /**
   * Generic rules alone — a catalog that has declared nothing still loses this repo's annotations.
   */
  @Test
  fun `generic rules still strip the preview annotations`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).let { it }
    val generic = assertNotNull(cleanAt("""caption = "Highest emphasis"""))
    assertFalse(generic.text.contains("@CatalogComponent"))
    // GENERIC knows only about THIS repo's own API — the preview-override knobs — and nothing about
    // any catalog's scaffolding.
    assertTrue(UsageRules.GENERIC.scaffolds.keys.all { it.startsWith("previewOverride") })
    val withGeneric =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          buttonsKt,
          lineOf("""caption = "Highest emphasis"""),
          UsageRules.GENERIC,
        )
      )
    assertFalse(withGeneric.text.contains("@CatalogComponent"), withGeneric.text)
    assertTrue(withGeneric.text.contains("Sticker {"), "generic rules must not invent a rename")
    assertTrue(text.text.isNotEmpty())
  }

  @Test
  fun `malformed rules degrade to generic rather than failing the handoff`() {
    assertNull(UsageRules.parse("{ not json"))
    assertNotNull(UsageRules.parse("""{"scaffoldAnnotationPackages":["a.b"]}"""))
  }

  /**
   * One rule this build cannot read must not cost a catalog every *other* rule in its file.
   *
   * A rules file is read at whatever ref the catalog was published from, so its vocabulary can be
   * older than the server's. `ignoreUnknownKeys` covers an unknown key; an unknown enum *value*
   * throws, and [UsageRules.parse] turns any throw into "no rules at all". Retiring the
   * `DESTRUCTURE` kind (#3884) therefore meant a catalog pinned to a ref that still declared one
   * silently lost `Sticker` and `catalogButtonSize` too and fell back to GENERIC — the scaffolding
   * leak this whole file exists to prevent, arriving through the compatibility door.
   */
  @Test
  fun `a rule kind this build does not know costs only that rule`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """
          {
            "scaffolds": {
              "Sticker": { "kind": "RENAME", "renameTo": "MaterialTheme" },
              "catalogButtonSize": { "kind": "DROP" },
              "toggleable": { "kind": "DESTRUCTURE", "plain": "x", "setter": "y" }
            }
          }
          """
            .trimIndent()
        )
      )
    // The rules either side of the retired one survived, which is the whole point.
    assertEquals(UsageRules.Kind.RENAME, rules.scaffolds["Sticker"]?.kind)
    assertEquals("MaterialTheme", rules.scaffolds["Sticker"]?.renameTo)
    assertEquals(UsageRules.Kind.DROP, rules.scaffolds["catalogButtonSize"]?.kind)
    assertEquals(UsageRules.Kind.UNKNOWN, rules.scaffolds["toggleable"]?.kind)
  }

  @Test
  fun `the reusable system Material 3 theme special parses without catalog-authored imports`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """{"scaffolds":{"Sticker":{"kind":"RENAME","renameTo":"MaterialTheme","special":"MATERIAL3_SYSTEM_THEME"}}}"""
        )
      )

    val sticker = assertNotNull(rules.scaffolds["Sticker"])
    assertEquals(UsageRules.Kind.RENAME, sticker.kind)
    assertEquals(UsageRules.MATERIAL3_SYSTEM_THEME, sticker.special)
    assertEquals(
      listOf(
        "androidx.compose.foundation.isSystemInDarkTheme",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.material3.darkColorScheme",
        "androidx.compose.material3.lightColorScheme",
      ),
      sticker.imports,
    )
  }

  @Test
  fun `an unknown rename special is residue instead of an incomplete rename`() {
    val source =
      """
      import androidx.compose.runtime.Composable
      import com.example.Sticker

      @Composable
      fun CardPreview() = Sticker { Unit }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = "FUTURE_THEME_POLICY",
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("Sticker { Unit }"), result.text)
    assertFalse(result.text.contains("MaterialTheme"), result.text)
    assertEquals(listOf("Sticker"), result.residue)
  }

  /**
   * Forgiving about the *kind* vocabulary is not the same as forgiving about the rest of the
   * document. A rules file this code cannot honour must still take the safe GENERIC fallback: an
   * `INLINE` rule with a null member map would otherwise decode to an empty one, and `applyInline`
   * would delete the binding while leaving its member references pointing at nothing.
   */
  @Test
  fun `tolerating an unknown kind does not make the rest of the document forgiving`() {
    assertNull(UsageRules.parse("""{"scaffolds":{"counted":{"kind":"INLINE","members":null}}}"""))
    assertNull(UsageRules.parse("""{"scaffoldPackages":null}"""))
  }

  /**
   * Unqualifying is setup for the kind passes, so a helper no pass will rewrite must not be put
   * through it: stripping the qualifier off a call nothing then rewrites — and nothing imports —
   * turns code that resolved into code that does not.
   */
  @Test
  fun `an unknown kind keeps the package qualifier that makes it resolve`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """
          {
            "scaffoldPackages": ["ee.schimke.m3catalog"],
            "scaffolds": { "toggleable": { "kind": "DESTRUCTURE" } }
          }
          """
            .trimIndent()
        )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable

      @Composable
      fun SwitchSticker() {
        var checked by ee.schimke.m3catalog.toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(result.text.contains("ee.schimke.m3catalog.toggleable(true)"), result.text)
    assertTrue(result.residue.contains("toggleable"), "${result.residue}")
  }

  /** An unknown kind fires no pass, so the helper survives the clean and is reported as residue. */
  @Test
  fun `a helper whose kind is unknown is left alone and reported`() {
    val rules =
      assertNotNull(UsageRules.parse("""{"scaffolds":{"toggleable":{"kind":"DESTRUCTURE"}}}"""))
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.toggleable

      @Composable
      fun SwitchSticker() {
        var checked by toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(result.text.contains("toggleable(true)"), result.text)
    assertTrue(result.residue.contains("toggleable"), "${result.residue}")
  }

  /**
   * The preview-override knobs are this repo's API, not a catalog's, so a catalog that declares its
   * own scaffolding must still get them — before this, declaring `compose-usage.json` *replaced*
   * the generic rules, so the catalogs that had done the work were the only ones leaking
   * `previewOverrideString(...)` into code a developer was invited to copy.
   */
  @Test
  fun `declared rules inherit the generic ones`() {
    val rules = assertNotNull(UsageRules.parse("""{"scaffolds":{"Sticker":{"kind":"UNWRAP"}}}"""))
    assertTrue(rules.scaffolds.containsKey("Sticker"))
    assertTrue(rules.scaffolds.containsKey("previewOverrideString"))
    assertTrue(rules.scaffoldAnnotationPackages.contains("ee.schimke.composeai.preview"))
  }

  /**
   * A knob nothing declared is exactly what residue cannot see: `previewOverrideString` is not in a
   * scaffold package, so the snippet was reported clean and did not compile. Found by the corpus
   * (`scripts/usage-corpus.sh`); see `docs/design/USAGE_SNIPPET_CORPUS.md`.
   */
  @Test
  fun `a preview override knob becomes the default the render was baked with`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Badge
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun NumberBadge() = Badge { Text(previewOverrideString("label", "3")) }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun NumberBadge"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("3")"""), cleaned.text)
    assertFalse(cleaned.text.contains("previewOverrideString"), cleaned.text)
  }

  /**
   * The same knob written with named arguments. A positional reading emits `Text(default =
   * "Shopping")`, which is Kotlin that looks right and does not compile — the worst outcome
   * available, since it reaches the visitor as "plain Compose you can run".
   */
  @Test
  fun `a named-argument knob substitutes as its value, not as the argument label`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Title() = Text(previewOverrideString(key = "title", default = "Shopping"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
  }

  /**
   * A package-qualified call is the same call. It was invisible in both directions before: no rule
   * fired (an occurrence after `.` is rejected, correctly) and no residue was reported (the call
   * needs no import), so the seed came out marked cleaned with a repo-internal call still in it.
   */
  @Test
  fun `a fully qualified knob call is rewritten like a bare one`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Title() =
        Text(ee.schimke.composeai.overrides.previewOverrideString("title", "Shopping"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
    assertFalse(cleaned.text.contains("previewOverrideString"), cleaned.text)
  }

  /**
   * A receiver chain is not a package, however much it looks like one. `state.metrics.counted { }`
   * is two lowercase segments followed by a declared scaffold name — matching on that shape would
   * strip the receiver and let the scaffold passes rewrite somebody's ordinary call. Only a package
   * the rules actually name is stripped.
   */
  @Test
  fun `a member call that shares a scaffold name is left alone`() {
    val rules =
      UsageRules(
        scaffoldPackages = listOf("ee.schimke.m3catalog"),
        scaffolds = mapOf("counted" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP)),
      )
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.runtime.Composable

      @Composable
      fun Tally() {
        stats.counted { }
        state.metrics.counted { }
      }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "stats.counted"), rules))
    assertTrue(cleaned.text.contains("stats.counted"), cleaned.text)
    assertTrue(cleaned.text.contains("state.metrics.counted"), cleaned.text)
  }

  /**
   * A qualified call the rules cannot unqualify must still be *reported*. The allow-list only
   * rewrites packages the rules name, so an undeclared one is left in place — and `mentionsWord`
   * rejects a name preceded by `.`, so nothing else would have said so. That combination is how a
   * seed gets marked cleaned with a catalog-internal call still in it.
   */
  @Test
  fun `an unlisted qualified scaffold call is reported as residue`() {
    val rules =
      UsageRules(scaffolds = mapOf("counted" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP)))
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.runtime.Composable

      @Composable
      fun Tally() = com.acme.counted { }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Tally"), rules))
    assertTrue(cleaned.text.contains("com.acme.counted"), cleaned.text)
    assertTrue(cleaned.residue.contains("counted"), "${cleaned.residue}")
  }

  /**
   * A matching callee *name* is not a matching call.
   *
   * The parsed substitution pass replaces the whole qualified expression, so selecting on the name
   * alone would delete somebody's receiver along with their call — `state.previewOverrideString(…)`
   * is an application's own member function that happens to share a name with a scaffold. Only a
   * bare call, or one qualified by a package the rules name, is the scaffold.
   */
  @Test
  fun `a member call sharing a substitute rule's name keeps its receiver`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Title() {
        Text(state.previewOverrideString("title", "Shopping"))
        Text(ee.schimke.composeai.overrides.previewOverrideString("subtitle", "Basket"))
      }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(
      cleaned.text.contains("state.previewOverrideString(\"title\", \"Shopping\")"),
      "an unrelated member call was rewritten: ${cleaned.text}",
    )
    // The package-qualified one is the scaffold, and is substituted.
    assertTrue(cleaned.text.contains("Text(\"Basket\")"), cleaned.text)
  }

  /**
   * `addImports` belongs to every kind that emits a replacement.
   *
   * m3-catalog's state helpers return a `MutableState`, so their rules are ordinary SUBSTITUTE —
   * and the substitution emitted `var checked by remember { mutableStateOf(true) }` against a
   * snippet with no `remember` import, because only one rewrite read the plural field.
   * Perfect-looking Kotlin that does not compile, which the corpus gate caught and no unit test
   * would have.
   */
  @Test
  fun `a substituted call contributes every import its rule declares`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "toggleable" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.SUBSTITUTE,
                params = listOf("initial"),
                plain = "remember { mutableStateOf(\$0) }",
                addImports =
                  listOf(
                    "androidx.compose.runtime.getValue",
                    "androidx.compose.runtime.mutableStateOf",
                    "androidx.compose.runtime.remember",
                    "androidx.compose.runtime.setValue",
                  ),
              )
          )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.getValue
      import androidx.compose.runtime.setValue
      import ee.schimke.m3catalog.toggleable

      @Composable
      fun SwitchSticker() {
        var checked by toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(
      result.text.contains("var checked by remember { mutableStateOf(true) }"),
      result.text,
    )
    for (import in listOf("getValue", "setValue", "remember", "mutableStateOf")) {
      assertTrue(result.text.contains("import androidx.compose.runtime.$import"), result.text)
    }
    assertFalse(result.text.contains("toggleable"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /**
   * The other kind that emits a replacement, and the one nobody had exercised: INLINE's member
   * templates are arbitrary Kotlin, so a rule can perfectly well inline a symbol that needs an
   * import. `applyInline` was not even handed the import set — the same omission as the plural
   * field, one layer up.
   */
  @Test
  fun `an inlined member contributes the imports its rule declares`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "counted" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.INLINE,
                members = mapOf("label" to "\$0", "onClick" to "{ Log.d(\"tap\") }"),
                addImport = "android.util.Log",
              )
          )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.counted

      @Composable
      fun ButtonSticker() {
        val c = counted("Filled")
        Button(onClick = c.onClick) { Text(c.label) }
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun ButtonSticker"), rules)
      )
    assertTrue(
      result.text.contains("""Button(onClick = { Log.d("tap") }) { Text("Filled") }"""),
      result.text,
    )
    assertTrue(result.text.contains("import android.util.Log"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /** Named arguments out of declaration order still bind by name, as Kotlin binds them. */
  @Test
  fun `a knob with reordered named arguments still resolves its default`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Title() = Text(previewOverrideString(default = "Shopping", key = "title"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
  }

  /**
   * A rule that declares no `params` cannot know which parameter a named argument names, so it
   * declines rather than guessing — and says so, as residue.
   */
  @Test
  fun `a substitute rule without params declines a named-argument call`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "catalogChoice" to UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$1")
          )
      )
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Style() = Text(catalogChoice(key = "style", default = "outlined"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Style"), rules))
    assertTrue(cleaned.text.contains("catalogChoice("), cleaned.text)
    assertTrue(cleaned.residue.contains("catalogChoice"), "${cleaned.residue}")
  }

  /**
   * `scaffoldsDeclared` drives a much stronger claim in the Source panel than annotation-stripping
   * earns, so only a catalog can turn it on. It used to be `scaffolds.isNotEmpty()`, which stopped
   * meaning that the moment GENERIC carried entries of its own.
   */
  @Test
  fun `inheriting the generic rules is not declaring scaffolding`() {
    with(UsageRules.Companion) {
      assertFalse(UsageRules.GENERIC.declaresCatalogScaffolds())
      assertFalse(assertNotNull(UsageRules.parse("{}")).declaresCatalogScaffolds())
      assertTrue(
        assertNotNull(UsageRules.parse("""{"scaffolds":{"Sticker":{"kind":"UNWRAP"}}}"""))
          .declaresCatalogScaffolds()
      )
    }
  }

  /**
   * A knob that has a plain reading is substituted rather than deleted. `catalogChoice` returns its
   * default on the baked lane by construction, so the default is what the render on screen was made
   * with — and it is the biggest single helper in m3-catalog after the frame and the tally.
   */
  @Test
  fun `a choice knob becomes the value the render was baked with`() {
    val choiceRules =
      rules.copy(
        scaffolds =
          rules.scaffolds +
            ("catalogChoice" to
              UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$1"))
      )
    val source =
      """
      package demo

      import androidx.compose.material3.AssistChip
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker
      import ee.schimke.m3catalog.catalogChoice

      @Composable
      fun Chip() = Sticker {
        val style = catalogChoice("style", "outlined", "outlined", "elevated")
        Text(style)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "val style"), choiceRules))
    assertTrue(result.text.contains("""val style = "outlined""""), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /**
   * A template citing an argument the call does not carry must leave the call alone rather than
   * emit a literal `$1` into somebody's editor.
   */
  @Test
  fun `a substitution template that cannot be filled is declined`() {
    val badRules =
      rules.copy(
        scaffolds =
          mapOf(
            "catalogChoice" to UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$7")
          )
      )
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.catalogChoice

      @Composable
      fun Chip() {
        Text(catalogChoice("style", "outlined"))
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text("), badRules)).text
    assertFalse(text.contains("\$7"), text)
    assertTrue(text.contains("catalogChoice(\"style\", \"outlined\")"), text)
  }

  // -----------------------------------------------------------------------------------------
  // Regressions found by review of the first cut. Each of these produced a seed that was
  // advertised as runnable and did not compile — the one failure mode the design says it must
  // not have. They survived the original fixture because ktfmt had wrapped its calls, putting
  // every knob on a line of its own where the buggy behaviour happened to be correct.
  // -----------------------------------------------------------------------------------------

  /**
   * Compose is built on imported extensions reached through receiver syntax, where every reference
   * to the imported name follows a dot. Pruning imports on the strict word test deleted `padding`
   * and `dp` out from under a body that still used them.
   */
  @Test
  fun `imports used through receiver syntax survive the prune`() {
    val source =
      """
      package demo

      import androidx.compose.foundation.layout.padding
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.unit.dp
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Padded() = Sticker {
        Text("hi", modifier = Modifier.padding(16.dp))
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"hi\""), rules))
        .text
    assertTrue(text.contains("import androidx.compose.foundation.layout.padding"), text)
    assertTrue(text.contains("import androidx.compose.ui.unit.dp"), text)
    assertTrue(text.contains("import androidx.compose.ui.Modifier"), text)
  }

  /** An aliased import must keep both the name the body uses and its `as` clause. */
  @Test
  fun `aliased imports keep their alias`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text as Label
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Aliased() = Sticker {
        Label("hi")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Label(\"hi\")"), rules))
        .text
    assertTrue(text.contains("import androidx.compose.material3.Text as Label"), text)
  }

  /**
   * The worst of them. A ktfmt-legal one-line call with a DROP helper in a named argument was
   * deleted whole, because the unbound call reported a "binding" whose line range was the entire
   * call's line — leaving an empty themed preview, with no residue to show for it.
   */
  @Test
  fun `a drop helper on a one-line call loses only its argument`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker
      import ee.schimke.m3catalog.catalogEnabled

      @Composable
      fun One() = Sticker {
        Button(onClick = {}, enabled = catalogEnabled()) { Text("Go") }
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Button("), rules))
    assertTrue(result.text.contains("""Button(onClick = {}) { Text("Go") }"""), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /**
   * An UNWRAP helper as the preview's expression body must not take `fun Card() =` with it. The
   * splice started at the line, not at the call.
   */
  @Test
  fun `unwrapping an expression body keeps the declaration`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.ButtonFrame

      @Composable
      fun Card() = ButtonFrame(2) {
        Text("inside")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(
          PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"inside\")"), rules)
        )
        .text
    assertTrue(text.contains("fun Card() ="), text)
    assertFalse(text.contains("ButtonFrame"), text)
  }

  /** A same-file `data class` the body still needs must come along with it. */
  @Test
  fun `modified type declarations are recognised by the closure`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      data class Model(val label: String)

      @Composable
      fun Row() = Sticker {
        Text(Model("hi").label)
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(Model"), rules)).text
    assertTrue(text.contains("data class Model(val label: String)"), text)
  }

  /** A wrapped `@file:OptIn(...)` must be emitted whole, not as its opening line. */
  @Test
  fun `a multiline file annotation survives intact`() {
    val source =
      """
      @file:OptIn(
        ExperimentalMaterial3ExpressiveApi::class,
        ExperimentalMaterial3Api::class,
      )

      package demo

      import androidx.compose.material3.ExperimentalMaterial3Api
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Opted() = Sticker {
        Text("hi")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"hi\")"), rules))
        .text
    assertTrue(text.contains("ExperimentalMaterial3Api::class,\n)"), text)
    assertTrue(text.contains("import androidx.compose.material3.ExperimentalMaterial3Api"), text)
  }

  /** String-resource inlining is masked like every other pass. */
  @Test
  fun `a resource lookup quoted inside a literal is not substituted`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Doc() = Sticker {
        Text("Use stringResource(Res.string.label_filled) for the label")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(
          PlaygroundSourceCleaner.clean(source, lineIn(source, "Use string"), rules, strings)
        )
        .text
    assertTrue(
      text.contains("""Text("Use stringResource(Res.string.label_filled) for the label")"""),
      text,
    )
  }

  /** An INLINE template citing an argument the call lacks must not delete the binding either. */
  @Test
  fun `an inline template that cannot be filled leaves the code alone`() {
    val badRules =
      rules.copy(
        scaffolds =
          mapOf(
            "counted" to
              UsageRules.Scaffold(kind = UsageRules.Kind.INLINE, members = mapOf("label" to "\$3"))
          )
      )
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.counted

      @Composable
      fun Tally() {
        val c = counted("Filled")
        Text(c.label)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "val c ="), badRules))
    assertFalse(result.text.contains("\$3"), result.text)
    assertTrue(result.text.contains("""val c = counted("Filled")"""), result.text)
    assertEquals(listOf("counted"), result.residue)
  }

  // -----------------------------------------------------------------------------------------------
  // Delegating catalogs: scaffold sources, EXPAND, and the string-keyed `when` dispatch (#4169).
  // -----------------------------------------------------------------------------------------------

  /**
   * A sticker sheet whose previews are one-line delegations, as `:samples:design-catalog-m3` is.
   */
  private val delegatingPreviews =
    """
    package demo.catalog

    import androidx.compose.runtime.Composable
    import demo.catalog.shared.CatalogComponent
    import ee.schimke.composeai.preview.CatalogComponent

    @CatalogComponent(id = "Button/Filled", group = "Buttons")
    @CatalogModes
    @Composable
    fun FilledButton() = Sticker("button-filled")
    """
      .trimIndent()

  private val stickerHelper =
    """
    package demo.catalog

    import androidx.compose.runtime.Composable
    import demo.catalog.shared.CatalogComponent

    @Composable fun Sticker(id: String) = CatalogSticker { CatalogComponent(id) }
    """
      .trimIndent()

  private val sharedComponents =
    """
    package demo.catalog.shared

    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable

    @Composable
    fun CatalogComponent(id: String) {
      when (id) {
        // The tally is what makes a stateless button do something visible.
        "button-filled" -> {
          val label = "Filled"
          Button(onClick = {}) { Text(label) }
        }
        "button-text" ->
          TextButton(onClick = {}) {
            Text("Text")
          }
        else -> Text("unknown")
      }
    }
    """
      .trimIndent()

  private val delegatingRules =
    UsageRules(
      scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview"),
      scaffoldAnnotationNames = listOf("CatalogModes"),
      scaffoldSources = listOf("a/Stickers.kt", "b/Components.kt"),
      scaffolds =
        mapOf(
          "Sticker" to UsageRules.Scaffold(kind = UsageRules.Kind.EXPAND),
          "CatalogComponent" to UsageRules.Scaffold(kind = UsageRules.Kind.EXPAND),
          "CatalogSticker" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP),
        ),
    )

  private fun cleanDelegating(
    needle: String,
    rules: UsageRules = delegatingRules,
    helpers: List<String> = listOf(stickerHelper, sharedComponents),
  ) =
    PlaygroundSourceCleaner.clean(
      source = delegatingPreviews,
      bodyLine = lineIn(delegatingPreviews, needle),
      rules = rules,
      parser = null,
      helperSources = helpers,
    )

  /**
   * The reported bug: a preview that is only a delegation has no component in it, so the whole
   * reduction has to cross into the file the component actually lives in.
   */
  @Test
  fun `a delegating preview expands to the component the shared set dispatches to`() {
    val result = assertNotNull(cleanDelegating("fun FilledButton"))

    assertTrue(result.text.contains("""Button(onClick = {}) { Text(label) }"""), result.text)
    // Only the branch that was asked for: expanding the dispatch whole would substitute the entire
    // catalog for the one component.
    assertFalse(result.text.contains("TextButton"), result.text)
    assertFalse(result.text.contains("unknown"), result.text)
    assertFalse(result.text.contains("when ("), result.text)
    // Neither wrapper survives, and the expression body became a block one so two statements fit.
    assertFalse(result.text.contains("Sticker"), result.text)
    assertFalse(result.text.contains("CatalogComponent"), result.text)
    assertTrue(result.text.contains("fun FilledButton() {"), result.text)
    // The annotation the catalog declares in the previews' own package — no import to resolve it
    // through — comes off too, and a real `@Preview` replaces it.
    assertFalse(result.text.contains("@CatalogModes"), result.text)
    assertTrue(result.text.contains("@Preview"), result.text)
    // The imports the spliced body needs come from the file the body came from.
    assertTrue(result.text.contains("import androidx.compose.material3.Button"), result.text)
    assertEquals(emptyList(), result.residue, result.text)
  }

  /**
   * A repo publishing several catalogs lists every catalog's scaffolding, so one name may be
   * declared twice. Picking either would splice one catalog's helper into another's snippet.
   */
  @Test
  fun `a name two scaffold sources both declare is not expanded`() {
    val rival =
      """
      package demo.other

      import androidx.compose.runtime.Composable

      @Composable fun Sticker(id: String) = Text("some other catalog's sticker")
      """
        .trimIndent()
    val result =
      assertNotNull(
        cleanDelegating(
          "fun FilledButton",
          helpers = listOf(stickerHelper, sharedComponents, rival),
        )
      )

    assertTrue(result.text.contains("""Sticker("button-filled")"""), result.text)
    assertFalse(result.text.contains("some other catalog"), result.text)
    assertTrue(result.residue.contains("Sticker"), result.residue.toString())
  }

  /** No scaffold sources ⇒ exactly the behaviour that shipped before EXPAND existed. */
  @Test
  fun `an EXPAND rule with nothing to read leaves the call alone`() {
    val result = assertNotNull(cleanDelegating("fun FilledButton", helpers = emptyList()))

    assertTrue(result.text.contains("""Sticker("button-filled")"""), result.text)
    assertFalse(result.text.contains("fun FilledButton() {"), result.text)
  }

  /**
   * A dispatch key the call does not pin to a literal cannot be resolved to one branch, and
   * guessing would put a component in front of a reader that the render never composed.
   */
  @Test
  fun `a dispatch whose key is not a literal is declined and reported`() {
    val computed =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable
      import demo.catalog.shared.CatalogComponent

      @Composable
      fun FilledButton(slug: String) {
        CatalogComponent(slug)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = computed,
          bodyLine = lineIn(computed, "CatalogComponent(slug)"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, sharedComponents),
        )
      )

    assertTrue(result.text.contains("CatalogComponent(slug)"), result.text)
    assertTrue(result.residue.contains("CatalogComponent"), result.residue.toString())
  }

  /**
   * The checked-in m3 catalog's shape, reduced: `CatalogStates.kt` declares `fun SegmentedToggle()
   * = Sticker("segmentedbutton")`, and that slug's branch in `CatalogComponents.kt` calls
   * `SegmentedToggle()` — a cross-file helper with the same name as the preview showing it.
   *
   * `skip` carries every name the preview file declares, on the reasoning that a local declaration
   * satisfies the reference. The entry is the one name that fails for: after EXPAND, its body is
   * the *sticker's* body, so the call is to the component, not to itself. Treating the entry as
   * satisfying it emitted a preview whose body called itself — unbounded recursion, reported clean
   * with no residue, and handed to the playground as a runnable snippet.
   */
  @Test
  fun `a helper sharing the entry preview's name is renamed rather than skipped`() {
    val previews =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun SegmentedToggle() = Sticker("segmentedbutton")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "segmentedbutton" -> SegmentedToggle()
          else -> Text("unknown")
        }
      }

      @Composable
      fun SegmentedToggle() {
        Text("Segmented")
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = previews,
          bodyLine = lineIn(previews, "fun SegmentedToggle"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The component came along, under a name that does not collide...
    assertTrue(result.text.contains("fun SegmentedToggleComponent"), result.text)
    assertTrue(result.text.contains("""Text("Segmented")"""), result.text)
    // ...the preview keeps the name the reader clicked...
    assertTrue(result.text.contains("fun SegmentedToggle()"), result.text)
    // ...and it calls the component rather than itself.
    assertTrue(result.text.contains("SegmentedToggleComponent()"), result.text)
    assertFalse(
      result.text.substringAfter("fun SegmentedToggle()").contains("\n  SegmentedToggle()"),
      "the preview must not call itself:\n${result.text}",
    )
    assertEquals(emptyList(), result.residue, result.text)
  }

  /**
   * A preview merely SHARING a name with a shared-module declaration it never calls is untouched.
   */
  @Test
  fun `a name shared with an uncalled helper is left alone`() {
    val previews =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun FilledButton() = Sticker("button-filled")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "button-filled" -> {
            val label = "Filled"
            Button(onClick = {}) { Text(label) }
          }
          else -> Text("unknown")
        }
      }

      @Composable
      fun FilledButton() {
        Text("not the one the sticker dispatches to")
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = previews,
          bodyLine = lineIn(previews, "fun FilledButton"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The branch inlines the button directly, so nothing calls `FilledButton` and no rename
    // happens.
    assertTrue(result.text.contains("fun FilledButton() {"), result.text)
    assertFalse(result.text.contains("FilledButtonComponent"), result.text)
  }

  /**
   * A scaffold annotation sharing its physical line with the rest of the declaration.
   *
   * Legal Kotlin, and checked in: `CatalogText.kt:42` is `@CatalogModes @Composable fun
   * TextBrandedSpecimen() = Sticker("text-branded")`. The stripper worked line-by-line, so
   * recognising `@CatalogModes` discarded the whole line — `@Composable` and the function with it —
   * leaving nothing to emit and falling back to the verbatim wrapper, which is the snippet this
   * class exists to replace.
   */
  @Test
  fun `a scaffold annotation sharing a line with the declaration takes only itself`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "text-branded" -> Text("Branded")
          else -> Text("unknown")
        }
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun TextBrandedSpecimen"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The scaffold annotation goes, everything else on that line stays, and the delegation still
    // reduces to the component behind it.
    assertFalse(result.text.contains("@CatalogModes"), result.text)
    assertTrue(result.text.contains("@Composable"), result.text)
    assertTrue(result.text.contains("fun TextBrandedSpecimen()"), result.text)
    assertTrue(result.text.contains("""Text("Branded")"""), result.text)
    assertFalse(result.text.contains("Sticker("), result.text)
  }

  /** An annotation with arguments on a shared line — the span to remove is not just a word. */
  @Test
  fun `only the matched annotation span is removed when it carries arguments`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.CatalogComponent

      @CatalogComponent(id = "Text/Branded", group = "Text") @Composable fun Branded() = Sticker("text-branded")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "text-branded" -> Text("Branded")
          else -> Text("unknown")
        }
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun Branded"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    assertFalse(result.text.contains("""id = "Text/Branded""""), result.text)
    assertTrue(result.text.contains("@Composable"), result.text)
    assertTrue(result.text.contains("fun Branded()"), result.text)
    assertTrue(result.text.contains("""Text("Branded")"""), result.text)
  }

  /**
   * An extension helper in a scaffold source, called the only way an extension can be.
   *
   * `helperIndex` keys on `declaredName`, which took the first identifier after `fun` — so `private
   * fun Morph.toComposePath(...)` was recorded under `Morph`, the RECEIVER. A body calling
   * `morph.toComposePath(progress)` matched neither that key (wrong word) nor, once keyed properly,
   * `mentionsWord` (which rejects a name preceded by `.`, correctly, for plain calls). The
   * checked-in `shape-morph` component is exactly this shape: its snippet carried
   * `ShapeMorphViewer` and left `toComposePath` behind — unresolved, and absent from residue too,
   * because the residue check reads the same index.
   */
  @Test
  fun `an extension helper is indexed by its callable name and followed through a receiver call`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun ShapeMorph() = Sticker("shape-morph")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "shape-morph" -> ShapeMorphViewer()
          else -> Unit
        }
      }

      @Composable
      fun ShapeMorphViewer() {
        val morph = rememberMorph()
        val path = morph.toComposePath(0.5f)
        Draw(path)
      }

      private fun Morph.toComposePath(progress: Float): String = "path at " + progress
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun ShapeMorph"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    assertTrue(result.text.contains("fun ShapeMorphViewer"), result.text)
    assertTrue(
      result.text.contains("fun Morph.toComposePath"),
      "the extension the snippet calls was left behind:\n${result.text}",
    )
    assertEquals(emptyList(), result.residue, result.text)
  }

  /**
   * The receiver-aware check must not drag in a same-named helper that is only read, not called.
   */
  @Test
  fun `a property read on a receiver does not pull in a same-named function`() {
    assertTrue(
      PlaygroundSourceCleaner.mentionsExtensionCall("morph.toComposePath(0.5f)", "toComposePath")
    )
    assertTrue(
      PlaygroundSourceCleaner.mentionsExtensionCall("a.b().toComposePath (x)", "toComposePath")
    )
    assertFalse(
      PlaygroundSourceCleaner.mentionsExtensionCall("state.toComposePath", "toComposePath")
    )
    // A package qualifier is not a receiver call.
    assertFalse(
      PlaygroundSourceCleaner.mentionsExtensionCall("\"morph.toComposePath(1f)\"", "toComposePath")
    )
  }

  private fun lineIn(text: String, needle: String): Int =
    text.lines().indexOfFirst { it.contains(needle) } + 1
}
