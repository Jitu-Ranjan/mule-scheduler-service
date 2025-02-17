/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.scheduler.internal;

import org.mule.runtime.api.profiling.ProfilingService;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.service.scheduler.ThreadType;
import org.mule.service.scheduler.internal.executor.ByCallerThrottlingPolicy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Extension of {@link DefaultScheduler} that has a limit on the tasks that can be run at the same time.
 * <p>
 * Exceeding tasks will block the caller, until a running task is finished.
 *
 * @since 1.0
 */
public class ThrottledScheduler extends DefaultScheduler {

  private final ByCallerThrottlingPolicy thottlingPolicy;

  /**
   * @param name                  the name of this scheduler
   * @param executor              the actual executor that will run the dispatched tasks.
   * @param parallelTasksEstimate an estimate of how many threads will be, at maximum, in the underlying executor
   * @param scheduledExecutor     the executor that will handle the delayed/periodic tasks. This will not execute the actual
   *                              tasks, but will dispatch it to the {@code executor} at the appropriate time.
   * @param quartzScheduler       the quartz object that will handle tasks scheduled with cron expressions. This will not execute
   *                              the actual tasks, but will dispatch it to the {@code executor} at the appropriate time.
   * @param threadsType           The {@link ThreadType} that matches with the {@link Thread}s managed by this {@link Scheduler}.
   * @param throttingPolicy       the action to perform when too many tasks are running at the same time for this
   *                              {@link Scheduler}.
   * @param shutdownTimeoutMillis the time in millis to wait for the graceful stop of this scheduler
   * @param shutdownCallback      a callback to be invoked when this scheduler is stopped/shutdown.
   */
  public ThrottledScheduler(String name, ExecutorService executor, int parallelTasksEstimate,
                            ScheduledExecutorService scheduledExecutor, org.quartz.Scheduler quartzScheduler,
                            ThreadType threadsType, ByCallerThrottlingPolicy throttingPolicy,
                            Supplier<Long> shutdownTimeoutMillis, Consumer<Scheduler> shutdownCallback,
                            ProfilingService profilingService) {
    super(name, executor, parallelTasksEstimate, scheduledExecutor, quartzScheduler, threadsType, shutdownTimeoutMillis,
          shutdownCallback, profilingService);
    thottlingPolicy = throttingPolicy;
  }

  @Override
  protected void putTask(RunnableFuture<?> task, ScheduledFuture<?> scheduledFuture) {
    if (scheduledFuture instanceof NullScheduledFuture) {
      thottlingPolicy.throttle(() -> super.putTask(task, scheduledFuture), task, this);
    }
  }

  @Override
  protected ScheduledFuture<?> removeTask(RunnableFuture<?> task) {
    ScheduledFuture<?> removedTask = super.removeTask(task);
    if (removedTask != null) {
      thottlingPolicy.throttleWrapUp();
    }
    return removedTask;
  }

  @Override
  protected <T> Runnable schedulableTask(RunnableFuture<T> task, Runnable rejectionCallback) {
    return () -> thottlingPolicy.throttle(() -> {
      super.schedulableTask(task, rejectionCallback).run();
      // non immediate tasks do not manage the throttling policy in the putTask/removeTask methods.
      thottlingPolicy.throttleWrapUp();
    }, task, this);
  }

  @Override
  public String toString() {
    return super.toString() + " " + thottlingPolicy.toString();
  }
}
