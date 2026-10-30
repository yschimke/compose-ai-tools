// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.tasks;

public abstract class Task<T> {
  public abstract boolean isSuccessful();

  public abstract T getResult();

  public abstract Exception getException();

  public abstract Task<T> addOnCompleteListener(OnCompleteListener<T> listener);
}
