package com.example.designcatalogconfetti

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Hand-built 24×24 vector icons for the Confetti catalog. The module deliberately vendors no
// material-icons dependency (matching the Wear catalog's single hand-built star) — this small set
// covers exactly the glyphs the stickers use: the bookmark toggle, the lightning-talk bolt, the
// time-header clock, the speaker person, and the four bottom-nav destinations + search. `Icon`
// re-tints every path, so the fills/strokes here are placeholders the theme colours override.

private val Placeholder = SolidColor(Color.Black)

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply { path(fill = Placeholder, pathFillType = PathFillType.NonZero, pathBuilder = block) }
    .build()

private fun strokeIcon(name: String, width: Float = 2f, block: PathBuilder.() -> Unit): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply {
      path(
        stroke = Placeholder,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
      )
    }
    .build()

/** The classic filled bookmark ribbon — the *bookmarked* state (tinted `primary` at the call site). */
val IconBookmark: ImageVector =
  icon("Bookmark") {
    moveTo(6f, 2f)
    horizontalLineTo(18f)
    verticalLineTo(22f)
    lineTo(12f, 18f)
    lineTo(6f, 22f)
    close()
  }

/** The outlined bookmark ribbon with a small `+` — the *add bookmark* (un-bookmarked) state. */
val IconBookmarkAdd: ImageVector =
  strokeIcon("BookmarkAdd") {
    // ribbon outline
    moveTo(6f, 3f)
    horizontalLineTo(18f)
    verticalLineTo(21f)
    lineTo(12f, 17f)
    lineTo(6f, 21f)
    close()
    // the little plus near the top
    moveTo(12f, 6.5f)
    verticalLineTo(11.5f)
    moveTo(9.5f, 9f)
    horizontalLineTo(14.5f)
  }

/** A lightning bolt — the lightning-talk pill glyph. */
val IconBolt: ImageVector =
  icon("Bolt") {
    moveTo(13f, 2f)
    lineTo(4f, 14f)
    horizontalLineTo(11f)
    lineTo(10f, 22f)
    lineTo(20f, 9f)
    horizontalLineTo(12.5f)
    close()
  }

/** A clock face — the time-block section header glyph. */
val IconSchedule: ImageVector =
  strokeIcon("Schedule") {
    moveTo(3f, 12f)
    arcTo(9f, 9f, 0f, true, true, 21f, 12f)
    arcTo(9f, 9f, 0f, true, true, 3f, 12f)
    close()
    // hands
    moveTo(12f, 7f)
    verticalLineTo(12f)
    lineTo(15.5f, 14f)
  }

/** A person silhouette — the speaker avatar fallback and the Speakers nav destination. */
val IconPerson: ImageVector =
  icon("Person") {
    // head
    moveTo(8f, 8f)
    arcTo(4f, 4f, 0f, true, true, 16f, 8f)
    arcTo(4f, 4f, 0f, true, true, 8f, 8f)
    close()
    // shoulders
    moveTo(4f, 20.5f)
    curveTo(4f, 15.5f, 8f, 13.5f, 12f, 13.5f)
    curveTo(16f, 13.5f, 20f, 15.5f, 20f, 20.5f)
    close()
  }

/** A house — the Schedule (home) nav destination. */
val IconHome: ImageVector =
  icon("Home") {
    moveTo(12f, 3f)
    lineTo(21f, 11f)
    horizontalLineTo(18.5f)
    verticalLineTo(21f)
    horizontalLineTo(14f)
    verticalLineTo(15f)
    horizontalLineTo(10f)
    verticalLineTo(21f)
    horizontalLineTo(5.5f)
    verticalLineTo(11f)
    horizontalLineTo(3f)
    close()
  }

/** Two stacked bookmark ribbons — the Bookmarks nav destination. */
val IconBookmarks: ImageVector =
  icon("Bookmarks") {
    // back ribbon
    moveTo(8f, 2f)
    horizontalLineTo(20f)
    verticalLineTo(18f)
    lineTo(18f, 16.7f)
    verticalLineTo(4f)
    horizontalLineTo(8f)
    close()
    // front ribbon
    moveTo(4f, 6f)
    horizontalLineTo(16f)
    verticalLineTo(22f)
    lineTo(10f, 18f)
    lineTo(4f, 22f)
    close()
  }

/** A map pin — the Venue nav destination. */
val IconLocationOn: ImageVector =
  ImageVector.Builder(name = "LocationOn", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply {
      path(fill = Placeholder, pathFillType = PathFillType.EvenOdd) {
        // teardrop
        moveTo(12f, 2f)
        curveTo(8.1f, 2f, 5f, 5.1f, 5f, 9f)
        curveTo(5f, 14.2f, 12f, 22f, 12f, 22f)
        curveTo(12f, 22f, 19f, 14.2f, 19f, 9f)
        curveTo(19f, 5.1f, 15.9f, 2f, 12f, 2f)
        close()
        // inner hole
        moveTo(12f, 6.5f)
        arcTo(2.5f, 2.5f, 0f, true, true, 11.99f, 6.5f)
        close()
      }
    }
    .build()

/** A magnifier — the search field leading glyph. */
val IconSearch: ImageVector =
  strokeIcon("Search") {
    moveTo(4f, 10f)
    arcTo(6f, 6f, 0f, true, true, 16f, 10f)
    arcTo(6f, 6f, 0f, true, true, 4f, 10f)
    close()
    moveTo(14.5f, 14.5f)
    lineTo(20f, 20f)
  }

/** An ✕ — the search field trailing clear glyph. */
val IconClose: ImageVector =
  strokeIcon("Close") {
    moveTo(6f, 6f)
    lineTo(18f, 18f)
    moveTo(18f, 6f)
    lineTo(6f, 18f)
  }
