package ee.schimke.composeai.discovery

// Fixtures for ComposableSignatureTest. Deliberately NOT @Composable — that would drag the Compose
// runtime onto the discovery test classpath, and ComposableSignature reads only Kotlin @Metadata,
// which every Kotlin declaration carries regardless. A top-level function compiles into the file
// facade class `SignatureFixturesKt`, exercising the FileFacade metadata path.
@Suppress("unused", "UNUSED_PARAMETER")
fun sampleComponent(
  state: String,
  count: Int = 3,
  labels: List<String>,
  onClick: () -> Unit,
  note: String? = null,
) {}

@Suppress("unused") fun noParams() {}
