package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable, privacy-minimal engagement counters for the preview server.
 *
 * Only aggregate page-view counts are retained: no IP address, cookie, user agent, or referrer is
 * recorded. A null [file] keeps the in-memory behaviour useful for local `serve` sessions; deployed
 * servers point it at their persistent config volume.
 */
class ServeEngagementStore(
  private val file: File? = null,
  private val maxSystemEntries: Int = DEFAULT_MAX_SYSTEM_ENTRIES,
  private val maxPreviewEntries: Int = DEFAULT_MAX_PREVIEW_ENTRIES,
) {
  @Serializable
  private data class Counter(val views: Long = 0, val lastViewedAtEpochMillis: Long = 0)

  @Serializable
  private data class State(
    val schema: String = SCHEMA,
    val systems: Map<String, Counter> = emptyMap(),
    val previews: Map<String, Map<String, Counter>> = emptyMap(),
  )

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private var state: State = load()

  @Synchronized
  fun incrementSystem(system: String): Long = update {
    val current = state.systems[system] ?: Counter()
    val updated =
      current.copy(views = current.views + 1, lastViewedAtEpochMillis = System.currentTimeMillis())
    state = state.copy(systems = state.systems + (system to updated))
    updated.views
  }

  @Synchronized fun systemViews(system: String): Long = read { it.systems[system]?.views ?: 0 }

  @Synchronized
  fun systemViews(systems: Collection<String>): Map<String, Long> = read { snapshot ->
    systems.associateWith { snapshot.systems[it]?.views ?: 0 }
  }

  @Synchronized
  fun incrementPreview(system: String, preview: String): Long = update {
    val systemPreviews = state.previews[system].orEmpty()
    val current = systemPreviews[preview] ?: Counter()
    val updated =
      current.copy(views = current.views + 1, lastViewedAtEpochMillis = System.currentTimeMillis())
    state =
      state.copy(previews = state.previews + (system to (systemPreviews + (preview to updated))))
    updated.views
  }

  @Synchronized
  fun previewViews(system: String, preview: String): Long = read {
    it.previews[system]?.get(preview)?.views ?: 0
  }

  @Synchronized
  fun previewViews(system: String, previews: Collection<String>): Map<String, Long> =
    read { snapshot ->
      previews.associateWith { preview -> snapshot.previews[system]?.get(preview)?.views ?: 0 }
    }

  /**
   * Re-read while holding a cross-process lock before every mutation. During a rolling deployment
   * the retiring and replacement containers share `/config`; merging under this lock prevents
   * either process from replacing the other's newer counts.
   */
  private fun <T> update(block: () -> T): T = withFileLock {
    if (file != null) state = load()
    val result = block()
    pruneSystems()
    prunePreviews()
    persist()
    result
  }

  private fun <T> read(block: (State) -> T): T = withFileLock {
    if (file != null) state = load()
    block(state)
  }

  private fun <T> withFileLock(block: () -> T): T {
    val target = file ?: return block()
    return runCatching {
      target.parentFile?.mkdirs()
      val lock = File(target.parentFile ?: File("."), ".${target.name}.lock")
      FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
        channel ->
        channel.lock().use { block() }
      }
    }
      .onFailure {
        System.err.println("serve: engagement file lock unavailable: ${target.path}: ${it.message}")
      }
      .getOrElse { block() }
  }

  private fun load(): State {
    val source = file ?: return State()
    if (!source.isFile) return State()
    return runCatching { json.decodeFromString<State>(source.readText()) }
      .onFailure {
        System.err.println("serve: engagement file unreadable: ${source.path}: ${it.message}")
      }
      .getOrDefault(State())
  }

  private fun pruneSystems() {
    val overflow = state.systems.size - maxSystemEntries
    if (overflow <= 0) return
    val victims =
      state.systems.entries
        .sortedBy { it.value.lastViewedAtEpochMillis }
        .take(overflow)
        .map { it.key }
    state =
      state.copy(
        systems = state.systems - victims.toSet(),
        previews = state.previews - victims.toSet(),
      )
  }

  private fun prunePreviews() {
    val overflow = state.previews.values.sumOf { it.size } - maxPreviewEntries
    if (overflow <= 0) return
    val victims =
      state.previews
        .flatMap { (system, previews) ->
          previews.map { (preview, counter) ->
            Triple(system, preview, counter.lastViewedAtEpochMillis)
          }
        }
        .sortedBy { it.third }
        .take(overflow)
        .groupBy({ it.first }, { it.second })
    val retained = state.previews.toMutableMap()
    victims.forEach { (system, previewIds) ->
      val systemPreviews = retained[system].orEmpty() - previewIds.toSet()
      if (systemPreviews.isEmpty()) retained.remove(system) else retained[system] = systemPreviews
    }
    state = state.copy(previews = retained)
  }

  private fun persist() {
    val target = file ?: return
    runCatching {
      target.parentFile?.mkdirs()
      val temporary = File(target.parentFile ?: File("."), ".${target.name}.tmp")
      temporary.writeText(json.encodeToString(state) + "\n")
      try {
        Files.move(
          temporary.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    }
      .onFailure {
        System.err.println("serve: engagement file not persisted: ${target.path}: ${it.message}")
      }
  }

  companion object {
    const val SCHEMA = "compose-preview-serve/engagement/v1"
    const val DEFAULT_MAX_SYSTEM_ENTRIES = 1_000
    const val DEFAULT_MAX_PREVIEW_ENTRIES = 10_000
  }
}
