package ee.schimke.composeai.simulator;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * In-process {@code MediaStore} content provider ({@code content://media/...}) backed by the
 * simulated external storage ({@link DocumentsStore#root()}). Supports {@code insert}, {@code
 * query}, {@code openFile}, {@code openAssetFile}, {@code update}, {@code delete}, and {@code
 * MediaScannerConnection.scanFile} indexing so apps and storage libraries (e.g. ModernStorage) can
 * create, stream, scan, and query MediaStore entries without a real MediaProvider process.
 */
public final class MediaStoreProvider extends ContentProvider {

  static final String AUTHORITY = MediaStore.AUTHORITY;

  private static final String[] DEFAULT_COLUMNS = {
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DATA,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.DATE_MODIFIED,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.TITLE,
  };

  private static final AtomicLong NEXT_ID = new AtomicLong(1);
  private static final Map<Long, Entry> BY_ID = new ConcurrentHashMap<>();
  private static final Map<String, Entry> BY_PATH = new ConcurrentHashMap<>();

  private static final class Entry {
    final long id;
    final Uri uri;
    File file;
    String displayName;
    String mimeType;
    String relativePath;
    final long dateAddedSeconds;
    long dateModifiedSeconds;
    final long dateTakenMillis;

    Entry(
        long id,
        Uri uri,
        File file,
        String displayName,
        String mimeType,
        String relativePath,
        long nowMillis) {
      this.id = id;
      this.uri = uri;
      this.file = file;
      this.displayName = displayName;
      this.mimeType = mimeType;
      this.relativePath = relativePath;
      this.dateAddedSeconds = nowMillis / 1000L;
      this.dateModifiedSeconds = nowMillis / 1000L;
      this.dateTakenMillis = nowMillis;
    }
  }

  /** Registers the provider for {@link MediaStore#AUTHORITY} and resets any prior state. */
  public static void install(Context context) {
    BY_ID.clear();
    BY_PATH.clear();
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTHORITY;
    info.packageName = "com.android.providers.media.module";
    info.name = MediaStoreProvider.class.getName();
    info.exported = true;
    info.grantUriPermissions = true;
    MediaStoreProvider provider = new MediaStoreProvider();
    provider.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
  }

  private static final String DEVICE_STORAGE_ROOT = "/storage/emulated/0";

  /**
   * Registers or updates a scanned file in the MediaStore index, returning its {@code
   * content://media/external/.../<id>} URI for {@code MediaScannerConnection.scanFile}.
   */
  public static Uri registerScannedFile(File file, String mimeType) {
    if (file == null) return null;
    File resolved = resolveBackingFile(file);
    String abs = resolved.getAbsolutePath();
    Entry existing = BY_PATH.get(abs);
    if (existing != null) {
      if (mimeType != null && !mimeType.isEmpty()) existing.mimeType = mimeType;
      if (resolved.exists()) existing.dateModifiedSeconds = resolved.lastModified() / 1000L;
      return existing.uri;
    }
    String name = resolved.getName();
    String resolvedMime =
        (mimeType != null && !mimeType.isEmpty()) ? mimeType : DocumentsStore.mimeType(name);
    Uri collection = collectionForMime(resolvedMime);
    long id = NEXT_ID.getAndIncrement();
    Uri itemUri = ContentUris.withAppendedId(collection, id);
    String relPath = relativePathOf(resolved);
    long now =
        resolved.exists() && resolved.lastModified() > 0
            ? resolved.lastModified()
            : System.currentTimeMillis();
    Entry entry = new Entry(id, itemUri, resolved, name, resolvedMime, relPath, now);
    BY_ID.put(id, entry);
    BY_PATH.put(abs, entry);
    return itemUri;
  }

  private static File resolveBackingFile(File file) {
    String path = file.getPath();
    if (path.equals(DEVICE_STORAGE_ROOT)) return DocumentsStore.root();
    if (path.startsWith(DEVICE_STORAGE_ROOT + "/")) {
      return new File(DocumentsStore.root(), path.substring(DEVICE_STORAGE_ROOT.length() + 1));
    }
    return file;
  }

  private static String deviceDataPath(File file) {
    File root = DocumentsStore.root();
    if (root != null) {
      String base = root.getAbsoluteFile().toPath().normalize().toString();
      String path = file.getAbsoluteFile().toPath().normalize().toString();
      if (path.equals(base)) return DEVICE_STORAGE_ROOT;
      if (path.startsWith(base + File.separator)) {
        return DEVICE_STORAGE_ROOT + "/" + path.substring(base.length() + 1);
      }
    }
    return file.getAbsolutePath();
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    ContentValues v = values != null ? values : new ContentValues();
    long id = NEXT_ID.getAndIncrement();
    String dataPath = v.getAsString(MediaStore.MediaColumns.DATA);
    String displayName = v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
    String mimeType = v.getAsString(MediaStore.MediaColumns.MIME_TYPE);
    String relativePath = v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);

    if ((displayName == null || displayName.isEmpty()) && dataPath != null && !dataPath.isEmpty()) {
      displayName = new File(dataPath).getName();
    }
    if (displayName == null || displayName.isEmpty()) {
      displayName = "media-" + id;
    }
    if (mimeType == null || mimeType.isEmpty()) {
      mimeType = DocumentsStore.mimeType(displayName);
    }

    File file;
    if (dataPath != null && !dataPath.isEmpty()) {
      file = new File(dataPath);
      if (relativePath == null) relativePath = relativePathOf(file);
    } else {
      String dirPath;
      if (relativePath != null && !relativePath.trim().isEmpty()) {
        dirPath = relativePath.trim();
        // Legacy callers sometimes pass "$directory/$filename" in RELATIVE_PATH.
        if (dirPath.endsWith("/" + displayName)) {
          dirPath = dirPath.substring(0, dirPath.length() - displayName.length() - 1);
        }
        while (dirPath.startsWith("/")) dirPath = dirPath.substring(1);
        while (dirPath.endsWith("/")) dirPath = dirPath.substring(0, dirPath.length() - 1);
      } else {
        dirPath = defaultDirectory(uri, mimeType);
      }
      relativePath = dirPath.isEmpty() ? "" : dirPath + "/";
      File dir = dirPath.isEmpty() ? DocumentsStore.root() : new File(DocumentsStore.root(), dirPath);
      dir.mkdirs();
      displayName =
          DocumentIds.unique(
              displayName,
              candidate -> {
                File f = new File(dir, candidate);
                return f.exists() || BY_PATH.containsKey(f.getAbsolutePath());
              });
      file = new File(dir, displayName);
    }
    if (file.getParentFile() != null) file.getParentFile().mkdirs();
    try {
      if (!file.exists()) file.createNewFile();
    } catch (IOException ignored) {
      // openFile will retry / report if truly unwritable.
    }

    Uri itemUri = ContentUris.withAppendedId(uri, id);
    Entry entry =
        new Entry(id, itemUri, file, displayName, mimeType, relativePath, System.currentTimeMillis());
    BY_ID.put(id, entry);
    BY_PATH.put(file.getAbsolutePath(), entry);
    return itemUri;
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    String[] cols = projection != null && projection.length > 0 ? projection : DEFAULT_COLUMNS;
    MatrixCursor cursor = new MatrixCursor(cols);
    long id = parseId(uri);
    if (id >= 0) {
      Entry entry = BY_ID.get(id);
      if (entry != null) addRow(cursor, cols, entry);
      return cursor;
    }
    List<Entry> matches = matchingEntries(uri, selection, selectionArgs);
    for (Entry entry : matches) {
      addRow(cursor, cols, entry);
    }
    return cursor;
  }

  @Override
  public String getType(Uri uri) {
    long id = parseId(uri);
    if (id >= 0) {
      Entry entry = BY_ID.get(id);
      return entry != null ? entry.mimeType : null;
    }
    return null;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    if (values == null) return 0;
    List<Entry> targets = targetsFor(uri, selection, selectionArgs);
    for (Entry entry : targets) {
      String newName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
      if (newName != null && !newName.isEmpty()) entry.displayName = newName;
      String newMime = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
      if (newMime != null && !newMime.isEmpty()) entry.mimeType = newMime;
      String newRel = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
      if (newRel != null) entry.relativePath = newRel;
      entry.dateModifiedSeconds = System.currentTimeMillis() / 1000L;
    }
    return targets.size();
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    List<Entry> targets = targetsFor(uri, selection, selectionArgs);
    for (Entry entry : targets) {
      BY_ID.remove(entry.id);
      BY_PATH.remove(entry.file.getAbsolutePath());
      if (entry.file.exists()) {
        entry.file.delete();
      }
    }
    return targets.size();
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    return openFile(uri, mode, null);
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    long id = parseId(uri);
    Entry entry = id >= 0 ? BY_ID.get(id) : null;
    if (entry == null) throw new FileNotFoundException("No MediaStore entry for " + uri);
    int flags = ParcelFileDescriptor.parseMode(mode != null ? mode : "r");
    if ((flags & ParcelFileDescriptor.MODE_CREATE) != 0 && entry.file.getParentFile() != null) {
      entry.file.getParentFile().mkdirs();
    }
    return ParcelFileDescriptor.open(entry.file, flags);
  }

  @Override
  public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
    return openAssetFile(uri, mode, null);
  }

  @Override
  public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    ParcelFileDescriptor pfd = openFile(uri, mode, signal);
    return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
  }

  private static List<Entry> targetsFor(Uri uri, String selection, String[] selectionArgs) {
    long id = parseId(uri);
    if (id >= 0) {
      Entry entry = BY_ID.get(id);
      return entry != null ? List.of(entry) : List.of();
    }
    return matchingEntries(uri, selection, selectionArgs);
  }

  private static List<Entry> matchingEntries(Uri uri, String selection, String[] selectionArgs) {
    List<String> segments = uri.getPathSegments();
    String requiredPrefix = null;
    if (segments.contains("images")) requiredPrefix = "image/";
    else if (segments.contains("video")) requiredPrefix = "video/";
    else if (segments.contains("audio")) requiredPrefix = "audio/";

    List<Entry> out = new ArrayList<>();
    for (Entry entry : BY_ID.values()) {
      if (requiredPrefix != null
          && (entry.mimeType == null || !entry.mimeType.startsWith(requiredPrefix))) {
        continue;
      }
      if (!matchesSelection(entry, selection, selectionArgs)) continue;
      out.add(entry);
    }
    return out;
  }

  /** Evaluates AND-joined {@code col = ?} / {@code col = 'val'} predicates; rejects others. */
  private static boolean matchesSelection(Entry entry, String selection, String[] selectionArgs) {
    if (selection == null || selection.isBlank()) return true;
    String[] parts = selection.split("(?i)\\s+AND\\s+");
    int argIdx = 0;
    for (String raw : parts) {
      String clause = raw.trim();
      while (clause.startsWith("(") && clause.endsWith(")")) {
        clause = clause.substring(1, clause.length() - 1).trim();
      }
      int eq = clause.indexOf('=');
      if (eq <= 0 || "!<>".indexOf(clause.charAt(eq - 1)) >= 0) {
        throw new IllegalArgumentException("Unsupported MediaStore selection: " + selection);
      }
      String col = clause.substring(0, eq).trim();
      String rhs = clause.substring(eq + 1).trim();
      if (rhs.startsWith("=")) {
        throw new IllegalArgumentException("Unsupported MediaStore selection: " + selection);
      }
      String expected;
      if ("?".equals(rhs)) {
        if (selectionArgs == null || argIdx >= selectionArgs.length) return false;
        expected = selectionArgs[argIdx++];
      } else if (rhs.startsWith("'") && rhs.endsWith("'") && rhs.length() >= 2) {
        expected = rhs.substring(1, rhs.length() - 1);
      } else if (rhs.indexOf('=') >= 0) {
        throw new IllegalArgumentException("Unsupported MediaStore selection: " + selection);
      } else {
        expected = rhs;
      }
      boolean matched =
          switch (col) {
            case MediaStore.MediaColumns._ID -> String.valueOf(entry.id).equals(expected);
            case MediaStore.MediaColumns.DATA ->
                entry.file.getAbsolutePath().equals(expected)
                    || deviceDataPath(entry.file).equals(expected);
            case MediaStore.MediaColumns.DISPLAY_NAME ->
                entry.displayName != null && entry.displayName.equals(expected);
            case MediaStore.MediaColumns.MIME_TYPE ->
                entry.mimeType != null && entry.mimeType.equals(expected);
            case MediaStore.MediaColumns.RELATIVE_PATH ->
                entry.relativePath != null && entry.relativePath.equals(expected);
            case MediaStore.MediaColumns.TITLE ->
                stripExtension(entry.displayName).equals(expected);
            default ->
                throw new IllegalArgumentException(
                    "Unsupported MediaStore selection column '" + col + "' in: " + selection);
          };
      if (!matched) return false;
    }
    return true;
  }

  private static void addRow(MatrixCursor cursor, String[] cols, Entry entry) {
    long size = entry.file.exists() ? entry.file.length() : 0L;
    long modified =
        entry.file.exists() && entry.file.lastModified() > 0
            ? entry.file.lastModified() / 1000L
            : entry.dateModifiedSeconds;
    Object[] row = new Object[cols.length];
    for (int i = 0; i < cols.length; i++) {
      row[i] =
          switch (cols[i]) {
            case MediaStore.MediaColumns._ID -> entry.id;
            case MediaStore.MediaColumns.DATA -> deviceDataPath(entry.file);
            case MediaStore.MediaColumns.DISPLAY_NAME -> entry.displayName;
            case MediaStore.MediaColumns.MIME_TYPE -> entry.mimeType;
            case MediaStore.MediaColumns.SIZE -> size;
            case MediaStore.MediaColumns.DATE_ADDED -> entry.dateAddedSeconds;
            case MediaStore.MediaColumns.DATE_MODIFIED -> modified;
            case MediaStore.MediaColumns.DATE_TAKEN -> entry.dateTakenMillis;
            case MediaStore.MediaColumns.RELATIVE_PATH -> entry.relativePath;
            case MediaStore.MediaColumns.TITLE -> stripExtension(entry.displayName);
            default -> null;
          };
    }
    cursor.addRow(row);
  }

  private static long parseId(Uri uri) {
    try {
      return ContentUris.parseId(uri);
    } catch (NumberFormatException | UnsupportedOperationException e) {
      return -1;
    }
  }

  private static Uri collectionForMime(String mimeType) {
    String lower = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (lower.startsWith("image/")) return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    if (lower.startsWith("video/")) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    if (lower.startsWith("audio/")) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    return MediaStore.Files.getContentUri("external");
  }

  private static String defaultDirectory(Uri uri, String mimeType) {
    List<String> segments = uri.getPathSegments();
    if (segments.contains("images")) return Environment.DIRECTORY_PICTURES;
    if (segments.contains("video")) return Environment.DIRECTORY_MOVIES;
    if (segments.contains("audio")) return Environment.DIRECTORY_MUSIC;
    if (segments.contains("downloads")) return Environment.DIRECTORY_DOWNLOADS;
    String lower = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (lower.startsWith("image/")) return Environment.DIRECTORY_PICTURES;
    if (lower.startsWith("video/")) return Environment.DIRECTORY_MOVIES;
    if (lower.startsWith("audio/")) return Environment.DIRECTORY_MUSIC;
    return Environment.DIRECTORY_DOWNLOADS;
  }

  private static String relativePathOf(File file) {
    File root = DocumentsStore.root();
    if (root == null || file.getParentFile() == null) return "";
    String base = root.getAbsoluteFile().toPath().normalize().toString();
    String parent = file.getParentFile().getAbsoluteFile().toPath().normalize().toString();
    if (parent.equals(base)) return "";
    if (parent.startsWith(base + File.separator)) {
      return parent.substring(base.length() + 1) + "/";
    }
    return "";
  }

  private static String stripExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
