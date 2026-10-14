package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.producer.BazelAndroidResources
import ee.schimke.composeai.bundle.producer.writeBazelBundle as produceBundle
import java.io.File

internal fun writeBazelBundle(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  selectedIds: Set<String>,
  lockFiles: List<File>,
  output: File,
  embedDeps: Boolean = false,
  includeRenders: Boolean = true,
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
  )
}
