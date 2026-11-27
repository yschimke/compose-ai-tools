package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The Wearable clients' tasks, over Play services' real {@code Task} classes: incomplete when the
 * app gets them, complete once the main looper (here a queue) has run.
 */
public class DeferredTasksTest {

  /** A main looper: what was posted, run when the test idles it. */
  private final ArrayDeque<Runnable> looper = new ArrayDeque<>();

  private final DeferredTasks tasks = new DeferredTasks(looper::add);

  private void idle() {
    while (!looper.isEmpty()) looper.poll().run();
  }

  @Test
  public void resultIsIncompleteOnReturnAndCompleteAfterTheLooperIdles() {
    Task<String> task = tasks.result("node");
    assertFalse(task.isComplete());
    idle();
    assertTrue(task.isComplete());
    assertTrue(task.isSuccessful());
    assertEquals("node", task.getResult());
  }

  @Test
  public void nullResultIsDeferredToo() {
    Task<Void> task = tasks.result(null);
    assertFalse(task.isComplete());
    idle();
    assertTrue(task.isSuccessful());
    assertNull(task.getResult());
  }

  @Test
  public void failureIsIncompleteOnReturnAndFailsAfterTheLooperIdles() {
    IllegalStateException error = new IllegalStateException("TARGET_NODE_NOT_CONNECTED");
    Task<Integer> task = tasks.failure(error);
    assertFalse(task.isComplete());
    idle();
    assertTrue(task.isComplete());
    assertFalse(task.isSuccessful());
    assertSame(error, task.getException());
  }

  @Test
  public void listenersAddedAfterTheCallRunOnTheLooperTurnNotInsideTheCall() {
    Task<String> task = tasks.result("x");
    List<String> seen = new ArrayList<>();
    task.addOnSuccessListener(Runnable::run, value -> seen.add("success " + value));
    assertTrue("nothing runs before the looper does", seen.isEmpty());
    idle();
    assertEquals(List.of("success x"), seen);
  }

  /** {@code sendRequest}'s answer arrives on the hub's reader thread; the task waits for main. */
  @Test
  public void anAnswerFromAnotherThreadCompletesOnTheLooper() throws InterruptedException {
    TaskCompletionSource<byte[]> response = new TaskCompletionSource<>();
    CountDownLatch answered = new CountDownLatch(1);
    Thread reader =
        new Thread(
            () -> {
              tasks.succeed(response, new byte[] {6});
              answered.countDown();
            });
    reader.start();
    assertTrue(answered.await(5, TimeUnit.SECONDS));
    reader.join();
    assertFalse(response.getTask().isComplete());
    idle();
    assertTrue(response.getTask().isComplete());
    assertEquals(6, response.getTask().getResult()[0]);
  }
}
