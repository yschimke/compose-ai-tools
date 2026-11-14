package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import com.google.wear.services.watchfaces.watchfacepush.ListWatchFaceSlotsResponse;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.AddException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.IsActiveException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.ListException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.RemoveException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.SetActiveException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager.UpdateException;
import com.google.wear.services.watchfaces.watchfacepush.WatchFaceSlot;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Backing store for {@link
 * com.google.wear.services.watchfaces.watchfacepush.WatchFacePushManager}, validating pushed Watch
 * Face Format APKs, compiling them on demand into RemoteCompose {@code .rc} documents when the DWF
 * compiler classpath is available, registering them in {@link WffWatchFaces} and {@link
 * WatchFaceHost}, and emitting {@code watchFacePush} control-channel events.
 */
public final class WatchFacePushStore {

  static final String SLOT_LIMIT_PROPERTY = "composeai.simulator.pushSlotLimit";
  static final String TOKEN_POLICY_PROPERTY = "composeai.simulator.pushTokenPolicy";
  static final String MAX_SET_ACTIVE_PROPERTY = "composeai.simulator.pushMaxSetActiveAttempts";
  static final String EXTRACTOR_CP_PROPERTY = "composeai.simulator.dwfExtractorClasspath";
  static final String DWF_JAVA_PROPERTY = "composeai.simulator.dwfJava";
  static final String DWF_ROBOLECTRIC_CP_PROPERTY = "composeai.simulator.dwfRobolectricClasspath";
  static final String DWF_CACHE_ROOT_PROPERTY = "composeai.simulator.dwfCacheRoot";
  private static final String COMPILER_MAIN = "ee.schimke.composeai.cli.ApkDwfCompilerMain";

  static final class StoredSlot {
    final String slotId;
    final String clientPackage;
    String packageName;
    long versionCode;
    Map<String, List<String>> metaData;
    Path apkFile;
    List<DwfWatchFaceClient.Entry> compiledEntries;

    StoredSlot(
        String slotId,
        String clientPackage,
        String packageName,
        long versionCode,
        Map<String, List<String>> metaData,
        Path apkFile,
        List<DwfWatchFaceClient.Entry> compiledEntries) {
      this.slotId = slotId;
      this.clientPackage = clientPackage;
      this.packageName = packageName;
      this.versionCode = versionCode;
      this.metaData = metaData;
      this.apkFile = apkFile;
      this.compiledEntries = compiledEntries;
    }

    WatchFaceSlot toSdkSlot() {
      return new WatchFaceSlot(slotId, packageName, versionCode, metaData);
    }

    JSONObject toJson(String activePackage) {
      return new Protocol.Json()
          .put("slotId", slotId)
          .put("packageName", packageName)
          .put("versionCode", versionCode)
          .put("active", packageName.equals(activePackage))
          .put("compiled", compiledEntries != null && !compiledEntries.isEmpty())
          .object();
    }
  }

  private static final WatchFacePushStore INSTANCE = new WatchFacePushStore();

  private final Map<String, StoredSlot> slots = new LinkedHashMap<>();
  private final AtomicInteger nextSlotSeq = new AtomicInteger(1);
  private int setActiveAttempts;
  private volatile String activePackageName;
  private Path storageDir;

  private WatchFacePushStore() {}

  public static WatchFacePushStore get() {
    return INSTANCE;
  }

  /** Resets pushed slots and attempt counters (for tests). */
  static synchronized void reset() {
    INSTANCE.slots.clear();
    INSTANCE.nextSlotSeq.set(1);
    INSTANCE.setActiveAttempts = 0;
    INSTANCE.activePackageName = null;
  }

  synchronized void setActivePackageName(String packageName) {
    this.activePackageName = packageName;
  }

  synchronized String activePackageName() {
    return activePackageName;
  }

