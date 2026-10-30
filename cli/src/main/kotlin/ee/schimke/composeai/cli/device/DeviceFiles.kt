package ee.schimke.composeai.cli.device

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `ls`, `mkdir`, `rm`, `cat`, `touch`, `stat` and `test` over [DeviceStorage]. */
internal class DeviceFiles(private val device: VirtualDevice) {

  private val storage
    get() = device.storage

  fun ls(args: List<String>, out: ShellOutput): Int {
    val long = args.any { it.startsWith("-") && 'l' in it }
    val paths = args.filter { !it.startsWith("-") }.ifEmpty { listOf("/") }
    var exit = 0
    for (path in paths) {
      val file = storage.resolve(path)
      when {
        file != null && file.isDirectory ->
          file
            .listFiles()
            .orEmpty()
            .sortedBy { it.name }
            .forEach { out.line(if (long) longEntry(it) else it.name) }
        file != null && file.isFile -> out.line(if (long) longEntry(file, path) else path)
        storage.isSystemDirectory(path) -> Unit
        else -> {
          out.err("ls: $path: No such file or directory")
          exit = 1
        }
      }
    }
    return exit
  }

  fun mkdir(args: List<String>, out: ShellOutput): Int {
    for (path in args.filter { !it.startsWith("-") }) {
      val file =
        storage.resolve(path)
          ?: if (storage.isSystemDirectory(path)) continue
          else return out.fail("mkdir: '$path': Read-only file system")
      if (!file.isDirectory && !file.mkdirs()) return out.fail("mkdir: '$path': File exists")
    }
    return 0
  }

  fun rm(args: List<String>, out: ShellOutput): Int {
    val force = args.any { it.startsWith("-") && 'f' in it }
    for (path in args.filter { !it.startsWith("-") }) {
      val file = storage.resolve(path)
      if (file == null || !file.exists()) {
        if (!force) return out.fail("rm: $path: No such file or directory")
        continue
      }
      file.deleteRecursively()
    }
    return 0
  }

  fun cat(args: List<String>, out: ShellOutput): Int {
    for (path in args.filter { !it.startsWith("-") }) {
      val file =
        storage.resolve(path)?.takeIf { it.isFile }
          ?: return out.fail("cat: $path: No such file or directory")
      out.write(file.readBytes())
    }
    return 0
  }

  fun touch(args: List<String>, out: ShellOutput): Int {
    for (path in args.filter { !it.startsWith("-") }) {
      val file = storage.resolve(path) ?: return out.fail("touch: '$path': Read-only file system")
      file.parentFile.mkdirs()
      if (!file.exists()) file.createNewFile() else file.setLastModified(System.currentTimeMillis())
    }
    return 0
  }

  fun stat(args: List<String>, out: ShellOutput): Int {
    val path = args.lastOrNull { !it.startsWith("-") } ?: return out.fail("stat: no file")
    val file = storage.resolve(path)
    if ((file == null || !file.exists()) && !storage.isSystemDirectory(path)) {
      return out.fail("stat: '$path': No such file or directory")
    }
    return out.line(
      "  File: $path\n  Size: ${file?.takeIf { it.isFile }?.length() ?: 0}\t" +
        (if (file?.isFile == true) "regular file" else "directory")
    )
  }

  fun exists(path: String?, out: ShellOutput): Int {
    if (path == null) return out.fail("missing operand")
    val file = storage.resolve(path)
    if ((file == null || !file.exists()) && !storage.isSystemDirectory(path)) {
      return out.fail("$path: No such file or directory")
    }
    return 0
  }

  /** `test -e/-f/-d path`. */
  fun test(args: List<String>): Int {
    if (args.size != 2) return 1
    val file = storage.resolve(args[1])
    val system = storage.isSystemDirectory(args[1])
    val ok =
      when (args[0]) {
        "-e" -> file?.exists() == true || system
        "-f" -> file?.isFile == true
        "-d" -> file?.isDirectory == true || system
        "-s" -> (file?.length() ?: 0) > 0
        else -> false
      }
    return if (ok) 0 else 1
  }

