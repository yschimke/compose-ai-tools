package ee.schimke.composeai.plugin

import java.util.Properties
import org.gradle.api.Project

/**
 * Opt-in theme pinning: a catalog's selected theme recolours previews that install their own theme.
 * The innermost `MaterialTheme` normally wins over the wrapping provider (see
 * [ee.schimke.composeai.discovery.PreviewThemeShadowing]); with pinning:
 * - `theme-pin-compiler-plugin` redirects the module's calls to M3 `MaterialTheme` to
 *   `PreviewMaterialTheme` (same descriptor, different owner), and
 * - `theme-pin-runtime` supplies `PreviewMaterialTheme`, which prefers a scheme pinned via
 *   `PinMaterialTheme`.
 *
 * **Opt-in, never on a shipped build:** only with `composePreview.themePinning=true`, which the
 * import pipeline sets in throwaway checkouts.
 *
 * **Kotlin version gate:** compiler plugins link against compiler internals, so it's attached only
 * on the Kotlin line it was built for (`themePinKotlin`), otherwise warn and skip.
 *
 * **Paired per compilation:** the runtime goes on each JVM / Android compilation's runtime-only
 * bucket and the compiler plugin on exactly those compilations, so a redirected call never lacks
 * its target.
 */
internal object ThemePinning {
  const val PROPERTY = "composePreview.themePinning"
  const val RUNTIME_ARTIFACT = "theme-pin-runtime"
  const val COMPILER_PLUGIN_ARTIFACT = "theme-pin-compiler-plugin"

  private const val COMPILER_PLUGIN_CLASSPATH = "kotlinCompilerPluginClasspath"

  /** KMP targets whose main compilation runs on a JVM / Android render lane. */
  private val KMP_RENDER_TARGETS = listOf("jvm", "desktop", "android")

  private val KOTLIN_PLUGIN_IDS =
    listOf(
      "org.jetbrains.kotlin.multiplatform",
      "org.jetbrains.kotlin.jvm",
      "org.jetbrains.kotlin.android",
    )

  private val versions: Properties by lazy {
    Properties().apply {
      ThemePinning::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/plugin/plugin-version.properties")
        ?.use { load(it) }
    }
  }

  private fun baked(key: String): String =
    versions.getProperty(key) ?: error("$key missing from plugin-version.properties")

  /** The Kotlin version `theme-pin-compiler-plugin` was compiled against. */
  val compilerPluginKotlin: String by lazy { baked("themePinKotlin") }

  /**
   * The versions the pair is published at, which selective publishing lets differ from this
   * plugin's: a release that changes the plugin but not the pair leaves the pair where it was.
   */
  val runtimeVersion: String by lazy { baked("themePinRuntime") }
  val compilerPluginVersion: String by lazy { baked("themePinCompilerPlugin") }

  fun apply(project: Project) {
    val enabled =
      project.providers.gradleProperty(PROPERTY).map { it.trim().toBoolean() }.getOrElse(false)
    if (!enabled) return
    var configured = false
    KOTLIN_PLUGIN_IDS.forEach { id ->
      project.pluginManager.withPlugin(id) {
        if (configured) return@withPlugin
        configured = true
        configure(project, multiplatform = id == "org.jetbrains.kotlin.multiplatform")
      }
    }
  }

  private fun configure(project: Project, multiplatform: Boolean) {
    val consumerKotlin = kotlinVersion(project)
    if (!isSupported(consumerKotlin, compilerPluginKotlin)) {
      project.logger.warn(
        "compose-preview: theme pinning skipped for ${project.path} — it uses Kotlin " +
          "${consumerKotlin ?: "(unknown)"}, and the theme-pin compiler plugin is built for Kotlin " +
          "${kotlinLine(compilerPluginKotlin)}.x. Previews render with their own theme."
      )
      return
    }
    wire(
      project,
      multiplatform,
      runtime = artifact(project, RUNTIME_ARTIFACT, runtimeVersion),
      compilerPlugin = artifact(project, COMPILER_PLUGIN_ARTIFACT, compilerPluginVersion),
    )
  }

  /** Adds [runtime] and [compilerPlugin] to each paired render compilation — see [pairings]. */
  internal fun wire(project: Project, multiplatform: Boolean, runtime: Any, compilerPlugin: Any) {
    val pairs = pairings(multiplatform)
    // KGP creates the two halves in no promised order; whichever arrives second completes the pair.
    val attached = mutableSetOf<String>()
    fun attach(pluginClasspath: String) {
      if (attached.add(pluginClasspath)) project.dependencies.add(pluginClasspath, compilerPlugin)
    }
    project.configurations.configureEach {
      val name = this.name
      pairs.forEach { (runtimeOnly, pluginClasspath) ->
        if (name == runtimeOnly) {
          project.dependencies.add(name, runtime)
          project.configurations.names.filter { pluginClasspath.matches(it) }.forEach(::attach)
        }
        if (
          pluginClasspath.matches(name) && project.configurations.findByName(runtimeOnly) != null
        ) {
          attach(name)
        }
      }
    }
  }

  /**
   * `(runtime-only bucket, compiler-plugin classpath configurations)` per render compilation. Plain
   * JVM / Android modules pair one `runtimeOnly` with every compiler plugin classpath; KMP pairs
   * each JVM / Android target's `<target>MainRuntimeOnly` with its
   * `kotlinCompilerPluginClasspath<Target>Main`, and nothing else.
   */
  internal fun pairings(multiplatform: Boolean): List<Pair<String, Regex>> =
    if (!multiplatform) {
      listOf("runtimeOnly" to Regex("$COMPILER_PLUGIN_CLASSPATH.*"))
    } else {
      KMP_RENDER_TARGETS.map { target ->
        val capitalised = target.replaceFirstChar { it.uppercaseChar() }
        "${target}MainRuntimeOnly" to
          Regex(Regex.escape("$COMPILER_PLUGIN_CLASSPATH${capitalised}Main"))
      }
    }

  /** The published coordinate at [version], or the sibling project inside this build. */
  private fun artifact(project: Project, artifactId: String, version: String): Any =
    project.findProject(":$artifactId")?.let {
      project.dependencies.project(mapOf("path" to it.path))
    } ?: "ee.schimke.composeai:$artifactId:$version"

  /** True when [consumer] and [builtFor] are on the same `major.minor` Kotlin line. */
  internal fun isSupported(consumer: String?, builtFor: String): Boolean =
    consumer != null && kotlinLine(consumer) == kotlinLine(builtFor)

  private fun kotlinLine(version: String): String = version.split('.').take(2).joinToString(".")

  /** The consumer's Kotlin Gradle plugin version, by reflection: no compile dependency on KGP. */
  private fun kotlinVersion(project: Project): String? =
    try {
      Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt")
        .getMethod("getKotlinPluginVersion", Project::class.java)
        .invoke(null, project) as? String
    } catch (_: Throwable) {
      null
    }
}
