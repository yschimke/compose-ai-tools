package ee.schimke.composeai.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Gives an app its own copy of Guava where the simulator's would break it.
 *
 * <p>The sandbox serves the app's own copy of every class it has, except those under {@link
 * #RUNTIME_FIRST}: Guava, which Robolectric's own sandboxed code links against, so the simulator's
 * copy serves both ({@code SimulatorSandboxClassLoader}). A debug APK's Guava is a published one,
 * and taking the simulator's newer copy is harmless. A release APK's has been rewritten by R8 —
 * members added ({@code $r8$clinit}), constructors changed, an interface merged into an unrelated
 * class — and the app's own code calls what only its copy has: {@code NoSuchFieldError}, {@code
 * NoSuchMethodError} and {@code IllegalAccessError} at the first use.
 *
 * <p>This pass reads the converted jar and checks every reference the app makes to a class the
 * runtime also defines against the runtime's copy: that the class and member exist, are reachable
 * with that access, are static or not as called, and are an interface or not as called. A package
 * with a reference that fails is relocated: each of the app's classes in it is renamed under
 * {@value #PREFIX}, and every reference to them follows, so the app runs on its own copy and the
 * simulator keeps its own. The check repeats, since a relocated class may now be passed to or
 * override a runtime method declared with the original type, until nothing more fails.
 *
 * <p>Some types are shared with the simulator's own code and must keep their names: the classes it
 * shadows ({@code @Implements}), receives, returns, casts to or calls instance methods on (Guava's
 * {@code ListenableFuture} from the AppSearch stand-in, say). They are read from the classes under
 * {@value #OWN_CODE} in the boundary jars and never relocated; a package whose relocation would
 * split one of them from the rest is left alone, and reported.
 *
 * <p>Relocation renames class names in code, signatures, annotations, string constants that are
 * exactly a relocated class's name, and {@code META-INF/services} files; resources inside a
 * relocated package are kept at both paths. Kotlin metadata, and names computed at run time, are
 * not rewritten.
 */
public final class LibraryRelocation {

  /** The simulator's runtime class path, in order: where a runtime-first class comes from. */
  public static final String RUNTIME_PROPERTY = "composeai.simulator.relocation.runtime";

  /** Jars whose {@value #OWN_CODE} classes exchange objects with the app. */
  public static final String BOUNDARY_PROPERTY = "composeai.simulator.relocation.boundary";

  /**
   * The app's relocated jar, for a test APK: its renames are applied as they are, without
   * analysis, so the test's references reach the app's classes (and the app's copy of Guava) by the
   * names the app's now have.
   */
  public static final String CONTEXT_PROPERTY = "composeai.simulator.relocation.context";

  /** Where relocated classes go: {@code com/google/common/base/Joiner} becomes this + that. */
  static final String PREFIX = "composeai/relocated/";

  /** The simulator's own classes, which see the app's objects. */
  static final String OWN_CODE = "ee/schimke/composeai/";

  /**
   * The class-resource prefixes the sandbox serves from the simulator's runtime ahead of the APK
   * ({@code SimulatorSandboxClassLoader}): Guava, which Robolectric's own sandboxed code links
   * against. Every other class the APK has, it gets from the APK; only these can collide.
   */
  public static final String[] RUNTIME_FIRST = {"com/google/common/", "com/google/thirdparty/"};

  /** Whether the sandbox takes [internalName] from the runtime when the runtime has it. */
  public static boolean runtimeFirst(String internalName) {
    for (String prefix : RUNTIME_FIRST) {
      if (internalName.startsWith(prefix)) return true;
    }
    return false;
  }

  /**
   * Never relocated: the JDK and the platform (which the sandbox resolves its own way), the test
   * frameworks and shared libraries the simulator provides on purpose, and the classes the
   * sandbox deliberately leaves to its parent class loader.
   */
  static final String[] NEVER = {
    "java/",
    "javax/",
    "jdk/",
    "sun/",
    "com/sun/",
    "android/",
    "dalvik/",
    "libcore/",
    "org/json/",
    "org/xmlpull/",
    "org/apache/http/",
    "org/w3c/",
    "org/xml/",
    "org/robolectric/",
    "org/junit/",
    "junit/",
    "org/hamcrest/",
    "org/bouncycastle/",
    "org/conscrypt/",
    "org/objectweb/asm/",
    "androidx/test/",
    "com/google/android/wearable/",
    "com/google/wear/",
    OWN_CODE,
    PREFIX,
  };

  /** How many issues the summary names. */
  private static final int REPORTED_ISSUES = 20;

  /** A bound on the analysis's rounds; each one only grows the relocated or blocked set. */
  private static final int MAX_ROUNDS = 256;

  private LibraryRelocation() {}

  /**
   * {@code LibraryRelocation <classes.jar> <out.jar> <platform android.jar>}: prints a JSON
   * summary; writes [out.jar] only when something is relocated.
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("usage: LibraryRelocation <classes.jar> <out.jar> <platform android.jar>");
      System.exit(2);
    }
    Result result =
        relocate(
            Paths.get(args[0]),
            Paths.get(args[1]),
            Paths.get(args[2]),
            paths(System.getProperty(RUNTIME_PROPERTY)),
            paths(System.getProperty(BOUNDARY_PROPERTY)),
            Optional.ofNullable(System.getProperty(CONTEXT_PROPERTY))
                .filter(value -> !value.isEmpty())
                .map(Paths::get)
                .orElse(null));
    System.out.println(result.toJson());
  }

  /** What a relocation decided. */
  public static final class Result {
    /** The relocated packages, as internal names ({@code com/google/common/base}). */
    public final Set<String> packages = new TreeSet<>();

    /** Why each relocated package moved: the first reference that failed against the runtime. */
    public final Map<String, String> reasons = new java.util.TreeMap<>();

    /** Packages left to the runtime because relocating them would split a shared type, and why. */
    public final Map<String, String> blocked = new java.util.TreeMap<>();

    /** App classes the simulator's own code exchanges with the app, which keep their names. */
    public int pinned;

    public final Set<String> pinnedClasses = new TreeSet<>();

    /** App classes the runtime also defines. */
    public int collisions;

    /** App classes renamed. */
    public int relocatedClasses;

    /** References the analysis could not make work, by relocating or otherwise. */
    public final List<String> issues = new ArrayList<>();

    public int issueCount;

    void issue(String issue) {
      issueCount++;
      if (issues.size() < REPORTED_ISSUES && !issues.contains(issue)) issues.add(issue);
    }

    public String toJson() {
      StringBuilder json = new StringBuilder("{\"relocated\":[");
      int i = 0;
      for (String pkg : packages) {
        if (i++ > 0) json.append(',');
        json.append('"').append(escape(pkg)).append('"');
      }
      json.append("],\"reasons\":{");
      int k = 0;
      for (Map.Entry<String, String> reason : reasons.entrySet()) {
        if (k++ > 0) json.append(',');
        json.append('"').append(escape(reason.getKey())).append("\":\"");
        json.append(escape(reason.getValue())).append('"');
      }
      json.append("},\"blocked\":{");
      int b = 0;
      for (Map.Entry<String, String> block : blocked.entrySet()) {
        if (b++ > 0) json.append(',');
        json.append('"').append(escape(block.getKey())).append("\":\"");
        json.append(escape(block.getValue())).append('"');
      }
      json.append("},\"pinned\":").append(pinned);
      json.append(",\"pinnedClasses\":[");
      int p = 0;
      for (String cls : pinnedClasses) {
        if (p >= REPORTED_ISSUES) break;
        if (p++ > 0) json.append(',');
        json.append('"').append(escape(cls)).append('"');
      }
      json.append(']');
      json.append(",\"relocatedClasses\":").append(relocatedClasses);
      json.append(",\"collisions\":").append(collisions);
      json.append(",\"issueCount\":").append(issueCount);
      json.append(",\"issues\":[");
      for (int j = 0; j < issues.size(); j++) {
        if (j > 0) json.append(',');
        json.append('"').append(escape(issues.get(j))).append('"');
      }
      return json.append("]}").toString();
    }
  }

  /**
   * Relocates [in]'s packages that the [runtime] jars' copies would break into [out], which is
   * written only when there are any. With a [context] (the app's relocated jar, for a test APK), its
   * renames are applied to [in] as they are, and nothing is analysed.
   */
  public static Result relocate(
      Path in, Path out, Path platform, List<Path> runtime, List<Path> boundary, Path context)
      throws IOException {
    if (context != null) return relocateLike(in, out, context);
    Result result = new Result();
    try (Types host = new Types(runtime, platform);
        ZipFile app = new ZipFile(in.toFile())) {
      Model model = Model.read(app, host);
      result.collisions = model.colliding.size();
      Set<String> relocated;
      Set<String> pinned = Set.of();
      if (model.colliding.isEmpty()) {
        relocated = Set.of();
      } else {
        pinned = crossing(boundary, model.classes.keySet());
        result.pinned = pinned.size();
        result.pinnedClasses.addAll(pinned);
        relocated = new Analysis(model, host, pinned, result).run();
      }
      result.packages.addAll(relocated);
      if (!relocated.isEmpty()) {
        Map<String, String> renames = new HashMap<>();
        for (String name : model.classes.keySet()) {
          if (relocated.contains(pkg(name)) && !pinned.contains(name)) {
            renames.put(name, PREFIX + name);
          }
        }
        result.relocatedClasses = renames.size();
        write(app, out, renames, relocated);
      }
    }
    return result;
  }

  /**
   * [in] (a test APK's classes) with [context]'s renames (its app's relocated jar): every reference
   * to a class the app moved follows it, whether or not [in] has a copy of that class. A class of
   * [in]'s own in a relocated package moves too, unless the app kept that name (a shared type).
   */
  static Result relocateLike(Path in, Path out, Path context) throws IOException {
    Result result = new Result();
    Map<String, String> renames = new HashMap<>();
    Set<String> kept = new HashSet<>();
    try (ZipFile appJar = new ZipFile(context.toFile())) {
      for (ZipEntry entry : Collections.list(appJar.entries())) {
        String name = entry.getName();
        if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
        String className = name.substring(0, name.length() - ".class".length());
        if (className.startsWith(PREFIX)) {
          String original = className.substring(PREFIX.length());
          renames.put(original, className);
          result.packages.add(pkg(original));
        } else {
          kept.add(className);
        }
      }
    }
    if (renames.isEmpty()) return result;
    try (ZipFile test = new ZipFile(in.toFile())) {
      for (ZipEntry entry : Collections.list(test.entries())) {
        String name = entry.getName();
        if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
        String className = name.substring(0, name.length() - ".class".length());
        if (renames.containsKey(className)) {
          result.relocatedClasses++;
        } else if (result.packages.contains(pkg(className)) && !kept.contains(className)) {
          renames.put(className, PREFIX + className);
          result.relocatedClasses++;
        }
      }
      write(test, out, renames, result.packages);
    }
    return result;
  }

  // -----------------------------------------------------------------------------------------------
  // The analysis

  /** Repeats the checks, relocating or blocking packages, until nothing more changes. */
  static final class Analysis {
    private final Model model;
    private final Types host;
    private final Set<String> pinned;
    private final Result result;
    private final Set<String> relocated = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();

    Analysis(Model model, Types host, Set<String> pinned, Result result) {
      this.model = model;
      this.host = host;
      this.pinned = pinned;
      this.result = result;
    }

    Set<String> run() {
      for (int round = 0; round < MAX_ROUNDS; round++) {
        Round outcome = check();
        if (!outcome.block.isEmpty()) {
          // A package that cannot move without breaking a shared type: start over without it, as
          // what was relocated on its account may no longer be needed.
          blocked.addAll(outcome.block);
          for (String pkg : outcome.block) result.blocked.putIfAbsent(pkg, outcome.blockWhy.get(pkg));
          relocated.clear();
          result.reasons.clear();
          continue;
        }
        if (outcome.relocate.isEmpty()) {
          for (String issue : outcome.issues) result.issue(issue);
          return relocated;
        }
        relocated.addAll(outcome.relocate);
        for (String pkg : outcome.relocate) result.reasons.putIfAbsent(pkg, outcome.why.get(pkg));
      }
      result.issue("relocation analysis did not settle in " + MAX_ROUNDS + " rounds");
      return Set.of();
    }

    /** What one pass over every reference found. */
    private static final class Round {
      final Map<String, String> why = new HashMap<>();
      final Map<String, String> blockWhy = new HashMap<>();
      final Set<String> relocate = new HashSet<>();
      final Set<String> block = new HashSet<>();

      void block(String pkg, String reason) {
        block.add(pkg);
        blockWhy.putIfAbsent(pkg, reason);
      }
      final List<String> issues = new ArrayList<>();
    }

    /** The name [name] (an app class) has after this round's relocations. */
    private String renamed(String name) {
      return model.classes.containsKey(name)
              && relocated.contains(pkg(name))
              && !pinned.contains(name)
          ? PREFIX + name
          : name;
    }

    /** Whether the sandbox loads the runtime's copy of [name] rather than the app's. */
    private boolean fromRuntime(String name) {
      return model.colliding.contains(name) && renamed(name).equals(name);
    }

    private boolean relocatable(String pkg) {
      return model.packages.contains(pkg) && !blocked.contains(pkg) && !never(pkg + "/");
    }

    private Round check() {
      Round round = new Round();
      for (AppClass from : model.classes.values()) {
        // A colliding class still taken from the runtime never runs from the app: its references
        // do not matter.
        if (fromRuntime(from.name)) continue;
        String fromPkg = pkg(renamed(from.name));
        for (Ref ref : from.refs) {
          String owner = ref.owner;
          if (fromRuntime(owner)) {
            String problem = runtimeProblem(from, fromPkg, ref);
            String mismatch = problem == null ? relocatedTypeIn(ref) : null;
            if (problem == null && mismatch == null) continue;
            String why =
                problem != null
                    ? problem
                    : "passes the app's own " + mismatch.replace('/', '.') + " for the runtime's";
            if (!pinned.contains(owner) && relocatable(pkg(owner))) {
              round.relocate.add(pkg(owner));
              round.why.putIfAbsent(pkg(owner), describe(from, ref) + ": " + why);
            } else if (mismatch != null) {
              round.block(pkg(mismatch), describe(from, ref) + ": the shared " + owner.replace('/', '.') + " " + why);
            } else if (pkg(from.name).equals(pkg(owner)) && !fromPkg.equals(pkg(owner))) {
              // Relocating the package moved [from] away from a shared class it used from within.
              round.block(pkg(owner), describe(from, ref) + ": " + why + " once moved away from it");
            } else {
              round.issues.add(describe(from, ref) + ": " + why);
            }
          } else if (model.classes.containsKey(owner)) {
            // Both copies are the app's; a relocation may have put them in different packages.
            if (!pkg(from.name).equals(pkg(owner))) continue;
            if (fromPkg.equals(pkg(renamed(owner)))) continue;
            if (packagePrivate(model.classes.get(owner), ref)) {
              round.block(pkg(owner), describe(from, ref) + ": package-private across a shared class");
            }
          }
        }
        overrides(from, round);
      }
      return round;
    }

    /**
     * A method of [from] that overrides a runtime supertype's, declared there with a type this
     * round relocates, would no longer override it once renamed.
     */
    private void overrides(AppClass from, Round round) {
      List<String> runtimeSupers = new ArrayList<>();
      collectRuntimeSupers(from.name, runtimeSupers, new HashSet<>());
      if (runtimeSupers.isEmpty()) return;
      for (String method : from.methods) {
        String mismatch = relocatedTypeIn(descriptorOf(method), true);
        if (mismatch == null) continue;
        String name = method.substring(0, method.indexOf('('));
        String desc = descriptorOf(method);
        for (String type : runtimeSupers) {
          if (host.findMethod(type, name, desc, new HashSet<>()) == null) continue;
          if (!pinned.contains(type) && relocatable(pkg(type))) {
            round.relocate.add(pkg(type));
            round.why.putIfAbsent(
                pkg(type),
                from.name.replace('/', '.') + "." + name + " overrides " + type.replace('/', '.')
                    + "'s with the app's own " + mismatch.replace('/', '.'));
          } else {
            round.block(
                pkg(mismatch),
                from.name.replace('/', '.') + "." + name + " overrides the shared " + type.replace('/', '.'));
          }
        }
      }
    }

    private void collectRuntimeSupers(String name, List<String> out, Set<String> seen) {
      if (name == null || !seen.add(name)) return;
      if (fromRuntime(name)) {
        out.add(name);
        return;
      }
      AppClass app = model.classes.get(name);
      if (app == null) return;
      collectRuntimeSupers(app.superName, out, seen);
      for (String iface : app.interfaces) collectRuntimeSupers(iface, out, seen);
    }

    /** Why [ref] from [from] (now in [fromPkg]) fails against the runtime's copy, or null. */
    private String runtimeProblem(AppClass from, String fromPkg, Ref ref) {
      Info owner = host.info(ref.owner);
      if (owner == null) return null; // Defined by a runtime jar the reader could not parse.
      boolean ownerPublic = (owner.access & Opcodes.ACC_PUBLIC) != 0;
      if (!ownerPublic && !pkg(ref.owner).equals(fromPkg)) {
        return "the runtime's " + ref.owner.replace('/', '.') + " is not public";
      }
      boolean ownerInterface = (owner.access & Opcodes.ACC_INTERFACE) != 0;
      switch (ref.kind) {
        case Ref.TYPE:
          return null;
        case Ref.SUPER:
          if (ownerInterface) return "extends what the runtime has as an interface";
          if ((owner.access & Opcodes.ACC_FINAL) != 0) return "extends a final runtime class";
          return null;
        case Ref.INTERFACE:
          return ownerInterface ? null : "implements what the runtime has as a class";
        case Ref.METHOD:
          {
            if (ref.interfaceCall != ownerInterface && !"<init>".equals(ref.name)) {
              return ownerInterface ? "calls an interface as a class" : "calls a class as an interface";
            }
            Member member =
                "<init>".equals(ref.name)
                    ? host.declared(ref.owner, ref.name + ref.desc, true)
                    : host.findMethod(ref.owner, ref.name, ref.desc, new HashSet<>());
            if (member == null) return "the runtime's copy has no such method";
            return memberProblem(member, ref, from, fromPkg);
          }
        case Ref.FIELD:
          {
            Member member = host.findField(ref.owner, ref.name, ref.desc, new HashSet<>());
            if (member == null) return "the runtime's copy has no such field";
            return memberProblem(member, ref, from, fromPkg);
          }
        default:
          return null;
      }
    }

    private String memberProblem(Member member, Ref ref, AppClass from, String fromPkg) {
      boolean isStatic = (member.access & Opcodes.ACC_STATIC) != 0;
      if (isStatic != ref.isStatic) {
        return isStatic ? "is static in the runtime's copy" : "is not static in the runtime's copy";
      }
      if ((member.access & Opcodes.ACC_PUBLIC) != 0) return null;
      if ((member.access & Opcodes.ACC_PRIVATE) != 0) return "is private in the runtime's copy";
      if (pkg(member.owner).equals(fromPkg)) return null;
      if ((member.access & Opcodes.ACC_PROTECTED) != 0 && extendsClass(from.name, member.owner)) {
        return null;
      }
      return "is not accessible in the runtime's copy";
    }

    private boolean extendsClass(String name, String ancestor) {
      Set<String> seen = new HashSet<>();
      for (String type = name; type != null && seen.add(type); ) {
        if (type.equals(ancestor)) return true;
        AppClass app = fromRuntime(type) ? null : model.classes.get(type);
        if (app != null) {
          type = app.superName;
        } else {
          Info info = host.info(type);
          type = info == null ? null : info.superName;
        }
      }
      return false;
    }

    /** A type [ref]'s descriptor names that this round relocates: the runtime would see another. */
    private String relocatedTypeIn(Ref ref) {
      if (ref.desc == null || ref.kind == Ref.TYPE) return null;
      return relocatedTypeIn(ref.desc, ref.kind == Ref.METHOD);
    }

    private String relocatedTypeIn(String desc, boolean method) {
      for (String type : typesOf(desc, method)) {
        if (model.classes.containsKey(type) && !renamed(type).equals(type)) return type;
      }
      return null;
    }

    private boolean packagePrivate(AppClass owner, Ref ref) {
      Integer access = null;
      if (ref.kind == Ref.METHOD) access = owner.methodAccess.get(ref.name + ref.desc);
      else if (ref.kind == Ref.FIELD) access = owner.fieldAccess.get(ref.name + ":" + ref.desc);
      if (access == null) return false;
      return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0;
    }

    private static String describe(AppClass from, Ref ref) {
      String target = ref.owner.replace('/', '.');
      if (ref.name != null) target += "." + ref.name;
      return from.name.replace('/', '.') + " -> " + target;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The app's classes

  /** One of the app's classes, as the analysis needs it. */
  static final class AppClass {
    final String name;
    final String superName;
    final String[] interfaces;
    /** Instance methods, {@code name + descriptor}, that can override. */
    final List<String> methods = new ArrayList<>();

    final Map<String, Integer> methodAccess = new HashMap<>();
    final Map<String, Integer> fieldAccess = new HashMap<>();
    final List<Ref> refs = new ArrayList<>();

    AppClass(String name, String superName, String[] interfaces) {
      this.name = name;
      this.superName = superName;
      this.interfaces = interfaces == null ? new String[0] : interfaces;
    }
  }

  /** A reference from an app class to a type or member. */
  static final class Ref {
    static final byte TYPE = 0;
    static final byte SUPER = 1;
    static final byte INTERFACE = 2;
    static final byte METHOD = 3;
    static final byte FIELD = 4;

    final byte kind;
    final String owner;
    final String name;
    final String desc;
    final boolean isStatic;
    final boolean interfaceCall;

    Ref(byte kind, String owner, String name, String desc, boolean isStatic, boolean interfaceCall) {
      this.kind = kind;
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.isStatic = isStatic;
      this.interfaceCall = interfaceCall;
    }
  }

  /** The app's classes, and which of them the runtime also defines. */
  static final class Model {
    final Map<String, AppClass> classes = new HashMap<>();
    final Set<String> colliding = new HashSet<>();
    /** The packages with an app class in them. */
    final Set<String> packages = new HashSet<>();

    static Model read(ZipFile app, Types host) throws IOException {
      Model model = new Model();
      List<ZipEntry> entries = new ArrayList<>();
      Enumeration<? extends ZipEntry> all = app.entries();
      while (all.hasMoreElements()) {
        ZipEntry entry = all.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
          continue;
        }
        String className = name.substring(0, name.length() - ".class".length());
        entries.add(entry);
        model.packages.add(pkg(className));
        if (!never(className) && host.defines(className)) model.colliding.add(className);
      }
      // Only references that a collision can affect are kept: to a package with a colliding
      // class in it, or naming such a type.
      Set<String> collidingPackages = new HashSet<>();
      for (String name : model.colliding) collidingPackages.add(pkg(name));
      for (ZipEntry entry : entries) {
        byte[] bytes;
        try (InputStream in = app.getInputStream(entry)) {
          bytes = in.readAllBytes();
        }
        AppClass cls = scan(bytes, collidingPackages);
        model.classes.put(cls.name, cls);
      }
      return model;
    }

    private static AppClass scan(byte[] bytes, Set<String> collidingPackages) {
      ClassReader reader = new ClassReader(bytes);
      AppClass cls =
          new AppClass(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
      ArrayList<Ref> refs = new ArrayList<>();
      java.util.function.Predicate<String> interesting =
          type -> type != null && collidingPackages.contains(pkg(type));
      java.util.function.Predicate<String> descInteresting =
          desc -> {
            if (desc == null) return false;
            for (String type : typesOf(desc, desc.startsWith("("))) {
              if (interesting.test(type)) return true;
            }
            return false;
          };
      class Adder {
        void type(String internal) {
          String element = element(internal);
          if (interesting.test(element)) {
            refs.add(new Ref(Ref.TYPE, element, null, null, false, false));
          }
        }

        void member(byte kind, String owner, String name, String desc, boolean st, boolean itf) {
          String element = element(owner);
          if (element == null) return;
          if (interesting.test(element) || descInteresting.test(desc)) {
            refs.add(new Ref(kind, element, name, desc, st, itf));
          }
        }

        void handle(Handle handle) {
          int tag = handle.getTag();
          boolean field = tag <= Opcodes.H_PUTSTATIC;
          boolean st = tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC
              || tag == Opcodes.H_INVOKESTATIC;
          member(
              field ? Ref.FIELD : Ref.METHOD,
              handle.getOwner(),
              handle.getName(),
              handle.getDesc(),
              st,
              handle.isInterface());
        }

        void constant(Object value) {
          if (value instanceof Type) {
            Type type = (Type) value;
            if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
              type(type.getInternalName());
            }
          } else if (value instanceof Handle) {
            handle((Handle) value);
          }
        }
      }
      Adder add = new Adder();
      if (cls.superName != null && interesting.test(cls.superName)) {
        refs.add(new Ref(Ref.SUPER, cls.superName, null, null, false, false));
      }
      for (String iface : cls.interfaces) {
        if (interesting.test(iface)) {
          refs.add(new Ref(Ref.INTERFACE, iface, null, null, false, true));
        }
      }
      reader.accept(
          new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(
                int access, String name, String desc, String signature, Object value) {
              cls.fieldAccess.put(name + ":" + desc, access);
              return null;
            }

            @Override
            public MethodVisitor visitMethod(
                int access, String name, String desc, String signature, String[] exceptions) {
              cls.methodAccess.put(name + desc, access);
              if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
                  && !name.startsWith("<")) {
                cls.methods.add(name + desc);
              }
              return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                  add.type(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                  boolean st = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                  add.member(Ref.FIELD, owner, name, desc, st, false);
                }

                @Override
                public void visitMethodInsn(
                    int opcode, String owner, String name, String desc, boolean itf) {
                  add.member(Ref.METHOD, owner, name, desc, opcode == Opcodes.INVOKESTATIC, itf);
                }

                @Override
                public void visitInvokeDynamicInsn(
                    String name, String desc, Handle bsm, Object... args) {
                  add.handle(bsm);
                  for (Object arg : args) add.constant(arg);
                }

                @Override
                public void visitLdcInsn(Object value) {
                  add.constant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String desc, int dims) {
                  add.type(Type.getType(desc).getInternalName());
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String t) {
                  if (t != null) add.type(t);
                }
              };
            }
          },
          ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      refs.trimToSize();
      cls.refs.addAll(refs);
      return cls;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The runtime's classes

  /** A class's header and members, from the runtime, the platform or the JDK. */
  static final class Info {
    final String name;
    final int access;
    final String superName;
    final String[] interfaces;
    /** {@code name + descriptor} to access flags. */
    final Map<String, Integer> methods = new HashMap<>();
    /** {@code name + ":" + descriptor} to access flags. */
    final Map<String, Integer> fields = new HashMap<>();

    Info(String name, int access, String superName, String[] interfaces) {
      this.name = name;
      this.access = access;
      this.superName = superName;
      this.interfaces = interfaces == null ? new String[0] : interfaces;
    }

    static Info of(byte[] bytes) {
      ClassReader reader = new ClassReader(bytes);
      Info info =
          new Info(
              reader.getClassName(),
              reader.getAccess(),
              reader.getSuperName(),
              reader.getInterfaces());
      reader.accept(
          new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(
                int access, String name, String desc, String signature, Object value) {
              info.fields.put(name + ":" + desc, access);
              return null;
            }

            @Override
            public MethodVisitor visitMethod(
                int access, String name, String desc, String signature, String[] exceptions) {
              info.methods.put(name + desc, access);
              return null;
            }
          },
          ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return info;
    }
  }

  /** A member found by resolution: where it is declared, and its access. */
  static final class Member {
    final String owner;
    final int access;

    Member(String owner, int access) {
      this.owner = owner;
      this.access = access;
    }
  }

  /**
   * The sandbox's own classes: the runtime jars, first match winning as on the class path; then,
   * for their supertypes only, the platform jar and the JDK.
   */
  static final class Types implements AutoCloseable {
    private final List<ZipFile> jars = new ArrayList<>();
    private final Map<String, ZipFile> runtime = new HashMap<>();
    private final ZipFile platform;
    private final Map<String, Info> infos = new HashMap<>();
    private final Set<String> missing = new HashSet<>();

    Types(List<Path> runtimeJars, Path platformJar) throws IOException {
      for (Path path : runtimeJars) {
        ZipFile jar;
        try {
          jar = new ZipFile(path.toFile());
        } catch (IOException e) {
          System.err.println("LibraryRelocation: cannot open " + path + ": " + e);
          continue;
        }
        jars.add(jar);
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          String name = entries.nextElement().getName();
          if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
          runtime.putIfAbsent(name.substring(0, name.length() - ".class".length()), jar);
        }
      }
      this.platform = platformJar == null ? null : new ZipFile(platformJar.toFile());
    }

    /** Whether the sandbox loads the runtime's [name] instead of the app's: runtime-first, and here. */
    boolean defines(String name) {
      return runtimeFirst(name) && runtime.containsKey(name);
    }

    Info info(String name) {
      if (name == null) return null;
      Info cached = infos.get(name);
      if (cached != null || missing.contains(name)) return cached;
      Info found = null;
      try {
        byte[] bytes = bytes(name);
        if (bytes != null) found = Info.of(bytes);
      } catch (IOException | RuntimeException e) {
        found = null;
      }
      if (found == null) missing.add(name);
      else infos.put(name, found);
      return found;
    }

    private byte[] bytes(String name) throws IOException {
      String resource = name + ".class";
      ZipFile jar = runtime.get(name);
      if (jar != null) {
        try (InputStream in = jar.getInputStream(jar.getEntry(resource))) {
          return in.readAllBytes();
        }
      }
      if (platform != null) {
        ZipEntry entry = platform.getEntry(resource);
        if (entry != null) {
          try (InputStream in = platform.getInputStream(entry)) {
            return in.readAllBytes();
          }
        }
      }
      try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
        return in == null ? null : in.readAllBytes();
      }
    }

    /** [key] declared by [owner] itself, if it is ({@code method} or field). */
    Member declared(String owner, String key, boolean method) {
      Info info = info(owner);
      if (info == null) return null;
      Integer access = (method ? info.methods : info.fields).get(key);
      return access == null ? null : new Member(owner, access);
    }

    /**
     * JVMS 5.4.3.3 / 5.4.3.4, leniently: the class and its superclasses, then every
     * superinterface. A supertype that cannot be read ends the search with success: a runtime
     * class whose supertype is missing cannot be judged, and the sandbox's view would be the
     * same.
     */
    Member findMethod(String owner, String name, String desc, Set<String> seen) {
      String key = name + desc;
      Deque<String> interfaces = new ArrayDeque<>();
      for (String type = owner; type != null; ) {
        if (!seen.add(type)) break;
        Info info = info(type);
        if (info == null) return new Member(type, Opcodes.ACC_PUBLIC);
        Integer access = info.methods.get(key);
        if (access != null) return new Member(type, access);
        Collections.addAll(interfaces, info.interfaces);
        type = info.superName;
      }
      while (!interfaces.isEmpty()) {
        String type = interfaces.poll();
        if (!seen.add(type)) continue;
        Info info = info(type);
        if (info == null) return new Member(type, Opcodes.ACC_PUBLIC);
        Integer access = info.methods.get(key);
        if (access != null && (access & Opcodes.ACC_STATIC) == 0) return new Member(type, access);
        Collections.addAll(interfaces, info.interfaces);
      }
      // An interface's methods include Object's.
      if (seen.add("java/lang/Object")) {
        Info object = info("java/lang/Object");
        Integer access = object == null ? null : object.methods.get(key);
        if (access != null) return new Member("java/lang/Object", access);
      }
      return null;
    }

    Member findField(String owner, String name, String desc, Set<String> seen) {
      for (String type = owner; type != null; ) {
        if (!seen.add(type)) return null;
        Info info = info(type);
        if (info == null) return new Member(type, Opcodes.ACC_PUBLIC);
        Integer access = info.fields.get(name + ":" + desc);
        if (access != null) return new Member(type, access);
        for (String iface : info.interfaces) {
          Member found = findField(iface, name, desc, seen);
          if (found != null) return found;
        }
        type = info.superName;
      }
      return null;
    }

    @Override
    public void close() throws IOException {
      for (ZipFile jar : jars) jar.close();
      if (platform != null) platform.close();
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The boundary

  /**
   * The app classes whose objects cross between the app and the simulator's own code
   * ({@value #OWN_CODE} in [boundaryJars]), and so must keep their names.
   *
   * <p>The roots are where that code meets an app object: the classes it shadows
   * ({@code @Implements}) and every type in its shadow methods' signatures; and the app classes it
   * extends, implements, casts to, catches or names (a class literal, or a class name in a string,
   * for reflection). From those, the types of what it gets from or gives to such an object — an
   * instance call's signature, a field's type — cross too, until nothing more is added. A value it
   * only passes between its own classes and the runtime's — an {@code ImmutableList} handed to
   * Robolectric — never meets an app object, and does not pin the app's copy.
   */
  static Set<String> crossing(List<Path> boundaryJars, Set<String> appClasses) throws IOException {
    Set<String> types = new HashSet<>();
    // An instance member of an app class the code uses, and the types its signature names.
    Map<String, Set<String>> uses = new HashMap<>();
    for (Path path : boundaryJars) {
      try (ZipFile jar = new ZipFile(path.toFile())) {
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          String name = entry.getName();
          if (!name.startsWith(OWN_CODE) || !name.endsWith(".class") || offline(name)) continue;
          try (InputStream in = jar.getInputStream(entry)) {
            boundaryTypes(in.readAllBytes(), appClasses, types, uses);
          }
        }
      } catch (IOException e) {
        System.err.println("LibraryRelocation: cannot read " + path + ": " + e);
      }
    }
    Deque<String> work = new ArrayDeque<>(types);
    while (!work.isEmpty()) {
      Set<String> reached = uses.get(work.poll());
      if (reached == null) continue;
      for (String type : reached) {
        if (types.add(type)) work.add(type);
      }
    }
    types.retainAll(appClasses);
    return types;
  }

  /** The simulator's classes that never run in the sandbox: this pass and the APK converter. */
  private static boolean offline(String entryName) {
    String name = entryName.substring(0, entryName.length() - ".class".length());
    return name.startsWith(OWN_CODE + "simulator/LibraryRelocation")
        || name.startsWith(OWN_CODE + "simulator/ApkClassConverter");
  }

  static void boundaryTypes(
      byte[] bytes, Set<String> appClasses, Set<String> roots, Map<String, Set<String>> uses) {
    ClassReader reader = new ClassReader(bytes);
    java.util.function.Consumer<String> root =
        type -> {
          String element = element(type);
          if (element != null && appClasses.contains(element)) roots.add(element);
        };
    java.util.function.BiConsumer<String, String> use =
        (owner, d) -> {
          String element = element(owner);
          if (element == null || !appClasses.contains(element) || d == null) return;
          Set<String> reached = uses.computeIfAbsent(element, k -> new HashSet<>());
          for (String type : typesOf(d, d.startsWith("("))) {
            if (appClasses.contains(type)) reached.add(type);
          }
        };
    root.accept(reader.getSuperName());
    for (String iface : reader.getInterfaces()) root.accept(iface);
    // A shadow's methods stand in for its target's: their signatures are the app's.
    boolean[] shadow = {false};
    List<String> ownDescriptors = new ArrayList<>();
    AnnotationVisitor implementsReader =
        new AnnotationVisitor(Opcodes.ASM9) {
          @Override
          public void visit(String name, Object value) {
            if ("value".equals(name) && value instanceof Type) {
              root.accept(((Type) value).getInternalName());
            } else if ("className".equals(name) && value instanceof String) {
              root.accept(((String) value).replace('.', '/'));
            }
          }
        };
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!"Lorg/robolectric/annotation/Implements;".equals(descriptor)) return null;
            shadow[0] = true;
            return implementsReader;
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String d, String signature, String[] exceptions) {
            ownDescriptors.add(d);
            return new MethodVisitor(Opcodes.ASM9) {
              @Override
              public void visitTypeInsn(int opcode, String type) {
                // A cast or type test meets an object of that type; creating one does not.
                if (opcode != Opcodes.NEW) root.accept(type);
              }

              @Override
              public void visitFieldInsn(int opcode, String owner, String name, String d) {
                if (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD) use.accept(owner, d);
              }

              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String name, String d, boolean itf) {
                if (opcode != Opcodes.INVOKESTATIC) use.accept(owner, d);
              }

              @Override
              public void visitInvokeDynamicInsn(String name, String d, Handle bsm, Object... a) {
                for (Object arg : a) {
                  if (!(arg instanceof Handle)) continue;
                  Handle h = (Handle) arg;
                  if (h.getTag() != Opcodes.H_INVOKESTATIC) use.accept(h.getOwner(), h.getDesc());
                }
              }

              @Override
              public void visitLdcInsn(Object value) {
                if (value instanceof Type && ((Type) value).getSort() == Type.OBJECT) {
                  root.accept(((Type) value).getInternalName());
                } else if (value instanceof String) {
                  String s = (String) value;
                  // A class named for reflection: dotted, or internal.
                  if (s.indexOf(' ') < 0 && (s.indexOf('.') > 0 || s.indexOf('/') > 0)) {
                    root.accept(s.replace('.', '/'));
                  }
                }
              }

              @Override
              public void visitTryCatchBlock(Label start, Label end, Label handler, String t) {
                if (t != null) root.accept(t);
              }
            };
          }
        },
        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    if (shadow[0]) {
      for (String d : ownDescriptors) {
        for (String type : typesOf(d, true)) root.accept(type);
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Writing

  /** [app] with [renames] applied, into [out]. */
  static void write(ZipFile app, Path out, Map<String, String> renames, Set<String> packages)
      throws IOException {
    Map<String, String> dotted = new HashMap<>();
    for (Map.Entry<String, String> rename : renames.entrySet()) {
      dotted.put(rename.getKey().replace('/', '.'), rename.getValue().replace('/', '.'));
    }
    Remapper remapper =
        new Remapper() {
          @Override
          public String map(String internalName) {
            String mapped = renames.get(internalName);
            return mapped != null ? mapped : internalName;
          }

          @Override
          public Object mapValue(Object value) {
            if (value instanceof String) {
              String s = (String) value;
              String mapped = renames.get(s);
              if (mapped == null) mapped = dotted.get(s);
              if (mapped != null) return mapped;
            }
            return super.mapValue(value);
          }
        };
    Path partial = out.resolveSibling(out.getFileName() + ".partial");
    Set<String> written = new HashSet<>();
    try (OutputStream file = Files.newOutputStream(partial);
        ZipOutputStream zip = new ZipOutputStream(file)) {
      Enumeration<? extends ZipEntry> entries = app.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        byte[] bytes;
        try (InputStream in = app.getInputStream(entry)) {
          bytes = in.readAllBytes();
        }
        if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
          ClassReader reader = new ClassReader(bytes);
          ClassWriter writer = new ClassWriter(0);
          reader.accept(new ClassRemapper(writer, remapper), 0);
          put(zip, written, remapper.map(reader.getClassName()) + ".class", writer.toByteArray());
        } else if (name.startsWith("META-INF/services/")) {
          String service = name.substring("META-INF/services/".length());
          String mappedService = dotted.getOrDefault(service, service);
          StringBuilder text = new StringBuilder();
          for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            String trimmed = line.trim();
            String mapped = dotted.get(trimmed);
            text.append(mapped != null ? line.replace(trimmed, mapped) : line).append('\n');
          }
          put(
              zip,
              written,
              "META-INF/services/" + mappedService,
              text.substring(0, Math.max(0, text.length() - 1)).getBytes(StandardCharsets.UTF_8));
        } else {
          put(zip, written, name, bytes);
          // A resource beside relocated classes: found from them by a relative name as well.
          int slash = name.lastIndexOf('/');
          if (slash > 0 && packages.contains(name.substring(0, slash))) {
            put(zip, written, PREFIX + name, bytes);
          }
        }
      }
    }
    Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void put(ZipOutputStream zip, Set<String> written, String name, byte[] bytes)
      throws IOException {
    if (!written.add(name)) return;
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  // -----------------------------------------------------------------------------------------------
  // Names

  static String pkg(String internalName) {
    int slash = internalName.lastIndexOf('/');
    return slash < 0 ? "" : internalName.substring(0, slash);
  }

  static boolean never(String internalName) {
    for (String prefix : NEVER) {
      if (internalName.startsWith(prefix)) return true;
    }
    return false;
  }

  /** The class an array type's elements are, or [internal] itself; null for a primitive array. */
  static String element(String internal) {
    if (internal == null || !internal.startsWith("[")) return internal;
    Type type = Type.getType(internal).getElementType();
    return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
  }

  /** The classes a method's ([method]) or a field's descriptor names. */
  static List<String> typesOf(String desc, boolean method) {
    List<String> out = new ArrayList<>(4);
    try {
      if (method) {
        for (Type arg : Type.getArgumentTypes(desc)) addType(arg, out);
        addType(Type.getReturnType(desc), out);
      } else {
        addType(Type.getType(desc), out);
      }
    } catch (RuntimeException e) {
      // Not a descriptor.
    }
    return out;
  }

  private static void addType(Type type, List<String> out) {
    if (type.getSort() == Type.ARRAY) type = type.getElementType();
    if (type.getSort() == Type.OBJECT) out.add(type.getInternalName());
  }

  /** The descriptor part of {@code name + descriptor}. */
  static String descriptorOf(String method) {
    return method.substring(method.indexOf('('));
  }

  static List<Path> paths(String value) {
    List<Path> out = new ArrayList<>();
    if (value == null || value.isEmpty()) return out;
    for (String part : value.split(java.io.File.pathSeparator)) {
      if (!part.isEmpty()) out.add(Paths.get(part));
    }
    return out;
  }

  private static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    return out.toString();
  }
}
