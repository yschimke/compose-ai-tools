package ee.schimke.composeai.discovery

import ee.schimke.composeai.preview.ColorCatalog
import io.github.classgraph.ClassGraph
import org.junit.Assert.assertEquals
import org.junit.Test

/** Declared out of alphabetical order on purpose: a catalog sheet lists tokens as written. */
object CatalogFieldOrderFixture {
  @field:ColorCatalog val Coral = 1
  @field:ColorCatalog val Teal = 2
  val Unannotated = 3
  @field:ColorCatalog val Gold = 4
}

/**
 * Pins [PreviewDiscovery.catalogFieldsInDeclarationOrder]. ClassGraph 5 sorts a class's fields by
 * name, which reordered every colour, typography and shape catalog sheet alphabetically.
 */
class CatalogFieldOrderTest {
  @Test
  fun `catalog fields keep their declaration order, not ClassGraph's name order`() {
    val names =
      ClassGraph()
        .enableClasspath()
        .enableClassInfo()
        .enableFieldInfo()
        .ignoreFieldVisibility()
        .enableAnnotationInfo()
        .acceptClasses(CatalogFieldOrderFixture::class.java.name)
        .scan()
        .use { scan ->
          val classInfo = scan.getClassInfo(CatalogFieldOrderFixture::class.java.name)!!
          PreviewDiscovery.catalogFieldsInDeclarationOrder(classInfo).map { it.name }
        }

    assertEquals(listOf("Coral", "Teal", "Gold"), names)
  }

  @Test
  fun `methods keep their declaration order, overloads included`() {
    val names =
      ClassGraph()
        .enableClasspath()
        .enableClassInfo()
        .enableMethodInfo()
        .ignoreMethodVisibility()
        .acceptClasses(MethodOrderFixture::class.java.name)
        .scan()
        .use { scan ->
          val classInfo = scan.getClassInfo(MethodOrderFixture::class.java.name)!!
          val wanted = classInfo.methodInfo.filter { it.name in setOf("zebra", "apple", "mango") }
          PreviewDiscovery.inDeclarationOrder(classInfo, wanted).map {
            it.name + it.parameterInfo.size
          }
        }

    assertEquals(listOf("zebra0", "apple0", "zebra1", "mango0"), names)
  }
}

/** Methods declared out of alphabetical order, with an overload split around another method. */
@Suppress("unused", "UNUSED_PARAMETER")
class MethodOrderFixture {
  fun zebra() = Unit

  fun apple() = Unit

  fun zebra(x: Int) = Unit

  fun mango() = Unit
}
