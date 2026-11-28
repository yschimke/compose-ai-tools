package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * {@link LibraryRelocation} on small libraries shaped like R8's rewrites of Guava: the runtime (the
 * simulator's copy) and the app each have a `com/google/common/...`, and the app's code calls what only its own
 * copy has. Relocated output is then loaded the way the sandbox loads it — the runtime's classes
 * first — and run.
 */
public class LibraryRelocationTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void aCompatibleCopyIsLeftToTheRuntime() throws Exception {
    Path runtime = jar("runtime", Map.of("com/google/common/Util", util(7, false)));
    Path app =
        jar("app", Map.of("com/google/common/Util", util(7, false), "app/Main", main(callStatic("com/google/common/Util", "f"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(1, result.collisions);
    assertTrue(result.packages.isEmpty());
    assertFalse(Files.exists(out()));
  }

  @Test
  public void aMemberOnlyTheAppsCopyHasRelocatesItsPackage() throws Exception {
    // As `Joiner.<init>(String, int)` or `ImmutableSet.$r8$clinit`: the app's copy has `g`.
    Path runtime = jar("runtime", Map.of("com/google/common/Util", util(7, false)));
    Path app =
        jar("app", Map.of("com/google/common/Util", util(42, true), "app/Main", main(callStatic("com/google/common/Util", "g"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(Set.of("com/google/common"), result.packages);
    assertEquals(1, result.relocatedClasses);
    Map<String, byte[]> classes = classes(out());
    assertTrue(classes.containsKey("composeai/relocated/com/google/common/Util"));
    assertFalse(classes.containsKey("com/google/common/Util"));
    assertEquals("composeai/relocated/com/google/common/Util", calls(classes.get("app/Main")).get(0).owner);
    assertEquals(42, run(runtime, out(), "app.Main"));
  }

  @Test
  public void anInterfaceTheRuntimeKeepsPackagePrivateRelocatesItsPackage() throws Exception {
    // As okhttp3.ConnectionPool implementing R8's merged Splitter$Strategy.
    Path runtime = jar("runtime", Map.of("com/google/common/Strategy", iface("com/google/common/Strategy", 0)));
    Path app =
        jar(
            "app",
            Map.of(
                "com/google/common/Strategy", iface("com/google/common/Strategy", Opcodes.ACC_PUBLIC),
                "app/Impl", impl("app/Impl", "com/google/common/Strategy"),
                "app/Main", main(newAndReturn("app/Impl", 5))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(Set.of("com/google/common"), result.packages);
    assertEquals(5, run(runtime, out(), "app.Main"));
  }

  @Test
  public void aRuntimeMethodTakingARelocatedTypeRelocatesItsPackageToo() throws Exception {
    // `com/google/common/a/Maker.make()` returns a `com/google/common/b/Thing`: once the app's `com/google/common/b` moves, the app must call
    // its own `Maker`, or it would get the runtime's `Thing` where it expects its own.
    Path runtime =
        jar("runtime", Map.of("com/google/common/b/Thing", thing(false), "com/google/common/a/Maker", maker()));
    Path app =
        jar(
            "app",
            Map.of(
                "com/google/common/b/Thing", thing(true),
                "com/google/common/a/Maker", maker(),
                "app/Main", main(makeAndCallExtra())));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(Set.of("com/google/common/a", "com/google/common/b"), result.packages);
    assertEquals(9, run(runtime, out(), "app.Main"));
  }

  @Test
  public void aTypeTheSimulatorsOwnCodeExchangesKeepsItsName() throws Exception {
    // The simulator's code calls `value()` on an app's `com/google/common/Fut` (as it handles Guava's
    // ListenableFuture for the AppSearch stand-in): the package moves, `Fut` does not.
    Path runtime =
        jar("runtime", Map.of("com/google/common/Util", util(7, false), "com/google/common/Fut", iface("com/google/common/Fut", Opcodes.ACC_PUBLIC)));
    Path boundary =
        jar(
            "boundary",
            Map.of(
                "ee/schimke/composeai/Bridge",
                bridge(
                    "(Ljava/lang/Object;)I",
                    mv -> {
                      mv.visitVarInsn(Opcodes.ALOAD, 0);
                      mv.visitTypeInsn(Opcodes.CHECKCAST, "com/google/common/Fut");
                      mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/google/common/Fut", "value", "()I", true);
                      mv.visitInsn(Opcodes.IRETURN);
                    })));
    Path app =
        jar(
            "app",
            Map.of(
                "com/google/common/Util", util(42, true),
                "com/google/common/Fut", iface("com/google/common/Fut", Opcodes.ACC_PUBLIC),
                "app/Main", main(callStatic("com/google/common/Util", "g"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of(boundary));

    assertEquals(Set.of("com/google/common"), result.packages);
    Map<String, byte[]> classes = classes(out());
    assertTrue(classes.containsKey("com/google/common/Fut"));
    assertTrue(classes.containsKey("composeai/relocated/com/google/common/Util"));
    assertEquals(42, run(runtime, out(), "app.Main"));
  }

  @Test
  public void aPackageThatWouldSplitFromASharedTypeIsLeftAlone() throws Exception {
    // The simulator's code takes a `Thing` from the app (a cast), so `Thing` is shared and stays the
    // runtime's: the app's call to its own `Thing.extra` cannot be made to work, and is reported.
    Path runtime =
        jar("runtime", Map.of("com/google/common/b/Thing", thing(false), "com/google/common/a/Maker", maker()));
    Path boundary =
        jar(
            "boundary",
            Map.of(
                "ee/schimke/composeai/Bridge",
                bridge(
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    mv -> {
                      mv.visitVarInsn(Opcodes.ALOAD, 0);
                      mv.visitTypeInsn(Opcodes.CHECKCAST, "com/google/common/b/Thing");
                      mv.visitInsn(Opcodes.ARETURN);
                    })));
    Path app =
        jar(
            "app",
            Map.of(
                "com/google/common/b/Thing", thing(true),
                "com/google/common/a/Maker", maker(),
                "app/Main", main(makeAndCallExtra())));

    LibraryRelocation.Result result = relocate(app, runtime, List.of(boundary));

    assertTrue(result.packages.isEmpty());
    assertEquals(1, result.issueCount);
    assertTrue(result.issues.get(0), result.issues.get(0).contains("com.google.common.b.Thing.extra"));
  }

  @Test
  public void aTypeTheSimulatorOnlyPassesToTheRuntimeIsNotShared() throws Exception {
    // As `SimulatorSandbox` handing Robolectric an `ImmutableList`: its own signatures and calls to
    // runtime-only classes involve no app object, so the app's Guava still moves.
    Path runtime = jar("runtime", Map.of("com/google/common/Util", util(7, false)));
    Path boundary =
        jar(
            "boundary",
            Map.of(
                "ee/schimke/composeai/Bridge",
                bridge(
                    "(Lcom/google/common/Util;)Lcom/google/common/Util;",
                    mv -> {
                      mv.visitVarInsn(Opcodes.ALOAD, 0);
                      mv.visitInsn(Opcodes.ARETURN);
                    })));
    Path app =
        jar("app", Map.of("com/google/common/Util", util(42, true), "app/Main", main(callStatic("com/google/common/Util", "g"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of(boundary));

    assertEquals(Set.of("com/google/common"), result.packages);
    assertTrue(classes(out()).containsKey("composeai/relocated/com/google/common/Util"));
  }

  @Test
  public void namesInStringsAndServiceFilesFollowTheirClasses() throws Exception {
    Path runtime = jar("runtime", Map.of("com/google/common/Util", util(7, false)));
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("com/google/common/Util", util(42, true));
    entries.put("app/Main", main(callStatic("com/google/common/Util", "g"), "com.google.common.Util"));
    Path app = jar("app", entries);
    addEntry(app, "META-INF/services/com.google.common.Util", "com.google.common.Util\n");
    addEntry(app, "com/google/common/data.txt", "x");

    relocate(app, runtime, List.of());

    try (ZipFile zip = new ZipFile(out().toFile())) {
      assertNotNull(zip.getEntry("META-INF/services/composeai.relocated.com.google.common.Util"));
      assertNull(zip.getEntry("META-INF/services/com.google.common.Util"));
      try (InputStream in =
          zip.getInputStream(zip.getEntry("META-INF/services/composeai.relocated.com.google.common.Util"))) {
        assertEquals("composeai.relocated.com.google.common.Util\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
      assertNotNull(zip.getEntry("com/google/common/data.txt"));
      assertNotNull(zip.getEntry("composeai/relocated/com/google/common/data.txt"));
    }
    assertEquals("composeai.relocated.com.google.common.Util", ldcStrings(classes(out()).get("app/Main")).get(0));
  }

  @Test
  public void aTestApkFollowsItsAppsRenamesEvenForClassesItLacks() throws Exception {
    // The app's Guava moved; the test APK has no Guava of its own, but calls the app's.
    Path runtime = jar("runtime", Map.of("com/google/common/Util", util(7, false)));
    Path app =
        jar(
            "app",
            Map.of(
                "com/google/common/Util", util(42, true),
                "app/Main", main(callStatic("com/google/common/Util", "g"))));
    relocate(app, runtime, List.of());
    Path appRelocated = temp.getRoot().toPath().resolve("app-relocated.jar");
    Files.move(out(), appRelocated);
    Map<String, byte[]> testClasses = new HashMap<>();
    byte[] testMain = main(callStatic("com/google/common/Util", "g"));
    testClasses.put("app/Main", testMain);
    Path test = jar("test", testClasses);

    LibraryRelocation.Result result =
        LibraryRelocation.relocate(test, out(), null, List.of(), List.of(), appRelocated);

    assertEquals(Set.of("com/google/common"), result.packages);
    assertEquals(
        "composeai/relocated/com/google/common/Util",
        calls(classes(out()).get("app/Main")).get(0).owner);
  }

  @Test
  public void aLibraryTheSandboxServesFromTheAppIsNeverRelocated() throws Exception {
    // Only Guava is runtime-first: the app already gets its own okio, whatever the runtime has.
    Path runtime = jar("runtime", Map.of("okio/Util", util("okio/Util", 7, false)));
    Path app =
        jar(
            "app",
            Map.of(
                "okio/Util", util("okio/Util", 42, true),
                "app/Main", main(callStatic("okio/Util", "g"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(0, result.collisions);
    assertTrue(result.packages.isEmpty());
  }

  @Test
  public void theJdkAndPlatformAreNeverRelocated() throws Exception {
    Path runtime = jar("runtime", Map.of("android/Thing", util("android/Thing", 1, false)));
    Path app =
        jar(
            "app",
            Map.of(
                "android/Thing", util("android/Thing", 2, true),
                "app/Main", main(callStatic("android/Thing", "g"))));

    LibraryRelocation.Result result = relocate(app, runtime, List.of());

    assertEquals(0, result.collisions);
    assertTrue(result.packages.isEmpty());
  }

  // -----------------------------------------------------------------------------------------------

  private Path out() {
    return temp.getRoot().toPath().resolve("out.jar");
  }

  private LibraryRelocation.Result relocate(Path app, Path runtime, List<Path> boundary)
      throws IOException {
    return LibraryRelocation.relocate(app, out(), null, List.of(runtime), boundary, null);
  }

  private interface Body {
    void emit(MethodVisitor mv);
  }

  /** `public class [name]` with `static int f()` returning [value], and `g()` if [withG]. */
  private static byte[] util(int value, boolean withG) {
    return util("com/google/common/Util", value, withG);
  }

  private static byte[] util(String name, int value, boolean withG) {
    ClassWriter cw = classWriter(name, "java/lang/Object", null, Opcodes.ACC_PUBLIC);
    staticInt(cw, "f", value);
    if (withG) staticInt(cw, "g", value);
    return cw.toByteArray();
  }

  /** An interface with `int value()`, accessible as [access] says. */
  private static byte[] iface(String name, int access) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(
        Opcodes.V1_8,
        access | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
        name,
        null,
        "java/lang/Object",
        null);
    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()I", null, null).visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  /** `public class [name] implements [iface]`, whose `value()` returns 5. */
  private static byte[] impl(String name, String iface) {
    ClassWriter cw = classWriter(name, "java/lang/Object", new String[] {iface}, Opcodes.ACC_PUBLIC);
    MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
    mv.visitCode();
    mv.visitInsn(Opcodes.ICONST_5);
    mv.visitInsn(Opcodes.IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    return cw.toByteArray();
  }

  /** `com/google/common/b/Thing` with a constructor and, if [withExtra], `int extra()` returning 9. */
  private static byte[] thing(boolean withExtra) {
    ClassWriter cw = classWriter("com/google/common/b/Thing", "java/lang/Object", null, Opcodes.ACC_PUBLIC);
    if (withExtra) {
      MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "extra", "()I", null, null);
      mv.visitCode();
      mv.visitIntInsn(Opcodes.BIPUSH, 9);
      mv.visitInsn(Opcodes.IRETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }
    return cw.toByteArray();
  }

  /** `com/google/common/a/Maker` with `static Thing make()`. */
  private static byte[] maker() {
    ClassWriter cw = classWriter("com/google/common/a/Maker", "java/lang/Object", null, Opcodes.ACC_PUBLIC);
    MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Lcom/google/common/b/Thing;", null, null);
    mv.visitCode();
    mv.visitTypeInsn(Opcodes.NEW, "com/google/common/b/Thing");
    mv.visitInsn(Opcodes.DUP);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/common/b/Thing", "<init>", "()V", false);
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    return cw.toByteArray();
  }

  /** The simulator's own code: `static [desc] use(...)` whose code is [body]. */
  private static byte[] bridge(String desc, Body body) {
    ClassWriter cw =
        classWriter("ee/schimke/composeai/Bridge", "java/lang/Object", null, Opcodes.ACC_PUBLIC);
    MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", desc, null, null);
    mv.visitCode();
    body.emit(mv);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    return cw.toByteArray();
  }

  private static Body callStatic(String owner, String name) {
    return mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "()I", false);
  }

  private static Body newAndReturn(String type, int unused) {
    return mv -> {
      mv.visitTypeInsn(Opcodes.NEW, type);
      mv.visitInsn(Opcodes.DUP);
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
      mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/google/common/Strategy", "value", "()I", true);
    };
  }

  private static Body makeAndCallExtra() {
    return mv -> {
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/a/Maker", "make", "()Lcom/google/common/b/Thing;", false);
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/b/Thing", "extra", "()I", false);
    };
  }

  /** `app/Main` whose `static int run()` is [body] then IRETURN, after LDCs of [strings]. */
  private static byte[] main(Body body, String... strings) {
    ClassWriter cw = classWriter("app/Main", "java/lang/Object", null, Opcodes.ACC_PUBLIC);
    MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
    mv.visitCode();
    for (String s : strings) {
      mv.visitLdcInsn(s);
      mv.visitInsn(Opcodes.POP);
    }
    body.emit(mv);
    mv.visitInsn(Opcodes.IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    return cw.toByteArray();
  }

  private static ClassWriter classWriter(String name, String superName, String[] ifaces, int access) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(Opcodes.V1_8, access, name, null, superName, ifaces);
    MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    init.visitCode();
    init.visitVarInsn(Opcodes.ALOAD, 0);
    init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
    init.visitInsn(Opcodes.RETURN);
    init.visitMaxs(0, 0);
    init.visitEnd();
    return cw;
  }

  private static void staticInt(ClassWriter cw, String name, int value) {
    MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()I", null, null);
    mv.visitCode();
    mv.visitIntInsn(Opcodes.BIPUSH, value);
    mv.visitInsn(Opcodes.IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private Path jar(String name, Map<String, byte[]> classes) throws IOException {
    Path path = temp.getRoot().toPath().resolve(name + ".jar");
    try (OutputStream file = Files.newOutputStream(path);
        ZipOutputStream zip = new ZipOutputStream(file)) {
      for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return path;
  }

  /** Rewrites [jar] with one more entry. */
  private static void addEntry(Path jar, String name, String text) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
        try (InputStream in = zip.getInputStream(entry)) {
          entries.put(entry.getName(), in.readAllBytes());
        }
      }
    }
    entries.put(name, text.getBytes(StandardCharsets.UTF_8));
    try (OutputStream file = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(file)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
  }

  private static Map<String, byte[]> classes(Path jar) throws IOException {
    Map<String, byte[]> out = new HashMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
        if (!entry.getName().endsWith(".class")) continue;
        try (InputStream in = zip.getInputStream(entry)) {
          String name = entry.getName();
          out.put(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
        }
      }
    }
    return out;
  }

  private static ClassNode node(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  private static List<MethodInsnNode> calls(byte[] bytes) {
    java.util.ArrayList<MethodInsnNode> out = new java.util.ArrayList<>();
    for (var method : node(bytes).methods) {
      if (!method.name.equals("run")) continue;
      for (var insn : method.instructions) {
        if (insn instanceof MethodInsnNode) out.add((MethodInsnNode) insn);
      }
    }
    return out;
  }

  private static List<String> ldcStrings(byte[] bytes) {
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for (var method : node(bytes).methods) {
      for (var insn : method.instructions) {
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
          out.add((String) ((LdcInsnNode) insn).cst);
        }
      }
    }
    return out;
  }

  /**
   * Runs [main]'s `run()` in a loader that, as the sandbox does, takes a class from [runtime]
   * before [app]. Each class is verified as it is defined.
   */
  private static Object run(Path runtime, Path app, String main) throws Exception {
    Map<String, byte[]> first = classes(runtime);
    Map<String, byte[]> then = classes(app);
    ClassLoader loader =
        new ClassLoader(LibraryRelocationTest.class.getClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] bytes = first.containsKey(internal) ? first.get(internal) : then.get(internal);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
          }
        };
    return loader.loadClass(main).getMethod("run").invoke(null);
  }
}
