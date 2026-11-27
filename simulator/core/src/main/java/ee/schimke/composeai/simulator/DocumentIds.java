package ee.schimke.composeai.simulator;

/**
 * Document ids of the simulated external storage, as Android's {@code ExternalStorageProvider}
 * writes them: {@code primary:} and the path below the storage root, {@code /}-separated, with
 * {@code primary:} alone the root. Plain Java, so the mapping is tested without a sandbox.
 */
final class DocumentIds {

  static final String ROOT = "primary";
  static final String ROOT_ID = ROOT + ":";

  /** The id of [relativePath] below the storage root ({@code ""} for the root itself). */
  static String of(String relativePath) {
    String trimmed = relativePath.replace('\\', '/');
    while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return ROOT_ID + trimmed;
  }

  /** The path below the storage root that [documentId] names; refuses another root or {@code ..}. */
  static String path(String documentId) {
    if (!documentId.startsWith(ROOT_ID)) {
      throw new IllegalArgumentException("not a document of the primary storage: " + documentId);
    }
    String path = documentId.substring(ROOT_ID.length());
    for (String segment : path.split("/")) {
      if (segment.equals("..")) throw new IllegalArgumentException(documentId + " climbs out");
    }
    return path;
  }

  /** The id of [name] inside the directory [parentId]. */
  static String child(String parentId, String name) {
    if (name.isEmpty() || name.contains("/") || name.equals("..") || name.equals(".")) {
      throw new IllegalArgumentException("not a file name: " + name);
    }
    String parent = path(parentId);
    return of(parent.isEmpty() ? name : parent + "/" + name);
  }

  /** Whether [childId] is [parentId] or below it. */
  static boolean isChild(String parentId, String childId) {
    String parent = path(parentId);
    String child = path(childId);
    return parent.isEmpty() || child.equals(parent) || child.startsWith(parent + "/");
  }

  /**
   * [name], or {@code name (1).ext}, {@code name (2).ext}… : the first that [taken] says is free,
   * as the documents UI names a file created beside one of the same name.
   */
  static String unique(String name, java.util.function.Predicate<String> taken) {
    if (!taken.test(name)) return name;
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String extension = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; ; i++) {
      String candidate = base + " (" + i + ")" + extension;
      if (!taken.test(candidate)) return candidate;
    }
  }

  private DocumentIds() {}
}
