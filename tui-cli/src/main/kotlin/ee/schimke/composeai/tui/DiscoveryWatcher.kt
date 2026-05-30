package ee.schimke.composeai.tui

import ee.schimke.composeai.cli.PreviewModule
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

/**
 * Watches each module's `build/compose-previews/previews.json` and fires a single coalesced signal
 * whenever the discovered preview set changes — a `@Preview` was added, removed, or renamed and the
 * Gradle discovery pass (or the daemon's file watcher) rewrote the manifest.
 *
 * ## Why a separate watcher from [FileWatcher]
 *
 * [FileWatcher] watches the module's *source* tree to drive live re-renders, and only runs while
 * live mode is on. Discovery refresh has to work regardless of live mode — you can add a preview
 * with the TUI sitting idle — and it cares about exactly one generated file per module rather than
 * the whole source tree. Keeping it separate keeps both watchers small and single-purpose.
 *
 * ## Late directory creation
 *
 * `build/compose-previews/` (and even `build/`) may not exist yet when the TUI launches against a
 * module that hasn't rendered. A [WatchService] can only watch directories that exist, so we
 * register the deepest ancestor that *does* exist and walk down as the missing links are created:
 * watching the project dir catches `build/` appearing, watching `build/` catches
 * `compose-previews/` appearing, and watching `compose-previews/` catches `previews.json` itself.
 * This also survives a `gradle clean` that deletes `build/` out from under us and a later rebuild
 * that recreates it.
 */
class DiscoveryWatcher(
  private val modules: List<PreviewModule>,
  private val debounceMillis: Long = 250,
) : AutoCloseable {
  private val watchService: WatchService = FileSystems.getDefault().newWatchService()
  private val keysToDir = mutableMapOf<WatchKey, Path>()

  @Volatile private var stopped = false
  private var thread: Thread? = null
  private var lastFire = 0L

  /** Directory names on the path from a project dir down to the manifest's parent. */
  private val chainDirs = setOf("build", "compose-previews")

  fun start(onChange: () -> Unit) {
    for (module in modules) {
      var dir = module.projectDir
      // Register the project dir and each existing link of build/compose-previews so we pick up
      // the manifest however deep the tree currently goes.
      register(dir.toPath())
      dir = dir.resolve("build")
      register(dir.toPath())
      dir = dir.resolve("compose-previews")
      register(dir.toPath())
    }
    if (keysToDir.isEmpty()) return // No watchable ancestor for any module — nothing to do.

    thread =
      thread(name = "compose-preview-tui-discovery", isDaemon = true) {
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
          var relevant = false
          for (event in key.pollEvents()) {
            val ctx = event.context() as? Path ?: continue
            val name = ctx.fileName?.toString() ?: continue
            val absolute = dir.resolve(ctx)
            // A newly-created link in the build/compose-previews chain — register it so the next
            // level down (ultimately previews.json) becomes observable.
            if (event.kind() == ENTRY_CREATE && name in chainDirs && Files.isDirectory(absolute)) {
              register(absolute)
              relevant = true
            }
            if (name == "previews.json") relevant = true
          }
          if (relevant) {
            val now = System.currentTimeMillis()
            if (now - lastFire >= debounceMillis) {
              lastFire = now
              try {
                onChange()
              } catch (_: Throwable) {
                // Isolate listener faults from the watcher loop, as FileWatcher does.
              }
            }
          }
          if (!key.reset()) {
            keysToDir.remove(key)
            // Don't break when the map empties: a `gradle clean` can invalidate the build/
            // and compose-previews keys, but the project-dir key survives and will re-register
            // them when the rebuild recreates the chain.
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

  private fun register(dir: Path) {
    if (!Files.isDirectory(dir)) return
    if (keysToDir.values.any { it == dir }) return // already watching
    val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
    keysToDir[key] = dir
  }
}
