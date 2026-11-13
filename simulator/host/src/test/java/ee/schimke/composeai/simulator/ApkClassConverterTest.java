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
    // R8-shrunk androidx.test classes leaked into release APKs must never shadow Robolectric's.
    assertTrue(
        ApkClassConverter.providedBySimulator(
            "androidx/test/internal/util/Checks.class", host));
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

  @Test
  public void r8ConstructorInlinedAncestorInitCallsLoadAndReuseCleanly() throws Exception {
    Path platform = emptyJar();
    // R8 constructor inlining produces two patterns valid on ART but rejected by JVM verification:
    // 1. `new-instance Lapp/Leaf;` followed directly by `invoke-direct v0, Lapp/Root;-><init>(I)V`
    // 2. `Lapp/Leaf;-><init>(Ljava/lang/String;)V` calling `Lapp/Root;-><init>(I)V` on `this`,
    //    skipping `Lapp/Mid;` (while also constructing an unrelated `Ljava/lang/StringBuilder;`
    //    inside `<init>` so `this` tracking must distinguish `this` from local `new-instance`).
    String root =
        """
        .class public Lapp/Root;
        .super Ljava/lang/Object;
        .field public value:I
        .method public constructor <init>(I)V
          .registers 2
          invoke-direct {p0}, Ljava/lang/Object;-><init>()V
          iput p1, p0, Lapp/Root;->value:I
          return-void
        .end method
        """;
    String mid =
        """
        .class public Lapp/Mid;
        .super Lapp/Root;
        """;
    String leaf =
        """
        .class public Lapp/Leaf;
        .super Lapp/Mid;
        .field public label:Ljava/lang/String;
        .method public constructor <init>(Ljava/lang/String;)V
          .registers 4
          new-instance v0, Ljava/lang/StringBuilder;
          invoke-direct {v0, p1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
          invoke-virtual {v0}, Ljava/lang/StringBuilder;->length()I
          move-result v1
          invoke-direct {p0, v1}, Lapp/Root;-><init>(I)V
          iput-object p1, p0, Lapp/Leaf;->label:Ljava/lang/String;
          return-void
        .end method
        """;
    String caller =
        """
        .class public Lapp/Caller;
        .super Ljava/lang/Object;
        .method public static run(I)I
          .registers 4
          new-instance v0, Lapp/Leaf;
          invoke-direct {v0, p0}, Lapp/Root;-><init>(I)V
          new-instance v1, Lapp/Leaf;
          const-string v2, "hello"
          invoke-direct {v1, v2}, Lapp/Leaf;-><init>(Ljava/lang/String;)V
          iget v0, v0, Lapp/Root;->value:I
          iget v1, v1, Lapp/Root;->value:I
          add-int/2addr v0, v1
          return v0
        .end method
        """;
    Path firstApk = apk("inlined-1.apk", root, mid, leaf, caller);
    Path firstJar = temp.getRoot().toPath().resolve("inlined-1.jar");
    ApkClassConverter.Summary first = ApkClassConverter.convert(firstApk, firstJar, platform, null);
    assertEquals(0, first.failedMethods);
    assertEquals(0, first.unframedClasses);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {firstJar.toUri().toURL()}, null)) {
      assertEquals(15, loader.loadClass("app.Caller").getMethod("run", int.class).invoke(null, 10));
    }

    // Rebuild where Caller is edited so Mid/Leaf are reused from the previous jar: Mid and Leaf
    // must keep their synthesized forwarding `<init>(I)V` constructors across incremental builds.
    String callerV2 = caller.replace("add-int/2addr v0, v1", "mul-int/2addr v0, v1");
    Path secondApk = apk("inlined-2.apk", root, mid, leaf, callerV2);
    Path secondJar = temp.getRoot().toPath().resolve("inlined-2.jar");
    ApkClassConverter.Summary second =
        ApkClassConverter.convert(secondApk, secondJar, platform, firstJar);
    assertEquals(3, second.reused);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {secondJar.toUri().toURL()}, null)) {
      assertEquals(50, loader.loadClass("app.Caller").getMethod("run", int.class).invoke(null, 10));
    }
  }

  @Test
  public void inlinedAncestorConstructorSkipsExistingSubclassConstructorsWithSameDescriptor()
      throws Exception {
    Path platform = emptyJar();
    String root =
        """
        .class public Lapp/Root;
        .super Ljava/lang/Object;
        .field public value:I
        .method public constructor <init>(I)V
          .registers 2
          invoke-direct {p0}, Ljava/lang/Object;-><init>()V
          iput p1, p0, Lapp/Root;->value:I
          return-void
        .end method
        """;
    String mid =
        """
        .class public Lapp/Mid;
        .super Lapp/Root;
        .method public constructor <init>(I)V
          .registers 3
          add-int/lit8 v0, p1, 0x64
          invoke-direct {p0, v0}, Lapp/Root;-><init>(I)V
          return-void
        .end method
        """;
    String leaf =
        """
        .class public Lapp/Leaf;
        .super Lapp/Mid;
        .method public constructor <init>(I)V
          .registers 3
          add-int/lit16 v0, p1, 0x3e8
          invoke-direct {p0, v0}, Lapp/Mid;-><init>(I)V
          return-void
        .end method
        """;
    String arrayCaller =
        """
        .class public Lapp/Caller;
        .super Ljava/lang/Object;
        .method public static run(I)[I
          .registers 6
          new-instance v0, Lapp/Leaf;
          invoke-direct {v0, p0}, Lapp/Root;-><init>(I)V
          new-instance v1, Lapp/Leaf;
          invoke-direct {v1, p0}, Lapp/Mid;-><init>(I)V
          new-instance v2, Lapp/Leaf;
          invoke-direct {v2, p0}, Lapp/Leaf;-><init>(I)V
          const/4 v3, 0x3
          new-array v3, v3, [I
          const/4 v4, 0x0
          iget v0, v0, Lapp/Root;->value:I
          aput v0, v3, v4
          const/4 v4, 0x1
          iget v1, v1, Lapp/Root;->value:I
          aput v1, v3, v4
          const/4 v4, 0x2
          iget v2, v2, Lapp/Root;->value:I
          aput v2, v3, v4
          return-object v3
        .end method
        """;
    Path outApk = apk("inlined-existing.apk", root, mid, leaf, arrayCaller);
    Path outJar = temp.getRoot().toPath().resolve("inlined-existing.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, platform, null);
    assertEquals(0, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {outJar.toUri().toURL()}, null)) {
      int[] actual = (int[]) loader.loadClass("app.Caller").getMethod("run", int.class).invoke(null, 10);
      org.junit.Assert.assertArrayEquals(new int[] {10, 110, 1110}, actual);
    }
  }
}
