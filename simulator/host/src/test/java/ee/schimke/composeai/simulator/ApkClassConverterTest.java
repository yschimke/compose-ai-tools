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
import java.util.ArrayList;
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
  public void superCallsToAnIndirectSuperinterfaceDefaultArePointedAtADirectSupertype()
      throws Exception {
    // R8's member rebinding writes `invoke-super` against the interface declaring a default
    // method, even when the calling class only reaches it through its superclass (an
    // `AbstractMap` subclass's `super.getOrDefault` became `Map.getOrDefault`) or through another
    // interface. ART runs that; HotSpot's verifier rejects it ("interface method reference is in
    // an indirect superinterface").
    byte[] api = defaultIface("test/Api", null);
    byte[] base = plainClass("test/Base", "java/lang/Object", new String[] {"test/Api"});
    byte[] viaClass = superCaller("test/ViaClass", "test/Base", null);
    byte[] sub = defaultIface("test/SubApi", "test/Api");
    byte[] viaIface = superCaller("test/ViaIface", "java/lang/Object", new String[] {"test/SubApi"});
    byte[] direct = superCaller("test/Direct", "java/lang/Object", new String[] {"test/Api"});
    ApkClassConverter.ClassHierarchy hierarchy =
        hierarchy(api, base, viaClass, sub, viaIface, direct);

    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    byte[] fixedViaClass = ApkClassConverter.rewrite(viaClass, hierarchy, summary);
    byte[] fixedViaIface = ApkClassConverter.rewrite(viaIface, hierarchy, summary);
    byte[] fixedDirect = ApkClassConverter.rewrite(direct, hierarchy, summary);

    MethodInsnNode toClass = calls(fixedViaClass, "value").get(0);
    assertEquals("test/Base", toClass.owner);
    assertFalse(toClass.itf);
    MethodInsnNode toIface = calls(fixedViaIface, "value").get(0);
    assertEquals("test/SubApi", toIface.owner);
    assertTrue(toIface.itf);
    assertEquals("test/Api", calls(fixedDirect, "value").get(0).owner);
    assertEquals(2, summary.interfaceCallsFixed);

    Map<String, byte[]> classes =
        Map.of(
            "test/Api", api,
            "test/Base", base,
            "test/SubApi", sub,
            "test/ViaClass", fixedViaClass,
            "test/ViaIface", fixedViaIface,
            "test/Direct", fixedDirect);
    assertEquals(43, invoke(classes, "test.ViaClass"));
    assertEquals(43, invoke(classes, "test.ViaIface"));
    assertEquals(43, invoke(classes, "test.Direct"));
  }

  @Test
  public void aSuperCallIsNotRetargetedToASupertypeThatOverridesTheDefault() throws Exception {
    // `Other` overrides `Api.value()`. `User extends Base implements Other` calling the rebound
    // `Api.value` must reach the default `Base` inherits (42), not `Other`'s override (99), even
    // though `Other` is a direct superinterface that extends `Api`.
    byte[] api = defaultIface("test/Api", null);
    byte[] base = plainClass("test/Base", "java/lang/Object", new String[] {"test/Api"});
    byte[] other = defaultIface("test/Other", "test/Api", 99);
    byte[] user = superCaller("test/User", "test/Base", new String[] {"test/Other"});
    // With no superclass path to `Api`'s default, nothing can stand in: the call is left alone.
    byte[] stuck = superCaller("test/Stuck", "java/lang/Object", new String[] {"test/Other"});
    ApkClassConverter.ClassHierarchy hierarchy = hierarchy(api, base, other, user, stuck);

    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    byte[] fixedUser = ApkClassConverter.rewrite(user, hierarchy, summary);
    byte[] fixedStuck = ApkClassConverter.rewrite(stuck, hierarchy, summary);

    assertEquals("test/Base", calls(fixedUser, "value").get(0).owner);
    assertEquals("test/Api", calls(fixedStuck, "value").get(0).owner);
    assertEquals(1, summary.interfaceCallsFixed);
    Map<String, byte[]> classes =
        Map.of("test/Api", api, "test/Base", base, "test/Other", other, "test/User", fixedUser);
    assertEquals(43, invoke(classes, "test.User"));
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
  public void softwareCanvasDrawRenderNodeGoesThroughTheSimulator() throws Exception {
    // DeclarativeWatchFaceRuntime draws its cached layers with canvas.drawRenderNode(node), also
    // onto renderWatchFaceToBitmap's software canvas, where the framework throws.
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Layer", null, "java/lang/Object", null);
    MethodVisitor mv =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "draw",
            "(Landroid/graphics/Canvas;Landroid/graphics/RecordingCanvas;"
                + "Landroid/graphics/RenderNode;)V",
            null,
            null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ALOAD, 2);
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "android/graphics/Canvas",
        "drawRenderNode",
        "(Landroid/graphics/RenderNode;)V",
        false);
    mv.visitVarInsn(Opcodes.ALOAD, 1);
    mv.visitVarInsn(Opcodes.ALOAD, 2);
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "android/graphics/RecordingCanvas",
        "drawRenderNode",
        "(Landroid/graphics/RenderNode;)V",
        false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(2, 3);
    mv.visitEnd();
    writer.visitEnd();
    byte[] layer = writer.toByteArray();

    ClassNode node = new ClassNode();
    new ClassReader(
            ApkClassConverter.rewrite(layer, hierarchy(layer), new ApkClassConverter.Summary()))
        .accept(node, 0);
    List<String> calls = new ArrayList<>();
    for (org.objectweb.asm.tree.AbstractInsnNode insn : method(node, "draw").instructions) {
      if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
        calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
      }
    }
    assertEquals(
        List.of(
            Opcodes.INVOKESTATIC
                + " ee/schimke/composeai/simulator/SoftwareRenderNodes.draw"
                + "(Landroid/graphics/Canvas;Landroid/graphics/RenderNode;)V",
            // Always hardware: left alone.
            Opcodes.INVOKEVIRTUAL
                + " android/graphics/RecordingCanvas.drawRenderNode"
                + "(Landroid/graphics/RenderNode;)V"),
        calls);
  }

  @Test
  public void zonedDateTimeNowFollowsTheSimulatedClock() throws Exception {
    // The runtime's live draw reads the date and time from ZonedDateTime.now(); the converter points
    // it (and only the no-argument form) at the simulated clock.
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Clock", null, "java/lang/Object", null);
    MethodVisitor mv =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "read",
            "(Ljava/time/ZoneId;)V",
            null,
            null);
    mv.visitCode();
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/time/ZonedDateTime",
        "now",
        "()Ljava/time/ZonedDateTime;",
        false);
    mv.visitInsn(Opcodes.POP);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/time/ZonedDateTime",
        "now",
        "(Ljava/time/ZoneId;)Ljava/time/ZonedDateTime;",
        false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    writer.visitEnd();
    byte[] clock = writer.toByteArray();

    ClassNode node = new ClassNode();
    new ClassReader(
            ApkClassConverter.rewrite(clock, hierarchy(clock), new ApkClassConverter.Summary()))
        .accept(node, 0);
    List<String> calls = new ArrayList<>();
    for (org.objectweb.asm.tree.AbstractInsnNode insn : method(node, "read").instructions) {
      if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
        calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
      }
    }
    assertEquals(
        List.of(
            Opcodes.INVOKESTATIC
                + " ee/schimke/composeai/simulator/SimulatorClock.zonedNow()"
                + "Ljava/time/ZonedDateTime;",
            Opcodes.INVOKESTATIC
                + " java/time/ZonedDateTime.now(Ljava/time/ZoneId;)Ljava/time/ZonedDateTime;"),
        calls);
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

  /** An interface with a default `int value()` returning 42, extending [parent] if given. */
  private static byte[] defaultIface(String name, String parent) {
    return defaultIface(name, parent, parent == null ? 42 : null);
  }

  /** An interface extending [parent] if given, declaring a default `value()` if [value] is set. */
  private static byte[] defaultIface(String name, String parent, Integer value) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
        name,
        null,
        "java/lang/Object",
        parent != null ? new String[] {parent} : null);
    if (value != null) {
      MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
      mv.visitCode();
      mv.visitIntInsn(Opcodes.BIPUSH, value);
      mv.visitInsn(Opcodes.IRETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** A public class with a no-argument constructor. */
  private static byte[] plainClass(String name, String superName, String[] interfaces) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
    MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    init.visitCode();
    init.visitVarInsn(Opcodes.ALOAD, 0);
    init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
    init.visitInsn(Opcodes.RETURN);
    init.visitMaxs(0, 0);
    init.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * As dex2jar writes R8's rebound super call: `value()` is `super.value() + 1` against `test/Api`,
   * and `static int run()` returns `new [name]().value()`.
   */
  private static byte[] superCaller(String name, String superName, String[] interfaces) {
    ClassNode node = new ClassNode();
    new ClassReader(plainClass(name, superName, interfaces)).accept(node, 0);
    ClassWriter writer = new ClassWriter(0);
    node.accept(
        new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
          @Override
          public void visitEnd() {
            MethodVisitor value = visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
            value.visitCode();
            value.visitVarInsn(Opcodes.ALOAD, 0);
            value.visitMethodInsn(Opcodes.INVOKESPECIAL, "test/Api", "value", "()I", true);
            value.visitInsn(Opcodes.ICONST_1);
            value.visitInsn(Opcodes.IADD);
            value.visitInsn(Opcodes.IRETURN);
            value.visitMaxs(2, 1);
            value.visitEnd();
            MethodVisitor run =
                visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
            run.visitCode();
            run.visitTypeInsn(Opcodes.NEW, name);
            run.visitInsn(Opcodes.DUP);
            run.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "value", "()I", false);
            run.visitInsn(Opcodes.IRETURN);
            run.visitMaxs(2, 0);
            run.visitEnd();
            super.visitEnd();
          }
        });
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
  public void anInnerEnumMarkedFinalAndAbstractLoads() throws Exception {
    // MarkerBlock's ClosingAction in org.jetbrains:markdown, as R8 and dex2jar leave it.
    org.objectweb.asm.ClassWriter outer = new org.objectweb.asm.ClassWriter(0);
    outer.visit(
        org.objectweb.asm.Opcodes.V1_8,
        org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
            | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
        "p/MarkerBlock",
        null,
        "java/lang/Object",
        null);
    outer.visitInnerClass("p/MarkerBlock$ClosingAction", "p/MarkerBlock", "ClosingAction", 0x4419);
    outer.visitEnd();
    byte[] bytes = outer.toByteArray();

    byte[] fixed = ApkClassConverter.rewrite(bytes, hierarchy(bytes), new ApkClassConverter.Summary());

    int[] flags = {-1};
    new org.objectweb.asm.ClassReader(fixed)
        .accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
              @Override
              public void visitInnerClass(String name, String outerName, String inner, int access) {
                flags[0] = access;
              }
            },
            0);
    assertEquals(0x4409, flags[0]);
    assertEquals(0x0401, ApkClassConverter.legalClassAccess(0x0411));
    assertEquals(0x0011, ApkClassConverter.legalClassAccess(0x0011));
    // Package-private and private classes become public; final ones stay final.
    assertEquals(0x0031, ApkClassConverter.legalClassAccess(0x0030));
    assertEquals(0x0019, ApkClassConverter.legalClassAccess(0x001a));
  }

  @Test
  public void onlyTheAndroidxTestClassesTheSimulatorCarriesAreItsOwn() throws Exception {
    Path jar = Files.createTempFile("provided", ".jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      for (String name :
          List.of("androidx/test/core/app/ActivityScenario.class", "androidx/test/R.txt")) {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
      }
    }
    Set<String> provided = ApkClassConverter.ProvidedTestClasses.read(jar.toString());
    assertEquals(Set.of("androidx/test/core/app/ActivityScenario.class"), provided);
    // uiautomator is not among the simulator's: a test APK's copy is the only one there is.
    assertFalse(provided.contains("androidx/test/uiautomator/UiDevice.class"));
    assertEquals(null, ApkClassConverter.ProvidedTestClasses.read(""));
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

  @Test
  public void providedPropertyJarsResolveCommonSuperClass() throws Exception {
    // Simulator-provided androidx.test classes are stripped from the test APK, so ASM's
    // ClassHierarchy must also consult PROVIDED_PROPERTY jars when computing frames for multi-catch
    // blocks like `catch (NoMatchingViewException | AssertionFailedError)`.
    ClassWriter providedEx = new ClassWriter(0);
    providedEx.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC,
        "androidx/test/espresso/FakeNoMatchingViewException",
        null,
        "java/lang/RuntimeException",
        null);
    providedEx.visitEnd();

    Path providedJar = temp.newFile("provided-espresso.jar").toPath();
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(providedJar))) {
      zip.putNextEntry(new ZipEntry("androidx/test/espresso/FakeNoMatchingViewException.class"));
      zip.write(providedEx.toByteArray());
      zip.closeEntry();
    }

    ClassWriter apkErr = new ClassWriter(0);
    apkErr.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC,
        "junit/framework/FakeAssertionFailedError",
        null,
        "java/lang/AssertionError",
        null);
    apkErr.visitEnd();

    Map<String, ApkClassConverter.ClassHierarchy.Header> appClasses =
        Map.of(
            "junit/framework/FakeAssertionFailedError",
            ApkClassConverter.ClassHierarchy.Header.of(new ClassReader(apkErr.toByteArray())));

    String prev = System.getProperty(ApkClassConverter.PROVIDED_PROPERTY);
    System.setProperty(ApkClassConverter.PROVIDED_PROPERTY, providedJar.toString());
    try (ZipFile platform = new ZipFile(emptyJar().toFile());
        ApkClassConverter.ClassHierarchy hierarchy =
            new ApkClassConverter.ClassHierarchy(appClasses, platform)) {
      assertEquals(
          "java/lang/Throwable",
          hierarchy.commonSuperClass(
              "androidx/test/espresso/FakeNoMatchingViewException",
              "junit/framework/FakeAssertionFailedError"));
    } finally {
      if (prev == null) System.clearProperty(ApkClassConverter.PROVIDED_PROPERTY);
      else System.setProperty(ApkClassConverter.PROVIDED_PROPERTY, prev);
    }
  }

  @Test
  public void javaClasspathResourcesAreCopiedAndDexmakerIsExcluded() throws Exception {
    Path platform = emptyJar();
    String simple =
        """
        .class public Lapp/Res;
        .super Ljava/lang/Object;
        .method public static ok()I
          .registers 1
          const/4 v0, 0x1
          return v0
        .end method
        """;
    Path baseApk = apk("res-base.apk", simple);
    Path apkWithResources = temp.newFile("res-full.apk").toPath();
    try (ZipFile in = new ZipFile(baseApk.toFile());
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(apkWithResources))) {
      var entries = in.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        out.putNextEntry(new ZipEntry(e.getName()));
        out.write(in.getInputStream(e).readAllBytes());
        out.closeEntry();
      }
      Map<String, String> extras = new HashMap<>();
      extras.put(
          "org/mockito/internal/creation/bytebuddy/inject-MockMethodDispatcher.raw", "bytes");
      extras.put("META-INF/services/com.example.Plugin", "com.example.PluginImpl\n");
      extras.put(
          "mockito-extensions/org.mockito.plugins.MockMaker",
          "com.android.dx.mockito.inline.MockMakerMultiplexer\n");
      extras.put(
          "mockito-extensions/org.mockito.plugins.InstantiatorProvider2",
          "com.linkedin.dexmaker.mockito.DexmakerInstantiator\n");
      extras.put(
          "mockito-extensions/org.mockito.plugins.StackTraceCleanerProvider",
          "com.example.CustomCleaner\n");
      extras.put("META-INF/services/androidx.test.platform.app.InstrumentationRegistry", "bad\n");
      extras.put("AndroidManifest.xml", "<manifest/>");
      extras.put("resources.arsc", "arsc");
      extras.put("res/layout/main.xml", "<View/>");
      extras.put("assets/hello.txt", "hi");
      extras.put("lib/arm64-v8a/libfoo.so", "elf");
      extras.put("META-INF/CERT.SF", "sig");
      extras.put("META-INF/CERT.RSA", "rsa");
      for (Map.Entry<String, String> e : extras.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }

    Path outJar = temp.getRoot().toPath().resolve("res-full.jar");
    ApkClassConverter.convert(apkWithResources, outJar, platform, null);
    try (ZipFile converted = new ZipFile(outJar.toFile())) {
      assertTrue(
          converted.getEntry(
                  "org/mockito/internal/creation/bytebuddy/inject-MockMethodDispatcher.raw")
              != null);
      assertTrue(converted.getEntry("META-INF/services/com.example.Plugin") != null);
      assertTrue(
          converted.getEntry("mockito-extensions/org.mockito.plugins.StackTraceCleanerProvider")
              != null);
      assertTrue(
          converted.getEntry("mockito-extensions/org.mockito.plugins.MockMaker") == null);
      assertTrue(
          converted.getEntry("mockito-extensions/org.mockito.plugins.InstantiatorProvider2")
              == null);
      assertTrue(
          converted.getEntry("META-INF/services/androidx.test.platform.app.InstrumentationRegistry")
              == null);
      assertTrue(converted.getEntry("AndroidManifest.xml") == null);
      assertTrue(converted.getEntry("resources.arsc") == null);
      assertTrue(converted.getEntry("res/layout/main.xml") == null);
      assertTrue(converted.getEntry("assets/hello.txt") == null);
      assertTrue(converted.getEntry("lib/arm64-v8a/libfoo.so") == null);
      assertTrue(converted.getEntry("META-INF/CERT.SF") == null);
      assertTrue(converted.getEntry("META-INF/CERT.RSA") == null);
    }
  }

  @Test
  public void sparseUnssaAndRegisterAssignmentProduceVerifiableBytecode() throws Exception {
    // Build an IR method with int + long parameters, conditional jump + fallthrough to a merge
    // label with a PhiExpr, plus intra-block temporaries whose slots should be reused.
    IrMethod ir = new IrMethod();
    ir.isStatic = true;
    ir.owner = "Ltest/Sparse;";
    ir.name = "pick";
    ir.args = new String[] {"I", "J"};
    ir.ret = "J";

    Local pFlag = Exprs.nLocal("flag");
    pFlag.valueType = "I";
    Local pBase = Exprs.nLocal("base");
    pBase.valueType = "J";
    Local tmp1 = Exprs.nLocal("tmp1");
    tmp1.valueType = "J";
    Local tmp2 = Exprs.nLocal("tmp2");
    tmp2.valueType = "J";
    Local branchA = Exprs.nLocal("branchA");
    branchA.valueType = "J";
    Local branchB = Exprs.nLocal("branchB");
    branchB.valueType = "J";
    Local merged = Exprs.nLocal("merged");
    merged.valueType = "J";
    ir.locals.addAll(List.of(pFlag, pBase, tmp1, tmp2, branchA, branchB, merged));

    LabelStmt elseLabel = Stmts.nLabel();
    LabelStmt mergeLabel = Stmts.nLabel();
    com.googlecode.dex2jar.ir.expr.PhiExpr phi = Exprs.nPhi(branchA, branchB);
    phi.valueType = "J";
    mergeLabel.phis = new ArrayList<>(List.of(Stmts.nAssign(merged, phi)));
    ir.phiLabels = new ArrayList<>(List.of(mergeLabel));

    ir.stmts.add(Stmts.nIdentity(pFlag, Exprs.nParameterRef("I", 0)));
    ir.stmts.add(Stmts.nIdentity(pBase, Exprs.nParameterRef("J", 1)));
    ir.stmts.add(Stmts.nIf(Exprs.nEq(pFlag, Exprs.nInt(0), "I"), elseLabel));
    // Then branch: uses two sequential intra-block long temporaries, then defines branchA.
    ir.stmts.add(Stmts.nAssign(tmp1, Exprs.nAdd(pBase, Exprs.nLong(10L), "J")));
    ir.stmts.add(Stmts.nAssign(tmp2, Exprs.nAdd(tmp1, Exprs.nLong(5L), "J")));
    ir.stmts.add(Stmts.nAssign(branchA, tmp2));
    ir.stmts.add(Stmts.nGoto(mergeLabel));
    // Else branch: defines branchB and falls through to mergeLabel.
    ir.stmts.add(elseLabel);
    ir.stmts.add(Stmts.nAssign(branchB, Exprs.nAdd(pBase, Exprs.nLong(100L), "J")));
    ir.stmts.add(mergeLabel);
    ir.stmts.add(Stmts.nReturn(merged));

    new com.googlecode.dex2jar.ir.ts.TypeTransformer().transform(ir);
    ApkClassConverter.deconstructPhisSparse(ir);
    ApkClassConverter.assignRegistersSparse(ir);

    assertEquals(0, pFlag.lsIndex);
    assertEquals(1, pBase.lsIndex);
    assertTrue(ir.phiLabels.isEmpty());
    assertEquals(null, mergeLabel.phis);

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Sparse", null, "java/lang/Object", null);
    MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(IJ)J", null, null);
    mv.visitCode();
    new com.googlecode.d2j.converter.IR2JConverter().ir(ir).asm(mv).convert();
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    cw.visitEnd();

    byte[] raw = cw.toByteArray();
    ApkClassConverter.Summary summary = new ApkClassConverter.Summary();
    byte[] fixed = ApkClassConverter.rewrite(raw, hierarchy(raw), summary);
    assertEquals(0, summary.unframedClasses);

    Map<String, byte[]> map = Map.of("test/Sparse", fixed);
    ClassLoader cl =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = map.get(name.replace('.', '/'));
            if (b == null) throw new ClassNotFoundException(name);
            return defineClass(name, b, 0, b.length);
          }
        };
    Method pick = cl.loadClass("test.Sparse").getMethod("pick", int.class, long.class);
    assertEquals(22L, pick.invoke(null, 1, 7L));
    assertEquals(107L, pick.invoke(null, 0, 7L));
  }

  @Test
  public void oversizedMethodStubsOnlyThatMethodAndPreservesSiblings() throws Exception {
    // Dex allows methods whose JVM bytecode exceeds 65,535 bytes; the converter must stub only
    // the un-emittable method and keep the rest of the class intact and verifiable.
    StringBuilder smali = new StringBuilder();
    smali.append(".class public Lapp/Huge;\n");
    smali.append(".super Ljava/lang/Object;\n");
    smali.append(".field public static sink:I\n");
    smali.append(".method public static small()I\n");
    smali.append("  .registers 1\n");
    smali.append("  const/16 v0, 0x2a\n");
    smali.append("  return v0\n");
    smali.append(".end method\n");
    smali.append(".method public static huge(I)I\n");
    smali.append("  .registers 2\n");
    smali.append("  move v0, p0\n");
    // ~12,000 `sput` + `add-int/lit8` pairs (flat IR, ~72 KB of JVM bytecode > 65,535 limit).
    for (int i = 0; i < 12_000; i++) {
      smali.append("  sput v0, Lapp/Huge;->sink:I\n");
      smali.append("  add-int/lit8 v0, v0, 0x1\n");
    }
    smali.append("  return v0\n");
    smali.append(".end method\n");

    Path outApk = apk("huge.apk", smali.toString());
    Path outJar = temp.getRoot().toPath().resolve("huge.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(1, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {outJar.toUri().toURL()}, null)) {
      assertEquals(42, loader.loadClass("app.Huge").getMethod("small").invoke(null));
    }
  }

  @Test
  public void byteBuddyAdviceMethodDoesNotOverwriteParameterSlots() throws Exception {
    // Mirrors Mockito's MockMethodAdvice.enter(@Advice.OnMethodEnter): dex2jar's default register
    // allocator reuses dead parameter slot 0 with `astore_0`, which ByteBuddy rejects with
    // "Cannot write to constant value".
    String advice =
        """
        .class public Lapp/AdviceSample;
        .super Ljava/lang/Object;
        .method public static enter(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
          .registers 4
          .annotation runtime Lnet/bytebuddy/asm/Advice$OnMethodEnter;
          .end annotation
          invoke-static {p0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
          move-result-object v0
          if-eqz v0, :ret_null
          invoke-virtual {v0}, Ljava/lang/String;->trim()Ljava/lang/String;
          move-result-object v1
          return-object v1
          :ret_null
          const/4 v0, 0x0
          return-object v0
        .end method
        """;
    Path outApk = apk("advice.apk", advice);
    Path outJar = temp.getRoot().toPath().resolve("advice.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(0, summary.failedMethods);
    try (ZipFile zip = new ZipFile(outJar.toFile())) {
      byte[] bytes = zip.getInputStream(zip.getEntry("app/AdviceSample.class")).readAllBytes();
      MethodNode enter = method(bytes, "enter");
      for (org.objectweb.asm.tree.AbstractInsnNode insn : enter.instructions) {
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode varInsn) {
          boolean isStore =
              varInsn.getOpcode() >= Opcodes.ISTORE && varInsn.getOpcode() <= Opcodes.ASTORE;
          if (isStore) {
            assertTrue(
                "Advice method must not overwrite parameter slot " + varInsn.var,
                varInsn.var >= 2);
          }
        }
      }
    }
    try (URLClassLoader loader = new URLClassLoader(new URL[] {outJar.toUri().toURL()}, null)) {
      assertEquals(
          "hi",
          loader
              .loadClass("app.AdviceSample")
              .getMethod("enter", String.class, Object.class)
              .invoke(null, " hi ", null));
    }
  }

  @Test
  public void byteBuddyWritableReturnAdvicePreservesStoreToParameterSlot() throws Exception {
    // Mirrors MockK's JvmMockKProxyAdvice.exit and Mockito's MockMethodAdvice.exit:
    // `if (mocked != null) returned = mocked.call();` where `returned` is @Advice.Return(readOnly=false).
    // dex2jar's T_DEAD_CODE / T_VOID_INVOKE would otherwise turn `move-result-object p0` into `pop`.
    String exitAdvice =
        """
        .class public Lapp/ExitAdvice;
        .super Ljava/lang/Object;
        .method public static exit(Ljava/lang/Object;Ljava/util/concurrent/Callable;)V
          .registers 2
          .annotation runtime Lnet/bytebuddy/asm/Advice$OnMethodExit;
          .end annotation
          .param p0
            .annotation runtime Lnet/bytebuddy/asm/Advice$Return;
              readOnly = false
            .end annotation
          .end param
          .param p1
            .annotation runtime Lnet/bytebuddy/asm/Advice$Enter;
            .end annotation
          .end param
          if-eqz p1, :done
          invoke-interface {p1}, Ljava/util/concurrent/Callable;->call()Ljava/lang/Object;
          move-result-object p0
          :done
          return-void
        .end method
        """;
    Path outApk = apk("exit-advice.apk", exitAdvice);
    Path outJar = temp.getRoot().toPath().resolve("exit-advice.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(0, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (ZipFile zip = new ZipFile(outJar.toFile())) {
      byte[] bytes = zip.getInputStream(zip.getEntry("app/ExitAdvice.class")).readAllBytes();
      MethodNode exit = method(bytes, "exit");
      boolean storesSlot0 = false;
      boolean popsReturn = false;
      for (org.objectweb.asm.tree.AbstractInsnNode insn : exit.instructions) {
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode varInsn
            && varInsn.getOpcode() == Opcodes.ASTORE
            && varInsn.var == 0) {
          storesSlot0 = true;
        }
        if (insn.getOpcode() == Opcodes.POP) {
          popsReturn = true;
        }
      }
      assertTrue("Exit advice must write mocked return value into slot 0", storesSlot0);
      assertFalse("Exit advice must not discard Callable.call() with POP", popsReturn);
    }
  }

  @Test
  public void byteBuddyWritableAdvicePreservesReturnedOriginalParam() throws Exception {
    // Writable advice that saves a parameter's original value, overwrites the parameter, and
    // returns the original value. The pre-return store into slot 0 must not clobber the return.
    String advice =
        """
        .class public Lapp/ReturnOrigAdvice;
        .super Ljava/lang/Object;
        .method public static enter(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
          .registers 3
          .annotation runtime Lnet/bytebuddy/asm/Advice$OnMethodEnter;
          .end annotation
          .param p0
            .annotation runtime Lnet/bytebuddy/asm/Advice$Argument;
              value = 0
              readOnly = false
            .end annotation
          .end param
          move-object v0, p0
          move-object p0, p1
          return-object v0
        .end method
        """;
    Path outApk = apk("return-orig-advice.apk", advice);
    Path outJar = temp.getRoot().toPath().resolve("return-orig-advice.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(0, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {outJar.toUri().toURL()}, null)) {
      assertEquals(
          "original",
          loader
              .loadClass("app.ReturnOrigAdvice")
              .getMethod("enter", String.class, String.class)
              .invoke(null, "original", "updated"));
    }
  }

  @Test
  public void byteBuddyWritableAdviceSwappingTwoParamsDoesNotClobber() throws Exception {
    // Two writable advice parameters swapped before return: writing slot 0 must not clobber the
    // source read for slot 1.
    String advice =
        """
        .class public Lapp/SwapAdvice;
        .super Ljava/lang/Object;
        .method public static swap(Ljava/lang/String;Ljava/lang/String;)V
          .registers 3
          .annotation runtime Lnet/bytebuddy/asm/Advice$OnMethodEnter;
          .end annotation
          .param p0
            .annotation runtime Lnet/bytebuddy/asm/Advice$Argument;
              value = 0
              readOnly = false
            .end annotation
          .end param
          .param p1
            .annotation runtime Lnet/bytebuddy/asm/Advice$Argument;
              value = 1
              readOnly = false
            .end annotation
          .end param
          move-object v0, p0
          move-object p0, p1
          move-object p1, v0
          return-void
        .end method
        """;
    Path outApk = apk("swap-advice.apk", advice);
    Path outJar = temp.getRoot().toPath().resolve("swap-advice.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(0, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (ZipFile zip = new ZipFile(outJar.toFile())) {
      byte[] bytes = zip.getInputStream(zip.getEntry("app/SwapAdvice.class")).readAllBytes();
      MethodNode swap = method(bytes, "swap");
      // Track slot values symbolically through ALOAD/ASTORE to verify slot 0 ends as initial p1
      // and slot 1 ends as initial p0.
      Map<Integer, String> slots = new HashMap<>();
      slots.put(0, "p0");
      slots.put(1, "p1");
      String tos = null;
      for (org.objectweb.asm.tree.AbstractInsnNode insn : swap.instructions) {
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode v) {
          if (v.getOpcode() == Opcodes.ALOAD) {
            tos = slots.get(v.var);
          } else if (v.getOpcode() == Opcodes.ASTORE) {
            slots.put(v.var, tos);
            tos = null;
          }
        }
      }
      assertEquals("p1", slots.get(0));
      assertEquals("p0", slots.get(1));
    }
  }

  @Test
  public void sparseUnssaPreservesValueBeforeThrowingAssignmentAcrossCatchEdge() throws Exception {
    // Mirrors `v = input; try { v = callable.call(); } catch (Exception e) {} return v;` on the
    // sparse UnSSA path (triggered here via @Advice.OnMethodEnter). On the catch path, the phi at
    // the return merge must pick `input`, not the unassigned result of the throwing `call()`.
    String advice =
        """
        .class public Lapp/CatchMergeAdvice;
        .super Ljava/lang/Object;
        .method public static run(Ljava/lang/String;Ljava/util/concurrent/Callable;)Ljava/lang/String;
          .registers 4
          .annotation runtime Lnet/bytebuddy/asm/Advice$OnMethodEnter;
          .end annotation
          move-object v0, p0
          :try_start
          invoke-interface {p1}, Ljava/util/concurrent/Callable;->call()Ljava/lang/Object;
          move-result-object v1
          check-cast v1, Ljava/lang/String;
          move-object v0, v1
          :try_end
          .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch_handler
          goto :merge
          :catch_handler
          move-exception v1
          :merge
          return-object v0
        .end method
        """;
    Path outApk = apk("catch-merge-advice.apk", advice);
    Path outJar = temp.getRoot().toPath().resolve("catch-merge-advice.jar");
    ApkClassConverter.Summary summary = ApkClassConverter.convert(outApk, outJar, emptyJar(), null);
    assertEquals(0, summary.failedMethods);
    assertEquals(0, summary.unframedClasses);
    try (URLClassLoader loader = new URLClassLoader(new URL[] {outJar.toUri().toURL()}, null)) {
      java.lang.reflect.Method run =
          loader
              .loadClass("app.CatchMergeAdvice")
              .getMethod("run", String.class, java.util.concurrent.Callable.class);
      java.util.concurrent.Callable<Object> failing =
          () -> {
            throw new IllegalStateException("boom");
          };
      java.util.concurrent.Callable<Object> succeeding = () -> "updated";
      assertEquals("fallback", run.invoke(null, "fallback", failing));
      assertEquals("updated", run.invoke(null, "fallback", succeeding));
    }
  }
}
