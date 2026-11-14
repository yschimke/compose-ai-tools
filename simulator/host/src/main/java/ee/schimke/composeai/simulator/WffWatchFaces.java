package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPackageParser;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Installs Watch Face Format (WFF) resource APKs ({@value #PROPERTY} or dynamically pushed via
 * {@link WatchFacePushStore}) into the simulator's {@code PackageManager}, and indexes manifest
 * {@code <property>} and {@code <meta-data>} tags so {@link SimulatorShadowPackageManager} can
 * answer {@code PackageManager.getProperty("com.google.wear.watchface.format.version", pkg)} and
 * {@code WatchFaceSlot.getMetaDataValues(key)}.
 */
final class WffWatchFaces {

  static final String PROPERTY = "composeai.simulator.wffApks";
  static final String FORMAT_VERSION_PROPERTY = "com.google.wear.watchface.format.version";

  static final class ApkInspection {
    final String packageName;
    final long versionCode;
    final boolean hasManifest;
    final boolean hasWatchFaceFormat;
    final boolean hasExecutableContent;
    final String unexpectedEntry;
    final Map<String, List<String>> metaDataValues;

    ApkInspection(
        String packageName,
        long versionCode,
        boolean hasManifest,
        boolean hasWatchFaceFormat,
        boolean hasExecutableContent,
        String unexpectedEntry,
        Map<String, List<String>> metaDataValues) {
      this.packageName = packageName;
      this.versionCode = versionCode;
      this.hasManifest = hasManifest;
      this.hasWatchFaceFormat = hasWatchFaceFormat;
      this.hasExecutableContent = hasExecutableContent;
      this.unexpectedEntry = unexpectedEntry;
      this.metaDataValues = metaDataValues;
    }
  }

  private static final Set<String> installedPackages = new LinkedHashSet<>();
  private static final Map<String, Spec> properties = new LinkedHashMap<>();

  private static final int RES_STRING_POOL_TYPE = 0x0001;
  private static final int RES_XML_TYPE = 0x0003;
  private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
  private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
  private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
  private static final int UTF8_FLAG = 0x100;

  private static final int ATTR_NAME = 0x01010003;
  private static final int ATTR_VALUE = 0x01010024;
  private static final int ATTR_RESOURCE = 0x01010025;
  private static final int ATTR_VERSION_CODE = 0x0101021b;
  private static final int ATTR_VERSION_CODE_MAJOR = 0x01010576;

  private static final int TYPE_REFERENCE = 0x01;
  private static final int TYPE_STRING = 0x03;
  private static final int TYPE_FLOAT = 0x04;
  private static final int TYPE_INT_DEC = 0x10;
  private static final int TYPE_INT_HEX = 0x11;
  private static final int TYPE_INT_BOOLEAN = 0x12;

  private static final Pattern XML_PKG_PATTERN =
      Pattern.compile("\\bpackage\\s*=\\s*\"([^\"]+)\"");
  private static final Pattern XML_VERSION_PATTERN =
      Pattern.compile("(?:android:)?versionCode\\s*=\\s*\"([0-9]+)\"");
  private static final Pattern XML_PROP_OR_META_PATTERN =
      Pattern.compile(
          "<(?:property|meta-data)\\b[^>]*?(?:android:)?name\\s*=\\s*\"([^\"]+)\"[^>]*?(?:android:)?value\\s*=\\s*\"([^\"]*)\"[^>]*/?>");

  private static final class Spec {
    final String name;
    final String packageName;
    final String className;
    final int kind; // 1=boolean, 2=float, 3=int, 4=resource, 5=string
    final int intValue;
    final float floatValue;
    final boolean booleanValue;
    final String stringValue;

    private Spec(
        String name,
        String packageName,
        String className,
        int kind,
        int intValue,
        float floatValue,
        boolean booleanValue,
        String stringValue) {
      this.name = name;
      this.packageName = packageName;
      this.className = className == null ? "" : className;
      this.kind = kind;
      this.intValue = intValue;
      this.floatValue = floatValue;
      this.booleanValue = booleanValue;
      this.stringValue = stringValue;
    }

    String asStringValue() {
      switch (kind) {
        case 1:
          return Boolean.toString(booleanValue);
        case 2:
          return Float.toString(floatValue);
        case 3:
          return Integer.toString(intValue);
        case 4:
          return "@" + Integer.toHexString(intValue);
        default:
          return stringValue == null ? "" : stringValue;
      }
    }

    Object toProperty() {
      switch (kind) {
        case 1:
          return ReflectionHelpers.callConstructor(
              PackageManager.Property.class,
              ClassParameter.from(String.class, name),
              ClassParameter.from(boolean.class, booleanValue),
              ClassParameter.from(String.class, packageName),
              ClassParameter.from(String.class, className));
        case 2:
          return ReflectionHelpers.callConstructor(
              PackageManager.Property.class,
              ClassParameter.from(String.class, name),
              ClassParameter.from(float.class, floatValue),
              ClassParameter.from(String.class, packageName),
              ClassParameter.from(String.class, className));
        case 3:
          return ReflectionHelpers.callConstructor(
              PackageManager.Property.class,
              ClassParameter.from(String.class, name),
              ClassParameter.from(int.class, intValue),
              ClassParameter.from(boolean.class, false),
              ClassParameter.from(String.class, packageName),
              ClassParameter.from(String.class, className));
        case 4:
          return ReflectionHelpers.callConstructor(
              PackageManager.Property.class,
              ClassParameter.from(String.class, name),
              ClassParameter.from(int.class, intValue),
              ClassParameter.from(boolean.class, true),
              ClassParameter.from(String.class, packageName),
              ClassParameter.from(String.class, className));
        default:
          return ReflectionHelpers.callConstructor(
              PackageManager.Property.class,
              ClassParameter.from(String.class, name),
              ClassParameter.from(String.class, stringValue == null ? "" : stringValue),
              ClassParameter.from(String.class, packageName),
              ClassParameter.from(String.class, className));
      }
    }
  }

  /** Installs configured WFF APKs and indexes manifest {@code <property>} entries. */
  static synchronized void install(Application app) {
    installedPackages.clear();
    properties.clear();
    String mainApk = app.getApplicationInfo().sourceDir;
    if (mainApk != null && !mainApk.isEmpty()) {
      indexApkProperties(Path.of(mainApk));
    }
    String raw = System.getProperty(PROPERTY);
    if (raw == null || raw.isEmpty()) return;
    for (String entry : raw.split(File.pathSeparator)) {
      if (entry.isEmpty()) continue;
      Path apk = Path.of(entry);
      try {
        installDynamicApk(app, apk);
      } catch (RuntimeException e) {
        System.err.println("wff: cannot install " + apk + ": " + e);
      }
    }
  }

  /** Dynamically installs a WFF APK into {@link ShadowPackageManager} and indexes its properties. */
  static synchronized String installDynamicApk(Application app, Path apk) {
    ApkInspection inspection = inspectApk(apk);
    String pkgName = inspection.packageName;
    if (app != null) {
      ShadowPackageManager manager = Shadow.extract(app.getPackageManager());
      boolean parsedByFramework = false;
      try {
        Object parsed =
            ReflectionHelpers.callStaticMethod(
                ShadowPackageParser.class,
                "callParsePackage",
                ClassParameter.from(Path.class, apk));
        ApplicationInfo ai = ReflectionHelpers.getField(parsed, "applicationInfo");
        ai.sourceDir = apk.toString();
        ai.publicSourceDir = apk.toString();
        String parsedPkg = ReflectionHelpers.getField(parsed, "packageName");
        if (parsedPkg != null && !parsedPkg.isEmpty()) pkgName = parsedPkg;
        ReflectionHelpers.callInstanceMethod(
            manager, "addPackageInternal", ClassParameter.from(parsed.getClass(), parsed));
        try {
          PackageInfo installed = app.getPackageManager().getPackageInfo(pkgName, 0);
          if (installed != null && installed.applicationInfo != null) {
            installed.applicationInfo.sourceDir = apk.toString();
            installed.applicationInfo.publicSourceDir = apk.toString();
          }
        } catch (PackageManager.NameNotFoundException ignored) {
          // Handled by addPackageInternal above.
        }
        parsedByFramework = true;
      } catch (RuntimeException ignored) {
        // Synthetic test APKs without full resources.arsc fall back to PackageInfo registration.
      }
      if (!parsedByFramework && pkgName != null && !pkgName.isEmpty()) {
        PackageInfo pkgInfo = new PackageInfo();
        pkgInfo.packageName = pkgName;
        pkgInfo.setLongVersionCode(inspection.versionCode);
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkgName;
        ai.sourceDir = apk.toString();
        ai.publicSourceDir = apk.toString();
        pkgInfo.applicationInfo = ai;
        manager.installPackage(pkgInfo);
      }
    }
    if (pkgName != null && !pkgName.isEmpty()) {
      installedPackages.add(pkgName);
    }
    indexApkProperties(apk);
    return pkgName;
  }

  static synchronized void uninstallPackage(Application app, String packageName) {
    if (packageName == null || packageName.isEmpty()) return;
    installedPackages.remove(packageName);
    for (Iterator<Map.Entry<String, Spec>> it = properties.entrySet().iterator(); it.hasNext(); ) {
      if (packageName.equals(it.next().getValue().packageName)) {
        it.remove();
      }
    }
    if (app != null) {
      try {
        ShadowPackageManager manager = Shadow.extract(app.getPackageManager());
        manager.removePackage(packageName);
      } catch (RuntimeException ignored) {
      }
    }
  }

  /** Inspects a candidate WFF APK for manifest identity, metadata, WFF signal, and code entries. */
  static ApkInspection inspectApk(Path apk) {
    if (apk == null || !apk.toFile().isFile()) {
      return new ApkInspection("", 0L, false, false, false, null, Collections.emptyMap());
    }
    try (ZipFile zip = new ZipFile(apk.toFile())) {
      boolean hasExec = false;
      String unexpected = null;
      boolean hasRawWatchFace = false;
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".dex") || lower.endsWith(".so")) {
          hasExec = true;
          if (unexpected == null) unexpected = name;
        }
        if (lower.startsWith("res/raw") && lower.endsWith(".xml")) {
          hasRawWatchFace = true;
        }
      }
      ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
      if (manifest == null) {
        return new ApkInspection(
            "", 0L, false, false, hasExec, unexpected, Collections.emptyMap());
      }
      byte[] bytes;
      try (InputStream in = zip.getInputStream(manifest)) {
        bytes = in.readAllBytes();
      }
      ManifestParsed parsed = parseManifestDetails(bytes);
      boolean hasWff =
          parsed.metaDataValues.containsKey(FORMAT_VERSION_PROPERTY) || hasRawWatchFace;
      return new ApkInspection(
          parsed.packageName,
          parsed.versionCode,
          !parsed.packageName.isEmpty(),
          hasWff,
          hasExec,
          unexpected,
          parsed.metaDataValues);
    } catch (IOException e) {
      return new ApkInspection("", 0L, false, false, false, null, Collections.emptyMap());
    }
  }

  static synchronized List<String> packages() {
    return new ArrayList<>(installedPackages);
  }

  static synchronized boolean isInstalled(String packageName) {
    return installedPackages.contains(packageName);
  }

  static synchronized Object findProperty(
      String packageName, String className, String propertyName) {
    if (packageName == null || propertyName == null) return null;
    Spec spec = properties.get(key(packageName, className == null ? "" : className, propertyName));
    return spec == null ? null : spec.toProperty();
  }

  static synchronized void indexApkProperties(Path apk) {
    try (ZipFile zip = new ZipFile(apk.toFile())) {
      ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
      if (manifest == null) return;
      try (InputStream in = zip.getInputStream(manifest)) {
        parseManifestProperties(in.readAllBytes());
      }
    } catch (IOException ignored) {
      // Non-APK or unreadable manifest.
    }
  }

  private static final class ManifestParsed {
    String packageName = "";
    long versionCode = 1L;
    final Map<String, List<String>> metaDataValues = new LinkedHashMap<>();
    final List<Spec> specs = new ArrayList<>();
  }

  static synchronized void parseManifestProperties(byte[] bytes) {
    ManifestParsed parsed = parseManifestDetails(bytes);
    for (Spec spec : parsed.specs) {
      properties.put(key(spec.packageName, spec.className, spec.name), spec);
    }
  }

  private static ManifestParsed parseManifestDetails(byte[] bytes) {
    ManifestParsed out = new ManifestParsed();
    if (bytes == null || bytes.length < 4) return out;
    int firstShort = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xffff;
    if (firstShort != RES_XML_TYPE) {
      // Plain UTF-8 AndroidManifest.xml (supported for lightweight synthetic test APKs).
      String text = new String(bytes, StandardCharsets.UTF_8);
      Matcher pkgM = XML_PKG_PATTERN.matcher(text);
      if (pkgM.find()) out.packageName = pkgM.group(1);
      Matcher verM = XML_VERSION_PATTERN.matcher(text);
      if (verM.find()) {
        try {
          out.versionCode = Long.parseLong(verM.group(1));
        } catch (NumberFormatException ignored) {
        }
      }
      Matcher propM = XML_PROP_OR_META_PATTERN.matcher(text);
      while (propM.find()) {
        String name = propM.group(1);
        String val = propM.group(2);
        addMetaValue(out.metaDataValues, name, val);
        if (!out.packageName.isEmpty()) {
          out.specs.add(new Spec(name, out.packageName, "", 5, 0, 0f, false, val));
        }
      }
      return out;
    }

    if (bytes.length < 8) return out;
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    List<String> strings = Collections.emptyList();
    int[] resourceIds = new int[0];
    String packageName = "";
    int versionCodeLow = 1;
    int versionCodeHigh = 0;
    boolean seenVersion = false;
    List<String> elementStack = new ArrayList<>();
    List<String> componentStack = new ArrayList<>();
    int offset = buffer.getShort(2) & 0xffff;
    while (offset + 8 <= bytes.length) {
      int type = buffer.getShort(offset) & 0xffff;
      int headerSize = buffer.getShort(offset + 2) & 0xffff;
      int size = buffer.getInt(offset + 4);
      if (size <= 0 || offset + size > bytes.length) break;
      if (type == RES_STRING_POOL_TYPE) {
        strings = readStringPool(buffer, offset);
      } else if (type == RES_XML_RESOURCE_MAP_TYPE) {
        int count = (size - headerSize) / 4;
        resourceIds = new int[count];
        for (int i = 0; i < count; i++) {
          resourceIds[i] = buffer.getInt(offset + headerSize + i * 4);
        }
      } else if (type == RES_XML_START_ELEMENT_TYPE) {
        int ext = offset + headerSize;
        String element = stringAt(strings, buffer.getInt(ext + 4));
        int attrStart = buffer.getShort(ext + 8) & 0xffff;
        int attrSize = buffer.getShort(ext + 10) & 0xffff;
        int count = buffer.getShort(ext + 12) & 0xffff;

        String attrName = null;
        String manifestPkg = null;
        int valueType = 0;
        int valueData = 0;
        String valueText = null;
        int resourceData = 0;
        boolean hasResource = false;

        for (int i = 0; i < count; i++) {
          int at = ext + attrStart + i * attrSize;
          int nameIdx = buffer.getInt(at + 4);
          int resId = nameIdx >= 0 && nameIdx < resourceIds.length ? resourceIds[nameIdx] : 0;
          String attr = stringAt(strings, nameIdx);
          int raw = buffer.getInt(at + 8);
          int dataType = bytes[at + 15] & 0xff;
          int data = buffer.getInt(at + 16);
          String text =
              raw >= 0
                  ? stringAt(strings, raw)
                  : (dataType == TYPE_STRING ? stringAt(strings, data) : null);
          if (resId == ATTR_NAME || "name".equals(attr)) {
            attrName = text;
          } else if ("package".equals(attr)) {
            manifestPkg = text;
          } else if (resId == ATTR_VERSION_CODE || "versionCode".equals(attr)) {
            versionCodeLow = data;
            seenVersion = true;
          } else if (resId == ATTR_VERSION_CODE_MAJOR || "versionCodeMajor".equals(attr)) {
            versionCodeHigh = data;
            seenVersion = true;
          } else if (resId == ATTR_RESOURCE || "resource".equals(attr)) {
            hasResource = true;
            resourceData = data;
          } else if (resId == ATTR_VALUE || "value".equals(attr)) {
            valueType = dataType;
            valueData = data;
            valueText = text;
          }
        }

        if ("manifest".equals(element)) {
          if (manifestPkg != null) {
            packageName = manifestPkg;
            out.packageName = manifestPkg;
          }
          if (seenVersion) {
            out.versionCode =
                (((long) versionCodeHigh) << 32) | (((long) versionCodeLow) & 0xffffffffL);
          }
        }
        String parentComponent =
            componentStack.isEmpty() ? "" : componentStack.get(componentStack.size() - 1);
        String currentComponent = parentComponent;
        if ("activity".equals(element)
            || "activity-alias".equals(element)
            || "service".equals(element)
            || "receiver".equals(element)
            || "provider".equals(element)) {
          currentComponent = qualify(packageName, attrName);
        } else if ("application".equals(element)) {
          currentComponent = "";
        } else if (("property".equals(element) || "meta-data".equals(element))
            && attrName != null
            && !packageName.isEmpty()) {
          Spec spec =
              buildSpec(
                  attrName,
                  packageName,
                  parentComponent,
                  hasResource,
                  resourceData,
                  valueType,
                  valueData,
                  valueText);
          if (spec != null) {
            addMetaValue(out.metaDataValues, attrName, spec.asStringValue());
            if ("property".equals(element)) {
              out.specs.add(spec);
            }
          }
        }
        elementStack.add(element);
        componentStack.add(currentComponent);
      } else if (type == RES_XML_END_ELEMENT_TYPE) {
        if (!elementStack.isEmpty()) {
          elementStack.remove(elementStack.size() - 1);
          componentStack.remove(componentStack.size() - 1);
        }
      }
      offset += size;
    }
    return out;
  }

  private static void addMetaValue(Map<String, List<String>> map, String key, String rawValue) {
    if (key == null || rawValue == null) return;
    List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
    if (rawValue.indexOf('|') >= 0) {
      for (String part : rawValue.split("\\|")) {
        if (!part.isEmpty()) list.add(part);
      }
    } else {
      list.add(rawValue);
    }
  }

  private static Spec buildSpec(
      String name,
      String packageName,
      String className,
      boolean hasResource,
      int resourceData,
      int valueType,
      int valueData,
      String valueText) {
    if (hasResource || valueType == TYPE_REFERENCE) {
      int resId = hasResource ? resourceData : valueData;
      return new Spec(name, packageName, className, 4, resId, 0f, false, null);
    }
    switch (valueType) {
      case TYPE_INT_BOOLEAN:
        return new Spec(name, packageName, className, 1, 0, 0f, valueData != 0, null);
      case TYPE_FLOAT:
        return new Spec(
            name, packageName, className, 2, 0, Float.intBitsToFloat(valueData), false, null);
      case TYPE_INT_DEC:
      case TYPE_INT_HEX:
        return new Spec(name, packageName, className, 3, valueData, 0f, false, null);
      case TYPE_STRING:
        return new Spec(name, packageName, className, 5, 0, 0f, false, valueText);
      default:
        return valueText != null
            ? new Spec(name, packageName, className, 5, 0, 0f, false, valueText)
            : null;
    }
  }

  private static String key(String packageName, String className, String propertyName) {
    return packageName + "/" + className + ":" + propertyName;
  }

  private static String qualify(String packageName, String name) {
    if (name == null || name.isEmpty()) return "";
    if (name.startsWith(".")) return packageName + name;
    if (name.indexOf('.') < 0 && !packageName.isEmpty()) return packageName + "." + name;
    return name;
  }

  private static String stringAt(List<String> strings, int index) {
    return index >= 0 && index < strings.size() ? strings.get(index) : "";
  }

  private static List<String> readStringPool(ByteBuffer buffer, int chunk) {
    int count = buffer.getInt(chunk + 8);
    int flags = buffer.getInt(chunk + 16);
    int stringsStart = buffer.getInt(chunk + 20);
    int headerSize = buffer.getShort(chunk + 2) & 0xffff;
    boolean utf8 = (flags & UTF8_FLAG) != 0;
    List<String> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int at = chunk + stringsStart + buffer.getInt(chunk + headerSize + i * 4);
      result.add(utf8 ? readUtf8(buffer, at) : readUtf16(buffer, at));
    }
    return result;
  }

  private static String readUtf8(ByteBuffer buffer, int start) {
    int at = start;
    at += (buffer.get(at) & 0x80) != 0 ? 2 : 1;
    int length = buffer.get(at) & 0xff;
    if ((length & 0x80) != 0) {
      length = ((length & 0x7f) << 8) | (buffer.get(at + 1) & 0xff);
      at += 2;
    } else {
      at += 1;
    }
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) bytes[i] = buffer.get(at + i);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String readUtf16(ByteBuffer buffer, int start) {
    int at = start;
    int length = buffer.getShort(at) & 0xffff;
    if ((length & 0x8000) != 0) {
      length = ((length & 0x7fff) << 16) | (buffer.getShort(at + 2) & 0xffff);
      at += 4;
    } else {
      at += 2;
    }
    char[] chars = new char[length];
    for (int i = 0; i < length; i++) chars[i] = (char) (buffer.getShort(at + i * 2) & 0xffff);
    return new String(chars);
  }

  private WffWatchFaces() {}
}
