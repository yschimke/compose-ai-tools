package ee.schimke.composeai.plugin

import java.io.File
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/*
 * Reads of this plugin's own build outputs (`previews.json` above all) during configuration.
 *
 * Under the configuration cache, every file the configuration phase reads or merely stats becomes
 * an input of the cache entry, and that includes providers Gradle evaluates while *storing* the
 * entry. `composePreviewDiscover` rewrites `previews.json` on every discovery, so a plain
 * `providers.provider { file.isFile }` or a `readText()` at configuration made each discovery
 * invalidate the entry it had just stored. A [ValueSource] is the boundary Gradle offers instead:
 * reads inside [ValueSource.obtain] are not recorded, and only the value it returns is.
 */

/**
 * The first of [Params.candidates] that exists — a regular file, or a directory when
 * [Params.directory] is set — or absent when none does.
 *
 * For an `@Optional` task input that names a build output: nothing queries it at configuration, so
 * the cache serializes the source rather than its value and it is obtained when the task reads the
 * property, exactly as the eager provider resolved without the cache.
 */
internal abstract class FirstExistingPathValueSource :
  ValueSource<File, FirstExistingPathValueSource.Params> {
  interface Params : ValueSourceParameters {
    val candidates: ListProperty<File>
    val directory: Property<Boolean>
  }

  override fun obtain(): File? {
    val directory = parameters.directory.getOrElse(false)
    return parameters.candidates.getOrElse(emptyList()).firstOrNull {
      if (directory) it.isDirectory else it.isFile
    }
  }

  companion object {
    fun of(project: Project, candidates: Provider<List<File>>, directory: Boolean): Provider<File> =
      project.providers.of(FirstExistingPathValueSource::class.java) {
        parameters.candidates.set(candidates)
        parameters.directory.set(directory)
      }
  }
}

/**
 * `shards = 0` (auto) sized from the previous `previews.json` by [ShardTuning.autoShards], or 1
 * when there is none yet.
 *
 * The count decides which tasks exist and how many forks the render task takes, so it has to be
 * known at configuration. As a value source the cache entry depends on the *count*, not on the
 * manifest's bytes: a discovery that leaves the count unchanged reuses the entry, and one that
 * changes it reconfigures, as it must.
 */
internal abstract class AutoShardCountValueSource :
  ValueSource<Int, AutoShardCountValueSource.Params> {
  interface Params : ValueSourceParameters {
    val previewsJson: Property<File>
  }

  override fun obtain(): Int {
    val logger = Logging.getLogger(AutoShardCountValueSource::class.java)
    val previewsJson = parameters.previewsJson.get()
    if (!previewsJson.exists()) {
      logger.info(
        "compose-ai-tools: shards=auto but previews.json missing; defaulting to 1 for this run"
      )
      return 1
    }
    // Size by preview row: the renderer keeps a row's captures on one fork, so counting captures
    // would pick idle extra forks. [ShardTuning.perPreviewRowCosts] mirrors the renderer's per-row
    // cost.
    val text = previewsJson.readText()
    val rowCosts = ShardTuning.perPreviewRowCosts(text)
    val rowCount = rowCosts.size
    val totalCost = rowCosts.sum()
    val maxIndividualCost = rowCosts.maxOrNull() ?: 0.0
    val hostMemoryMb = ShardTuning.hostMemoryMb()
    val resolved =
      ShardTuning.autoShards(
        totalCost = totalCost,
        maxIndividualCost = maxIndividualCost,
        shardableRows = rowCount,
        availableMemoryMb = hostMemoryMb,
      )
    val memTag = if (hostMemoryMb == Long.MAX_VALUE) "unknown" else "${hostMemoryMb}MB"
    logger.lifecycle(
      "compose-ai-tools: shards=auto → $resolved " +
        "(rows=$rowCount, totalCost=${"%.1f".format(totalCost)}, " +
        "maxRowCost=${"%.1f".format(maxIndividualCost)}, " +
        "cores=${Runtime.getRuntime().availableProcessors()}, mem=$memTag)"
    )
    return resolved
  }
}
