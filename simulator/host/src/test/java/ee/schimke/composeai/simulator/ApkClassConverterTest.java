package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            + "\"withoutDebugInfo\":0,\"failures\":[\"a \\\"quoted\\\"\\nline\\\\\"]}",
        summary.toJson());
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
}
