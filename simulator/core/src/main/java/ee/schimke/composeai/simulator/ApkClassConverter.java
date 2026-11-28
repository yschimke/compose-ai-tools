package ee.schimke.composeai.simulator;

import com.googlecode.d2j.Field;
import com.googlecode.d2j.Method;
import com.googlecode.d2j.converter.IR2JConverter;
import com.googlecode.d2j.dex.BaseDexExceptionHandler;
import com.googlecode.d2j.dex.ClassVisitorFactory;
import com.googlecode.d2j.dex.ExDex2Asm;
import com.googlecode.d2j.dex.LambadaNameSafeClassAdapter;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.d2j.node.DexMethodNode;
import com.googlecode.d2j.reader.BaseDexFileReader;
import com.googlecode.d2j.reader.DexFileReader;
import com.googlecode.d2j.reader.MultiDexFileReader;
import com.googlecode.d2j.smali.BaksmaliDumper;
import com.googlecode.d2j.util.Out;
import com.googlecode.d2j.visitors.DexClassVisitor;
import com.googlecode.d2j.visitors.DexFieldVisitor;
import com.googlecode.d2j.visitors.DexMethodVisitor;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.Trap;
import com.googlecode.dex2jar.ir.expr.BinopExpr;
import com.googlecode.dex2jar.ir.expr.Constant;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.InvokeExpr;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.NewExpr;
import com.googlecode.dex2jar.ir.expr.PhiExpr;
import com.googlecode.dex2jar.ir.expr.RefExpr;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.AssignStmt;
import com.googlecode.dex2jar.ir.stmt.BaseSwitchStmt;
import com.googlecode.dex2jar.ir.stmt.GotoStmt;
import com.googlecode.dex2jar.ir.stmt.IfStmt;
import com.googlecode.dex2jar.ir.stmt.LabelStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import com.googlecode.dex2jar.ir.stmt.Stmts;
import com.googlecode.dex2jar.ir.ts.Cfg;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Turns an APK's `classes*.dex` into one jar Robolectric's simulator can load as an extra
 * classpath entry, in two passes:
 *
 * <ol>
 *   <li>dex2jar translates every dex in the APK, keeping line numbers. A class with a method it
 *       cannot translate is taken from a second translation without debug info when that one
 *       manages; a method neither can becomes a stub that throws, and is counted rather than
 *       failing the whole APK. Integer comparisons against a zero on the left are mirrored
 *       before code generation, which dex2jar gets backwards ({@link #normalizeZeroLeftJumps}).
 *   <li>An ASM pass makes the output loadable by a verifying JVM:
 *       <ul>
 *         <li>calls to static and private interface methods become interface method references.
 *             Dex does not record whether an `invoke-static` owner is an interface, so dex2jar
 *             always emits a class `Methodref`, which the JVM rejects with
 *             `IncompatibleClassChangeError` for every Kotlin `$default` and Java static interface
 *             method;
 *         <li>stack-map frames and stack/locals maxima are computed from scratch. dex2jar writes no
 *             frames, and undercounts `max_stack` around exception handlers (a few hundred of the
 *             sample app's 140k methods) — enough, with verification off, for the GC to crash the
 *             JVM building an oop map for one of those frames. Supertypes come from the APK
 *             itself, then the platform `android.jar`, then the JDK, read from class headers.
 *         <li>protected fields and methods become public, as ART does not enforce the JVM's
 *             protected-receiver rule and shrunk code depends on that.
 *       </ul>
 * </ol>
 *
 * <p>Runs in its own JVM, with `lib-dex2jar/` on the classpath, and prints one JSON summary line on
 * stdout: `{"classes":n,"interfaceCallsFixed":n,"failedMethods":n,"unframedClasses":n,
 * "withoutDebugInfo":n,"failures":[...]}`.
 */
public final class ApkClassConverter {

  /** How many translation failures the summary names; the count is always exact. */
  private static final int REPORTED_FAILURES = 20;


  public static void main(String[] args) throws IOException {
    if (args.length != 3 && args.length != 4) {
      System.err.println(
          "usage: ApkClassConverter <app.apk> <out.jar> <platform android.jar> [previous.jar]");
      System.exit(2);
    }
    Path previous = args.length == 4 ? Paths.get(args[3]) : null;
    Summary summary = convert(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), previous);
    System.out.println(summary.toJson());
  }

  /** What one conversion produced. */
  public static final class Summary {
    public int classes;
    public int interfaceCallsFixed;
    public int failedMethods;
    public int unframedClasses;
    /** Classes taken from the translation without debug info, because the first one failed them. */
    public int withoutDebugInfo;
    /** The APK's copies of shared-library classes the simulator provides, which were dropped. */
    public int sharedLibraryClasses;
    /** Classes copied from the previous conversion, unchanged, instead of translated again. */
    public int reused;
    public final List<String> failures = new ArrayList<>();

    void add(Index.Entry entry) {
      classes++;
      interfaceCallsFixed += entry.interfaceCallsFixed;
      unframedClasses += entry.unframed ? 1 : 0;
      withoutDebugInfo += entry.withoutDebugInfo ? 1 : 0;
      failedMethods += entry.failedMethods;
      for (String failure : entry.failures) {
        if (failures.size() < REPORTED_FAILURES) failures.add(failure);
      }
    }

    String toJson() {
      StringBuilder json = new StringBuilder();
      json.append("{\"classes\":").append(classes);
      json.append(",\"interfaceCallsFixed\":").append(interfaceCallsFixed);
      json.append(",\"failedMethods\":").append(failedMethods);
      json.append(",\"unframedClasses\":").append(unframedClasses);
      json.append(",\"withoutDebugInfo\":").append(withoutDebugInfo);
      json.append(",\"sharedLibraryClasses\":").append(sharedLibraryClasses);
      json.append(",\"reused\":").append(reused);
      json.append(",\"failures\":[");
      for (int i = 0; i < failures.size(); i++) {
        if (i > 0) json.append(',');
        json.append('"').append(escape(failures.get(i))).append('"');
      }
      return json.append("]}").toString();
    }
  }

  /** The index written beside a converted jar: {@code classes.jar.index}. */
  public static Path indexOf(Path jar) {
    return jar.resolveSibling(jar.getFileName() + ".index");
  }

  public static Summary convert(Path apk, Path out, Path platformJar) throws IOException {
    return convert(apk, out, platformJar, null);
  }

  /**
   * Converts [apk] into [out], and writes {@link #indexOf}([out]) beside it. With a [previous]
   * conversion of an earlier build of the same app (and its index), by this same converter, each
   * class whose dex is unchanged is copied from it instead of translated again — when its
   * inner-class facts are unchanged too, and every supertype lookup its frames were computed from
   * still resolves the same way.
   */
  public static Summary convert(Path apk, Path out, Path platformJar, Path previous)
      throws IOException {
    Summary summary = new Summary();
    BaseDexFileReader reader;
    try {
      // The APK's bytes are dropped once its dex files are extracted.
      reader = MultiDexFileReader.open(Files.readAllBytes(apk));
    } catch (IOException e) {
      // As dex2jar does: an APK with no dex (a resource-only app) converts to an empty jar.
      new BaseDexExceptionHandler().handleFileException(e);
      reader = new MultiDexFileReader(List.of());
    }
    Translator.ClassInfo classInfo = Translator.classInfo(reader);
    Map<String, Index.Entry> before =
        previous == null ? Map.of() : Index.read(indexOf(previous));
    List<String> names = reader.getClassNames();
    int count = names.size();
    Index.Entry[] reused = new Index.Entry[count];
    Translation[] fresh = new Translation[count];
    String[] keys = new String[count];
    Path translated = Files.createTempFile(out.toAbsolutePath().getParent(), "dex2jar-", ".jar");
    try (ZipFile previousJar = before.isEmpty() ? null : new ZipFile(previous.toFile());
        ZipFile platform = new ZipFile(platformJar.toFile())) {
      // Pass 1: dex2jar, one class at a time, for each class the previous conversion lacks.
      Set<String> translatedNames = new HashSet<>();
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(translated))) {
        for (int i = 0; i < count; i++) {
          if (providedBySimulator(
              internalName(names.get(i)) + ".class", ApkClassConverter.class.getClassLoader())) {
            summary.sharedLibraryClasses++;
            continue;
          }
          DexClassNode node = read(reader, i, false);
          if (node == null) continue;
          keys[i] = Translator.key(node, classInfo);
          Index.Entry earlier = before.get(names.get(i));
          if (earlier != null
              && earlier.key.equals(keys[i])
              && earlier.entryName != null
              && previousJar.getEntry(earlier.entryName) != null) {
            reused[i] = earlier;
            continue;
          }
          fresh[i] = translate(reader, i, node, classInfo);
          // Two dex classes renamed to one class file: the first is kept.
          if (fresh[i].entryName != null && !translatedNames.add(fresh[i].entryName)) {
            fresh[i] = null;
          } else if (fresh[i].entryName != null) {
            zip.putNextEntry(new ZipEntry(fresh[i].entryName));
            zip.write(fresh[i].bytes);
            zip.closeEntry();
            fresh[i].bytes = null;
          }
        }
      }
      // Collect forwarding <init> constructors required by R8 constructor-inlined calls across all
      // translated and reused classes before Pass 2 writes or reuses any class file.
      Map<String, Set<String>> neededInits = new HashMap<>();
      for (int i = 0; i < count; i++) {
        Iterable<String> calls =
            reused[i] != null
                ? splitSpaces(reused[i].inlinedInits)
                : (fresh[i] != null ? fresh[i].inlinedInits : List.of());
        for (String call : calls) {
          int p1 = call.indexOf('|');
          int p2 = call.indexOf('|', p1 + 1);
          if (p1 > 0 && p2 > p1) {
            classInfo.recordNeededInits(
                call.substring(0, p1),
                call.substring(p1 + 1, p2),
                call.substring(p2 + 1),
                neededInits);
          }
        }
      }
      // Pass 2: interface calls, synthesized forwarding <init>s, frames and access, with the whole
      // hierarchy to hand.
      try (ZipFile pass1 = new ZipFile(translated.toFile());
          ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out));
          Index.Writer index = new Index.Writer(indexOf(out))) {
        Map<String, ClassHierarchy.Header> appClasses = new HashMap<>();
        for (int i = 0; i < count; i++) {
          byte[] bytes = null;
          if (reused[i] != null) bytes = entryBytes(previousJar, reused[i].entryName);
          else if (fresh[i] != null && fresh[i].entryName != null) {
            bytes = entryBytes(pass1, fresh[i].entryName);
          }
          if (bytes == null) continue;
          ClassReader header = new ClassReader(bytes);
          appClasses.put(header.getClassName(), ClassHierarchy.Header.of(header));
        }
        try (ClassHierarchy hierarchy = new ClassHierarchy(appClasses, platform)) {
          Set<String> written = new HashSet<>();
          for (int i = 0; i < count; i++) {
            Set<String> synth =
                new TreeSet<>(neededInits.getOrDefault(names.get(i), Collections.emptySet()));
            String synthEncoded = String.join(" ", synth);
            Index.Entry entry;
            byte[] bytes;
            if (reused[i] != null
                && reused[i].synthesizedInits.equals(synthEncoded)
                && hierarchy.resolvesAsBefore(reused[i].dependencies)) {
              entry = reused[i];
              bytes = entryBytes(previousJar, entry.entryName);
              summary.reused++;
            } else {
              Translation translation = fresh[i];
              byte[] input;
              if (translation != null) {
                input =
                    translation.entryName == null ? null : entryBytes(pass1, translation.entryName);
              } else if (reused[i] != null) {
                // Unchanged, but a supertype or synthesized <init> set changed: translate it again.
                DexClassNode node = read(reader, i, false);
                if (node == null) continue;
                translation = translate(reader, i, node, classInfo);
                input = translation.bytes;
              } else {
                continue;
              }
              if (input == null) continue;
              entry = new Index.Entry(names.get(i), keys[i], translation.entryName);
              entry.withoutDebugInfo = translation.withoutDebugInfo;
              entry.failedMethods = translation.failures.size();
              entry.failures.addAll(translation.failures);
              entry.inlinedInits = String.join(" ", translation.inlinedInits);
              entry.synthesizedInits = synthEncoded;
              Summary one = new Summary();
              Set<String> lookups = new TreeSet<>();
              hierarchy.recordInto(lookups);
              for (String call : translation.inlinedInits) {
                int p1 = call.indexOf('|');
                int p2 = call.indexOf('|', p1 + 1);
                if (p1 > 0 && p2 > p1) {
                  hierarchy.commonSuperClass(
                      internalName(call.substring(0, p1)),
                      internalName(call.substring(p1 + 1, p2)));
                }
              }
              bytes = rewrite(input, synth, hierarchy, one);
              hierarchy.recordInto(null);
              entry.interfaceCallsFixed = one.interfaceCallsFixed;
              entry.unframed = one.unframedClasses > 0;
              entry.failures.addAll(one.failures);
              entry.dependencies = hierarchy.describe(lookups);
            }
            if (!written.add(entry.entryName)) continue;
            summary.add(entry);
            index.write(entry);
            zip.putNextEntry(new ZipEntry(entry.entryName));
            zip.write(bytes);
            zip.closeEntry();
          }
          copyJavaResources(apk, zip, written);
        }
      }
    } finally {
      Files.deleteIfExists(translated);
    }
    return summary;
  }

  /**
   * Copies Java classpath resources packaged in [apk] (such as {@code META-INF/services/*},
   * ByteBuddy's {@code inject-MockMethodDispatcher.raw}, and {@code getResourceAsStream} fixtures)
   * into the converted jar. Android-only packaging artifacts ({@code classes*.dex}, {@code res/},
   * {@code assets/}, {@code lib/}, signing files, and Android ART {@code dexmaker} Mockito plugin
   * descriptors) are left out so JVM-side tools like Mockito's ByteBuddy mock maker work natively.
   */
  private static void copyJavaResources(Path apk, ZipOutputStream out, Set<String> written)
      throws IOException {
    try (ZipFile apkZip = new ZipFile(apk.toFile())) {
      Enumeration<? extends ZipEntry> entries = apkZip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || !isJavaResourcePath(name) || written.contains(name)) continue;
        byte[] data;
        try (InputStream in = apkZip.getInputStream(entry)) {
          data = in.readAllBytes();
        }
        if (!shouldCopyResource(name, data)) continue;
        if (!written.add(name)) continue;
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
      }
    } catch (IOException e) {
      // Not a zip (e.g. a bare .dex in unit tests): nothing to copy.
    }
  }

  static boolean isJavaResourcePath(String name) {
    if (name == null || name.isEmpty() || name.endsWith("/") || name.endsWith(".class")) {
      return false;
    }
    if (name.equals("AndroidManifest.xml")
        || name.equals("resources.arsc")
        || name.equals("stamp-cert-sha256")
        || name.startsWith("res/")
        || name.startsWith("assets/")
        || name.startsWith("lib/")
        || name.startsWith("dexopt/")
        || name.startsWith("META-INF/com/android/")
        || name.startsWith("META-INF/services/androidx.test.")
        || name.startsWith("META-INF/services/org.robolectric.")) {
      return false;
    }
    if (name.startsWith("classes") && name.endsWith(".dex")) {
      String mid = name.substring("classes".length(), name.length() - ".dex".length());
      if (mid.isEmpty() || mid.chars().allMatch(Character::isDigit)) return false;
    }
    if (name.startsWith("META-INF/")) {
      String rest = name.substring("META-INF/".length());
      if (!rest.contains("/")) {
        if (rest.equals("MANIFEST.MF")
            || rest.endsWith(".SF")
            || rest.endsWith(".RSA")
            || rest.endsWith(".DSA")
            || rest.endsWith(".EC")
            || rest.startsWith("CERT.")) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean shouldCopyResource(String name, byte[] data) {
    if (!isJavaResourcePath(name)) return false;
    // mockito-android bundles dexmaker descriptors under mockito-extensions/; dexmaker requires
    // ART/JVMTI and fails on a desktop JVM, whereas dropping those lets Mockito use its JVM
    // ByteBuddy mock maker (whose inject-MockMethodDispatcher.raw IS copied above).
    if (name.startsWith("mockito-extensions/")) {
      String text = new String(data, StandardCharsets.UTF_8);
      if (text.toLowerCase(java.util.Locale.ROOT).contains("dexmaker")
          || text.contains("com.android.dx.mockito.")) {
        return false;
      }
    }
    return true;
  }

  private static List<String> splitSpaces(String value) {
    if (value == null || value.isEmpty()) return List.of();
    return List.of(value.split(" "));
  }

  /** {@code Lcom/example/Foo;} as {@code com/example/Foo}. */
  private static String internalName(String descriptor) {
    return descriptor.startsWith("L") && descriptor.endsWith(";")
        ? descriptor.substring(1, descriptor.length() - 1)
        : descriptor;
  }

  private static byte[] entryBytes(ZipFile jar, String name) throws IOException {
    ZipEntry entry = jar.getEntry(name);
    if (entry == null) return null;
    try (InputStream stream = jar.getInputStream(entry)) {
      return stream.readAllBytes();
    }
  }

  /** Class [index] of the APK, with or without debug info; null if dex2jar cannot read it. */
  private static DexClassNode read(BaseDexFileReader reader, int index, boolean skipDebug) {
    DexFileNode one = new DexFileNode();
    int config = DexFileReader.IGNORE_READ_EXCEPTION | (skipDebug ? DexFileReader.SKIP_DEBUG : 0);
    try {
      reader.accept(one, index, config);
    } catch (Exception e) {
      new BaseDexExceptionHandler().handleFileException(e);
      return null;
    }
    return one.clzs.isEmpty() ? null : one.clzs.get(0);
  }

  /** One class through dex2jar: the class file before the ASM pass, and what failed. */
  private static final class Translation {
    String entryName;
    byte[] bytes;
    boolean withoutDebugInfo;
    public final List<String> failures = new ArrayList<>();
    /** {@code fromDesc|targetDesc|initDesc} triples rewritten for R8 constructor inlining. */
    final Set<String> inlinedInits = new LinkedHashSet<>();
  }

  /**
   * dex2jar on one class. Line numbers and local names first, so an app crash names its source
   * line. dex2jar's type inference sometimes trips on a method's debug local types; such a class is
   * translated again without debug info, which is kept when that translates every method. A method
   * neither translation manages becomes a stub that throws, and is reported.
   *
   * <p>This is {@code Dex2jar.to}, driven from dex2jar's public pieces so {@link
   * #normalizeZeroLeftJumps} runs between its IR passes and code generation, and one class at a
   * time: dex2jar reads the whole APK into one {@code DexFileNode} tree first, every method's
   * instructions included, which for a 70,000-class app is gigabytes of heap. Here only the class
   * being translated is in memory, beside the class-level facts ({@link Translator#classInfo})
   * that inner-class attributes need from the others.
   */
  private static Translation translate(
      BaseDexFileReader reader, int index, DexClassNode node, Translator.ClassInfo classInfo) {
    Translation withDebug = translateOnce(reader.getDexVersion(), node, classInfo);
    if (withDebug.failures.isEmpty()) return withDebug;
    DexClassNode bare = read(reader, index, true);
    if (bare != null) {
      Translation withoutDebug = translateOnce(reader.getDexVersion(), bare, classInfo);
      if (withoutDebug.failures.isEmpty() && withoutDebug.entryName != null) {
        withoutDebug.withoutDebugInfo = true;
        return withoutDebug;
      }
    }
    return withDebug;
  }

  private static Translation translateOnce(
      int dexVersion, DexClassNode node, Translator.ClassInfo classInfo) {
    try {
      return translateOnce(dexVersion, node, classInfo, false);
    } catch (RuntimeException e) {
      // ASM's ClassWriter.toByteArray() can fail in visitEnd() after dex2jar finishes all methods
      // (for example, a >64 KB Dex method causing MethodTooLargeException or IllegalArgumentException
      // inside ClassWriter.replaceAsmInstructions). Retry this class validating each method's ASM
      // serialization in isolation so only the un-emittable method(s) become stubs.
      return translateOnce(dexVersion, node, classInfo, true);
    }
  }

  private static Translation translateOnce(
      int dexVersion, DexClassNode node, Translator.ClassInfo classInfo, boolean safePerMethod) {
    Translation translation = new Translation();
    BaseDexExceptionHandler handler =
        new BaseDexExceptionHandler() {
          @Override
          public void handleMethodTranslateException(
              Method method, DexMethodNode methodNode, MethodVisitor mv, Exception e) {
            translation.failures.add(
                method.getOwner() + "." + method.getName() + method.getDesc() + ": " + e);
            super.handleMethodTranslateException(method, methodNode, mv, e);
          }
        };
    ClassVisitorFactory classes =
        name -> {
          // Maxima only: the ASM pass computes frames, with the whole hierarchy to hand.
          ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
          LambadaNameSafeClassAdapter names = new LambadaNameSafeClassAdapter(writer, false);
          return new ClassVisitor(Opcodes.ASM9, names) {
            @Override
            public void visitEnd() {
              super.visitEnd();
              translation.entryName = names.getClassName() + ".class";
              byte[] bytes = writer.toByteArray();
              if (bytes.length > 65535) {
                validateBytecodeReadable(bytes);
              }
              translation.bytes = bytes;
            }
          };
        };
    new Translator(handler, classInfo, translation.inlinedInits, safePerMethod)
        .convertClass(dexVersion, node, classes, classInfo.classes);
    return translation;
  }

  /**
   * dex2jar's translator, with {@code Dex2jar}'s pass order, R8 constructor-inlining normalization
   * before {@code T_NEW}, and the zero-left jump fix.
   */
  private static final class Translator extends ExDex2Asm {

    private static final String BRIDGE_MARKER = "Ljava/lang/Void;";

    private final ClassInfo classInfo;
    private final Set<String> inlinedInits;
    private final boolean safePerMethod;

    Translator(
        BaseDexExceptionHandler handler,
        ClassInfo classInfo,
        Set<String> inlinedInits,
        boolean safePerMethod) {
      super(handler);
      this.classInfo = classInfo;
      this.inlinedInits = inlinedInits;
      this.safePerMethod = safePerMethod;
    }

    /**
     * What {@code Dex2Asm.convertDex} collects from every class before translating any: access
     * flags and the enclosing / inner / member class annotations, plus each kept APK class's
     * superclass and {@code <init>} descriptors so R8 constructor-inlined {@code invoke-direct}
     * calls can be bridged across intermediate subclasses. Read without code or field constants, so
     * it is small.
     */
    static ClassInfo classInfo(BaseDexFileReader reader) {
      Map<String, String> superOf = new HashMap<>();
      Map<String, Set<String>> existingInits = new HashMap<>();
      ClassLoader host = ApkClassConverter.class.getClassLoader();
      DexFileNode headers =
          new DexFileNode() {
            @Override
            public DexClassVisitor visit(
                int access, String className, String superClass, String[] interfaceNames) {
              boolean kept = !providedBySimulator(internalName(className) + ".class", host);
              Set<String> inits = kept ? new HashSet<>() : null;
              if (kept) {
                superOf.put(className, superClass);
                existingInits.put(className, inits);
              }
              DexClassNode node =
                  new DexClassNode(access, className, superClass, interfaceNames) {
                    @Override
                    public DexFieldVisitor visitField(int access, Field field, Object value) {
                      return null;
                    }

                    @Override
                    public DexMethodVisitor visitMethod(int access, Method method) {
                      if (inits != null && "<init>".equals(method.getName())) {
                        inits.add(method.getDesc());
                      }
                      return null;
                    }
                  };
              clzs.add(node);
              return node;
            }
          };
      reader.accept(
          headers,
          DexFileReader.IGNORE_READ_EXCEPTION
              | DexFileReader.SKIP_CODE
              | DexFileReader.SKIP_DEBUG
              | DexFileReader.SKIP_FIELD_CONSTANT);
      return new ClassInfo(collectClzInfo(headers), superOf, existingInits);
    }

    /**
     * What a class's translation depends on, as a digest: its smali (every instruction, constant,
     * annotation and debug entry, with names resolved, so it does not shift when another class adds
     * a string to the dex) and the inner-class facts dex2jar takes from the rest of the APK — its
     * own, its nested classes' and its enclosing classes'.
     */
    static String key(DexClassNode node, ClassInfo classInfo) {
      MessageDigest digest = sha256();
      Out out =
          new Out() {
            @Override
            public void push() {}

            @Override
            public void pop() {}

            @Override
            public void s(String line) {
              digest.update(line.getBytes(StandardCharsets.UTF_8));
              digest.update((byte) '\n');
            }

            @Override
            public void s(String format, Object... args) {
              // The format and its arguments determine the line; formatting it is the slow part.
              s(format);
              for (Object arg : args) s(String.valueOf(arg));
            }
          };
      new BaksmaliDumper(true, true).baksmaliClass(node, out);
      Clz clz = classInfo.classes.get(node.className);
      StringBuilder facts = new StringBuilder("\u0000");
      // searchInnerClass: every class nested in this one, at any depth.
      Set<Clz> seen = new HashSet<>();
      List<String> nested = new ArrayList<>();
      ArrayDeque<Clz> pending = new ArrayDeque<>();
      if (clz != null) pending.add(clz);
      while (!pending.isEmpty()) {
        Clz next = pending.poll();
        if (!seen.add(next) || next.inners == null) continue;
        for (Clz inner : next.inners) {
          nested.add(next.name + ">" + inner.name + "/" + inner.innerName + "/" + inner.access);
          pending.add(inner);
        }
      }
      Collections.sort(nested);
      facts.append(nested);
      // searchEnclosing and the outer class: this one and each enclosing class up the chain.
      Set<Clz> chain = new HashSet<>();
      for (Clz p = clz; p != null && chain.add(p); p = p.enclosingClass) {
        facts.append('|').append(p.name).append('/').append(p.innerName).append('/');
        facts.append(p.access).append('/');
        if (p.enclosingMethod != null) {
          Method m = p.enclosingMethod;
          facts.append(m.getOwner()).append('.').append(m.getName()).append(m.getDesc());
        }
        facts.append('/').append(p.enclosingClass == null ? null : p.enclosingClass.name);
      }
      Set<String> supers = new HashSet<>();
      facts.append("|pad=").append(classInfo.baseVoidPad);
      for (String s = node.className; s != null && supers.add(s); s = classInfo.superOf.get(s)) {
        facts.append('>').append(s);
      }
      digest.update(facts.toString().getBytes(StandardCharsets.UTF_8));
      return hex(digest.digest());
    }

    /** {@link #classInfo}'s result; dex2jar's {@code Clz} is not visible outside a subclass. */
    static final class ClassInfo {
      final Map<String, Clz> classes;
      final Map<String, String> superOf;
      final Map<String, Set<String>> existingInits;
      final int baseVoidPad;

      ClassInfo(
          Map<String, Clz> classes,
          Map<String, String> superOf,
          Map<String, Set<String>> existingInits) {
        this.classes = classes;
        this.superOf = superOf;
        this.existingInits = existingInits;
        int maxTrailing = 0;
        for (Set<String> inits : existingInits.values()) {
          for (String desc : inits) maxTrailing = Math.max(maxTrailing, trailingMarkers(desc));
        }
        this.baseVoidPad = 1 + maxTrailing;
      }

      private static int trailingMarkers(String initDesc) {
        int end = initDesc.indexOf(')');
        if (end < 0) return 0;
        String params = initDesc.substring(0, end);
        int count = 0;
        while (params.endsWith(BRIDGE_MARKER)) {
          count++;
          params = params.substring(0, params.length() - BRIDGE_MARKER.length());
        }
        return count;
      }

      int depth(String desc) {
        int d = 0;
        String cur = desc;
        Set<String> visited = new HashSet<>();
        while (cur != null && superOf.containsKey(cur) && visited.add(cur)) {
          d++;
          cur = superOf.get(cur);
        }
        return d;
      }

      int bridgePad(String targetAncestorDesc) {
        return baseVoidPad + depth(targetAncestorDesc);
      }

      static String appendMarkers(String initDesc, int pad) {
        int end = initDesc.indexOf(')');
        StringBuilder sb = new StringBuilder(initDesc.length() + pad * BRIDGE_MARKER.length());
        sb.append(initDesc, 0, end);
        for (int i = 0; i < pad; i++) sb.append(BRIDGE_MARKER);
        sb.append(initDesc, end, initDesc.length());
        return sb.toString();
      }

      /**
       * Whether [targetAncestorDesc] is an ancestor of [fromDesc] reachable entirely through APK
       * classes we emit (so forwarding bridge {@code <init>}s along the chain can be synthesized).
       */
      boolean canBridge(String fromDesc, String targetAncestorDesc) {
        String cur = fromDesc;
        Set<String> visited = new HashSet<>();
        while (cur != null && !cur.equals(targetAncestorDesc) && visited.add(cur)) {
          if (!superOf.containsKey(cur)) return false;
          cur = superOf.get(cur);
        }
        return targetAncestorDesc.equals(cur);
      }

      void recordNeededInits(
          String fromDesc,
          String targetAncestorDesc,
          String origInitDesc,
          Map<String, Set<String>> neededInits) {
        String bridgeDesc = appendMarkers(origInitDesc, bridgePad(targetAncestorDesc));
        String cur = fromDesc;
        Set<String> visited = new HashSet<>();
        while (cur != null && !cur.equals(targetAncestorDesc) && visited.add(cur)) {
          String sup = superOf.get(cur);
          if (sup == null) break;
          String superCallDesc = sup.equals(targetAncestorDesc) ? origInitDesc : bridgeDesc;
          neededInits
              .computeIfAbsent(cur, k -> new LinkedHashSet<>())
              .add(bridgeDesc + ">" + superCallDesc);
          cur = sup;
        }
      }
    }

    /**
     * Normalizes R8 constructor-inlined {@code invoke-direct} instructions before {@code T_NEW}.
     *
     * <p>ART permits both {@code new-instance Sub; invoke-direct Ancestor.<init>} (skipping {@code
     * Sub.<init>}) and {@code Sub.<init>} calling {@code Grandparent.<init>} on {@code this}
     * (skipping {@code Parent.<init>}). On a JVM both are {@code VerifyError}s ("Call to wrong
     * {@code <init>} method"), and the first also leaves {@code T_NEW} unable to fuse {@code NEW} +
     * {@code INVOKESPECIAL}, emitting a bare {@code NEW} with no {@code <init>} call at all.
     * Rewriting the owner to {@code Sub} (or {@code Parent}) and synthesizing forwarding {@code
     * <init>} methods along the super chain satisfies both {@code T_NEW} and the JVM verifier.
     */
    private void normalizeInlinedConstructors(IrMethod irMethod) {
      Map<Local, String> newTypes = new HashMap<>();
      Set<Local> thisLocals = new HashSet<>();
      boolean changed = true;
      while (changed) {
        changed = false;
        for (Stmt s : irMethod.stmts) {
          if ((s.st == Stmt.ST.ASSIGN || s.st == Stmt.ST.IDENTITY)
              && s.getOp1().vt == Value.VT.LOCAL) {
            Local lhs = (Local) s.getOp1();
            Value rhs = s.getOp2();
            if (rhs.vt == Value.VT.NEW) {
              String type = ((NewExpr) rhs).type;
              changed |= !type.equals(newTypes.put(lhs, type));
            } else if (rhs.vt == Value.VT.THIS_REF) {
              changed |= thisLocals.add(lhs);
            } else if (rhs instanceof Local src) {
              String type = newTypes.get(src);
              if (type != null) changed |= !type.equals(newTypes.put(lhs, type));
              if (thisLocals.contains(src)) changed |= thisLocals.add(lhs);
            }
          }
        }
      }
      if (newTypes.isEmpty() && thisLocals.isEmpty()) return;
      String selfDesc =
          irMethod.owner == null
              ? null
              : (irMethod.owner.startsWith("L") ? irMethod.owner : "L" + irMethod.owner + ";");
      List<Stmt> stmts = new ArrayList<>();
      for (Stmt s : irMethod.stmts) stmts.add(s);
      int bridgeCounter = 0;
      for (Stmt s : stmts) {
        Value op = s.st == Stmt.ST.VOID_INVOKE ? s.getOp() : null;
        if (!(op instanceof InvokeExpr inv)
            || inv.vt != Value.VT.INVOKE_SPECIAL
            || !"<init>".equals(inv.getName())) {
          continue;
        }
        Value[] ops = inv.getOps();
        if (ops == null || ops.length == 0 || !(ops[0] instanceof Local loc)) continue;
        String currentOwner = inv.getOwner();
        String allocType = newTypes.get(loc);
        if (allocType != null
            && !allocType.equals(currentOwner)
            && classInfo.canBridge(allocType, currentOwner)) {
          String initDesc = inv.getProto().getDesc();
          inlinedInits.add(allocType + "|" + currentOwner + "|" + initDesc);
          bridgeCounter =
              rewriteToBridgeInit(
                  irMethod,
                  s,
                  inv,
                  allocType,
                  classInfo.bridgePad(currentOwner),
                  bridgeCounter);
        } else if (selfDesc != null
            && thisLocals.contains(loc)
            && "<init>".equals(irMethod.name)) {
          String directSuper = classInfo.superOf.get(selfDesc);
          if (directSuper != null
              && !currentOwner.equals(selfDesc)
              && !currentOwner.equals(directSuper)
              && classInfo.canBridge(directSuper, currentOwner)) {
            String initDesc = inv.getProto().getDesc();
            inlinedInits.add(directSuper + "|" + currentOwner + "|" + initDesc);
            bridgeCounter =
                rewriteToBridgeInit(
                    irMethod,
                    s,
                    inv,
                    directSuper,
                    classInfo.bridgePad(currentOwner),
                    bridgeCounter);
          }
        }
      }
    }

    private static int rewriteToBridgeInit(
        IrMethod irMethod,
        Stmt invokeStmt,
        InvokeExpr inv,
        String newOwner,
        int pad,
        int bridgeCounter) {
      Value[] oldOps = inv.getOps();
      String[] oldArgs = inv.getArgs();
      Value[] newOps = Arrays.copyOf(oldOps, oldOps.length + pad);
      String[] newArgs = Arrays.copyOf(oldArgs, oldArgs.length + pad);
      for (int i = 0; i < pad; i++) {
        Local marker = Exprs.nLocal("bridge" + (bridgeCounter++));
        irMethod.locals.add(marker);
        irMethod.stmts.insertBefore(
            invokeStmt, Stmts.nAssign(marker, Exprs.nConstant(Constant.NULL)));
        newOps[oldOps.length + i] = marker;
        newArgs[oldArgs.length + i] = BRIDGE_MARKER;
      }
      invokeStmt.setOp(Exprs.nInvokeSpecial(newOps, newOwner, "<init>", newArgs, "V"));
      return bridgeCounter;
    }

    /**
     * Above this {@code locals * stmts} product (~200 MB of dense live-analysis arrays), dex2jar's
     * {@code UnSSATransformer.LiveA} and {@code Ir2JRegAssignTransformer}'s {@code
     * SimpleLiveAnalyze} OOM on giant generated methods (e.g. 60k-instruction Kotlin list
     * builders, where {@code locals * stmts} exceeds 1 billion). Switch to the linear-memory
     * sparse passes below above this threshold, while keeping normal large suspend functions on
     * dex2jar's graph-coloring allocator so {@code max_locals} stays compact for ASM frames.
     */
    private static final long SPARSE_UNSSA_THRESHOLD = 50_000_000L;

    private static final String ADVICE_KEEP_OWNER = "L__composeai_advice_keep__;";

    private record WritableAdviceParam(int dexReg, int jvmSlot, String type) {}

    private boolean preserveParamSlots;
    private List<WritableAdviceParam> writableAdviceParams = List.of();

    @Override
    public void convertCode(DexMethodNode methodNode, MethodVisitor mv, ClzCtx clzCtx) {
      this.preserveParamSlots = hasByteBuddyAdviceAnnotation(methodNode);
      this.writableAdviceParams =
          this.preserveParamSlots ? findWritableAdviceParams(methodNode) : List.of();
      List<com.googlecode.d2j.node.insn.DexStmtNode> originalStmts = null;
      if (!writableAdviceParams.isEmpty()
          && methodNode.codeNode != null
          && methodNode.codeNode.stmts != null) {
        originalStmts = methodNode.codeNode.stmts;
        List<com.googlecode.d2j.node.insn.DexStmtNode> augmented =
            new ArrayList<>(originalStmts.size() + writableAdviceParams.size() * 2);
        for (com.googlecode.d2j.node.insn.DexStmtNode stmt : originalStmts) {
          if (isReturnOp(stmt.op)) {
            for (WritableAdviceParam wp : writableAdviceParams) {
              int[] regs =
                  isWideType(wp.type)
                      ? new int[] {wp.dexReg, wp.dexReg + 1}
                      : new int[] {wp.dexReg};
              augmented.add(
                  new com.googlecode.d2j.node.insn.MethodStmtNode(
                      com.googlecode.d2j.reader.Op.INVOKE_STATIC,
                      regs,
                      new Method(
                          ADVICE_KEEP_OWNER,
                          "keep" + wp.jvmSlot,
                          new String[] {wp.type},
                          "V")));
            }
          }
          augmented.add(stmt);
        }
        methodNode.codeNode.stmts = augmented;
      }
      try {
        super.convertCode(methodNode, mv, clzCtx);
      } finally {
        if (originalStmts != null) {
          methodNode.codeNode.stmts = originalStmts;
        }
        this.preserveParamSlots = false;
        this.writableAdviceParams = List.of();
      }
    }

    private static boolean isReturnOp(com.googlecode.d2j.reader.Op op) {
      return op == com.googlecode.d2j.reader.Op.RETURN_VOID
          || op == com.googlecode.d2j.reader.Op.RETURN
          || op == com.googlecode.d2j.reader.Op.RETURN_WIDE
          || op == com.googlecode.d2j.reader.Op.RETURN_OBJECT;
    }

    private static List<WritableAdviceParam> findWritableAdviceParams(DexMethodNode methodNode) {
      if (methodNode.parameterAnns == null || methodNode.codeNode == null) return List.of();
      String[] paramTypes = methodNode.method.getParameterTypes();
      if (paramTypes == null || paramTypes.length == 0) return List.of();
      boolean isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;
      int inWords = isStatic ? 0 : 1;
      for (String t : paramTypes) inWords += isWideType(t) ? 2 : 1;
      int dexReg = methodNode.codeNode.totalRegister - inWords + (isStatic ? 0 : 1);
      int jvmSlot = isStatic ? 0 : 1;
      List<WritableAdviceParam> out = new ArrayList<>();
      for (int i = 0; i < paramTypes.length; i++) {
        String type = paramTypes[i];
        int width = isWideType(type) ? 2 : 1;
        if (i < methodNode.parameterAnns.length
            && hasWritableAdviceAnnotation(methodNode.parameterAnns[i])) {
          out.add(new WritableAdviceParam(dexReg, jvmSlot, type));
        }
        dexReg += width;
        jvmSlot += width;
      }
      return out;
    }

    private static boolean hasWritableAdviceAnnotation(
        List<com.googlecode.d2j.node.DexAnnotationNode> anns) {
      if (anns == null) return false;
      for (com.googlecode.d2j.node.DexAnnotationNode ann : anns) {
        if (ann == null
            || ann.type == null
            || !ann.type.startsWith("Lnet/bytebuddy/asm/Advice$")
            || ann.items == null) {
          continue;
        }
        for (com.googlecode.d2j.node.DexAnnotationNode.Item item : ann.items) {
          if ("readOnly".equals(item.name) && Boolean.FALSE.equals(item.value)) {
            return true;
          }
        }
      }
      return false;
    }

    private static void rewriteWritableAdviceParamKeeps(
        IrMethod irMethod, List<WritableAdviceParam> params) {
      if (params.isEmpty()) return;
      Map<String, WritableAdviceParam> byName = new HashMap<>();
      int nextSlot = 0;
      for (WritableAdviceParam wp : params) {
        byName.put("keep" + wp.jvmSlot, wp);
        nextSlot = Math.max(nextSlot, wp.jvmSlot + (isWideType(wp.type) ? 2 : 1));
      }
      for (Local l : irMethod.locals) {
        if (l.lsIndex >= 0) {
          nextSlot = Math.max(nextSlot, l.lsIndex + (isWideType(l.valueType) ? 2 : 1));
        }
      }
      List<Stmt> stmts = new ArrayList<>();
      for (Stmt s : irMethod.stmts) stmts.add(s);
      int seq = 0;
      for (int i = 0; i < stmts.size(); i++) {
        WritableAdviceParam first = matchAdviceKeep(stmts.get(i), byName);
        if (first == null) continue;
        int j = i + 1;
        while (j < stmts.size() && matchAdviceKeep(stmts.get(j), byName) != null) {
          j++;
        }
        // Collect all writes in this contiguous pre-return keep run.
        List<WritableAdviceParam> writeParams = new ArrayList<>();
        List<Value> writeSources = new ArrayList<>();
        Set<Integer> writtenSlots = new HashSet<>();
        for (int k = i; k < j; k++) {
          Stmt keepStmt = stmts.get(k);
          WritableAdviceParam wp = matchAdviceKeep(keepStmt, byName);
          InvokeExpr inv = (InvokeExpr) keepStmt.getOp();
          Value[] ops = inv.getOps();
          if (ops != null && ops.length == 1 && ops[0] != null) {
            Value val = ops[0];
            if (!(val instanceof Local l && l.lsIndex == wp.jvmSlot)) {
              writeParams.add(wp);
              writeSources.add(val);
              writtenSlots.add(wp.jvmSlot);
              if (isWideType(wp.type)) writtenSlots.add(wp.jvmSlot + 1);
            }
          }
        }
        Stmt anchor = stmts.get(i);
        if (!writtenSlots.isEmpty()) {
          // If the following RETURN reads a slot we are about to overwrite (e.g. advice saves the
          // original parameter, mutates it, and returns the original), copy the return value into
          // a fresh temporary slot first.
          Stmt after = j < stmts.size() ? stmts.get(j) : null;
          if (after != null
              && after.st == Stmt.ST.RETURN
              && after.getOp() != null
              && valueUsesAnySlot(after.getOp(), writtenSlots)) {
            String retType =
                after.getOp().valueType != null ? after.getOp().valueType : irMethod.ret;
            Local retTmp = Exprs.nLocal("advice_ret_" + (seq++));
            retTmp.lsIndex = nextSlot;
            retTmp.valueType = retType;
            nextSlot += isWideType(retType) ? 2 : 1;
            irMethod.locals.add(retTmp);
            irMethod.stmts.insertBefore(anchor, Stmts.nAssign(retTmp, after.getOp()));
            after.setOp(retTmp);
          }
          // When multiple writable parameters are updated (e.g. swapped), materialize any source
          // that reads a slot overwritten by the same run before emitting the stores.
          if (writeParams.size() > 1) {
            for (int w = 0; w < writeParams.size(); w++) {
              Value src = writeSources.get(w);
              if (valueUsesAnySlot(src, writtenSlots)) {
                WritableAdviceParam wp = writeParams.get(w);
                Local tmp = Exprs.nLocal("advice_tmp_" + (seq++));
                tmp.lsIndex = nextSlot;
                tmp.valueType = wp.type;
                nextSlot += isWideType(wp.type) ? 2 : 1;
                irMethod.locals.add(tmp);
                irMethod.stmts.insertBefore(anchor, Stmts.nAssign(tmp, src));
                writeSources.set(w, tmp);
              }
            }
          }
          for (int w = 0; w < writeParams.size(); w++) {
            WritableAdviceParam wp = writeParams.get(w);
            Local dst = Exprs.nLocal("advice_out_" + (seq++));
            dst.lsIndex = wp.jvmSlot;
            dst.valueType = wp.type;
            irMethod.locals.add(dst);
            irMethod.stmts.insertBefore(anchor, Stmts.nAssign(dst, writeSources.get(w)));
          }
        }
        for (int k = i; k < j; k++) {
          irMethod.stmts.remove(stmts.get(k));
        }
        i = j - 1;
      }
    }

    private static WritableAdviceParam matchAdviceKeep(
        Stmt s, Map<String, WritableAdviceParam> byName) {
      if (s.st != Stmt.ST.VOID_INVOKE) return null;
      if (!(s.getOp() instanceof InvokeExpr inv)) return null;
      if (!ADVICE_KEEP_OWNER.equals(inv.getOwner())) return null;
      return byName.get(inv.getName());
    }

    private static boolean valueUsesAnySlot(Value v, Set<Integer> slots) {
      if (v == null) return false;
      if (v instanceof Local l) {
        if (l.lsIndex < 0) return false;
        if (slots.contains(l.lsIndex)) return true;
        return isWideType(l.valueType) && slots.contains(l.lsIndex + 1);
      }
      return switch (v.et) {
        case E0 -> false;
        case E1 -> valueUsesAnySlot(v.getOp(), slots);
        case E2 -> valueUsesAnySlot(v.getOp1(), slots) || valueUsesAnySlot(v.getOp2(), slots);
        case En -> {
          Value[] ops = v.getOps();
          if (ops != null) {
            for (Value op : ops) {
              if (valueUsesAnySlot(op, slots)) yield true;
            }
          }
          yield false;
        }
      };
    }

    private static boolean hasByteBuddyAdviceAnnotation(DexMethodNode methodNode) {
      if (methodNode.anns != null) {
        for (com.googlecode.d2j.node.DexAnnotationNode ann : methodNode.anns) {
          if (ann != null && ann.type != null && ann.type.startsWith("Lnet/bytebuddy/asm/Advice$")) {
            return true;
          }
        }
      }
      if (methodNode.parameterAnns != null) {
        for (List<com.googlecode.d2j.node.DexAnnotationNode> list : methodNode.parameterAnns) {
          if (list == null) continue;
          for (com.googlecode.d2j.node.DexAnnotationNode ann : list) {
            if (ann != null
                && ann.type != null
                && ann.type.startsWith("Lnet/bytebuddy/asm/Advice$")) {
              return true;
            }
          }
        }
      }
      return false;
    }

    // Dex2jar's own pass order, which differs from Dex2Asm's default in its last two steps.
    @Override
    public void optimize(IrMethod irMethod) {
      T_CLEAN_LABEL.transform(irMethod);
      T_DEAD_CODE.transform(irMethod);
      T_REMOVE_LOCAL.transform(irMethod);
      T_REMOVE_CONST.transform(irMethod);
      T_ZERO.transform(irMethod);
      if (T_NPE.transformReportChanged(irMethod)) {
        T_DEAD_CODE.transform(irMethod);
        T_REMOVE_LOCAL.transform(irMethod);
        T_REMOVE_CONST.transform(irMethod);
      }
      normalizeInlinedConstructors(irMethod);
      T_NEW.transform(irMethod);
      T_FILL_ARRAY.transform(irMethod);
      T_AGG.transform(irMethod);
      T_MULTI_ARRAY.transform(irMethod);
      T_VOID_INVOKE.transform(irMethod);
      T_DEAD_CODE.transform(irMethod);
      T_REMOVE_LOCAL.transform(irMethod);
      T_REMOVE_CONST.transform(irMethod);
      T_TYPE.transform(irMethod);
      // ByteBuddy @Advice methods forbid writing to read-only @Advice parameter slots (which
      // dex2jar's Ir2JRegAssignTransformer reuses once a parameter dies). Our sparse register
      // allocator keeps 0..paramSlots-1 untouched, fixing Mockito's MockMethodAdvice and MockK.
      if (preserveParamSlots
          || (long) irMethod.locals.size() * irMethod.stmts.getSize() > SPARSE_UNSSA_THRESHOLD) {
        deconstructPhisSparse(irMethod);
        assignRegistersSparse(irMethod);
        rewriteWritableAdviceParamKeeps(irMethod, writableAdviceParams);
        irMethod.vars.clear();
      } else {
        T_UNSSA.transform(irMethod);
        T_IR_2_J_REG_ASSIGN.transform(irMethod);
      }
      T_TRIM_EX.transform(irMethod);
    }

    @Override
    public void ir2j(IrMethod irMethod, MethodVisitor mv, ClzCtx clzCtx) {
      normalizeZeroLeftJumps(irMethod);
      if (safePerMethod) {
        ClassWriter probe = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        probe.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "probe/Probe", null, "java/lang/Object", null);
        StringBuilder desc = new StringBuilder("(");
        if (irMethod.args != null) {
          for (String arg : irMethod.args) desc.append(arg);
        }
        desc.append(')').append(irMethod.ret == null ? "V" : irMethod.ret);
        int access = Opcodes.ACC_PUBLIC | (irMethod.isStatic ? Opcodes.ACC_STATIC : 0);
        MethodVisitor pmv = probe.visitMethod(access, "m", desc.toString(), null, null);
        pmv.visitCode();
        new IR2JConverter().clzCtx(clzCtx).ir(irMethod).asm(pmv).convert();
        pmv.visitMaxs(-1, -1);
        pmv.visitEnd();
        probe.visitEnd();
        byte[] probeBytes = probe.toByteArray();
        if (probeBytes.length > 65535) {
          validateBytecodeReadable(probeBytes);
        }
      }
      new IR2JConverter().clzCtx(clzCtx).ir(irMethod).asm(mv).convert();
    }
  }

  /**
   * Verifies that ASM's {@link ClassReader} can parse every method body in [bytes] (catching
   * methods whose bytecode exceeds the JVM's 65,535-byte {@code code_length} limit before Pass 2).
   */
  private static void validateBytecodeReadable(byte[] bytes) {
    MethodVisitor sink = new MethodVisitor(Opcodes.ASM9) {};
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String descriptor, String signature, String[] exceptions) {
                return sink;
              }
            },
            ClassReader.SKIP_FRAMES);
  }

  /**
   * Sparse phi deconstruction for methods too large for dex2jar's dense {@code UnSSATransformer}.
   *
   * <p>For each phi {@code dst = Phi(v1, v2, ...)} at a {@link LabelStmt}, allocates a fresh
   * temporary local {@code tmp}, inserts {@code tmp = src} on every incoming CFG edge (using a
   * bridge block for conditional branches and switches), and inserts {@code dst = tmp} immediately
   * after the label (after any {@code @caughtexception} identity). Because every phi has its own
   * temporary, parallel phi swaps never clobber each other.
   */
  static void deconstructPhisSparse(IrMethod irMethod) {
    List<LabelStmt> phiLabels = new ArrayList<>();
    for (Stmt s : irMethod.stmts) {
      if (s.st == Stmt.ST.LABEL) {
        LabelStmt label = (LabelStmt) s;
        if (label.phis != null && !label.phis.isEmpty()) {
          phiLabels.add(label);
        }
      }
    }
    if (phiLabels.isEmpty()) {
      if (irMethod.phiLabels != null) irMethod.phiLabels.clear();
      return;
    }

    Cfg.createCFG(irMethod);

    // Map each statement to the SSA locals it defines (including phi targets on labels).
    Map<Stmt, List<Local>> defsAt = new IdentityHashMap<>();
    for (Stmt s : irMethod.stmts) {
      if ((s.st == Stmt.ST.ASSIGN || s.st == Stmt.ST.IDENTITY)
          && s.getOp1() instanceof Local loc) {
        defsAt.computeIfAbsent(s, k -> new ArrayList<>(1)).add(loc);
      } else if (s.st == Stmt.ST.LABEL) {
        LabelStmt label = (LabelStmt) s;
        if (label.phis != null) {
          for (AssignStmt phi : label.phis) {
            if (phi.getOp1() instanceof Local loc) {
              defsAt.computeIfAbsent(label, k -> new ArrayList<>()).add(loc);
            }
          }
        }
      }
    }

    int tempSeq = 0;
    for (LabelStmt label : phiLabels) {
      List<AssignStmt> phis = label.phis;
      List<Local> temps = new ArrayList<>(phis.size());
      List<Local> dsts = new ArrayList<>(phis.size());
      List<Set<Local>> opLocals = new ArrayList<>(phis.size());
      List<Value> fallbackConsts = new ArrayList<>(phis.size());
      for (AssignStmt phi : phis) {
        Local dst = (Local) phi.getOp1();
        Local tmp = Exprs.nLocal("phi_tmp_" + (tempSeq++));
        tmp.valueType = dst.valueType;
        irMethod.locals.add(tmp);
        temps.add(tmp);
        dsts.add(dst);
        Set<Local> locals = Collections.newSetFromMap(new IdentityHashMap<>());
        Value fallback = null;
        if (phi.getOp2() instanceof PhiExpr phiExpr && phiExpr.getOps() != null) {
          for (Value op : phiExpr.getOps()) {
            if (op instanceof Local l) {
              locals.add(l);
            } else if (fallback == null && op != null) {
              fallback = op;
            }
          }
        }
        opLocals.add(locals);
        fallbackConsts.add(fallback);
      }

      List<Stmt> preds =
          label.cfgFroms == null ? List.of() : new ArrayList<>(label.cfgFroms);
      for (Stmt pred : preds) {
        boolean isExceptionEdge =
            pred.exceptionHandlers != null && pred.exceptionHandlers.contains(label);
        boolean isGoto = pred.st == Stmt.ST.GOTO && ((GotoStmt) pred).target == label;
        boolean isIfJump = pred.st == Stmt.ST.IF && ((IfStmt) pred).target == label;
        boolean isSwitchJump = pred instanceof BaseSwitchStmt sw && switchTargets(sw, label);
        boolean isFallthrough = pred.getNext() == label && pred.st.canContinue();

        if (isExceptionEdge) {
          List<AssignStmt> copies =
              buildPhiCopies(pred, false, temps, dsts, opLocals, fallbackConsts, defsAt);
          for (AssignStmt copy : copies) {
            irMethod.stmts.insertBefore(pred, copy);
          }
        }
        if (isGoto) {
          List<AssignStmt> copies =
              buildPhiCopies(pred, true, temps, dsts, opLocals, fallbackConsts, defsAt);
          for (AssignStmt copy : copies) {
            irMethod.stmts.insertBefore(pred, copy);
          }
        } else if (isIfJump && isFallthrough) {
          List<AssignStmt> copies =
              buildPhiCopies(pred, true, temps, dsts, opLocals, fallbackConsts, defsAt);
          for (AssignStmt copy : copies) {
            irMethod.stmts.insertBefore(pred, copy);
          }
        } else {
          if (isIfJump) {
            List<AssignStmt> copies =
                buildPhiCopies(pred, true, temps, dsts, opLocals, fallbackConsts, defsAt);
            LabelStmt bridge = appendPhiBridge(irMethod, label, copies);
            ((IfStmt) pred).target = bridge;
          }
          if (isSwitchJump) {
            List<AssignStmt> copies =
                buildPhiCopies(pred, true, temps, dsts, opLocals, fallbackConsts, defsAt);
            LabelStmt bridge = appendPhiBridge(irMethod, label, copies);
            BaseSwitchStmt sw = (BaseSwitchStmt) pred;
            if (sw.defaultTarget == label) sw.defaultTarget = bridge;
            if (sw.targets != null) {
              for (int i = 0; i < sw.targets.length; i++) {
                if (sw.targets[i] == label) sw.targets[i] = bridge;
              }
            }
          }
          if (isFallthrough) {
            List<AssignStmt> copies =
                buildPhiCopies(pred, true, temps, dsts, opLocals, fallbackConsts, defsAt);
            for (AssignStmt copy : copies) {
              irMethod.stmts.insertBefore(label, copy);
            }
          }
        }
      }

      Stmt anchor = label;
      while (anchor.getNext() != null
          && anchor.getNext().st == Stmt.ST.IDENTITY
          && anchor.getNext().getOp2().vt == Value.VT.EXCEPTION_REF) {
        anchor = anchor.getNext();
      }
      for (int i = dsts.size() - 1; i >= 0; i--) {
        irMethod.stmts.insertAfter(anchor, Stmts.nAssign(dsts.get(i), temps.get(i)));
      }
      label.phis = null;
    }
    if (irMethod.phiLabels != null) irMethod.phiLabels.clear();
  }

  private static boolean switchTargets(BaseSwitchStmt sw, LabelStmt label) {
    if (sw.defaultTarget == label) return true;
    if (sw.targets != null) {
      for (LabelStmt t : sw.targets) {
        if (t == label) return true;
      }
    }
    return false;
  }

  private static LabelStmt appendPhiBridge(
      IrMethod irMethod, LabelStmt target, List<AssignStmt> copies) {
    LabelStmt bridge = Stmts.nLabel();
    irMethod.stmts.add(bridge);
    for (AssignStmt copy : copies) {
      irMethod.stmts.add(copy);
    }
    irMethod.stmts.add(Stmts.nGoto(target));
    return bridge;
  }

  private static List<AssignStmt> buildPhiCopies(
      Stmt pred,
      boolean includePredDef,
      List<Local> temps,
      List<Local> dsts,
      List<Set<Local>> opLocals,
      List<Value> fallbackConsts,
      Map<Stmt, List<Local>> defsAt) {
    List<AssignStmt> copies = new ArrayList<>(temps.size());
    for (int i = 0; i < temps.size(); i++) {
      Local tmp = temps.get(i);
      Value src = findReachingPhiOperand(pred, includePredDef, opLocals.get(i), defsAt);
      if (src == null) src = fallbackConsts.get(i);
      if (src == null) src = defaultConstant(dsts.get(i).valueType);
      copies.add(Stmts.nAssign(tmp, src));
    }
    return copies;
  }

  private static Value findReachingPhiOperand(
      Stmt pred, boolean includePredDef, Set<Local> candidates, Map<Stmt, List<Local>> defsAt) {
    if (candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.iterator().next();
    Set<Stmt> visitedAfter = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<Stmt> visitedBefore = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Stmt> stmtQueue = new ArrayDeque<>();
    ArrayDeque<Boolean> includeQueue = new ArrayDeque<>();
    if ((includePredDef ? visitedAfter : visitedBefore).add(pred)) {
      stmtQueue.add(pred);
      includeQueue.add(includePredDef);
    }
    while (!stmtQueue.isEmpty()) {
      Stmt cur = stmtQueue.poll();
      boolean includeDef = includeQueue.poll();
      if (includeDef) {
        List<Local> defs = defsAt.get(cur);
        if (defs != null) {
          for (Local def : defs) {
            if (candidates.contains(def)) return def;
          }
        }
      }
      if (cur.cfgFroms != null) {
        LabelStmt curLabel = cur instanceof LabelStmt l ? l : null;
        for (Stmt from : cur.cfgFroms) {
          boolean exceptionOnly =
              curLabel != null
                  && from.exceptionHandlers != null
                  && from.exceptionHandlers.contains(curLabel)
                  && !isNormalEdge(from, curLabel);
          boolean nextInclude = !exceptionOnly;
          if ((nextInclude ? visitedAfter : visitedBefore).add(from)) {
            stmtQueue.add(from);
            includeQueue.add(nextInclude);
          }
        }
      }
    }
    return null;
  }

  private static boolean isNormalEdge(Stmt from, LabelStmt target) {
    if (from.getNext() == target && from.st.canContinue()) return true;
    if (from.st == Stmt.ST.GOTO && ((GotoStmt) from).target == target) return true;
    if (from.st == Stmt.ST.IF && ((IfStmt) from).target == target) return true;
    return from instanceof BaseSwitchStmt sw && switchTargets(sw, target);
  }

  private static Constant defaultConstant(String valueType) {
    if (valueType == null || valueType.isEmpty()) return Exprs.nConstant(Constant.NULL);
    Constant c =
        switch (valueType.charAt(0)) {
          case 'J' -> Exprs.nLong(0L);
          case 'D' -> Exprs.nDouble(0.0);
          case 'F' -> Exprs.nFloat(0.0f);
          case 'I', 'Z', 'B', 'C', 'S' -> Exprs.nInt(0);
          default -> Exprs.nConstant(Constant.NULL);
        };
    c.valueType = valueType;
    return c;
  }

  /**
   * Sparse JVM local-slot assignment for methods too large for dex2jar's dense {@code
   * Ir2JRegAssignTransformer}.
   *
   * <p>Parameter and {@code this} locals keep their fixed ABI slots ({@code 0..paramSlots-1}).
   * Cross-block locals get unique slots; intra-block temporaries reuse slots per JVM type category
   * via linear scan over statement indices, keeping {@code max_locals} small and memory {@code
   * O(stmts + locals)}.
   */
  static void assignRegistersSparse(IrMethod irMethod) {
    int[] paramSlotOf = new int[irMethod.args == null ? 0 : irMethod.args.length];
    int paramSlots = irMethod.isStatic ? 0 : 1;
    if (irMethod.args != null) {
      for (int i = 0; i < irMethod.args.length; i++) {
        paramSlotOf[i] = paramSlots;
        paramSlots += isWideType(irMethod.args[i]) ? 2 : 1;
      }
    }

    Set<Local> fixed = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<Local, LocalRange> ranges = new IdentityHashMap<>();
    for (Local loc : irMethod.locals) {
      ranges.put(loc, new LocalRange(loc));
    }

    int stmtIdx = 0;
    int block = 0;
    boolean prevEndedBlock = false;
    for (Stmt s : irMethod.stmts) {
      if (s.st == Stmt.ST.LABEL || prevEndedBlock) {
        block++;
      }
      if (s.st == Stmt.ST.IDENTITY && s.getOp1() instanceof Local lhs) {
        Value rhs = s.getOp2();
        if (rhs.vt == Value.VT.THIS_REF) {
          lhs.lsIndex = 0;
          fixed.add(lhs);
        } else if (rhs.vt == Value.VT.PARAMETER_REF) {
          int idx = ((RefExpr) rhs).parameterIndex;
          if (idx >= 0 && idx < paramSlotOf.length) {
            lhs.lsIndex = paramSlotOf[idx];
            fixed.add(lhs);
          }
        }
      }
      recordStmtLocals(s, stmtIdx, block, ranges);
      prevEndedBlock = endsBasicBlock(s.st);
      stmtIdx++;
    }

    int nextSlot = paramSlots;
    Map<Character, List<LocalRange>> intraByCategory = new HashMap<>();
    for (LocalRange range : ranges.values()) {
      if (fixed.contains(range.local)) continue;
      int width = isWideType(range.local.valueType) ? 2 : 1;
      if (range.firstIdx < 0) {
        // Unreferenced local: keep a valid slot without growing max_locals unnecessarily.
        range.local.lsIndex = paramSlots;
      } else if (range.firstBlock != range.lastBlock) {
        range.local.lsIndex = nextSlot;
        nextSlot += width;
      } else {
        char cat = slotCategory(range.local.valueType);
        intraByCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(range);
      }
    }

    for (Map.Entry<Character, List<LocalRange>> entry : intraByCategory.entrySet()) {
      char cat = entry.getKey();
      int width = (cat == 'J' || cat == 'D') ? 2 : 1;
      List<LocalRange> list = entry.getValue();
      list.sort(Comparator.comparingInt(r -> r.firstIdx));
      PriorityQueue<LocalRange> active =
          new PriorityQueue<>(Comparator.comparingInt(r -> r.lastIdx));
      ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
      for (LocalRange cur : list) {
        while (!active.isEmpty() && active.peek().lastIdx < cur.firstIdx) {
          freeSlots.push(active.poll().local.lsIndex);
        }
        int slot;
        if (!freeSlots.isEmpty()) {
          slot = freeSlots.pop();
        } else {
          slot = nextSlot;
          nextSlot += width;
        }
        cur.local.lsIndex = slot;
        active.add(cur);
      }
    }
  }

  private static final class LocalRange {
    final Local local;
    int firstIdx = -1;
    int lastIdx = -1;
    int firstBlock = -1;
    int lastBlock = -1;

    LocalRange(Local local) {
      this.local = local;
    }

    void record(int idx, int block) {
      if (firstIdx < 0) {
        firstIdx = idx;
        firstBlock = block;
      }
      lastIdx = idx;
      lastBlock = block;
    }
  }

  private static void recordStmtLocals(
      Stmt s, int stmtIdx, int block, Map<Local, LocalRange> ranges) {
    switch (s.et) {
      case E0 -> {}
      case E1 -> recordValueLocals(s.getOp(), stmtIdx, block, ranges);
      case E2 -> {
        recordValueLocals(s.getOp1(), stmtIdx, block, ranges);
        recordValueLocals(s.getOp2(), stmtIdx, block, ranges);
      }
      case En -> {
        Value[] ops = s.getOps();
        if (ops != null) {
          for (Value v : ops) recordValueLocals(v, stmtIdx, block, ranges);
        }
      }
    }
  }

  private static void recordValueLocals(
      Value v, int stmtIdx, int block, Map<Local, LocalRange> ranges) {
    if (v == null) return;
    if (v instanceof Local loc) {
      ranges.computeIfAbsent(loc, LocalRange::new).record(stmtIdx, block);
      return;
    }
    switch (v.et) {
      case E0 -> {}
      case E1 -> recordValueLocals(v.getOp(), stmtIdx, block, ranges);
      case E2 -> {
        recordValueLocals(v.getOp1(), stmtIdx, block, ranges);
        recordValueLocals(v.getOp2(), stmtIdx, block, ranges);
      }
      case En -> {
        Value[] ops = v.getOps();
        if (ops != null) {
          for (Value op : ops) recordValueLocals(op, stmtIdx, block, ranges);
        }
      }
    }
  }

  private static boolean endsBasicBlock(Stmt.ST st) {
    return st == Stmt.ST.GOTO
        || st == Stmt.ST.IF
        || st == Stmt.ST.TABLE_SWITCH
        || st == Stmt.ST.LOOKUP_SWITCH
        || st == Stmt.ST.RETURN
        || st == Stmt.ST.RETURN_VOID
        || st == Stmt.ST.THROW;
  }

  private static boolean isWideType(String type) {
    return "J".equals(type) || "D".equals(type);
  }

  private static char slotCategory(String type) {
    if (type == null || type.isEmpty()) return 'L';
    return switch (type.charAt(0)) {
      case 'J' -> 'J';
      case 'D' -> 'D';
      case 'F' -> 'F';
      case 'I', 'Z', 'B', 'C', 'S' -> 'I';
      default -> 'L';
    };
  }

  /**
   * Rewrites each integer {@code if (0 OP x)} as {@code if (x OP' 0)}, mirroring the comparison.
   *
   * <p>dex2jar's code generator turns a comparison against zero into a single-operand jump by
   * loading the other operand, but keeps the operator when the zero is on the left, so {@code
   * if-le 0, x} (from {@code if (0 <= x)}) is emitted as {@code ifle x}: true only for {@code x <=
   * 0}. Compose's {@code MutableVector.contains} has exactly that shape, and failed every lookup.
   * With the zero on the right the generator's mapping is correct.
   */
  static int normalizeZeroLeftJumps(IrMethod irMethod) {
    int rewritten = 0;
    for (Stmt stmt : irMethod.stmts) {
      if (stmt.st != Stmt.ST.IF) continue;
      Value condition = stmt.getOp();
      Value left = condition.getOp1();
      Value right = condition.getOp2();
      if (!isIntZero(left) || isIntZero(right)) continue;
      String type = ((BinopExpr) condition).type;
      Value mirrored;
      switch (condition.vt) {
        case GE:
          mirrored = Exprs.nLe(right, left, type);
          break;
        case GT:
          mirrored = Exprs.nLt(right, left, type);
          break;
        case LE:
          mirrored = Exprs.nGe(right, left, type);
          break;
        case LT:
          mirrored = Exprs.nGt(right, left, type);
          break;
        default:
          continue; // EQ and NE are symmetric.
      }
      mirrored.valueType = condition.valueType;
      stmt.setOp(mirrored);
      rewritten++;
    }
    return rewritten;
  }

  /**
   * [access] as the JVM accepts a class's: never both {@code final} and {@code abstract}. R8 can
   * leave an enum whose constants have bodies (an abstract class, its constants subclasses) with
   * its {@code InnerClass} annotation saying {@code final}, which dex2jar combines with the class's
   * own {@code abstract} into 0x4419, and the JVM refuses to load the enclosing class ({@code
   * ClassFormatError: Illegal class modifiers}). ART never checks; the class is abstract, so
   * {@code final} goes.
   */
  static int legalClassAccess(int access) {
    if ((access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
      access &= ~Opcodes.ACC_FINAL;
    }
    // Every class public, as ART treats them for reflection: it checks a member's own access
    // only, where HotSpot also refuses a public member of a class the caller cannot see. A
    // Kotlin `private data object`'s INSTANCE, read by kotlinx.serialization (Navigation 3's
    // saved state), was refused. Members keep their access, so nothing else can reach them.
    return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
  }

  private static boolean isIntZero(Value value) {
    return value.vt == Value.VT.CONSTANT && Integer.valueOf(0).equals(((Constant) value).value);
  }

  /**
   * The packages of the Android shared libraries ({@code <uses-library>}) the simulator provides:
   * the Wear OS library ({@code com.google.android.wearable}) and the Wear SDK ({@code wear-sdk}).
   */
  static final String[] SHARED_LIBRARY_PACKAGES = {
    "com/google/android/wearable/", "com/google/wear/"
  };

  /**
   * {@value #PROVIDED_PROPERTY}: the jars of the androidx.test classes the simulator runs tests
   * with. Only an APK's copies of those are dropped; the androidx.test classes a test APK brings
   * that the simulator lacks (uiautomator, espresso-contrib, test services) stay its own. Unset,
   * every androidx.test class is taken as the simulator's.
   */
  static final String PROVIDED_PROPERTY = "composeai.simulator.converter.provided";

  /** The class entries of {@value #PROVIDED_PROPERTY}'s jars, read once; null when unset. */
  static final class ProvidedTestClasses {
    static final Set<String> NAMES = read(System.getProperty(PROVIDED_PROPERTY));

    static Set<String> read(String jars) {
      if (jars == null || jars.isEmpty()) return null;
      Set<String> names = new HashSet<>();
      for (String jar : jars.split(java.io.File.pathSeparator)) {
        if (jar.isEmpty()) continue;
        try (ZipFile zip = new ZipFile(jar)) {
          zip.stream()
              .map(java.util.zip.ZipEntry::getName)
              .filter(n -> n.startsWith("androidx/test/") && n.endsWith(".class"))
              .forEach(names::add);
        } catch (IOException e) {
          System.err.println("compose-preview: cannot read " + jar + ": " + e);
        }
      }
      return names;
    }

    private ProvidedTestClasses() {}
  }

  /**
   * Whether [entryName] is a class the simulator itself supplies: either one of the {@link
   * #SHARED_LIBRARY_PACKAGES} present on [host], or a leaked {@code androidx.test} class (the
   * simulator's Robolectric sidecar carries the full {@code androidx.test:core}, {@code monitor},
   * {@code runner} and {@code espresso} jars, and an R8-shrunk copy bundled in an APK — such as
   * {@code DeclarativeWatchFaceRuntime.apk}'s stripped {@code Checks} — would otherwise shadow the
   * methods {@code RoboMonitoringInstrumentation} calls).
   */
  static boolean providedBySimulator(String entryName, ClassLoader host) {
    if (!entryName.endsWith(".class")) return false;
    if (entryName.startsWith("androidx/test/")) {
      Set<String> provided = ProvidedTestClasses.NAMES;
      return provided == null || provided.contains(entryName);
    }
    for (String prefix : SHARED_LIBRARY_PACKAGES) {
      if (entryName.startsWith(prefix)) return host != null && host.getResource(entryName) != null;
    }
    return false;
  }

  /**
   * Fixes interface method references and computes stack-map frames, so the class passes the
   * JVM's type-checking verifier. A class ASM cannot compute frames for keeps recomputed maxima
   * only, and is counted.
   */
  static byte[] rewrite(byte[] bytes, ClassHierarchy hierarchy, Summary summary) {
    return rewrite(bytes, Collections.emptySet(), hierarchy, summary);
  }

  static byte[] rewrite(
      byte[] bytes, Set<String> synthesizedInits, ClassHierarchy hierarchy, Summary summary) {
    int[] fixed = {0};
    try {
      byte[] framed =
          rewrite(bytes, synthesizedInits, hierarchy, ClassWriter.COMPUTE_FRAMES, fixed);
      summary.interfaceCallsFixed += fixed[0];
      return framed;
    } catch (RuntimeException e) {
      summary.unframedClasses++;
      if (summary.failures.size() < REPORTED_FAILURES) {
        summary.failures.add(new ClassReader(bytes).getClassName() + ": frames: " + e);
      }
      fixed[0] = 0;
      byte[] maxed = rewrite(bytes, synthesizedInits, hierarchy, ClassWriter.COMPUTE_MAXS, fixed);
      summary.interfaceCallsFixed += fixed[0];
      return maxed;
    }
  }

  /**
   * Protected becomes public. The JVM's verifier lets a subclass in another package read a
   * protected member only through a receiver of its own type; ART does not check that, and R8
   * output relies on it (Play Services' measurement classes failed with "Bad access to protected
   * data"). Making the member public changes nothing else: the JVM does not check an override's
   * access against the method it overrides.
   */
  private static int widen(int access) {
    return (access & Opcodes.ACC_PROTECTED) != 0
        ? (access & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC
        : access;
  }

  private static final String SOFTWARE_RENDER_NODES =
      "ee/schimke/composeai/simulator/SoftwareRenderNodes";

  /**
   * A {@code Canvas.drawRenderNode} call made through a plain {@code Canvas} reference, which on a
   * software canvas throws. Rewritten to {@link SoftwareRenderNodes#draw}, which passes a hardware
   * canvas through unchanged. Calls typed {@code RecordingCanvas} are always hardware and are left
   * alone.
   */
  static boolean isSoftwareUnsafeDrawRenderNode(
      int opcode, String owner, String method, String descriptor) {
    return opcode == Opcodes.INVOKEVIRTUAL
        && "android/graphics/Canvas".equals(owner)
        && "drawRenderNode".equals(method)
        && "(Landroid/graphics/RenderNode;)V".equals(descriptor);
  }

  private static final String SIMULATOR_CLOCK = "ee/schimke/composeai/simulator/SimulatorClock";

  /** {@code ZonedDateTime.now()}, which reads the host's clock rather than the simulated one. */
  static boolean isZonedDateTimeNow(int opcode, String owner, String method, String descriptor) {
    return opcode == Opcodes.INVOKESTATIC
        && "java/time/ZonedDateTime".equals(owner)
        && "now".equals(method)
        && "()Ljava/time/ZonedDateTime;".equals(descriptor);
  }

  private static byte[] rewrite(
      byte[] bytes,
      Set<String> synthesizedInits,
      ClassHierarchy hierarchy,
      int compute,
      int[] fixed) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer =
        new ClassWriter(compute) {
          @Override
          protected String getCommonSuperClass(String type1, String type2) {
            return hierarchy.commonSuperClass(type1, type2);
          }
        };
    // ASM 9.11+ caps COMPUTE_FRAMES operations/memory by default, which trips on large Kotlin
    // suspend state machines and leaves them unframed (VerifyError at class load time).
    writer.setComputeLimits(Integer.MAX_VALUE, Long.MAX_VALUE);
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          private String superInternalName;
          private String[] directInterfaces = new String[0];

          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            this.superInternalName = superName;
            this.directInterfaces = interfaces != null ? interfaces : new String[0];
            // Frames need class file version 50+; dex2jar may write older.
            super.visit(
                Math.max(version, Opcodes.V1_8),
                legalClassAccess(access),
                name,
                signature,
                superName,
                interfaces);
          }

          @Override
          public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(name, outerName, innerName, legalClassAccess(access));
          }

          @Override
          public FieldVisitor visitField(
              int access, String name, String desc, String signature, Object value) {
            return super.visitField(widen(access), name, desc, signature, value);
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor delegate =
                super.visitMethod(widen(access), name, desc, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, delegate) {
              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String method, String descriptor, boolean itf) {
                if (isSoftwareUnsafeDrawRenderNode(opcode, owner, method, descriptor)) {
                  super.visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      SOFTWARE_RENDER_NODES,
                      "draw",
                      "(Landroid/graphics/Canvas;Landroid/graphics/RenderNode;)V",
                      false);
                  return;
                }
                if (isZonedDateTimeNow(opcode, owner, method, descriptor)) {
                  super.visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      SIMULATOR_CLOCK,
                      "zonedNow",
                      "()Ljava/time/ZonedDateTime;",
                      false);
                  return;
                }
                boolean staticOrSpecial =
                    opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL;
                if (!itf && staticOrSpecial && hierarchy.isInterface(owner)) {
                  itf = true;
                  fixed[0]++;
                }
                if (opcode == Opcodes.INVOKESPECIAL && itf && !"<init>".equals(method)) {
                  String target = superCallTarget(owner, method + descriptor);
                  if (target != null) {
                    fixed[0]++;
                    super.visitMethodInsn(
                        opcode, target, method, descriptor, hierarchy.isInterface(target));
                    return;
                  }
                }
                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
              }

              /**
               * Where a {@code super} call to [owner]'s default method must point for HotSpot, or
               * null if it already may (or no direct supertype can stand in). The JVM accepts an
               * {@code invokespecial} of an interface method only from a class that implements
               * that interface directly; dex's {@code invoke-super} has no such rule, and R8's
               * member rebinding writes the interface that declares the method rather than the
               * supertype the source named (an {@code AbstractMap} subclass's {@code
               * super.getOrDefault} becomes {@code Map.getOrDefault}). The call is pointed at the
               * superclass or a direct superinterface whose resolution of [method] selects the
               * same declaration as [owner]'s, so the same code runs: a supertype that merely
               * extends [owner] is not enough, since another path to it may override the method.
               */
              private String superCallTarget(String owner, String method) {
                for (String direct : directInterfaces) {
                  if (direct.equals(owner)) return null;
                }
                String declaration = hierarchy.resolve(owner, method);
                if (declaration == null) return null;
                if (superInternalName != null
                    && !"java/lang/Object".equals(superInternalName)
                    && hierarchy.isSubtypeOf(superInternalName, owner)
                    && declaration.equals(hierarchy.resolve(superInternalName, method))) {
                  return superInternalName;
                }
                for (String direct : directInterfaces) {
                  if (hierarchy.isSubtypeOf(direct, owner)
                      && declaration.equals(hierarchy.resolve(direct, method))) {
                    return direct;
                  }
                }
                return null;
              }
            };
          }

          @Override
          public void visitEnd() {
            if (superInternalName != null) {
              for (String spec : synthesizedInits) {
                int sep = spec.indexOf('>');
                String bridgeDesc = sep >= 0 ? spec.substring(0, sep) : spec;
                String superCallDesc = sep >= 0 ? spec.substring(sep + 1) : spec;
                MethodVisitor mv =
                    super.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                        "<init>",
                        bridgeDesc,
                        null,
                        null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                int stack = 1;
                for (Type argType : Type.getArgumentTypes(superCallDesc)) {
                  mv.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), stack);
                  stack += argType.getSize();
                }
                int locals = 1;
                for (Type argType : Type.getArgumentTypes(bridgeDesc)) {
                  locals += argType.getSize();
                }
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, superInternalName, "<init>", superCallDesc, false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(stack, locals);
                mv.visitEnd();
              }
            }
            super.visitEnd();
          }
        },
        ClassReader.SKIP_FRAMES);
    return writer.toByteArray();
  }

  /**
   * Supertypes and interface-ness by internal name, read from class headers without loading
   * anything: the APK's own classes, then the platform `android.jar`, then the JDK.
   */
  static final class ClassHierarchy implements AutoCloseable {

    /** What frame computation needs from one class. */
    static final class Header {
      final String superName;
      final boolean isInterface;
      final String[] interfaces;

      /** Instance methods the type declares, as {@code name + descriptor}. */
      final Set<String> methods;

      Header(String superName, boolean isInterface) {
        this(superName, isInterface, NO_INTERFACES, Set.of());
      }

      Header(String superName, boolean isInterface, String[] interfaces, Set<String> methods) {
        this.superName = superName;
        this.isInterface = isInterface;
        this.interfaces = interfaces;
        this.methods = methods;
      }

      static Header of(ClassReader reader) {
        Set<String> methods = new HashSet<>();
        reader.accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String desc, String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_STATIC) == 0 && !name.startsWith("<")) {
                  methods.add(name + desc);
                }
                return null;
              }
            },
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Header(
            reader.getSuperName(),
            (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0,
            reader.getInterfaces(),
            methods);
      }
    }

    private final Map<String, Header> appClasses;
    private final ZipFile platform;
    private final Map<String, Header> platformClasses = new HashMap<>();
    private Set<String> lookups;

    /** Adds each type looked up from now on to [lookups]; null stops recording. */
    void recordInto(Set<String> lookups) {
      this.lookups = lookups;
    }

    /** [types] with what each resolves to, as {@link #resolvesAsBefore} reads it back. */
    String describe(Set<String> types) {
      StringBuilder out = new StringBuilder();
      for (String type : types) {
        if (out.length() > 0) out.append(' ');
        out.append(type).append('=').append(resolution(type));
      }
      return out.toString();
    }

    /** Whether every type in a {@link #describe} result still resolves as it did then. */
    boolean resolvesAsBefore(String described) {
      if (described.isEmpty()) return true;
      for (String dependency : described.split(" ")) {
        int equals = dependency.lastIndexOf('=');
        if (equals <= 0) return false;
        String type = dependency.substring(0, equals);
        if (!resolution(type).equals(dependency.substring(equals + 1))) return false;
      }
      return true;
    }

    private String resolution(String type) {
      Header header = header(type);
      if (header == null) return "?";
      StringBuilder out =
          new StringBuilder(header.isInterface ? "I:" : "C:").append(header.superName);
      for (String iface : header.interfaces) out.append(',').append(iface);
      // Which methods a supertype declares decides where a retargeted super call resolves.
      int methods = 0;
      for (String method : header.methods) methods += method.hashCode();
      out.append('#').append(Integer.toHexString(methods));
      return out.toString();
    }

    private static final String[] NO_INTERFACES = new String[0];

    private static final Header MISSING = new Header(null, false);

    ClassHierarchy(Map<String, Header> appClasses, ZipFile platform) {
      this.appClasses = appClasses;
      this.platform = platform;
      this.context = openContext();
    }

    /**
     * {@value #CONTEXT_PROPERTY}: jars of classes this APK's code runs beside, looked up after the
     * APK's own and before the platform's. A test APK is converted with its app's classes here:
     * its calls into the app's interfaces' static and default methods (Kotlin's {@code $default}
     * on an interface) are interface calls only the app's classes can tell.
     */
    static final String CONTEXT_PROPERTY = "composeai.simulator.converter.context";

    private final List<ZipFile> context;

    private static List<ZipFile> openContext() {
      Set<String> paths = new LinkedHashSet<>();
      addJarPaths(paths, System.getProperty(CONTEXT_PROPERTY));
      // Simulator-provided jars (e.g. androidx.test / espresso) whose copies were stripped from the
      // APK by providedBySimulator must still be visible here so ASM COMPUTE_FRAMES computes real
      // common superclasses (e.g. Throwable for multi-catch NoMatchingViewException | Error).
      addJarPaths(paths, System.getProperty(PROVIDED_PROPERTY));
      if (paths.isEmpty()) return List.of();
      List<ZipFile> open = new ArrayList<>(paths.size());
      for (String jar : paths) {
        try {
          open.add(new ZipFile(jar));
        } catch (IOException e) {
          System.err.println("ApkClassConverter: cannot open context jar " + jar + ": " + e);
        }
      }
      return open;
    }

    private static void addJarPaths(Set<String> out, String jars) {
      if (jars == null || jars.isEmpty()) return;
      for (String jar : jars.split(java.io.File.pathSeparator)) {
        if (!jar.isEmpty()) out.add(jar);
      }
    }

    @Override
    public void close() {
      for (ZipFile jar : context) {
        try {
          jar.close();
        } catch (IOException e) {
          // Best-effort cleanup.
        }
      }
    }

    boolean isInterface(String name) {
      Header header = header(name);
      return header != null && header.isInterface;
    }

    /** Whether [type] is [ancestor] or extends or implements it, through any path. */
    boolean isSubtypeOf(String type, String ancestor) {
      return isSubtypeOf(type, ancestor, new HashSet<>());
    }

    /**
     * The type whose declaration of [method] (name + descriptor) the JVM selects for an {@code
     * invokespecial} naming [type], per JVMS 5.4.3.3 / 5.4.3.4: the first declaration up a class's
     * superclass chain, else the single maximally-specific superinterface declaring it. Null when
     * nothing declares it or the choice is ambiguous.
     */
    String resolve(String type, String method) {
      Header header = header(type);
      if (header == null) return null;
      if (header.methods.contains(method)) return type;
      List<String> chain = new ArrayList<>();
      if (!header.isInterface) {
        Set<String> seen = new HashSet<>();
        for (String c = header.superName; c != null && seen.add(c); c = superName(c)) {
          Header superHeader = header(c);
          if (superHeader == null) return null;
          if (superHeader.methods.contains(method)) return c;
          chain.add(c);
        }
      }
      Set<String> declaring = new LinkedHashSet<>();
      Set<String> visited = new HashSet<>();
      collectDeclaring(type, method, declaring, visited);
      for (String c : chain) collectDeclaring(c, method, declaring, visited);
      List<String> maximal = new ArrayList<>();
      for (String candidate : declaring) {
        boolean shadowed = false;
        for (String other : declaring) {
          if (!other.equals(candidate) && isSubtypeOf(other, candidate)) {
            shadowed = true;
            break;
          }
        }
        if (!shadowed) maximal.add(candidate);
      }
      return maximal.size() == 1 ? maximal.get(0) : null;
    }

    /** The superinterfaces of [type], transitively, that declare [method]. */
    private void collectDeclaring(
        String type, String method, Set<String> declaring, Set<String> visited) {
      Header header = header(type);
      if (header == null) return;
      for (String iface : header.interfaces) {
        if (!visited.add(iface)) continue;
        Header ifaceHeader = header(iface);
        if (ifaceHeader == null) continue;
        if (ifaceHeader.methods.contains(method)) declaring.add(iface);
        collectDeclaring(iface, method, declaring, visited);
      }
    }

    private boolean isSubtypeOf(String type, String ancestor, Set<String> seen) {
      if (type == null || !seen.add(type)) return false;
      if (type.equals(ancestor)) return true;
      Header header = header(type);
      if (header == null) return false;
      if (isSubtypeOf(header.superName, ancestor, seen)) return true;
      for (String iface : header.interfaces) {
        if (isSubtypeOf(iface, ancestor, seen)) return true;
      }
      return false;
    }

    /**
     * The nearest common superclass, as {@link ClassWriter#getCommonSuperClass} defines it: {@code
     * java/lang/Object} when either is an interface. A type no source knows — an optional
     * dependency the app compiled against but does not ship, such as the window extensions —
     * also resolves to {@code java/lang/Object}: its class cannot load anyway.
     */
    String commonSuperClass(String type1, String type2) {
      Header first = header(type1);
      Header second = header(type2);
      if (first == null || second == null) return "java/lang/Object";
      if (first.isInterface || second.isInterface) return "java/lang/Object";
      Set<String> ancestors = new HashSet<>();
      for (String type = type1; type != null && ancestors.add(type); type = superName(type)) {}
      Set<String> seen = new HashSet<>();
      for (String type = type2; type != null && seen.add(type); type = superName(type)) {
        if (ancestors.contains(type)) return type;
      }
      return "java/lang/Object";
    }

    private String superName(String name) {
      Header header = header(name);
      return header == null ? null : header.superName;
    }

    private Header header(String name) {
      if (lookups != null) lookups.add(name);
      Header app = appClasses.get(name);
      if (app != null) return app;
      Header cached = platformClasses.get(name);
      if (cached != null) return cached == MISSING ? null : cached;
      Header found = readPlatform(name);
      platformClasses.put(name, found != null ? found : MISSING);
      return found;
    }

    private Header readPlatform(String name) {
      String resource = name + ".class";
      for (ZipFile jar : context) {
        ZipEntry entry = jar.getEntry(resource);
        if (entry == null) continue;
        try (InputStream stream = jar.getInputStream(entry)) {
          return Header.of(new ClassReader(stream));
        } catch (IOException e) {
          // Unreadable: try the next jar, then the platform.
        }
      }
      try {
        ZipEntry entry = platform.getEntry(resource);
        // `.class` resources of the JDK's named modules are not encapsulated.
        InputStream stream =
            entry != null
                ? platform.getInputStream(entry)
                : ClassLoader.getSystemResourceAsStream(resource);
        if (stream == null) return null;
        try (stream) {
          return Header.of(new ClassReader(stream));
        }
      } catch (IOException e) {
        return null;
      }
    }
  }

  /**
   * One line per class of a conversion, so the next build of the app can reuse it: the class's dex
   * name, its {@link Translator#key}, its entry in the jar, what the conversion reported for it,
   * and the supertype lookups its frames were computed from. Tab-separated; a type name never
   * holds a tab, space or newline, and failure messages have theirs replaced.
   */
  static final class Index {
    static final String HEADER = "composeai-apk-classes 2";

    static final class Entry {
      final String descriptor;
      final String key;
      final String entryName;
      int interfaceCallsFixed;
      boolean unframed;
      boolean withoutDebugInfo;
      int failedMethods;
      String dependencies = "";
      String inlinedInits = "";
      String synthesizedInits = "";
      final List<String> failures = new ArrayList<>();

      Entry(String descriptor, String key, String entryName) {
        this.descriptor = descriptor;
        this.key = key;
        this.entryName = entryName;
      }
    }

    static Map<String, Entry> read(Path index) {
      Map<String, Entry> entries = new HashMap<>();
      if (!Files.isRegularFile(index)) return entries;
      try {
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) return entries;
        for (String line : lines.subList(1, lines.size())) {
          String[] f = line.split("\t", -1);
          if (f.length < 11) return new HashMap<>();
          Entry entry = new Entry(f[0], f[1], f[2]);
          entry.interfaceCallsFixed = Integer.parseInt(f[3]);
          entry.unframed = f[4].equals("1");
          entry.withoutDebugInfo = f[5].equals("1");
          entry.failedMethods = Integer.parseInt(f[6]);
          entry.dependencies = f[7];
          entry.inlinedInits = f[8];
          entry.synthesizedInits = f[9];
          for (int i = 10; i < f.length; i++) {
            if (!f[i].isEmpty()) entry.failures.add(f[i]);
          }
          entries.put(entry.descriptor, entry);
        }
      } catch (IOException | RuntimeException e) {
        // An unreadable index only means nothing is reused.
        System.err.println("ApkClassConverter: ignoring the previous index " + index + ": " + e);
        return new HashMap<>();
      }
      return entries;
    }

    static final class Writer implements AutoCloseable {
      private final java.io.Writer out;

      Writer(Path index) throws IOException {
        out = Files.newBufferedWriter(index, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.write('\n');
      }

      void write(Entry entry) throws IOException {
        out.write(entry.descriptor + "\t" + entry.key + "\t" + entry.entryName);
        out.write("\t" + entry.interfaceCallsFixed + "\t" + (entry.unframed ? 1 : 0));
        out.write("\t" + (entry.withoutDebugInfo ? 1 : 0) + "\t" + entry.failedMethods);
        out.write("\t" + entry.dependencies);
        out.write("\t" + entry.inlinedInits + "\t" + entry.synthesizedInits);
        if (entry.failures.isEmpty()) out.write('\t');
        for (String failure : entry.failures) {
          out.write('\t');
          out.write(failure.replaceAll("[\\t\\r\\n]", " "));
        }
        out.write('\n');
      }

      @Override
      public void close() throws IOException {
        out.close();
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(String.format("%02x", b));
    return out.toString();
  }

  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.toString();
  }

  private ApkClassConverter() {}
}
