package ee.schimke.composeai.plugin

import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Archives a snapshot of each rendered preview into a per-preview history
 * folder (keyed by preview id), only when the PNG's SHA-256 differs from the
 * most recent archived entry. Timestamp-prefixed filenames sort
 * lexicographically, so "latest" = lexicographically max file.
 *
 * The history directory is intentionally **not** declared as a task output:
 * it lives outside `build/` and must survive `./gradlew clean`. This makes
 * the task ineligible for build-cache restoration — we mark it disabled and
 * rely on its input bytes (renders + manifest) for up-to-date checks.
 */
@DisableCachingByDefault(because = "maintains persistent cross-build history outside build/")
abstract class HistorizePreviewsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val previewsJson: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rendersDir: DirectoryProperty

    /** Intentionally @Internal — persistent across builds; not a Gradle-managed output. */
    @get:Internal
    abstract val historyDir: DirectoryProperty

    @TaskAction
    fun historize() {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<PreviewManifest>(previewsJson.get().asFile.readText())

        val historyRoot = historyDir.get().asFile
        val rendersRoot = rendersDir.get().asFile
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(ZonedDateTime.now(ZoneOffset.UTC))

        var archived = 0
        for (preview in manifest.previews) {
            val currentRender = rendersRoot.resolve("${preview.id}.png")
            if (!currentRender.exists()) continue

            val previewFolder = historyRoot.resolve(sanitize(preview.id)).apply { mkdirs() }
            val currentHash = sha256(currentRender.readBytes())
            val latestHash = latestHashIn(previewFolder)

            if (latestHash == currentHash) continue

            val dest = uniqueDest(previewFolder, timestamp)
            currentRender.copyTo(dest)
            archived++
            logger.info("compose-preview history: archived ${preview.id} -> ${dest.name}")
        }

        logger.lifecycle("compose-preview history: archived $archived new snapshot(s) under $historyRoot")
    }

    private fun latestHashIn(dir: File): String? {
        val latest = dir.listFiles { f -> f.isFile && f.extension == "png" }
            ?.maxByOrNull { it.name }
            ?: return null
        return sha256(latest.readBytes())
    }

    /** If multiple renders happen inside the same second, append `-N` so filenames stay unique. */
    private fun uniqueDest(dir: File, timestamp: String): File {
        val primary = dir.resolve("$timestamp.png")
        if (!primary.exists()) return primary
        var n = 1
        while (true) {
            val alt = dir.resolve("$timestamp-$n.png")
            if (!alt.exists()) return alt
            n++
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(id: String): String = buildString(id.length) {
        for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
    }
}
