package ee.schimke.composeai.plugin

import java.util.Properties
import org.gradle.api.Project

/**
 * Opt-in theme pinning: a catalog's selected theme recolours previews that install their own theme.
 *
 * A theme provider wraps a preview from the outside, but an app preview usually installs its own
 * theme further in (`AppScaffold { AppTheme { … } }`), and the innermost `MaterialTheme` wins — the
 * case [ee.schimke.composeai.discovery.PreviewThemeShadowing] warns about. With pinning on, two
 * artifacts reverse that precedence:
 * - `theme-pin-compiler-plugin` points the module's own calls to Material 3 `MaterialTheme` at
 *   `PreviewMaterialTheme` (same JVM descriptor, so only the call's owner changes), and
 * - `theme-pin-runtime` supplies `PreviewMaterialTheme`, which prefers a colour scheme a generated
 *   theme provider has pinned with `PinMaterialTheme`.
 *
 * **Opt-in, and never on a shipped build.** The compiler plugin rewrites the module's own call
 * sites, so it is attached only when `composePreview.themePinning=true` — which the import pipeline
 * sets in its throwaway checkout of somebody else's project. A first-party build that applies this
 * plugin is unchanged.
 *
 * **Kotlin version gate.** A compiler plugin links against compiler internals, which change between
 * Kotlin lines. It is attached only when the consumer's Kotlin is on the line it was built against
 * (`themePinKotlin` in `plugin-version.properties`, baked from the catalog); anything else logs a
 * warning and renders without pinning rather than risk a compiler crash.
 *
 * **Paired per compilation.** Only the runtime classpath needs `theme-pin-runtime` — the module's
 * source never names it — so it rides on the runtime-only bucket of each JVM / Android compilation,
 * and the compiler plugin is attached to exactly those compilations. A compilation that would get
 * one without the other is skipped, so a redirected call can never meet a missing method.
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

  /** The Kotlin version `theme-pin-compiler-plugin` was compiled against. */
  val compilerPluginKotlin: String by lazy {
    val props = Properties()
    ThemePinning::class
      .java
      .classLoader
      .getResourceAsStream("ee/schimke/composeai/plugin/plugin-version.properties")
      ?.use { props.load(it) }
    props.getProperty("themePinKotlin")
      ?: error("themePinKotlin missing from plugin-version.properties")
  }

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
      runtime = artifact(project, RUNTIME_ARTIFACT),
      compilerPlugin = artifact(project, COMPILER_PLUGIN_ARTIFACT),
    )
  }

  /** Adds [runtime] and [compilerPlugin] to each paired render compilation — see [pairings]. */
  internal fun wire(project: Project, multiplatform: Boolean, runtime: Any, compilerPlugin: Any) {
    val pairs = pairings(multiplatform)
    // The two halves of a pair are created by different parts of KGP, in no promised order, so
    // whichever arrives second completes the pair. The compiler plugin is attached only once its
    // runtime bucket exists: a compilation can never be redirected without the method it is
    // redirected to.
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
   * `(runtime-only bucket, compiler-plugin classpath configurations)` per render compilation.
   *
   * A plain JVM or Android module has one `runtimeOnly` feeding every compilation, so every
   * compiler plugin classpath it has pairs with it. A KMP module pairs each JVM / Android target's
   * `<target>MainRuntimeOnly` with that target's `kotlinCompilerPluginClasspath<Target>Main` — and
   * nothing else: a native, JS or Wasm compilation, or a JVM target under another name, gets
   * neither.
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

  /**
   * The published coordinate at this plugin's version — or, inside the compose-ai-tools build
   * itself, where the artifact is a sibling project, that project (the same rule as the local
   * `:daemon:desktop` lookup in `registerDesktopDaemonStartTask`).
   */
  private fun artifact(project: Project, artifactId: String): Any =
    project.findProject(":$artifactId")?.let {
      project.dependencies.project(mapOf("path" to it.path))
    } ?: "ee.schimke.composeai:$artifactId:${PluginVersion.value}"

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
