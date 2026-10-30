package ee.schimke.composeai.preview

/** Test stand-in for the published `@ColorCatalog`; discovery matches it by name only. */
@Target(AnnotationTarget.FIELD) annotation class ColorCatalog(val name: String = "")
