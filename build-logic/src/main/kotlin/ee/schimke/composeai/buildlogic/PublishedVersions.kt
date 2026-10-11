package ee.schimke.composeai.buildlogic

/**
 * Which version each coordinate carries on a release where only some modules publish. One pure
 * function shared by `ComposeAiMavenPublishingPlugin` (`project.version`, which POMs name) and
 * `:bom` (its constraints), so the two can't disagree.
 */
object PublishedVersions {
  /**
   * The version [artifactId] carries: [tagVersion] if [publishSet] is null (publish everything) or
   * contains it, otherwise its last published version. Not finding one is an error, never a
   * fallback to the tag, which would publish a POM naming a nonexistent coordinate.
   */
  fun resolve(
    artifactId: String,
    tagVersion: String,
    publishSet: Collection<String>?,
    manifestText: String,
  ): String {
    if (publishSet == null || artifactId in publishSet) return tagVersion
    return recordedVersion(artifactId, manifestText)
      ?: error(
        "'$artifactId' is not in the publish set and has no entry in publishing-manifest.json, " +
          "so there is no version it can safely carry. That file is not committed: the release " +
          "plan writes it from Maven Central (maven-publish-plan.sh --write-manifest). An empty " +
          "name here means a project path that resolves to no artifact id."
      )
  }

  /**
   * The version the manifest records for [artifactId], or null. A regex (build-logic has no JSON
   * dependency) over a shape `PublishedVersionsTest` pins.
   */
  fun recordedVersion(artifactId: String, manifestText: String): String? =
    Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"")
      .find(manifestText)
      ?.groupValues
      ?.get(1)

  /**
   * Parses `-Pcomposeai.publishSet`. Absent and empty must stay distinct:
   *  * absent (`null`) — no plan ran: publish everything (the `workflow_dispatch` recovery path);
   *  * empty (`""`) — the plan found nothing to publish (e.g. a docs-only release): publish nothing,
   *    including the BOM, which would be byte-identical.
   */
  fun parsePublishSet(property: String?): Set<String>? =
    property?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
}
