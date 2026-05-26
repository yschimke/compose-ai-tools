package ee.schimke.composeai.tui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Spacer
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.tui.LiveSession
import ee.schimke.composeai.tui.PreviewIndex
import ee.schimke.composeai.tui.TuiArgs
import ee.schimke.composeai.tui.terminal.TerminalSize
import kotlinx.coroutines.launch

/**
 * Top-level TUI composition. Holds the [PreviewIndex] (filtered, navigable rows) and the
 * [LiveSession] controller, and decides layout based on terminal width:
 *
 * * `cols >= 120` — three columns: preview list (left), image (centre), data panel (right).
 * * `cols < 120` — single column, with a tab strip at the top to switch between list / image /
 *   data. Tab cycles forward, Shift+Tab cycles back.
 *
 * Mosaic 0.18 exposes terminal size through [LocalTerminalState] inside the composition. The
 * decision is recomputed on every recomposition so a SIGWINCH (which Mosaic surfaces by pushing a
 * new TerminalState value) flips the layout without restart.
 */
@Composable
fun App(modules: List<PreviewModule>, args: TuiArgs) {
  val initialSize = remember { TerminalSize.probe() }
  val terminalState = LocalTerminalState.current
  val cols = terminalState.size.columns.takeIf { it > 0 } ?: initialSize.cols
  val rows = terminalState.size.rows.takeIf { it > 0 } ?: initialSize.rows
  val isWide = cols >= 120

  val index =
    remember(modules) {
      PreviewIndex(
        initialRows = PreviewIndex.loadRows(modules),
        initialFilter = args.filter,
        initialExactId = args.exactId,
      )
    }

  val scope = rememberCoroutineScope()
  val liveSession = remember { LiveSession(scope) }
  val liveState by liveSession.state.collectAsState()
  var liveOn by remember { mutableStateOf(args.liveOnStart) }
  var focusedPane by remember { mutableIntStateOf(0) } // 0=list, 1=preview, 2=data
  var filterEditing by remember { mutableStateOf(false) }
  var filterDraft by remember { mutableStateOf(args.filter ?: "") }
  var exitRequested by remember { mutableStateOf(false) }
  var cursorTick by remember { mutableIntStateOf(0) }

  // Sticky live mode: when the user toggles live on, this effect opens the session against the
  // current preview's module. Toggling off disables. Navigation between previews is handled by
  // the secondary effect below — this one only fires when `liveOn` actually flips.
  LaunchedEffect(liveOn) {
    if (liveOn) {
      val module = index.current()?.module
      if (module != null) liveSession.enable(module, args.extensions)
    } else {
      liveSession.disable()
    }
  }

  // Re-target the session when the cursor moves to a preview in a different module — sticky
  // live mode means the user gets new module's previews subscribed without manually toggling.
  LaunchedEffect(cursorTick, liveState.status, liveOn) {
    if (liveOn) {
      val current = index.current()
      if (current != null) {
        val running = liveState.modulePath
        if (running != current.module.gradlePath) {
          liveSession.enable(current.module, args.extensions)
        } else if (liveState.status == LiveSession.Status.READY) {
          liveSession.setVisible(current.id)
        }
      }
    }
  }

  if (exitRequested) {
    LaunchedEffect(Unit) {
      // We disabled the session up in the q-handler. After this composable returns nothing
      // the Mosaic runtime keeps the foreground alive — call exitProcess so the launcher
      // returns control to the parent shell.
      kotlin.system.exitProcess(0)
    }
    return
  }

  Column(
    modifier =
      Modifier.onKeyEvent { event ->
        if (filterEditing) {
          handleFilterEdit(
            event = event,
            current = filterDraft,
            onUpdate = { filterDraft = it },
            onCommit = {
              index.setFilter(filterDraft.ifEmpty { null })
              filterEditing = false
              cursorTick++
            },
            onCancel = {
              filterEditing = false
              filterDraft = ""
            },
          )
          return@onKeyEvent true
        }
        when (event.key) {
          "q",
          "Q" -> {
            liveSession.disable()
            exitRequested = true
            true
          }
          "ArrowUp",
          "k" -> {
            index.moveCursor(-1)
            cursorTick++
            true
          }
          "ArrowDown",
          "j" -> {
            index.moveCursor(1)
            cursorTick++
            true
          }
          "ArrowRight",
          "l",
          "Tab" -> {
            focusedPane = (focusedPane + 1) % 3
            true
          }
          "ArrowLeft",
          "h" -> {
            focusedPane = (focusedPane + 2) % 3
            true
          }
          "/" -> {
            filterEditing = true
            filterDraft = ""
            true
          }
          "L" -> {
            liveOn = !liveOn
            true
          }
          "r" -> {
            val cur = index.current()
            if (cur != null) {
              scope.launch { liveSession.forceRender(cur.id) }
            }
            true
          }
          else -> false
        }
      }
  ) {
    StatusBar(
      isWide = isWide,
      cols = cols,
      rows = rows,
      liveOn = liveOn,
      liveStatus = liveState.status,
      liveError = liveState.lastError,
      filterEditing = filterEditing,
      filterDraft = filterDraft,
      currentFilter = if (args.exactId != null) "id=${args.exactId}" else args.filter,
      countShown = index.size(),
    )

    Spacer(Modifier.height(1))

    if (isWide) {
      WideLayout(
        index = index,
        liveSession = liveSession,
        focusedPane = focusedPane,
        cols = cols,
        rows = (rows - 4).coerceAtLeast(8),
        tick = liveState.tick + cursorTick.toLong(),
      )
    } else {
      NarrowLayout(
        index = index,
        liveSession = liveSession,
        focusedPane = focusedPane,
        cols = cols,
        rows = (rows - 5).coerceAtLeast(8),
        tick = liveState.tick + cursorTick.toLong(),
      )
    }
  }
}

/**
 * Handle one key event while the filter input is in editing mode. Pulled out of [App] so the key
 * dispatch table stays compact.
 */
private fun handleFilterEdit(
  event: KeyEvent,
  current: String,
  onUpdate: (String) -> Unit,
  onCommit: () -> Unit,
  onCancel: () -> Unit,
) {
  when (event.key) {
    "Enter" -> onCommit()
    "Escape" -> onCancel()
    "Backspace" -> onUpdate(current.dropLast(1))
    else -> {
      // Printable single-char keys arrive with `key` equal to the literal character. Anything
      // longer is a named key (F1, ArrowUp, …) and we ignore it inside the filter editor.
      val k = event.key
      if (k.length == 1 && !event.ctrl && !event.alt) {
        onUpdate(current + k)
      }
    }
  }
}
