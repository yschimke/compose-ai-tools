package ee.schimke.composeai.discovery

import ee.schimke.composeai.wear.preview.CapturingWearWidgetPreview

// Fixtures for PreviewWidgetTest: preview bodies as the bytecode walk sees them.

/** A Wear widget sticker: content drawn through the widget-preview entry point. */
@Suppress("unused", "FunctionName")
fun WearWidgetStickerFixture() = CapturingWearWidgetPreview { sampleWidgetContent() }

/** A component sticker of the same size: no widget entry point anywhere in its body. */
@Suppress("unused", "FunctionName") fun ComponentStickerFixture() = sampleWidgetContent()

/** A project wrapper around the entry point, as a catalog's own sticker frame would be. */
@Suppress("unused", "FunctionName")
fun WrappedWidgetFrameFixture(content: () -> Unit) = CapturingWearWidgetPreview(content)

/** A sticker drawing through that wrapper. */
@Suppress("unused", "FunctionName")
fun WrappedWearWidgetStickerFixture() = WrappedWidgetFrameFixture { sampleWidgetContent() }

internal fun sampleWidgetContent() {}
