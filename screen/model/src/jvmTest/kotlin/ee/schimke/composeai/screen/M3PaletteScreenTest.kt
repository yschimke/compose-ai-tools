package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four real screens, from four different sample apps, rebuilt as builder documents.
 *
 * This is the palette's acceptance test and the reason it holds what it holds. Each document below
 * is a screen that already exists in this repository as a `@Preview` — the source it is measured
 * against is checked in, and its render is what `composePreviewRender` produces for it. The
 * generated source will never be *identical*, because the originals call their own composables and
 * read their own string resources, but the **padding, the modifiers and the arguments** should be
 * the same ones. That is the question being asked: can this vocabulary express a real screen, or
 * only a demo.
 */
class M3PaletteScreenTest {

  private fun generate(document: ScreenDocument): ScreenGenerator.Result =
    ScreenGenerator.generate(
      document,
      M3Palette.records,
      packageName = "generated.screen",
      expressionPackages = M3Palette.expressionPackages,
    )

  private fun source(document: ScreenDocument): String {
    val result = generate(document)
    if (result is ScreenGenerator.Result.Refused) {
      throw AssertionError("refused:\n  " + result.reasons.joinToString("\n  "))
    }
    return (result as ScreenGenerator.Result.Emitted).source.also { println(it) }
  }

  private fun modifier(vararg links: String, paddingDp: Int = 16): ScreenValue {
    val available = M3Palette.modifierLinks(paddingDp)
    return ScreenValue.Chain(
      receiver = M3Palette.modifierReceiver,
      links = links.map { label -> available.first { it.first == label }.second },
      typeFqn = "androidx.compose.ui.Modifier",
    )
  }

  private fun text(value: String, style: String? = null): ScreenNode =
    ScreenNode(
      "text",
      arguments =
        buildMap {
          put("text", ScreenValue.Text(value))
          if (style != null) {
            put(
              "style",
              M3Palette.choicesFor("androidx.compose.ui.text.TextStyle")
                .first { it.first == style }
                .second,
            )
          }
        },
    )

  private fun color(name: String): ScreenValue =
    M3Palette.choicesFor("androidx.compose.ui.graphics.Color").first { it.first == name }.second

  /**
   * `samples/android-library` — `LibraryGreetingPreview`.
   *
   * `Surface { Column(Modifier.padding(16.dp)) { Text(…) } }`. The simplest of the four, and the
   * one that pins the padding amount: the original is 16, and a palette offering only 8 could not
   * build it.
   */
  @Test
  fun `library greeting`() {
    val screen =
      ScreenDocument(
        name = "LibraryGreeting",
        root =
          ScreenNode(
            "surface",
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      "column",
                      arguments = mapOf("modifier" to modifier("padding(16)")),
                      slots = mapOf("content" to listOf(text("Library: Hello"))),
                    )
                  )
              ),
          ),
      )

    val source = source(screen)
    assertTrue(source, source.contains("Modifier.padding(16.dp)"))
    assertTrue(source, source.contains("""Text(text = "Library: Hello")"""))
  }

  /**
   * `samples/android` — `PermissionGatedCameraScreen`, denied branch.
   *
   * The one that asks the most of the *argument* vocabulary: a themed surface colour, a `spacedBy`
   * arrangement, two typography styles and a button's content padding.
   */
  @Test
  fun `camera permission denied`() {
    val screen =
      ScreenDocument(
        name = "CameraPermission",
        root =
          ScreenNode(
            "surface",
            arguments = mapOf("color" to color("background")),
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      "column",
                      arguments =
                        mapOf(
                          "modifier" to modifier("fillMaxWidth", "padding(16)"),
                          "verticalArrangement" to M3Palette.spacedBy(12),
                        ),
                      slots =
                        mapOf(
                          "content" to
                            listOf(
                              text("Camera access", style = "titleMedium"),
                              text(
                                "We need camera permission to capture photos.",
                                style = "bodyMedium",
                              ),
                              ScreenNode(
                                "button",
                                arguments =
                                  mapOf(
                                    "contentPadding" to
                                      M3Palette.paddingValues(horizontal = 16, vertical = 8)
                                  ),
                                slots = mapOf("content" to listOf(text("Grant camera access"))),
                              ),
                            )
                        ),
                    )
                  )
              ),
          ),
      )

    val source = source(screen)
    assertTrue(
      source,
      source.contains("color = MaterialTheme.colorScheme.background"),
    )
    assertTrue(source, source.contains("Modifier.fillMaxWidth().padding(16.dp)"))
    assertTrue(source, source.contains("Arrangement.spacedBy(12.dp)"))
    assertTrue(source, source.contains("MaterialTheme.typography.titleMedium"))
    assertTrue(source, source.contains("PaddingValues(horizontal = 16.dp, vertical = 8.dp)"))
  }

  /**
   * `samples/design-catalog-m3` — `AppScaffoldTemplate`.
   *
   * A `Scaffold` filling three different slots, which is what makes it worth building: `topBar`,
   * `floatingActionButton` and `content` are distinct drop targets, and a builder that offers one
   * slot per container cannot express any screen shaped like an app.
   */
  @Test
  fun `app scaffold template`() {
    val screen =
      ScreenDocument(
        name = "AppScaffold",
        root =
          ScreenNode(
            "scaffold",
            slots =
              mapOf(
                "topBar" to
                  listOf(
                    ScreenNode(
                      "top-app-bar",
                      slots = mapOf("title" to listOf(text("Inbox"))),
                    )
                  ),
                "floatingActionButton" to
                  listOf(ScreenNode("fab", slots = mapOf("content" to listOf(text("+"))))),
                "content" to
                  listOf(
                    ScreenNode(
                      "column",
                      arguments = mapOf("modifier" to modifier("fillMaxSize")),
                      slots =
                        mapOf(
                          "content" to
                            listOf(
                              ScreenNode(
                                "list-item",
                                slots =
                                  mapOf(
                                    "headlineContent" to listOf(text("Alex Kim")),
                                    "supportingContent" to listOf(text("Lunch at one?")),
                                  ),
                              ),
                              ScreenNode("divider"),
                              ScreenNode(
                                "list-item",
                                slots =
                                  mapOf(
                                    "headlineContent" to listOf(text("Design team")),
                                    "supportingContent" to listOf(text("Specs are up")),
                                  ),
                              ),
                            )
                        ),
                    )
                  ),
              ),
          ),
      )

    val source = source(screen)
    assertTrue(source, source.contains("TopAppBar(title = {"))
    assertTrue(source, source.contains("FloatingActionButton("))
    assertTrue(source, source.contains("ListItem("))
    assertTrue(source, source.contains("HorizontalDivider("))
    // `TopAppBar` is experimental, so the screen carries the opt-in rather than failing to
    // compile. `ExperimentalMaterial3Api` is declared with Kotlin's `@RequiresOptIn`, not the
    // AndroidX one, so it belongs under `kotlin.OptIn` — the two annotations reject each other's
    // markers, and the generator splits them by the mechanism the record names.
    assertTrue(
      source,
      source.contains("@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)"),
    )
  }
}
