#!/bin/sh
# Link compiled Android resources into a standalone resource APK and final-ID R classes.
#
# Shared by the Android aspect (one link per target) and the compose-preview CLI (one link over the
# union of a selection's targets, so several libraries render from one APK). Both call it with the
# same arguments, which the aspect also records in its metadata, so the two cannot drift.
#
# Usage: link.sh AAPT2 JAVA_HOME OUT_APK OUT_R_JAR MANIFEST ANDROID_JAR EXTRA_PACKAGES ASSETS ARCHIVE...
#   EXTRA_PACKAGES  colon-separated R packages besides the manifest's own (may be empty); an entry
#                   that is a path (with a slash, or an existing file ending .xml) is a library
#                   manifest, standing for the package it declares
#   ASSETS          a file of "<source>\t<path under assets/>" lines (may be empty); a source that
#                   is a directory merges its contents at that path; later lines win
#   ARCHIVE...      compiled resource archives in overlay order, every dependency first
set -eu
aapt2="$1"; jdk="$2"; apk="$3"; rjar="$4"; manifest="$5"; android_jar="$6"; extra="$7"; assets="$8"
shift 8
# Scratch beside the output, never in /tmp: remote executors often mount a small tmpfs there, and a
# link over a few hundred resource packages generates tens of megabytes of R sources.
# A unique name, so a caller's own file beside the output is never touched.
work="$(mktemp -d "$(dirname "$apk")/.compose-preview-link.XXXXXX")"
# Staged assets keep the read-only modes of Bazel's outputs, which rm cannot remove.
trap 'chmod -R u+w "$work" 2>/dev/null; rm -rf "$work"' EXIT
overlays=""
for zip in "$@"; do overlays="$overlays -R $zip"; done
# The package a manifest declares; empty when it declares none.
manifest_package() {
  tr '\n\r\t' '   ' < "$1" |
    sed -n -E "s/.*<manifest[^>]*[[:space:]]package=[\"']([^\"']*)[\"'].*/\\1/p"
}
own="$(manifest_package "$manifest")"
packages=""
set -f
old_ifs="$IFS"
IFS=":"
for entry in $extra; do
  # A bare name ending .xml is a manifest only when it is a file: com.example.xml is a package.
  case "$entry" in
    */* | *.xml)
      if [ -f "$entry" ]; then
        entry="$(manifest_package "$entry")"
      elif [ "${entry#*/}" != "$entry" ]; then
        echo "link.sh: no manifest at $entry; skipping its R package" >&2
        entry=""
      fi
      ;;
  esac
  if [ -n "$entry" ] && [ "$entry" != "$own" ]; then packages="$packages $entry"; fi
done
IFS="$old_ifs"
set +f
# shellcheck disable=SC2086
extra="$(printf '%s\n' $packages | LC_ALL=C sort -u | paste -s -d: -)"
# aapt2's --extra-packages writes a full copy of the R sources per package, identical but for the
# package line; over a few hundred libraries javac then compiles millions of duplicate lines
# (compose-ai-tools#6110). So aapt2 writes the APK's own package only, javac compiles it once, and
# RClone below copies the compiled R classes into every extra package, renaming the classes in their
# constant pools: byte for byte what javac makes of the copy aapt2 would have written. Only a
# manifest whose package this script cannot read keeps aapt2's copies.
extra_flag=""
if [ -n "$extra" ] && [ -z "$own" ]; then extra_flag="--extra-packages $extra"; fi
assets_flag=""
if [ -s "$assets" ]; then
  tab="$(printf '\t')"
  while IFS="$tab" read -r src path; do
    dest="$work/assets/$path"
    if [ -d "$src" ]; then
      # A directory (TreeArtifact) input: merge its contents at its place under assets/.
      mkdir -p "$dest"
      cp -R "$src/." "$dest/"
    else
      mkdir -p "$(dirname "$dest")"
      cp "$src" "$dest"
    fi
  done < "$assets"
  assets_flag="-A $work/assets"
fi
# The SDK's own aapt2 loads the libraries beside it, as rules_android's launcher arranges.
if [ -d "$(dirname "$aapt2")/lib64" ]; then
  LD_LIBRARY_PATH="$(dirname "$aapt2")/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export LD_LIBRARY_PATH
fi
# shellcheck disable=SC2086
"$aapt2" link -o "$apk" --manifest "$manifest" -I "$android_jar" --auto-add-overlay \
  --no-version-vectors --java "$work/gen" $extra_flag $assets_flag $overlays
mkdir "$work/classes"
# Quoted for javac's argfile syntax: the scratch directory inherits the output path, spaces included.
find "$work/gen" -name '*.java' | LC_ALL=C sort | sed 's/[\\"]/\\&/g; s/.*/"&"/' > "$work/sources"
# Batched, with room to spare: one javac over hundreds of large R.java files exhausts the default
# heap. Each R class stands alone, so the batches compile independently into one directory.
# --release 11: the R classes load in the render JVM, which can be older than the JDK compiling
# them (Buck2's [java] java_home is 21 while the daemon runs on 17).
if [ -s "$work/sources" ]; then
  split -l 100 "$work/sources" "$work/batch."
  for batch in "$work"/batch.*; do
    "$jdk/bin/javac" -J-Xmx2g --release 11 -nowarn -encoding UTF-8 -d "$work/classes" "@$batch"
  done
