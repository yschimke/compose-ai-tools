package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Android aspect the CLI installs for a workspace without `@rules_compose_preview`
 * (compose-ai-tools#5945): verbatim rules files, generated provider shims, and an install that
 * upgrades its own copies but never a hand-edited one.
 */
class BazelAndroidAspectTest {
  private fun workspace(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-android-aspect").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun resource(name: String): ByteArray =
    requireNotNull(BazelCommand::class.java.getResourceAsStream("bazel/android/$name")) { name }
      .use { it.readBytes() }

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun `the bundled rules files are exactly the release provenance names`() {
    val provenance =
      bazelJson.parseToJsonElement(resource("provenance.json").decodeToString()).jsonObject
    val hashes = provenance.getValue("sha256").jsonObject
    assertEquals(
      setOf("android.bzl", "android_providers.bzl", "java_info.bzl", "runtime.bzl", "link.sh"),
      hashes.keys,
    )
    hashes.forEach { (name, hash) ->
      assertEquals(hash.jsonPrimitive.content, sha256(resource(name)), "$name was edited by hand")
    }
  }

  @Test
  fun `install writes the package and records what it wrote`() = workspace { root ->
    assertEquals(emptyList(), installBazelAndroidAspect(root))
    val dir = root.resolve(BAZEL_ANDROID_ASPECT_DIR)
    listOf(
        "BUILD.bazel",
        "android.bzl",
        "android_providers.bzl",
        "java_info.bzl",
        "runtime.bzl",
        "link.sh",
      )
      .forEach { assertEquals(resource(it).decodeToString(), dir.resolve(it).readText(), it) }
    val record =
      bazelJson.parseToJsonElement(dir.resolve("installed.json").readText()) as JsonObject
    assertEquals(
      sha256(dir.resolve("android.bzl").readBytes()),
      record.getValue("android.bzl").jsonPrimitive.content,
    )
  }

  @Test
  fun `a copy an earlier CLI wrote is upgraded, a hand edit is kept`() = workspace { root ->
    installBazelAndroidAspect(root)
    val dir = root.resolve(BAZEL_ANDROID_ASPECT_DIR)
    // What an older CLI would have installed and recorded.
    val older = "# an older android.bzl\n"
    dir.resolve("android.bzl").writeText(older)
    val record =
      bazelJson.parseToJsonElement(dir.resolve("installed.json").readText()) as JsonObject
    dir
      .resolve("installed.json")
      .writeText(
        JsonObject(
            record +
              ("android.bzl" to
                kotlinx.serialization.json.JsonPrimitive(sha256(older.toByteArray())))
          )
          .toString()
      )
    // A local edit no CLI wrote.
    dir.resolve("link.sh").appendText("# local tweak\n")

    val kept = installBazelAndroidAspect(root)

    assertEquals(resource("android.bzl").decodeToString(), dir.resolve("android.bzl").readText())
    assertEquals(listOf(dir.resolve("link.sh")), kept)
    assertTrue(dir.resolve("link.sh").readText().endsWith("# local tweak\n"))
    assertFailsWith<IllegalArgumentException> { installBazelAndroidAspect(root, strict = true) }
  }

  @Test
  fun `providers generate the shims and a later change regenerates them`() = workspace { root ->
    val providers =
      mapOf(
        "JavaInfo" to "//tools/java:providers.bzl%InternalJavaInfo",
        "JDK_RUNTIME" to "//tools/jdk:runtime_17",
        "AndroidIdeInfo" to "//tools/android:providers.bzl",
        "ANDROID_SDK_TOOLCHAIN_TYPE" to "//tools/android:sdk_toolchain_type",
      )
    installBazelAndroidAspect(root, providers)
    val dir = root.resolve(BAZEL_ANDROID_ASPECT_DIR)
    assertEquals(
      GENERATED_SHIM_HEADER +
        "load(\"//tools/java:providers.bzl\", _JavaInfo = \"InternalJavaInfo\")\n" +
        "load(\"@rules_java//java/common/rules:java_runtime.bzl\", _JavaRuntimeInfo = \"JavaRuntimeInfo\")\n" +
        "\n" +
        "JavaInfo = _JavaInfo\n" +
        "JavaRuntimeInfo = _JavaRuntimeInfo\n" +
        "JDK_RUNTIME = Label(\"//tools/jdk:runtime_17\")\n",
      dir.resolve("java_info.bzl").readText(),
    )
    val android = dir.resolve("android_providers.bzl").readText()
    assertTrue(
      "load(\"//tools/android:providers.bzl\", _AndroidIdeInfo = \"AndroidIdeInfo\")" in android,
      android,
    )
    assertTrue(
      "ANDROID_SDK_TOOLCHAIN_TYPE = Label(\"//tools/android:sdk_toolchain_type\")" in android,
      android,
    )
    // Changing the providers regenerates the shim; dropping them restores the rules' own file.
    installBazelAndroidAspect(root, providers + ("JDK_RUNTIME" to "//tools/jdk:runtime_21"))
    assertTrue("runtime_21" in dir.resolve("java_info.bzl").readText())
    installBazelAndroidAspect(root)
    assertEquals(
      resource("java_info.bzl").decodeToString(),
      dir.resolve("java_info.bzl").readText(),
    )
  }

  @Test
  fun `an unknown provider or a non-bzl load is refused`() = workspace { root ->
    assertFailsWith<IllegalArgumentException> {
      installBazelAndroidAspect(root, mapOf("JavaInfos" to "//x.bzl"))
    }
    assertFailsWith<IllegalArgumentException> {
      installBazelAndroidAspect(root, mapOf("JavaInfo" to "//tools/java:providers"))
    }
  }

  @Test
  fun `the bundled Android aspect selects Android target kinds`() {
    assertEquals(
      defaultTargetKinds(DEFAULT_BAZEL_ANDROID_ASPECT),
      defaultTargetKinds("@rules_compose_preview//:android.bzl%compose_preview_android_aspect"),
    )
  }
}
