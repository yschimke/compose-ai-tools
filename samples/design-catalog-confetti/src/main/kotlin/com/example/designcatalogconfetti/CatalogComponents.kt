@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogconfetti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.preview.slots.PreviewSlot

// The Confetti component set: standalone Material 3 reproductions of the conference app's signature
// surfaces (shared `dev.johnoreilly.confetti.ui.*`). Each is authored against stock `androidx`
// material3 — no dependency on the Confetti codebase — but mirrors the real layout, text hierarchy,
// and iconography (see the mapping in the module KDoc). The stickers in `CatalogPreviews.kt` wrap
// these; the full-screen schedule template composes them into a real screen.

// ---------------------------------------------------------------------------
// Sessions — the signature Confetti list row.
// ---------------------------------------------------------------------------

/**
 * A session row — Confetti's `SessionItemView`. A `Surface` + `Row` (16dp horizontal / 8dp vertical
 * padding): a left column with an optional [lightning] pill, the bold title, the speaker names, and
 * the room in the muted `onSurfaceVariant`; the [Bookmark] toggle on the right. The title/speakers
 * region is a [PreviewSlot] so a structured-screen builder can target the fillable text block.
 */
@Composable
fun SessionCard(
  title: String,
  speakers: String,
  room: String,
  bookmarked: Boolean,
  lightning: String? = null,
  modifier: Modifier = Modifier,
) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
      PreviewSlot("content", Modifier.weight(1f)) {
        Column {
          if (lightning != null) {
            LightningPill(lightning)
            Spacer(Modifier.size(4.dp))
          }
          Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
          Text(speakers, style = MaterialTheme.typography.bodyMedium)
          Text(
            room,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Bookmark(bookmarked)
    }
  }
}

/**
 * A non-session schedule row — a break (Confetti's `Break`). No room, no bookmark, not clickable:
 * just the title and its time span in the muted `onSurfaceVariant`.
 */
@Composable
fun BreakItem(title: String, time: String, modifier: Modifier = Modifier) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
      Text(
        time,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The bookmark toggle — Confetti's `Bookmark`. Bookmarked → filled ribbon tinted `primary`;
 *  otherwise the outlined "add bookmark" ribbon in the default content tint. */
@Composable
fun Bookmark(bookmarked: Boolean, onToggle: () -> Unit = {}) {
  IconButton(onClick = onToggle) {
    if (bookmarked) {
      Icon(
        IconBookmark,
        contentDescription = stringResource(R.string.cd_bookmarked),
        tint = MaterialTheme.colorScheme.primary,
      )
    } else {
      Icon(IconBookmarkAdd, contentDescription = stringResource(R.string.cd_bookmark_add))
    }
  }
}

/** The lightning-talk pill from inside a session row — a `primaryContainer` capsule with the bolt. */
@Composable
fun LightningPill(label: String) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Row(
      Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(IconBolt, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.size(4.dp))
      Text(label, style = MaterialTheme.typography.labelMedium)
    }
  }
}

// ---------------------------------------------------------------------------
// Headers — Confetti's sticky section header.
// ---------------------------------------------------------------------------

/**
 * A section header — Confetti's `ConfettiHeader`. A `surface` bar at 2dp tonal elevation between two
 * dividers, with an optional leading [icon] and bold `bodyLarge` [text]. Used for the schedule's
 * time blocks (clock icon + `"14:00"`) and the search groups (person/event icon + group name).
 */
@Composable
fun ConfettiHeader(text: String, modifier: Modifier = Modifier, icon: ImageVector? = IconSchedule) {
  Column(modifier) {
    HorizontalDivider()
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (icon != null) {
          Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
      }
    }
    HorizontalDivider()
  }
}

// ---------------------------------------------------------------------------
// Speakers.
// ---------------------------------------------------------------------------

/** A circular speaker avatar with the person-icon fallback (Confetti's `SubcomposeAsyncImage` error
 *  state), on a `secondaryContainer` disc. */
@Composable
fun SpeakerAvatar(diameter: Int, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
  Box(
    Modifier.size(diameter.dp).background(MaterialTheme.colorScheme.secondaryContainer, shape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      IconPerson,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.size((diameter * 0.6f).dp),
    )
  }
}

