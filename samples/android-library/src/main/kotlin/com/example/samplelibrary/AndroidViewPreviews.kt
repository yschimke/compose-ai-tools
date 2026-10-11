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
 * `AndroidView`-hosted rich text (`TextView` + `Html.fromHtml`) in a library module. The layout
 * styles itself through an app-owned theme attribute, which a library has no `<application
 * android:theme>` to provide, so inflation would throw and the render produce no PNG.
 * `composePreview { hostTheme.set("@style/Theme.SampleLibrary") }` makes the host activity apply
 * that theme; `AndroidViewHtmlTextPixelTest` checks the result.
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
