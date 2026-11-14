package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `compose-preview dwf-runtime`: manages a locally cached `DeclarativeWatchFaceRuntime.apk`
 * extracted from a Wear OS 5/6 emulator or device via `adb` (or installed from a local APK file).
 *
 * Google's runtime APK is proprietary and never redistributed or downloaded from the network; this
 * command pulls it only from a user's own local emulator/device or registers a local file into
 * `composeAiCacheDir("dwf-runtime")`.
 */
internal class DwfRuntimeCommand(
  private val args: List<String>,
  private val cacheRoot: File = composeAiCacheDir(CACHE_SUBDIR),
  private val env: (String) -> String? = System::getenv,
  private val adbRunner: AdbRunner = ProcessAdbRunner(env),
) {

  fun run() {
    if ("--help" in args || "-h" in args) {
      println(USAGE)
      return
    }
    val jsonOut = "--json" in args
    val verbose = "--verbose" in args || "-v" in args
    val positionals = CliFlags.positionals(args)
    val action = positionals.firstOrNull() ?: "status"

    when (action) {
      "status" -> runStatus(jsonOut)
      "install" -> {
        val apkArg = args.flagValue("--apk") ?: positionals.getOrNull(1)
        if (apkArg.isNullOrBlank()) {
          fail("dwf-runtime install needs --apk <DeclarativeWatchFaceRuntime.apk>", jsonOut)
        }
        val sourceApk = File(apkArg).absoluteFile
        val installed = runCatching {
          installLocalApk(sourceApk, cacheRoot, source = "local:${sourceApk.path}")
        }
          .getOrElse { fail(it.message ?: "$it", jsonOut) }
        printResult("install", installed, jsonOut)
      }
      "pull" -> {
        val serial = args.flagValue("--serial")
        val force = "--force" in args
        val outputFile =
          (args.flagValue("--output") ?: args.flagValue("-o"))?.let { File(it).absoluteFile }
        val existing = cachedMetadata(cacheRoot)
        if (!force && existing != null && cachedApkFile(cacheRoot).isFile && outputFile == null) {
          printResult("pull", existing, jsonOut, note = "already cached (pass --force to re-pull)")
          return
        }
        val pulled = runCatching {
          pullFromAdb(
            cacheRoot = cacheRoot,
            serial = serial,
            outputFile = outputFile,
            adbRunner = adbRunner,
            verbose = verbose,
          )
        }
          .getOrElse { fail(it.message ?: "$it", jsonOut) }
        printResult("pull", pulled, jsonOut)
      }
      else ->
        fail(
          "unknown dwf-runtime subcommand '$action' (expected status, pull, or install)",
          jsonOut,
        )
    }
  }

  private fun runStatus(jsonOut: Boolean) {
    val envApkPath = env(LaunchCommand.DWF_RUNTIME_ENV)?.takeIf { it.isNotBlank() }
    val envApk = envApkPath?.let { File(it).absoluteFile }
    val envValid = envApk?.takeIf { it.isFile }?.let { inspectRuntimeApk(it) }
    val cachedFile = cachedApkFile(cacheRoot)
    val cachedMeta =
      cachedMetadata(cacheRoot)
        ?: cachedFile
          .takeIf { it.isFile }
          ?.let { file ->
            inspectRuntimeApk(file)?.let { info ->
              RuntimeMetadata(
                packageName = info.packageName,
                sha256 = sha256(file),
                sizeBytes = file.length(),
                source = "cache",
                path = file.absolutePath,
              )
            }
          }

    val activeSource =
      when {
        envValid != null -> "env"
        cachedMeta != null && cachedFile.isFile -> "cache"
        else -> "builtin-compiler"
      }
    val activePath =
      when (activeSource) {
        "env" -> envApk!!.absolutePath
        "cache" -> cachedFile.absolutePath
        else -> null
      }

    if (jsonOut) {
      val payload = buildJsonObject {
        put("schema", SCHEMA)
        put("action", "status")
        put("ok", true)
        put("activeSource", activeSource)
        activePath?.let { put("activePath", it) }
        envApkPath?.let { put("envPath", it) }
        put("envValid", envValid != null)
        put("cached", cachedMeta != null && cachedFile.isFile)
        put("cachePath", cachedFile.absolutePath)
        cachedMeta?.let {
          put("packageName", it.packageName)
          put("sha256", it.sha256)
          put("sizeBytes", it.sizeBytes)
          put("source", it.source)
        }
      }
      println(prettyJson.encodeToString(payload))
    } else {
      println("DWF runtime status:")
      println(
        "  Active mode:  $activeSource${activePath?.let { " ($it)" } ?: " (WFF -> RemoteCompose)"}",
      )
      if (envApkPath != null) {
        println(
          "  Env override: $envApkPath (${if (envValid != null) "valid: ${envValid.packageName}" else "missing or invalid"})",
        )
      }
      if (cachedMeta != null && cachedFile.isFile) {
        println("  Cached APK:   ${cachedFile.absolutePath}")
        println("  Package:      ${cachedMeta.packageName}")
        println("  SHA-256:      ${cachedMeta.sha256}")
        println("  Source:       ${cachedMeta.source}")
      } else {
        println("  Cached APK:   none (${cachedFile.absolutePath})")
      }
    }
  }

  private fun printResult(
    action: String,
    meta: RuntimeMetadata,
    jsonOut: Boolean,
    note: String? = null,
  ) {
    if (jsonOut) {
      val payload = buildJsonObject {
        put("schema", SCHEMA)
        put("action", action)
        put("ok", true)
        put("path", meta.path)
        put("packageName", meta.packageName)
        put("sha256", meta.sha256)
        put("sizeBytes", meta.sizeBytes)
        put("source", meta.source)
        note?.let { put("note", it) }
      }
      println(prettyJson.encodeToString(payload))
    } else {
      println("DWF runtime ($action): ${meta.path}")
      println("  Package: ${meta.packageName}")
      println("  SHA-256: ${meta.sha256}")
      println("  Source:  ${meta.source}")
      if (note != null) println("  Note:    $note")
    }
  }

  private fun fail(message: String, jsonOut: Boolean): Nothing {
    if (jsonOut) {
      val payload = buildJsonObject {
        put("schema", SCHEMA)
        put("ok", false)
        put("error", message)
      }
      println(prettyJson.encodeToString(payload))
    } else {
      System.err.println("compose-preview dwf-runtime: $message")
    }
    exitProcess(1)
  }

  @Serializable
  internal data class RuntimeMetadata(
    val packageName: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: String,
    val path: String,
  )

  internal data class RuntimeApkInfo(
    val packageName: String,
    val hasWatchFaceControlAction: Boolean,
    val hasDex: Boolean,
  )

  internal interface AdbRunner {
    fun run(serial: String?, vararg adbArgs: String): CommandOutput

    data class CommandOutput(val exitCode: Int, val stdout: String, val stderr: String)
  }

  internal class ProcessAdbRunner(private val env: (String) -> String? = System::getenv) :
    AdbRunner {
    private val adbBinary: String by lazy { findAdbBinary(env) }

    override fun run(serial: String?, vararg adbArgs: String): AdbRunner.CommandOutput {
      val cmd = buildList {
        add(adbBinary)
        if (!serial.isNullOrBlank()) {
          add("-s")
          add(serial)
        }
        addAll(adbArgs)
      }
      return try {
        val proc = ProcessBuilder(cmd).start()
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
          proc.destroyForcibly()
          AdbRunner.CommandOutput(124, "", "adb timed out: ${cmd.joinToString(" ")}")
        } else {
          AdbRunner.CommandOutput(
            proc.exitValue(),
            proc.inputStream.bufferedReader().readText(),
            proc.errorStream.bufferedReader().readText(),
          )
        }
      } catch (e: Exception) {
        AdbRunner.CommandOutput(127, "", e.message ?: "$e")
      }
    }
  }

  companion object {
    const val CACHE_SUBDIR = "dwf-runtime"
    const val APK_FILE_NAME = "DeclarativeWatchFaceRuntime.apk"
    const val METADATA_FILE_NAME = "metadata.json"
    const val SCHEMA = "compose-preview-dwf-runtime/v1"
    const val WATCH_FACE_CONTROL_ACTION = "com.google.android.wearable.action.WATCH_FACE_CONTROL"

    val CANDIDATE_PACKAGES =
      listOf(
        "com.google.wear.watchface.runtime",
        "com.android.wearable.watchface.runtime",
        "com.google.android.wearable.watchface.rwf",
      )

    private val prettyJson = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    fun cachedApkFile(cacheRoot: File = composeAiCacheDir(CACHE_SUBDIR)): File =
      File(cacheRoot, APK_FILE_NAME)

    internal fun cachedMetadata(cacheRoot: File): RuntimeMetadata? {
      val metaFile = File(cacheRoot, METADATA_FILE_NAME)
      if (!metaFile.isFile) return null
      return runCatching { prettyJson.decodeFromString<RuntimeMetadata>(metaFile.readText()) }
        .getOrNull()
    }

    /**
     * Resolves `--dwf-runtime auto`:
     * 1. `$COMPOSE_PREVIEW_DWF_RUNTIME_APK` if set and valid
     * 2. Cached `DeclarativeWatchFaceRuntime.apk` in [cacheRoot]
     * 3. Opportunistic `adb` pull if an online Wear device/emulator has the runtime package
     * 4. `null`, letting `LaunchCommand` fall back cleanly to built-in WFF -> RemoteCompose
     */
    fun resolveAuto(
      cacheRoot: File = composeAiCacheDir(CACHE_SUBDIR),
      env: (String) -> String? = System::getenv,
      adbRunner: AdbRunner = ProcessAdbRunner(env),
      allowAdbPull: Boolean = true,
      verbose: Boolean = false,
    ): File? {
      val envPath =
        env(LaunchCommand.DWF_RUNTIME_APK_ENV_ALIAS)?.takeIf { it.isNotBlank() }
          ?: env(LaunchCommand.DWF_RUNTIME_ENV)?.takeIf { it.isNotBlank() }
      if (envPath != null) {
        val envFile = File(envPath).absoluteFile
        if (envFile.isFile && inspectRuntimeApk(envFile) != null) {
          return envFile
        }
      }
      val cached = cachedApkFile(cacheRoot)
      if (cached.isFile && inspectRuntimeApk(cached) != null) {
        return cached
      }
      if (allowAdbPull) {
        val pulled = runCatching {
          val devices = adbRunner.run(null, "devices")
          val hasOnlineDevice =
            devices.exitCode == 0 &&
              devices.stdout.lineSequence().drop(1).any { line -> line.trim().endsWith("\tdevice") }
          if (!hasOnlineDevice) null
          else
            pullFromAdb(
              cacheRoot,
              serial = null,
              outputFile = null,
              adbRunner = adbRunner,
              verbose = verbose,
            )
        }
          .getOrNull()
        if (pulled != null) {
          val f = File(pulled.path)
          if (f.isFile) return f
        }
      }
      if (verbose) {
        System.err.println(
          "compose-preview launch: no DeclarativeWatchFaceRuntime.apk cached or reachable on adb; " +
            "using built-in WFF -> RemoteCompose renderer",
        )
      }
      return null
    }

    internal fun installLocalApk(
      sourceApk: File,
      cacheRoot: File,
      source: String,
    ): RuntimeMetadata {
      require(sourceApk.isFile) { "no such APK: $sourceApk" }
      val info =
        inspectRuntimeApk(sourceApk)
          ?: throw IllegalArgumentException(
            "$sourceApk is not a valid DeclarativeWatchFaceRuntime APK (must carry code and " +
              "declare $WATCH_FACE_CONTROL_ACTION in AndroidManifest.xml)",
          )
      cacheRoot.mkdirs()
      val target = cachedApkFile(cacheRoot)
      if (sourceApk.canonicalFile != target.canonicalFile) {
        Files.copy(sourceApk.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      val metadata =
        RuntimeMetadata(
          packageName = info.packageName,
          sha256 = sha256(target),
          sizeBytes = target.length(),
          source = source,
          path = target.absolutePath,
        )
      File(cacheRoot, METADATA_FILE_NAME).writeText(prettyJson.encodeToString(metadata))
      return metadata
    }

    internal fun pullFromAdb(
      cacheRoot: File,
      serial: String?,
      outputFile: File?,
      adbRunner: AdbRunner,
      verbose: Boolean = false,
    ): RuntimeMetadata {
      val candidates = LinkedHashSet<String>(CANDIDATE_PACKAGES)
      val listed = adbRunner.run(serial, "shell", "pm", "list", "packages")
      if (listed.exitCode == 0) {
        listed.stdout
          .lineSequence()
          .map { it.trim().removePrefix("package:").trim() }
          .filter { pkg ->
            pkg.contains("watchface.runtime", ignoreCase = true) ||
              pkg.contains("declarativewatchface", ignoreCase = true) ||
              pkg.endsWith(".watchface.rwf", ignoreCase = true)
          }
          .forEach { candidates.add(it) }
      } else if (listed.exitCode == 127) {
        throw IllegalStateException("adb was not found on PATH or ANDROID_HOME/platform-tools")
      }

      var remoteApkPath: String? = null
      var matchedPackage: String? = null
      for (pkg in candidates) {
        val pmPath = adbRunner.run(serial, "shell", "pm", "path", pkg)
        if (pmPath.exitCode != 0) continue
        val pathLine =
          pmPath.stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith(".apk") }
        if (pathLine != null) {
          remoteApkPath = pathLine.removePrefix("package:").trim()
          matchedPackage = pkg
          break
        }
      }
      if (remoteApkPath == null || matchedPackage == null) {
        throw IllegalStateException(
          "no DeclarativeWatchFaceRuntime package found on connected Wear OS device/emulator " +
            "(probed: ${candidates.joinToString(", ")})",
        )
      }
      if (verbose) {
        System.err.println(
          "compose-preview dwf-runtime: pulling $matchedPackage ($remoteApkPath) via adb",
        )
      }
      cacheRoot.mkdirs()
      val tempApk = Files.createTempFile(cacheRoot.toPath(), "dwf-runtime-pull-", ".apk").toFile()
      try {
        val pullRes = adbRunner.run(serial, "pull", remoteApkPath, tempApk.absolutePath)
        if (pullRes.exitCode != 0 || !tempApk.isFile || tempApk.length() == 0L) {
          throw IllegalStateException(
            "adb pull $remoteApkPath failed (exit ${pullRes.exitCode}): " +
              (pullRes.stderr.ifBlank { pullRes.stdout }).trim(),
          )
        }
        val meta =
          installLocalApk(
            tempApk,
            cacheRoot,
            source = "adb:${serial ?: "default"}:$matchedPackage",
          )
        if (outputFile != null) {
          outputFile.parentFile?.mkdirs()
          Files.copy(
            cachedApkFile(cacheRoot).toPath(),
            outputFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
          )
          return meta.copy(path = outputFile.absolutePath)
        }
        return meta
      } finally {
        tempApk.delete()
      }
    }

    /**
     * Inspects [apk] to verify it is a DWF runtime APK: a valid zip with at least one `classes.dex`
     * and an `AndroidManifest.xml` referencing `WATCH_FACE_CONTROL` (or a known runtime package).
     */
    internal fun inspectRuntimeApk(apk: File): RuntimeApkInfo? = runCatching {
      if (!apk.isFile) return null
      ZipFile(apk).use { zip ->
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
        val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
        val hasDex =
          zip.entries().asSequence().any {
            !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex")
          }
        if (!hasDex) return null

        val packageName: String
        val hasAction: Boolean
        if (
          manifestBytes.size >= 8 &&
            ByteBuffer.wrap(manifestBytes, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and
              0xFFFF == 0x0003
        ) {
          val signals = ManifestSignals.read(manifestBytes)
          packageName = signals.packageName
          hasAction =
            binaryXmlContainsString(manifestBytes, WATCH_FACE_CONTROL_ACTION) ||
              packageName in CANDIDATE_PACKAGES
        } else {
          val text = manifestBytes.toString(Charsets.UTF_8)
          packageName =
            Regex("""\bpackage\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1).orEmpty()
          hasAction = text.contains(WATCH_FACE_CONTROL_ACTION) || packageName in CANDIDATE_PACKAGES
        }
        if (packageName.isBlank() || !hasAction) return null
        RuntimeApkInfo(
          packageName = packageName,
          hasWatchFaceControlAction = hasAction,
          hasDex = hasDex,
        )
      }
    }
      .getOrNull()

    private fun binaryXmlContainsString(bytes: ByteArray, needle: String): Boolean {
      val utf8 = needle.toByteArray(Charsets.UTF_8)
      val utf16 = needle.toByteArray(Charsets.UTF_16LE)
      return indexOfBytes(bytes, utf8) >= 0 || indexOfBytes(bytes, utf16) >= 0
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
      if (needle.isEmpty() || haystack.size < needle.size) return -1
      val max = haystack.size - needle.size
      outer@ for (i in 0..max) {
        if (haystack[i] != needle[0]) continue
        for (j in 1 until needle.size) {
          if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
      }
      return -1
    }

    private fun findAdbBinary(env: (String) -> String?): String {
      for (sdkVar in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        val sdk = env(sdkVar)?.takeIf { it.isNotBlank() } ?: continue
        val candidate = File(sdk, "platform-tools/adb")
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
      }
      return "adb"
    }

    private fun sha256(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
          val r = input.read(buf)
          if (r <= 0) break
          md.update(buf, 0, r)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }

    val USAGE =
      """
      compose-preview dwf-runtime [status|pull|install] [options]

      Manage the cached Wear OS DeclarativeWatchFaceRuntime.apk used by `compose-preview launch`
      with `--dwf-runtime auto` (or when `DeclarativeWatchFaceRuntime.apk` is needed to run a
      resource-only Watch Face Format APK through Android's real watch face runtime).

      Google's runtime APK is never redistributed or fetched over the network; `pull` extracts it
      from a local Wear OS 5/6 emulator or device over `adb`, and `install` caches a local file.

      Subcommands:
        status                 Report active runtime source (env, cache, or built-in compiler).
        pull [--from-adb]      Pull DeclarativeWatchFaceRuntime.apk from an adb-connected Wear
                               emulator/device into the local cache.
        install --apk <file>   Validate and copy a local DeclarativeWatchFaceRuntime.apk into
                               the cache.

      Options:
        --from-adb             Pull from adb (default for `pull`).
        --serial <id>          adb device serial (e.g. emulator-5554).
        --apk <file>           Local DeclarativeWatchFaceRuntime.apk for `install`.
        --output, -o <file>    Also copy the pulled APK to <file>.
        --force                Re-pull even when an APK is already cached.
        --json                 Emit machine-readable JSON (`$SCHEMA`).
        --verbose, -v          Verbose progress on stderr.
        --help, -h             Show this help.
      """
        .trimIndent()
  }
}
