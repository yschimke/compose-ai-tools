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
import com.googlecode.d2j.visitors.DexClassVisitor;
import com.googlecode.d2j.visitors.DexFieldVisitor;
import com.googlecode.d2j.visitors.DexMethodVisitor;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.expr.BinopExpr;
import com.googlecode.dex2jar.ir.expr.Constant;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
    if (args.length != 3) {
      System.err.println("usage: ApkClassConverter <app.apk> <out.jar> <platform android.jar>");
      System.exit(2);
    }
    Summary summary = convert(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
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
    public final List<String> failures = new ArrayList<>();

    String toJson() {
      StringBuilder json = new StringBuilder();
      json.append("{\"classes\":").append(classes);
      json.append(",\"interfaceCallsFixed\":").append(interfaceCallsFixed);
      json.append(",\"failedMethods\":").append(failedMethods);
      json.append(",\"unframedClasses\":").append(unframedClasses);
      json.append(",\"withoutDebugInfo\":").append(withoutDebugInfo);
      json.append(",\"sharedLibraryClasses\":").append(sharedLibraryClasses);
      json.append(",\"failures\":[");
      for (int i = 0; i < failures.size(); i++) {
        if (i > 0) json.append(',');
        json.append('"').append(escape(failures.get(i))).append('"');
      }
      return json.append("]}").toString();
    }
  }

  public static Summary convert(Path apk, Path out, Path platformJar) throws IOException {
    Summary summary = new Summary();
    Path translated = Files.createTempFile(out.toAbsolutePath().getParent(), "dex2jar-", ".jar");
    try {
      // The APK's bytes are dropped once its dex files are extracted.
      BaseDexFileReader reader;
      try {
        reader = MultiDexFileReader.open(Files.readAllBytes(apk));
      } catch (IOException e) {
        // As dex2jar does: an APK with no dex (a resource-only app) converts to an empty jar.
        new BaseDexExceptionHandler().handleFileException(e);
        reader = new MultiDexFileReader(List.of());
      }
      Translator.ClassInfo classInfo = Translator.classInfo(reader);
      // Line numbers and local names first, so an app crash names its source line. dex2jar's type
      // inference sometimes trips on a method's debug local types; those classes are translated
      // again without debug info, where they usually translate.
      Map<String, List<String>> failed;
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(translated))) {
        failed =
            translate(
                reader,
                classInfo,
                false,
                null,
                (name, bytes) -> {
                  zip.putNextEntry(new ZipEntry(name));
                  zip.write(bytes);
                  zip.closeEntry();
                });
      }
      Map<String, byte[]> fallback = new HashMap<>();
      if (!failed.isEmpty()) {
        Map<String, byte[]> again = new HashMap<>();
        Map<String, List<String>> failedAgain =
            translate(reader, classInfo, true, failed.keySet(), again::put);
        for (String owner : failed.keySet()) {
          byte[] bytes = again.get(owner + ".class");
          if (failedAgain.containsKey(owner) || bytes == null) continue;
          fallback.put(owner + ".class", bytes);
        }
      }
      for (Map.Entry<String, List<String>> e : failed.entrySet()) {
        if (fallback.containsKey(e.getKey() + ".class")) continue;
        for (String failure : e.getValue()) {
          summary.failedMethods++;
          if (summary.failures.size() < REPORTED_FAILURES) summary.failures.add(failure);
        }
      }
      summary.withoutDebugInfo = fallback.size();
      // The dex readers (tens of MB for a large app) are not needed by the ASM pass.
      reader = null;
      classInfo = null;
      fixInterfaceCalls(translated, fallback, out, platformJar, summary);
    } finally {
      Files.deleteIfExists(translated);
    }
    return summary;
  }

  /** Where {@link #translate} puts each class it writes, by entry name. */
  private interface ClassSink {
    void put(String entryName, byte[] bytes) throws IOException;
  }

  /**
   * One dex2jar pass over the APK's classes — every one, or those {@code only} names (internal
   * names) — handing each class file to {@code sink}. A method it cannot translate becomes a stub that
   * throws; returns them by owner (internal name), each as {@code owner.name(desc): e}.
   *
   * <p>This is {@code Dex2jar.to}, driven from dex2jar's public pieces so {@link
   * #normalizeZeroLeftJumps} runs between its IR passes and code generation, and one class at a
   * time: dex2jar reads the whole APK into one {@code DexFileNode} tree first, every method's
   * instructions included, which for a 70,000-class app is gigabytes of heap. Here only the
   * class being translated is in memory, beside the class-level facts ({@link
   * Translator#classInfo}) that inner-class attributes need from the others.
   */
  private static Map<String, List<String>> translate(
      BaseDexFileReader reader,
      Translator.ClassInfo classInfo,
      boolean skipDebug,
      Set<String> only,
      ClassSink sink) {
    Map<String, List<String>> failed = new HashMap<>();
    BaseDexExceptionHandler handler =
        new BaseDexExceptionHandler() {
          @Override
          public void handleMethodTranslateException(
              Method method, DexMethodNode node, MethodVisitor mv, Exception e) {
            String owner = internalName(method.getOwner());
            failed
                .computeIfAbsent(owner, k -> new ArrayList<>())
                .add(method.getOwner() + "." + method.getName() + method.getDesc() + ": " + e);
            super.handleMethodTranslateException(method, node, mv, e);
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
              try {
                sink.put(names.getClassName() + ".class", writer.toByteArray());
              } catch (Exception e) {
                handler.handleFileException(e);
              }
            }
          };
        };
    Translator translator = new Translator(handler);
    int config = DexFileReader.IGNORE_READ_EXCEPTION | (skipDebug ? DexFileReader.SKIP_DEBUG : 0);
    int dexVersion = reader.getDexVersion();
    List<String> names = reader.getClassNames();
    for (int i = 0; i < names.size(); i++) {
      if (only != null && !only.contains(internalName(names.get(i)))) continue;
      DexFileNode one = new DexFileNode();
      try {
        reader.accept(one, i, config);
      } catch (Exception e) {
        handler.handleFileException(e);
        continue;
      }
      for (DexClassNode classNode : one.clzs) {
        translator.convertClass(dexVersion, classNode, classes, classInfo.classes);
      }
    }
    return failed;
  }

  /** {@code Lcom/example/Foo;} as {@code com/example/Foo}. */
  private static String internalName(String descriptor) {
    return descriptor.startsWith("L") && descriptor.endsWith(";")
        ? descriptor.substring(1, descriptor.length() - 1)
        : descriptor;
  }

  /** dex2jar's translator, with {@code Dex2jar}'s pass order and the zero-left jump fix. */
  private static final class Translator extends ExDex2Asm {

    Translator(BaseDexExceptionHandler handler) {
      super(handler);
    }

    /**
     * What {@code Dex2Asm.convertDex} collects from every class before translating any: access
     * flags and the enclosing / inner / member class annotations, for each class's inner-class
     * attributes. Read without code, fields or methods, so it is small.
     */
    static ClassInfo classInfo(BaseDexFileReader reader) {
      DexFileNode headers =
          new DexFileNode() {
            @Override
            public DexClassVisitor visit(
                int access, String className, String superClass, String[] interfaceNames) {
              DexClassNode node =
                  new DexClassNode(access, className, superClass, interfaceNames) {
                    @Override
                    public DexFieldVisitor visitField(int access, Field field, Object value) {
                      return null;
                    }

                    @Override
                    public DexMethodVisitor visitMethod(int access, Method method) {
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
      return new ClassInfo(collectClzInfo(headers));
    }

    /** {@link #classInfo}'s result; dex2jar's {@code Clz} is not visible outside a subclass. */
    static final class ClassInfo {
      final Map<String, Clz> classes;

      ClassInfo(Map<String, Clz> classes) {
        this.classes = classes;
      }
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
      T_NEW.transform(irMethod);
      T_FILL_ARRAY.transform(irMethod);
      T_AGG.transform(irMethod);
      T_MULTI_ARRAY.transform(irMethod);
      T_VOID_INVOKE.transform(irMethod);
      T_DEAD_CODE.transform(irMethod);
      T_REMOVE_LOCAL.transform(irMethod);
      T_REMOVE_CONST.transform(irMethod);
      T_TYPE.transform(irMethod);
      T_UNSSA.transform(irMethod);
      T_IR_2_J_REG_ASSIGN.transform(irMethod);
      T_TRIM_EX.transform(irMethod);
    }

    @Override
    public void ir2j(IrMethod irMethod, MethodVisitor mv, ClzCtx clzCtx) {
      normalizeZeroLeftJumps(irMethod);
      new IR2JConverter().clzCtx(clzCtx).ir(irMethod).asm(mv).convert();
    }
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
   * Whether [entryName] is a class of a shared library that [host], the simulator's own class path,
   * also defines. On a device the library's class loader is the app's parent, so its classes win
   * over any copy the APK carries (often a compile stub missing the methods the app calls); in the
   * sandbox the app's classes come first, so the APK's copy is dropped instead.
   */
  static boolean providedBySimulator(String entryName, ClassLoader host) {
    if (!entryName.endsWith(".class")) return false;
    for (String prefix : SHARED_LIBRARY_PACKAGES) {
      if (entryName.startsWith(prefix)) return host != null && host.getResource(entryName) != null;
    }
    return false;
  }

  private static void fixInterfaceCalls(
      Path in, Map<String, byte[]> replacements, Path out, Path platformJar, Summary summary)
      throws IOException {
    try (ZipFile jar = new ZipFile(in.toFile());
        ZipFile platform = new ZipFile(platformJar.toFile())) {
      Map<String, ClassHierarchy.Header> appClasses = new HashMap<>();
      for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
        ZipEntry entry = e.nextElement();
        if (!entry.getName().endsWith(".class")) continue;
        try (InputStream stream = jar.getInputStream(entry)) {
          ClassReader reader = new ClassReader(stream);
          appClasses.put(reader.getClassName(), ClassHierarchy.Header.of(reader));
        }
      }
      ClassHierarchy hierarchy = new ClassHierarchy(appClasses, platform);
      try (OutputStream file = Files.newOutputStream(out);
          ZipOutputStream zip = new ZipOutputStream(file)) {
        for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
          ZipEntry entry = e.nextElement();
          if (entry.isDirectory()) continue;
          if (providedBySimulator(entry.getName(), ApkClassConverter.class.getClassLoader())) {
            summary.sharedLibraryClasses++;
            continue;
          }
          byte[] bytes = replacements.get(entry.getName());
          if (bytes == null) {
            try (InputStream stream = jar.getInputStream(entry)) {
              bytes = stream.readAllBytes();
            }
          }
          if (entry.getName().endsWith(".class")) {
            summary.classes++;
            bytes = rewrite(bytes, hierarchy, summary);
          }
          zip.putNextEntry(new ZipEntry(entry.getName()));
          zip.write(bytes);
          zip.closeEntry();
        }
      }
    }
  }

  /**
   * Fixes interface method references and computes stack-map frames, so the class passes the
   * JVM's type-checking verifier. A class ASM cannot compute frames for keeps recomputed maxima
   * only, and is counted.
   */
  static byte[] rewrite(byte[] bytes, ClassHierarchy hierarchy, Summary summary) {
    int[] fixed = {0};
    try {
      byte[] framed = rewrite(bytes, hierarchy, ClassWriter.COMPUTE_FRAMES, fixed);
      summary.interfaceCallsFixed += fixed[0];
      return framed;
    } catch (RuntimeException e) {
      summary.unframedClasses++;
      if (summary.failures.size() < REPORTED_FAILURES) {
        summary.failures.add(new ClassReader(bytes).getClassName() + ": frames: " + e);
      }
      fixed[0] = 0;
      byte[] maxed = rewrite(bytes, hierarchy, ClassWriter.COMPUTE_MAXS, fixed);
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

  private static byte[] rewrite(byte[] bytes, ClassHierarchy hierarchy, int compute, int[] fixed) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer =
        new ClassWriter(compute) {
          @Override
          protected String getCommonSuperClass(String type1, String type2) {
            return hierarchy.commonSuperClass(type1, type2);
          }
        };
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            // Frames need class file version 50+; dex2jar may write older.
            super.visit(
                Math.max(version, Opcodes.V1_8), access, name, signature, superName, interfaces);
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
                boolean staticOrSpecial =
                    opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL;
                if (!itf && staticOrSpecial && hierarchy.isInterface(owner)) {
                  itf = true;
                  fixed[0]++;
                }
                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
              }
            };
          }
        },
        ClassReader.SKIP_FRAMES);
    return writer.toByteArray();
  }

  /**
   * Supertypes and interface-ness by internal name, read from class headers without loading
   * anything: the APK's own classes, then the platform `android.jar`, then the JDK.
   */
  static final class ClassHierarchy {

    /** What frame computation needs from one class. */
    static final class Header {
      final String superName;
      final boolean isInterface;

      Header(String superName, boolean isInterface) {
        this.superName = superName;
        this.isInterface = isInterface;
      }

      static Header of(ClassReader reader) {
        return new Header(
            reader.getSuperName(), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
      }
    }

    private final Map<String, Header> appClasses;
    private final ZipFile platform;
    private final Map<String, Header> platformClasses = new HashMap<>();

    ClassHierarchy(Map<String, Header> appClasses, ZipFile platform) {
      this.appClasses = appClasses;
      this.platform = platform;
    }

    boolean isInterface(String name) {
      Header header = header(name);
      return header != null && header.isInterface;
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
      for (String type = type1; type != null; type = superName(type)) ancestors.add(type);
      for (String type = type2; type != null; type = superName(type)) {
        if (ancestors.contains(type)) return type;
      }
      return "java/lang/Object";
    }

    private String superName(String name) {
      Header header = header(name);
      return header == null ? null : header.superName;
    }

    private Header header(String name) {
      Header app = appClasses.get(name);
      if (app != null) return app;
      return platformClasses.computeIfAbsent(name, this::readPlatform);
    }

    private Header readPlatform(String name) {
      String resource = name + ".class";
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
