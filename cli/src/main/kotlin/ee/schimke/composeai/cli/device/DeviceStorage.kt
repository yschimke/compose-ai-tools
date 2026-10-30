package ee.schimke.composeai.cli.device

import java.io.File

/**
 * The part of the device's file system adb reaches directly — `adb push` / `pull`, `ls`, `rm`, and
 * the staged APKs of a legacy `adb install` — held under the device's own directory:
 * `/data/local/tmp` and `/sdcard` (`/storage/emulated/0`). An app's private storage is the
 * simulator's, reached through `run-as`, which the simulator answers.
 */
internal class DeviceStorage(private val root: File) {

  /** The host file behind device path [path], or null when the device has no such writable area. */
  fun resolve(path: String): File? {
    val normalized = normalize(path)
    for ((prefix, dir) in AREAS) {
      if (normalized == prefix || normalized.startsWith("$prefix/")) {
        val rest = normalized.removePrefix(prefix).trimStart('/')
        val base = File(root, dir)
        base.mkdirs()
        return if (rest.isEmpty()) base else File(base, rest)
      }
    }
    return null
  }

  /** Directories that exist on every device, though nothing here can be written into them. */
  fun isSystemDirectory(path: String): Boolean = normalize(path) in SYSTEM_DIRECTORIES

  companion object {
    private val AREAS =
      listOf(
        "/data/local/tmp" to "data/local/tmp",
        "/sdcard" to "sdcard",
        "/storage/emulated/0" to "sdcard",
        "/storage/self/primary" to "sdcard",
      )

    private val SYSTEM_DIRECTORIES =
      setOf("/", "/data", "/data/local", "/storage", "/storage/emulated", "/system", "/data/app")

    fun normalize(path: String): String {
      val parts = ArrayDeque<String>()
      for (part in path.split('/')) {
        when (part) {
          "",
          "." -> Unit
          ".." -> parts.removeLastOrNull()
          else -> parts.addLast(part)
        }
      }
      return "/" + parts.joinToString("/")
    }
  }
}
