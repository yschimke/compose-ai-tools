// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.tasks;

public class TaskCompletionSource<T> {
  public TaskCompletionSource() {}

  public Task<T> getTask() {
    throw new UnsupportedOperationException();
  }

  public boolean trySetResult(T result) {
    throw new UnsupportedOperationException();
  }

  public boolean trySetException(Exception e) {
    throw new UnsupportedOperationException();
  }
}
