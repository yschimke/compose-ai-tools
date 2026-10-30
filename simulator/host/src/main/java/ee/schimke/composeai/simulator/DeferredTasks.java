package ee.schimke.composeai.simulator;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.concurrent.Executor;

/**
 * How the simulator's Wearable clients complete the {@code Task}s they hand the app: as Play
 * services does, whose answer comes back over a binder and is posted to the main thread. A task is
 * never complete when the app gets it, and it completes on the main looper, whatever thread the
 * answer arrived on (the hub's reader, a timer, a channel's writer). Code that awaits a task and
 * expects to suspend first (Horologist's {@code DataLayerViewModel} init, {@code
 * addOnSuccessListener} registered after the call) misbehaves when it is already complete.
 *
 * <p>[main] is the main looper's {@code Handler::post} in the simulator; the tests drain a queue.
 */
final class DeferredTasks {

  private final Executor main;

  DeferredTasks(Executor main) {
    this.main = main;
  }

  /** A task that succeeds with [value] on the main looper's next turn. */
  <T> Task<T> result(T value) {
    TaskCompletionSource<T> source = new TaskCompletionSource<>();
    succeed(source, value);
    return source.getTask();
  }

  /** A task that fails with [error] on the main looper's next turn. */
  <T> Task<T> failure(Exception error) {
    TaskCompletionSource<T> source = new TaskCompletionSource<>();
    fail(source, error);
    return source.getTask();
  }

  /** Completes [source] with [value] on the main looper. */
  <T> void succeed(TaskCompletionSource<T> source, T value) {
    main.execute(() -> source.trySetResult(value));
  }

  /** Fails [source] with [error] on the main looper. */
  <T> void fail(TaskCompletionSource<T> source, Exception error) {
    main.execute(() -> source.trySetException(error));
  }
}
