package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's {@code JobScheduler} jobs run. Robolectric records a scheduled job and never starts
 * it. Each cycle a job whose latency has passed and whose constraints the device meets (a network,
 * an unmetered one, charging, idle: {@link WorkTiming}), or whose deadline has passed, starts as
 * the system starts it: its {@code JobService} is created and bound, and {@code startJob} goes
 * through the service's own {@code JobServiceEngine}, so {@code onStartJob}, {@code jobFinished}
 * and {@code onStopJob} behave as on a device. A one-off job leaves the schedule when it starts; a
 * periodic one runs again an interval later. Starts and finishes are {@code job} events.
 *
 * <p>WorkManager's own {@code SystemJobService} is left alone: in the simulator WorkManager
 * already runs its work in-process ({@link SimulatorTestEnvironment}), and running its job too
 * would start the same work twice. {@code jobs} lists every job and why it waits; {@code run}
 * starts one now.
 */
final class Jobs {

  static final String WORK_MANAGER_SERVICE =
      "androidx.work.impl.background.systemjob.SystemJobService";

  private final Application app;
  private final Protocol protocol;

  /** When each pending job was scheduled, by id, with the {@code JobInfo} seen then. */
  private final Map<Integer, Seen> seen = new HashMap<>();

  private static final class Seen {
    final JobInfo info;
    long at;

    Seen(JobInfo info, long at) {
      this.info = info;
      this.at = at;
    }
  }

  Jobs(Application app, Protocol protocol) {
    this.app = app;
    this.protocol = protocol;
  }

  /** Every job scheduled through any context, with the scheduler that holds it. */
  private static Map<JobInfo, JobScheduler> pending() {
    Map<JobInfo, JobScheduler> all = new java.util.LinkedHashMap<>();
    for (SimulatorShadowJobScheduler owner : SimulatorShadowJobScheduler.all()) {
      for (JobInfo info : owner.getAllPendingJobs()) all.put(info, owner.real());
    }
    return all;
  }

  void cycle() {
    long now = SystemClock.elapsedRealtime();
    Map<JobInfo, JobScheduler> owners = pending();
    List<JobInfo> pending = new java.util.ArrayList<>(owners.keySet());
    seen.keySet().retainAll(ids(pending));
    WorkTiming.DeviceState device = null;
    for (JobInfo info : pending) {
      Seen record = seen.get(info.getId());
      if (record == null || record.info != info) {
        // New, or scheduled again under the same id: the clock starts over.
        seen.put(info.getId(), new Seen(info, now));
        continue;
      }
      if (isWorkManagers(info)) continue;
      if (device == null) device = device();
      if (WorkTiming.waitingFor(needs(info), device, record.at, now) != null) continue;
      start(info, owners.get(info), "due");
      record.at = now;
    }
  }

  private static java.util.Set<Integer> ids(List<JobInfo> jobs) {
    java.util.Set<Integer> ids = new java.util.HashSet<>();
    for (JobInfo info : jobs) ids.add(info.getId());
    return ids;
  }

  private static boolean isWorkManagers(JobInfo info) {
    return info.getService() != null
        && WORK_MANAGER_SERVICE.equals(info.getService().getClassName());
  }

  private static WorkTiming.JobNeeds needs(JobInfo info) {
    WorkTiming.JobNeeds needs = new WorkTiming.JobNeeds();
    needs.minLatencyMs = info.getMinLatencyMillis();
    needs.deadlineMs = info.getMaxExecutionDelayMillis();
    int network = info.getNetworkType();
    needs.network = network != JobInfo.NETWORK_TYPE_NONE;
    needs.unmetered = network == JobInfo.NETWORK_TYPE_UNMETERED;
    needs.charging = info.isRequireCharging();
    needs.idle = info.isRequireDeviceIdle();
    needs.periodic = info.isPeriodic();
    needs.intervalMs = info.getIntervalMillis();
    return needs;
  }

  private WorkTiming.DeviceState device() {
    WorkTiming.DeviceState device = new WorkTiming.DeviceState();
    ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
    device.online = connectivity.getActiveNetwork() != null;
    device.metered = connectivity.isActiveNetworkMetered();
    device.charging = app.getSystemService(BatteryManager.class).isCharging();
    device.idle = app.getSystemService(PowerManager.class).isDeviceIdleMode();
    return device;
  }

  /** Starts [info]'s job through its service's {@code JobServiceEngine}. */
  private JSONObject start(JobInfo info, JobScheduler owner, String why) {
    ComponentName component = info.getService();
    Protocol.Json event =
        new Protocol.Json()
            .put("state", "started")
            .put("why", why)
            .put("jobId", info.getId())
            .put("service", component.getClassName());
    if (!info.isPeriodic()) owner.cancel(info.getId());
    try {
      Service service = Navigator.serviceFor(component);
      if (!(service instanceof JobService)) {
        throw new IllegalStateException(component.getClassName() + " is not a JobService");
      }
      // JobService.onBind creates its engine; startJob through it reaches onStartJob on the main
      // thread, and the engine reports back to the callback below.
      IBinder engine = service.onBind(new Intent().setComponent(component));
      Object parameters = parameters(info, new Callback(info.getId(), component));
      Class<?> stub = Class.forName("android.app.job.IJobService$Stub");
      Object jobService = stub.getMethod("asInterface", IBinder.class).invoke(null, engine);
      Method startJob = null;
      for (Method method : jobService.getClass().getMethods()) {
        if (method.getName().equals("startJob")) startJob = method;
      }
      if (startJob == null) throw new IllegalStateException("IJobService has no startJob");
      startJob.invoke(jobService, parameters);
    } catch (ReflectiveOperationException | RuntimeException e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      event.put("state", "failed").put("error", String.valueOf(cause));
    }
    JSONObject started = event.object();
    protocol.event("job", started);
    return started;
  }

