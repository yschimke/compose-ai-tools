package ee.schimke.composeai.discovery

import io.github.classgraph.ClassGraph
import java.io.File

/** Metadata-only discovery for explicitly selected top-level Kotlin adapter entry points. */
object TypedAdapterDiscovery {
  private const val COMPOSABLE = "androidx.compose.runtime.Composable"

  /** JVM file-facade owner and method name, for example `com.acme.ButtonKt` / `BrandButton`. */
  data class Callable(val owner: String, val name: String)

  /**
   * Scan compiled code without loading or executing application classes. A null classpath scans the
   * export process's runtime classpath; build integrations can supply their compiled outputs. This
   * selects entry points rather than following every call made by an app's previews.
   */
  fun discover(
    module: String,
    variant: String,
    callables: List<Callable>,
    classpath: List<File>? = null,
  ): ComponentRecordFile {
    require(module.isNotBlank() && variant.isNotBlank()) { "module and variant must be non-blank" }
    require(callables.isNotEmpty() && callables.distinct().size == callables.size) {
      "declare distinct callable entry points"
    }
    require(callables.all { it.owner.contains('.') && it.name.isNotBlank() }) {
      "callables require a qualified owner and a method name"
    }
    val graph =
      ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .acceptClasses(*callables.map { it.owner }.distinct().toTypedArray())
    if (classpath != null) graph.enableClasspathEntries(classpath) else graph.enableClasspath()
    return graph.scan().use { scan ->
      val components =
        callables
          .map { selected ->
            val owner =
              requireNotNull(scan.getClassInfo(selected.owner)) {
                "missing compiled owner ${selected.owner}"
              }
            val methods = owner.methodInfo.filter { it.name == selected.name }
            require(methods.size == 1) {
              "${selected.owner}.${selected.name}: expected one callable, found ${methods.size}"
            }
            val method = methods.single()
            require(method.isStatic && ComposableSignature.isTopLevel(owner)) {
              "${selected.owner}.${selected.name}: use an ordinary top-level Kotlin export wrapper"
            }
            val signature =
              requireNotNull(ComposableSignature.signatureOf(owner, method, scan)) {
                "${selected.owner}.${selected.name}: signature metadata missing"
              }
            val record =
              ComponentRecord.Builder(
                  "$module/${selected.owner}.${signature.name}",
                  ComponentSymbol.Builder(
                      selected.owner,
                      selected.owner.substringBeforeLast('.') + "." + signature.name,
                      signature.name,
                      ComponentOrigin.PROJECT,
                    )
                    .also {
                      it.descriptor = method.typeDescriptorString
                      it.jvmName = selected.name
                      it.receiver = signature.receiver
                    }
                    .build(),
                )
                .also {
                  it.signatureKnown = true
                  it.parameters = signature.parameters
                  it.slots = ComponentRecords.slotsOf(signature.parameters)
                  it.callableFromAnotherFile = signature.callableFromAnotherFile
                  it.hasTypeParameters = signature.hasTypeParameters
                  it.hasContextReceivers = signature.hasContextReceivers
                  it.requiredOptIns = signature.requiredOptIns
                  it.androidxOptIns = signature.androidxOptIns
                }
                .build()
            record.newBuilder().also { it.code = ComponentSnippets.codeFor(record) }.build()
          }
          .sortedBy { it.canonicalId }
      ComponentRecordFile.Builder(module, variant, components).build()
    }
  }

  /**
   * A library composable a typed definition targets: its JVM file facade [owner] and source [name].
   * Kotlin overloads it by name, as Wear's `Button` is overloaded on `label` and `content`, so
   * [declaring] picks the overload the record's own parameters come from: the one non-deprecated
   * overload declaring every name in it. Where overloads differ only in a type, as `Text` takes
   * `text: String` or `text: AnnotatedString`, [types] also fixes a parameter's type by name; and
   * where one overload adds parameters to another, as the image `Button` adds `containerPainter`,
   * [excluding] names parameters the picked overload must not declare. All empty picks the only
   * non-deprecated overload.
   */
  data class LibraryCallable(
    val owner: String,
    val name: String,
    val declaring: Set<String> = emptySet(),
    val types: Map<String, String> = emptyMap(),
    val excluding: Set<String> = emptySet(),
  )