  private fun longEntry(file: File, name: String = file.name): String {
    val kind = if (file.isDirectory) "drwxrwx--x" else "-rw-rw-rw-"
    return "$kind 1 shell shell ${file.length()} 2026-01-01 00:00 $name"
  }
}

/**
 * The sync service (`adb push`, `pull`, `ls` and the `STAT` adb runs before them), over
 * [DeviceStorage]: AOSP `SYNC.TXT`'s `STAT`, `LIST`, `SEND`, `RECV`, `QUIT`.
 */
internal class SyncService(private val storage: DeviceStorage) : AdbService {

  override fun run(stream: AdbDeviceServer.AdbStream) {
    val input = LeReader(stream.input)
    val out = stream.output
    while (true) {
      val id =
        try {
          input.id()
        } catch (_: java.io.EOFException) {
          return
        }
      val length = input.int()
      when (id) {
        "STAT" -> stat(String(input.bytes(length)), out)
        "LIST" -> list(String(input.bytes(length)), out)
        "SEND" -> send(String(input.bytes(length)), input, out)
        "RECV" -> recv(String(input.bytes(length)), out)
        "QUIT" -> return
        else -> {
          fail(out, "unsupported sync request $id")
          return
        }
      }
    }
  }

  private fun stat(path: String, out: OutputStream) {
    val file = storage.resolve(path)
    val (mode, size, time) =
      when {
        file != null && file.isFile -> Triple(FILE or 420, file.length().toInt(), seconds(file))
        file != null && file.isDirectory || storage.isSystemDirectory(path) ->
          Triple(DIRECTORY or 505, 4096, (System.currentTimeMillis() / 1000).toInt())
        else -> Triple(0, 0, 0)
      }
    out.write(le("STAT", mode, size, time))
  }

  private fun list(path: String, out: OutputStream) {
    val dir = storage.resolve(path)
    for (file in dir?.listFiles().orEmpty().sortedBy { it.name }) {
      val name = file.name.toByteArray()
      val mode = if (file.isDirectory) DIRECTORY or 505 else FILE or 420
      out.write(le("DENT", mode, file.length().toInt(), seconds(file), name.size) + name)
    }
    out.write(le("DONE", 0, 0, 0, 0))
  }

  private fun send(spec: String, input: LeReader, out: OutputStream) {
    val path = spec.substringBeforeLast(',')
    val file = storage.resolve(path)
    val sink: OutputStream =
      file?.let {
        it.parentFile.mkdirs()
        it.outputStream()
      } ?: OutputStream.nullOutputStream()
    sink.use {
      while (true) {
        val id = input.id()
        val n = input.int()
        when (id) {
          "DATA" -> it.write(input.bytes(n))
          "DONE" -> {
            file?.setLastModified(n.toLong() * 1000)
            break
          }
          else -> {
            fail(out, "unexpected $id in SEND")
            return
          }
        }
      }
    }
    if (file == null) {
      fail(out, "$path: Read-only file system (the simulator takes /data/local/tmp and /sdcard)")
    } else {
      out.write(le("OKAY", 0))
    }
  }

  private fun recv(path: String, out: OutputStream) {
    val file = storage.resolve(path)?.takeIf { it.isFile }
    if (file == null) {
      fail(out, "remote object '$path' does not exist")
      return
    }
    file.inputStream().use { input -> chunks(input, out) }
    out.write(le("DONE", 0))
  }

  private fun chunks(input: InputStream, out: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      out.write(le("DATA", n) + buffer.copyOf(n))
    }
  }

  private fun fail(out: OutputStream, message: String) {
    val bytes = message.toByteArray()
    out.write(le("FAIL", bytes.size) + bytes)
  }

  private fun seconds(file: File) = (file.lastModified() / 1000).toInt()

  private companion object {
    const val FILE = 0x8000
    const val DIRECTORY = 0x4000

    fun le(id: String, vararg ints: Int): ByteArray {
      val b = ByteBuffer.allocate(4 + ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      b.put(id.toByteArray(Charsets.US_ASCII))
      ints.forEach { b.putInt(it) }
      return b.array()
    }
  }
}
