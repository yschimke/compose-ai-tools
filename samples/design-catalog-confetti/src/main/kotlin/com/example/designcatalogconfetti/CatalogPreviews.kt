@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogconfetti

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The Confetti catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
// Each is a thin wrapper over the reproductions in `CatalogComponents.kt`, framed by [ConfettiSticker]
// (transparent, Confetti-themed). Function names are the join key the export driver matches against
// `catalog.spec.json`'s `preview` field, so they must not change.
//
// The sample data — session titles, speakers, rooms — is Confetti's own mock data
// (`shared/.../preview/MockData.kt`), carried here as string resources so a `localeTag` override can
// translate the copy while proper nouns (speaker names, room names) stay literal.

// A fixed 360dp width for the full-bleed rows (session cards, headers, search, nav) so a frameless
// phone sticker reads as a real phone-width component rather than stretching to the canvas.
private val RowWidth = Modifier.width(360.dp)

// ---------------------------------------------------------------------------
// Sessions.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun SessionCardSticker() =
  ConfettiSticker {
    SessionCard(
      title = stringResource(R.string.session_title),
      speakers = stringResource(R.string.session_speakers),
      room = stringResource(R.string.room_effectenbeurszaal),
      bookmarked = false,
      modifier = RowWidth,
    )
  }

@CatalogModes
@Composable
fun SessionCardBookmarked() =
  ConfettiSticker {
    SessionCard(
      title = stringResource(R.string.session_title),
      speakers = stringResource(R.string.session_speakers),
      room = stringResource(R.string.room_effectenbeurszaal),
      bookmarked = true,
      modifier = RowWidth,
    )
  }

@CatalogModes
@Composable
fun LightningSessionCard() =
  ConfettiSticker {
    SessionCard(
      title = stringResource(R.string.lightning_title),
      speakers = stringResource(R.string.session_speakers2),
      room = stringResource(R.string.room_veilingzaal),
      bookmarked = false,
      lightning = stringResource(R.string.lightning_label),
      modifier = RowWidth,
    )
  }

@CatalogModes
@Composable
fun BreakSticker() =
  ConfettiSticker {
    BreakItem(
      title = stringResource(R.string.break_title),
      time = stringResource(R.string.break_time),
      modifier = RowWidth,
    )
  }

// ---------------------------------------------------------------------------
// Bookmark.
// ---------------------------------------------------------------------------

@CatalogModes @Composable fun BookmarkAddSticker() = ConfettiSticker { Bookmark(bookmarked = false) }

@CatalogModes @Composable fun BookmarkOnSticker() = ConfettiSticker { Bookmark(bookmarked = true) }

// ---------------------------------------------------------------------------
// Headers + the lightning pill.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun TimeHeaderSticker() =
  ConfettiSticker { ConfettiHeader(stringResource(R.string.header_1400), RowWidth) }

@CatalogModes
@Composable
fun LightningPillSticker() =
  ConfettiSticker { LightningPill(stringResource(R.string.lightning_label)) }

// ---------------------------------------------------------------------------
// Speakers.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun SpeakerListItemSticker() =
  ConfettiSticker {
    SpeakerListItem(
      name = stringResource(R.string.speaker_name),
      tagline = stringResource(R.string.speaker_tagline),
      modifier = RowWidth,
    )
  }

@CatalogModes
@Composable
fun SpeakerGridCellSticker() =
  ConfettiSticker { SpeakerGridCell(name = stringResource(R.string.speaker_name)) }

// ---------------------------------------------------------------------------
// Navigation.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun DayTabRowSticker() =
  ConfettiSticker {
    DayTabRow(
      days = listOf(stringResource(R.string.day_thu), stringResource(R.string.day_fri)),
      selected = 0,
      modifier = RowWidth,
    )
  }

@CatalogModes
@Composable
fun BottomBarSticker() = ConfettiSticker { ConfettiBottomBar(selected = 0, modifier = RowWidth) }

// ---------------------------------------------------------------------------
// Search.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun SearchFieldSticker() =
  ConfettiSticker { ConfettiSearchField(query = stringResource(R.string.search_query), modifier = RowWidth) }

// ---------------------------------------------------------------------------
// Conference themes — the payoff of the app's per-conference seeding: the same component set
// re-branded from each conference's `themeColor` seed (see [ConfettiConferences]). Each sticker
// pins its palette via [ConferenceSticker], so the grid shows the brands side by side.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun ConfettiBrandTheme() = ConferenceSticker("Confetti") { ConferenceThemeSpecimen("Confetti") }

@CatalogModes
@Composable
fun KotlinConfTheme() = ConferenceSticker("KotlinConf") { ConferenceThemeSpecimen("KotlinConf") }

@CatalogModes
@Composable
fun DevFestTheme() = ConferenceSticker("DevFest") { ConferenceThemeSpecimen("DevFest") }

@CatalogModes
@Composable
fun DroidconTheme() = ConferenceSticker("droidcon") { ConferenceThemeSpecimen("droidcon") }

@CatalogModes
@Composable
fun AndroidMakersTheme() =
  ConferenceSticker("Android Makers") { ConferenceThemeSpecimen("Android Makers") }

// ---------------------------------------------------------------------------
// Scaffold template — the full Confetti schedule screen, captured as a phone screenshot
// (`showSystemUi = true`): a CenterAlignedTopAppBar with the conference name, the day tabs, a time
// header, session rows (a talk, a bookmarked lightning talk), a break, and the bottom navigation.
// ---------------------------------------------------------------------------

@CatalogTemplate
@Composable
fun ScheduleScreenTemplate() =
  FullScreenConfetti {
    Scaffold(
      contentWindowInsets = WindowInsets(bottom = SYSTEM_BAR_INSET),
      topBar = {
        CenterAlignedTopAppBar(
          title = { Text(stringResource(R.string.conference_title)) },
          windowInsets = WindowInsets(top = SYSTEM_BAR_INSET),
          colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
        )
      },
      bottomBar = { ConfettiBottomBar(selected = 0, modifier = Modifier.padding(bottom = SYSTEM_BAR_INSET)) },
    ) { padding ->
      Column(Modifier.padding(padding).fillMaxSize()) {
        DayTabRow(
          days = listOf(stringResource(R.string.day_thu), stringResource(R.string.day_fri)),
          selected = 0,
          modifier = Modifier.fillMaxWidth(),
        )
        ConfettiHeader(stringResource(R.string.header_1400), Modifier.fillMaxWidth())
        SessionCard(
          title = stringResource(R.string.session_title),
          speakers = stringResource(R.string.session_speakers),
          room = stringResource(R.string.room_effectenbeurszaal),
          bookmarked = false,
          modifier = Modifier.fillMaxWidth(),
        )
        SessionCard(
          title = stringResource(R.string.lightning_title),
          speakers = stringResource(R.string.session_speakers2),
          room = stringResource(R.string.room_veilingzaal),
          bookmarked = true,
          lightning = stringResource(R.string.lightning_label),
          modifier = Modifier.fillMaxWidth(),
        )
        ConfettiHeader(stringResource(R.string.header_1500), Modifier.fillMaxWidth())
        BreakItem(
          title = stringResource(R.string.break_title),
          time = stringResource(R.string.break_time),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
