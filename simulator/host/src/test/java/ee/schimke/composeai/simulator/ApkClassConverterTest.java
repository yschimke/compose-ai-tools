package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlecode.d2j.dex.writer.DexFileWriter;
import com.googlecode.d2j.smali.Smali;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.Local;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.stmt.LabelStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import com.googlecode.dex2jar.ir.stmt.Stmts;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The converter's ASM pass, on classes shaped like dex2jar's output: no frames, an undercounted
 * `max_stack`, and static interface calls written as class method references. Each converted class
 * is then loaded by a verifying JVM, which is the property the simulator relies on.
 */
public class ApkClassConverterTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void staticInterfaceCallsBecomeInterfaceMethodRefs() throws Exception {
    byte[] api = iface("test/Api");
    byte[] user =
        cls(
            "test/User",
            mv -> {
              mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Api", "make", "()I", false);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  "java/util/Comparator",
                  "naturalOrder",
                  "()Ljava/util/Comparator;",
                  false);
              mv.visitInsn(Opcodes.POP);
              mv.visitInsn(Opcodes.IRETURN);
            });
    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    byte[] fixed = ApkClassConverter.rewrite(user, hierarchy(api, user), summary);

    assertEquals(2, summary.interfaceCallsFixed);
    for (MethodInsnNode call : calls(fixed, "run")) {
      assertTrue(call.owner + " is an interface", call.itf);
    }
    assertEquals(42, invoke(Map.of("test/Api", api, "test/User", fixed), "test.User"));
  }

  @Test
  public void framesAndMaximaAreComputedForAVerifyingJvm() throws Exception {
    // `run` merges an ArrayList and a LinkedList into one local (needing a frame whose type is
    // their common superclass) and declares a max stack of 1 for code that needs 3.
    byte[] user =
        cls(
            "test/Merge",
            mv -> {
              Label linked = new Label();
              Label merged = new Label();
              mv.visitInsn(Opcodes.ICONST_1);
              mv.visitJumpInsn(Opcodes.IFEQ, linked);
              mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
              mv.visitInsn(Opcodes.DUP);
              mv.visitMethodInsn(
                  Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
              mv.visitVarInsn(Opcodes.ASTORE, 0);
              mv.visitJumpInsn(Opcodes.GOTO, merged);
              mv.visitLabel(linked);
              mv.visitTypeInsn(Opcodes.NEW, "java/util/LinkedList");
              mv.visitInsn(Opcodes.DUP);
              mv.visitMethodInsn(
                  Opcodes.INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false);
              mv.visitVarInsn(Opcodes.ASTORE, 0);
              mv.visitLabel(merged);
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitLdcInsn("x");
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "java/util/AbstractList",
                  "add",
                  "(Ljava/lang/Object;)Z",
                  false);
              mv.visitInsn(Opcodes.POP);
              mv.visitIntInsn(Opcodes.BIPUSH, 42);
              mv.visitInsn(Opcodes.IRETURN);
            });
    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    byte[] fixed = ApkClassConverter.rewrite(user, hierarchy(user), summary);

    assertEquals(0, summary.unframedClasses);
    MethodNode run = method(fixed, "run");
    assertTrue("max stack " + run.maxStack, run.maxStack >= 2);
    assertEquals(42, invoke(Map.of("test/Merge", fixed), "test.Merge"));
  }

  @Test
  public void zeroOnTheLeftIsMirroredBeforeCodeGeneration() {
    Local x = Exprs.nLocal(0);
    LabelStmt target = Stmts.nLabel();
    IrMethod method = new IrMethod();
    method.stmts.add(Stmts.nIf(Exprs.nLe(Exprs.nInt(0), x, "I"), target)); // 0 <= x
    method.stmts.add(Stmts.nIf(Exprs.nGt(Exprs.nInt(0), x, "I"), target)); // 0 > x
    method.stmts.add(Stmts.nIf(Exprs.nEq(Exprs.nInt(0), x, "I"), target)); // symmetric
    method.stmts.add(Stmts.nIf(Exprs.nLt(x, Exprs.nInt(0), "I"), target)); // already x OP 0
    method.stmts.add(target);

    assertEquals(2, ApkClassConverter.normalizeZeroLeftJumps(method));

    List<String> conditions = new java.util.ArrayList<>();
    for (Stmt stmt : method.stmts) {
      if (stmt.st != Stmt.ST.IF) continue;
      Value condition = stmt.getOp();
      boolean xFirst = condition.getOp1() == x || condition.vt == Value.VT.EQ;
      assertTrue(condition + " compares x first", xFirst);
      conditions.add(condition.vt.name());
    }
    assertEquals(List.of("GE", "LT", "EQ", "LT"), conditions);
  }

  @Test
  public void protectedMembersBecomePublic() throws Exception {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Base", null, "java/lang/Object", null);
    writer.visitField(Opcodes.ACC_PROTECTED, "count", "I", null, null).visitEnd();
    MethodVisitor mv =
        writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC, "make", "()I", null, null);
    mv.visitCode();
    mv.visitInsn(Opcodes.ICONST_0);
    mv.visitInsn(Opcodes.IRETURN);
    mv.visitMaxs(1, 0);
    mv.visitEnd();
    writer.visitEnd();
    byte[] base = writer.toByteArray();

    ClassNode node = new ClassNode();
    byte[] rewritten =
        ApkClassConverter.rewrite(base, hierarchy(base), new ApkClassConverter.Summary());
    new ClassReader(rewritten).accept(node, 0);
    assertEquals(Opcodes.ACC_PUBLIC, node.fields.get(0).access);
    assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method(node, "make").access);
  }

  @Test
  public void unknownSupertypesResolveToObject() throws Exception {
    ApkClassConverter.ClassHierarchy hierarchy = hierarchy();
    assertEquals(
        "java/util/AbstractList",
        hierarchy.commonSuperClass("java/util/ArrayList", "java/util/LinkedList"));
    assertEquals(
        "java/lang/Object", hierarchy.commonSuperClass("java/util/ArrayList", "missing/Type"));
    assertEquals("java/lang/Object", hierarchy.commonSuperClass("java/util/List", "java/util/Map"));
  }

  @Test
  public void summaryEscapesJson() {
    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    summary.failures.add("a \"quoted\"\nline\\");
    assertEquals(
        "{\"classes\":0,\"interfaceCallsFixed\":0,\"failedMethods\":0,\"unframedClasses\":0,"
            + "\"withoutDebugInfo\":0,\"sharedLibraryClasses\":0,\"reused\":0,"
            + "\"failures\":[\"a \\\"quoted\\\"\\nline\\\\\"]}",
        summary.toJson());
  }

  @Test
  public void aRebuiltApkReusesTheClassesThatDidNotChange() throws Exception {
    Path platform = emptyJar();
    String greeter =
        """
        .class public Lapp/Greeter;
        .super Ljava/lang/Object;
        .method public static greet()Ljava/lang/String;
          .registers 1
          const-string v0, "hello"
          return-object v0
        .end method
        """;
    String counter =
        """
        .class public Lapp/Counter;
        .super Ljava/lang/Object;
        .method public static next(I)I
          .registers 2
          add-int/lit8 v0, p0, %d
          return v0
        .end method
        """;
    Path first = apk("first.apk", greeter, String.format(counter, 1));
    Path firstJar = temp.getRoot().toPath().resolve("first.jar");
    ApkClassConverter.Summary full = ApkClassConverter.convert(first, firstJar, platform, null);
    assertEquals(2, full.classes);
    assertEquals(0, full.reused);
    assertTrue(Files.isRegularFile(ApkClassConverter.indexOf(firstJar)));

    // A three-line edit: Counter adds 2 now. Greeter is copied; Counter is translated again.
    Path second = apk("second.apk", greeter, String.format(counter, 2));
    Path secondJar = temp.getRoot().toPath().resolve("second.jar");
    ApkClassConverter.Summary incremental =
        ApkClassConverter.convert(second, secondJar, platform, firstJar);
    assertEquals(2, incremental.classes);
    assertEquals(1, incremental.reused);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {secondJar.toUri().toURL()}, null)) {
      assertEquals(
          "hello", loader.loadClass("app.Greeter").getMethod("greet").invoke(null));
      assertEquals(
          7, loader.loadClass("app.Counter").getMethod("next", int.class).invoke(null, 5));
    }

    // Unchanged again: everything is copied.
    Path third = temp.getRoot().toPath().resolve("third.jar");
    assertEquals(2, ApkClassConverter.convert(second, third, platform, secondJar).reused);
  }

  @Test
  public void aReusedClassIsFramedAgainWhenASupertypeItUsedChanged() throws Exception {
    Map<String, ApkClassConverter.ClassHierarchy.Header> app = new HashMap<>();
    app.put("app/Base", new ApkClassConverter.ClassHierarchy.Header("java/lang/Object", false));
    app.put("app/Child", new ApkClassConverter.ClassHierarchy.Header("app/Base", false));
    try (ZipFile platform = new ZipFile(emptyJar().toFile())) {
      ApkClassConverter.ClassHierarchy before = new ApkClassConverter.ClassHierarchy(app, platform);
      Set<String> lookups = new TreeSet<>();
      before.recordInto(lookups);
      assertEquals("app/Base", before.commonSuperClass("app/Child", "app/Base"));
      before.recordInto(null);
      String described = before.describe(lookups);
      assertTrue(before.resolvesAsBefore(described));

      // Child now extends Object directly: its frames were computed against a stale hierarchy.
      app.put("app/Child", new ApkClassConverter.ClassHierarchy.Header("java/lang/Object", false));
      ApkClassConverter.ClassHierarchy after = new ApkClassConverter.ClassHierarchy(app, platform);
      assertFalse(after.resolvesAsBefore(described));
    }
  }

  @Test
  public void anIndexRoundTrips() throws Exception {
    Path index = temp.getRoot().toPath().resolve("classes.jar.index");
    ApkClassConverter.Index.Entry entry =
        new ApkClassConverter.Index.Entry("Lapp/A;", "k1", "app/A.class");
    entry.interfaceCallsFixed = 3;
    entry.unframed = true;
    entry.failedMethods = 1;
    entry.dependencies = "app/B=C:java/lang/Object java/util/List=I:java/lang/Object";
    entry.failures.add("Lapp/A;.f()V: java.lang.RuntimeException:\tbad\nline");
    try (ApkClassConverter.Index.Writer writer = new ApkClassConverter.Index.Writer(index)) {
      writer.write(entry);
    }
    ApkClassConverter.Index.Entry read = ApkClassConverter.Index.read(index).get("Lapp/A;");
    assertEquals("k1", read.key);
    assertEquals("app/A.class", read.entryName);
    assertEquals(3, read.interfaceCallsFixed);
    assertTrue(read.unframed);
    assertEquals(1, read.failedMethods);
    assertEquals(entry.dependencies, read.dependencies);
    assertEquals(List.of("Lapp/A;.f()V: java.lang.RuntimeException: bad line"), read.failures);
  }

  /** An APK holding one classes.dex assembled from [smali] sources. */
  private Path apk(String name, String... smali) throws Exception {
    DexFileWriter dex = new DexFileWriter();
    for (int i = 0; i < smali.length; i++) Smali.smaliFile("c" + i + ".smali", smali[i], dex);
    dex.visitEnd();
    Path apk = temp.getRoot().toPath().resolve(name);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
      zip.putNextEntry(new ZipEntry("classes.dex"));
      zip.write(dex.toByteArray());
      zip.closeEntry();
    }
    return apk;
  }

  private Path emptyJar() throws Exception {
    Path jar = temp.getRoot().toPath().resolve("android.jar");
    if (!Files.exists(jar)) {
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
        zip.putNextEntry(new ZipEntry("README"));
        zip.closeEntry();
      }
    }
    return jar;
  }

  // -----------------------------------------------------------------------------------------------

  private interface Body {
    void emit(MethodVisitor mv);
  }

  /** A dex2jar-shaped class: version 52, no frames, `static int run()` with too small maxima. */
  private static byte[] cls(String name, Body body) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    MethodVisitor mv =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
    mv.visitCode();
    body.emit(mv);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** An interface with `static int make()` returning 42. */
  private static byte[] iface(String name) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
        name,
        null,
        "java/lang/Object",
        null);
    MethodVisitor mv =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()I", null, null);
    mv.visitCode();
    mv.visitIntInsn(Opcodes.BIPUSH, 42);
    mv.visitInsn(Opcodes.IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private ApkClassConverter.ClassHierarchy hierarchy(byte[]... appClasses) throws Exception {
    Map<String, ApkClassConverter.ClassHierarchy.Header> headers = new HashMap<>();
    for (byte[] bytes : appClasses) {
      ClassReader reader = new ClassReader(bytes);
      headers.put(reader.getClassName(), ApkClassConverter.ClassHierarchy.Header.of(reader));
    }
    // No platform classes: the JDK answers for `java/*`, as it does for the real converter.
    File platform = temp.newFile("android.jar");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(platform))) {
      zip.setComment("empty platform");
    }
    return new ApkClassConverter.ClassHierarchy(headers, new ZipFile(platform));
  }

  private static MethodNode method(byte[] bytes, String name) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    return method(node, name);
  }

  private static MethodNode method(ClassNode node, String name) {
    return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
  }

  private static List<MethodInsnNode> calls(byte[] bytes, String name) {
    MethodNode method = method(bytes, name);
    java.util.ArrayList<MethodInsnNode> calls = new java.util.ArrayList<>();
    for (var insn : method.instructions) {
      if (insn instanceof MethodInsnNode) calls.add((MethodInsnNode) insn);
    }
    return calls;
  }

  /** Defines [classes] in a fresh loader (the JVM verifies them) and returns `run()`. */
  private static Object invoke(Map<String, byte[]> classes, String main) throws Exception {
    ClassLoader loader =
        new ClassLoader(ApkClassConverterTest.class.getClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
          }
        };
    Method run = loader.loadClass(main).getMethod("run");
    return run.invoke(null);
  }

  @Test
  public void sharedLibraryCopiesTheSimulatorProvidesAreDropped() {
    ClassLoader host = ApkClassConverter.class.getClassLoader();
    // The Wear OS library's ambient controller and the Wear SDK's ambient service and version.
    assertTrue(
        ApkClassConverter.providedBySimulator(
            "com/google/android/wearable/compat/WearableActivityController.class", host));
    assertTrue(
        ApkClassConverter.providedBySimulator(
            "com/google/wear/services/ambient/AmbientManager.class", host));
    assertTrue(ApkClassConverter.providedBySimulator("com/google/wear/Sdk.class", host));
    // A shared-library class the simulator does not provide stays the APK's.
    assertFalse(
        ApkClassConverter.providedBySimulator(
            "com/google/wear/services/tiles/TilesManager.class", host));
    // Outside the libraries' packages nothing is dropped, nor anything that is not a class.
    assertFalse(
        ApkClassConverter.providedBySimulator(
            "ee/schimke/composeai/simulator/AmbientClients.class", host));
    assertFalse(ApkClassConverter.providedBySimulator("com/google/wear/Sdk.txt", host));
  }
}
