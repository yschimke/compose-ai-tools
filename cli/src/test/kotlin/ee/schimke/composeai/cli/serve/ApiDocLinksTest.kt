package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the page-shape rules in [ApiDocLinks].
 *
 * Every case here is a shape that got a **404 from `developer.android.com`** while the resolver was
 * measured against live catalog snippets — a top-level composable served the class page, or a value
 * class served the composable one. They are not stylistic preferences: drop one and the viewer goes
 * back to publishing dead reference links, silently, because a wrong page still looks like a link.
 */
class ApiDocLinksTest {

  private fun urls(source: String) = ApiDocLinks.of(source).associate { it.name to it.url }

  private fun ref(path: String) = "https://developer.android.com/reference/kotlin/$path"

  @Test
  fun `composable call and its defaults object take different page shapes`() {
    val links =
      urls(
        """
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.wear.compose.material3.Button
        import androidx.wear.compose.material3.ButtonDefaults
        import androidx.wear.compose.material3.Text
        import ee.schimke.wearm3catalog.Sticker

        @Preview
        @Composable
        fun ImageBackgroundButton() = Sticker {
          Button(
            onClick = {},
            colors = ButtonDefaults.buttonColors(),
            label = { Text("Hi") },
          )
        }
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/wear/compose/material3/Button.composable"), links["Button"])
    assertEquals(ref("androidx/wear/compose/material3/Text.composable"), links["Text"])
    // A qualifier is never a composable, so it keeps the declaration page.
    assertEquals(ref("androidx/wear/compose/material3/ButtonDefaults"), links["ButtonDefaults"])
    assertEquals(ref("androidx/compose/runtime/Composable"), links["Composable"])
    assertEquals(ref("androidx/compose/ui/tooling/preview/Preview"), links["Preview"])
    // The catalog's own helper has no published reference.
    assertTrue("Sticker" !in links)
  }

  @Test
  fun `composables lead the list, in the order the code names them`() {
    val names =
      ApiDocLinks.of(
          """
          import androidx.compose.runtime.Composable
          import androidx.compose.material3.Button
          import androidx.compose.material3.MaterialTheme
          import androidx.compose.material3.Text

          @Composable
          fun Demo() {
            Button(onClick = {}) { Text("Hi", style = MaterialTheme.typography.bodyLarge) }
          }
          """
            .trimIndent()
        )
        .map { it.name }
    assertEquals(listOf("Button", "Text", "Composable", "MaterialTheme"), names)
  }

  @Test
  fun `a value constructed as an argument is never a composable`() {
    val links =
      urls(
        """
        import androidx.compose.foundation.layout.PaddingValues
        import androidx.compose.material3.Text
        import androidx.compose.ui.graphics.Color

        fun demo() {
          Text("Hi", color = Color(0xFF00FF00), modifier = pad(PaddingValues(4.dp)))
        }
        """
          .trimIndent()
      )
    // `androidx.compose.ui.graphics` publishes values only, so the page shape is known outright.
    assertEquals(ref("androidx/compose/ui/graphics/Color"), links["Color"])
    // Elsewhere a constructor call in argument position is simply not evidence either way, and the
    // resolver drops what it cannot place rather than guessing at a page that may not exist.
    assertTrue("PaddingValues" !in links)
  }

  @Test
  fun `a wrapped argument is not a statement`() {
    // The call lands at the start of a line, but the line before it ends in `=`: this is the
    // continuation of an argument, not a new statement. Reading line starts alone got it wrong.
    val links =
      urls(
        """
        import androidx.compose.ui.graphics.SolidColor
        import androidx.wear.compose.material3.Button

        fun demo() {
          Button(
            colors =
              SolidColor(surface),
          )
        }
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/compose/ui/graphics/SolidColor"), links["SolidColor"])
    assertEquals(ref("androidx/wear/compose/material3/Button.composable"), links["Button"])
  }

  @Test
  fun `an expression-bodied composable is a statement`() {
    val links =
      urls(
        """
        import androidx.compose.runtime.Composable
        import androidx.wear.compose.material3.Icon

        @Composable private fun kitGlyph() = Icon(Icons.Filled.Add, contentDescription = "Add")
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/wear/compose/material3/Icon.composable"), links["Icon"])
  }

  @Test
  fun `a value-producing lambda is not a composition`() {
    val links =
      urls(
        """
        import androidx.compose.foundation.interaction.MutableInteractionSource
        import androidx.compose.runtime.remember

        fun demo() {
          val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
        }
        """
          .trimIndent()
      )
    // The `remember { … }` call site would otherwise read exactly like a composable one. The type
    // annotation is what places it — and without one it would be dropped, not mislabelled.
    assertEquals(
      ref("androidx/compose/foundation/interaction/MutableInteractionSource"),
      links["MutableInteractionSource"],
    )
  }

  @Test
  fun `comments and strings never decide a symbol's page`() {
    val links =
      urls(
        """
        import androidx.compose.material.icons.filled.Add
        import androidx.wear.compose.material3.Icon
        import androidx.wear.compose.material3.Slider

        fun demo() {
          // the bar draws `steps + 1` bands (visibleSegments = steps + 1, Slider.kt)
          Slider(value = 0.5f)
          Icon(Icons.Filled.Add, contentDescription = "Add")
        }
        """
          .trimIndent()
      )
    // `Slider.kt` in a comment used to read as a qualifier and demote the composable.
    assertEquals(ref("androidx/wear/compose/material3/Slider.composable"), links["Slider"])
    assertEquals(ref("androidx/wear/compose/material3/Icon.composable"), links["Icon"])
    // The icon packs are extension properties on `Icons.Filled`; the only thing that made `Add`
    // look like a symbol used by name was the `"Add"` content description.
    assertTrue("Add" !in links)
  }

  @Test
  fun `properties and extensions are left out rather than linked to a page that does not exist`() {
    val links =
      urls(
        """
        import androidx.compose.animation.core.LinearEasing
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.shape.CircleShape
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.platform.LocalDensity

        fun demo() {
          val density = LocalDensity.current
          box(Modifier.fillMaxSize(), shape = CircleShape, easing = LinearEasing)
        }
        """
          .trimIndent()
      )
    // Kept: a qualifier that really is a class.
    assertEquals(ref("androidx/compose/ui/Modifier"), links["Modifier"])
    // Dropped: extension function, bare property references, and a composition local.
    assertTrue("fillMaxSize" !in links)
    assertTrue("CircleShape" !in links)
    assertTrue("LinearEasing" !in links)
    assertTrue("LocalDensity" !in links)
  }

  @Test
  fun `an alias links the symbol it renames`() {
    val links =
      urls(
        """
        import androidx.wear.compose.material3.Text as WearText

        fun demo() {
          WearText("Hi")
        }
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/wear/compose/material3/Text.composable"), links["WearText"])
  }

  @Test
  fun `a line break ends the statement before a composable call`() {
    // Kotlin has no statement terminator, so the character before `Button(` is the `e` of `true`.
    // Reading only that dropped the component the card is about — the most visible way this can
    // be wrong, and invisible in a 404 count because a missing link 404s nothing.
    val links =
      urls(
        """
        import androidx.wear.compose.material3.Button

        fun demo() {
          val enabled = true
          Button(onClick = {}, enabled = enabled)
        }
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/wear/compose/material3/Button.composable"), links["Button"])
  }

  @Test
  fun `a constructor in a callback lambda is not a composable`() {
    // `onClick = { … }` and `label = { … }` are the same shape and opposite kinds, so the braces
    // cannot decide this. The namespace can: nothing outside Compose has a `.composable` page.
    val links =
      urls(
        """
        import android.content.Intent
        import androidx.wear.compose.material3.Button

        fun demo() {
          Button(onClick = { Intent(context, Target::class.java) })
        }
        """
          .trimIndent()
      )
    assertEquals(ref("android/content/Intent"), links["Intent"])
    assertEquals(ref("androidx/wear/compose/material3/Button.composable"), links["Button"])
  }

  @Test
  fun `a nested type keeps its dot`() {
    val links =
      urls(
        """
        import androidx.wear.protolayout.LayoutElementBuilders.Box

        fun demo() {
          val box: Box = build()
        }
        """
          .trimIndent()
      )
    // `…/LayoutElementBuilders/Box` asks for a directory; the site spells the nested type dotted.
    assertEquals(ref("androidx/wear/protolayout/LayoutElementBuilders.Box"), links["Box"])
  }

  @Test
  fun `an API written out in full is linked even with no import`() {
    // The cleaner's `MATERIAL3_SYSTEM_THEME` rewrite emits exactly this and prunes the import, so
    // reading import lines alone missed the most prominent API on the card.
    val links =
      urls(
        """
        import androidx.compose.runtime.Composable

        @Composable
        fun FilledButton() =
          androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.lightColorScheme()
          ) {}
        """
          .trimIndent()
      )
    // Called, so the composable page — the same reading a plain `MaterialTheme { }` would get.
    assertEquals(ref("androidx/compose/material3/MaterialTheme.composable"), links["MaterialTheme"])
  }

  @Test
  fun `a qualified use stops at the type, and a constant is not one`() {
    val links =
      urls(
        """
        fun demo() {
          val c = androidx.compose.ui.graphics.Color.Transparent
          request(android.permission.BLUETOOTH_CONNECT)
        }
        """
          .trimIndent()
      )
    // `Color.Transparent` is a member read off `Color`, not a nested type.
    assertEquals(ref("androidx/compose/ui/graphics/Color"), links["Color"])
    // A SCREAMING_CASE leaf is a String constant. Only the qualified scan can reach one, and an
    // import line is not there to say otherwise.
    assertTrue("BLUETOOTH_CONNECT" !in links)
  }

  @Test
  fun `a raw string is blanked whole`() {
    // Toggling per quote hands back alternating slices of a raw string as though they were code —
    // and `Button.foo` inside one reads as a qualifier, demoting the real composable call.
    val links =
      urls(
        """
        import androidx.compose.material3.Button

        fun demo() {
          val json = ${"\"\"\""}{"kind": "Button.foo"}${"\"\"\""}
          Button(onClick = {})
        }
        """
          .trimIndent()
      )
    assertEquals(ref("androidx/compose/material3/Button.composable"), links["Button"])
  }

  @Test
  fun `a snippet with no platform imports contributes nothing`() {
    assertEquals(emptyList(), ApiDocLinks.of("fun demo() { println(\"hi\") }"))
  }

  @Test
  fun `the list is capped`() {
    val source = buildString {
      repeat(40) { appendLine("import androidx.compose.material3.Comp$it") }
      appendLine("fun demo() {")
      repeat(40) { appendLine("  Comp$it()") }
      appendLine("}")
    }
    assertEquals(24, ApiDocLinks.of(source).size)
  }
}
