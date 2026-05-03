package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val DATA_PRODUCTS_SCHEMA = "compose-preview-data-products/v1"
internal const val DATA_GET_SCHEMA = "compose-preview-data-get/v1"

private val dataJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
}

@Serializable
data class DataProductsResponse(
  val schema: String = DATA_PRODUCTS_SCHEMA,
  val modules: List<DataProductsModule>,
)

@Serializable
data class DataProductsModule(
  val module: String,
  val dataDir: String,
  val products: List<DataProductSummary>,
)

@Serializable
data class DataProductSummary(
  val kind: String,
  val schemaVersion: Int,
  val transport: DataProductTransport,
  val fetchable: Boolean,
  val previews: List<String>,
)

@Serializable
data class DataGetResponse(
  val schema: String = DATA_GET_SCHEMA,
  val module: String,
  val previewId: String,
  val kind: String,
  val schemaVersion: Int,
  val transport: DataProductTransport,
  val path: String,
  val payload: JsonElement? = null,
)

class DataProductsCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val response =
        DataProductsResponse(
          modules = modules.map { scanDataProducts(it.gradlePath, it.projectDir) }
        )
      if (jsonOutput) {
        println(dataJson.encodeToString(DataProductsResponse.serializer(), response))
      } else {
        printText(response)
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview data-products [--module M] [--json]

      Lists data-product files already emitted under
      <module>/build/compose-previews/data. This local-library command does
      not run Gradle and does not start a daemon; run `compose-preview show`
      first when no products are present.
      """
        .trimIndent()
    )
  }

  private fun printText(response: DataProductsResponse) {
    var any = false
    for (module in response.modules) {
      if (module.products.isEmpty()) continue
      any = true
      println("[${module.module}]")
      for (product in module.products) {
        println(
          "  ${product.kind}  schema=v${product.schemaVersion}  " +
            "transport=${product.transport.name.lowercase()}  previews=${product.previews.size}"
        )
      }
    }
    if (!any) {
      println("No data products found. Run `compose-preview show` to render previews first.")
    }
  }
}

class DataCommand(args: List<String>) : Command(args) {
  private val subcommand = args.dataSubcommand()
  private val jsonOutput = "--json" in args
  private val output: String? = args.flagValue("--output")
  private val kind: String? = args.flagValue("--kind")

  override fun run() {
    if ("--help" in args || "-h" in args || subcommand == "help") {
      printUsage()
      return
    }
    when (subcommand) {
      "get" -> runGet()
      null -> {
        System.err.println("No data subcommand specified.")
        printUsage()
        exitProcess(1)
      }
      else -> {
        System.err.println("Unknown data subcommand: $subcommand")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun runGet() {
    val previewId =
      exactId
        ?: run {
          System.err.println("data get requires --id <preview-id>.")
          exitProcess(1)
        }
    val productKind =
      kind
        ?: run {
          System.err.println("data get requires --kind <kind>.")
          exitProcess(1)
        }
    if (jsonOutput && output != null) {
      System.err.println("data get accepts either --json or --output, not both.")
      exitProcess(1)
    }

    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val matches = modules.mapNotNull { module -> findDataProduct(module, previewId, productKind) }
      when {
        matches.isEmpty() -> {
          System.err.println(
            "Data product not available: id='$previewId' kind='$productKind'. " +
              "Run `compose-preview show` first, or check that the kind is enabled for this module."
          )
          exitProcess(3)
        }
        matches.size > 1 && explicitModule == null -> {
          System.err.println(
            "Data product matched multiple modules: ${matches.joinToString { it.module }}. " +
              "Pass --module <name>."
          )
          exitProcess(1)
        }
      }

      val response = buildDataGetResponse(matches.single())
      if (output != null) {
        File(response.path).copyTo(File(output), overwrite = true)
        println("Wrote ${response.kind} for ${response.previewId} to $output")
      } else if (jsonOutput) {
        println(dataJson.encodeToString(DataGetResponse.serializer(), response))
      } else {
        println(response.path)
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview data get --id PREVIEW --kind KIND [--module M] [--json|--output PATH]

      Reads one data product already emitted under
      <module>/build/compose-previews/data/<preview-id>/. This first
      implementation does not auto-render and does not call daemon data/fetch.
      """
        .trimIndent()
    )
  }
}

