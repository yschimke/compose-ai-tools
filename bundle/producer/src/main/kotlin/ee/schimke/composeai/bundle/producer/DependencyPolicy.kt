package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.bundle.BundleReader
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One step of a [DependencyPolicy] fallback chain (#6016): how a runtime jar is carried in a
 * bundle. Steps are tried in order; the first that applies decides the classpath entry. A chain
 * that runs out embeds the jar, which is always correct.
 */
public enum class DependencyPolicyStep(public val id: String) {
  /** The identified coordinate, as-is; a checksum mismatch is only recorded. Today's behaviour. */
  COORDINATE("coordinate"),

  /**
   * The identified coordinate only when that version is published and the built bytes match the
   * published checksum, or the rule allows transformed bytes (`allowTransformed`: ijar, jetify,
   * desugar, Bazel's `processed_` manifest stamp).
   */
  COORDINATE_VERIFIED("coordinate-verified"),

  /** Carry the built jar in the bundle's `libs/`. */
  EMBED("embed"),

  /**
   * Group and artifact known but the version is unpublished (a top-of-tree build): reference the
   * nearest released version at or below it. Explicit opt-in; marks the bundle `approximate`.
   */
  CLOSEST_RELEASED("closest-released"),

  /**
   * Reserved: reference the producing build target. Not implemented — older bundle readers reject
   * an unknown classpath entry kind — so it always falls through to the next step.
   */
  BUILD_TARGET("build-target");

  public companion object {
    public fun of(id: String): DependencyPolicyStep =
      entries.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException(
          "Unknown dependency policy '$id' (expected one of ${entries.joinToString { it.id }})"
        )
  }
}

/**
 * A per-group override in a [DependencyPolicy]. [match] is a glob (`*` any run of characters, `?`
 * one) over the coordinate's group, or over `group:artifact` when it contains a `:`.
 */
public class DependencyPolicyRule(
  public val match: String,
  public val policy: List<DependencyPolicyStep>,
  public val allowTransformed: Boolean = false,
) {
  init {
    require(match.isNotBlank()) { "dependencyPolicy rule needs a match pattern" }
    require(policy.isNotEmpty()) { "dependencyPolicy rule '$match' needs a policy chain" }
  }

  private val regex: Regex =
    Regex(
      match
        .map {
          when (it) {
            '*' -> ".*"
            '?' -> "."
            else -> Regex.escape(it.toString())
          }
        }
        .joinToString("")
    )

  public fun matches(group: String, artifact: String): Boolean =
    regex.matches(if (':' in match) "$group:$artifact" else group)
}

/**
 * How a Bazel bundle carries each runtime jar (#6016): a [default] fallback chain, per-group
 * [rules] (first match wins), and which extra identification signals to read.
 *
 * [DEFAULT] is exactly the behaviour before policies existed — `coordinate`, then `embed` for an
 * unidentified jar, no version stamps or owner labels, and the original
 * `dependency-resolution.json` shape — so an unconfigured bundle is byte-for-byte unchanged. A
 * configured policy ([explicit]) also writes the decision fields (`rule`, `policy`, `fallbacks`,
 * `substitutedFrom`, `reason`).
 */
public class DependencyPolicy
internal constructor(
  public val default: List<DependencyPolicyStep> = DEFAULT_CHAIN,
  public val rules: List<DependencyPolicyRule> = emptyList(),
  /** `allowTransformed` for the [default] chain's `coordinate-verified`. */
  public val allowTransformed: Boolean = false,
  /** Identify jars by an androidx `META-INF/<group>_<artifact>.version` stamp. */
  public val versionStamps: Boolean = false,
  /** Identify jars by their producing `@repo//:group_artifact` target (`runtimeJarOwners`). */
  public val ownerLabels: Boolean = false,
  /** Whether this came from configuration: only then are the extended decision fields recorded. */
  public val explicit: Boolean = false,
) {
  init {
    require(default.isNotEmpty()) { "dependencyPolicy default chain is empty" }
  }

  /** A new field is a new `var` here, so the type can grow without breaking released callers. */
  public class Builder {
    public var default: List<DependencyPolicyStep> = DEFAULT_CHAIN
    public var rules: List<DependencyPolicyRule> = emptyList()
    public var allowTransformed: Boolean = false
    public var versionStamps: Boolean = false
    public var ownerLabels: Boolean = false
    public var explicit: Boolean = false

    public fun build(): DependencyPolicy =
      DependencyPolicy(default, rules, allowTransformed, versionStamps, ownerLabels, explicit)
  }

  public fun newBuilder(): Builder =
    Builder().also {
      it.default = default
      it.rules = rules
      it.allowTransformed = allowTransformed
      it.versionStamps = versionStamps
      it.ownerLabels = ownerLabels
      it.explicit = explicit
    }

  /** The rule for an identified coordinate, or null for the [default] chain. */
  public fun ruleFor(group: String, artifact: String): DependencyPolicyRule? = rules.firstOrNull {
    it.matches(group, artifact)
  }

  public companion object {
    public val DEFAULT_CHAIN: List<DependencyPolicyStep> =
      listOf(DependencyPolicyStep.COORDINATE, DependencyPolicyStep.EMBED)

    /** Today's behaviour. */
    public val DEFAULT: DependencyPolicy = DependencyPolicy()

    /**
     * Parses a `dependencyPolicy` block:
     * ```
     * {"default": ["coordinate-verified", "embed"], "allowTransformed": true,
     *  "versionStamps": true, "ownerLabels": true,
     *  "rules": [{"match": "androidx.*", "policy": ["closest-released", "embed"]}]}
     * ```
     *
     * Every key is optional. A step may be a string or a single-element list. Configuring a block
     * at all opts into the new identification signals (`versionStamps`, `ownerLabels`, default true
     * here) and the extended decision record.
     */
    public fun parse(block: JsonObject): DependencyPolicy {
      val known = setOf("default", "rules", "allowTransformed", "versionStamps", "ownerLabels")
      block.keys
        .firstOrNull { it !in known }
        ?.let { throw IllegalArgumentException("Unknown dependencyPolicy key '$it'") }
      fun chain(value: Any?, where: String): List<DependencyPolicyStep> =
        when (value) {
          is JsonPrimitive -> listOf(DependencyPolicyStep.of(value.content))
          is JsonArray ->
            value.map {
              DependencyPolicyStep.of(
                (it as? JsonPrimitive)?.contentOrNull
                  ?: throw IllegalArgumentException("$where must list policy names")
              )
            }
          else -> throw IllegalArgumentException("$where must be a policy name or a list of them")
        }
      fun flag(obj: JsonObject, key: String, default: Boolean): Boolean =
        obj[key]?.let {
          (it as? JsonPrimitive)?.booleanOrNull
            ?: throw IllegalArgumentException("dependencyPolicy $key must be true or false")
        } ?: default
      val rules =
        when (val raw = block["rules"]) {
          null -> emptyList()
          is JsonArray ->
            raw.mapIndexed { index, element ->
              val rule =
                element as? JsonObject
                  ?: throw IllegalArgumentException(
                    "dependencyPolicy rules[$index] is not an object"
                  )
              rule.keys
                .firstOrNull { it !in setOf("match", "policy", "allowTransformed") }
                ?.let {
                  throw IllegalArgumentException("Unknown dependencyPolicy rules[$index] key '$it'")
                }
              DependencyPolicyRule(
                match =
                  (rule["match"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("dependencyPolicy rules[$index] needs match"),
                policy =
                  chain(
                    rule["policy"]
                      ?: throw IllegalArgumentException(
                        "dependencyPolicy rules[$index] needs policy"
                      ),
                    "rules[$index].policy",
                  ),
                allowTransformed = flag(rule, "allowTransformed", false),
              )
            }
          else -> throw IllegalArgumentException("dependencyPolicy rules must be a list")
        }
      return DependencyPolicy(
        default = block["default"]?.let { chain(it, "dependencyPolicy default") } ?: DEFAULT_CHAIN,
        rules = rules,
        allowTransformed = flag(block, "allowTransformed", false),
        versionStamps = flag(block, "versionStamps", true),
        ownerLabels = flag(block, "ownerLabels", true),
        explicit = true,
      )
    }
  }
}

/**
 * Which versions of `group:artifact` are released. Null means unknown (offline, uncached or the
 * lookup failed); the policy then falls through to its next step rather than guessing.
 */
public fun interface ReleasedVersions {
  public fun versions(group: String, artifact: String): List<String>?

  public companion object {
    /** Nothing is known beyond the lockfile: the hermetic / `COMPOSE_PREVIEW_NO_NETWORK` view. */
    public val NONE: ReleasedVersions = ReleasedVersions { _, _ -> null }
  }
}

/**
 * Everything [writeBazelBundle] needs to decide how dependencies travel: the [policy], a
 * [releasedVersions] lookup for `coordinate-verified` / `closest-released`, and the build's
 * [runtimeJarOwners] (runtime jar → producing target label, from the artifacts contract's
 * `runtimeJarOwners`; empty when the aspect predates it).
 *
 * Owner labels are used only to identify a jar; they are never written into the bundle, since a
 * workspace label exposes repository layout and means nothing outside the workspace.
 */
public class DependencyResolutionOptions
internal constructor(
  public val policy: DependencyPolicy = DependencyPolicy.DEFAULT,
  public val releasedVersions: ReleasedVersions = ReleasedVersions.NONE,
  public val runtimeJarOwners: Map<File, String> = emptyMap(),
) {
  /** A new field is a new `var` here (a `build-target` consumer, say), never a signature change. */
  public class Builder {
    public var policy: DependencyPolicy = DependencyPolicy.DEFAULT
    public var releasedVersions: ReleasedVersions = ReleasedVersions.NONE
    public var runtimeJarOwners: Map<File, String> = emptyMap()

    public fun build(): DependencyResolutionOptions =
      DependencyResolutionOptions(policy, releasedVersions, runtimeJarOwners)
  }

  public fun newBuilder(): Builder =
    Builder().also {
      it.policy = policy
      it.releasedVersions = releasedVersions
      it.runtimeJarOwners = runtimeJarOwners
    }

  public companion object {
    public val DEFAULT: DependencyResolutionOptions = DependencyResolutionOptions()
  }
}

/** One step that did not apply, and why. */
internal data class PolicyFallback(val policy: String, val reason: String)

/** What a [DependencyPolicy] decided for one jar: a coordinate, or null to embed it. */
internal data class PolicyOutcome(
  val coordinate: BundleReader.ClasspathEntry.Maven?,
  val rule: String,
  val policy: String,
  val fallbacks: List<PolicyFallback>,
  val substitutedFrom: BundleReader.ClasspathEntry.Maven? = null,
  val reason: String? = null,
)

internal fun BundleReader.ClasspathEntry.Maven.gav(): String = "$group:$artifact:$version"

/** Applies [policy] (or `embed` for everything when [embedAll]) to one identified-or-not jar. */
internal fun applyDependencyPolicy(
  match: BazelMavenMatch?,
  builtSha256: String,
  pins: List<BundleReader.ClasspathEntry.Maven>,
  policy: DependencyPolicy,
  releases: ReleasedVersions,
  embedAll: Boolean,
): PolicyOutcome {
  if (embedAll) return PolicyOutcome(null, "embed-deps", DependencyPolicyStep.EMBED.id, emptyList())
  val coordinate = match?.coordinate
  val rule = coordinate?.let { policy.ruleFor(it.group, it.artifact) }
  val chain = rule?.policy ?: policy.default
  val allowTransformed = rule?.allowTransformed ?: policy.allowTransformed
  val ruleName = rule?.match ?: "default"
  val fallbacks = mutableListOf<PolicyFallback>()
  fun pinnedVersions(group: String, artifact: String): List<String> =
    pins.filter { it.group == group && it.artifact == artifact }.map { it.version }
  fun knownVersions(group: String, artifact: String): List<String>? {
    val pinned = pinnedVersions(group, artifact)
    val remote = releases.versions(group, artifact)
    return if (remote == null && pinned.isEmpty()) null else (pinned + remote.orEmpty()).distinct()
  }
  for (step in chain) {
    fun fail(reason: String) {
      fallbacks += PolicyFallback(step.id, reason)
    }
    when (step) {
      DependencyPolicyStep.EMBED -> return PolicyOutcome(null, ruleName, step.id, fallbacks)
      DependencyPolicyStep.COORDINATE ->
        if (coordinate != null) return PolicyOutcome(coordinate, ruleName, step.id, fallbacks)
        else fail("unidentified")
      DependencyPolicyStep.COORDINATE_VERIFIED -> {
        if (coordinate == null) {
          fail("unidentified")
          continue
        }
        val published =
          if (coordinate.version in pinnedVersions(coordinate.group, coordinate.artifact)) true
          else
            releases.versions(coordinate.group, coordinate.artifact)?.contains(coordinate.version)
        when {
          published == null -> fail("publication-unknown")
          !published -> fail("unpublished")
          coordinate.sha256 != null && coordinate.sha256 == builtSha256 ->
            return PolicyOutcome(coordinate, ruleName, step.id, fallbacks)
          allowTransformed ->
            return PolicyOutcome(
              coordinate,
              ruleName,
              step.id,
              fallbacks,
              reason = "transformed-allowed",
            )
          coordinate.sha256 == null -> fail("checksum-unknown")
          else -> fail("checksum-mismatch")
        }
      }
      DependencyPolicyStep.CLOSEST_RELEASED -> {
        if (coordinate == null) {
          fail("unidentified")
          continue
        }
        val versions = knownVersions(coordinate.group, coordinate.artifact)
        if (versions == null) {
          fail("publication-unknown")
          continue
        }
        if (coordinate.version in versions)
          return PolicyOutcome(coordinate, ruleName, step.id, fallbacks)
        val best =
          versions
            .filter { !isSnapshotVersion(it) && compareMavenVersions(it, coordinate.version) <= 0 }
            .maxWithOrNull(::compareMavenVersions)
        if (best == null) {
          fail("no-earlier-release")
          continue
        }
        val pinSha =
          pins
            .firstOrNull {
              it.group == coordinate.group &&
                it.artifact == coordinate.artifact &&
                it.version == best
            }
            ?.sha256
        return PolicyOutcome(
          BundleReader.ClasspathEntry.Maven(
            coordinate.group,
            coordinate.artifact,
            best,
            coordinate.type,
            pinSha,
          ),
          ruleName,
          step.id,
          fallbacks,
          substitutedFrom = coordinate,
          reason = "unpublished-version",
        )
      }
      // No bundle entry kind can carry a target yet (older readers reject unknown kinds).
      DependencyPolicyStep.BUILD_TARGET -> fail("build-target-unsupported")
    }
  }
  // An exhausted chain embeds: always correct, if larger.
  fallbacks += PolicyFallback("chain", "exhausted")
  return PolicyOutcome(null, ruleName, DependencyPolicyStep.EMBED.id, fallbacks)
}

internal fun isSnapshotVersion(version: String): Boolean = version.endsWith("-SNAPSHOT", true)

/**
 * Maven-style ordering for the versions libraries actually publish: numeric release parts, then a
 * qualifier (`alpha` < `beta` < `milestone` < `rc` < `SNAPSHOT` < release), then its number.
 * `1.13.0-beta01` < `1.13.0` < `1.14.0-alpha01`.
 */
internal fun compareMavenVersions(a: String, b: String): Int {
  data class Parsed(
    val numbers: List<Int>,
    val rank: Int,
    val qualifierNumber: Int,
    val raw: String,
  )
  fun parse(version: String): Parsed {
    val match =
      Regex("^(\\d+(?:\\.\\d+)*)(?:[-.]?([A-Za-z]+)[-.]?(\\d*))?").find(version)
        ?: return Parsed(emptyList(), 0, 0, version)
    val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
    val qualifier = match.groupValues[2].lowercase()
    val rank =
      when (qualifier) {
        "" -> 6
        "alpha",
        "a" -> 1
        "beta",
        "b" -> 2
        "milestone",
        "m" -> 3
        "rc",
        "cr" -> 4
        "snapshot" -> 5
        "ga",
        "final",
        "release" -> 6
        else -> 0
      }
    return Parsed(numbers, rank, match.groupValues[3].toIntOrNull() ?: 0, version)
  }
  val x = parse(a)
  val y = parse(b)
  for (i in 0 until maxOf(x.numbers.size, y.numbers.size)) {
    val c = x.numbers.getOrElse(i) { 0 }.compareTo(y.numbers.getOrElse(i) { 0 })
    if (c != 0) return c
  }
  return compareValuesBy(x, y, { it.rank }, { it.qualifierNumber }, { it.raw })
}
