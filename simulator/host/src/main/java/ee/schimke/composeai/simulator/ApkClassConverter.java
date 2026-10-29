package ee.schimke.composeai.simulator;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.dex.BaseDexExceptionHandler;
import com.googlecode.d2j.dex.Dex2jar;
import com.googlecode.d2j.node.DexMethodNode;
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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Turns an APK's `classes*.dex` into one jar Robolectric's simulator can load as an extra
 * classpath entry, in two passes:
 *
 * <ol>
 *   <li>dex2jar translates every dex in the APK, keeping line numbers. A method it cannot
 *       translate becomes a stub that throws, and is counted rather than failing the whole APK.
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
 *       </ul>
 * </ol>
 *
 * <p>Runs in its own JVM, with `lib-dex2jar/` on the classpath, and prints one JSON summary line on
 * stdout: `{"classes":n,"interfaceCallsFixed":n,"failedMethods":n,"unframedClasses":n,
 * "failures":[...]}`.
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
    public final List<String> failures = new ArrayList<>();

    String toJson() {
      StringBuilder json = new StringBuilder();
      json.append("{\"classes\":").append(classes);
      json.append(",\"interfaceCallsFixed\":").append(interfaceCallsFixed);
      json.append(",\"failedMethods\":").append(failedMethods);
      json.append(",\"unframedClasses\":").append(unframedClasses);
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
      Dex2jar.from(apk.toFile())
          .withExceptionHandler(
              new BaseDexExceptionHandler() {
                @Override
                public void handleMethodTranslateException(
                    Method method, DexMethodNode node, MethodVisitor mv, Exception e) {
                  summary.failedMethods++;
                  if (summary.failures.size() < REPORTED_FAILURES) {
                    summary.failures.add(
                        method.getOwner() + "." + method.getName() + method.getDesc() + ": " + e);
                  }
                  super.handleMethodTranslateException(method, node, mv, e);
                }
              })
          .reUseReg(false)
          .topoLogicalSort()
          // Line numbers and local names, so an app crash in the simulator names its source line.
          .skipDebug(false)
          .to(translated);
      fixInterfaceCalls(translated, out, platformJar, summary);
    } finally {
      Files.deleteIfExists(translated);
    }
    return summary;
  }

  private static void fixInterfaceCalls(Path in, Path out, Path platformJar, Summary summary)
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
          byte[] bytes;
          try (InputStream stream = jar.getInputStream(entry)) {
            bytes = stream.readAllBytes();
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
          public MethodVisitor visitMethod(
              int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
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
