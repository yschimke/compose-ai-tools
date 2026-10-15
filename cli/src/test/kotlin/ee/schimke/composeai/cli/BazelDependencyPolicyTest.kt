package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.producer.DependencyPolicy
import ee.schimke.composeai.bundle.producer.DependencyPolicyStep
import ee.schimke.composeai.bundle.producer.MavenMetadataVersions
import ee.schimke.composeai.bundle.producer.ReleasedVersions
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BazelDependencyPolicyTest {
  private fun jar(file: File): File = file.apply {
    parentFile.mkdirs()
    ZipOutputStream(outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("A.class"))
      zip.write(byteArrayOf(1))
      zip.closeEntry()
    }
  }

  @Test
  fun `bazel json carries an optional dependencyPolicy`() {
    val absent = bazelJson.decodeFromString<BazelBuildConfig>("""{"target":"//app"}""")
    assertEquals(null, absent.dependencyPolicy)
    val config =
      bazelJson.decodeFromString<BazelBuildConfig>(
        """{"target":"//app","dependencyPolicy":{"default":["coordinate-verified","embed"],
           "rules":[{"match":"androidx.*","policy":["closest-released","embed"]}]}}"""
      )
    val root = Files.createTempDirectory("bazel-policy").toFile()
    try {
      val artifacts = resolvedArtifacts(root)
      val options =
        bazelDependencyOptions(
          config.dependencyPolicy,
          artifacts,
          networkDisabled = false,
          offline = true,
          cacheDir = root,
        )
      assertEquals(DependencyPolicyStep.COORDINATE_VERIFIED, options.policy.default.first())
      assertTrue(options.releasedVersions is MavenMetadataVersions)
      assertEquals("@maven//:lib", options.runtimeJarOwners.values.single())
      // No policy: the original behaviour, whatever the network.
      assertSame(
        DependencyPolicy.DEFAULT,
        bazelDependencyOptions(null, artifacts, networkDisabled = false, offline = false, root)
          .policy,
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `under COMPOSE_PREVIEW_NO_NETWORK only lockfile-pinned versions count`() {
    val root = Files.createTempDirectory("bazel-policy").toFile()
    try {
      val options =
        bazelDependencyOptions(
          null,
          resolvedArtifacts(root),
          networkDisabled = true,
          offline = true,
          cacheDir = root,
        )
      assertSame(ReleasedVersions.NONE, options.releasedVersions)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `runtimeJarOwners are read raw and only for listed runtime jars`() {
    val text =
      """{"schemaVersion":1,"target":"//app","configuration":"k8","projectJars":["app.jar"],
          "runtimeJars":["app.jar","external/maven/lib.jar"],
          "runtimeJarOwners":{"external/maven/lib.jar":"@maven//:lib","bazel-out/other.jar":"//x:y"}}"""
    assertEquals(2, bazelRuntimeJarOwners(text).size)
    assertTrue(bazelRuntimeJarOwners("""{"schemaVersion":1}""").isEmpty())
    val root = Files.createTempDirectory("bazel-owners").toFile()
    try {
      val owners = resolvedArtifacts(root, text).runtimeJarOwners
      assertEquals(
        mapOf(root.resolve("external/maven/lib.jar").absoluteFile to "@maven//:lib"),
        owners,
      )
    } finally {
      root.deleteRecursively()
    }
  }

  private fun resolvedArtifacts(
    root: File,
    text: String =
      """{"schemaVersion":1,"target":"//app","configuration":"k8","projectJars":["app.jar"],
          "runtimeJars":["app.jar","external/maven/lib.jar"],
          "runtimeJarOwners":{"external/maven/lib.jar":"@maven//:lib"}}""",
  ): ResolvedBazelArtifacts {
    jar(root.resolve("app.jar"))
    jar(root.resolve("external/maven/lib.jar"))
    return bazelJson
      .decodeFromString<BazelArtifacts>(text)
      .resolve(root, runtimeJarOwners = bazelRuntimeJarOwners(text))
  }
}
