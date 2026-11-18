package com.overhand.app.features;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Primitive reads keep reflection's boxed Long/argument-array allocations out of the interval. */
final class AllocationCounter {
  private final MethodHandle read;

  AllocationCounter() {
    try {
      Class<?> api = Class.forName("com.sun.management.ThreadMXBean");
      Object bean = Class.forName("java.lang.management.ManagementFactory")
          .getMethod("getThreadMXBean").invoke(null);
      api.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
      read = MethodHandles.lookup().unreflect(api.getMethod("getCurrentThreadAllocatedBytes"))
          .bindTo(bean);
      for (int i = 0; i < 100_000; i++) bytes();
    } catch (Throwable error) {
      throw new AssertionError(error);
    }
  }

  long bytes() {
    try {
      return (long) read.invokeExact();
    } catch (Throwable error) {
      throw new AssertionError(error);
    }
  }
}