  /**
   * A {@code JobParameters} for [info], built through its hidden constructor, whose parameters
   * differ by API level: they are filled by type, the first {@code int} being the job id.
   */
  private static Object parameters(JobInfo info, IBinder callback)
      throws ReflectiveOperationException {
    Class<?> type = android.app.job.JobParameters.class;
    Constructor<?> constructor = null;
    for (Constructor<?> candidate : type.getDeclaredConstructors()) {
      Class<?>[] types = candidate.getParameterTypes();
      if (types.length > 0 && types[0] == IBinder.class) constructor = candidate;
    }
    if (constructor == null) throw new IllegalStateException("no JobParameters constructor");
    Class<?>[] types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    boolean idGiven = false;
    for (int i = 0; i < types.length; i++) {
      Class<?> t = types[i];
      if (t == IBinder.class) args[i] = callback;
      else if (t == int.class) {
        args[i] = idGiven ? 0 : info.getId();
        idGiven = true;
      } else if (t == boolean.class) args[i] = false;
      else if (t == long.class) args[i] = 0L;
      else if (t == android.os.PersistableBundle.class) args[i] = info.getExtras();
      else if (t == android.os.Bundle.class) args[i] = info.getTransientExtras();
      else if (t == android.content.ClipData.class) args[i] = info.getClipData();
      else args[i] = null;
    }
    constructor.setAccessible(true);
    return constructor.newInstance(args);
  }

  /**
   * The system's end of {@code IJobCallback}: answers every call as the service expects (no work
   * to dequeue) and reports the job finishing, which the engine says through {@code
   * acknowledgeStartMessage} (no work ongoing) or {@code jobFinished}.
   */
  private final class Callback extends Binder {
    private final int jobId;
    private final ComponentName component;

    Callback(int jobId, ComponentName component) {
      this.jobId = jobId;
      this.component = component;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
      if (code == transaction("acknowledgeStartMessage")) {
        data.setDataPosition(0);
        skipInterface(data);
        data.readInt();
        boolean ongoing = data.readInt() != 0;
        if (!ongoing) finished(false);
      } else if (code == transaction("jobFinished")) {
        data.setDataPosition(0);
        skipInterface(data);
        data.readInt();
        finished(data.readInt() != 0);
      }
      if (reply != null) {
        reply.writeNoException();
        // dequeueWork's null JobWorkItem; completeWork's false.
        reply.writeInt(0);
      }
      return true;
    }

    private void finished(boolean reschedule) {
      protocol.event(
          "job",
          new Protocol.Json()
              .put("state", "finished")
              .put("jobId", jobId)
              .put("service", component.getClassName())
              .put("wantsReschedule", reschedule)
              .object());
    }
  }

  private static void skipInterface(Parcel data) {
    try {
      data.enforceInterface("android.app.job.IJobCallback");
    } catch (SecurityException e) {
      // A layout this API level writes differently; the ints that follow are read regardless.
    }
  }

  /** The AIDL transaction code of IJobCallback's [method], or -1 on a level without it. */
  private static int transaction(String method) {
    try {
      java.lang.reflect.Field field =
          Class.forName("android.app.job.IJobCallback$Stub")
              .getDeclaredField("TRANSACTION_" + method);
      field.setAccessible(true);
      return field.getInt(null);
    } catch (ReflectiveOperationException e) {
      return -1;
    }
  }

  /** The {@code jobs} command. */
  JSONObject command(JSONObject c) throws JSONException {
    if (c.has("run")) {
      int id = c.getInt("run");
      for (Map.Entry<JobInfo, JobScheduler> e : pending().entrySet()) {
        if (e.getKey().getId() == id) return start(e.getKey(), e.getValue(), "command");
      }
      throw new IllegalArgumentException("no job " + id + " is scheduled");
    }
    long now = SystemClock.elapsedRealtime();
    WorkTiming.DeviceState device = device();
    JSONArray list = new JSONArray();
    for (JobInfo info : pending().keySet()) {
      Seen record = seen.get(info.getId());
      long at = record == null ? now : record.at;
      WorkTiming.JobNeeds needs = needs(info);
      String waiting =
          isWorkManagers(info) ? "WorkManager runs it in-process" : WorkTiming.waitingFor(needs, device, at, now);
      list.put(
          new Protocol.Json()
              .put("jobId", info.getId())
              .put("service", info.getService().getClassName())
              .put("periodic", needs.periodic)
              .put("intervalMs", needs.periodic ? needs.intervalMs : null)
              .put("minLatencyMs", needs.minLatencyMs)
              .put("deadlineMs", needs.deadlineMs > 0 ? needs.deadlineMs : null)
              .put("network", needs.network)
              .put("charging", needs.charging)
              .put("idle", needs.idle)
              .put("scheduledMsAgo", now - at)
              .put("waitingFor", waiting)
              .object());
    }
    return new Protocol.Json().put("jobs", list).object();
  }
}
