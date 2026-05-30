package ee.schimke.composeai.renderer

/**
 * Lives in its own file so Kotlin emits a synthetic
 * `PreviewReceiverTestFixturesKt` class — the file [PreviewReceiverTest]
 * reflects on to exercise the top-level / static-method path through
 * [resolvePreviewReceiver]. Keeping this separate from the test class means
 * there's a file-level Kt wrapper to inspect (the test class itself isn't a
 * Kt synthetic).
 */
@Suppress("unused")
fun topLevelPreviewFixture() = Unit
