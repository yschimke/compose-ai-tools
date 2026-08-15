package com.example.samplelibrary

import android.text.Html
import android.view.LayoutInflater
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * `AndroidView`-hosted platform content in a **library** module — the shape issue #2957 is about.
 *
 * Rendering rich text is the classic reason a Compose app reaches for `AndroidView`: `TextView`
 * understands the `Html.fromHtml` spans that Compose's `AnnotatedString` doesn't cover for free.
 * Pocket Casts' `HtmlText` (podcast show notes, in the library module `:modules:services:compose`)
 * is exactly this, and it was one of the previews that rendered no PNG at all.
 *
 * The fixture is faithful to that failure. The inflated layout styles itself through
 * `?attr/sampleBodyTextAppearance`, an **app-owned** theme attribute, and this module is a library
 * — so there is no `<application android:theme>` for the preview host activity to inherit and the
 * attribute resolves against the platform default, which has never heard of it. Inflation throws
 * `UnsupportedOperationException: Failed to resolve attribute at index N`, that escapes
 * composition, and the render dies before writing anything. The design-artifacts export then drops
 * the component from the candidate join as "no static PNG".
 *
 * What makes it render is `composePreview { hostTheme.set("@style/Theme.SampleLibrary") }` in this
 * module's `build.gradle.kts`: the renderer's `PreviewHostTheme` resolves that name and applies the
 * theme to the host activity. `AndroidViewHtmlTextPixelTest` asserts the PNG exists and carries
 * drawn text.
 */
@Composable
fun HtmlShowNotes(html: String, modifier: Modifier = Modifier) {
  AndroidView(
    modifier = modifier.fillMaxWidth(),
    factory = { context ->
      LayoutInflater.from(context).inflate(R.layout.sample_html_text, null) as TextView
    },
    update = { view -> view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) },
  )
}

/** Show-notes markup with the span shapes a podcast feed actually ships. */
private const val SHOW_NOTES_HTML =
  "<b>Episode 42</b> &#8212; we talk about rendering Compose previews " +
    "<i>outside</i> Android Studio, and why <tt>AndroidView</tt> is the hard part."

@Preview(name = "HTML show notes", showBackground = true, widthDp = 320, heightDp = 180)
@Composable
fun HtmlShowNotesPreview() {
  MaterialTheme {
    Surface {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Show notes", style = MaterialTheme.typography.titleMedium)
        HtmlShowNotes(html = SHOW_NOTES_HTML, modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
}