fi
# r-final.jar is written by RJar below, run from source (JEP 330) with the JDK that compiled the R
# classes: the only tool this action has. Writing it there, rather than cloning to disk for `jar`,
# saves writing and rereading hundreds of megabytes, and BEST_SPEED deflates them several times
# faster than jar's default level.
clone=""
if [ -n "$own" ]; then clone="$extra"; fi
cat > "$work/RJar.java" <<'JAVA'
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Usage: RJar OUT_JAR CLASSES OWN_PACKAGE PACKAGE...
 *
 * <p>Writes CLASSES to OUT_JAR, plus a copy of OWN_PACKAGE's R classes in each PACKAGE. A copy is
 * the compiled class with its own name, and its nested classes' names, replaced in the constant
 * pool: byte for byte what javac compiles from aapt2's copy for that package, which differs from
 * the own package's R.java only in its package line. Entries are sorted and dated as `jar --date`
 * dates them, so the jar is reproducible.
 */
public final class RJar {
  private static final LocalDateTime DATE = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

  public static void main(String[] args) throws IOException {
    Path jar = Paths.get(args[0]);
    Path classes = Paths.get(args[1]);
    // An entry's name to its file, or to the own R class it is a copy of and the copy's package.
    Map<String, Object[]> entries = new TreeMap<>();
    try (Stream<Path> files = Files.walk(classes)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        entries.put(classes.relativize(file).toString().replace('\\', '/'), new Object[] {file});
      }
    }
    if (args.length > 3) {
      String own = args[2].replace('.', '/');
      Map<String, byte[]> rClasses = new TreeMap<>();
      Path ownDir = classes.resolve(own);
      if (Files.isDirectory(ownDir)) {
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(ownDir, "R{.class,$*.class}")) {
          for (Path file : dir) rClasses.put(file.getFileName().toString(), Files.readAllBytes(file));
        }
      }
      if (!rClasses.containsKey("R.class")) {
        throw new IllegalStateException("aapt2 wrote no R class for " + args[2]);
      }
      for (int p = 3; p < args.length; p++) {
        String target = args[p].replace('.', '/');
        for (Map.Entry<String, byte[]> r : rClasses.entrySet()) {
          entries.put(target + "/" + r.getKey(), new Object[] {r.getValue(), own + "/R", target + "/R"});
        }
      }
    }
    // Directory entries, as jar writes them.
    for (String name : new ArrayList<>(entries.keySet())) {
      for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
        entries.putIfAbsent(name.substring(0, slash + 1), null);
      }
    }
    try (OutputStream file = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file, 1 << 16))) {
      zip.setLevel(Deflater.BEST_SPEED);
      put(zip, "META-INF/", null);
      put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, Object[]> entry : entries.entrySet()) {
        Object[] source = entry.getValue();
        byte[] bytes = null;
        if (source != null && source.length == 1) {
          bytes = Files.readAllBytes((Path) source[0]);
        } else if (source != null) {
          bytes = relocate((byte[]) source[0], (String) source[1], (String) source[2]);
        }
        put(zip, entry.getKey(), bytes);
      }
    }
  }

  private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTimeLocal(DATE);
    zip.putNextEntry(entry);
    if (bytes != null) zip.write(bytes);
    zip.closeEntry();
  }

  /** The class file with every CONSTANT_Utf8 naming class FROM (or one nested in it) naming TO's. */
  static byte[] relocate(byte[] classFile, String from, String to) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(classFile.length + 1024);
    DataOutputStream out = new DataOutputStream(bytes);
    int magic = in.readInt();
    if (magic != 0xCAFEBABE) throw new IOException("not a class file");
    out.writeInt(magic);
    out.writeShort(in.readUnsignedShort()); // minor_version
    out.writeShort(in.readUnsignedShort()); // major_version
    int count = in.readUnsignedShort();
    out.writeShort(count);
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      out.writeByte(tag);
      switch (tag) {
        case 1: // Utf8: modified UTF-8 behind a u2 length, as readUTF and writeUTF read and write it
          out.writeUTF(rename(in.readUTF(), from, to));
          break;
        case 7: case 8: case 16: case 19: case 20: // Class, String, MethodType, Module, Package
          out.writeShort(in.readUnsignedShort());
          break;
        case 15: // MethodHandle
          out.writeByte(in.readUnsignedByte());
          out.writeShort(in.readUnsignedShort());
          break;
        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
          out.writeInt(in.readInt());
          break;
        case 5: case 6: // Long, Double: two slots
          out.writeLong(in.readLong());
          i++;
          break;
        default:
          throw new IOException("unknown constant pool tag " + tag);
      }
    }
    // Everything after the constant pool refers to it by index, so it is unchanged.
    out.write(in.readAllBytes());
    out.flush();
    return bytes.toByteArray();
  }

  /** NAME with FROM replaced where it is a whole internal name: bare, in a descriptor, or nested. */
  static String rename(String name, String from, String to) {
    StringBuilder result = null;
    int last = 0;
    int i = name.indexOf(from);
    while (i >= 0) {
      int end = i + from.length();
      boolean starts = i == 0 || name.charAt(i - 1) == 'L';
      boolean ends = end == name.length() || name.charAt(end) == '$' || name.charAt(end) == ';';
      if (starts && ends) {
        if (result == null) result = new StringBuilder();
        result.append(name, last, i).append(to);
        last = end;
        i = name.indexOf(from, end);
      } else {
        i = name.indexOf(from, i + 1);
      }
    }
    return result == null ? name : result.append(name, last, name.length()).toString();
  }
}
JAVA
# shellcheck disable=SC2046
"$jdk/bin/java" -Xmx1g "$work/RJar.java" "$rjar" "$work/classes" "$own" $(printf '%s' "$clone" | tr ':' ' ')
