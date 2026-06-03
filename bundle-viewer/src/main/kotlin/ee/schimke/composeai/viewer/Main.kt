package ee.schimke.composeai.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Compose Preview Viewer — a one-window desktop app that opens a `compose-preview` bundle (PNG+ZIP
 * polyglot) and renders its `@Preview` composable LIVE inside the window. State, recomposition,
 * animations all tick as they would in the source app; the viewer is a thin Window shell around a
 * reflective composable invocation against the bundle's classloader.
 *
 * # Modes
 *
 * - **CLI arg**: `compose-preview-viewer foo.png` opens the bundle on startup. Useful for
 *   double-click associations and shell scripting.
 * - **Drag-and-drop**: launching with no args opens an empty drop-target window. Dropping a `.png`
 *   polyglot loads it, swapping the live preview and resizing the window to the preview's declared
 *   size.
 *
 * # Window sizing
 *
 * On bundle load, [previewSize] computes a `DpSize` from the preview's `params.widthDp` /
 * `params.heightDp` (defaulting to 400×800 dp wrap-content for previews that didn't pin a device).
 * The window state is mutated so the OS chrome includes the preview at its declared dimensions —
 * same shape `@Preview` viewers in Android Studio show.
 */
fun main(args: Array<String>) {
  // The bundle arg may be a local path or an http(s)/file URL — resolve (download) it to a local
  // file before loading. Null when no arg, an unreadable path, or a failed download (the window
  // still opens so the user can drop a bundle).
  val initial = args.firstOrNull()?.let { resolveBundleArg(it) }
  application {
    var loadedBundle by remember { mutableStateOf<LoadedBundle?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val windowState = rememberWindowState(width = INITIAL_WIDTH, height = INITIAL_HEIGHT)

    // Load the file the CLI handed us on first composition. Errors land in [loadError]; the
    // window opens regardless so the user can drop a fresh bundle.
    LaunchedEffect(Unit) {
      if (initial != null) {
        try {
          loadedBundle = loadBundle(initial)
          loadError = null
        } catch (e: Throwable) {
          loadError = "Could not load $initial: ${e.message}"
        }
      }
    }

    // Resize the window to the preview's declared size whenever a fresh bundle lands. We
    // observe [loadedBundle]'s cover here rather than at load-site so a drop-while-running
    // swap repaints the window without an explicit recomputation.
    LaunchedEffect(loadedBundle) {
      val cover = loadedBundle?.coverPreview ?: return@LaunchedEffect
      val size = previewSize(cover)
      windowState.size = size
    }

    val title =
      when {
        loadError != null -> "compose-preview viewer — error"
        loadedBundle != null -> "compose-preview — ${loadedBundle?.coverPreview?.info?.id}"
        else -> "compose-preview viewer — drop a bundle .png to begin"
      }

    Window(onCloseRequest = ::exitApplication, state = windowState, title = title) {
      // Wire a Swing/AWT DropTarget once — Compose Desktop's `Modifier.dragAndDropTarget` is
      // newer but the AWT API is universally available and works around the entire window
      // rather than a single composable. Cleared on dispose.
      val composeWindow = window
      DisposableEffect(composeWindow) {
        val target =
          DropTarget(
            composeWindow,
            object : DropTargetAdapter() {
              override fun drop(event: DropTargetDropEvent) {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val files =
                  try {
                    @Suppress("UNCHECKED_CAST")
                    event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                  } catch (e: Exception) {
                    event.dropComplete(false)
                    loadError = "drop: ${e.message}"
                    return
                  }
                event.dropComplete(true)
                val file = files.firstOrNull() ?: return
                // Synchronous on the AWT event thread — loads are serialised, so a second drop
                // can't race an in-flight one.
                try {
                  val next = loadBundle(file.path.toPath())
                  loadedBundle?.close()
                  loadedBundle = next
                  loadError = null
                } catch (e: Throwable) {
                  loadError = "Could not load ${file.path}: ${e.message}"
                }
              }
            },
          )
        composeWindow.dropTarget = target
        onDispose { composeWindow.dropTarget = null }
      }

      Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ViewerContent(loadedBundle = loadedBundle, loadError = loadError)
      }
    }
  }
}

@Composable
private fun ViewerContent(loadedBundle: LoadedBundle?, loadError: String?) {
  when {
    loadError != null -> ErrorPanel(loadError)
    loadedBundle == null -> EmptyDropPanel()
    else -> {
      val preview = loadedBundle.coverPreview
      val method = preview.composableMethod
      if (method == null) {
        ErrorPanel(preview.errorMessage ?: "Could not resolve preview.")
      } else {
        LivePreview(method, preview.info)
      }
    }
  }
}

@Composable
private fun LivePreview(method: ComposableMethod, info: PreviewInfo) {
  val background =
    if (info.params.showBackground && info.params.backgroundColor != 0L)
      Color(info.params.backgroundColor.toULong())
    else MaterialTheme.colorScheme.surface
  Box(
    modifier = Modifier.fillMaxSize().background(background),
    contentAlignment = Alignment.Center,
  ) {
    InvokeComposable(method)
  }
}

/**
 * Reflective invocation site — the method gets called from inside an active composition so the
 * bundle's `@Composable` function gets the composer state Compose expects. Loaded via the bundle's
 * child classloader; the parent has the viewer's Compose runtime, so the composer symbol is shared
 * and the call is a normal composable invocation as far as the runtime is concerned.
 */
@Composable
private fun InvokeComposable(method: ComposableMethod) {
  method.invoke(currentComposer, null)
}

@Composable
private fun EmptyDropPanel() {
  Column(
    modifier = Modifier.fillMaxSize().padding(PaddingValues(32.dp)),
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "Drop a `compose-preview` bundle here",
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Or launch with `compose-preview-viewer <bundle.png>`.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ErrorPanel(message: String) {
  Column(
    modifier = Modifier.fillMaxSize().padding(PaddingValues(32.dp)),
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "Cannot show preview",
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = message,
      modifier = Modifier.fillMaxWidth(),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * Resolve the [DpSize] the window should adopt for [preview]. Pinned dimensions from the
 * `@Preview(widthDp = .., heightDp = ..)` annotation win; absent ones fall back to the same
 * wrap-content sandbox (400×800 dp) the renderer uses. The 200-dp floor avoids unusable tiny
 * windows for previews that pin extreme dimensions.
 */
private fun previewSize(preview: LoadedPreview): DpSize {
  val widthDp = (preview.info.params.widthDp ?: 400).coerceAtLeast(200)
  val heightDp = (preview.info.params.heightDp ?: 800).coerceAtLeast(200)
  return DpSize(widthDp.dp, heightDp.dp)
}

private val INITIAL_WIDTH = 480.dp
private val INITIAL_HEIGHT = 720.dp

/**
 * Resolve a bundle CLI arg — a local path or an http(s)/file URL — to a readable local file, or
 * null when it can't be opened (missing path, failed download). URLs are downloaded to a temp file
 * (delete-on-exit). Kept self-contained here rather than depending on `:cli`'s BundleSource so the
 * viewer's module graph stays minimal (same convention as the duplicated `extractZipBytes`).
 */
private fun resolveBundleArg(arg: String): Path? {
  val scheme = arg.substringBefore(':', missingDelimiterValue = "").lowercase()
  val isUrl = scheme == "http" || scheme == "https" || scheme == "file"
  fun Path.takeIfFile(): Path? = takeIf {
    SystemFileSystem.metadataOrNull(it)?.isRegularFile == true
  }
  if (!isUrl) return arg.toPath().takeIfFile()
  return try {
    val uri = java.net.URI(arg)
    // file: URIs go via java.io.File(URI) — the only correct cross-platform URI→path decode — then
    // bridge to an Okio Path.
    if (uri.scheme.equals("file", ignoreCase = true)) return File(uri).path.toPath().takeIfFile()
    // Ktor client over the OkHttp engine; stream the body to disk on a 2xx. runBlocking is fine at
    // this one-shot startup call (main(), before Compose starts).
    val temp = TemporaryDirectory / "compose-preview-bundle-${UUID.randomUUID()}.png"
    temp.toFile().deleteOnExit()
    runBlocking {
      val ok =
        HttpClient(OkHttp).use { client ->
          client.prepareGet(uri.toString()).execute { response ->
            if (response.status.isSuccess()) {
              SystemFileSystem.sink(temp).buffer().use { sink ->
                response.bodyAsChannel().copyTo(sink.outputStream())
              }
              true
            } else {
              false
            }
          }
        }
      if (ok && (SystemFileSystem.metadataOrNull(temp)?.size ?: 0L) > 0L) temp
      else {
        SystemFileSystem.delete(temp, mustExist = false)
        null
      }
    }
  } catch (_: Exception) {
    null
  }
}
