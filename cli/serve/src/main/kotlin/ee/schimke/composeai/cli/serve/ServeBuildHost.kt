package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File

/**
 * The build the server asks for, without naming a single Gradle type.
 *
 * Split out of [ServeOptions], which had grown to 99 members carrying two different things. Ninety-
 * one of those are flags — values the command parsed. These seven are not options at all: they are
 * work the CLI does *on the server's behalf*, and the difference matters more than the member
 * count. An option is answered once at startup; a build is invoked, can fail, and takes time.
 *
 * Saying so separately also makes the real question visible. A preview server that no longer has a
 * Gradle build behind it — one serving only fetched bundles and catalogs, which is already a
 * supported mode — does not need a different `ServeOptions`; it needs a different implementation of
 * *this*, and there are seven methods to write rather than ninety-nine to read.
 *
 * **Nothing here names a Gradle type, and that is load-bearing.** The obvious seam would be to hand
 * the server `Command.withGradle` / `runGradle`, but both expose `GradleConnection`, which lives in
 * `:gradle-preview-driver` behind `api("org.gradle:gradle-tooling-api")`. Either on this interface
 * would drop the Gradle Tooling API onto the preview server's floor — exactly the dependency #4599
 * removed, and exactly what would stop the server being separable. So this is the *operations*, not
 * the connection: every type below is already on the server's floor (`File`, `String`, and
 * `PreviewModule` from `:preview-data-api`), and the Tooling API stays on the `:cli` side.
 *
 * The names differ from `Command`'s protected `findProjectRoot` / `variantGradleArgs` /
 * `gradleArgsWithForce` on purpose: `ServeCommand` inherits both sets, and a public interface
 * member cannot implement a protected one of the same name. The rename is what lets the command
 * satisfy this contract by *delegating* to its own base class rather than re-exposing it.
 */
public interface ServeBuildHost {

  /**
   * The init-script arguments to add for [projectRoot], if any.
   *
   * Build work, despite reading like a flag: it decides whether to inject the preview plugin into a
   * project that does not declare it. `:cli` binds the raw `args` here so it can tell an explicit
   * `--init-script` from an injected one, which is why the server cannot compute it itself.
   */
  public fun autoInjectInitScriptArgs(projectRoot: File): List<String>

  /** The Gradle project root for this invocation, or null when there is no `gradlew` above us. */
  public fun gradleProjectRoot(): File?

  /** `-PcomposePreview.variant=…`, if `--variant` was given. */
  public fun gradleVariantArgs(): List<String>

  /** The build arguments this invocation implies, including `--force` and data extensions. */
  public fun gradleBuildArgs(extra: List<String> = emptyList()): List<String>

  /** Every Gradle project in the build that declares previews. */
  public fun gradleProjects(): List<PreviewModule>

  /** Run [tasks] in the project's Gradle build; true when the build succeeded. */
  public fun runGradleTasks(
    vararg tasks: String,
    arguments: List<String> = emptyList(),
    silenceStdout: Boolean = false,
  ): Boolean

  /** Discover and build the selected modules so their manifests exist on disk. */
  public fun discoverAndBuild(silenceStdout: Boolean): ServeDiscovery
}
