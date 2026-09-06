package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * A list with **real content**: distinct text per row, varying row heights, avatars and dividers.
 *
 * Flat colour bands are the one thing a content-aware stitcher cannot align — every slice looks
 * like every other slice, so there is nothing to anchor on. A fixture made of them says nothing
 * about whether the stitch works; this one gives the stitcher what a real screen gives it.
 */
private data class Mail(val from: String, val subject: String, val body: String, val at: String)

private val INBOX =
  listOf(
    Mail(
      "Priya Raman",
      "Re: quarterly render budget",
      "The numbers from the seed job look right to me — one caveat on the wear lane.",
      "09:14",
    ),
    Mail(
      "Build bot",
      "compose-preview 1.85.0 published",
      "Artifacts are on Maven Central.",
      "08:52",
    ),
    Mail(
      "Tomas Lindqvist",
      "Stadium screenshots",
      "Attached the three round sizes. The 192 one wraps differently, as you predicted, and I think the list header is the reason.",
      "08:31",
    ),
    Mail("Ana Beatriz", "Lunch?", "Thai place at 12:30.", "08:02"),
    Mail("Renovate", "chore(deps): update androidx.glance", "1 package updated.", "07:47"),
    Mail(
      "Kwame Mensah",
      "Widget footprints",
      "The large squircle renders at 432x248 now. Small is unchanged.",
      "07:20",
    ),
    Mail(
      "Sofia Marchetti",
      "Design review notes",
      "Three things came up: the fold behaviour on the supporting pane, the type scale at 1.3, and whether the empty state needs an illustration at all.",
      "Yesterday",
    ),
    Mail("CI", "Nightly: 412 green, 0 red", "Full matrix passed.", "Yesterday"),
    Mail(
      "Jonas Weber",
      "Re: expanded canvas",
      "Agreed on the proxy. Worth checking what it does to a sticky header.",
      "Yesterday",
    ),
    Mail(
      "Mira Costa",
      "Catalog drift",
      "The two DeviceDimensions copies agree again after the last sync.",
      "Tuesday",
    ),
    Mail(
      "Oluwaseun Adebayo",
      "Robolectric 4.17",
      "SDK 37 support is in the beta; our clamp still stops at 36.",
      "Tuesday",
    ),
    Mail(
      "Hannah Schmidt",
      "Typography",
      "Roboto Flex resolves offline now on the hosts that vendor it, and downloads elsewhere.",
      "Monday",
    ),
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaffoldedInbox() {
  Scaffold(topBar = { TopAppBar(title = { Text("Inbox") }) }) { padding ->
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
      items(INBOX) { mail ->
        Column {
          Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
              Modifier.size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
              contentAlignment = Alignment.Center,
            ) {
              Text(mail.from.take(1), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.padding(start = 12.dp)) {
              Row(Modifier.fillMaxWidth()) {
                Text(
                  mail.from,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.weight(1f),
                )
                Text(mail.at, style = MaterialTheme.typography.labelSmall)
              }
              Text(mail.subject, style = MaterialTheme.typography.bodyMedium)
              Text(mail.body, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
          }
          HorizontalDivider()
        }
      }
    }
  }
}

/** The stitched extent, which is what a proxy pane would be tested against. */
@Preview(name = "Inbox Long", showBackground = true, widthDp = 320, heightDp = 480)
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun ScaffoldedInboxLongPreview() {
  ScaffoldedInbox()
}

/** The same screen at its frame, for comparison. */
@Preview(name = "Inbox Frame", showBackground = true, widthDp = 320, heightDp = 480)
@Composable
fun ScaffoldedInboxFramePreview() {
  ScaffoldedInbox()
}