  /**
   * Metadata-only discovery of the real library composables a catalog's typed definitions target,
   * for an export projection to call them as they are. Unlike [discover], a callable may be
   * overloaded: every overload with readable metadata is published as [ComponentRecord.overloads],
   * so validation resolves a projection's arguments the way Kotlin resolves named arguments, and
   * the record's own parameters are the overload [LibraryCallable] picks. A deprecated overload is
   * never picked, and is not published.
   */
  fun discoverLibrary(
    module: String,
    variant: String,
    callables: List<LibraryCallable>,
    classpath: List<File>? = null,
  ): ComponentRecordFile {
    require(module.isNotBlank() && variant.isNotBlank()) { "module and variant must be non-blank" }
    require(
      callables.isNotEmpty() && callables.distinctBy { it.owner to it.name }.size == callables.size
    ) {
      "declare distinct library callables"
    }
    require(callables.all { it.owner.contains('.') && it.name.isNotBlank() }) {
      "callables require a qualified owner and a source name"
    }
    val graph =
      ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .acceptClasses(*callables.map { it.owner }.distinct().toTypedArray())
    if (classpath != null) graph.enableClasspathEntries(classpath) else graph.enableClasspath()
    return graph.scan().use { scan ->
      val components =
        callables
          .map { selected ->
            val where = "${selected.owner}.${selected.name}"
            val owner =
              requireNotNull(scan.getClassInfo(selected.owner)) {
                "missing compiled owner ${selected.owner}"
              }
            require(ComposableSignature.isTopLevel(owner)) {
              "$where: not a top-level Kotlin file facade"
            }
            val overloads =
              owner.methodInfo
                // A composable only: a plain helper of the same name is never a component.
                .filter {
                  it.isStatic &&
                    it.hasAnnotation(COMPOSABLE) &&
                    !ComposableSignature.isDeprecated(it)
                }
                .mapNotNull { method ->
                  ComposableSignature.signatureOf(owner, method, scan)
                    ?.takeIf { it.name == selected.name }
                    ?.let { method to it }
                }
            require(overloads.isNotEmpty()) {
              "$where: no non-deprecated composable overload with readable metadata"
            }
            val picked = overloads.filter { (_, signature) ->
              selected.declaring.all { name -> signature.parameters.any { it.name == name } } &&
                selected.types.all { (name, type) ->
                  signature.parameters.any { it.name == name && it.typeFqn == type }
                } &&
                signature.parameters.none { it.name in selected.excluding }
            }
            require(picked.size == 1) {
              "$where: ${picked.size} non-deprecated overloads declare ${selected.declaring.sorted()} " +
                "with ${selected.types}, excluding ${selected.excluding.sorted()}; name parameters that pick one of " +
                overloads.joinToString("; ") { (_, s) ->
                  s.parameters.joinToString(", ", "(", ")") { "${it.name}: ${it.typeFqn}" }
                }
            }
            val (method, signature) = picked.single()
            val record =
              ComponentRecord.Builder(
                  "$module/${selected.owner}.${signature.name}",
                  ComponentSymbol.Builder(
                      selected.owner,
                      selected.owner.substringBeforeLast('.') + "." + signature.name,
                      signature.name,
                      ComponentOrigin.LIBRARY,
                    )
                    .also {
                      it.descriptor = method.typeDescriptorString
                      it.jvmName = method.name
                      it.receiver = signature.receiver
                    }
                    .build(),
                )
                .also {
                  it.signatureKnown = true
                  it.parameters = signature.parameters
                  it.slots = ComponentRecords.slotsOf(signature.parameters)
                  it.callableFromAnotherFile = signature.callableFromAnotherFile
                  it.hasTypeParameters = signature.hasTypeParameters
                  it.hasContextReceivers = signature.hasContextReceivers
                  it.requiredOptIns = signature.requiredOptIns
                  it.androidxOptIns = signature.androidxOptIns
                  it.overloads = overloads.map { (m, s) ->
                    ComponentOverload.Builder()
                      .also { o ->
                        o.descriptor = m.typeDescriptorString
                        o.parameters = s.parameters
                      }
                      .build()
                  }
                }
                .build()
            record.newBuilder().also { it.code = ComponentSnippets.codeFor(record) }.build()
          }
          .sortedBy { it.canonicalId }
      ComponentRecordFile.Builder(module, variant, components).build()
    }
  }

  /**
   * The factories a typed definition's export projection calls — `ButtonDefaults` members such as
   * `filledTonalButtonColors` — with every overload's parameters and return type, so
   * [TypedComponentAdapter.validateAgainst] can check a `call(…)` by argument name and type rather
   * than trusting a string. [Callable.owner] is the object's class (`…material3.ButtonDefaults`),
   * not a file facade. Metadata only: no class is loaded.
   */
  fun discoverFactories(
    factories: List<Callable>,
    classpath: List<File>? = null,
  ): List<FactorySignature> {
    require(factories.isNotEmpty() && factories.distinct().size == factories.size) {
      "declare distinct factories"
    }
    val graph =
      ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .acceptClasses(*factories.map { it.owner }.distinct().toTypedArray())
    if (classpath != null) graph.enableClasspathEntries(classpath) else graph.enableClasspath()
    // Overloads of one factory, and factories of one family, share a return type.
    val ancestry = mutableMapOf<String, Set<String>>()
    return graph.scan().use { scan ->
      factories.flatMap { factory ->
        val owner =
          requireNotNull(scan.getClassInfo(factory.owner)) {
            "missing compiled owner ${factory.owner}"
          }
        ComposableSignature.factorySignatures(owner, factory.name) {
            ancestry.getOrPut(it) { supertypes(it, classpath) }
          }
          .also {
            require(it.isNotEmpty()) {
              "${factory.owner}.${factory.name}: no public member of an object or companion with readable metadata"
            }
          }
      }
    }
  }

  /**
   * Every class and interface [jvmName] extends, transitively, as qualified names with nested
   * classes dotted (`…Arrangement.Vertical`). Read level by level from the compiled classes, so a
   * supertype outside the classpath ends the walk there. `Any` / `Object` are left out.
   */
  private fun supertypes(jvmName: String, classpath: List<File>?): Set<String> {
    val found = mutableSetOf<String>()
    var pending = setOf(jvmName)
    val seen = mutableSetOf(jvmName)
    while (pending.isNotEmpty()) {
      // External: a supertype outside the accepted set is still named, and read next level.
      val graph =
        ClassGraph()
          .enableClassInfo()
          .enableExternalClasses()
          .acceptClasses(*pending.toTypedArray())
      if (classpath != null) graph.enableClasspathEntries(classpath) else graph.enableClasspath()
      val next = mutableSetOf<String>()
      graph.scan().use { scan ->
        for (name in pending) {
          val info = scan.getClassInfo(name) ?: continue
          val direct =
            listOfNotNull(info.superclass?.name) + info.directSuperinterfaces.map { it.name }
          for (parent in direct) {
            if (parent == "java.lang.Object" || !seen.add(parent)) continue
            found += parent.replace('$', '.')
            next += parent
          }
        }
      }
      pending = next
    }
    return found
  }
}
