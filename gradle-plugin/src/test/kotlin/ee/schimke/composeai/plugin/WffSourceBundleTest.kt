package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WffSourceBundleTest {

  @get:Rule val tmp = TemporaryFolder()

  private val sha = "2087f9f9b9c27ee3c3f5b824ff73221e413cc7c7"

  /** A checkout shaped like an imported upstream: detached HEAD, `origin`, Apache licence. */
  private fun repo(): File {
    val root = tmp.newFolder("upstream")
    File(root, ".git").mkdirs()
    File(root, ".git/HEAD").writeText("$sha\n")
    File(root, ".git/config")
      .writeText(
        """
        [core]
        	bare = false
        [remote "origin"]
        	url = https://github.com/bonstio/casino-gshark.git
        	fetch = +refs/heads/*:refs/remotes/origin/*
        """
          .trimIndent(),
      )
    File(root, "LICENSE")
      .writeText("                                 Apache License\n Version 2.0, January 2004\n")
    val res = File(root, "app/src/main/res")
    fun write(rel: String, text: String) =
      File(res, rel).apply {
        parentFile.mkdirs()
        writeText(text)
      }
    write(
      "raw/watchface_pixel.xml",
      """
      <WatchFace width="450" height="450">
        <Scene><PartImage><Image resource="hand_hour"/></PartImage>
        <PartText><Text><Font family="digital_font" size="20"/></Text></PartText></Scene>
      </WatchFace>
      """
        .trimIndent(),
    )
    write("raw/watchface.xml", """<WatchFace width="450" height="450"><Scene/></WatchFace>""")
    write(
      "xml/watch_face_info.xml",
      """<WatchFaceInfo><Preview value="@drawable/preview"/></WatchFaceInfo>""",
    )
    write(
      "xml/watch_face_shapes.xml",
      """<WatchFaces><WatchFace shape="CIRCLE" width="450" height="450" file="@raw/watchface"/></WatchFaces>""",
    )
    write("values/strings.xml", """<resources><string name="app_name">G</string></resources>""")
    write("values/integers.xml", """<resources><integer name="wff">2</integer></resources>""")
    write("drawable/hand_hour.png", "png")
    write("drawable-nodpi/preview.png", "png")
    write("drawable/unused.png", "png")
    write("font/digital_font.ttf", "ttf")
    write("layout/ignored.xml", "<x/>")
    File(root, "app/src/main/AndroidManifest.xml")
      .writeText(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
          <application android:hasCode="false">
            <property
                android:name="com.google.wear.watchface.format.version"
                android:value="@integer/wff" />
          </application>
        </manifest>
        """
          .trimIndent(),
      )
    return root
  }

  private fun entries(zip: ByteArray): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        out[e.name] = zin.readBytes().decodeToString()
      }
    }
    return out
  }

  @Test
  fun `packs the face in the standard WFF layout with only the resources it references`() {
    val root = repo()
    val module = File(root, "app")
    val packed =
      WffSourceBundle.pack(
        previewId = "dwf__raw_watchface_pixel",
        bundlePath = "previews/dwf__raw_watchface_pixel.wff.zip",
        dwfAssetPath = "raw/watchface_pixel.xml",
        resRoots = listOf(File(module, "src/main/res")),
        androidManifests = listOf(File(module, "src/main/AndroidManifest.xml")),
        provenance = WffSourceBundle.gitProvenance(module),
      )!!

    val files = entries(packed.zip)
    assertThat(files.keys)
      .containsExactly(
        "AndroidManifest.xml",
        "LICENSE",
        "res/drawable-nodpi/preview.png",
        "res/drawable/hand_hour.png",
        "res/font/digital_font.ttf",
        "res/raw/watchface.xml",
        "res/values/integers.xml",
        "res/values/strings.xml",
        "res/xml/watch_face_info.xml",
      )
      .inOrder()
    // The face is relocated to the path WFF requires; the module's other face and the shape map
    // that names both are left out.
    assertThat(files.getValue("res/raw/watchface.xml")).contains("hand_hour")

    val m = packed.manifest
    assertThat(m.wffVersion).isEqualTo(2)
    assertThat(m.repoUrl).isEqualTo("https://github.com/bonstio/casino-gshark")
    assertThat(m.commit).isEqualTo(sha)
    assertThat(m.sourcePath).isEqualTo("app/src/main/res/raw/watchface_pixel.xml")
    assertThat(m.sourceUrl)
      .isEqualTo(
        "https://github.com/bonstio/casino-gshark/blob/$sha/app/src/main/res/raw/watchface_pixel.xml",
      )
    assertThat(m.license).isEqualTo("Apache-2.0")
    assertThat(m.licenseFile).isEqualTo("LICENSE")
    assertThat(m.files).isEqualTo(files.keys.toList())

    val json = WffSourceBundle.encodeManifest(m).decodeToString()
    assertThat(json).contains("\"wffVersion\": 2")
  }

  @Test
  fun `pack is byte-stable`() {
    val root = repo()
    val module = File(root, "app")
    fun once() =
      WffSourceBundle.pack(
          "id",
          "previews/id.wff.zip",
          "raw/watchface.xml",
          listOf(File(module, "src/main/res")),
          listOf(File(module, "src/main/AndroidManifest.xml")),
          WffSourceBundle.gitProvenance(module),
        )!!
        .zip
    assertThat(once()).isEqualTo(once())
  }

  @Test
  fun `outside a git checkout provenance and licence are null, not guessed`() {
    val res = tmp.newFolder("plain", "res")
    File(res, "raw").mkdirs()
    File(res, "raw/watchface.xml").writeText("<WatchFace width=\"1\" height=\"1\"/>")
    val packed =
      WffSourceBundle.pack(
        "id",
        "previews/id.wff.zip",
        "raw/watchface.xml",
        listOf(res),
        emptyList(),
        WffSourceBundle.Provenance(),
      )!!
    with(packed.manifest) {
      assertThat(wffVersion).isNull()
      assertThat(repoUrl).isNull()
      assertThat(commit).isNull()
      assertThat(sourceUrl).isNull()
      assertThat(license).isNull()
    }
    assertThat(WffSourceBundle.encodeManifest(packed.manifest).decodeToString())
      .contains("\"license\": null")
  }

  @Test
  fun `missing face returns null`() {
    assertThat(
        WffSourceBundle.pack(
          "id",
          "p",
          "raw/nope.xml",
          listOf(tmp.newFolder("r")),
          emptyList(),
          WffSourceBundle.Provenance(),
        ),
      )
      .isNull()
  }

  @Test
  fun `branch HEAD resolves through loose and packed refs`() {
    val root = tmp.newFolder("branch")
    File(root, ".git/refs/heads").mkdirs()
    File(root, ".git/HEAD").writeText("ref: refs/heads/main\n")
    File(root, ".git/packed-refs").writeText("# pack-refs\n$sha refs/heads/main\n")
    assertThat(WffSourceBundle.gitProvenance(root).commit).isEqualTo(sha)
    File(root, ".git/refs/heads/main").writeText("${"a".repeat(40)}\n")
    assertThat(WffSourceBundle.gitProvenance(root).commit).isEqualTo("a".repeat(40))
  }

  @Test
  fun `remote URLs normalise to credential-free https`() {
    assertThat(WffSourceBundle.normalizeRemoteUrl("git@github.com:bonstio/casino-gshark.git"))
      .isEqualTo("https://github.com/bonstio/casino-gshark")
    assertThat(WffSourceBundle.normalizeRemoteUrl("https://x-access-token:abc@github.com/o/r.git"))
      .isEqualTo("https://github.com/o/r")
    assertThat(WffSourceBundle.normalizeRemoteUrl("ssh://git@github.com:22/o/r"))
      .isEqualTo("https://github.com/o/r")
    assertThat(WffSourceBundle.normalizeRemoteUrl("/home/me/repo")).isNull()
  }

  @Test
  fun `spdx detection`() {
    assertThat(
        WffSourceBundle.detectSpdx(
          "MIT License\n\nPermission is hereby granted, free of charge, to any",
        ),
      )
      .isEqualTo("MIT")
    val bsd = "Redistribution and use in source and binary forms, with or without modification"
    assertThat(WffSourceBundle.detectSpdx(bsd)).isEqualTo("BSD-2-Clause")
    assertThat(WffSourceBundle.detectSpdx("$bsd. Neither the name of the copyright holder"))
      .isEqualTo("BSD-3-Clause")
    assertThat(WffSourceBundle.detectSpdx("Creative Commons CC0 1.0 Universal"))
      .isEqualTo("CC0-1.0")
    assertThat(
        WffSourceBundle.detectSpdx(
          "This is free and unencumbered software released into the public domain.",
        ),
      )
      .isEqualTo("Unlicense")
    assertThat(
        WffSourceBundle.detectSpdx("Creative Commons Attribution 4.0 International Public License"),
      )
      .isEqualTo("CC-BY-4.0")
    assertThat(
        WffSourceBundle.detectSpdx(
          "Creative Commons Attribution-NonCommercial 4.0 International Public License",
        ),
      )
      .isNull()
    assertThat(WffSourceBundle.detectSpdx("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007"))
      .isEqualTo("GPL-3.0")
    assertThat(WffSourceBundle.detectSpdx("All rights reserved.")).isNull()
  }
}
