package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
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
): BundleReader.Manifest =
  produceBundle(
    artifacts.metadata.target,
    artifacts.projectJars,
    artifacts.runtimeJars,
    prepared.manifest,
    prepared.manifestFile.parentFile.takeIf { includeRenders },
    selectedIds,
    lockFiles,
    output,
    embedDeps,
  )
