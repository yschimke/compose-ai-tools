package ee.schimke.composeai.daemon.forensics

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Diagnostic library for the [classloader-forensics dump design](
 * ../../../../../../../docs/daemon/CLASSLOADER-FORENSICS.md).
 *
 * Per-class survey of the loaded class graph: classloader chain (with identity hash codes), code
 * source location + JAR/dir nature + SHA-256 checksum, Robolectric-instrumentation heuristic,
 * package versions, and a SHA-256 of the class's bytecode (`moduleHash`) reachable via the class's
 * own classloader. Plus a runtime-config snapshot for the active Robolectric configuration.
 *
 * **Renderer-agnostic.** Lives in `:daemon:core` because both `:renderer-android` (the working
 * standalone path, Configuration A) and `:daemon:android` (the broken daemon path, Configuration B)
 * need to call `capture(...)` against the *same* survey set — and the library itself touches only
 * `Class<?>` / `ClassLoader` / `getProtectionDomain` reflection, so it has no Compose / Robolectric
 * / AndroidX dependency at the type level.
 *
 * **Two entrypoints:**
 *
 * * [capture] — interrogates [surveySet] in the current JVM and writes the dump as JSON.
 * * [diff] — compares two dumps and writes a structured per-field diff (JSON) plus a human-readable
 *   summary (Markdown) that highlights the smoking-gun shape ("`Composer` loaded by different
 *   classloaders in A vs B") with ⚠️ flags. Sorted by suspected significance, not alphabetically.
 *
 * The library is renderer-agnostic by design; configuration A's `ClassloaderForensicsTest` and
 * configuration B's `ClassloaderForensicsDaemonTest` each call `capture(...)` with their own
 * context hint and survey set. Sanity checks (run A twice, dump byte-identical modulo timestamps;
 * dump `java.lang.String` always identical; etc.) live in the design doc and are realised by the
 * test bodies.
 */
object ClassloaderForensics {

  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
  }

  /**
   * Captures forensic data for [surveySet] and writes it to [out] as JSON.
   *
   * @param surveySet fully qualified class names to interrogate. Each FQN is loaded via
   *   `Class.forName(fqn, /*initialize=*/false, contextClassLoader)`; a `ClassNotFoundException` is
   *   recorded in the dump rather than thrown so the diff surfaces "class only in A" / "class only
   *   in B" cleanly.
   * @param robolectricConfig optional snapshot of the active Robolectric configuration. `null` when
   *   the caller isn't running inside a Robolectric sandbox (in practice that case never arises in
   *   v0 — both configurations A and B do run inside Robolectric — but the parameter is nullable so
   *   the library doesn't refuse a desktop-side use case).
   * @param contextHint short string identifying the configuration ("standalone-control" /
   *   "daemon-subject" / etc.). Recorded verbatim in the dump.
   * @param out target file. Parent directories are created if absent. Existing file is overwritten.
   */
  fun capture(
    surveySet: List<String>,
    robolectricConfig: RobolectricConfigSnapshot? = null,
    contextHint: String,
    out: File,
  ) {
    val seen = LinkedHashSet<String>()
    val classes = surveySet.filter { seen.add(it) }.map { fqn -> captureClass(fqn) }

    val systemSnapshot = captureSystem()

    val root = buildJsonObject {
      put("contextHint", contextHint)
      put("schemaVersion", "v0")
      put("classes", buildJsonArray { classes.forEach { add(it) } })
      if (robolectricConfig != null) {
        put("robolectric", json.encodeToJsonElement(robolectricConfig))
      }
      put("system", systemSnapshot)
    }

    out.parentFile?.mkdirs()
    out.writeText(json.encodeToString(root))
  }

  /**
   * Compares two captures and writes a structured per-field diff to [jsonOut] and a human-readable
   * summary to [mdOut].
   *
   * The Markdown summary is sorted by suspected significance per the design's
   * [diff interpretation guide](../../../../../../../docs/daemon/CLASSLOADER-FORENSICS.md#diff-interpretation-guide):
   *
   * 1. Smoking-gun: same FQN, different classloader-identity (or different `codeSource.location`,
   *    or different `moduleHash`). Flagged with ⚠️.
   * 2. `instrumentation.robolectricInstrumented` mismatches — the Android-bug-hunting case.
   * 3. Classes only in A / only in B.
   * 4. Robolectric-config field diffs.
   * 5. The remainder (loader-name diffs that don't affect identity, package version diffs).
   */
  fun diff(a: File, b: File, mdOut: File, jsonOut: File) {
    val aRoot = json.parseToJsonElement(a.readText()).asObject()
    val bRoot = json.parseToJsonElement(b.readText()).asObject()
    val aClasses = aRoot.classesArray(a).map { it.asObject() }.associateBy { it.fqn() }
    val bClasses = bRoot.classesArray(b).map { it.asObject() }.associateBy { it.fqn() }

    val onlyInA = aClasses.keys - bClasses.keys
    val onlyInB = bClasses.keys - aClasses.keys
    val both = aClasses.keys.intersect(bClasses.keys)

    data class ClassDiff(
      val fqn: String,
      val classloaderIdentityChanged: Boolean,
      val classloaderTypeChanged: Boolean,
      val codeSourceChanged: Boolean,
      val moduleHashChanged: Boolean,
      val instrumentedFlagChanged: Boolean,
      val packageVersionChanged: Boolean,
      val aLoader: String,
      val bLoader: String,
      val aLocation: String,
      val bLocation: String,
      val aInstrumented: String,
      val bInstrumented: String,
      val aModuleHash: String,
      val bModuleHash: String,
    ) {
      fun anyChange() =
        classloaderIdentityChanged ||
          classloaderTypeChanged ||
          codeSourceChanged ||
          moduleHashChanged ||
          instrumentedFlagChanged ||
          packageVersionChanged
    }

    val classDiffs = both.map { fqn ->
      val aCls = aClasses[fqn]!!
      val bCls = bClasses[fqn]!!
      val aClErr = aCls.stringOrNull("error")
      val bClErr = bCls.stringOrNull("error")
      if (aClErr != null || bClErr != null) {
        ClassDiff(
          fqn = fqn,
          classloaderIdentityChanged = aClErr != bClErr,
          classloaderTypeChanged = false,
          codeSourceChanged = false,
          moduleHashChanged = false,
          instrumentedFlagChanged = false,
          packageVersionChanged = false,
          aLoader = aClErr ?: "<loaded>",
          bLoader = bClErr ?: "<loaded>",
          aLocation = "",
          bLocation = "",
          aInstrumented = "",
          bInstrumented = "",
          aModuleHash = "",
          bModuleHash = "",
        )
      } else {
        val aLoader = aCls["classloader"]?.asObject()
        val bLoader = bCls["classloader"]?.asObject()
        val aLoaderId = aLoader?.stringOrNull("identityHashCode") ?: ""
        val bLoaderId = bLoader?.stringOrNull("identityHashCode") ?: ""
        val aLoaderType = aLoader?.stringOrNull("type") ?: ""
        val bLoaderType = bLoader?.stringOrNull("type") ?: ""
        val aCs = aCls["codeSource"]?.asObject()
        val bCs = bCls["codeSource"]?.asObject()
        val aLoc = aCs?.stringOrNull("location") ?: ""
        val bLoc = bCs?.stringOrNull("location") ?: ""
        val aInstr = aCls["instrumentation"]?.asObject()
        val bInstr = bCls["instrumentation"]?.asObject()
        val aInstrFlag = aInstr?.stringOrNull("robolectricInstrumented") ?: ""
        val bInstrFlag = bInstr?.stringOrNull("robolectricInstrumented") ?: ""
        val aMh = aCls.stringOrNull("moduleHash") ?: ""
        val bMh = bCls.stringOrNull("moduleHash") ?: ""
        val aPkg = aCls["package"]?.asObject()?.toString() ?: ""
        val bPkg = bCls["package"]?.asObject()?.toString() ?: ""
        ClassDiff(
          fqn = fqn,
          classloaderIdentityChanged = aLoaderId != bLoaderId,
          classloaderTypeChanged = aLoaderType != bLoaderType,
          codeSourceChanged = aLoc != bLoc,
          moduleHashChanged = aMh != bMh,
          instrumentedFlagChanged = aInstrFlag != bInstrFlag,
          packageVersionChanged = aPkg != bPkg,
          aLoader = "$aLoaderType@$aLoaderId",
          bLoader = "$bLoaderType@$bLoaderId",
          aLocation = aLoc,
          bLocation = bLoc,
          aInstrumented = aInstrFlag,
          bInstrumented = bInstrFlag,
          aModuleHash = aMh,
          bModuleHash = bMh,
        )
      }
    }

    // Smoking-gun rule excludes identity-hash-only differences. When A and B run in separate
    // test forks (the common case — one Gradle Test JVM per module), classloader identity
    // hashes always differ across runs even for byte-equivalent loaders. Real divergences
    // surface as either a different loader *type* (e.g. SandboxClassLoader vs AppClassLoader,
    // the actual Android save-loop bug) OR a different codeSource (different JAR) OR a
    // different moduleHash (same JAR path, different bytes — instrumentation drift, version
    // skew). Identity-hash-only differences within the same loader type are routed to
    // [identityNoise] for visibility without being flagged as a problem.
    val smokingGuns = classDiffs.filter {
      it.classloaderTypeChanged || it.codeSourceChanged || it.moduleHashChanged
    }
    val instrumentationDiffs = classDiffs.filter { it.instrumentedFlagChanged }
    val identityNoise = classDiffs.filter {
      it.classloaderIdentityChanged &&
        !it.classloaderTypeChanged &&
        !it.codeSourceChanged &&
        !it.moduleHashChanged &&
        !it.instrumentedFlagChanged
    }
    val otherChanges = classDiffs.filter {
      it.anyChange() && it !in smokingGuns && it !in instrumentationDiffs && it !in identityNoise
    }
    val unchanged = classDiffs.filter { !it.anyChange() }

    // Robolectric runtime-config diff
    val aRobo = aRoot["robolectric"]?.asObject()
    val bRobo = bRoot["robolectric"]?.asObject()
    val roboDiffs = mutableListOf<Triple<String, String, String>>()
    if (aRobo != null && bRobo != null) {
      val keys = (aRobo.keys + bRobo.keys).toSortedSet()
      for (key in keys) {
        val av = aRobo[key]?.toString() ?: "<absent>"
        val bv = bRobo[key]?.toString() ?: "<absent>"
        if (av != bv) roboDiffs.add(Triple(key, av, bv))
      }
    }

    val jsonReport = buildJsonObject {
      put("aContextHint", aRoot.stringOrNull("contextHint") ?: "")
      put("bContextHint", bRoot.stringOrNull("contextHint") ?: "")
      put(
        "smokingGuns",
        buildJsonArray {
          smokingGuns.forEach {
            add(
              buildJsonObject {
                put("fqn", it.fqn)
                put("aLoader", it.aLoader)
                put("bLoader", it.bLoader)
                put("aLocation", it.aLocation)
                put("bLocation", it.bLocation)
                put("aModuleHash", it.aModuleHash)
                put("bModuleHash", it.bModuleHash)
                put("classloaderIdentityChanged", it.classloaderIdentityChanged)
                put("codeSourceChanged", it.codeSourceChanged)
                put("moduleHashChanged", it.moduleHashChanged)
              }
            )
          }
        },
      )
      put(
        "instrumentationDiffs",
        buildJsonArray {
          instrumentationDiffs.forEach {
            add(
              buildJsonObject {
                put("fqn", it.fqn)
                put("aInstrumented", it.aInstrumented)
                put("bInstrumented", it.bInstrumented)
                put("aLoader", it.aLoader)
                put("bLoader", it.bLoader)
              }
            )
          }
        },
      )
      put("onlyInA", buildJsonArray { onlyInA.forEach { add(it) } })
      put("onlyInB", buildJsonArray { onlyInB.forEach { add(it) } })
      put(
        "robolectricConfigDiffs",
        buildJsonArray {
          roboDiffs.forEach { (k, av, bv) ->
            add(
              buildJsonObject {
                put("key", k)
                put("a", av)
                put("b", bv)
              }
            )
          }
        },
      )
      put(
        "otherChanges",
        buildJsonArray {
          otherChanges.forEach {
            add(
              buildJsonObject {
                put("fqn", it.fqn)
                put("classloaderTypeChanged", it.classloaderTypeChanged)
                put("instrumentedFlagChanged", it.instrumentedFlagChanged)
                put("packageVersionChanged", it.packageVersionChanged)
                put("aLoader", it.aLoader)
                put("bLoader", it.bLoader)
              }
            )
          }
        },
      )
      put(
        "identityNoise",
        buildJsonArray {
          identityNoise.forEach {
            add(
              buildJsonObject {
                put("fqn", it.fqn)
                put("aLoader", it.aLoader)
                put("bLoader", it.bLoader)
              }
            )
          }
        },
      )
      put("unchangedCount", unchanged.size)
      put("totalA", aClasses.size)
      put("totalB", bClasses.size)
    }
    jsonOut.parentFile?.mkdirs()
    jsonOut.writeText(json.encodeToString(jsonReport))

    // Markdown summary, sorted by suspected significance.
    val md = buildString {
      val aHint = aRoot.stringOrNull("contextHint") ?: "A"
      val bHint = bRoot.stringOrNull("contextHint") ?: "B"
      append("# Classloader forensics diff: $aHint vs $bHint\n\n")
      append("- A (control): `${a.absolutePath}`\n")
      append("- B (subject): `${b.absolutePath}`\n")
      append("- Survey size: A=${aClasses.size}, B=${bClasses.size}, both=${both.size}\n")
      append("- Unchanged: ${unchanged.size}/${both.size}\n\n")

      append("## 1. Smoking gun — different loader type / codeSource / bytecode\n\n")
      if (smokingGuns.isEmpty()) {
        append(
          "_None._ A and B agree on loader type, code source, and bytecode for every shared class. " +
            "(Identity-hash-only differences within the same loader type are routed to § 7 as " +
            "expected per-JVM-fork noise, not flagged here.)\n\n"
        )
      } else {
        append(
          "| FQN | A loader | B loader | A location | B location | type ≠ | codeSource ≠ | moduleHash ≠ |\n"
        )
        append("|---|---|---|---|---|---|---|---|\n")
        for (d in smokingGuns) {
          val typeFlag = if (d.classloaderTypeChanged) "⚠️ yes" else "no"
          val csFlag = if (d.codeSourceChanged) "⚠️ yes" else "no"
          val mhFlag = if (d.moduleHashChanged) "⚠️ yes" else "no"
          append(
            "| `${d.fqn}` | `${d.aLoader}` | `${d.bLoader}` | ${shortLoc(d.aLocation)} | ${shortLoc(d.bLocation)} | $typeFlag | $csFlag | $mhFlag |\n"
          )
        }
        append("\n")
      }

      append("## 2. Robolectric instrumentation flag mismatches\n\n")
      if (instrumentationDiffs.isEmpty()) {
        append(
          "_None._ Every shared class has the same `robolectricInstrumented` flag in A and B.\n\n"
        )
      } else {
        append("| FQN | A instrumented? | B instrumented? | A loader | B loader |\n")
        append("|---|---|---|---|---|\n")
        for (d in instrumentationDiffs) {
          append(
            "| `${d.fqn}` | ${d.aInstrumented} | ${d.bInstrumented} | `${d.aLoader}` | `${d.bLoader}` |\n"
          )
        }
        append("\n")
      }

      append("## 3. Classes only in A\n\n")
      if (onlyInA.isEmpty()) {
        append("_None._\n\n")
      } else {
        for (fqn in onlyInA.sorted()) append("- `$fqn`\n")
        append("\n")
      }

      append("## 4. Classes only in B\n\n")
      if (onlyInB.isEmpty()) {
        append("_None._\n\n")
      } else {
        for (fqn in onlyInB.sorted()) append("- `$fqn`\n")
        append("\n")
      }

      append("## 5. Robolectric runtime-config diffs\n\n")
      if (roboDiffs.isEmpty()) {
        append("_None._ Both runs report the same Robolectric config snapshot.\n\n")
      } else {
        append("| Key | A | B |\n|---|---|---|\n")
        for ((k, av, bv) in roboDiffs) {
          append("| `$k` | $av | $bv |\n")
        }
        append("\n")
      }

      append("## 6. Other changes (package-version drift, etc.)\n\n")
      if (otherChanges.isEmpty()) {
        append("_None._\n\n")
      } else {
        append("| FQN | A loader | B loader |\n|---|---|---|\n")
        for (d in otherChanges) {
          append("| `${d.fqn}` | `${d.aLoader}` | `${d.bLoader}` |\n")
        }
        append("\n")
      }

      append("## 7. Identity-hash noise (expected per-JVM-fork variance)\n\n")
      if (identityNoise.isEmpty()) {
        append(
          "_None._ Either both runs share a JVM (rare) or no shared classes had identity-hash differences.\n\n"
        )
      } else {
        append(
          "${identityNoise.size} classes load via the same loader type and bytecode in both " +
            "configurations but with different `identityHashCode`s. Expected when A and B run " +
            "in separate Gradle test forks — each Robolectric `Sandbox` builds a fresh " +
            "`SandboxClassLoader` whose identity hash is per-JVM-run randomness. **Not a bug** " +
            "unless A and B were supposed to share a JVM.\n\n"
        )
      }
    }
    mdOut.parentFile?.mkdirs()
    mdOut.writeText(md)
  }

  // ---- per-class capture ------------------------------------------------------------------------

  private fun captureClass(fqn: String): JsonElement {
    val ctxLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val cls: Class<*> =
      try {
        // initialize=false so the dump itself doesn't perturb static state.
        Class.forName(fqn, false, ctxLoader)
      } catch (t: Throwable) {
        return buildJsonObject {
          put("fqn", fqn)
          put("error", "${t.javaClass.simpleName}: ${t.message}")
        }
      }

    return buildJsonObject {
      put("fqn", fqn)
      put("classloader", classloaderChain(cls.classLoader))
      put("codeSource", codeSource(cls))
      put("instrumentation", instrumentation(cls))
      put("package", packageInfo(cls))
      put("moduleHash", moduleHash(cls, fqn))
    }
  }

  private fun classloaderChain(start: ClassLoader?): JsonElement {
    fun one(cl: ClassLoader?): JsonElement {
      if (cl == null) {
        return buildJsonObject { put("type", "<bootstrap>") }
      }
      val name: String? =
        try {
          val m = ClassLoader::class.java.getMethod("getName")
          m.invoke(cl) as String?
        } catch (_: Throwable) {
          null
        }
      return buildJsonObject {
        put("type", cl.javaClass.name)
        put("identityHashCode", "0x" + Integer.toHexString(System.identityHashCode(cl)))
        put("name", name ?: "")
        put("parent", one(cl.parent))
      }
    }
    return one(start)
  }

  private fun codeSource(cls: Class<*>): JsonElement {
    val pd =
      try {
        cls.protectionDomain
      } catch (_: Throwable) {
        null
      }
    val cs = pd?.codeSource
    val loc = cs?.location
    if (loc == null) {
      return buildJsonObject {
        put("location", "")
        put("isJar", false)
        put("isDirectory", false)
        put("checksum", "")
      }
    }
    val locStr = loc.toString()
    var isJar = false
    var isDirectory = false
    var checksum = ""
    try {
      val asFile =
        try {
          File(loc.toURI())
        } catch (_: Throwable) {
          null
        }
      if (asFile != null && asFile.exists()) {
        isJar = asFile.isFile && asFile.name.endsWith(".jar", ignoreCase = true)
        isDirectory = asFile.isDirectory
        if (asFile.isFile) {
          checksum = "sha256:" + sha256OfFile(asFile)
        }
      }
    } catch (_: Throwable) {
      // best effort; leave fields as defaults
    }
    return buildJsonObject {
      put("location", locStr)
      put("isJar", isJar)
      put("isDirectory", isDirectory)
      put("checksum", checksum)
    }
  }

  private fun instrumentation(cls: Class<*>): JsonElement {
    val cl = cls.classLoader
    var instrumented = false
    var method = "<not-detected>"
    if (cl != null) {
      // Walk up the classloader-class chain looking for InstrumentingClassLoader.
      // Cheap heuristic per the design.
      var c: Class<*>? = cl.javaClass
      while (c != null) {
        if (c.name == "org.robolectric.internal.bytecode.InstrumentingClassLoader") {
          instrumented = true
          method = "InstrumentingClassLoader applied bytecode rewrite"
          break
        }
        c = c.superclass
      }
      if (!instrumented) {
        // Robolectric 4.13 wraps the InstrumentingClassLoader in
        // `org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader` (which extends
        // `org.robolectric.internal.bytecode.InstrumentingClassLoader` → URLClassLoader). When
        // the inheritance walk above misses it (e.g. the InstrumentingClassLoader class itself
        // got loaded under a different classloader, so `c.name` comparison is satisfied but the
        // string compare wasn't), fall back on naming. The "SandboxClassLoader" suffix is
        // load-bearing — generic "Sandbox" elsewhere wouldn't necessarily mean instrumented.
        val name = cl.javaClass.name
        if (
          name.contains("SandboxClassLoader", ignoreCase = true) ||
            name.contains("InstrumentingClassLoader", ignoreCase = true) ||
            name.startsWith("org.robolectric.")
        ) {
          instrumented = true
          method = "loader class name ($name) matches Robolectric sandbox naming pattern"
        } else if (name.contains("Robolectric", ignoreCase = true)) {
          method = "loader name mentions Robolectric ($name) — heuristic uncertain"
        }
      }
    }
    // Shadow detection: probe for `__robo_data__` style instrumentation marker. Robolectric's
    // `InstrumentingClassLoader` historically rewrites instrumented classes to add a static
    // `$$robo$initData` field (or similar); presence is a strong signal but the field naming has
    // shifted across Robolectric releases. We only flag false-positives here, never claim
    // shadowed=true unless we see something concrete.
    var shadowed = false
    try {
      for (f in cls.declaredFields) {
        if (f.name.startsWith("\$\$robo$") || f.name.startsWith("__robo_")) {
          shadowed = true
          break
        }
      }
    } catch (_: Throwable) {
      // some classes refuse declaredFields under SecurityManager; ignore.
    }
    return buildJsonObject {
      put("robolectricInstrumented", instrumented.toString())
      put("robolectricShadowed", shadowed.toString())
      put("method", method)
    }
  }

  private fun packageInfo(cls: Class<*>): JsonElement {
    val pkg = cls.`package`
    if (pkg == null) {
      return buildJsonObject {
        put("specificationVersion", "")
        put("implementationVersion", "")
        put("implementationVendor", "")
      }
    }
    return buildJsonObject {
      put("specificationVersion", pkg.specificationVersion ?: "")
      put("implementationVersion", pkg.implementationVersion ?: "")
      put("implementationVendor", pkg.implementationVendor ?: "")
    }
  }

  private fun moduleHash(cls: Class<*>, fqn: String): String {
    val cl = cls.classLoader ?: return ""
    val resourceName = fqn.replace('.', '/') + ".class"
    return try {
      cl.getResourceAsStream(resourceName)?.use { stream -> "sha256:" + sha256OfStream(stream) }
        ?: ""
    } catch (_: Throwable) {
      ""
    }
  }

  private fun sha256OfFile(file: File): String = file.inputStream().use { sha256OfStream(it) }

  private fun sha256OfStream(input: java.io.InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8192)
    while (true) {
      val n = input.read(buf)
      if (n <= 0) break
      md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun captureSystem(): JsonElement = buildJsonObject {
    put("javaVersion", System.getProperty("java.version") ?: "")
    put("javaVendor", System.getProperty("java.vendor") ?: "")
    put("vmName", System.getProperty("java.vm.name") ?: "")
    put("osArch", System.getProperty("os.arch") ?: "")
    put("osName", System.getProperty("os.name") ?: "")
  }

  // ---- JSON helpers -----------------------------------------------------------------------------

  private fun JsonElement.asObject(): JsonObject = this as JsonObject

  private fun JsonElement.asArray() = (this as kotlinx.serialization.json.JsonArray)

  private fun JsonObject.classesArray(source: File): kotlinx.serialization.json.JsonArray =
    this["classes"] as? kotlinx.serialization.json.JsonArray
      ?: error("Forensics capture ${source.absolutePath} is missing the 'classes' array")

  private fun JsonObject.fqn(): String =
    (this["fqn"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

  private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

  private fun shortLoc(loc: String): String {
    if (loc.isEmpty()) return "_(none)_"
    val slash = loc.lastIndexOf('/')
    val tail = if (slash >= 0) loc.substring(slash + 1) else loc
    return "`$tail`"
  }

  private fun Json.encodeToJsonElement(snapshot: RobolectricConfigSnapshot): JsonElement =
    encodeToJsonElementImpl(snapshot)

  private fun encodeToJsonElementImpl(s: RobolectricConfigSnapshot): JsonElement = buildJsonObject {
    put("apiLevel", s.apiLevel)
    put("qualifiers", s.qualifiers)
    put("fontScale", s.fontScale.toString())
    put("applicationClassName", s.applicationClassName)
    put("graphicsMode", s.graphicsMode)
    put("looperMode", s.looperMode)
    put("instrumentedPackages", buildJsonArray { s.instrumentedPackages.forEach { add(it) } })
    put("doNotInstrumentPackages", buildJsonArray { s.doNotInstrumentPackages.forEach { add(it) } })
    put("doNotAcquirePackages", buildJsonArray { s.doNotAcquirePackages.forEach { add(it) } })
    put("sandboxFactoryClassName", s.sandboxFactoryClassName)
    put("instrumentingClassLoaderIdentity", s.instrumentingClassLoaderIdentity)
    put("sandboxAgeMs", s.sandboxAgeMs?.toString() ?: "")
    put("sandboxRenderCount", s.sandboxRenderCount?.toString() ?: "")
  }
}

/**
 * Active Robolectric configuration captured alongside the per-class survey. Filled in by the test
 * body (Configuration A or B) using `RuntimeEnvironment` + reflection into the
 * `InstrumentingClassLoader` filter lists. Per the design, fields requiring brittle reflection
 * across Robolectric versions are populated best-effort and documented inline in the test.
 */
@Serializable
data class RobolectricConfigSnapshot(
  val apiLevel: Int,
  val qualifiers: String,
  val fontScale: Float,
  val applicationClassName: String,
  val graphicsMode: String,
  val looperMode: String,
  val instrumentedPackages: List<String>,
  val doNotInstrumentPackages: List<String>,
  val doNotAcquirePackages: List<String>,
  val sandboxFactoryClassName: String,
  /** `0x` + lowercase hex of `System.identityHashCode(InstrumentingClassLoader instance)`. */
  val instrumentingClassLoaderIdentity: String,
  val sandboxAgeMs: Long? = null,
  val sandboxRenderCount: Long? = null,
)