/** A speaker row — Confetti's `SpeakerItemView`: a `ListItem` with a 64dp circular avatar, the
 *  speaker name as headline, and the tagline as supporting text. */
@Composable
fun SpeakerListItem(name: String, tagline: String, modifier: Modifier = Modifier) {
  ListItem(
    modifier = modifier,
    headlineContent = { Text(name) },
    supportingContent = { Text(tagline) },
    leadingContent = { SpeakerAvatar(64) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}

/** A speaker grid cell — Confetti's `SpeakersGridView` item: a 150dp rounded avatar above the
 *  centred name in `onSecondaryContainer`. */
@Composable
fun SpeakerGridCell(name: String, modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    SpeakerAvatar(150, RoundedCornerShape(16.dp))
    Spacer(Modifier.size(8.dp))
    Text(
      name,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      textAlign = TextAlign.Center,
    )
  }
}

// ---------------------------------------------------------------------------
// Navigation.
// ---------------------------------------------------------------------------

/** The day tabs over the schedule — Confetti's `SessionListTabRow`: a transparent `TabRow` with one
 *  tab per conference day, each a formatted date. */
@Composable
fun DayTabRow(days: List<String>, selected: Int, modifier: Modifier = Modifier) {
  TabRow(
    selectedTabIndex = selected,
    modifier = modifier,
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    days.forEachIndexed { i, day ->
      Tab(selected = i == selected, onClick = {}, text = { Text(day) })
    }
  }
}

private data class NavDestination(val icon: ImageVector, val label: String)

/** The bottom navigation — Confetti's `BottomBar`: a `NavigationBar` (0 tonal elevation) above a
 *  divider, with the app's four destinations. */
@Composable
fun ConfettiBottomBar(selected: Int, modifier: Modifier = Modifier) {
  val destinations =
    listOf(
      NavDestination(IconHome, stringResource(R.string.nav_schedule)),
      NavDestination(IconPerson, stringResource(R.string.nav_speakers)),
      NavDestination(IconBookmarks, stringResource(R.string.nav_bookmarks)),
      NavDestination(IconLocationOn, stringResource(R.string.nav_venue)),
    )
  Column(modifier) {
    HorizontalDivider()
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
      destinations.forEachIndexed { i, dest ->
        NavigationBarItem(
          selected = i == selected,
          onClick = {},
          icon = { Icon(dest.icon, contentDescription = dest.label) },
          label = { Text(dest.label) },
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Search.
// ---------------------------------------------------------------------------

/** The search field — Confetti's `SearchView` text field: a rounded (`shapes.large`) `TextField`
 *  with a leading magnifier, a trailing clear ✕, and no indicator line. */
@Composable
fun ConfettiSearchField(query: String, modifier: Modifier = Modifier) {
  TextField(
    value = previewOverrideString("query", query),
    onValueChange = {},
    modifier = modifier,
    singleLine = true,
    shape = MaterialTheme.shapes.large,
    leadingIcon = { Icon(IconSearch, contentDescription = null) },
    trailingIcon = { Icon(IconClose, contentDescription = stringResource(R.string.cd_clear)) },
    placeholder = { Text(stringResource(R.string.search_hint)) },
    colors =
      TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
      ),
  )
}

// ---------------------------------------------------------------------------
// Theme specimen — used by the "Conference themes" group.
// ---------------------------------------------------------------------------

/** A brand swatch row + a real session card, so each conference sticker shows both its token set and
 *  the brand applied to a component (the bookmark tint, the lightning pill). */
@Composable
fun ConferenceThemeSpecimen(conference: String, modifier: Modifier = Modifier) {
  Column(modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Swatch(MaterialTheme.colorScheme.primary)
      Swatch(MaterialTheme.colorScheme.primaryContainer)
      Swatch(MaterialTheme.colorScheme.secondary)
      Swatch(MaterialTheme.colorScheme.tertiary)
    }
    SessionCard(
      title = conference,
      speakers = stringResource(R.string.session_speakers),
      room = stringResource(R.string.room_effectenbeurszaal),
      bookmarked = true,
      lightning = stringResource(R.string.lightning_label),
    )
  }
}

@Composable
private fun Swatch(color: Color) {
  Box(Modifier.size(40.dp).background(color, RoundedCornerShape(8.dp)))
}
