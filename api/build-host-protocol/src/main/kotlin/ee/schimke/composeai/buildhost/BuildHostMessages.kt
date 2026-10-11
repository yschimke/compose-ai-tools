package ee.schimke.composeai.buildhost

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A module that declares previews, as it crosses the wire: the mirror of [PreviewModule], whose
 * `java.io.File` is meaningless in another process. [projectDir] is its path.
 *
 * Always absolute (the server may not share the host's working directory; [from] resolves before
 * sending) and lexically normalised so `/w/project/.` and `/w/project` compare equal. Symlinks are
 * not resolved, so the path stays the one the user recognises.
 */
@Serializable
public data class WireModule(val gradlePath: String, val projectDir: String) {

  /** The in-process form, for a JVM consumer that wants the original type back. */
  public fun toPreviewModule(): PreviewModule =
    PreviewModule(gradlePath = gradlePath, projectDir = File(projectDir))

  public companion object {
    public fun from(module: PreviewModule): WireModule =
      WireModule(gradlePath = module.gradlePath, projectDir = wirePath(module.projectDir))

    /** The one place a path becomes wire-shaped, so both ends agree on what that means. */
    public fun wirePath(file: File): String = file.absoluteFile.normalize().path
  }
}

/** A module paired with the manifest its build produced. */
@Serializable
public data class WireModuleManifest(val module: WireModule, val manifest: PreviewManifest)

/**
 * What the server asks the build host to do: the seven `ServeBuildHost` operations, plus a
 * handshake. Sealed and polymorphic so an unknown request fails to deserialise rather than silently
 * defaulting when the two sides skew.
 */
@Serializable
public sealed interface BuildHostRequest {

  /** First message on the connection. The host answers [BuildHostResponse.Handshake] or fails. */
  @Serializable
  @SerialName("handshake")
  public data class Handshake(val protocolVersion: Int = BuildHostProtocol.VERSION) :
    BuildHostRequest

  /**
   * Init-script arguments to add for [projectRoot], if any — i.e. whether to inject the preview
   * plugin into a project that doesn't declare it. Only the host, holding the invocation's argv,
   * can tell an explicit `--init-script` from an injected one.
   */
  @Serializable
  @SerialName("autoInjectInitScriptArgs")
  public data class AutoInjectInitScriptArgs(val projectRoot: String) : BuildHostRequest

  /** The Gradle project root, or null when there is no `gradlew` above the host. */
  @Serializable
  @SerialName("gradleProjectRoot")
  public data object GradleProjectRoot : BuildHostRequest

  /** `-PcomposePreview.variant=…`, if the host was given `--variant`. */
  @Serializable
  @SerialName("gradleVariantArgs")
  public data object GradleVariantArgs : BuildHostRequest

  /** The build arguments this invocation implies, including `--force` and data extensions. */
  @Serializable
  @SerialName("gradleBuildArgs")
  public data class GradleBuildArgs(val extra: List<String> = emptyList()) : BuildHostRequest

  /** Every Gradle project in the build that declares previews. */
  @Serializable @SerialName("gradleProjects") public data object GradleProjects : BuildHostRequest

  /**
   * Run [tasks] in the project's Gradle build. [silenceStdout] suppresses [BuildHostEvent.Log] at
   * the host, so a long build doesn't fill a pipe nobody reads.
   */
  @Serializable
  @SerialName("runGradleTasks")
  public data class RunGradleTasks(
    val tasks: List<String>,
    val arguments: List<String> = emptyList(),
    val silenceStdout: Boolean = false,
  ) : BuildHostRequest

  /** Discover and build the selected modules so their manifests exist on disk. */
  @Serializable
  @SerialName("discoverAndBuild")
  public data class DiscoverAndBuild(val silenceStdout: Boolean = false) : BuildHostRequest
}

/** The host's answer to one [BuildHostRequest]. */
@Serializable
public sealed interface BuildHostResponse {

  @Serializable
  @SerialName("handshake")
  public data class Handshake(val protocolVersion: Int, val hostVersion: String) : BuildHostResponse

  /** Answer to the three argument-list operations. */
  @Serializable
  @SerialName("strings")
  public data class Strings(val values: List<String>) : BuildHostResponse

  /**
   * Answer to [BuildHostRequest.GradleProjectRoot]. Absolute when present, for [WireModule]'s
   * reason.
   */
  @Serializable @SerialName("path") public data class Path(val path: String?) : BuildHostResponse

  @Serializable
  @SerialName("modules")
  public data class Modules(val modules: List<WireModule>) : BuildHostResponse

  /** Answer to [BuildHostRequest.RunGradleTasks]: whether the build succeeded. */
  @Serializable
  @SerialName("buildResult")
  public data class BuildResult(val buildOk: Boolean) : BuildHostResponse

  /**
   * Answer to [BuildHostRequest.DiscoverAndBuild]. Carries the parsed manifests rather than paths,
   * since a path can't say whether this build wrote it or a previous failed run did.
   */
  @Serializable
  @SerialName("discovery")
  public data class Discovery(val buildOk: Boolean, val manifests: List<WireModuleManifest>) :
    BuildHostResponse

  /**
   * The host could not answer at all (protocol mismatch, malformed request, exception from Gradle)
   * — distinct from a build that ran and failed ([BuildResult] with `buildOk = false`). The server
   * then falls back to serving without a build host.
   */
  @Serializable
  @SerialName("failure")
  public data class Failure(val message: String) : BuildHostResponse
}

/**
 * Something the host emits while an operation is in flight. Carries that operation's id so output
 * can be attributed to its task.
 */
@Serializable
public sealed interface BuildHostEvent {

  /** One line of build output. Newline-free; the framing supplies the line break. */
  @Serializable @SerialName("log") public data class Log(val line: String) : BuildHostEvent
}

/**
 * One framed line: an id and exactly one of the three payload kinds. One envelope because there is
 * one pipe, and inferring the kind from present fields would be ambiguous.
 */
@Serializable
public data class BuildHostEnvelope(
  val id: Long,
  val request: BuildHostRequest? = null,
  val response: BuildHostResponse? = null,
  val event: BuildHostEvent? = null,
)