private fun List<String>.dataSubcommand(): String? {
  val valuedFlags = setOf("--module", "--id", "--kind", "--output", "--timeout")
  var i = 0
  while (i < size) {
    val arg = this[i]
    when {
      valuedFlags.any { arg.startsWith("$it=") } -> i++
      arg in valuedFlags -> i += 2
      arg.startsWith("-") -> i++
      else -> return arg
    }
  }
  return null
}

internal fun scanDataProducts(module: String, projectDir: File): DataProductsModule {
  val dataDir = dataRoot(projectDir)
  val manifest = readDataManifest(projectDir)
  val manifestProductBuckets =
    manifest
      ?.previews
      ?.flatMap { it.dataProducts }
      ?.mapNotNull { product ->
        product.output.removePrefix("data/").substringBefore('/').takeIf { it.isNotBlank() }
      }
      ?.toSet() ?: emptySet()
  val byKind = linkedMapOf<String, MutableSet<String>>()
  if (dataDir.isDirectory) {
    dataDir
      .listFiles()
      ?.filter { it.isDirectory }
      ?.filter { it.name !in manifestProductBuckets }
      ?.sortedBy { it.name }
      ?.forEach { productDir ->
        val directoryDescriptor = descriptorForDirectory(productDir.name)
        productDir
          .listFiles()
          ?.filter { it.isFile }
          ?.forEach { file ->
            if (directoryDescriptor != null) {
              byKind.getOrPut(directoryDescriptor.kind) { linkedSetOf() } +=
                file.nameWithoutExtension
            } else {
              descriptorForFile(file)?.let { descriptor ->
                byKind.getOrPut(descriptor.kind) { linkedSetOf() } += productDir.name
              }
            }
          }
      }
  }
  manifest?.previews?.forEach { preview ->
    preview.dataProducts.forEach { product ->
      val file = projectDir.resolve("build/compose-previews/${product.output}")
      if (product.output.isNotBlank() && file.isFile) {
        byKind.getOrPut(product.kind) { linkedSetOf() } += preview.id
      }
    }
  }

  val products =
    byKind
      .map { (kind, previews) ->
        val descriptor = descriptorForKind(kind)
        DataProductSummary(
          kind = kind,
          schemaVersion = descriptor.schemaVersion,
          transport = descriptor.transport,
          fetchable = true,
          previews = previews.toList().sorted(),
        )
      }
      .sortedBy { it.kind }

  return DataProductsModule(
    module = module,
    dataDir = dataDir.absoluteFile.path,
    products = products,
  )
}

internal fun findDataProduct(
  module: PreviewModule,
  previewId: String,
  kind: String,
): LocalDataProduct? {
  val descriptor = descriptorForKind(kind)
  val root = dataRoot(module.projectDir)
  val file = root.resolve(previewId).resolve(descriptor.fileName)
  if (file.exists() && file.isFile) {
    return LocalDataProduct(module.gradlePath, previewId, descriptor, file.absoluteFile)
  }
  val manifestProduct =
    readDataManifest(module.projectDir)
      ?.previews
      ?.firstOrNull { it.id == previewId }
      ?.dataProducts
      ?.firstOrNull { it.kind == kind && it.output.isNotBlank() }
  if (manifestProduct != null) {
    val manifestFile = module.projectDir.resolve("build/compose-previews/${manifestProduct.output}")
    if (manifestFile.isFile) {
      return LocalDataProduct(
        module.gradlePath,
        previewId,
        descriptorForManifestProduct(manifestProduct, manifestFile),
        manifestFile.absoluteFile,
      )
    }
  }
  val kindScopedFile =
    root.resolve(kind.replace('/', '-')).resolve("$previewId.${descriptor.extension}")
  if (!kindScopedFile.isFile) return null
  return LocalDataProduct(module.gradlePath, previewId, descriptor, kindScopedFile.absoluteFile)
}

internal fun buildDataGetResponse(product: LocalDataProduct): DataGetResponse {
  val payload =
    product.file
      .takeIf { it.extension.equals("json", ignoreCase = true) }
      ?.let { dataJson.parseToJsonElement(it.readText()) }
  return DataGetResponse(
    module = product.module,
    previewId = product.previewId,
    kind = product.descriptor.kind,
    schemaVersion = product.descriptor.schemaVersion,
    transport = product.descriptor.transport,
    path = product.file.path,
    payload = payload,
  )
}

