package ee.schimke.composeai.wear.preview

// A stand-in for `:wear-preview-runtime`'s entry point, at the same owner class
// (`CapturingWearWidgetPreviewKt`) and name: what discovery recognises a Glance Wear widget preview
// by is a call to it, so the fixture only has to compile to that call. Deliberately not
// @Composable, like the other discovery fixtures.
@Suppress("FunctionName") fun CapturingWearWidgetPreview(content: () -> Unit) = content()
