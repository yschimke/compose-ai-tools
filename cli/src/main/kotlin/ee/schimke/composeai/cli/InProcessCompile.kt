package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Why edits to a module compile through Gradle rather than inside its preview daemon, read from the
 * launch descriptor's `btaCompile.ineligibilityReason`. The plugin writes the reason: a module that
 * applies KSP or KAPT, or a compiler plugin the daemon cannot configure. `null` when the module
 * compiles in process, or when the descriptor carries no `btaCompile` block (an older plugin).
 *
 * It matters for speed: an in-process compile turns an edit into new classes in well under a
 * second, while the Gradle fallback costs seconds per edit, and nothing else tells the user why.
 */
internal fun inProcessCompileBlocker(descriptor: JsonObject): String? =
  (descriptor["btaCompile"] as? JsonObject)
    ?.get("ineligibilityReason")
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf { it.isNotBlank() }

/** One line for a doctor: what [reason] costs, and for KSP what still compiles in process. */
internal fun inProcessCompileMessage(reason: String): String = buildString {
  append("edits compile through Gradle, not in the preview daemon: ")
  append(reason)
  append(". Each edit pays a Gradle compile (seconds) instead of an in-process one (well under a")
  append(" second).")
  if (reason.contains("ksp", ignoreCase = true) || reason.contains("kapt", ignoreCase = true)) {
    append(" The in-process compiler does not run annotation processors yet; @Preview functions")
    append(" in a module without them do compile in process.")
  }
}

/**
 * The `compose-preview doctor` check for [modulePath]'s in-process compile: `null` when it has no
 * launch descriptor yet (`compose-preview mcp install` writes one), so a project that never used
 * the daemon gets no noise.
 */
internal fun inProcessCompileCheck(
  modulePath: String,
  descriptorFile: File,
  fileSystem: FileSystem = SystemFileSystem,
): DoctorCheck? {
  if (!descriptorFile.isFile) return null
  val descriptor =
    try {
      Json.parseToJsonElement(fileSystem.read(descriptorFile.path.toPath()) { readUtf8() })
        .jsonObject
    } catch (_: Exception) {
      return null
    }
  val id = "project.${modulePath.trimStart(':').ifEmpty { "root" }}.in-process-compile"
  val reason =
    inProcessCompileBlocker(descriptor)
      ?: return DoctorCheck(
        id = id,
        category = "project",
        status = "ok",
        message = "$modulePath — edits compile in the preview daemon",
      )
  return DoctorCheck(
    id = id,
    category = "project",
    status = "warning",
    message = "$modulePath — edits compile through Gradle, not in the preview daemon",
    detail = inProcessCompileMessage(reason),
  )
}