internal data class LocalDataProduct(
  val module: String,
  val previewId: String,
  val descriptor: LocalDataProductDescriptor,
  val file: File,
)

internal data class LocalDataProductDescriptor(
  val kind: String,
  val schemaVersion: Int,
  val transport: DataProductTransport,
  val fileName: String,
) {
  val extension: String = fileName.substringAfterLast('.', missingDelimiterValue = "json")
}

private fun dataRoot(projectDir: File): File = projectDir.resolve("build/compose-previews/data")

private val knownLocalDataProducts =
  listOf(
    LocalDataProductDescriptor("a11y/atf", 1, DataProductTransport.INLINE, "a11y-atf.json"),
    LocalDataProductDescriptor(
      "a11y/hierarchy",
      1,
      DataProductTransport.PATH,
      "a11y-hierarchy.json",
    ),
    LocalDataProductDescriptor(
      "a11y/touchTargets",
      1,
      DataProductTransport.INLINE,
      "a11y-touchTargets.json",
    ),
    LocalDataProductDescriptor("a11y/overlay", 1, DataProductTransport.PATH, "a11y-overlay.png"),
    LocalDataProductDescriptor(
      "compose/semantics",
      1,
      DataProductTransport.INLINE,
      "compose-semantics.json",
    ),
    LocalDataProductDescriptor(
      "resources/used",
      1,
      DataProductTransport.INLINE,
      "resources-used.json",
    ),
    LocalDataProductDescriptor(
      "i18n/translations",
      1,
      DataProductTransport.INLINE,
      "i18n-translations.json",
    ),
    LocalDataProductDescriptor("fonts/used", 1, DataProductTransport.PATH, "fonts-used.json"),
    LocalDataProductDescriptor(
      "render/composeAiTrace",
      1,
      DataProductTransport.PATH,
      "render-perfetto-trace.json",
    ),
    LocalDataProductDescriptor(
      "render/scroll/long",
      1,
      DataProductTransport.PATH,
      "render-scroll-long.png",
    ),
    LocalDataProductDescriptor(
      "render/scroll/gif",
      1,
      DataProductTransport.PATH,
      "render-scroll-gif.gif",
    ),
  )

private val knownByKind = knownLocalDataProducts.associateBy { it.kind }
private val knownByFileName = knownLocalDataProducts.associateBy { it.fileName }
private val knownByDirectoryName = knownLocalDataProducts.associateBy { it.kind.replace('/', '-') }

private fun descriptorForKind(kind: String): LocalDataProductDescriptor =
  knownByKind[kind]
    ?: LocalDataProductDescriptor(
      kind = kind,
      schemaVersion = 1,
      transport = DataProductTransport.INLINE,
      fileName = "${kind.replace('/', '-')}.json",
    )

private fun readDataManifest(projectDir: File): PreviewManifest? {
  val manifestFile = projectDir.resolve("build/compose-previews/previews.json")
  if (!manifestFile.isFile) return null
  return runCatching {
      dataJson.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
    }
    .getOrNull()
}

private fun descriptorForManifestProduct(
  product: PreviewDataProduct,
  file: File,
): LocalDataProductDescriptor {
  val transport =
    when (file.extension.lowercase()) {
      "json" -> DataProductTransport.INLINE
      else -> DataProductTransport.PATH
    }
  return LocalDataProductDescriptor(
    kind = product.kind,
    schemaVersion = descriptorForKind(product.kind).schemaVersion,
    transport = transport,
    fileName = file.name,
  )
}

private fun descriptorForFile(file: File): LocalDataProductDescriptor? =
  knownByFileName[file.name]
    ?: when (file.extension.lowercase()) {
      "json" ->
        LocalDataProductDescriptor(
          kind = file.nameWithoutExtension.replace('-', '/'),
          schemaVersion = 1,
          transport = DataProductTransport.INLINE,
          fileName = file.name,
        )
      "png" ->
        LocalDataProductDescriptor(
          kind = file.nameWithoutExtension.replace('-', '/'),
          schemaVersion = 1,
          transport = DataProductTransport.PATH,
          fileName = file.name,
        )
      else -> null
    }

private fun descriptorForDirectory(name: String): LocalDataProductDescriptor? =
  knownByDirectoryName[name]
