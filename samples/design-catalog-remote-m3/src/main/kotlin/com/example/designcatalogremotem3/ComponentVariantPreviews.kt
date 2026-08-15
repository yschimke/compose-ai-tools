@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.animateRemoteFloat
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteInt
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.wear.compose.remote.material3.RemoteAppCard
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteButtonDefaults
import androidx.wear.compose.remote.material3.RemoteCircularProgressIndicator
import androidx.wear.compose.remote.material3.RemoteCompactButton
import androidx.wear.compose.remote.material3.RemoteHorizontalPageIndicator
import androidx.wear.compose.remote.material3.RemoteIcon
import androidx.wear.compose.remote.material3.RemoteIconButton
import androidx.wear.compose.remote.material3.RemoteIconButtonDefaults
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemotePageIndicatorState
import androidx.wear.compose.remote.material3.RemoteText
import androidx.wear.compose.remote.material3.RemoteTextButton
import androidx.wear.compose.remote.material3.RemoteTextButtonDefaults
import androidx.wear.compose.remote.material3.RemoteTimeText
import androidx.wear.compose.remote.material3.RemoteTitleCard
import androidx.wear.compose.remote.material3.RemoteVerticalPageIndicator
import androidx.wear.compose.remote.material3.rememberRemotePageIndicatorState
import ee.schimke.composeai.preview.AnimatedPreview

// This file is the variant matrix for the public component surface in remote-material3 alpha09.
// CatalogPreviews.kt keeps the concise one-per-family set; these previews cover every public size,
// the supported emphasis treatments, and the optional icon/label/time/content slots discovered by
// reviewing the AndroidX source JAR that the module actually resolves.

@CatalogRemoteModes
@Composable
fun TonalRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Tonal")
  RemoteButton(
    onClick = onClick,
    colors =
      RemoteButtonDefaults.buttonColors(
        containerColor = RemoteMaterialTheme.colorScheme.secondaryContainer,
        contentColor = RemoteMaterialTheme.colorScheme.onSecondaryContainer,
        secondaryContentColor = RemoteMaterialTheme.colorScheme.onSecondaryContainer,
        iconColor = RemoteMaterialTheme.colorScheme.onSecondaryContainer,
      ),
    content = { RemoteText(label) },
  )
}

@CatalogRemoteLarge
@Composable
fun IconLabelRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Favourite")
  RemoteButton(
    onClick = onClick,
    icon = {
      RemoteIcon(
        starIcon,
        contentDescription = null,
        modifier = RemoteModifier.size(RemoteButtonDefaults.IconSize),
      )
    },
    label = { RemoteText(label) },
  )
}

@CatalogRemoteLarge
@Composable
fun IconLabelSecondaryRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Morning run")
  RemoteButton(
    onClick = onClick,
    icon = {
      RemoteIcon(
        starIcon,
        contentDescription = null,
        modifier = RemoteModifier.size(RemoteButtonDefaults.LargeIconSize),
      )
    },
    secondaryLabel = { RemoteText("5.2 km · 28 min".rs) },
    label = { RemoteText(label) },
  )
}

