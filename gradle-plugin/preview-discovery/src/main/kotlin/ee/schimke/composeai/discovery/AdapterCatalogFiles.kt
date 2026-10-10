package ee.schimke.composeai.discovery

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Write one generated pair into a new output directory. Build and publish this directory as a unit;
 * catalog.json's componentsFile/uiBuilderFile must point at this pair from the same generation.
 * Discovery itself never executes adapter declarations or loads the app's classes.
 */
fun GeneratedAdapterCatalog.writeTo(directory: Path) {
  val destination = directory.toAbsolutePath().normalize()
  require(!Files.exists(destination)) { "catalog output already exists: $destination" }
  Files.createDirectories(destination.parent)
  val staging = Files.createTempDirectory(destination.parent, ".adapter-catalog-")
  try {
    val json = Json {
      prettyPrint = true
      encodeDefaults = true
    }
    Files.writeString(
      staging.resolve("components.json"),
      json.encodeToString(ComponentRecordFile.serializer(), record),
    )
    Files.writeString(
      staging.resolve("ui-builder.json"),
      json.encodeToString(UiBuilderCatalogFile.serializer(), catalog),
    )
    Files.move(staging, destination)
  } finally {
    if (Files.exists(staging)) {
      Files.deleteIfExists(staging.resolve("components.json"))
      Files.deleteIfExists(staging.resolve("ui-builder.json"))
      Files.deleteIfExists(staging)
    }
  }
}
