package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Pins that a Remote Compose bundle carries the rc-players players the connector selects by id,
 * even though nothing in the consumer's bytecode references them.
 *
 * The connector reaches `CmpAndroidPlayerBackend` (and the embedded backend) through
 * `Class.forName`, which the closure walk cannot follow. Without a replay seed, `rc-player-compose`
 * on the consumer's runtime classpath reached zero classes and was pruned, so `bundle.json` never
 * listed it and a serve host never offered `cmp-android` for the bundle.
 */
class BundleRcPlayerSeedsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val previewClassFqn = "ee.schimke.composeai.plugin.BundlePreviewIds"
  private val stem = "sample"

  @Test
  fun `a Remote Compose bundle keeps rc-player-compose and the embedded player`() {
    val cmp =
      jarWithClass(
        "rc-player-compose.aar.jar",
        "ee/schimke/composeai/rcplayer/compose/RcComposePlayerKt",
      )
    val embedded =
      jarWithClass(
        "third-party-rc-embedded-player.jar",
        "ee/schimke/composeai/rcembedded/player/RcPlayerKt",
      )
    val unrelated = jarWithClass("unrelated.jar", "com/example/unrelated/Unused")

    val task = newBundleTask(tmp.newFolder("out"), rcIr = true)
    task.dependencyJars.from(cmp, embedded, unrelated)
    task.dependencyCoordinates.set(
      mapOf(
        cmp.absolutePath to "maven:ee.schimke.composeai:rc-player-compose-android:2.1.1:aar",
        embedded.absolutePath to
          "maven:ee.schimke.composeai:third-party-rc-embedded-player:2.1.1:aar",
        unrelated.absolutePath to "maven:com.example:unrelated:1.0:jar",
      )
    )
    task.pack()

    val artifacts = classpathArtifacts(task.output.get().asFile)
    assertThat(artifacts).contains("rc-player-compose-android")
    assertThat(artifacts).contains("third-party-rc-embedded-player")
    assertThat(artifacts).doesNotContain("unrelated")
  }

  @Test
  fun `a bundle without Remote Compose IR does not carry the rc-players players`() {
    val cmp =
      jarWithClass(
        "rc-player-compose.aar.jar",
        "ee/schimke/composeai/rcplayer/compose/RcComposePlayerKt",
      )

    val task = newBundleTask(tmp.newFolder("out-plain"), rcIr = false)
    task.dependencyJars.from(cmp)
    task.dependencyCoordinates.set(
      mapOf(cmp.absolutePath to "maven:ee.schimke.composeai:rc-player-compose-android:2.1.1:aar")
    )
    task.pack()

    // Control: the seed only applies when the bundle replays Remote Compose IR.
    assertThat(classpathArtifacts(task.output.get().asFile))
      .doesNotContain("rc-player-compose-android")
  }

  private fun newBundleTask(outDir: File, rcIr: Boolean): BundlePreviewTask {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task = project.tasks.register("composePreviewBundle", BundlePreviewTask::class.java).get()
    val manifest =
      PreviewManifest(
        module = ":sample",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "$previewClassFqn.sample",
              functionName = "sample",
              className = previewClassFqn,
              captures = listOf(Capture(renderOutput = "renders/$stem.png")),
            )
          ),
      )
    val previewsJson =
      File(outDir, "previews.json").apply {
        writeText(json.encodeToString(PreviewManifest.serializer(), manifest))
      }
    val renders = File(outDir, "renders").apply { mkdirs() }
    if (rcIr) File(renders, "$stem.$IR_EXT_REMOTECOMPOSE").writeBytes(byteArrayOf(1, 2, 3, 4))
    task.previewsJson.set(previewsJson)
    task.output.set(File(outDir, "bundle.png"))
    task.rendersDir.set(renders)
    task.modulePath.set(":sample")
    task.producedBy.set("test")
    task.backend.set("android")
    task.previewIds.set(emptyList())
    return task
  }

  /** A jar holding one empty public class named [internalName]. */
  private fun jarWithClass(fileName: String, internalName: String): File {
    val writer = ClassWriter(0)
    writer.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    writer.visitEnd()
    val jar = File(tmp.newFolder(), fileName)
    ZipOutputStream(jar.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("$internalName.class"))
      zip.write(writer.toByteArray())
      zip.closeEntry()
    }
    return jar
  }

  /** The `artifact` of every Maven entry in the bundle's `bundle.json` classpath. */
  private fun classpathArtifacts(bundle: File): List<String> {
    val manifest =
      readZipEntry(extractZipBytes(bundle.readBytes()), "bundle.json") ?: return emptyList()
    val root = Json.parseToJsonElement(manifest.decodeToString()).jsonObject
    return root["classpath"]?.jsonArray.orEmpty().mapNotNull {
      it.jsonObject["artifact"]?.jsonPrimitive?.content
    }
  }

  private fun readZipEntry(zipBytes: ByteArray, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (entry.name == name) {
          val out = zin.readBytes()
          zin.closeEntry()
          return out
        }
        zin.closeEntry()
      }
    }
    return null
  }

  /** Strip the leading PNG (the polyglot cover) so the trailing ZIP can be read. */
  private fun extractZipBytes(bytes: ByteArray): ByteArray {
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    var offset = 8 // PNG signature
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return bytes.copyOfRange(offset, bytes.size)
    }
    error("PNG IEND not found")
  }
}
