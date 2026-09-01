@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.servewasm

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val AppColors =
  darkColorScheme(
    primary = Color(0xFF91B9FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF164780),
    secondary = Color(0xFFC3C6D0),
    background = Color(0xFF111318),
    surface = Color(0xFF191C22),
    surfaceVariant = Color(0xFF252930),
    outline = Color(0xFF434850),
  )

@Composable
fun PreviewServerApp(client: BrowserPreviewClient) {
  MaterialTheme(colorScheme = AppColors) {
    Surface(Modifier.fillMaxSize()) {
      var catalog by remember { mutableStateOf<Catalog?>(null) }
      var loadError by remember { mutableStateOf<String?>(null) }
      var location by remember { mutableStateOf(client.initialLocation()) }
      DisposableEffect(client) {
        val stop = client.observeHistory { location = it }
        onDispose(stop)
      }
      LaunchedEffect(Unit) {
        try {
          catalog = client.catalog()
          if (
            location.previewId != null &&
              catalog?.previews?.none { it.id == location.previewId } == true
          ) {
            location = AppLocation()
            client.replaceLocation(location)
          }
        } catch (error: Throwable) {
          loadError = error.message ?: "Could not load the preview catalog"
        }
      }

      val loaded = catalog
      when {
        loadError != null -> ErrorScreen(loadError!!)
        loaded == null -> LoadingScreen()
        else -> {
          val selected = loaded.previews.firstOrNull { it.id == location.previewId }
          LaunchedEffect(loaded.module, selected?.id, location.composing) {
            client.setDocumentTitle(
              when {
                location.composing -> "UI Composer · ${loaded.module}"
                selected != null -> "${selected.label} · ${loaded.module}"
                else -> "${loaded.module} · Compose Preview"
              }
            )
          }
          BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp
            Column(Modifier.fillMaxSize()) {
              AppHeader(
                catalog = loaded,
                selected = selected,
                composing = location.composing,
                compact = compact,
                onCatalog = {
                  location = AppLocation()
                  client.pushLocation(location)
                },
                onCompose = {
                  location = AppLocation(composing = true)
                  client.pushLocation(location)
                },
              )
              if (location.composing) {
                UiComposer(compact)
              } else if (selected != null) {
                PreviewDetail(
                  preview = selected,
                  client = client,
                  compact = compact,
                  initiallyLive = location.live,
                  initialUiMode = location.uiMode,
                  initiallyTransparent = location.transparent,
                  initialFontScale = location.fontScale,
                  initialLocale = location.localeTag,
                  onLivePermalink = { enabled ->
                    location = location.copy(live = enabled)
                    client.pushLocation(location)
                  },
                  onUiModePermalink = { uiMode ->
                    location = location.copy(uiMode = uiMode)
                    client.pushLocation(location)
                  },
                  onTransparentPermalink = { transparent ->
                    location = location.copy(transparent = transparent)
                    client.pushLocation(location)
                  },
                  onFontScalePermalink = { fontScale ->
                    location = location.copy(fontScale = fontScale)
                    client.replaceLocation(location)
                  },
                  onLocalePermalink = { locale ->
                    location = location.copy(localeTag = locale)
                    client.replaceLocation(location)
                  },
                  onBack = {
                    location = AppLocation()
                    client.pushLocation(location)
                  },
                )
              } else {
                CatalogBrowser(
                  catalog = loaded,
                  filter = location.filter,
                  compact = compact,
                  client = client,
                  onFilter = {
                    location = location.copy(filter = it)
                    client.replaceLocation(location)
                  },
                  onSelect = {
                    location = AppLocation(previewId = it.id)
                    client.pushLocation(location)
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AppHeader(
  catalog: Catalog,
  selected: PreviewSummary?,
  composing: Boolean,
  compact: Boolean,
  onCatalog: () -> Unit,
  onCompose: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().height(68.dp).background(Color(0xFF15181D)).padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
    }
    Column(Modifier.weight(1f)) {
      Text(
        when {
          composing -> "UI Composer"
          selected == null -> catalog.module
          else -> selected.label
        },
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        when {
          composing -> "Drag, arrange, and interact with native CMP components"
          selected == null -> "Wasm preview browser · ${catalog.previews.size} previews"
          else -> "${catalog.module} · ${selected.id}"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = 1,
      )
    }
    if (!compact && catalog.trust != null) StatusPill(catalog.trust, Color(0xFF65D6A3))
    StatusPill("Wasm prototype", MaterialTheme.colorScheme.primary)
    when {
      composing -> OutlinedButton(onClick = onCatalog) { Text("Catalog") }
      selected != null -> {
        if (!compact) OutlinedButton(onClick = onCompose) { Text("Compose UI") }
        OutlinedButton(onClick = onCatalog) { Text("All previews") }
      }
      else -> Button(onClick = onCompose) { Text("Compose UI") }
    }
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
}

@Composable
private fun CatalogBrowser(
  catalog: Catalog,
  filter: String,
  compact: Boolean,
  client: BrowserPreviewClient,
  onFilter: (String) -> Unit,
  onSelect: (PreviewSummary) -> Unit,
) {
  val visible =
    remember(catalog.previews, filter) {
      val needle = filter.trim().lowercase()
      if (needle.isEmpty()) catalog.previews
      else catalog.previews.filter { needle in it.label.lowercase() || needle in it.id.lowercase() }
    }
  Row(Modifier.fillMaxSize()) {
    if (!compact) {
      Column(
        Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF15181D)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text("Catalog", style = MaterialTheme.typography.titleSmall)
        Text(
          "A separate client over the server's stable API and stream protocol.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.secondary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("${visible.size} shown", style = MaterialTheme.typography.labelLarge)
        if (catalog.degradations.isNotEmpty()) {
          Text(
            "Snapshot only",
            color = Color(0xFFFFCA75),
            style = MaterialTheme.typography.labelLarge,
          )
          catalog.degradations.forEach {
            Text(
              it,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        } else {
          Text(
            "Live lane available",
            color = Color(0xFF65D6A3),
            style = MaterialTheme.typography.labelLarge,
          )
        }
        Spacer(Modifier.weight(1f))
        Text(
          "Open any card to switch between its baked and live render.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      HorizontalDivider(
        Modifier.fillMaxHeight().width(1.dp),
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Column(Modifier.fillMaxSize().padding(if (compact) 14.dp else 24.dp)) {
      OutlinedTextField(
        value = filter,
        onValueChange = onFilter,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Filter previews") },
        placeholder = { Text("Button, dark, login…") },
        keyboardActions = KeyboardActions(onSearch = {}),
      )
      Spacer(Modifier.height(18.dp))
      if (visible.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No previews match “$filter”", color = MaterialTheme.colorScheme.secondary)
        }
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(if (compact) 170.dp else 220.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          items(visible, key = { it.id }) { preview ->
            PreviewCard(preview, client, onClick = { onSelect(preview) })
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewCard(
  preview: PreviewSummary,
  client: BrowserPreviewClient,
  onClick: () -> Unit,
) {
  val native = preview.nativeTarget
  var bitmap by remember(preview.id) { mutableStateOf<ImageBitmap?>(null) }
  var failed by remember(preview.id) { mutableStateOf(false) }
  LaunchedEffect(preview.id) {
    if (preview.nativeTarget != null) return@LaunchedEffect
    try {
      bitmap = client.snapshot(preview.id, emptyMap())
    } catch (_: Throwable) {
      failed = true
    }
  }
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(1.35f).checkerboard(),
      contentAlignment = Alignment.Center,
    ) {
      val image = bitmap
      if (native != null) {
        NativeCatalogPreview(native, modifier = Modifier.fillMaxSize())
      } else if (image != null) {
        Image(
          image,
          preview.label,
          Modifier.fillMaxSize().padding(12.dp),
          contentScale = ContentScale.Fit,
        )
      } else if (failed) {
        Text("Preview unavailable", style = MaterialTheme.typography.labelSmall)
      } else {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
      }
      if (preview.liveOnly) {
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          StatusPill("Live only", Color(0xFFFFCA75))
        }
      } else if (native != null) {
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          StatusPill("Native CMP", Color(0xFF65D6A3))
        }
        // Catalog components are intentionally interactive, but a card is navigation rather than
        // a mini editor. Keep its whole stage clickable even when the child is a Switch/Slider.
        Box(Modifier.fillMaxSize().clickable(onClick = onClick))
      }
    }
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        preview.label,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        preview.id,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (preview.modes.isNotEmpty()) {
        Text(
          if (native != null) "native cmp · snapshot fallback"
          else preview.modes.joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun PreviewDetail(
  preview: PreviewSummary,
  client: BrowserPreviewClient,
  compact: Boolean,
  initiallyLive: Boolean?,
  initialUiMode: String?,
  initiallyTransparent: Boolean,
  initialFontScale: Float?,
  initialLocale: String,
  onLivePermalink: (Boolean) -> Unit,
  onUiModePermalink: (String) -> Unit,
  onTransparentPermalink: (Boolean) -> Unit,
  onFontScalePermalink: (Float) -> Unit,
  onLocalePermalink: (String) -> Unit,
  onBack: () -> Unit,
) {
  val nativeTarget = preview.nativeTarget
  var live by
    remember(preview.id, initiallyLive) { mutableStateOf(initiallyLive ?: (nativeTarget != null)) }
  var dark by
    remember(preview.id, initialUiMode) {
      mutableStateOf(initialUiMode?.let { it == "dark" } ?: (nativeTarget?.dark ?: false))
    }
  var transparent by
    remember(preview.id, initiallyTransparent) { mutableStateOf(initiallyTransparent) }
  var fontScale by
    remember(preview.id, initialFontScale) {
      mutableStateOf(initialFontScale ?: (nativeTarget?.fontScale ?: 1f))
    }
  var locale by remember(preview.id, initialLocale) { mutableStateOf(initialLocale) }
  var bitmap by remember(preview.id) { mutableStateOf<ImageBitmap?>(null) }
  var frameSize by remember(preview.id) { mutableStateOf(IntSize.Zero) }
  var stageSize by remember { mutableStateOf(IntSize.Zero) }
  var status by
    remember(preview.id) {
      mutableStateOf(if (nativeTarget != null) "Native CMP · in browser" else "Snapshot")
    }
  var error by remember(preview.id) { mutableStateOf<String?>(null) }
  val overrides = buildMap {
    put("uiMode", if (dark) "dark" else "light")
    if (transparent) put("background", "off")
    if (fontScale != 1f) put("fontScale", ((fontScale * 100).roundToInt() / 100f).toString())
    if (locale.isNotBlank()) put("localeTag", locale.trim())
  }
  val nativeActive = live && nativeTarget != null
  val serverLive = live && nativeTarget == null

  LaunchedEffect(preview.id, nativeActive, serverLive, overrides) {
    if (nativeActive) {
      status = "Native CMP · in browser"
      error = null
    } else if (serverLive) {
      client.sendOverrides(overrides)
    } else {
      status = "Rendering snapshot…"
      error = null
      try {
        val fresh = client.snapshot(preview.id, overrides)
        bitmap = fresh
        frameSize = IntSize(fresh.width, fresh.height)
        status = "Snapshot"
      } catch (cause: Throwable) {
        error = cause.message ?: "Snapshot render failed"
        status = "Unavailable"
      }
    }
  }

  LaunchedEffect(preview.id, serverLive) {
    if (!serverLive) return@LaunchedEffect
    status = "Connecting…"
    error = null
    client.openStream(preview.id, overrides)
    try {
      while (live) {
        when (val event = client.nextStreamEvent()) {
          StreamEvent.Opened -> {
            status = "Live"
            client.sendOverrides(overrides)
          }
          is StreamEvent.Frame -> {
            bitmap = event.value.bitmap
            frameSize = IntSize(event.value.widthPx, event.value.heightPx)
            status = "Live · frame ${event.value.sequence}"
          }
          is StreamEvent.Error -> error = event.message
          is StreamEvent.Closed -> {
            live = false
            status = "Disconnected"
            error = closeMessage(event.code, event.reason)
          }
        }
      }
    } catch (cause: Throwable) {
      live = false
      status = "Disconnected"
      error = cause.message ?: "The live frame could not be decoded"
    } finally {
      client.closeStream()
    }
  }
  DisposableEffect(preview.id) { onDispose { client.closeStream() } }

  val controls: @Composable () -> Unit = {
    ControlsPanel(
      preview = preview,
      live = live,
      status = status,
      native = nativeTarget != null,
      dark = dark,
      transparent = transparent,
      fontScale = fontScale,
      locale = locale,
      client = client,
      onLive = {
        live = it
        onLivePermalink(it)
      },
      onDark = {
        dark = it
        onUiModePermalink(if (it) "dark" else "light")
      },
      onTransparent = {
        transparent = it
        onTransparentPermalink(it)
      },
      onFontScale = {
        fontScale = it
        onFontScalePermalink(it)
      },
      onLocale = {
        locale = it
        onLocalePermalink(it)
      },
      onBack = onBack,
    )
  }

  if (compact) {
    LazyColumn(
      Modifier.fillMaxSize().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item { controls() }
      item {
        PreviewStage(
          bitmap,
          frameSize,
          stageSize,
          transparent,
          live,
          nativeActive,
          status,
          error,
          onSize = { stageSize = it },
          onTap = { x, y -> client.sendTap(x, y) },
          nativeContent =
            nativeTarget
              ?.takeIf { nativeActive }
              ?.let { target ->
                {
                  NativeCatalogPreview(
                    target = target,
                    dark = dark,
                    fontScale = fontScale,
                    locale = locale,
                    transparent = transparent,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              },
        )
      }
    }
  } else {
    Row(Modifier.fillMaxSize()) {
      Box(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
        PreviewStage(
          bitmap,
          frameSize,
          stageSize,
          transparent,
          live,
          nativeActive,
          status,
          error,
          onSize = { stageSize = it },
          onTap = { x, y -> client.sendTap(x, y) },
          nativeContent =
            nativeTarget
              ?.takeIf { nativeActive }
              ?.let { target ->
                {
                  NativeCatalogPreview(
                    target = target,
                    dark = dark,
                    fontScale = fontScale,
                    locale = locale,
                    transparent = transparent,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              },
        )
      }
      HorizontalDivider(
        Modifier.fillMaxHeight().width(1.dp),
        color = MaterialTheme.colorScheme.outline,
      )
      Box(Modifier.width(330.dp).fillMaxHeight().background(Color(0xFF15181D)).padding(22.dp)) {
        controls()
      }
    }
  }
}

@Composable
private fun ControlsPanel(
  preview: PreviewSummary,
  live: Boolean,
  status: String,
  native: Boolean,
  dark: Boolean,
  transparent: Boolean,
  fontScale: Float,
  locale: String,
  client: BrowserPreviewClient,
  onLive: (Boolean) -> Unit,
  onDark: (Boolean) -> Unit,
  onTransparent: (Boolean) -> Unit,
  onFontScale: (Float) -> Unit,
  onLocale: (String) -> Unit,
  onBack: () -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          if (native) "Native CMP" else "Server-rendered preview",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          status,
          style = MaterialTheme.typography.labelSmall,
          color = if (live) Color(0xFF65D6A3) else MaterialTheme.colorScheme.secondary,
        )
      }
      Switch(checked = live, onCheckedChange = onLive)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text("Appearance", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = !dark, onClick = { onDark(false) }, label = { Text("Light") })
      FilterChip(selected = dark, onClick = { onDark(true) }, label = { Text("Dark") })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Clear background", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = transparent, onCheckedChange = onTransparent)
    }
    Column {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Font scale", style = MaterialTheme.typography.bodyMedium)
        Text("${(fontScale * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
      }
      Slider(value = fontScale, onValueChange = onFontScale, valueRange = .5f..2f)
    }
    OutlinedTextField(
      value = locale,
      onValueChange = onLocale,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Locale") },
      placeholder = { Text("en-GB, ar, ja…") },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text(
      if (native) "Native CMP · snapshot fallback available"
      else
        "Not bundled in this Wasm frontend · " +
          preview.modes.joinToString(" · ").ifEmpty { "default render mode" },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.secondary,
    )
    Button(
      onClick = { uriHandler.openUri(client.legacyViewerUrl(preview.id)) },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Open advanced viewer")
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to catalog") }
  }
}

@Composable
private fun PreviewStage(
  bitmap: ImageBitmap?,
  frameSize: IntSize,
  stageSize: IntSize,
  transparent: Boolean,
  live: Boolean,
  native: Boolean,
  status: String,
  error: String?,
  onSize: (IntSize) -> Unit,
  onTap: (Int, Int) -> Unit,
  nativeContent: (@Composable () -> Unit)? = null,
) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      StatusPill(
        if (native) "NATIVE" else if (live) "SERVER LIVE" else "SERVER SNAPSHOT",
        if (live) Color(0xFF65D6A3) else MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(10.dp))
      Text(
        status,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
      )
      if (native) {
        Spacer(Modifier.weight(1f))
        Text(
          "Runs in this browser",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      } else if (live) {
        Spacer(Modifier.weight(1f))
        Text(
          "Click the preview to send input",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }
    }
    Card(
      Modifier.fillMaxWidth().weight(1f),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E12)),
    ) {
      Box(
        Modifier.fillMaxSize()
          .then(
            if (transparent) Modifier.checkerboard() else Modifier.background(Color(0xFFF8F8FA))
          )
          .onSizeChanged(onSize)
          .then(
            if (nativeContent != null) Modifier
            else
              Modifier.pointerInput(live, frameSize, stageSize) {
                detectTapGestures { point ->
                  if (live && frameSize.width > 0 && frameSize.height > 0) {
                    mapToFrame(point.x, point.y, stageSize, frameSize)?.let {
                      onTap(it.first, it.second)
                    }
                  }
                }
              }
          ),
        contentAlignment = Alignment.Center,
      ) {
        val image = bitmap
        if (nativeContent != null) nativeContent()
        else if (image == null) CircularProgressIndicator()
        else
          Image(
            image,
            "Rendered preview",
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
          )
        if (error != null) {
          Surface(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            color = Color(0xFF51242A),
            shape = RoundedCornerShape(10.dp),
          ) {
            Text(
              error,
              Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              color = Color(0xFFFFB3B8),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }
  }
}

internal fun mapToFrame(
  x: Float,
  y: Float,
  stage: IntSize,
  frame: IntSize,
): Pair<Int, Int>? {
  if (stage.width <= 0 || stage.height <= 0 || frame.width <= 0 || frame.height <= 0) return null
  val scale = minOf(stage.width.toFloat() / frame.width, stage.height.toFloat() / frame.height)
  val shownWidth = frame.width * scale
  val shownHeight = frame.height * scale
  val left = (stage.width - shownWidth) / 2f
  val top = (stage.height - shownHeight) / 2f
  if (x < left || y < top || x >= left + shownWidth || y >= top + shownHeight) return null
  return (((x - left) / scale).toInt().coerceIn(0, frame.width - 1)) to
    (((y - top) / scale).toInt().coerceIn(0, frame.height - 1))
}

private fun closeMessage(code: Int, reason: String): String =
  when (code) {
    1008 -> "Live preview is unauthorized."
    1013 -> "Live preview is at capacity — try again shortly."
    else -> reason.takeIf { it.isNotBlank() } ?: "Live preview disconnected."
  }

@Composable
internal fun StatusPill(label: String, color: Color) {
  Surface(
    color = color.copy(alpha = .14f),
    shape = RoundedCornerShape(50),
    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .5f)),
  ) {
    Text(
      label,
      Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      color = color,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun LoadingScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      CircularProgressIndicator()
      Text("Loading preview catalog…", color = MaterialTheme.colorScheme.secondary)
    }
  }
}

@Composable
private fun ErrorScreen(message: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF51242A))) {
      Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Could not connect to the preview server",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(message, color = Color(0xFFFFB3B8))
        Text(
          "Check the token/session parameters in this page's URL.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun Modifier.checkerboard(): Modifier = drawBehind {
  val cell = 12.dp.toPx()
  val pale = Color(0xFFF5F6F8)
  val shade = Color(0xFFE5E7EB)
  var row = 0
  var y = 0f
  while (y < size.height) {
    var column = 0
    var x = 0f
    while (x < size.width) {
      drawRect(
        color = if ((row + column) % 2 == 0) pale else shade,
        topLeft = Offset(x, y),
        size = Size(cell, cell),
      )
      x += cell
      column++
    }
    y += cell
    row++
  }
}
