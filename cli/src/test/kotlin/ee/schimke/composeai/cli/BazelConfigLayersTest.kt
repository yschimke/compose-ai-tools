package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelConfigLayersTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-config-layers").toFile().canonicalFile
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private val classpath =
    """
    {
      "buildCommand": ["dist-bazel"],
      "buildArgs": ["--config=dist"],
      "queryArgs": ["--noimplicit_deps"],
      "digestXattr": "dist.digest",
      "target": "//dist:default"
    }
    """

  @Test
  fun `upstream bundles no defaults resource`() {
    assertNull(bundledBazelDefaults())
    assertTrue(loadBazelConfigLayers(null, envConfig = null).layers.isEmpty())
    assertEquals(BazelBuildConfig(), loadBazelConfigLayers(null, envConfig = null).config)
  }

  @Test
  fun `layers merge key by key, classpath then env file then workspace`() = fixture { root ->
    val env =
      root.resolve("env.json").apply {
        writeText("""{"buildArgs": ["--config=env"], "digestXattr": "env.digest"}""")
      }
    val workspace = root.resolve(".compose-preview/bazel.json")
    workspace.parentFile.mkdirs()
    workspace.writeText("""{"target": "//app:ui"}""")

    val layers = loadBazelConfigLayers(workspace, env.path, classpath)
    val config = layers.config
    // Workspace wins where it speaks, and inherits every other key.
    assertEquals("//app:ui", config.target)
    // The env file replaces the classpath value of a key it sets (a whole list, not appended).
    assertEquals(listOf("--config=env"), config.buildArgs)
    assertEquals("env.digest", config.digestXattr)
    // Keys only the classpath sets come through.
    assertEquals(listOf("dist-bazel"), config.buildCommand)
    assertEquals(listOf("--noimplicit_deps"), config.queryArgs)

    assertEquals(3, layers.layers.size)
    assertTrue(layers.sourceOf("buildCommand")!!.source.contains(BAZEL_DEFAULTS_RESOURCE))
    assertTrue(layers.sourceOf("buildArgs")!!.source.contains(BAZEL_CONFIG_ENV))
    assertEquals(workspace.path, layers.sourceOf("target")!!.source)
    assertTrue(layers.setByWorkspace("target"))
    assertFalse(layers.setByWorkspace("buildCommand"))
    assertNull(layers.sourceOf("aspect"))
  }

  @Test
  fun `workspace overrides defaults and a missing workspace file keeps them`() = fixture { root ->
    val workspace = root.resolve("bazel.json")
    val defaultsOnly = loadBazelConfigLayers(workspace, envConfig = null, classpath)
    assertEquals(listOf("dist-bazel"), defaultsOnly.config.buildCommand)
    assertEquals("//dist:default", defaultsOnly.config.target)
    assertFalse(defaultsOnly.setByWorkspace("buildCommand"))

    workspace.writeText("""{"buildCommand": ["bazelisk"], "buildArgs": []}""")
    val overridden = loadBazelConfigLayers(workspace, envConfig = null, classpath)
    assertEquals(listOf("bazelisk"), overridden.config.buildCommand)
    assertEquals(emptyList(), overridden.config.buildArgs)
    assertEquals("//dist:default", overridden.config.target)
    assertTrue(overridden.setByWorkspace("buildCommand"))
  }

  @Test
  fun `env defaults alone are a layer and a missing env file fails`() = fixture { root ->
    val env =
      root.resolve("env.json").apply { writeText("""{"providers": {"javaInfo": "//x.bzl"}}""") }
    val layers = loadBazelConfigLayers(null, env.path, classpathDefaults = null)
    assertEquals(1, layers.layers.size)
    assertTrue(layers.merged.containsKey("providers"))
    assertEquals(BazelBuildConfig(), loadBazelConfigLayers(null, "  ", null).config)
    val missing =
      assertFailsWith<IllegalArgumentException> {
        loadBazelConfigLayers(null, root.resolve("absent.json").path, null)
      }
    assertTrue(missing.message!!.contains(BAZEL_CONFIG_ENV))
  }

  @Test
  fun `a layer that is not a JSON object is rejected`() {
    assertFailsWith<IllegalStateException> {
      loadBazelConfigLayers(null, envConfig = null, classpathDefaults = "[]")
    }
  }
}
