package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.producer.BazelAndroidResources
import ee.schimke.composeai.bundle.producer.DependencyPolicy
import ee.schimke.composeai.bundle.producer.DependencyResolutionOptions
import ee.schimke.composeai.bundle.producer.MavenMetadataVersions
import ee.schimke.composeai.bundle.producer.ReleasedVersions
import ee.schimke.composeai.bundle.producer.writeBazelBundle as produceBundle
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import kotlinx.serialization.json.JsonObject

internal fun writeBazelBundle(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  selectedIds: Set<String>,
  lockFiles: List<File>,
  output: File,
  embedDeps: Boolean = false,
  includeRenders: Boolean = true,
  dependencies: DependencyResolutionOptions = DependencyResolutionOptions.DEFAULT,
): BundleReader.Manifest {
  val sourcePaths =
    artifacts.sourceFiles.zip(artifacts.metadata.sourceFiles.distinct()).associate { (file, path) ->
      file.canonicalFile to path
    }
  val portable =
    prepared.manifest.copy(
      previews =
        prepared.manifest.previews.map { preview ->
          preview.copy(
            sourceFile =
              preview.sourceFile?.let { path -> sourcePaths[File(path).canonicalFile] ?: path }
          )
        }
    )
  return produceBundle(
    artifacts.metadata.target,
    artifacts.projectJars,
    artifacts.runtimeJars,
    portable,
    prepared.manifestFile.parentFile.takeIf { includeRenders },
    selectedIds,
    lockFiles,
    output,
    embedDeps,
    artifacts.android?.let {
      BazelAndroidResources(it.packageName, it.resourceApk, it.manifest, it.rJar)
    },
    dependencies,
  )
}

/**
 * The dependency options for a Bazel bundle (#6016): the configured `dependencyPolicy` (none keeps
 * the original behaviour exactly), the build's runtime jar owners, and a release lookup that obeys
 * [NetworkPolicy] — no lookup at all under `COMPOSE_PREVIEW_NO_NETWORK` (only lockfile-pinned
 * versions count), the metadata cache only under `COMPOSE_PREVIEW_OFFLINE`, else Google Maven /
 * Central with a cache under `composeAiCacheDir("maven-metadata")`.
 */
internal fun bazelDependencyOptions(
  policyBlock: JsonObject?,
  artifacts: ResolvedBazelArtifacts,
  networkDisabled: Boolean = NetworkPolicy.disabled,
  offline: Boolean = NetworkPolicy.offline,
  cacheDir: File = composeAiCacheDir("maven-metadata"),
): DependencyResolutionOptions =
  DependencyResolutionOptions.Builder()
    .apply {
      policy = policyBlock?.let(DependencyPolicy::parse) ?: DependencyPolicy.DEFAULT
      releasedVersions =
        if (networkDisabled) ReleasedVersions.NONE
        else MavenMetadataVersions(cacheDir, network = !offline)
      runtimeJarOwners = artifacts.runtimeJarOwners
    }
    .build()
