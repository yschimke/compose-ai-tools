package ee.schimke.composeai.tui

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

/**
 * Minimal recursive filesystem watcher used by [LiveSession] to detect edits made from outside the
 * TUI's own process — vim in a sibling pane, VS Code, a CLI codemod, etc. — and forward them as
 * `fileChanged` notifications.
 *
 * ## Why a hand-rolled watcher rather than the daemon's
 *
 * The daemon has its own watcher for the modules it knows about, but the daemon only watches
 * compiled `.class` outputs (it cares about classpath dirtiness, not source edits). The TUI sits in
 * front of the daemon: when the user edits a `.kt` file in vim, the daemon won't notice until
 * Gradle rebuilds — which the TUI is now responsible for triggering. We watch the source tree,
 * debounce extension-matched events, and tell the daemon to invalidate; the daemon's subsequent
 * re-render emits the notification that drives the UI refresh.
 *
 * ## Scope
 *
 * Watches the module's `src/` directory recursively. Filters down to source-relevant extensions so
 * transient editor swap-files (`.kt.swp`, `.idea/`, `__pycache__/`) don't trigger spurious
 * re-renders. Debounces same-path events within [debounceMillis] — most editors
 * truncate-and-rewrite, producing a burst of MODIFY events for one logical save.
 */
class FileWatcher(
  private val root: File,
  private val debounceMillis: Long = 250,
  private val sourceExtensions: Set<String> =
    setOf("kt", "kts", "java", "xml", "json", "png", "svg"),
) : AutoCloseable {
  private val watchService: WatchService = FileSystems.getDefault().newWatchService()
  private val keysToDir = mutableMapOf<WatchKey, Path>()

  @Volatile private var stopped = false
  private var thread: Thread? = null
  private val lastEvent = mutableMapOf<Path, Long>()

  fun start(onChange: (Path) -> Unit) {
    if (!root.isDirectory) return // Nothing to watch — modules without `src/` (KMP shared
    // commonMain only?) won't trigger live updates. The user
    // can still force-render with `r`.
    registerRecursive(root.toPath())
    thread =
      thread(name = "compose-preview-tui-watcher", isDaemon = true) {
        while (!stopped) {
          val key =
            try {
              watchService.take()
            } catch (_: InterruptedException) {
              break
            } catch (_: java.nio.file.ClosedWatchServiceException) {
              break
            }
          val dir = keysToDir[key] ?: continue
          val events = key.pollEvents()
          for (event in events) {
            val ctx = event.context() as? Path ?: continue
            val absolute = dir.resolve(ctx)
            val kind = event.kind()
            if (kind == ENTRY_CREATE && Files.isDirectory(absolute)) {
              registerRecursive(absolute)
              continue
            }
            if (Files.isDirectory(absolute)) continue
            val ext = absolute.fileName?.toString()?.substringAfterLast('.', "") ?: continue
            if (ext !in sourceExtensions) continue
            val now = System.currentTimeMillis()
            val last = lastEvent[absolute] ?: 0L
            if (now - last < debounceMillis) continue
            lastEvent[absolute] = now
            try {
              onChange(absolute)
            } catch (_: Throwable) {
              // Listener faults are isolated from the watcher loop. The caller wraps daemon
              // calls in their own try/catch — anything that escapes is a programming error
              // we'd rather not let kill the watcher thread.
            }
          }
          if (!key.reset()) {
            keysToDir.remove(key)
            if (keysToDir.isEmpty()) break
          }
        }
      }
  }

  override fun close() {
    stopped = true
    runCatching { watchService.close() }
    thread?.interrupt()
    thread = null
  }

  private fun registerRecursive(dir: Path) {
    if (!Files.isDirectory(dir)) return
    Files.walk(dir).use { stream ->
      stream
        .filter(Files::isDirectory)
        .filter { !it.fileName.toString().startsWith(".") } // .git, .gradle, .idea
        .filter { !it.fileName.toString().endsWith("build") || it == dir }
        .forEach { path ->
          val key = path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY)
          keysToDir[key] = path
        }
    }
  }
}
