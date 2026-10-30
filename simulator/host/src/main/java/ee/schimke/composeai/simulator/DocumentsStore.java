package ee.schimke.composeai.simulator;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Locale;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * The simulated external storage ({@code /sdcard}, {@link AppFiles}) as Android's {@code
 * ExternalStorageProvider} serves it to the Storage Access Framework, at the same authority and
 * with the same document ids ({@link DocumentIds}): the documents {@link SystemPickers} picks are
 * {@code content://com.android.externalstorage.documents/document/primary%3A…} URIs, and a tree
 * from {@code OPEN_DOCUMENT_TREE} lists, creates, renames and deletes through {@code
 * DocumentsContract} as on a device. Robolectric has no documents provider, so without it a
 * picked URI would open nothing.
 *
 * <p>Registered with Robolectric's content resolver directly, before the application's {@code
 * onCreate()}: it is the system's, not the app's, and its manifest has no entry for it.
 */
public final class DocumentsStore extends DocumentsProvider {

  static final String AUTHORITY = "com.android.externalstorage.documents";

  private static final String[] ROOT_COLUMNS = {
    Root.COLUMN_ROOT_ID,
    Root.COLUMN_FLAGS,
    Root.COLUMN_TITLE,
    Root.COLUMN_DOCUMENT_ID,
    Root.COLUMN_AVAILABLE_BYTES,
  };

  private static final String[] DOCUMENT_COLUMNS = {
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
    Document.COLUMN_FLAGS,
  };

  /** Creates the provider and registers it for {@link #AUTHORITY}. */
  static void install(Context context) {
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTHORITY;
    info.packageName = "com.android.externalstorage";
    info.name = DocumentsStore.class.getName();
    // DocumentsProvider.attachInfo insists on what the system's own provider declares.
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = Manifest.permission.MANAGE_DOCUMENTS;
    info.writePermission = Manifest.permission.MANAGE_DOCUMENTS;
    DocumentsStore store = new DocumentsStore();
    store.attachInfo(context, info);
    Front front = new Front(store);
    front.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(AUTHORITY, front);
  }

  /**
   * What Robolectric's content resolver calls. It queries with the five-argument {@code query},
   * which {@code DocumentsProvider} declares final and rejects ("Pre-Android-O query format"); on a
   * device the framework calls the {@code Bundle} one. The rest passes through as it is.
   */
  static final class Front extends ContentProvider {
    private final DocumentsStore store;

    Front(DocumentsStore store) {
      this.store = store;
    }

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
      Bundle args = null;
      if (sortOrder != null) {
        args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder);
      }
      return store.query(uri, projection, args, null);
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, Bundle queryArgs, CancellationSignal cancellationSignal) {
      return store.query(uri, projection, queryArgs, cancellationSignal);
    }

    @Override
    public String getType(Uri uri) {
      return store.getType(uri);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException("insert is not a documents operation");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
      return store.delete(uri, selection, selectionArgs);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
      throw new UnsupportedOperationException("update is not a documents operation");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      return store.call(method, arg, extras);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
      return store.openFile(uri, mode);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
        throws FileNotFoundException {
      return store.openFile(uri, mode, signal);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
      return store.openAssetFile(uri, mode);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
        throws FileNotFoundException {
      return store.openAssetFile(uri, mode, signal);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
        throws FileNotFoundException {
      return store.openTypedAssetFile(uri, mimeTypeFilter, opts);
    }
  }

  /** The document URI of [file], which must be in the simulated external storage. */
  static Uri documentUri(File file) {
    return DocumentsContract.buildDocumentUri(AUTHORITY, idOf(file));
  }

  /** The tree URI of the directory [dir], as {@code OPEN_DOCUMENT_TREE} returns it. */
  static Uri treeUri(File dir) {
    return DocumentsContract.buildTreeDocumentUri(AUTHORITY, idOf(dir));
  }

  static File root() {
    return Environment.getExternalStorageDirectory();
  }

  static String idOf(File file) {
    String base = root().getAbsoluteFile().toPath().normalize().toString();
    String path = file.getAbsoluteFile().toPath().normalize().toString();
    if (!path.equals(base) && !path.startsWith(base + File.separator)) {
      throw new IllegalArgumentException(file + " is not in the simulated external storage");
    }
    return DocumentIds.of(path.substring(base.length()));
  }

  private static File fileOf(String documentId) throws FileNotFoundException {
    try {
      String path = DocumentIds.path(documentId);
      return path.isEmpty() ? root() : new File(root(), path);
    } catch (IllegalArgumentException e) {
      throw new FileNotFoundException(e.getMessage());
    }
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
    MatrixCursor.RowBuilder row = cursor.newRow();
    row.add(Root.COLUMN_ROOT_ID, DocumentIds.ROOT);
    row.add(
        Root.COLUMN_FLAGS,
        Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
    row.add(Root.COLUMN_TITLE, "Internal storage");
    row.add(Root.COLUMN_DOCUMENT_ID, DocumentIds.ROOT_ID);
    row.add(Root.COLUMN_AVAILABLE_BYTES, root().getUsableSpace());
    return cursor;
  }

  @Override
  public Cursor queryDocument(String documentId, String[] projection)
      throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
    File file = fileOf(documentId);
    if (!file.exists()) throw new FileNotFoundException("no document " + documentId);
    addRow(cursor, documentId, file);
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
      throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
    File dir = fileOf(parentDocumentId);
    File[] children = dir.listFiles();
    if (children == null) return cursor;
    java.util.Arrays.sort(children);
    for (File child : children) {
      addRow(cursor, DocumentIds.child(parentDocumentId, child.getName()), child);
    }
    return cursor;
  }

  private static void addRow(MatrixCursor cursor, String documentId, File file) {
    boolean dir = file.isDirectory();
    MatrixCursor.RowBuilder row = cursor.newRow();
    row.add(Document.COLUMN_DOCUMENT_ID, documentId);
    row.add(
        Document.COLUMN_DISPLAY_NAME,
        documentId.equals(DocumentIds.ROOT_ID) ? "Internal storage" : file.getName());
    row.add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : mimeType(file.getName()));
    row.add(Document.COLUMN_SIZE, dir ? null : file.length());
    row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    int flags = dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
    // The storage root is the volume itself: it is neither deleted nor renamed.
    if (!documentId.equals(DocumentIds.ROOT_ID)) {
      flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
    }
    row.add(Document.COLUMN_FLAGS, flags);
  }

  @Override
  public String getDocumentType(String documentId) throws FileNotFoundException {
    File file = fileOf(documentId);
    return file.isDirectory() ? Document.MIME_TYPE_DIR : mimeType(file.getName());
  }

  @Override
  public ParcelFileDescriptor openDocument(
      String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
    File file = fileOf(documentId);
    int flags = ParcelFileDescriptor.parseMode(mode);
    if ((flags & ParcelFileDescriptor.MODE_CREATE) != 0 && file.getParentFile() != null) {
      file.getParentFile().mkdirs();
    }
    return ParcelFileDescriptor.open(file, flags);
  }

  @Override
  public String createDocument(String parentDocumentId, String mimeType, String displayName)
      throws FileNotFoundException {
    File dir = fileOf(parentDocumentId);
    if (!dir.isDirectory()) throw new FileNotFoundException(parentDocumentId + " is no directory");
    boolean directory = Document.MIME_TYPE_DIR.equals(mimeType);
    String name = directory ? displayName : withExtension(displayName, mimeType);
    name = DocumentIds.unique(name, candidate -> new File(dir, candidate).exists());
    // Checked before anything is created: a name with a path in it ("../x") is refused.
    String id = childId(parentDocumentId, name);
    File file = new File(dir, name);
    try {
      boolean made = directory ? file.mkdirs() : file.createNewFile();
      if (!made) throw new FileNotFoundException("cannot create " + file);
    } catch (IOException e) {
      throw new FileNotFoundException("cannot create " + file + ": " + e.getMessage());
    }
    return id;
  }

  private static String childId(String parentDocumentId, String name) throws FileNotFoundException {
    try {
      return DocumentIds.child(parentDocumentId, name);
    } catch (IllegalArgumentException e) {
      throw new FileNotFoundException(e.getMessage());
    }
  }

  private static void checkNotRoot(String documentId, String operation)
      throws FileNotFoundException {
    if (documentId.equals(DocumentIds.ROOT_ID)) {
      throw new FileNotFoundException("the storage root cannot be " + operation);
    }
  }

  @Override
  public void deleteDocument(String documentId) throws FileNotFoundException {
    checkNotRoot(documentId, "deleted");
    File file = fileOf(documentId);
    if (!delete(file)) throw new FileNotFoundException("cannot delete " + documentId);
  }

  private static boolean delete(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    return file.delete();
  }

  @Override
  public String renameDocument(String documentId, String displayName)
      throws FileNotFoundException {
    checkNotRoot(documentId, "renamed");
    File file = fileOf(documentId);
    String parent = idOf(file.getParentFile());
    childId(parent, displayName);
    File renamed = new File(file.getParentFile(), displayName);
    if (renamed.exists() || !file.renameTo(renamed)) {
      throw new FileNotFoundException("cannot rename " + documentId + " to " + displayName);
    }
    return idOf(renamed);
  }

  @Override
  public boolean isChildDocument(String parentDocumentId, String documentId) {
    try {
      return DocumentIds.isChild(parentDocumentId, documentId);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static String mimeType(String name) {
    int dot = name.lastIndexOf('.');
    if (dot >= 0) {
      String fromMap =
          MimeTypeMap.getSingleton()
              .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
      if (fromMap != null) return fromMap;
    }
    String guessed = URLConnection.guessContentTypeFromName(name);
    return guessed != null ? guessed : "application/octet-stream";
  }

  private static String withExtension(String name, String mimeType) {
    if (name.contains(".") || mimeType == null) return name;
    String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
    if (extension == null && mimeType.equals("text/plain")) extension = "txt";
    return extension == null ? name : name + "." + extension;
  }
}
