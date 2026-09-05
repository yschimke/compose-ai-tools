package ee.schimke.composeai.buildhost

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A module that declares previews, as it crosses the wire.
 *
 * The mirror of [PreviewModule], and it exists for one reason: that type carries a `java.io.File`,
 * and a file handle is meaningless in another process. [projectDir] is its path.
 *
 * **Absolute and normalised, always.** The build host runs in the user's project and the server may
 * not share its working directory — it can be started from anywhere, and in the deployed case is
 * not started by a human at all. A relative path would resolve against whichever process happened
 * to read it, which is the kind of bug that reproduces on one machine in ten. [from] resolves
 * before sending rather than trusting the caller, so a relative `projectDir` cannot reach the wire
 * even if one is constructed.
 *
 * Normalised because absolute is not enough to compare: the CLI's project-root discovery
 * legitimately produces paths like `/w/project/.`, which is the same directory as `/w/project` and
 * not the same string. A server keying anything by module directory would see two. [normalize] is
 * lexical, so unlike `canonicalPath` it does not resolve symlinks — a project reached through a
 * symlinked home keeps the path the user recognises.
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
 * What the server asks the build host to do — the seven `ServeBuildHost` operations, plus a
 * handshake.
 *
 * Sealed and polymorphic: a request the host does not know is a deserialisation failure, which is
 * the behaviour worth having. The alternative — a string `op` field with a `when` — turns an
 * unknown operation into a silent default at exactly the moment the two sides have skewed.
 */
@Serializable
public sealed interface BuildHostRequest {

  /** First message on the connection. The host answers [BuildHostResponse.Handshake] or fails. */
  @Serializable
  @SerialName("handshake")
  public data class Handshake(val protocolVersion: Int = BuildHostProtocol.VERSION) :
    BuildHostRequest

  /**
   * Init-script arguments to add for [projectRoot], if any.
   *
   * Build work despite reading like a flag: it decides whether to inject the preview plugin into a
   * project that does not declare it. The host holds the invocation's own argv, which is what lets
   * it tell an explicit `--init-script` from an injected one; the server cannot compute this.
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
   * Run [tasks] in the project's Gradle build.
   *
   * [silenceStdout] is carried rather than applied by the host: it decides whether
   * [BuildHostEvent.Log] events are emitted at all. Dropping them here rather than at the server
   * keeps a long build from writing megabytes into a pipe nobody reads.
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
   * Answer to [BuildHostRequest.DiscoverAndBuild].
   *
   * Carries the manifests rather than paths to them. Both processes are on one machine and could
   * share the files, but a path says nothing about *when* it was written — the server would have no
   * way to tell a manifest this build produced from one left by a previous run that failed. Sending
   * the parsed value makes the answer self-contained, and `PreviewManifest` is already
   * `@Serializable` because it is written to disk in this same shape.
   */
  @Serializable
  @SerialName("discovery")
  public data class Discovery(val buildOk: Boolean, val manifests: List<WireModuleManifest>) :
    BuildHostResponse

  /**
   * The operation could not be performed.
   *
   * Distinct from a build that ran and failed — that is [BuildResult] with `buildOk = false`. This
   * is the host being unable to answer at all: a protocol mismatch, a malformed request, or an
   * exception escaping the Gradle call. The distinction matters because the server's response
   * differs: a failed build is shown to the user, a failed host means fall back to serving without
   * one.
   */
  @Serializable
  @SerialName("failure")
  public data class Failure(val message: String) : BuildHostResponse
}

/**
 * Something the host emits while an operation is in flight, rather than in answer to one.
 *
 * Events carry the id of the operation that produced them, so a server showing build progress can
 * attribute a line to the task it came from.
 */
@Serializable
public sealed interface BuildHostEvent {

  /** One line of build output. Newline-free; the framing supplies the line break. */
  @Serializable @SerialName("log") public data class Log(val line: String) : BuildHostEvent
}

/**
 * One framed line: an id, and exactly one of the three payload kinds.
 *
 * A single envelope type rather than three streams because there is one pipe, and the alternative —
 * inferring the kind from which fields are present — is the ambiguity this protocol exists to
 * avoid.
 */
@Serializable
public data class BuildHostEnvelope(
  val id: Long,
  val request: BuildHostRequest? = null,
  val response: BuildHostResponse? = null,
  val event: BuildHostEvent? = null,
)
