package ee.schimke.composeai.simulator;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * The simulator's main thread, from another. In an instrumentation run the tests have a thread of
 * their own while the main thread keeps looping, as on a device; Robolectric's shadows assume the
 * test thread is the main one (its paused looper mode), so a framework call they handle off the
 * main thread asserts or reads state another thread owns. {@link #call} runs such a call where it
 * belongs.
 */
public final class MainThread {

  public static boolean isCurrent() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  /** [task]'s result, run on the main thread (here, when this is it); what it threw, rethrown. */
  public static <T> T call(Callable<T> task) throws Exception {
    if (isCurrent()) return task.call();
    CountDownLatch done = new CountDownLatch(1);
    Object[] result = new Object[1];
    Throwable[] thrown = new Throwable[1];
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                result[0] = task.call();
              } catch (Throwable t) {
                thrown[0] = t;
              } finally {
                done.countDown();
              }
            });
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for the main thread", e);
    }
    if (thrown[0] instanceof Exception e) throw e;
    if (thrown[0] instanceof Error e) throw e;
    if (thrown[0] != null) throw new RuntimeException(thrown[0]);
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }

  /** As {@link #call}, for a task that throws nothing checked. */
  public static <T> T get(Callable<T> task) {
    try {
      return call(task);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private MainThread() {}
}