  public void listWatchFaceSlots(
      Context context,
      Executor executor,
      OutcomeReceiver<ListWatchFaceSlotsResponse, ListException> receiver) {
    try {
      String clientPkg = context.getPackageName();
      ListWatchFaceSlotsResponse response = listForClient(clientPkg);
      deliverResult(executor, receiver, response);
    } catch (Throwable t) {
      deliverError(
          executor, receiver, new ListException(ListException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  public void addWatchFace(
      Context context,
      ParcelFileDescriptor apkFd,
      String validationToken,
      Executor executor,
      OutcomeReceiver<WatchFaceSlot, AddException> receiver) {
    try {
      WatchFaceSlot slot = addInternal(context, apkFd, validationToken);
      deliverResult(executor, receiver, slot);
    } catch (AddException e) {
      deliverError(executor, receiver, e);
    } catch (Throwable t) {
      deliverError(
          executor, receiver, new AddException(AddException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  public void updateWatchFace(
      Context context,
      String slotId,
      ParcelFileDescriptor apkFd,
      String validationToken,
      Executor executor,
      OutcomeReceiver<WatchFaceSlot, UpdateException> receiver) {
    try {
      WatchFaceSlot slot = updateInternal(context, slotId, apkFd, validationToken);
      deliverResult(executor, receiver, slot);
    } catch (UpdateException e) {
      deliverError(executor, receiver, e);
    } catch (Throwable t) {
      deliverError(
          executor, receiver, new UpdateException(UpdateException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  public void removeWatchFace(
      Context context,
      String slotId,
      Executor executor,
      OutcomeReceiver<Void, RemoveException> receiver) {
    try {
      removeInternal(context, slotId);
      deliverResult(executor, receiver, null);
    } catch (RemoveException e) {
      deliverError(executor, receiver, e);
    } catch (Throwable t) {
      deliverError(
          executor, receiver, new RemoveException(RemoveException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  public void setWatchFaceAsActive(
      Context context,
      String slotId,
      Executor executor,
      OutcomeReceiver<Void, SetActiveException> receiver) {
    try {
      setActiveInternal(context, slotId);
      deliverResult(executor, receiver, null);
    } catch (SetActiveException e) {
      deliverError(executor, receiver, e);
    } catch (Throwable t) {
      deliverError(
          executor,
          receiver,
          new SetActiveException(SetActiveException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  public void isWatchFaceActive(
      Context context,
      String packageName,
      Executor executor,
      OutcomeReceiver<Boolean, IsActiveException> receiver) {
    try {
      String clientPkg = context.getPackageName();
      if (!isValidPushPackageName(clientPkg, packageName)) {
        throw new IsActiveException(
            IsActiveException.ERROR_INVALID_PACKAGE_NAME,
            "Watch face package '"
                + packageName
                + "' must start with '"
                + clientPkg
                + ".watchfacepush.'");
      }
      boolean active = isPackageActive(packageName);
      deliverResult(executor, receiver, Boolean.valueOf(active));
    } catch (IsActiveException e) {
      deliverError(executor, receiver, e);
    } catch (Throwable t) {
      deliverError(
          executor,
          receiver,
          new IsActiveException(IsActiveException.ERROR_UNKNOWN, t.getMessage()));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Internal operations & control-channel helpers

  private synchronized ListWatchFaceSlotsResponse listForClient(String clientPkg) {
    List<WatchFaceSlot> clientSlots = new ArrayList<>();
    for (StoredSlot s : slots.values()) {
      if (clientPkg.equals(s.clientPackage)) {
        clientSlots.add(s.toSdkSlot());
      }
    }
    int limit = slotLimit();
    int available = Math.max(0, limit - clientSlots.size());
    return new ListWatchFaceSlotsResponse(clientSlots, available);
  }

  private synchronized WatchFaceSlot addInternal(
      Context context, ParcelFileDescriptor apkFd, String validationToken) throws AddException {
    String clientPkg = context.getPackageName();
    if (!isValidToken(validationToken)) {
      throw new AddException(
          AddException.ERROR_INVALID_VALIDATION_TOKEN, "Invalid Watch Face Push validation token");
    }
    int used = countClientSlots(clientPkg);
    if (used >= slotLimit()) {
      throw new AddException(
          AddException.ERROR_SLOT_LIMIT_REACHED,
          "Watch Face Push slot limit (" + slotLimit() + ") reached for " + clientPkg);
    }
    Path staged = stageDescriptorToTemp(apkFd);
    if (staged == null) {
      throw new AddException(
          AddException.ERROR_MALFORMED_WATCHFACE_APK, "Null or unreadable watch face APK");
    }
    WffWatchFaces.ApkInspection inspection = WffWatchFaces.inspectApk(staged);
    if (!inspection.hasManifest || !inspection.hasWatchFaceFormat) {
      deleteQuietly(staged);
      throw new AddException(
          AddException.ERROR_MALFORMED_WATCHFACE_APK,
          "APK is missing AndroidManifest.xml or Watch Face Format declaration");
    }
    if (inspection.hasExecutableContent) {
      deleteQuietly(staged);
      throw new AddException(
          AddException.ERROR_UNEXPECTED_CONTENT,
          "Watch Face Format APK must not contain executable code ("
              + inspection.unexpectedEntry
              + ")");
    }
    if (!isValidPushPackageName(clientPkg, inspection.packageName)) {
      deleteQuietly(staged);
      throw new AddException(
          AddException.ERROR_INVALID_PACKAGE_NAME,
          "Watch face package '"
              + inspection.packageName
              + "' must start with '"
              + clientPkg
              + ".watchfacepush.'");
    }
    for (StoredSlot existing : slots.values()) {
      if (inspection.packageName.equals(existing.packageName)) {
        deleteQuietly(staged);
        throw new AddException(
            AddException.ERROR_INVALID_PACKAGE_NAME,
            "Watch face package '" + inspection.packageName + "' is already installed");
      }
    }

    String slotId = "slot-" + nextSlotSeq.getAndIncrement();
    Path finalApk = moveToSlotPath(staged, slotId);
    Application app = applicationOf(context);
    WffWatchFaces.installDynamicApk(app, finalApk);
    List<DwfWatchFaceClient.Entry> compiled = compileOrExtractEntries(finalApk, inspection);
    StoredSlot stored =
        new StoredSlot(
            slotId,
            clientPkg,
            inspection.packageName,
            inspection.versionCode,
            inspection.metaDataValues,
            finalApk,
            compiled);
    slots.put(slotId, stored);

    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.onPushedSlotUpdated(stored, false);
      host.emitPushEvent("add", stored);
    }
    return stored.toSdkSlot();
  }

  private synchronized WatchFaceSlot updateInternal(
      Context context, String slotId, ParcelFileDescriptor apkFd, String validationToken)
      throws UpdateException {
    String clientPkg = context.getPackageName();
    StoredSlot existing = slots.get(slotId);
    if (existing == null || !clientPkg.equals(existing.clientPackage)) {
      throw new UpdateException(
          UpdateException.ERROR_INVALID_SLOT_ID, "Unknown Watch Face Push slot '" + slotId + "'");
    }
    if (!isValidToken(validationToken)) {
      throw new UpdateException(
          UpdateException.ERROR_INVALID_VALIDATION_TOKEN,
          "Invalid Watch Face Push validation token");
    }
    Path staged = stageDescriptorToTemp(apkFd);
    if (staged == null) {
      throw new UpdateException(
          UpdateException.ERROR_MALFORMED_WATCHFACE_APK, "Null or unreadable watch face APK");
    }
    WffWatchFaces.ApkInspection inspection = WffWatchFaces.inspectApk(staged);
    if (!inspection.hasManifest || !inspection.hasWatchFaceFormat) {
      deleteQuietly(staged);
      throw new UpdateException(
          UpdateException.ERROR_MALFORMED_WATCHFACE_APK,
          "APK is missing AndroidManifest.xml or Watch Face Format declaration");
    }
    if (inspection.hasExecutableContent) {
      deleteQuietly(staged);
      throw new UpdateException(
          UpdateException.ERROR_UNEXPECTED_CONTENT,
          "Watch Face Format APK must not contain executable code ("
              + inspection.unexpectedEntry
              + ")");
    }
    if (!isValidPushPackageName(clientPkg, inspection.packageName)) {
      deleteQuietly(staged);
      throw new UpdateException(
          UpdateException.ERROR_INVALID_PACKAGE_NAME,
          "Watch face package '"
              + inspection.packageName
              + "' must start with '"
              + clientPkg
              + ".watchfacepush.'");
    }
    for (StoredSlot other : slots.values()) {
      if (!existing.slotId.equals(other.slotId)
          && inspection.packageName.equals(other.packageName)) {
        deleteQuietly(staged);
        throw new UpdateException(
            UpdateException.ERROR_INVALID_PACKAGE_NAME,
            "Watch face package '" + inspection.packageName + "' is already installed");
      }
    }

    String oldPackage = existing.packageName;
    boolean wasActive = isPackageActive(oldPackage);
    Application app = applicationOf(context);
    if (!oldPackage.equals(inspection.packageName)) {
      WffWatchFaces.uninstallPackage(app, oldPackage);
    }
    Path finalApk = moveToSlotPath(staged, slotId);
    WffWatchFaces.installDynamicApk(app, finalApk);
    List<DwfWatchFaceClient.Entry> compiled = compileOrExtractEntries(finalApk, inspection);

    existing.packageName = inspection.packageName;
    existing.versionCode = inspection.versionCode;
    existing.metaData = inspection.metaDataValues;
    existing.apkFile = finalApk;
    existing.compiledEntries = compiled;
    if (wasActive) {
      activePackageName = inspection.packageName;
    }

    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.onPushedSlotUpdated(oldPackage, existing, wasActive);
      host.emitPushEvent("update", existing);
    }
    return existing.toSdkSlot();
  }

  private synchronized void removeInternal(Context context, String slotId) throws RemoveException {
    String clientPkg = context.getPackageName();
    StoredSlot existing = slots.get(slotId);
    if (existing == null || !clientPkg.equals(existing.clientPackage)) {
      throw new RemoveException(
          RemoveException.ERROR_INVALID_SLOT_ID, "Unknown Watch Face Push slot '" + slotId + "'");
    }
    removeStoredSlot(applicationOf(context), existing);
  }

  private synchronized void setActiveInternal(Context context, String slotId)
      throws SetActiveException {
    String clientPkg = context.getPackageName();
    StoredSlot existing = slots.get(slotId);
    if (existing == null || !clientPkg.equals(existing.clientPackage)) {
      throw new SetActiveException(
          SetActiveException.ERROR_INVALID_SLOT_ID,
          "Unknown Watch Face Push slot '" + slotId + "'");
    }
    int maxAttempts = Integer.getInteger(MAX_SET_ACTIVE_PROPERTY, 1).intValue();
    if (setActiveAttempts >= maxAttempts) {
      throw new SetActiveException(
          SetActiveException.ERROR_MAXIMUM_ATTEMPTS_REACHED,
          "Maximum setWatchFaceAsActive attempts (" + maxAttempts + ") reached");
    }
    setActiveAttempts++;
    activePackageName = existing.packageName;
    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.activatePushedSlot(existing);
      host.emitPushEvent("setActive", existing);
    }
  }

  /** Control-channel direct push (`watchface op:push`) bypassing client package prefix restriction. */
  synchronized StoredSlot pushFromControl(
      Application app, Path apkPath, String validationToken, boolean activate) {
    if (!apkPath.toFile().isFile()) {
      throw new IllegalArgumentException("watch face APK does not exist: " + apkPath);
    }
    if (validationToken != null && !isValidToken(validationToken)) {
      throw new IllegalArgumentException("invalid validation token");
    }
    WffWatchFaces.ApkInspection inspection = WffWatchFaces.inspectApk(apkPath);
    if (!inspection.hasManifest || !inspection.hasWatchFaceFormat) {
      throw new IllegalArgumentException(
          "APK is missing AndroidManifest.xml or Watch Face Format declaration: " + apkPath);
    }
    if (inspection.hasExecutableContent) {
      throw new IllegalArgumentException(
          "Watch Face Format APK contains executable code: " + inspection.unexpectedEntry);
    }
    String slotId = "slot-" + nextSlotSeq.getAndIncrement();
    Path finalApk = copyToSlotPath(apkPath, slotId);
    WffWatchFaces.installDynamicApk(app, finalApk);
    List<DwfWatchFaceClient.Entry> compiled = compileOrExtractEntries(finalApk, inspection);
    StoredSlot stored =
        new StoredSlot(
            slotId,
            app.getPackageName(),
            inspection.packageName,
            inspection.versionCode,
            inspection.metaDataValues,
            finalApk,
            compiled);
    slots.put(slotId, stored);
    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.onPushedSlotUpdated(stored, activate);
      host.emitPushEvent("add", stored);
      if (activate) {
        activePackageName = stored.packageName;
        host.activatePushedSlot(stored);
        host.emitPushEvent("setActive", stored);
      }
    }
    return stored;
  }

  synchronized void removeFromControl(Application app, String slotOrPkg) {
    StoredSlot found = slots.get(slotOrPkg);
    if (found == null) {
      for (StoredSlot s : slots.values()) {
        if (s.packageName.equals(slotOrPkg)) {
          found = s;
          break;
        }
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("no pushed watch face slot '" + slotOrPkg + "'");
    }
    removeStoredSlot(app, found);
  }

  private void removeStoredSlot(Application app, StoredSlot existing) {
    slots.remove(existing.slotId);
    WffWatchFaces.uninstallPackage(app, existing.packageName);
    deleteQuietly(existing.apkFile);
    boolean wasActive = isPackageActive(existing.packageName);
    if (wasActive) {
      activePackageName = null;
    }
    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      host.onPushedSlotRemoved(existing, wasActive);
      host.emitPushEvent("remove", existing);
    }
  }

  synchronized JSONArray describeSlots() {
    JSONArray arr = new JSONArray();
    String currentActive = effectiveActivePackage();
    for (StoredSlot s : slots.values()) {
      arr.put(s.toJson(currentActive));
    }
    return arr;
  }

  private boolean isPackageActive(String packageName) {
    if (packageName == null || packageName.isEmpty()) return false;
    String current = effectiveActivePackage();
    return packageName.equals(current);
  }

  private String effectiveActivePackage() {
    WatchFaceHost host = WatchFaceHost.current();
    if (host != null) {
      String hostPkg = host.activeWatchFacePackage();
      if (hostPkg != null && !hostPkg.isEmpty()) return hostPkg;
    }
    return activePackageName;
  }

  private int countClientSlots(String clientPkg) {
    int count = 0;
    for (StoredSlot s : slots.values()) {
      if (clientPkg.equals(s.clientPackage)) count++;
    }
    return count;
  }

  private static int slotLimit() {
    return Math.max(1, Integer.getInteger(SLOT_LIMIT_PROPERTY, 1).intValue());
  }

  static boolean isValidPushPackageName(String clientPackage, String watchFacePackage) {
    if (clientPackage == null || watchFacePackage == null) return false;
    String prefix = clientPackage + ".watchfacepush.";
    return watchFacePackage.startsWith(prefix) && watchFacePackage.length() > prefix.length();
  }

  static boolean isValidToken(String token) {
    String policy =
        System.getProperty(TOKEN_POLICY_PROPERTY, "any").trim().toLowerCase(Locale.ROOT);
    if ("reject".equals(policy)) return false;
    if ("accept-all".equals(policy)) return token != null;
    if (token == null || token.trim().isEmpty()) return false;
    String lower = token.trim().toLowerCase(Locale.ROOT);
    if (lower.startsWith("invalid:") || "invalid".equals(lower)) return false;
    return true;
  }

  private List<DwfWatchFaceClient.Entry> compileOrExtractEntries(
      Path apkFile, WffWatchFaces.ApkInspection inspection) {
    String extractorCp = System.getProperty(EXTRACTOR_CP_PROPERTY, "");
    String javaBin =
        System.getProperty(
            DWF_JAVA_PROPERTY,
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    String roboCp = System.getProperty(DWF_ROBOLECTRIC_CP_PROPERTY, "");
    String cacheRoot =
        System.getProperty(
            DWF_CACHE_ROOT_PROPERTY,
            ensureStorageDir().resolve("compiled-cache").toAbsolutePath().toString());
    if (!extractorCp.isEmpty()) {
      try {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xmx512m");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(extractorCp);
        cmd.add(COMPILER_MAIN);
        cmd.add("--apk");
        cmd.add(apkFile.toAbsolutePath().toString());
        cmd.add("--java");
        cmd.add(javaBin);
        cmd.add("--robolectric-cp");
        cmd.add(roboCp);
        cmd.add("--cache-root");
        cmd.add(cacheRoot);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = p.waitFor();
        if (exit == 0 && !out.isEmpty()) {
          String[] lines = out.split("\\R");
          File compiledDir = new File(lines[lines.length - 1].trim());
          List<DwfWatchFaceClient.Entry> loaded = DwfWatchFaceClient.loadEntriesFromDir(compiledDir);
          if (!loaded.isEmpty()) return loaded;
        }
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      }
    }
    // Fallback for lightweight in-process tests where a pushed APK embeds a pre-compiled .rc or
    // compiled-manifest.json directly inside the archive.
    return extractEmbeddedEntriesIfPresent(apkFile, inspection.packageName);
  }

  private List<DwfWatchFaceClient.Entry> extractEmbeddedEntriesIfPresent(
      Path apkFile, String packageName) {
    try (ZipFile zip = new ZipFile(apkFile.toFile())) {
      ZipEntry manifestEntry = zip.getEntry("compiled-manifest.json");
      if (manifestEntry == null) return java.util.Collections.emptyList();
      Path unpackDir =
          ensureStorageDir().resolve("unpacked-" + packageName.replace('.', '_') + "-" + System.nanoTime());
      Files.createDirectories(unpackDir);
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        Path dest = unpackDir.resolve(entry.getName()).normalize();
        if (!dest.startsWith(unpackDir)) continue;
        Files.createDirectories(dest.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
          Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
      }
      return DwfWatchFaceClient.loadEntriesFromDir(unpackDir.toFile());
    } catch (IOException e) {
      return java.util.Collections.emptyList();
    }
  }

  private Path stageDescriptorToTemp(ParcelFileDescriptor apkFd) {
    if (apkFd == null) return null;
    try {
      Path dir = ensureStorageDir();
      Path temp = Files.createTempFile(dir, "push-stage-", ".apk");
      try (InputStream in = new FileInputStream(apkFd.getFileDescriptor())) {
        Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      if (Files.size(temp) == 0L) {
        deleteQuietly(temp);
        return null;
      }
      return temp;
    } catch (IOException e) {
      return null;
    }
  }

  private Path moveToSlotPath(Path staged, String slotId) {
    try {
      Path target = ensureStorageDir().resolve(slotId + ".apk");
      Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } catch (IOException e) {
      return staged;
    }
  }

  private Path copyToSlotPath(Path src, String slotId) {
    try {
      Path target = ensureStorageDir().resolve(slotId + ".apk");
      Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } catch (IOException e) {
      return src;
    }
  }

  private synchronized Path ensureStorageDir() {
    if (storageDir != null && Files.isDirectory(storageDir)) return storageDir;
    try {
      storageDir = Files.createTempDirectory("simulator-wf-push-");
      storageDir.toFile().deleteOnExit();
      return storageDir;
    } catch (IOException e) {
      throw new IllegalStateException("could not create watch face push storage directory", e);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static Application applicationOf(Context context) {
    if (context instanceof Application) return (Application) context;
    Context appCtx = context.getApplicationContext();
    return appCtx instanceof Application ? (Application) appCtx : null;
  }

  private static <R, E extends Throwable> void deliverResult(
      Executor executor, OutcomeReceiver<R, E> receiver, R result) {
    if (executor != null) {
      executor.execute(() -> receiver.onResult(result));
    } else {
      receiver.onResult(result);
    }
  }

  private static <R, E extends Throwable> void deliverError(
      Executor executor, OutcomeReceiver<R, E> receiver, E error) {
    if (executor != null) {
      executor.execute(() -> receiver.onError(error));
    } else {
      receiver.onError(error);
    }
  }
}