@CatalogRemoteModes
@Composable
fun DisabledRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = valueChange(rememberMutableRemoteInt(0), 1.ri),
    enabled = false.rb,
    content = { RemoteText("Disabled".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun CompactIconLabelRemoteButton() = RemoteSticker {
  val (label, onClick) = countedRemote("Add")
  RemoteCompactButton(
    onClick = onClick,
    icon = {
      RemoteIcon(
        starIcon,
        contentDescription = null,
        modifier = RemoteModifier.size(RemoteButtonDefaults.ExtraSmallIconSize),
      )
    },
    label = { RemoteText(label) },
  )
}

@CatalogRemoteModes
@Composable
fun CompactIconOnlyRemoteButton() = RemoteSticker {
  val (on, onClick) = toggledRemote()
  val stock = RemoteButtonDefaults.buttonColors()
  RemoteCompactButton(
    onClick = onClick,
    colors =
      RemoteButtonDefaults.buttonColors(
        containerColor =
          tween(stock.containerColor, RemoteMaterialTheme.colorScheme.tertiaryContainer, on)
      ),
    icon = {
      RemoteIcon(
        starIcon,
        contentDescription = "Favourite".rs,
        modifier = RemoteModifier.size(RemoteButtonDefaults.SmallIconSize),
      )
    },
    label = null,
  )
}

@CatalogRemoteModes
@Composable
fun ExtraSmallRemoteIconButton() = RemoteSticker {
  SizedIconButton(RemoteIconButtonDefaults.ExtraSmallButtonSize)
}

@CatalogRemoteModes
@Composable
fun SmallRemoteIconButton() = RemoteSticker {
  SizedIconButton(RemoteIconButtonDefaults.SmallButtonSize)
}

@CatalogRemoteModes
@Composable
fun LargeRemoteIconButton() = RemoteSticker {
  SizedIconButton(RemoteIconButtonDefaults.LargeButtonSize)
}

@Composable
@RemoteComposable
private fun SizedIconButton(size: androidx.compose.remote.creation.compose.state.RemoteDp) {
  val (on, onClick) = toggledRemote()
  RemoteIconButton(
    onClick = onClick,
    modifier = RemoteModifier.size(size),
    colors =
      RemoteIconButtonDefaults.iconButtonColors(
        containerColor =
          tween(
            RemoteMaterialTheme.colorScheme.surfaceContainer,
            RemoteMaterialTheme.colorScheme.primaryContainer,
            on,
          ),
        contentColor = RemoteMaterialTheme.colorScheme.onSurface,
      ),
    content = {
      RemoteIcon(
        starIcon,
        contentDescription = "Favourite".rs,
        modifier = RemoteModifier.size(RemoteIconButtonDefaults.iconSizeFor(size)),
      )
    },
  )
}

@CatalogRemoteModes
@Composable
fun FilledRemoteIconButton() = RemoteSticker {
  val (on, onClick) = toggledRemote()
  RemoteIconButton(
    onClick = onClick,
    colors =
      RemoteIconButtonDefaults.iconButtonColors(
        containerColor =
          tween(
            RemoteMaterialTheme.colorScheme.primary,
            RemoteMaterialTheme.colorScheme.tertiaryContainer,
            on,
          ),
        contentColor =
          tween(
            RemoteMaterialTheme.colorScheme.onPrimary,
            RemoteMaterialTheme.colorScheme.onTertiaryContainer,
            on,
          ),
      ),
    content = { RemoteIcon(starIcon, "Favourite".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun OutlinedRemoteIconButton() = RemoteSticker {
  val (on, onClick) = toggledRemote()
  RemoteIconButton(
    onClick = onClick,
    colors =
      RemoteIconButtonDefaults.iconButtonColors(
        containerColor =
          tween(
            RemoteColor(androidx.compose.ui.graphics.Color.Transparent),
            RemoteMaterialTheme.colorScheme.primaryContainer,
            on,
          )
      ),
    border = 2.rdp,
    borderColor = RemoteMaterialTheme.colorScheme.outline,
    content = { RemoteIcon(starIcon, "Favourite".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun SmallRemoteTextButton() = RemoteSticker {
  SizedTextButton(
    size = RemoteTextButtonDefaults.SmallButtonSize,
    style = RemoteTextButtonDefaults.smallButtonTextStyle,
    label = "Aa",
  )
}

@CatalogRemoteModes
@Composable
fun LargeRemoteTextButton() = RemoteSticker {
  SizedTextButton(
    size = RemoteTextButtonDefaults.LargeButtonSize,
    style = RemoteTextButtonDefaults.largeButtonTextStyle,
    label = "Aa",
  )
}

@Composable
@RemoteComposable
private fun SizedTextButton(
  size: androidx.compose.remote.creation.compose.state.RemoteDp,
  style: androidx.compose.remote.creation.compose.text.RemoteTextStyle,
  label: String,
) {
  val (text, onClick) = countedRemote(label)
  RemoteTextButton(
    onClick = onClick,
    modifier = RemoteModifier.size(size),
    colors =
      RemoteTextButtonDefaults.textButtonColors(
        containerColor = RemoteMaterialTheme.colorScheme.surfaceContainer,
        contentColor = RemoteMaterialTheme.colorScheme.onSurface,
      ),
    content = { RemoteText(text, style = style) },
  )
}

@CatalogRemoteModes
@Composable
fun FilledRemoteTextButton() = RemoteSticker {
  val (label, onClick) = countedRemote("OK")
  RemoteTextButton(
    onClick = onClick,
    colors =
      RemoteTextButtonDefaults.textButtonColors(
        containerColor = RemoteMaterialTheme.colorScheme.primary,
        contentColor = RemoteMaterialTheme.colorScheme.onPrimary,
      ),
    content = { RemoteText(label) },
  )
}

@CatalogRemoteModes
@Composable
fun OutlinedRemoteTextButton() = RemoteSticker {
  val (label, onClick) = countedRemote("More")
  RemoteTextButton(
    onClick = onClick,
    border = 2.rdp,
    borderColor = RemoteMaterialTheme.colorScheme.outline,
    content = { RemoteText(label) },
  )
}

@CatalogRemoteLarge
@Composable
fun TitleOnlyRemoteTitleCard() = RemoteSticker {
  val (title, onClick) = countedRemote("Morning run")
  RemoteTitleCard(onClick = onClick, title = { RemoteText(title) })
}

@CatalogRemoteLarge
@Composable
fun TimeContentRemoteTitleCard() = RemoteSticker {
  val (title, onClick) = countedRemote("Morning run")
  RemoteTitleCard(
    onClick = onClick,
    title = { RemoteText(title) },
    time = { RemoteText("10:10".rs) },
    content = { RemoteText("5.2 km · 28 min".rs) },
  )
}

@CatalogRemoteLarge
@Composable
fun TimeRemoteAppCard() = RemoteSticker {
  val (title, onClick) = countedRemote("New message")
  RemoteAppCard(
    onClick = onClick,
    appName = { RemoteText("Messages".rs) },
    title = { RemoteText(title) },
    time = { RemoteText("10:10".rs) },
    content = { RemoteText("See you soon".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun DisabledCircularProgressRemote() = RemoteSticker {
  RemoteCircularProgressIndicator(
    progress = 0.66f.rf,
    enabled = false.rb,
    modifier = RemoteModifier.size(72.rdp),
  )
}

@CatalogRemoteModes
@Composable
fun IndeterminateCircularProgressRemote() = RemoteSticker {
  RemoteCircularProgressIndicator(modifier = RemoteModifier.size(72.rdp))
}

@CatalogRemoteModes
@AnimatedPreview(
  durationMs = 2000,
  frameIntervalMs = 50,
  showCurves = false,
  caption = "The indeterminate arc rotates and changes sweep continuously on the remote clock.",
)
@Composable
fun IndeterminateCircularProgressMotionRemote() = RemoteSticker {
  RemoteCircularProgressIndicator(modifier = RemoteModifier.size(72.rdp))
}

@CatalogRemoteModes
@Composable
fun HorizontalPageIndicatorRemote() = RemoteSticker {
  RemoteHorizontalPageIndicator(
    state = rememberRemotePageIndicatorState(pageCount = 5, selectedPage = 2.ri),
    modifier = RemoteModifier.size(180.rdp),
  )
}

@CatalogRemoteModes
@Composable
fun VerticalPageIndicatorRemote() = RemoteSticker {
  RemoteVerticalPageIndicator(
    state = rememberRemotePageIndicatorState(pageCount = 8, selectedPage = 4.ri),
    modifier = RemoteModifier.size(180.rdp),
  )
}

@CatalogRemoteModes
@Composable
fun InteractivePageIndicatorRemote() = RemoteSticker {
  val selected = rememberMutableRemoteInt(0)
  val animatedPage = animateRemoteFloat(selected.toRemoteFloat(), duration = 0.6f)
  val state =
    object : RemotePageIndicatorState {
      override val pageCount: Int = 4
      override val selectedPage: RemoteInt = 0.ri
      override val pageOffset: RemoteFloat = animatedPage
    }
  RemoteBox(
    modifier = RemoteModifier.size(180.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteHorizontalPageIndicator(state = state)
    RemoteTextButton(
      onClick = valueChange(selected, (1.ri - selected).createReference()),
      content = { RemoteText("Next".rs) },
    )
  }
}

@CatalogRemoteModes
@AnimatedPreview(
  durationMs = 1000,
  frameIntervalMs = 33,
  showCurves = false,
  caption = "The selected-page worm expands, travels, and contracts between adjacent pages.",
)
@Composable
fun PageIndicatorMotionRemote() = RemoteSticker {
  val state =
    object : RemotePageIndicatorState {
      override val pageCount: Int = 4
      override val selectedPage: RemoteInt = 0.ri
      override val pageOffset: RemoteFloat =
        animateRemoteFloat(1f.rf, duration = 0.8f, initialValue = 0f)
    }
  RemoteHorizontalPageIndicator(
    state = state,
    modifier = RemoteModifier.size(180.rdp),
  )
}

@Composable
@RemoteComposable
fun TimeTextRemoteSample() {
  RemoteTimeText(modifier = RemoteModifier.size(180.rdp), time = "10:10".rs)
}

@Composable
@RemoteComposable
fun TimeTextWithContextRemoteSample() {
  RemoteTimeText(
    modifier = RemoteModifier.size(180.rdp),
    time = "10:10".rs,
    leadingText = "Run".rs,
    trailingText = "72 bpm".rs,
  )
}
