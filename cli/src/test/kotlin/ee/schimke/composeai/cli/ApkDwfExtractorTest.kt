package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkDwfExtractorTest {

  @Test
  fun classifiesWatchFaceFormatPropertyAsWear() {
    val signals =
      ManifestSignals(
        packageName = "com.example.watchface",
        properties = setOf(ApkFormFactor.WATCH_FACE_FORMAT_PROPERTY),
      )
    assertEquals(ApkFormFactor.WEAR, ApkFormFactor.classify(signals, emptySequence()))
  }

  @Test
  fun detectsDeclarativeWatchFaceInApkWithRawWatchFaceXml() {
    val dir = Files.createTempDirectory("apk-dwf-test").toFile()
    try {
      val apk = File(dir, "watchface.apk")
      ZipOutputStream(apk.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("res/raw/watchface.xml"))
        zos.write(
          """
          <WatchFace width="450" height="450">
            <Scene backgroundColor="#FF000000"/>
          </WatchFace>
          """
            .trimIndent()
            .toByteArray(),
        )
        zos.closeEntry()
      }
      assertTrue(ApkDwfExtractor.hasDeclarativeWatchFace(apk))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun ignoresPlainPhoneApkWithoutWatchFaceResources() {
    val dir = Files.createTempDirectory("apk-plain-test").toFile()
    try {
      val apk = File(dir, "phone.apk")
      ZipOutputStream(apk.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("res/raw/config.xml"))
        zos.write("<config><item value=\"1\"/></config>".toByteArray())
        zos.closeEntry()
      }
      assertFalse(ApkDwfExtractor.hasDeclarativeWatchFace(apk))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun decodesPlainTextWatchFaceXml() {
    val xml = "<WatchFace width=\"450\" height=\"450\"><Scene/></WatchFace>"
    val decoded = ApkDwfExtractor.decodeXmlOrNull(xml.toByteArray())
    assertNotNull(decoded)
    assertTrue(decoded.contains("<WatchFace"))
  }

  @Test
  fun simulatorCommandIncludesWatchfaceAndDwfDirProperties() {
    val cmd =
      LaunchCommand.simulatorCommand(
        java = File("/usr/bin/java"),
        classpath = listOf(File("/tmp/simulator-host.jar")),
        apk = File("/tmp/watchface.apk"),
        classes = emptyList(),
        options =
          LaunchCommand.Options(
            sdk = 35,
            qualifiers = ApkFormFactor.WEAR.qualifiers,
            formFactor = ApkFormFactor.WEAR.id,
            fontScale = null,
            headless = true,
            control = true,
            installProviders = true,
            launcher = true,
            watchface = "com.example.watchface/watchface",
            properties = mapOf("composeai.simulator.dwfDir" to "/tmp/dwf-cache"),
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.watchface=com.example.watchface/watchface" in cmd)
    assertTrue("-Dcomposeai.simulator.dwfDir=/tmp/dwf-cache" in cmd)
    assertTrue("-Dcomposeai.simulator.launcher=true" in cmd)
  }
}
